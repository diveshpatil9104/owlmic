package com.owlmic.core.link

import com.owlmic.core.fromBase64
import com.owlmic.core.hexToBytes
import com.owlmic.core.hub.LinkKind
import com.owlmic.core.proto.Crypto
import com.owlmic.core.proto.Hello
import com.owlmic.core.proto.HelloAck
import com.owlmic.core.proto.Pending
import com.owlmic.core.proto.Ping
import com.owlmic.core.proto.Pong
import com.owlmic.core.proto.Proof
import com.owlmic.core.proto.Proto
import com.owlmic.core.proto.Reject
import com.owlmic.core.proto.RejectReason
import com.owlmic.core.proto.Welcome
import com.owlmic.core.toBase64
import com.owlmic.core.toHex
import java.io.IOException

/** What the phone remembers of a paired PC, to recognise it and to refuse an impostor with a different key. */
class PcKey(val staticPub: ByteArray, val pairingKey: ByteArray)

/** The phone's side of the handshake (protocol section 4 and 5). Runs on a connection thread. */
class Handshake(
    private val identity: Crypto.KeyPair,
    private val phoneId: ByteArray,
    private val phoneName: String,
    private val model: String,
    private val remembered: (pcId: String) -> PcKey?,
) {
    sealed interface Result {
        class Welcomed(
            val pcId: String,
            val pcName: String,
            val pcStaticPub: ByteArray,
            val pairingKey: ByteArray,
            val keys: Crypto.SessionKeys,
            val sessionId: ByteArray,
            val settings: Map<String, String>,
            val caps: List<String>,
            val btAddr: String?,
            /** True the first time: the PC approved this phone during this handshake. */
            val approvedNow: Boolean,
        ) : Result

        data class Rejected(val reason: RejectReason, val owner: String?, val pcId: String?, val pcName: String?, val pcProto: Int?) : Result

        data class Failed(val why: String) : Result
    }

    /**
     * Runs the whole handshake on [channel]. [deadline] closes the transport if the PC goes quiet: 3 s for each step,
     * 2 minutes while the PC waits for the user to approve.
     */
    fun run(channel: ControlChannel, link: LinkKind, resume: String?, deadline: Watchdog.Deadline, onPending: (pcName: String, code: String) -> Unit): Result {
        val eph = Crypto.generate()
        val nonce = Crypto.randomBytes(32)
        return try {
            deadline.arm(STEP_MS)
            val hello = Hello(Proto.VERSION, phoneId.toHex(), phoneName, model, identity.public.toBase64(), eph.public.toBase64(), nonce.toBase64(), link.wire, resume)
            val helloBytes = channel.send(hello)

            var ack: HelloAck? = null
            var ackBytes = ByteArray(0)
            while (ack == null) {
                val incoming = channel.read() as? Incoming.Control ?: continue
                when (val m = incoming.message) {
                    is HelloAck -> {
                        ack = m
                        ackBytes = incoming.payload
                    }
                    is Reject -> return Result.Rejected(m.reason, m.owner, null, null, null)
                    else -> Unit
                }
            }
            val pcId = ack.pcId
            val pcIdBytes = pcId.hexToBytes()
            if (pcIdBytes.size != 16) return Result.Failed("bad PC id")
            if (ack.proto != Proto.VERSION) return Result.Rejected(RejectReason.VERSION, null, pcId, ack.name, ack.proto)
            val pcStatic = ack.staticPub.fromBase64() ?: return Result.Failed("bad PC key")
            val pcEph = ack.ephPub.fromBase64() ?: return Result.Failed("bad PC key")
            val pcNonce = ack.nonce.fromBase64()?.takeIf { it.size == 32 } ?: return Result.Failed("bad PC nonce")
            val known = remembered(pcId)
            if (known != null && !known.staticPub.contentEquals(pcStatic)) return Result.Failed("this PC's key changed")

            val staticShared = identity.agree(pcStatic) ?: return Result.Failed("bad PC key")
            val pairingKey = Crypto.pairingKey(staticShared, phoneId, pcIdBytes)
            val transcript = Crypto.transcript(helloBytes, ackBytes)
            val ephShared = eph.agree(pcEph) ?: return Result.Failed("bad PC key")
            val keys = Crypto.sessionKeys(Crypto.sessionMaster(pairingKey, ephShared, nonce, pcNonce))
            channel.send(Proof(Crypto.proof(keys.auth, Crypto.Role.PHONE, transcript).toBase64()))
            deadline.arm(STEP_MS)

            awaitOutcome(channel, deadline, onPending, ack, pairingKey, transcript, keys, pcStatic)
        } catch (e: IOException) {
            deadline.cancel()
            Result.Failed(e.message ?: "connection closed")
        }
    }

    /** After PROOF: an optional PENDING while the user approves, then WELCOME or REJECT. */
    private fun awaitOutcome(
        channel: ControlChannel,
        deadline: Watchdog.Deadline,
        onPending: (pcName: String, code: String) -> Unit,
        ack: HelloAck,
        pairingKey: ByteArray,
        transcript: ByteArray,
        keys: Crypto.SessionKeys,
        pcStatic: ByteArray,
    ): Result {
        val code = Crypto.approvalCode(pairingKey, transcript)
        var approvedNow = false
        while (true) {
            val incoming = channel.read() as? Incoming.Control ?: continue
            when (val m = incoming.message) {
                is Pending -> {
                    // The PC's code must be the one we derive; anything else means someone is in the middle.
                    if (m.code != code) return Result.Failed("approval code mismatch")
                    approvedNow = true
                    deadline.arm(APPROVAL_MS)
                    onPending(ack.name, code)
                }
                is Welcome -> {
                    deadline.cancel()
                    val mac = m.mac.fromBase64() ?: return Result.Failed("bad PC proof")
                    if (!Crypto.proofMatches(keys.auth, Crypto.Role.PC, transcript, mac)) return Result.Failed("PC proof failed")
                    val sessionId = runCatching { m.sessionId.hexToBytes() }.getOrNull()?.takeIf { it.size == 16 }
                        ?: return Result.Failed("bad session id")
                    return Result.Welcomed(ack.pcId, ack.name, pcStatic, pairingKey, keys, sessionId, m.settings, m.caps, ack.btAddr, approvedNow)
                }
                is Reject -> return Result.Rejected(m.reason, m.owner, ack.pcId, ack.name, ack.proto)
                is Ping -> channel.send(Pong(m.t))
                else -> Unit
            }
        }
    }

    companion object {
        const val STEP_MS = 3_000L
        const val APPROVAL_MS = 120_000L
    }
}
