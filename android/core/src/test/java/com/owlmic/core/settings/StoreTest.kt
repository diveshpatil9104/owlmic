package com.owlmic.core.settings

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
    fun settingsArePerPairing() {
        val s = open()
        s.remember("aa", "DESKTOP-A", byteArrayOf(4), ByteArray(32), null)
        val changed = SettingValues.change(s.settings("aa"), "camera.lens", "front")
        s.saveSettings("aa", changed, setOf("camera.lens"))
        val again = open()
        assertEquals("front", again.settings("aa").getValue("camera.lens").value)
        assertEquals(setOf("camera.lens"), again.pending("aa"))
        assertEquals("back", again.settings(null).getValue("camera.lens").value)
        assertNotNull(again.pc("aa"))
    }
}
