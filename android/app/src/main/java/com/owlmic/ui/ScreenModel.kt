package com.owlmic.ui

import com.owlmic.core.hub.AppState
import com.owlmic.core.hub.Connection
import com.owlmic.core.hub.FeaturePhase
import com.owlmic.core.hub.FeatureProblem
import com.owlmic.core.hub.FeatureView
import com.owlmic.core.hub.LinkKind

/** The link tile's colour (section 36): green connected, yellow on the way or held, red when something needs the user. */
enum class Tone { GREEN, YELLOW, RED }

fun toneOf(c: Connection): Tone = when (c) {
    is Connection.Connected -> if (c.weak) Tone.YELLOW else Tone.GREEN
    is Connection.Searching -> if (c.unreachable != null) Tone.RED else Tone.YELLOW
    is Connection.Choosing, is Connection.Connecting, is Connection.Approving, is Connection.Reconnecting, is Connection.Waiting -> Tone.YELLOW
    is Connection.Denied, is Connection.Busy, is Connection.UpdateNeeded, is Connection.KeyChanged -> Tone.RED
}

/** The link tile's icon is the link; with no link yet, the unplugged cable. */
fun glyphOf(c: Connection): Glyph = when (c) {
    is Connection.Connected -> glyphOf(c.link)
    is Connection.Connecting -> glyphOf(c.link)
    is Connection.Approving -> glyphOf(c.link)
    is Connection.Reconnecting -> glyphOf(c.link)
    else -> Glyph.UNPLUG
}

fun glyphOf(link: LinkKind?): Glyph = when (link) {
    LinkKind.USB_DEBUGGING -> Glyph.PLUG
    LinkKind.USB_TETHERING -> Glyph.CHAIN
    LinkKind.WIFI -> Glyph.WIFI
    LinkKind.BLUETOOTH -> Glyph.BLUETOOTH
    null -> Glyph.UNPLUG
}

/** What tapping the status strip does, when it does anything. */
enum class StripAction { TETHERING, BLUETOOTH, ASK_AGAIN, CHOOSE }

/** The status strip shows whenever the phone isn't connected; the main screen has no other status line. */
fun showsStrip(state: AppState) = state.connection !is Connection.Connected

/** The tethering hint shows while searching with a cable in and tethering off (section 14.1). */
fun tethering(state: AppState) = state.tetherHint && state.connection is Connection.Searching

fun stripAction(state: AppState): StripAction? = when {
    state.connection is Connection.Choosing -> StripAction.CHOOSE
    state.connection is Connection.Denied -> StripAction.ASK_AGAIN
    tethering(state) -> StripAction.TETHERING
    state.bluetoothOffer && state.connection is Connection.Searching -> StripAction.BLUETOOTH
    else -> null
}

/** A note under a feature tile's icon. [alert] ones are red. */
enum class TileNote(val alert: Boolean) {
    PAUSED(false),
    RESTARTING(false),
    FAILED(true),
    PERMISSION(true),
    NO_BLUETOOTH(true),
}

/** A feature tile: the "on" icon or the slashed one, and why, when it isn't simply on or off. */
data class TileLook(val on: Boolean, val note: TileNote?)

fun lookOf(v: FeatureView): TileLook = when {
    v.problem == FeatureProblem.PERMISSION -> TileLook(false, TileNote.PERMISSION)
    v.problem == FeatureProblem.NO_BLUETOOTH -> TileLook(false, TileNote.NO_BLUETOOTH)
    v.phase == FeaturePhase.FAILED -> TileLook(false, TileNote.FAILED)
    v.phase == FeaturePhase.PAUSED -> TileLook(false, TileNote.PAUSED)
    v.phase == FeaturePhase.RECOVERING -> TileLook(true, TileNote.RESTARTING)
    else -> TileLook(v.isOn, null)
}

/** How a PC row in the connection list is marked. */
enum class PcBadge { ACTIVE, READY, STANDBY }

/**
 * One row of the connection list. [chooseId] makes the row tappable: a PC that answered and waits to be picked.
 * A manual address has no name: the row says "Direct IP" and shows the address.
 */
data class PcRow(
    val name: String?,
    val address: String?,
    val link: LinkKind?,
    val hotspot: Boolean,
    val badge: PcBadge?,
    val chooseId: String?,
)

/** The connected PC first, then PCs that answered, then other paired PCs, then addresses added by hand. */
fun pcRows(state: AppState): List<PcRow> {
    val rows = mutableListOf<PcRow>()
    val c = state.connection as? Connection.Connected
    if (c != null) rows += PcRow(c.pc, c.address, c.link, c.hotspot, PcBadge.ACTIVE, null)
    state.choices.forEach { rows += PcRow(it.name, null, null, false, PcBadge.READY, it.id) }
    val listed = state.choices.map { it.id }.toSet()
    state.knownPcs.filter { !it.current && it.id !in listed }.forEach { rows += PcRow(it.name, null, null, false, PcBadge.STANDBY, null) }
    state.manualAddresses.forEach { rows += PcRow(null, it, null, false, null, null) }
    return rows
}

/** The four links the connection list can switch off and on, with their settings. */
val LINK_SETTINGS = listOf(
    LinkKind.USB_DEBUGGING to "link.usbDebugging",
    LinkKind.USB_TETHERING to "link.usbTethering",
    LinkKind.WIFI to "link.wifi",
    LinkKind.BLUETOOTH to "link.bluetooth",
)
