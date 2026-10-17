package com.owlmic.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.owlmic.Owlmic
import com.owlmic.R
import com.owlmic.core.design.Tokens
import com.owlmic.core.hub.AppState
import com.owlmic.core.hub.Connection
import com.owlmic.core.hub.Feature
import com.owlmic.core.hub.FeaturePhase
import com.owlmic.core.hub.LinkKind
import com.owlmic.core.hub.SearchStage
import com.owlmic.hub.AppMsg
import com.owlmic.media.camera.VideoPlan
import com.owlmic.core.R as Copy

/**
 * The one screen, as drawn in the prototype (landing/proto-templates): the wordmark with the help and link tiles on top,
 * the preview area in the middle, and the camera, mic and speaker tiles below, in a bento grid of rounded tiles.
 * [turn] is how the phone is held (see Turn.kt): the grid never moves, its glyphs turn to read upright. [onFeature] and
 * [onTryBluetooth] go through the activity, which asks for permissions first; [onTethering] opens the system's tethering
 * settings. Settings open by long-pressing a feature tile.
 */
@Composable
fun MainScreen(turn: Int, onFeature: (Feature) -> Unit, onTryBluetooth: () -> Unit, onTethering: () -> Unit) {
    val angle = animateFloatAsState(uprightAngle(turn), tween(Tokens.Phone.TURN_MS.toInt()), label = "turn")
    val up = remember(turn, angle) { Upright(turn, angle) }
    Screen(up, onFeature, onTryBluetooth, onTethering)
}

@Composable
private fun Screen(up: Upright, onFeature: (Feature) -> Unit, onTryBluetooth: () -> Unit, onTethering: () -> Unit) {
    val state by Owlmic.state.collectAsState()
    var sheet by remember { mutableStateOf<String?>(null) }
    var drawer by remember { mutableStateOf<Drawer?>(null) }
    val toggle = { d: Drawer -> drawer = if (drawer == d) null else d }

    // Sideways, the status bar is hidden and both ends of the screen keep a black band, as in the prototype.
    val band = Tokens.Phone.SIDE_BAND_DP.dp
    val insets = if (up.sideways) WindowInsets.safeDrawing.union(WindowInsets(top = band, bottom = band)) else WindowInsets.safeDrawing
    Box(Modifier.fillMaxSize().background(Theme.bg)) {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(insets).background(Theme.seam).padding(Theme.gap),
            verticalArrangement = Arrangement.spacedBy(Theme.gap),
        ) {
            TopBar(up, state.connection, drawer, onHelp = { toggle(Drawer.HELP) }, onLink = { toggle(Drawer.CONNECTION) })
            Playground(
                up,
                state,
                drawer,
                Modifier.weight(1f).fillMaxWidth(),
                onClose = { drawer = null },
                onStrip = { action ->
                    when (action) {
                        StripAction.CHOOSE -> drawer = Drawer.CONNECTION
                        StripAction.ASK_AGAIN -> Owlmic.post(AppMsg.AskAgain)
                        StripAction.TETHERING -> onTethering()
                        StripAction.BLUETOOTH -> onTryBluetooth()
                    }
                },
            )
            Row(Modifier.fillMaxWidth().height(Tokens.Phone.BOTTOM_BAR_DP.dp), horizontalArrangement = Arrangement.spacedBy(Theme.gap)) {
                FeatureTile(up, state, Feature.CAMERA, Modifier.weight(1f), onFeature) { sheet = SECTION_CAMERA }
                FeatureTile(up, state, Feature.MIC, Modifier.weight(1f), onFeature) { sheet = SECTION_MIC }
                FeatureTile(up, state, Feature.SPEAKER, Modifier.weight(1f), onFeature) { sheet = SECTION_SPEAKER }
            }
        }
        AnimatedVisibility(sheet != null, enter = fadeIn(tween(Theme.STATE_MS)), exit = fadeOut(tween(Theme.STATE_MS))) {
            // The insets (the keyboard's too) stay outside the turn: they belong to the screen, not to the reader.
            Box(Modifier.fillMaxSize().background(Theme.bg).windowInsetsPadding(insets)) {
                SettingsSheet(state, startAt = sheet ?: SECTION_MIC, onClose = { sheet = null }, Modifier.fillMaxSize().upright(up, swap = true))
            }
        }
    }
    BackHandler(sheet != null || drawer != null) {
        if (sheet != null) sheet = null else drawer = null
    }
}

