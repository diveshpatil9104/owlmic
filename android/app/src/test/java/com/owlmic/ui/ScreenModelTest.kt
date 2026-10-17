package com.owlmic.ui

import com.owlmic.core.hub.AppState
import com.owlmic.core.hub.Connection
import com.owlmic.core.hub.FeaturePhase
import com.owlmic.core.hub.FeatureProblem
import com.owlmic.core.hub.FeatureView
import com.owlmic.core.hub.KnownPcView
import com.owlmic.core.hub.LinkKind
import com.owlmic.core.hub.PcChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenModelTest {
    @Test
    fun theLinkTileIsGreenConnectedYellowOnTheWayRedWhenTheUserIsNeeded() {
        assertEquals(Tone.GREEN, toneOf(Connection.Connected("PC", LinkKind.WIFI)))
        assertEquals(Tone.YELLOW, toneOf(Connection.Connected("PC", LinkKind.WIFI, weak = true)))
        listOf(
            Connection.Searching(),
            Connection.Choosing(2),
            Connection.Connecting("PC", LinkKind.WIFI),
            Connection.Approving("PC", "4821", LinkKind.WIFI),
            Connection.Reconnecting("PC", LinkKind.USB_TETHERING),
            Connection.Waiting("PC"),
        ).forEach { assertEquals(it.toString(), Tone.YELLOW, toneOf(it)) }
        listOf(
            Connection.Searching(unreachable = "PC"),
            Connection.Denied("PC"),
            Connection.Busy("PC", "Pixel"),
            Connection.UpdateNeeded("PC", phoneOutdated = true),
            Connection.KeyChanged("PC"),
        ).forEach { assertEquals(it.toString(), Tone.RED, toneOf(it)) }
    }

    @Test
    fun theLinkTileShowsTheLinkOrTheUnpluggedCable() {
        assertEquals(Glyph.WIFI, glyphOf(Connection.Connected("PC", LinkKind.WIFI)))
        assertEquals(Glyph.PLUG, glyphOf(Connection.Connected("PC", LinkKind.USB_DEBUGGING)))
        assertEquals(Glyph.CHAIN, glyphOf(Connection.Reconnecting("PC", LinkKind.USB_TETHERING)))
        assertEquals(Glyph.BLUETOOTH, glyphOf(Connection.Approving("PC", "1", LinkKind.BLUETOOTH)))
        assertEquals(Glyph.UNPLUG, glyphOf(Connection.Connecting(null, null)))
        assertEquals(Glyph.UNPLUG, glyphOf(Connection.Searching()))
        assertEquals(Glyph.UNPLUG, glyphOf(Connection.Waiting("PC")))
    }

    @Test
    fun theStripShowsUntilConnectedAndOffersOneAction() {
        assertFalse(showsStrip(AppState(connection = Connection.Connected("PC", LinkKind.WIFI))))
        assertTrue(showsStrip(AppState()))
        assertTrue(showsStrip(AppState(connection = Connection.Approving("PC", "4821", LinkKind.WIFI))))

        assertNull(stripAction(AppState()))
        assertEquals(StripAction.CHOOSE, stripAction(AppState(connection = Connection.Choosing(2))))
        assertEquals(StripAction.ASK_AGAIN, stripAction(AppState(connection = Connection.Denied("PC"))))
        // The tethering hint wins over Bluetooth: a cable is the better link.
        assertEquals(StripAction.TETHERING, stripAction(AppState(tetherHint = true, bluetoothOffer = true)))
        assertEquals(StripAction.BLUETOOTH, stripAction(AppState(bluetoothOffer = true)))
        assertNull(stripAction(AppState(connection = Connection.Reconnecting("PC", null), tetherHint = true, bluetoothOffer = true)))
    }

    @Test
    fun featureTilesShowOnOffAndWhyNot() {
        assertEquals(TileLook(false, null), lookOf(FeatureView()))
        assertEquals(TileLook(true, null), lookOf(FeatureView(FeaturePhase.ON)))
        assertEquals(TileLook(true, null), lookOf(FeatureView(FeaturePhase.STARTING)))
        assertEquals(TileLook(true, TileNote.RESTARTING), lookOf(FeatureView(FeaturePhase.RECOVERING)))
        assertEquals(TileLook(false, TileNote.PAUSED), lookOf(FeatureView(FeaturePhase.PAUSED)))
        assertEquals(TileLook(false, TileNote.FAILED), lookOf(FeatureView(FeaturePhase.FAILED)))
        assertEquals(TileLook(false, TileNote.PERMISSION), lookOf(FeatureView(FeaturePhase.OFF, FeatureProblem.PERMISSION)))
        assertEquals(TileLook(false, TileNote.NO_BLUETOOTH), lookOf(FeatureView(FeaturePhase.PAUSED, FeatureProblem.NO_BLUETOOTH)))
        assertTrue(TileNote.FAILED.alert)
        assertFalse(TileNote.PAUSED.alert)
    }

    @Test
    fun theConnectionListHasOnlyRealPcs() {
        assertEquals(emptyList<PcRow>(), pcRows(AppState()))

        val state = AppState(
            connection = Connection.Connected("Desk", LinkKind.WIFI, address = "192.168.1.15:7653"),
            choices = listOf(PcChoice("b", "Laptop")),
            knownPcs = listOf(KnownPcView("a", "Desk", current = true), KnownPcView("b", "Laptop", false), KnownPcView("c", "Studio", false)),
            manualAddresses = listOf("10.0.0.42"),
        )
        assertEquals(
            listOf(
                PcRow("Desk", "192.168.1.15:7653", LinkKind.WIFI, false, PcBadge.ACTIVE, null),
                PcRow("Laptop", null, null, false, PcBadge.READY, "b"),
                PcRow("Studio", null, null, false, PcBadge.STANDBY, null),
                PcRow(null, "10.0.0.42", null, false, null, null),
            ),
            pcRows(state),
        )
    }
}
