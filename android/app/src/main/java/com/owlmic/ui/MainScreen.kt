package com.owlmic.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.owlmic.Owlmic
import com.owlmic.core.design.Names
import com.owlmic.core.design.Tokens
import com.owlmic.core.hub.AppState
import com.owlmic.core.hub.Connection
import com.owlmic.core.hub.Feature
import com.owlmic.core.hub.FeaturePhase
import com.owlmic.core.hub.FeatureProblem
import com.owlmic.core.hub.FeatureView
import com.owlmic.core.hub.LinkKind
import com.owlmic.core.hub.SearchStage
import com.owlmic.core.hub.SpeakerOutput
import com.owlmic.hub.AppMsg
import com.owlmic.media.camera.VideoPlan
import com.owlmic.core.R as Copy

/**
 * The one screen (section 30): header, camera, mic and speaker, status and the connection indicator, in a bento grid
 * with 1 px lines. [onFeature] and [onTryBluetooth] go through the activity, which asks for permissions first.
 */
@Composable
fun MainScreen(onFeature: (Feature) -> Unit, onTryBluetooth: () -> Unit) {
    val state by Owlmic.state.collectAsState()
    var sheet by remember { mutableStateOf<String?>(null) }
    var chooser by remember { mutableStateOf(false) }
    val choosing = chooser && state.connection is Connection.Choosing
    val line = hairline()

    Box(Modifier.fillMaxSize().background(Theme.bg)) {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).background(Theme.hairline),
            verticalArrangement = Arrangement.spacedBy(line),
        ) {
            Header(onSettings = { sheet = SECTION_TOP })
            Box(Modifier.weight(CAMERA_SHARE).fillMaxWidth()) {
                if (choosing) {
                    Chooser(state) { id ->
                        chooser = false
                        Owlmic.post(AppMsg.ChoosePc(id))
                    }
                } else {
                    CameraTile(state, onTap = { onFeature(Feature.CAMERA) }, onLongPress = { sheet = SECTION_CAMERA })
                }
            }
            Row(Modifier.weight(1f - CAMERA_SHARE).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(line)) {
                MicTile(state, Modifier.weight(1f)) { onFeature(Feature.MIC) }
                SpeakerTile(state, Modifier.weight(1f)) { onFeature(Feature.SPEAKER) }
            }
            Row(Modifier.height(Tokens.Phone.STATUS_ROW_DP.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(line)) {
                StatusTile(
                    state,
                    Modifier.weight(1f),
                    onTap = {
                        when {
                            state.connection is Connection.Choosing -> chooser = !chooser
                            state.connection is Connection.Denied -> Owlmic.post(AppMsg.AskAgain)
                            state.bluetoothOffer -> onTryBluetooth()
                        }
                    },
                )
                Indicator(state.connection, Modifier.width(Tokens.Phone.STATUS_ROW_DP.dp))
            }
        }
        AnimatedVisibility(sheet != null, enter = fadeIn(tween(Theme.STATE_MS)), exit = fadeOut(tween(Theme.STATE_MS))) {
            SettingsSheet(state, startAt = sheet ?: SECTION_TOP, onClose = { sheet = null })
        }
    }
    BackHandler(sheet != null || choosing) {
        if (sheet != null) sheet = null else chooser = false
    }
}

const val SECTION_TOP = "top"
const val SECTION_CAMERA = "camera"

/** The camera tile takes about 46% of the screen and the mic and speaker row about 26% (section 30). */
private const val CAMERA_SHARE = 0.64f

@Composable
private fun Header(onSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(Tokens.Phone.HEADER_DP.dp).background(Theme.tile).padding(start = Theme.padding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(Names.WORDMARK, Modifier.weight(1f), style = Theme.title)
        val label = stringResource(Copy.string.ui_settings)
        Box(Modifier.size(Tokens.Phone.HEADER_DP.dp).clickable(onClickLabel = label, onClick = onSettings), contentAlignment = Alignment.Center) {
            Icon(Glyph.SETTINGS, Theme.text, label)
        }
    }
}

/** A grid tile. White when [on] (section 29.2); the colour change cross-fades in 150 ms. */
@Composable
private fun Tile(
    modifier: Modifier,
    on: Boolean = false,
    onTap: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    content: @Composable BoxScope.(fg: Color) -> Unit,
) {
    val bg by animateColorAsState(if (on) Theme.on else Theme.tile, tween(Theme.STATE_MS), label = "tile")
    val fg by animateColorAsState(if (on) Theme.onContent else Theme.text, tween(Theme.STATE_MS), label = "content")
    val tap = if (onTap != null || onLongPress != null) Modifier.combinedClickable(onLongClick = onLongPress, onClick = onTap ?: {}) else Modifier
    Box(modifier.fillMaxSize().background(bg).then(tap)) { content(fg) }
}

private data class FeatureText(val text: String, val alert: Boolean = false)

