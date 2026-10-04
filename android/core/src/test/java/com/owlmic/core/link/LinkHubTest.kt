package com.owlmic.core.link

import com.owlmic.core.fromBase64
import com.owlmic.core.hexToBytes
import com.owlmic.core.hub.Connection
import com.owlmic.core.hub.LinkKind
import com.owlmic.core.proto.AckStatus
import com.owlmic.core.proto.Crypto
import com.owlmic.core.proto.Frames
import com.owlmic.core.proto.Hello
import com.owlmic.core.proto.HelloAck
import com.owlmic.core.proto.Ping
import com.owlmic.core.proto.Pong
import com.owlmic.core.proto.Proto
import com.owlmic.core.proto.Reject
import com.owlmic.core.proto.RejectReason
import com.owlmic.core.proto.Switch
import com.owlmic.core.proto.Welcome
import com.owlmic.core.settings.KeyWrapper
import com.owlmic.core.settings.Store
import com.owlmic.core.toBase64
import com.owlmic.core.toHex
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** The Link Hub against a fake PC on loopback that plays protocol section 4 on each link it is offered. */
class LinkHubTest {
    @get:Rule val folder = TemporaryFolder()

    private val loopback = InetAddress.getLoopbackAddress()
    private val server = ServerSocket(0, 2, loopback)
    private val media = DatagramSocket(0, loopback)
    private val pcKeys = Crypto.generate()
    private val pcId = ByteArray(16) { 9 }.toHex()
    private val sessionId = "00112233445566778899aabbccddeeff"
    private val open = mutableListOf<Socket>()
    private val events = LinkedBlockingQueue<LinkEvent>()

    @Volatile private var clock = 1_000_000L

    @After
    fun close() {
        open.forEach { runCatching { it.close() } }
        server.close()
        media.close()
    }

    private fun store() = Store(
        folder.root.resolve("s.json"),
        object : KeyWrapper {
            override fun wrap(secret: ByteArray) = secret

            override fun unwrap(blob: ByteArray) = blob
        },
        write = Runnable::run,
    )

    private fun candidate(link: LinkKind) =
        Candidate(pcId, "DESKTOP-A", link, host = loopback, tcpPort = server.localPort, mediaPort = media.localPort)

    /** The PC's side of one link's handshake: HELLO, HELLO_ACK, PROOF, WELCOME into the one session. */
    private fun welcomeNext(): ControlChannel {
        val s = server.accept().also { open += it }
        check(s.getInputStream().read() == Frames.CHANNEL_CONTROL)
        val ch = ControlChannel(s.getInputStream(), s.getOutputStream(), s)
        val helloIn = ch.read() as Incoming.Control
        val hello = helloIn.message as Hello
        val eph = Crypto.generate()
        val nonce = Crypto.randomBytes(32)
        val ackBytes = ch.send(HelloAck(Proto.VERSION, pcId, "DESKTOP-A", pcKeys.public.toBase64(), eph.public.toBase64(), nonce.toBase64(), AckStatus.KNOWN, null))
        val k = Crypto.pairingKey(pcKeys.agree(hello.staticPub.fromBase64()!!)!!, hello.phoneId.hexToBytes(), pcId.hexToBytes())
        val t = Crypto.transcript(helloIn.payload, ackBytes)
        val keys = Crypto.sessionKeys(Crypto.sessionMaster(k, eph.agree(hello.ephPub.fromBase64()!!)!!, hello.nonce.fromBase64()!!, nonce))
        ch.read()
        ch.send(Welcome(sessionId, Crypto.proof(keys.auth, Crypto.Role.PC, t).toBase64(), emptyMap(), emptyList()))
        if (LinkKind.of(hello.link)!!.isWireless) ch.sealing = ControlSealing(keys.pcToPhone, keys.phoneToPc)
        return ch
    }

    /** Answers the phone's heartbeats, so a link stays alive until the test ends. */
    private fun keepAlive(ch: ControlChannel) = thread(isDaemon = true) {
        runCatching {
            while (true) {
                val m = (ch.read() as? Incoming.Control)?.message
                if (m is Ping) ch.send(Pong(m.t))
            }
        }
    }

