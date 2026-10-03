package com.owlmic.core.proto

import com.owlmic.core.proto.Vectors.hex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CryptoTest {
    private val v = Vectors.load("crypto.json")
    private val e = v.getJSONObject("expected")

    private fun pair(name: String) = v.getJSONObject(name).let { Crypto.restore(hex(it.getString("private")), hex(it.getString("public"))) }

    @Test
    fun everyDerivedValueMatchesTheIndependentVectors() {
        val phone = pair("phoneStatic")
        val pc = pair("pcStatic")
        assertArrayEquals(hex(e.getString("pcKeyHint")), Crypto.keyHint(pc.public))
        assertArrayEquals(hex(v.getJSONObject("phoneStatic").getString("private")), Crypto.privateScalar(phone.private))

        val staticShared = phone.agree(pc.public)!!
        assertArrayEquals(staticShared, pc.agree(phone.public))
        assertArrayEquals(hex(e.getString("staticShared")), staticShared)
        val k = Crypto.pairingKey(staticShared, hex(v.getString("phoneId")), hex(v.getString("pcId")))
        assertArrayEquals(hex(e.getString("pairingKey")), k)

        val t = Crypto.transcript(v.getString("helloPayload").toByteArray(), v.getString("helloAckPayload").toByteArray())
        assertArrayEquals(hex(e.getString("transcript")), t)
        val eph = pair("phoneEphemeral").agree(pair("pcEphemeral").public)!!
        assertArrayEquals(hex(e.getString("ephemeralShared")), eph)
        val master = Crypto.sessionMaster(k, eph, hex(v.getString("phoneNonce")), hex(v.getString("pcNonce")))
        assertArrayEquals(hex(e.getString("sessionMaster")), master)
        val keys = Crypto.sessionKeys(master)
        assertArrayEquals(hex(e.getString("phoneToPc")), keys.phoneToPc)
        assertArrayEquals(hex(e.getString("pcToPhone")), keys.pcToPhone)
        assertArrayEquals(hex(e.getString("auth")), keys.auth)

        assertArrayEquals(hex(e.getString("proofPhone")), Crypto.proof(keys.auth, Crypto.Role.PHONE, t))
        assertArrayEquals(hex(e.getString("proofPc")), Crypto.proof(keys.auth, Crypto.Role.PC, t))
        assertTrue(Crypto.proofMatches(keys.auth, Crypto.Role.PC, t, hex(e.getString("proofPc"))))
        assertFalse(Crypto.proofMatches(keys.auth, Crypto.Role.PHONE, t, hex(e.getString("proofPc"))))
        assertEquals(e.getString("approvalCode"), Crypto.approvalCode(k, t))
        assertArrayEquals(hex(e.getString("carrierMac")), Crypto.carrierMac(keys.auth, hex(e.getString("sessionId"))))
    }

    @Test
    fun sealingMatchesTheIndependentVectors() {
        val cases = v.getJSONArray("aead")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val sealing = Crypto.Sealing(hex(e.getString(c.getString("key"))))
            val stream = c.getInt("stream")
            val counter = c.getLong("counter")
            val aad = hex(c.getString("headerHex"))
            val plain = hex(c.getString("plaintextHex"))
            assertArrayEquals(hex(c.getString("nonceHex")), Crypto.nonce(stream, counter))
            val sealed = sealing.seal(stream, counter, aad, plain)
            assertArrayEquals(c.getString("name"), hex(c.getString("sealedHex")), sealed)
            assertArrayEquals(plain, sealing.open(stream, counter, aad, sealed))
            assertNull(sealing.open(stream, counter + 1, aad, sealed))
        }
    }

    @Test
    fun generatedKeysAgreeAndRestore() {
        val a = Crypto.generate()
        val b = Crypto.generate()
        assertArrayEquals(a.agree(b.public), b.agree(a.public))
        val restored = Crypto.restore(Crypto.privateScalar(a.private), a.public)
        assertArrayEquals(a.agree(b.public), restored.agree(b.public))
        assertNull(a.agree(ByteArray(65) { 4 }))
    }

    @Test
    fun replayWindowAcceptsEachSeqOnceAndNothingTooOld() {
        val w = Crypto.ReplayWindow()
        assertTrue(w.accept(100)); assertFalse(w.accept(100))
        assertTrue(w.accept(103)); assertTrue(w.accept(101)); assertFalse(w.accept(101)); assertTrue(w.accept(102))
        assertTrue(w.accept(200)); assertFalse(w.accept(136)); assertTrue(w.accept(137))
        val wrap = Crypto.ReplayWindow()
        assertTrue(wrap.accept(0xFFFFFFFFL)); assertTrue(wrap.accept(0)); assertFalse(wrap.accept(0xFFFFFFFFL))
    }
}