@Composable
private fun featureText(v: FeatureView): FeatureText = when {
    v.problem == FeatureProblem.PERMISSION -> FeatureText("", alert = true)
    v.problem == FeatureProblem.NO_BLUETOOTH -> FeatureText(stringResource(Copy.string.camera_no_bluetooth), alert = true)
    v.phase == FeaturePhase.RECOVERING -> FeatureText(stringResource(Copy.string.feature_restarting))
    v.phase == FeaturePhase.PAUSED -> FeatureText(stringResource(Copy.string.feature_paused))
    v.phase == FeaturePhase.FAILED -> FeatureText(stringResource(Copy.string.feature_off), alert = true)
    v.isOn -> FeatureText(stringResource(Copy.string.feature_on))
    else -> FeatureText(stringResource(Copy.string.feature_off))
}

/** Icon, name and state, centred. Dimmed while there is no PC to send to. */
@Composable
private fun BoxScope.FeatureFace(glyph: Glyph, name: String, state: FeatureText, fg: Color, dimmed: Boolean, detail: String? = null) {
    val main = if (dimmed) Theme.disabled else fg
    val secondary = when {
        dimmed -> Theme.disabled
        state.alert -> Theme.red
        fg == Theme.onContent -> Theme.onContent
        else -> Theme.text2
    }
    Column(Modifier.align(Alignment.Center).padding(Theme.padding), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(glyph, main, null)
        Spacer(Modifier.height(8.dp))
        BasicText(name, style = Theme.title.copy(color = main))
        if (!dimmed && state.text.isNotEmpty()) {
            BasicText(state.text, style = Theme.body.copy(color = secondary, textAlign = TextAlign.Center))
        }
        if (!dimmed && detail != null) {
            BasicText(detail, style = Theme.caption.copy(color = secondary, textAlign = TextAlign.Center))
        }
    }
}

private fun dimmed(state: AppState, v: FeatureView) = !state.connected && v.phase == FeaturePhase.OFF && v.problem == null

@Composable
private fun MicTile(state: AppState, modifier: Modifier, onTap: () -> Unit) {
    val v = state.mic
    val dim = dimmed(state, v)
    val text = if (v.problem == FeatureProblem.PERMISSION) FeatureText(stringResource(Copy.string.mic_permission), true) else featureText(v)
    Tile(modifier, on = v.isOn, onTap = onTap.takeUnless { dim }) { fg ->
        FeatureFace(Glyph.MIC, stringResource(Copy.string.feature_mic), text, fg, dim)
        if (v.phase == FeaturePhase.ON) {
            val level by Owlmic.level.collectAsState()
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(level.coerceIn(0f, 1f)).height(3.dp).background(fg))
        }
    }
}

@Composable
private fun SpeakerTile(state: AppState, modifier: Modifier, onTap: () -> Unit) {
    val v = state.speaker
    val dim = dimmed(state, v)
    val output = if (v.isOn) {
        stringResource(
            when (state.speakerOutput) {
                SpeakerOutput.SPEAKER -> Copy.string.phone_output_speaker
                SpeakerOutput.HEADPHONES -> Copy.string.phone_output_headphones
                SpeakerOutput.BLUETOOTH -> Copy.string.phone_output_bluetooth
            },
        )
    } else {
        null
    }
    Tile(modifier, on = v.isOn, onTap = onTap.takeUnless { dim }) { fg ->
        FeatureFace(Glyph.SPEAKER, stringResource(Copy.string.feature_speaker), featureText(v), fg, dim, output)
    }
}