const val SECTION_MIC = "mic"
const val SECTION_CAMERA = "camera"
const val SECTION_SPEAKER = "speaker"

/** A rounded black tile of the grid. Light with a black icon when [on] (an open drawer's tile); darker while pressed. */
@Composable
private fun Tile(
    modifier: Modifier,
    on: Boolean = false,
    label: String? = null,
    onTap: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    content: @Composable BoxScope.(fg: Color) -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val bg by animateColorAsState(
        when {
            on -> Theme.ink
            pressed -> Theme.pressed
            else -> Theme.bg
        },
        tween(Theme.STATE_MS),
        label = "tile",
    )
    val fg by animateColorAsState(if (on) Theme.onContent else Theme.ink, tween(Theme.STATE_MS), label = "content")
    Box(
        modifier
            .clip(RoundedCornerShape(Theme.radius))
            .background(bg)
            .combinedClickable(
                interactionSource = source,
                indication = null,
                role = Role.Button,
                onClickLabel = label,
                onLongClick = onLongPress,
                onClick = onTap,
            ),
        contentAlignment = Alignment.Center,
    ) { content(fg) }
}

@Composable
private fun TopBar(up: Upright, connection: Connection, drawer: Drawer?, onHelp: () -> Unit, onLink: () -> Unit) {
    val height = Tokens.Phone.TOP_BAR_DP.dp
    Row(Modifier.fillMaxWidth().height(height), horizontalArrangement = Arrangement.spacedBy(Theme.gap)) {
        Wordmark(up, Modifier.weight(1f))
        val help = stringResource(Copy.string.section_help)
        Tile(Modifier.fillMaxHeight().aspectRatio(1f), on = drawer == Drawer.HELP, label = help, onTap = onHelp) { fg ->
            Icon(Glyph.HELP, fg, help, Modifier.upright(up), size = Theme.glyph, stroke = Tokens.Phone.TOP_GLYPH_STROKE)
        }
        val tint by animateColorAsState(toneColor(toneOf(connection)), tween(Theme.STATE_MS), label = "link")
        val description = stringResource(linkLabel(connection))
        Tile(
            Modifier.fillMaxHeight().aspectRatio(1f).semantics { contentDescription = description },
            on = drawer == Drawer.CONNECTION,
            label = stringResource(Copy.string.section_connection),
            onTap = onLink,
        ) { fg ->
            Icon(glyphOf(connection), if (drawer == Drawer.CONNECTION) fg else tint, null, Modifier.upright(up), size = Theme.glyph, stroke = Tokens.Phone.TOP_GLYPH_STROKE)
        }
    }
}

/**
 * OWLMIC, letter by letter: in a row while upright; sideways each letter turns upright and they stack from the reader's
 * top, at the reader's bottom end of the tile, as in the prototype.
 */
@Composable
private fun Wordmark(up: Upright, modifier: Modifier) {
    val (align, inset) = when (up.turn) {
        TURN_LEFT -> Alignment.CenterStart to PaddingValues(start = Tokens.Phone.WORDMARK_SIDE_INSET_DP.dp)
        TURN_RIGHT -> Alignment.CenterEnd to PaddingValues(end = Tokens.Phone.WORDMARK_SIDE_INSET_DP.dp)
        else -> Alignment.CenterStart to PaddingValues(start = Tokens.Phone.WORDMARK_INSET_DP.dp)
    }
    val letter = if (up.sideways) {
        DpSize(Tokens.Phone.WORDMARK_LETTER_SIDE_DP.dp, Tokens.Phone.WORDMARK_LETTER_SIDE_DP.dp)
    } else {
        DpSize(Tokens.Phone.WORDMARK_WIDTH_DP.dp / 6, Tokens.Phone.WORDMARK_HEIGHT_DP.dp)
    }
    val name = stringResource(Copy.string.app_name)
    Box(
        modifier.fillMaxHeight().clip(RoundedCornerShape(Theme.radius)).background(Theme.bg).semantics { contentDescription = name }.padding(inset),
        contentAlignment = align,
    ) {
        Row {
            wordmarkOrder(up.turn).forEach { c ->
                Image(painterResource(LETTERS.getValue(c)), null, Modifier.size(letter).upright(up), colorFilter = ColorFilter.tint(Theme.ink))
            }
        }
    }
}

