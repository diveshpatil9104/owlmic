package com.owlmic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.owlmic.Owlmic
import com.owlmic.core.design.SettingsModel
import com.owlmic.core.design.Tokens
import com.owlmic.core.hub.AppState
import com.owlmic.hub.AppMsg
import com.owlmic.core.R as Copy

/**
 * The settings sheet (section 31): every phone setting inline, one step from the main screen. Choices are segmented
 * rows, never dialogs. Changes go to the Settings Hub, which saves them and syncs them with the PC.
 */
@Composable
fun SettingsSheet(state: AppState, startAt: String, onClose: () -> Unit) {
    val scroll = rememberScrollState()
    var cameraAt by remember { mutableIntStateOf(-1) }
    LaunchedEffect(startAt, cameraAt) {
        if (startAt == SECTION_CAMERA && cameraAt >= 0) scroll.scrollTo(cameraAt)
    }
    val line = hairline()
    Column(Modifier.fillMaxSize().background(Theme.bg).windowInsetsPadding(WindowInsets.safeDrawing).background(Theme.hairline)) {
        Row(
            Modifier.fillMaxWidth().height(Tokens.Phone.HEADER_DP.dp).background(Theme.tile).padding(start = Theme.padding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(stringResource(Copy.string.ui_settings), Modifier.weight(1f), style = Theme.title)
            val close = stringResource(Copy.string.ui_close)
            Box(Modifier.size(Tokens.Phone.HEADER_DP.dp).clickable(onClickLabel = close, onClick = onClose), contentAlignment = Alignment.Center) {
                Icon(Glyph.CLOSE, Theme.text, close)
            }
        }
        Column(Modifier.padding(top = line).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(line)) {
            Section(Copy.string.section_mic) {
                Choice(state, "mic.noiseReduction", Copy.string.setting_mic_noise_reduction)
                Choice(state, "mic.boost", Copy.string.setting_mic_boost)
            }
            Section(Copy.string.section_camera, Modifier.onGloballyPositioned { cameraAt = it.positionInParent().y.toInt() }) {
                Choice(state, "camera.lens", Copy.string.setting_camera_lens)
                Choice(state, "camera.quality", Copy.string.setting_camera_quality)
                Choice(state, "camera.frameRate", Copy.string.setting_camera_frame_rate)
                Choice(state, "camera.orientation", Copy.string.setting_camera_orientation)
            }
            Section(Copy.string.section_speaker) {
                Note(stringResource(Copy.string.phone_speaker_help))
            }
            Section(Copy.string.section_connection) {
                Choice(state, "link.usbDebugging", Copy.string.setting_link_usb_debugging)
                Choice(state, "link.usbTethering", Copy.string.setting_link_usb_tethering)
                Choice(state, "link.wifi", Copy.string.setting_link_wifi)
                Choice(state, "link.bluetooth", Copy.string.setting_link_bluetooth)
                Addresses(state)
            }
            Section(Copy.string.section_pcs) {
                if (state.knownPcs.isEmpty()) Note(stringResource(Copy.string.phone_no_pcs))
                state.knownPcs.forEach { pc ->
                    ItemRow(pc.name, if (pc.current) stringResource(Copy.string.ui_connected) else null, stringResource(Copy.string.ui_forget)) {
                        Owlmic.post(AppMsg.Forget(pc.id))
                    }
                }
            }
            Section(Copy.string.section_help) {
                Note(stringResource(Copy.string.help_background))
            }
            Section(Copy.string.section_about) {
                About()
            }
        }
    }
}

@Composable
private fun Section(title: Int, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().background(Theme.tile).padding(vertical = Theme.padding / 2)) {
        BasicText(
            stringResource(title).uppercase(),
            Modifier.padding(horizontal = Theme.padding, vertical = Theme.padding / 2),
            style = Theme.caption.copy(color = Theme.text2),
        )
        content()
    }
}

@Composable
private fun Note(text: String) {
    BasicText(text, Modifier.padding(horizontal = Theme.padding, vertical = 8.dp), style = Theme.body.copy(color = Theme.text2))
}

private fun valueLabel(value: String): Int = when (value) {
    "phone" -> Copy.string.value_phone
    "phoneAndPc" -> Copy.string.value_phone_and_pc
    "off" -> Copy.string.value_off
    "on" -> Copy.string.value_on
    "plus6" -> Copy.string.value_plus6
    "plus12" -> Copy.string.value_plus12
    "back" -> Copy.string.value_back
    "front" -> Copy.string.value_front
    "auto" -> Copy.string.value_auto
    "720p" -> Copy.string.value_720p
    "1080p" -> Copy.string.value_1080p
    "24" -> Copy.string.value_24
    "30" -> Copy.string.value_30
    "60" -> Copy.string.value_60
    "landscape" -> Copy.string.value_landscape
    "portrait" -> Copy.string.value_portrait
    "fill" -> Copy.string.value_fill
    else -> Copy.string.value_fit
}