@Composable
private fun CameraTile(state: AppState, onTap: () -> Unit, onLongPress: () -> Unit) {
    val v = state.camera
    val dim = dimmed(state, v)
    val live = v.isOn || v.phase == FeaturePhase.PAUSED
    Tile(Modifier, onTap = onTap.takeUnless { dim }, onLongPress = onLongPress) { fg ->
        if (live) {
            Preview()
            Caption(state, Modifier.align(Alignment.BottomStart))
            val flip = stringResource(Copy.string.phone_flip_camera)
            Box(
                Modifier.align(Alignment.TopEnd).size(Theme.touch).background(Theme.bg).clickable(onClickLabel = flip) { Owlmic.post(AppMsg.FlipLens) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Glyph.FLIP, Theme.text, flip)
            }
        } else {
            val text = if (v.problem == FeatureProblem.PERMISSION) FeatureText(stringResource(Copy.string.camera_permission), true) else featureText(v)
            FeatureFace(Glyph.CAMERA, stringResource(Copy.string.feature_camera), text, fg, dim)
        }
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
    BasicText(text, modifier.background(Theme.bg).padding(horizontal = 8.dp, vertical = 4.dp), style = Theme.caption)
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

/** Several new PCs answered: the list replaces the camera tile until one is picked (section 30.2). */
@Composable
private fun Chooser(state: AppState, onPick: (String) -> Unit) {
    val line = hairline()
    Column(Modifier.fillMaxSize().background(Theme.tile)) {
        BasicText(stringResource(Copy.string.phone_choose_pc), Modifier.padding(Theme.padding), style = Theme.title)
        Column(Modifier.verticalScroll(rememberScrollState()).background(Theme.hairline), verticalArrangement = Arrangement.spacedBy(line)) {
            Spacer(Modifier.height(0.dp))
            state.choices.forEach { pc ->
                Box(
                    Modifier.fillMaxWidth().height(Theme.touch + 8.dp).background(Theme.tile).clickable { onPick(pc.id) }.padding(horizontal = Theme.padding),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    BasicText(pc.name, style = Theme.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun StatusTile(state: AppState, modifier: Modifier, onTap: () -> Unit) {
    val c = state.connection
    val pc: String? = when (c) {
        is Connection.Connecting -> c.pc
        is Connection.Approving -> c.pc
        is Connection.Connected -> c.pc
        is Connection.Reconnecting -> c.pc
        is Connection.Waiting -> c.pc
        is Connection.Denied -> c.pc
        is Connection.Busy -> c.pc
        else -> null
    }
    val text: AnnotatedString = when {
        state.adbNeedsAllow -> AnnotatedString(stringResource(Copy.string.adb_allow))
        else -> when (c) {
            is Connection.Searching -> AnnotatedString(
                stringResource(
                    when (c.stage) {
                        SearchStage.SEARCHING -> Copy.string.status_searching
                        SearchStage.NOT_FOUND -> Copy.string.status_not_found
                        SearchStage.HELP -> Copy.string.status_not_found_help
                    },
                ),
            )
            is Connection.Choosing -> AnnotatedString(stringResource(Copy.string.status_choose, c.count.toString()))
            is Connection.Connecting -> AnnotatedString(stringResource(Copy.string.link_connecting))
            is Connection.Approving -> withMono(stringResource(Copy.string.status_approve, c.code), c.code)
            is Connection.Connected -> AnnotatedString(stringResource(Copy.string.status_connected))
            is Connection.Reconnecting -> AnnotatedString(stringResource(Copy.string.status_reconnecting))
            is Connection.Waiting -> AnnotatedString(stringResource(Copy.string.status_waiting, c.pc))
            is Connection.Denied -> AnnotatedString(stringResource(Copy.string.status_denied, c.pc))
            is Connection.Busy -> AnnotatedString(stringResource(Copy.string.status_busy, c.pc, c.owner))
            is Connection.UpdateNeeded -> AnnotatedString(stringResource(Copy.string.status_update, c.pc ?: stringResource(Copy.string.value_phone)))
        }
    }
    val offer = state.bluetoothOffer && c is Connection.Searching
    Tile(modifier, onTap = onTap) {
        Column(Modifier.fillMaxHeight().padding(horizontal = Theme.padding), verticalArrangement = Arrangement.Center) {
            if (pc != null) BasicText(pc, style = Theme.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicText(
                text,
                style = Theme.body.copy(color = Theme.text2),
                maxLines = if (pc == null && !offer) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (offer) BasicText(stringResource(Copy.string.bt_try), style = Theme.body)
        }
    }
}

/** The approval code in Geist Mono inside its sentence (section 29.3). */
private fun withMono(text: String, code: String) = buildAnnotatedString {
    append(text)
    val at = text.lastIndexOf(code)
    if (at >= 0) addStyle(SpanStyle(fontFamily = Theme.mono, color = Theme.text), at, at + code.length)
}

/** Section 36: the colour is the state, the icon is the link. Green is never a dot. */
@Composable
private fun Indicator(c: Connection, modifier: Modifier) {
    val (color, glyph, label) = when (c) {
        is Connection.Connecting -> Triple(Theme.yellow, glyphOf(c.link), Copy.string.link_connecting)
        is Connection.Approving -> Triple(Theme.yellow, glyphOf(c.link), Copy.string.link_approval)
        is Connection.Reconnecting -> Triple(Theme.yellow, glyphOf(c.link), Copy.string.status_reconnecting)
        is Connection.Connected -> when {
            c.weak -> Triple(Theme.yellow, glyphOf(c.link), Copy.string.link_weak)
            else -> Triple(
                Theme.green,
                glyphOf(c.link),
                when (c.link) {
                    LinkKind.USB_DEBUGGING -> Copy.string.link_usb_debugging
                    LinkKind.USB_TETHERING -> Copy.string.link_usb_tethering
                    LinkKind.WIFI -> if (c.hotspot) Copy.string.link_hotspot else Copy.string.link_wifi
                    LinkKind.BLUETOOTH -> Copy.string.link_bluetooth
                },
            )
        }
        else -> Triple(Theme.red, Glyph.UNPLUG, Copy.string.link_not_connected)
    }
    val tint by animateColorAsState(color, tween(Theme.STATE_MS), label = "indicator")
    val description = stringResource(label)
    Box(modifier.fillMaxHeight().background(Theme.tile).semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Icon(glyph, tint, null)
    }
}

private fun glyphOf(link: LinkKind?) = when (link) {
    LinkKind.USB_DEBUGGING -> Glyph.USB
    LinkKind.USB_TETHERING -> Glyph.CABLE
    LinkKind.WIFI -> Glyph.WIFI
    LinkKind.BLUETOOTH -> Glyph.BLUETOOTH
    null -> Glyph.UNPLUG
}