private val LETTERS = mapOf(
    'o' to R.drawable.wordmark_o,
    'w' to R.drawable.wordmark_w,
    'l' to R.drawable.wordmark_l,
    'm' to R.drawable.wordmark_m,
    'i' to R.drawable.wordmark_i,
    'c' to R.drawable.wordmark_c,
)

fun toneColor(t: Tone) = when (t) {
    Tone.GREEN -> Theme.green
    Tone.YELLOW -> Theme.yellow
    Tone.RED -> Theme.red
}

/** What the link tile says to a screen reader: the colour and the icon in words. */
private fun linkLabel(c: Connection): Int = when (c) {
    is Connection.Connecting -> Copy.string.link_connecting
    is Connection.Approving -> Copy.string.link_approval
    is Connection.Reconnecting, is Connection.Waiting -> Copy.string.status_reconnecting
    is Connection.Connected -> when {
        c.weak -> Copy.string.link_weak
        c.link == LinkKind.USB_DEBUGGING -> Copy.string.link_usb_debugging
        c.link == LinkKind.USB_TETHERING -> Copy.string.link_usb_tethering
        c.link == LinkKind.WIFI -> if (c.hotspot) Copy.string.link_hotspot else Copy.string.link_wifi
        else -> Copy.string.link_bluetooth
    }
    else -> Copy.string.link_not_connected
}

/**
 * The middle tile: the owl on a dot grid while nothing is on, what the camera sends while it is on, the mic's sound wave,
 * and the status strip while not connected. The drawers open over it.
 */
@Composable
private fun Playground(up: Upright, state: AppState, drawer: Drawer?, modifier: Modifier, onClose: () -> Unit, onStrip: (StripAction) -> Unit) {
    val camera = state.camera
    // Paused for Bluetooth, the camera is off: its tile says why instead of the preview showing nothing.
    val live = (camera.isOn || camera.phase == FeaturePhase.PAUSED) && camera.problem == null
    val mic = state.mic.isOn
    Box(modifier.clip(RoundedCornerShape(Theme.radius)).background(Theme.bg)) {
        DotGrid(Modifier.fillMaxSize())
        if (live) {
            Preview()
            CornerMask(Modifier.fillMaxSize())
            // The caption and the flip button sit in the reader's top corners.
            Caption(state, Modifier.align(spotOf(Spot.TOP_START, up.turn)).padding(12.dp).upright(up, swap = true))
            val flip = stringResource(Copy.string.phone_flip_camera)
            Box(
                Modifier.align(spotOf(Spot.TOP_END, up.turn)).padding(12.dp).size(Theme.touch).clip(RoundedCornerShape(10.dp)).background(Theme.bg)
                    .border(1.dp, Theme.stripEdge, RoundedCornerShape(10.dp)).clickable(onClickLabel = flip) { Owlmic.post(AppMsg.FlipLens) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Glyph.FLIP, Theme.ink, flip, Modifier.upright(up), size = 20.dp, stroke = Tokens.Phone.GLYPH_STROKE)
            }
        } else if (!mic) {
            Image(
                painterResource(R.drawable.owl),
                null,
                Modifier.align(Alignment.Center).size(Tokens.Phone.OWL_DP.dp).upright(up),
                colorFilter = ColorFilter.tint(Theme.ink.copy(alpha = Tokens.Phone.OWL_ALPHA)),
            )
        }
        if (mic && !live) Waveform(docked = false, modifier = Modifier.align(Alignment.Center).upright(up))
        // The docked sound wave and the status sit at the reader's bottom edge.
        val inset = if (up.sideways) {
            Modifier.padding(horizontal = Tokens.Phone.STRIP_SIDE_INSET_DP.dp, vertical = 16.dp)
        } else {
            Modifier.padding(start = 16.dp, end = 16.dp, bottom = Tokens.Phone.STRIP_INSET_DP.dp)
        }
        Column(
            Modifier.align(spotOf(Spot.BOTTOM, up.turn)).then(inset).upright(up, swap = true),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (mic && live) Waveform(docked = true)
            if (showsStrip(state)) StatusStrip(state, onStrip)
        }
        AnimatedDrawer(up, drawer, state, onClose)
    }
}

