package com.owlmic.core.settings

import com.owlmic.core.proto.SettingChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class SettingsHubTest {
    @get:Rule val folder = TemporaryFolder()

    private val events = LinkedBlockingQueue<SettingsEvent>()

    private fun next(): SettingsEvent = events.poll(2, TimeUnit.SECONDS) ?: error("no event")

    private inline fun <reified E : SettingsEvent> nextOf(): E {
        while (true) {
            val e = next()
            if (e is E) return e
        }
    }

    private fun store() = Store(
        folder.root.resolve("s.json"),
        object : KeyWrapper {
            override fun wrap(secret: ByteArray) = secret

            override fun unwrap(blob: ByteArray) = blob
        },
        write = Runnable::run,
    )

    @Test
    fun aChangeMadeWhileAwayGoesOutOnReconnect() {
        val store = store()
        store.remember("aa", "DESKTOP-A", byteArrayOf(4), ByteArray(32), null)
        val hub = SettingsHub(store) { events.put(it) }
        nextOf<SettingsEvent.Values>()

        hub.post(SettingsMsg.Use("aa", connected = false))
        nextOf<SettingsEvent.Values>()
        hub.post(SettingsMsg.Change("camera.lens", "front"))
        assertEquals("front", nextOf<SettingsEvent.Values>().values["camera.lens"])

        hub.post(SettingsMsg.Use("aa", connected = true, pcValues = mapOf("camera.lens" to "back", "camera.frameRate" to "30")))
        val values = nextOf<SettingsEvent.Values>().values
        assertEquals("front", values["camera.lens"])
        assertEquals("30", values["camera.frameRate"])

        // The PC's snapshot after WELCOME carries its versions; the waiting change goes out past them.
        hub.post(SettingsMsg.Remote(listOf(SettingChange("camera.lens", "back", 40), SettingChange("camera.frameRate", "30", 41))))
        val push = nextOf<SettingsEvent.Push>()
        assertEquals(listOf("camera.lens"), push.changes.map { it.id })
        assertEquals("front", push.changes.single().value)
        assertTrue(push.changes.single().version > 41)
        hub.close()
    }

    @Test
    fun afterTheSnapshotAPhoneChangeOutranksThePcsVersions() {
        val store = store()
        store.remember("aa", "DESKTOP-A", byteArrayOf(4), ByteArray(32), null)
        val hub = SettingsHub(store) { events.put(it) }
        hub.post(SettingsMsg.Use("aa", connected = true, pcValues = mapOf("mic.boost" to "off")))
        hub.post(SettingsMsg.Remote(listOf(SettingChange("mic.boost", "off", 100))))
        hub.post(SettingsMsg.Change("mic.boost", "plus6"))
        val push = nextOf<SettingsEvent.Push>()
        assertEquals(SettingChange("mic.boost", "plus6", 101), push.changes.single())
        hub.close()
    }

    @Test
    fun whileConnectedAChangeIsPushedAndThePcsChangesApply() {
        val store = store()
        store.remember("aa", "DESKTOP-A", byteArrayOf(4), ByteArray(32), null)
        val hub = SettingsHub(store) { events.put(it) }
        hub.post(SettingsMsg.Use("aa", connected = true, pcValues = emptyMap()))
        hub.post(SettingsMsg.Remote(emptyList()))
        hub.post(SettingsMsg.Change("mic.boost", "plus6"))
        val push = nextOf<SettingsEvent.Push>()
        assertEquals("plus6", push.changes.single().value)

        hub.post(SettingsMsg.Remote(listOf(SettingChange("mic.boost", "plus12", push.changes.single().version + 1))))
        val after = nextOf<SettingsEvent.Values>()
        assertTrue("mic.boost" in after.changed || after.values["mic.boost"] == "plus6")
        val applied = if (after.values["mic.boost"] == "plus12") after else nextOf()
        assertEquals("plus12", applied.values["mic.boost"])
        hub.close()
    }
}