    private inline fun <reified E : LinkEvent> next(match: (E) -> Boolean = { true }): E {
        val deadline = System.currentTimeMillis() + 5_000
        val seen = mutableListOf<Any>()
        while (System.currentTimeMillis() < deadline) {
            val e = events.poll(100, TimeUnit.MILLISECONDS) ?: continue
            seen += (e as? LinkEvent.ConnectionChanged)?.connection ?: e
            if (e is E && match(e)) return e
        }
        error("no ${E::class.simpleName} in $seen")
    }

    private fun connectedOver(link: LinkKind) =
        next<LinkEvent.ConnectionChanged> { (it.connection as? Connection.Connected)?.link == link }

    private fun hub(store: Store = store()) =
        LinkHub(store, MediaRouter(), null, null, { "Pixel" }, "Pixel 8", 34, { null }, { false }, { events.put(it) }, { clock })

    @Test
    fun aPcWhoseHandshakesKeepFailingIsNamedAfterTenSeconds() {
        // A PC that answers discovery but hangs up on every handshake.
        thread(isDaemon = true) { runCatching { while (true) server.accept().close() } }
        val hub = hub()
        hub.post(LinkMsg.Found(candidate(LinkKind.WIFI)))
        next<LinkEvent.ConnectionChanged> { it.connection is Connection.Connecting }
        next<LinkEvent.ConnectionChanged> { it.connection == Connection.Searching() }
        // It keeps answering, so the phone retries every 2 s; ten seconds in, the status names it.
        var unreachable: String? = null
        var tries = 0
        while (unreachable == null && tries++ < 8) {
            clock += 2_000
            hub.post(LinkMsg.Found(candidate(LinkKind.WIFI)))
            unreachable = (next<LinkEvent.ConnectionChanged> { it.connection is Connection.Searching }.connection as Connection.Searching).unreachable
        }
        assertEquals("DESKTOP-A", unreachable)
        assertEquals(5, tries)
        hub.close()
    }

    @Test
    fun aPairedPcWithAnotherKeyIsNotTrusted() {
        val store = store()
        store.remember(pcId, "DESKTOP-A", Crypto.generate().public, ByteArray(32), null)
        thread(isDaemon = true) { runCatching { welcomeNext() } }
        val hub = hub(store)
        hub.post(LinkMsg.Found(candidate(LinkKind.WIFI)))
        next<LinkEvent.ConnectionChanged> { it.connection == Connection.KeyChanged("DESKTOP-A") }
        hub.close()
    }

    @Test
    fun aNewerPcAsksThisPhoneToUpdate() {
        thread(isDaemon = true) {
            val s = server.accept().also { open += it }
            s.getInputStream().read()
            val ch = ControlChannel(s.getInputStream(), s.getOutputStream(), s)
            ch.read()
            ch.send(Reject(RejectReason.VERSION, proto = Proto.VERSION + 1))
        }
        val hub = hub()
        hub.post(LinkMsg.Found(candidate(LinkKind.WIFI)))
        next<LinkEvent.ConnectionChanged> { it.connection == Connection.UpdateNeeded("DESKTOP-A", phoneOutdated = true) }
        hub.close()
    }

    @Test
    fun theSwitchArrivesOnTheNewLinkAndMediaMovesThere() {
        val router = MediaRouter()
        val hub = LinkHub(store(), router, null, null, { "Pixel" }, "Pixel 8", 34, { null }, { false }, { events.put(it) })
        thread(isDaemon = true) {
            keepAlive(welcomeNext())
            // The PC moves the session by sending SWITCH on the link it moves to (protocol section 4).
            val cable = welcomeNext()
            cable.send(Switch(LinkKind.USB_TETHERING.wire))
            keepAlive(cable)
        }

        hub.post(LinkMsg.Found(candidate(LinkKind.WIFI)))
        connectedOver(LinkKind.WIFI)
        val wifiPath = router.active
        assertNotNull(wifiPath)

        // A better link for the same PC: the phone proves it as a standby and waits for the PC.
        hub.post(LinkMsg.Found(candidate(LinkKind.USB_TETHERING)))
        connectedOver(LinkKind.USB_TETHERING)
        assertEquals(LinkKind.USB_TETHERING, next<LinkEvent.Switched>().link)
        assertNotSame(wifiPath, router.active)
        hub.close()
    }
}