/** The black strip with a faint edge, in which the docked sound wave and the status sit. */
@Composable
fun StripBox(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val shape = RoundedCornerShape(Theme.radius)
    Box(
        modifier.clip(shape).background(Theme.bg).border(1.dp, Theme.stripEdge, shape).padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

@Composable
private fun StatusStrip(state: AppState, onStrip: (StripAction) -> Unit) {
    val action = stripAction(state)
    val actionText = when (action) {
        StripAction.TETHERING -> stringResource(Copy.string.ui_open_tethering)
        StripAction.BLUETOOTH -> stringResource(Copy.string.bt_try)
        StripAction.ASK_AGAIN, StripAction.CHOOSE, null -> null
    }
    val tap = if (action != null) Modifier.clickable(role = Role.Button) { onStrip(action) } else Modifier
    StripBox(Modifier.widthIn(max = 320.dp).then(tap)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            pcLine(state.connection)?.let { BasicText(it, style = stripStyle().copy(color = Theme.text, fontWeight = Theme.title.fontWeight), maxLines = 1) }
            BasicText(statusText(state), style = stripStyle(), maxLines = 3)
            if (actionText != null) {
                BasicText(actionText, Modifier.padding(top = 4.dp), style = stripStyle().copy(color = Theme.text, fontWeight = Theme.title.fontWeight))
            }
        }
    }
}

/** The PC's name above a status that doesn't already say it. */
private fun pcLine(c: Connection): String? = when (c) {
    is Connection.Connecting -> c.pc
    is Connection.Approving -> c.pc
    is Connection.Reconnecting -> c.pc
    is Connection.UpdateNeeded -> c.pc
    else -> null
}?.takeIf { it.isNotEmpty() }

@Composable
private fun stripStyle() = Theme.body.copy(color = Theme.ink, fontSize = 13.sp, lineHeight = 18.sp, textAlign = TextAlign.Center)

@Composable
private fun statusText(state: AppState): AnnotatedString = when (val c = state.connection) {
    is Connection.Searching -> AnnotatedString(
        if (tethering(state)) {
            stringResource(Copy.string.status_tether_hint)
        } else {
            c.unreachable?.let { stringResource(Copy.string.status_cant_reach, it) } ?: stringResource(
                when (c.stage) {
                    SearchStage.SEARCHING -> Copy.string.status_searching
                    SearchStage.NOT_FOUND -> Copy.string.status_not_found
                    SearchStage.HELP -> Copy.string.status_not_found_help
                },
            )
        },
    )
    is Connection.Choosing -> AnnotatedString(stringResource(Copy.string.status_choose, c.count.toString()))
    is Connection.Connecting -> AnnotatedString(stringResource(Copy.string.link_connecting))
    is Connection.Approving -> withCode(stringResource(Copy.string.status_approve, c.code), c.code)
    is Connection.Connected -> AnnotatedString(stringResource(Copy.string.status_connected))
    is Connection.Reconnecting -> AnnotatedString(stringResource(Copy.string.status_reconnecting))
    is Connection.Waiting -> AnnotatedString(stringResource(Copy.string.status_waiting, c.pc))
    is Connection.Denied -> AnnotatedString(stringResource(Copy.string.status_denied, c.pc))
    is Connection.Busy -> AnnotatedString(stringResource(Copy.string.status_busy, c.pc, c.owner))
    is Connection.UpdateNeeded -> AnnotatedString(
        stringResource(Copy.string.status_update, stringResource(if (c.phoneOutdated) Copy.string.device_phone else Copy.string.device_pc)),
    )
    is Connection.KeyChanged -> AnnotatedString(stringResource(Copy.string.status_key_changed, c.pc))
}

/** The approval code in Geist Mono at Display size inside its sentence (section 29.3). */
private fun withCode(text: String, code: String) = buildAnnotatedString {
    append(text)
    val at = text.lastIndexOf(code)
    if (at >= 0) {
        addStyle(
            SpanStyle(fontFamily = Theme.mono, fontSize = Theme.display.fontSize, fontWeight = Theme.display.fontWeight, color = Theme.text),
            at,
            at + code.length,
        )
    }
}

/** "720p · 24 fps", or "Paused" while the PC has paused the camera. */
@Composable
private fun Caption(state: AppState, modifier: Modifier) {
    val text = if (state.camera.phase == FeaturePhase.PAUSED) {
        stringResource(Copy.string.feature_paused)
    } else {
        val s = state.settings
        val link = (state.connection as? Connection.Connected)?.link ?: LinkKind.WIFI
        val fps = s["camera.frameRate"] ?: "24"
        val plan = VideoPlan.of(s["camera.quality"] ?: "auto", fps.toIntOrNull() ?: 24, link)
        val size = stringResource(if (plan.shortSide >= 1080) Copy.string.value_1080p else Copy.string.value_720p)
        stringResource(Copy.string.phone_camera_caption, size, fps)
    }
    val shape = RoundedCornerShape(8.dp)
    BasicText(
        text,
        modifier.clip(shape).background(Theme.bg).border(1.dp, Theme.stripEdge, shape).padding(horizontal = 8.dp, vertical = 4.dp),
        style = Theme.caption.copy(color = Theme.ink, fontFamily = Theme.mono, fontSize = 11.sp),
    )
}

/** Exactly what the PC receives, drawn by the camera's own GL pass into this surface. */
@Composable
private fun Preview() {
    AndroidView(
        factory = { context ->
            SurfaceView(context).apply {
                keepScreenOn = true
                holder.addCallback(
                    object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) = Unit

                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) =
                            Owlmic.setPreview(holder.surface, width, height)

                        override fun surfaceDestroyed(holder: SurfaceHolder) = Owlmic.setPreview(null, 0, 0)
                    },
                )
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

/** A SurfaceView ignores Compose's clip, so the grid's colour is painted over its corners to round them. */
@Composable
private fun CornerMask(modifier: Modifier) {
    Canvas(modifier) {
        val r = Theme.radius.toPx()
        val path = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(size.toRect())
            addRoundRect(RoundRect(size.toRect(), CornerRadius(r, r)))
        }
        drawPath(path, Theme.seam)
    }
}

private fun androidx.compose.ui.geometry.Size.toRect() = androidx.compose.ui.geometry.Rect(0f, 0f, width, height)

/** A feature tile: the prototype's icon, slashed while off, with a short note when it is paused, restarting or stuck. */
@Composable
private fun FeatureTile(up: Upright, state: AppState, feature: Feature, modifier: Modifier, onFeature: (Feature) -> Unit, onLongPress: () -> Unit) {
    val look = lookOf(state.feature(feature))
    val (name, glyph) = when (feature) {
        Feature.MIC -> Copy.string.feature_mic to if (look.on) Glyph.MIC else Glyph.MIC_OFF
        Feature.CAMERA -> Copy.string.feature_camera to if (look.on) Glyph.CAMERA else Glyph.CAMERA_OFF
        Feature.SPEAKER -> Copy.string.feature_speaker to if (look.on) Glyph.SPEAKER else Glyph.SPEAKER_OFF
    }
    val note = look.note?.let { stringResource(noteText(it, feature)) }
    val label = stringResource(name)
    val description = listOfNotNull(label, note ?: stringResource(if (look.on) Copy.string.feature_on else Copy.string.feature_off)).joinToString(", ")
    Tile(modifier.fillMaxHeight().semantics { contentDescription = description }, label = label, onTap = { onFeature(feature) }, onLongPress = onLongPress) { fg ->
        Column(Modifier.upright(up, swap = true), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(glyph, fg, null, size = Theme.glyph, stroke = Tokens.Phone.GLYPH_STROKE)
            if (note != null) {
                Spacer(Modifier.height(6.dp))
                BasicText(
                    note,
                    Modifier.padding(horizontal = 8.dp),
                    style = Theme.caption.copy(color = if (look.note?.alert == true) Theme.red else Theme.text2, fontSize = 11.sp, lineHeight = 14.sp, textAlign = TextAlign.Center),
                    maxLines = 3,
                )
            }
        }
    }
}

private fun noteText(note: TileNote, feature: Feature): Int = when (note) {
    TileNote.PAUSED -> Copy.string.feature_paused
    TileNote.RESTARTING -> Copy.string.feature_restarting
    TileNote.FAILED -> Copy.string.feature_failed
    TileNote.NO_BLUETOOTH -> Copy.string.camera_no_bluetooth
    TileNote.PERMISSION -> if (feature == Feature.CAMERA) Copy.string.camera_permission else Copy.string.mic_permission
}
