package com.owlmic.core.proto

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Keys, proofs and packet sealing (protocol/README.md, sections 5 and 6), with the platform's own crypto. */
object Crypto {
    /** Stream byte used in the nonce of encrypted control frames. */
    const val CONTROL_STREAM = 0xFF

    private val p256: ECParameterSpec by lazy {
        AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }.getParameterSpec(ECParameterSpec::class.java)
    }
    private val random = SecureRandom()

    /** A P-256 key pair; [public] is the 65-byte uncompressed point. */
    class KeyPair(val private: PrivateKey, val public: ByteArray) {
        /** ECDH: the x-coordinate of the shared point, or null for a key that isn't on the curve. */
        fun agree(peerPublic: ByteArray): ByteArray? = runCatching {
            KeyAgreement.getInstance("ECDH").run {
                init(private)
                doPhase(publicKey(peerPublic), true)
                generateSecret()
            }
        }.getOrNull()
    }

    fun generate(): KeyPair {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }.generateKeyPair()
        return KeyPair(pair.private, encodePoint((pair.public as ECPublicKey).w))
    }

    /** Restores a stored pair from its 32-byte private scalar and 65-byte public point. */
    fun restore(privateScalar: ByteArray, public: ByteArray): KeyPair =
        KeyPair(KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(1, privateScalar), p256)), public)

    /** The 32-byte private scalar, for storing the pair. */
    fun privateScalar(key: PrivateKey): ByteArray = unsigned32((key as java.security.interfaces.ECPrivateKey).s)

    fun randomBytes(n: Int) = ByteArray(n).also { random.nextBytes(it) }

    fun keyHint(public: ByteArray): ByteArray = sha256(public).copyOf(8)

    fun pairingKey(staticShared: ByteArray, phoneId: ByteArray, pcId: ByteArray) =
        hkdf("owlmic pair v3".toByteArray(), staticShared, phoneId + pcId)

    fun transcript(helloPayload: ByteArray, helloAckPayload: ByteArray) = sha256(helloPayload + helloAckPayload)

    fun sessionMaster(pairingKey: ByteArray, ephemeralShared: ByteArray, phoneNonce: ByteArray, pcNonce: ByteArray) =
        hkdf(phoneNonce + pcNonce, pairingKey + ephemeralShared, "owlmic session v3".toByteArray())

    class SessionKeys(val phoneToPc: ByteArray, val pcToPhone: ByteArray, val auth: ByteArray)

    fun sessionKeys(master: ByteArray) =
        SessionKeys(expand(master, "phone->pc".toByteArray()), expand(master, "pc->phone".toByteArray()), expand(master, "auth".toByteArray()))

    enum class Role(val label: String) { PHONE("phone"), PC("pc") }

    fun proof(auth: ByteArray, role: Role, transcript: ByteArray) = hmac(auth, role.label.toByteArray() + transcript)

    /** Constant-time check of a peer's proof. */
    fun proofMatches(auth: ByteArray, role: Role, transcript: ByteArray, mac: ByteArray) =
        MessageDigest.isEqual(proof(auth, role, transcript), mac)

    fun approvalCode(pairingKey: ByteArray, transcript: ByteArray): String {
        val mac = hmac(pairingKey, "code".toByteArray() + transcript)
        val n = ((mac[0].toLong() and 0xFF) shl 24) or ((mac[1].toLong() and 0xFF) shl 16) or
            ((mac[2].toLong() and 0xFF) shl 8) or (mac[3].toLong() and 0xFF)
        return "%04d".format(n % 10_000)
    }

    fun carrierMac(auth: ByteArray, sessionId: ByteArray): ByteArray = hmac(auth, "carrier".toByteArray() + sessionId).copyOf(16)

    /** AES-256-GCM for one direction of a session. */
    class Sealing(key: ByteArray) {
        private val key = SecretKeySpec(key, "AES")

        /** [plaintext] sealed with its 16-byte tag appended. [counter] is the packet's seq, or the control counter. */
        fun seal(stream: Int, counter: Long, aad: ByteArray, plaintext: ByteArray): ByteArray = cipher(Cipher.ENCRYPT_MODE, stream, counter, aad)
            .doFinal(plaintext)

        fun open(stream: Int, counter: Long, aad: ByteArray, sealed: ByteArray): ByteArray? =
            runCatching { cipher(Cipher.DECRYPT_MODE, stream, counter, aad).doFinal(sealed) }.getOrNull()

        private fun cipher(mode: Int, stream: Int, counter: Long, aad: ByteArray) = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, GCMParameterSpec(128, nonce(stream, counter)))
            updateAAD(aad)
        }
    }

    /** Accepts each seq once and nothing older than the last 64. */
    class ReplayWindow {
        private var highest: Long = -1
        private var seen = 0L

        /** True the first time a seq inside the window is offered; it is then remembered. */
        fun accept(seq: Long): Boolean {
            if (highest < 0) {
                highest = seq
                seen = 1
                return true
            }
            val ahead = (seq - highest) and 0xFFFFFFFFL
            if (ahead != 0L && ahead < (1L shl 31)) {
                seen = if (ahead >= 64) 0 else seen shl ahead.toInt()
                seen = seen or 1
                highest = seq
                return true
            }
            val behind = (highest - seq) and 0xFFFFFFFFL
            if (behind >= 64 || seen and (1L shl behind.toInt()) != 0L) return false
            seen = seen or (1L shl behind.toInt())
            return true
        }
    }

    internal fun nonce(stream: Int, counter: Long) = ByteArray(12).also {
        it[0] = stream.toByte()
        for (i in 0 until 8) it[4 + i] = (counter shr (56 - 8 * i)).toByte()
    }

    private fun publicKey(point: ByteArray): java.security.PublicKey {
        require(point.size == 65 && point[0].toInt() == 4) { "not an uncompressed P-256 point" }
        val spec = ECPublicKeySpec(ECPoint(BigInteger(1, point.copyOfRange(1, 33)), BigInteger(1, point.copyOfRange(33, 65))), p256)
        return KeyFactory.getInstance("EC").generatePublic(spec)
    }

    private fun encodePoint(w: ECPoint) = byteArrayOf(4) + unsigned32(w.affineX) + unsigned32(w.affineY)

    private fun unsigned32(n: BigInteger): ByteArray {
        val b = n.toByteArray()
        return when {
            b.size == 32 -> b
            b.size > 32 -> b.copyOfRange(b.size - 32, b.size)
            else -> ByteArray(32 - b.size) + b
        }
    }

    private fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }

    /** HKDF-SHA256 (RFC 5869) with a 32-byte output. */
    private fun hkdf(salt: ByteArray, ikm: ByteArray, info: ByteArray) = expand(hmac(salt, ikm), info)

    /** HKDF-Expand for 32 bytes: a single block. */
    private fun expand(prk: ByteArray, info: ByteArray) = hmac(prk, info + byteArrayOf(1))
}
