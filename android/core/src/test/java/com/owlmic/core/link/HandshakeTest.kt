package com.owlmic.core.link

import com.owlmic.core.fromBase64
import com.owlmic.core.hexToBytes
import com.owlmic.core.hub.LinkKind
import com.owlmic.core.proto.AckStatus
import com.owlmic.core.proto.Crypto
import com.owlmic.core.proto.Hello
import com.owlmic.core.proto.HelloAck
import com.owlmic.core.proto.Pending
import com.owlmic.core.proto.Proof
import com.owlmic.core.proto.Proto
import com.owlmic.core.proto.Reject
import com.owlmic.core.proto.RejectReason
import com.owlmic.core.proto.Welcome
import com.owlmic.core.toBase64
import com.owlmic.core.toHex
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/** The phone's handshake against a fake PC on a loopback socket that follows protocol section 4 and 5. */
class HandshakeTest {
    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    private val pcKeys = Crypto.generate()
    private val pcId = ByteArray(16) { 2 }
    private val phoneKeys = Crypto.generate()
    private val phoneId = ByteArray(16) { 1 }

    @After
    fun close() = server.close()

    private enum class Pc { APPROVES_NEW, KNOWN, WRONG_CODE, BAD_PROOF, REJECTS_VERSION, NEWER_PROTO }

    /** Plays the PC once. The PC's session keys come back through [auth] for checks. */
    private fun fakePc(behaviour: Pc, auth: (Crypto.SessionKeys) -> Unit = {}) = thread(isDaemon = true) {
        try {
            playPc(behaviour, auth)
        } catch (_: IOException) {
            // The phone hung up first, as it should when it refuses the PC.
        }
    }

    private fun playPc(behaviour: Pc, auth: (Crypto.SessionKeys) -> Unit) {
        server.accept().use { s ->
            val ch = ControlChannel(s.getInputStream(), s.getOutputStream(), s)
            val helloIn = ch.read() as Incoming.Control
            val hello = helloIn.message as Hello
            if (behaviour == Pc.REJECTS_VERSION) {
                ch.send(Reject(RejectReason.VERSION))
                return
            }
            val eph = Crypto.generate()
            val nonce = Crypto.randomBytes(32)
            val proto = if (behaviour == Pc.NEWER_PROTO) Proto.VERSION + 1 else Proto.VERSION
            val ack = HelloAck(proto, pcId.toHex(), "DESKTOP-A", pcKeys.public.toBase64(), eph.public.toBase64(), nonce.toBase64(), AckStatus.NEW, null)
            val ackBytes = ch.send(ack)
            if (behaviour == Pc.NEWER_PROTO) return

            val k = Crypto.pairingKey(pcKeys.agree(hello.staticPub.fromBase64()!!)!!, hello.phoneId.hexToBytes(), pcId)
            val t = Crypto.transcript(helloIn.payload, ackBytes)
            val keys = Crypto.sessionKeys(Crypto.sessionMaster(k, eph.agree(hello.ephPub.fromBase64()!!)!!, hello.nonce.fromBase64()!!, nonce))
            auth(keys)
            val proof = (ch.read() as Incoming.Control).message as Proof
            check(Crypto.proofMatches(keys.auth, Crypto.Role.PHONE, t, proof.mac.fromBase64()!!))
            when (behaviour) {
                Pc.APPROVES_NEW -> ch.send(Pending(Crypto.approvalCode(k, t)))
                Pc.WRONG_CODE -> ch.send(Pending("0000".takeIf { it != Crypto.approvalCode(k, t) } ?: "0001"))
                else -> Unit
            }
            val mac = if (behaviour == Pc.BAD_PROOF) ByteArray(32) else Crypto.proof(keys.auth, Crypto.Role.PC, t)
            ch.send(Welcome("0f0e0d0c0b0a09080706050403020100", mac.toBase64(), mapOf("camera.lens" to "front"), listOf("mic", "camera")))
            runCatching { ch.read() }
        }
    }

    private fun phoneRun(remembered: PcKey? = null, onPending: (String, String) -> Unit = { _, _ -> }): Handshake.Result {
        val socket = Socket(server.inetAddress, server.localPort)
        val ch = ControlChannel(socket.getInputStream(), socket.getOutputStream(), socket)
        val h = Handshake(phoneKeys, phoneId, "Pixel", "Pixel 8") { remembered }
        return h.run(ch, LinkKind.WIFI, null, Watchdog.on(ch), onPending).also { ch.close() }
    }

    @Test
    fun aNewPhoneIsApprovedWithTheMatchingCode() {
        var pcSide: Crypto.SessionKeys? = null
        val pc = fakePc(Pc.APPROVES_NEW) { pcSide = it }
        var shown: String? = null
        val r = phoneRun(onPending = { _, code -> shown = code })
        pc.join(2_000)
        assertTrue(r.toString(), r is Handshake.Result.Welcomed)
        r as Handshake.Result.Welcomed
        assertTrue(r.approvedNow)
        assertEquals(4, shown!!.length)
        assertEquals(pcId.toHex(), r.pcId)
        assertEquals("DESKTOP-A", r.pcName)
        assertEquals(mapOf("camera.lens" to "front"), r.settings)
        assertArrayEquals(pcSide!!.phoneToPc, r.keys.phoneToPc)
        assertArrayEquals(pcSide!!.pcToPhone, r.keys.pcToPhone)
        assertEquals(16, r.sessionId.size)
    }

    @Test
    fun aKnownPcWelcomesWithoutPending() {
        fakePc(Pc.KNOWN)
        val r = phoneRun()
        assertTrue(r is Handshake.Result.Welcomed && !r.approvedNow)
    }

    @Test
    fun aWrongApprovalCodeAborts() {
        fakePc(Pc.WRONG_CODE)
        assertEquals(Handshake.Result.Failed("approval code mismatch"), phoneRun())
    }

    @Test
    fun aPcThatCannotProveItselfIsRefused() {
        fakePc(Pc.BAD_PROOF)
        assertEquals(Handshake.Result.Failed("PC proof failed"), phoneRun())
    }

    @Test
    fun aRememberedPcWithAnotherKeyIsRefused() {
        fakePc(Pc.KNOWN)
        val r = phoneRun(remembered = PcKey(Crypto.generate().public, ByteArray(32)))
        assertEquals(Handshake.Result.Failed("this PC's key changed"), r)
    }

    @Test
    fun versionMismatchesAreReported() {
        fakePc(Pc.REJECTS_VERSION)
        assertEquals(RejectReason.VERSION, (phoneRun() as Handshake.Result.Rejected).reason)
        fakePc(Pc.NEWER_PROTO)
        val r = phoneRun() as Handshake.Result.Rejected
        assertEquals(Proto.VERSION + 1, r.pcProto)
    }
}
