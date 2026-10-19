package com.owlmic.core.settings

import com.owlmic.core.hub.LinkKind
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StoreTest {
    @get:Rule val folder = TemporaryFolder()

    /** Stands in for the Keystore: a reversible scramble, a switch to lose the key, and one to fail for now. */
    private class FakeWrapper : KeyWrapper {
        var lost = false
        var down = false
        var wraps = 0

        override fun wrap(secret: ByteArray): ByteArray {
            wraps++
            return byteArrayOf(0x5A) + secret.map { (it.toInt() xor 0x5A).toByte() }
        }

        override fun unwrap(blob: ByteArray): ByteArray? {
            if (down) throw java.security.ProviderException("keystore busy")
            return if (lost || blob.firstOrNull() != 0x5A.toByte()) null else blob.drop(1).map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        }
    }

    private val wrapper = FakeWrapper()
    private var t = 1_000L
    private val file get() = folder.root.resolve("owlmic.json")
    private fun open() = Store(file, wrapper, { t }, Runnable::run)

    @Test
    fun identityAndPairingsSurviveARestart() {
        val first = open()
        first.remember("aa", "DESKTOP-A", byteArrayOf(4, 1), ByteArray(32) { 7 }, "00:1a:7d:da:71:13")
        first.addAddress(" 192.168.1.20 ")
        val again = open()
        assertArrayEquals(first.phoneId, again.phoneId)
        assertArrayEquals(first.identity.public, again.identity.public)
        assertArrayEquals(ByteArray(32) { 7 }, again.key("aa")!!.pairingKey)
        assertEquals(setOf("00:1A:7D:DA:71:13"), again.btAddresses())
        assertEquals(listOf("192.168.1.20"), again.addresses())
        assertFalse(file.readText().contains("\"pairingKey\":\"" + java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 7 })))
    }

    @Test
    fun aLostKeystoreKeyMeansAFreshIdentity() {
        val first = open()
        wrapper.lost = true
        val again = open()
        assertFalse(first.phoneId.contentEquals(again.phoneId))
    }

    @Test
    fun aKeystoreThatFailsForNowLosesNothing() {
        val first = open()
        first.remember("aa", "DESKTOP-A", byteArrayOf(4), ByteArray(32) { 7 }, null)
        val before = file.readText()
        wrapper.down = true
        assertThrows(java.security.ProviderException::class.java) { open() }
        assertEquals(before, file.readText())
        wrapper.down = false
        val again = open()
        assertArrayEquals(first.phoneId, again.phoneId)
        assertNotNull(again.key("aa"))
    }

    @Test
    fun savingNeverWrapsAgain() {
        val s = open()
        s.remember("aa", "DESKTOP-A", byteArrayOf(4), ByteArray(32) { 7 }, null)
        val wraps = wrapper.wraps
        s.addAddress("10.0.0.2")
        s.saveSettings("aa", s.settings("aa"), emptySet())
        s.remember("aa", "DESKTOP-A", byteArrayOf(4), ByteArray(32) { 7 }, null)
        assertEquals(wraps, wrapper.wraps)
    }

    @Test
    fun askedPermissionsAreKept() {
        open().markAsked("android.permission.CAMERA")
        assertEquals(setOf("android.permission.CAMERA"), open().asked())
    }

    @Test
    fun refusalsAreRememberedButNotTrusted() {
        val s = open()
        s.refused("bb", "LAPTOP-B")
        assertNull(s.key("bb"))
        assertEquals(1_000L, s.known().getValue("bb").refusedAt)
        assertFalse(s.known().getValue("bb").approved)
        s.clearRefusal("bb")
        assertEquals(0L, s.known().getValue("bb").refusedAt)
    }

    @Test
    fun forgettingRemovesThePairingForGood() {
        val s = open()
        s.remember("aa", "DESKTOP-A", byteArrayOf(4), ByteArray(32), null)
        s.forget("aa")
        assertTrue(open().known().isEmpty())
    }

    @Test
    fun blocksAndAddressesSurviveARestart() {
        val s = open()
        s.remember("aa", "DESKTOP-A", byteArrayOf(4), ByteArray(32) { 3 }, null, "192.168.1.15:7653")
        s.connectedVia("aa", "10.42.0.1:7653")
        s.block("aa", "DESKTOP-A", "192.168.1.99:7653")
        // A PC never paired is remembered only to stay blocked, with where it answered from.
        s.block("bb", "LAPTOP-B", "192.168.1.30:7653")
        val again = open()
        val a = again.known().getValue("aa")
        assertTrue(a.blocked && a.approved)
        assertEquals("10.42.0.1:7653", a.address)
        val b = again.known().getValue("bb")
        assertTrue(b.blocked && !b.approved)
        assertEquals("192.168.1.30:7653", b.address)
        assertNull(again.key("bb"))
    }

    @Test
    fun unblockingKeepsAPairingButDropsAPcNeverPaired() {
        val s = open()
        s.remember("aa", "DESKTOP-A", byteArrayOf(4), ByteArray(32), null)
        s.block("aa", "DESKTOP-A", null)
        s.block("bb", "LAPTOP-B", null)
        s.unblock("aa")
        s.unblock("bb")
        val again = open()
        assertFalse(again.known().getValue("aa").blocked)
        assertNotNull(again.key("aa"))
        assertNull(again.pc("bb"))
    }

    @Test
    fun settingsArePerPairing() {
        val s = open()
        s.remember("aa", "DESKTOP-A", byteArrayOf(4), ByteArray(32), null)
        val changed = SettingValues.change(s.settings("aa"), "camera.lens", "back")
        s.saveSettings("aa", changed, setOf("camera.lens"))
        val again = open()
        assertEquals("back", again.settings("aa").getValue("camera.lens").value)
        assertEquals(setOf("camera.lens"), again.pending("aa"))
        assertEquals("front", again.settings(null).getValue("camera.lens").value)
        assertNotNull(again.pc("aa"))
    }

    @Test
    fun anUntouchedDefaultFollowsTheNewBuildsDefaultWhileAChangedValueStays() {
        val s = open()
        s.remember("aa", "DESKTOP-A", byteArrayOf(4), ByteArray(32), null)
        // An older build saved its own default (version 0) next to a value the user picked (version 3).
        val old = SettingValues.defaults() + mapOf("camera.ratio" to Versioned("16:9", 0), "camera.lens" to Versioned("back", 3))
        s.saveSettings("aa", old, emptySet())
        val again = open().settings("aa")
        assertEquals("full", again.getValue("camera.ratio").value)
        assertEquals("back", again.getValue("camera.lens").value)
    }

    @Test
    fun theLastConnectionsTimeAddressAndLinkSurviveARestart() {
        val s = open()
        s.remember("aa", "DESKTOP-A", byteArrayOf(4), ByteArray(32), null, "192.168.1.15:7653", LinkKind.WIFI)
        t = 70_000
        s.connectedNow("aa")
        s.connectedVia("aa", "10.42.0.1:7653", LinkKind.USB_TETHERING)
        val pc = open().known().getValue("aa")
        assertEquals(70_000L, pc.lastUsedAt)
        assertEquals("10.42.0.1:7653", pc.address)
        assertEquals(LinkKind.USB_TETHERING, pc.lastLink)
    }

    @Test
    fun aPcNeverConnectedKeepsWhereItAnsweredAndNoTime() {
        val s = open()
        s.block("bb", "LAPTOP-B", "192.168.1.30:7653", LinkKind.WIFI)
        t = 90_000
        s.connectedNow("bb")
        val pc = open().known().getValue("bb")
        assertEquals(0L, pc.lastUsedAt)
        assertEquals("192.168.1.30:7653", pc.address)
        assertEquals(LinkKind.WIFI, pc.lastLink)
    }

    @Test
    fun aNewWelcomeWithoutAnAddressKeepsTheLastOne() {
        val s = open()
        s.remember("aa", "DESKTOP-A", byteArrayOf(4), ByteArray(32) { 3 }, null, "192.168.1.15:7653", LinkKind.WIFI)
        t = 5_000
        // A welcome over the cable tunnel, which has no address of its own: the old address stays, the link moves.
        s.remember("aa", "DESKTOP-A", byteArrayOf(4), ByteArray(32) { 3 }, null, null, LinkKind.USB_DEBUGGING)
        val pc = s.known().getValue("aa")
        assertEquals("192.168.1.15:7653", pc.address)
        assertEquals(LinkKind.USB_DEBUGGING, pc.lastLink)
        assertEquals(5_000L, pc.lastUsedAt)
    }

    @Test
    fun aFileWrittenBeforeTheHistoryFieldsStillLoads() {
        open().remember("aa", "DESKTOP-A", byteArrayOf(4), ByteArray(32) { 7 }, null, "192.168.1.15:7653", LinkKind.WIFI)
        val json = JSONObject(file.readText())
        val pcs = json.getJSONArray("pcs")
        repeat(pcs.length()) { i -> listOf("blocked", "address", "lastLink").forEach { pcs.getJSONObject(i).remove(it) } }
        file.writeText(json.toString())
        val again = open()
        val pc = again.known().getValue("aa")
        assertTrue(pc.approved)
        assertFalse(pc.blocked)
        assertNull(pc.address)
        assertNull(pc.lastLink)
        assertArrayEquals(ByteArray(32) { 7 }, again.key("aa")!!.pairingKey)
    }
}