/** A setting as a label over a segmented row of all its values; the current one is white. */
@Composable
private fun Choice(state: AppState, id: String, label: Int) {
    val def = SettingsModel.find(id) ?: return
    val current = state.settings[id] ?: def.default
    val line = hairline()
    Column(Modifier.fillMaxWidth().padding(horizontal = Theme.padding, vertical = 8.dp)) {
        BasicText(stringResource(label), Modifier.padding(bottom = 8.dp), style = Theme.body)
        Row(Modifier.fillMaxWidth().background(Theme.hairline).padding(line), horizontalArrangement = Arrangement.spacedBy(line)) {
            def.values.forEach { value ->
                val selected = value == current
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = Theme.touch)
                        .background(if (selected) Theme.on else Theme.tile)
                        .semantics { this.selected = selected }
                        .clickable(role = Role.RadioButton) { if (!selected) Owlmic.post(AppMsg.Setting(id, value)) },
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        stringResource(valueLabel(value)),
                        Modifier.padding(horizontal = 4.dp),
                        style = Theme.body.copy(color = if (selected) Theme.onContent else Theme.text, textAlign = TextAlign.Center),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun ItemRow(name: String, detail: String?, action: String, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = Theme.touch).padding(start = Theme.padding), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            BasicText(name, style = Theme.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail != null) BasicText(detail, style = Theme.caption.copy(color = Theme.text2))
        }
        Box(Modifier.heightIn(min = Theme.touch).clickable(onClick = onAction).padding(horizontal = Theme.padding), contentAlignment = Alignment.Center) {
            BasicText(action, style = Theme.body.copy(color = Theme.text2))
        }
    }
}

/** Add PC by address: for networks where broadcasts don't get through (section 14.2). */
@Composable
private fun Addresses(state: AppState) {
    var typed by remember { mutableStateOf("") }
    val add = {
        val address = typed.trim()
        if (address.isNotEmpty()) Owlmic.post(AppMsg.AddAddress(address))
        typed = ""
    }
    BasicText(
        stringResource(Copy.string.phone_add_pc),
        Modifier.padding(start = Theme.padding, end = Theme.padding, top = 8.dp),
        style = Theme.body,
    )
    Row(Modifier.fillMaxWidth().padding(start = Theme.padding), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).heightIn(min = Theme.touch), contentAlignment = Alignment.CenterStart) {
            if (typed.isEmpty()) BasicText(stringResource(Copy.string.phone_address_hint), style = Theme.body.copy(color = Theme.disabled, fontFamily = Theme.mono))
            BasicTextField(
                typed,
                { typed = it },
                Modifier.fillMaxWidth(),
                textStyle = Theme.body.copy(fontFamily = Theme.mono),
                singleLine = true,
                cursorBrush = SolidColor(Theme.text),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add() }),
            )
        }
        Box(Modifier.heightIn(min = Theme.touch).clickable { add() }.padding(horizontal = Theme.padding), contentAlignment = Alignment.Center) {
            BasicText(stringResource(Copy.string.ui_add), style = Theme.body)
        }
    }
    state.manualAddresses.forEach { address ->
        ItemRow(address, null, stringResource(Copy.string.ui_remove)) { Owlmic.post(AppMsg.RemoveAddress(address)) }
    }
}

/** Version, the MIT licence, the website, the third-party notices (shown inline, from the app itself) and Donate. */
@Composable
private fun About() {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    // A phone without a browser has nothing to open a link with; the tap then does nothing.
    val open: (String) -> Unit = { url -> runCatching { uri.openUri(url) } }
    val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
    var notices by remember { mutableStateOf<String?>(null) }
    Note(stringResource(Copy.string.ui_version, version))
    Link(stringResource(Copy.string.ui_mit)) { open(LICENSE) }
    Link(stringResource(Copy.string.ui_website)) { open(WEBSITE) }
    Link(stringResource(Copy.string.ui_licences)) {
        notices = if (notices != null) null else runCatching { context.assets.open(NOTICES).bufferedReader().use { it.readText() } }.getOrNull()
    }
    notices?.let { BasicText(it, Modifier.padding(horizontal = Theme.padding, vertical = 8.dp), style = Theme.caption.copy(color = Theme.text2)) }
    Link(stringResource(Copy.string.ui_donate)) { open(DONATE) }
}

@Composable
private fun Link(text: String, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().heightIn(min = Theme.touch).clickable(onClick = onClick).padding(horizontal = Theme.padding), contentAlignment = Alignment.CenterStart) {
        BasicText(text, style = Theme.body)
    }
}

private const val WEBSITE = "https://owlmic.app"
private const val LICENSE = "https://github.com/diveshpatil9104/owlmic/blob/main/LICENSE"
private const val NOTICES = "licences.txt"
private const val DONATE = "https://github.com/sponsors/diveshpatil9104"
