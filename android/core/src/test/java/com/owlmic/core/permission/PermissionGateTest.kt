package com.owlmic.core.permission

import com.owlmic.core.hub.Feature
import com.owlmic.core.permission.PermissionGate.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionGateTest {
    private val mic = PermissionGate.RECORD_AUDIO

    @Test
    fun theSpeakerNeedsNoPermission() {
        assertEquals(Decision.Proceed, PermissionGate.decide(Feature.SPEAKER, emptySet(), emptySet()) { false })
    }

    @Test
    fun askedOnFirstUseThenAgainAfterOneNoThenSettings() {
        assertEquals(Decision.Ask(mic), PermissionGate.decide(Feature.MIC, emptySet(), emptySet()) { false })
        assertEquals(Decision.Ask(mic), PermissionGate.decide(Feature.MIC, emptySet(), setOf(mic)) { true })
        assertEquals(Decision.OpenSettings, PermissionGate.decide(Feature.MIC, emptySet(), setOf(mic)) { false })
        assertEquals(Decision.Proceed, PermissionGate.decide(Feature.MIC, setOf(mic), setOf(mic)) { false })
    }

    @Test
    fun notificationsAreAskedOnceOnAndroid13AndLater() {
        assertFalse(PermissionGate.askNotifications(32, emptySet(), emptySet()))
        assertTrue(PermissionGate.askNotifications(33, emptySet(), emptySet()))
        assertFalse(PermissionGate.askNotifications(33, emptySet(), setOf(PermissionGate.POST_NOTIFICATIONS)))
    }

    @Test
    fun bluetoothNeedsARuntimePermissionFromAndroid12() {
        assertNull(PermissionGate.bluetoothPermission(30))
        assertEquals(PermissionGate.BLUETOOTH_CONNECT, PermissionGate.bluetoothPermission(31))
    }
}
