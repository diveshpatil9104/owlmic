package com.owlmic.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.owlmic.Owlmic
import com.owlmic.core.design.Tokens
import com.owlmic.core.hub.AppState
import com.owlmic.core.hub.LinkKind
import com.owlmic.hub.AppMsg
import com.owlmic.core.R as Copy

/** The drawers that open over the preview: the PCs and links, and setup and help. */
enum class Drawer { CONNECTION, HELP }

/**
 * Slides up from the preview's bottom edge and fades in, as in the prototype. Sideways, the drawer is laid out for the
 * turned shape and turns with the phone, so its rows run the reader's full width and it slides up from the reader's bottom.
 */
@Composable
fun AnimatedDrawer(up: Upright, drawer: Drawer?, state: AppState, onClose: () -> Unit) {
    // The drawer that is closing keeps its content while it slides away.
    var shown by remember { mutableStateOf(drawer ?: Drawer.CONNECTION) }
    if (drawer != null) shown = drawer
    val ease = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
    val slide = Tokens.Phone.DRAWER_SLIDE_MS.toInt()
    val fade = Tokens.Phone.DRAWER_FADE_MS.toInt()
    Box(Modifier.fillMaxSize().upright(up, swap = true)) {
        AnimatedVisibility(
            drawer != null,
            enter = slideInVertically(tween(slide, easing = ease)) { it } + fadeIn(tween(fade)),
            exit = slideOutVertically(tween(slide, easing = ease)) { it } + fadeOut(tween(fade)),
        ) {
            Column(Modifier.fillMaxSize().background(Theme.bg)) {
                when (shown) {
                    Drawer.CONNECTION -> ConnectionDrawer(state, onClose)
                    Drawer.HELP -> HelpDrawer(onClose)
                }
            }
        }
    }
}

@Composable
private fun Header(title: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(Tokens.Phone.DRAWER_HEADER_DP.dp).rule().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val back = stringResource(Copy.string.ui_back)
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClickLabel = back, onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Glyph.BACK, Theme.text, back, size = 22.dp, stroke = Tokens.Phone.TOP_GLYPH_STROKE)
        }
        Spacer(Modifier.weight(1f))
        BasicText(title, style = text(16.sp, FontWeight.SemiBold, Theme.text))
    }
}

/** A 1 dp line under a row, end to end. */
private fun Modifier.rule() = drawBehind {
    val y = size.height - 0.5.dp.toPx()
    drawLine(Theme.rule, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
}

private fun text(size: TextUnit, weight: FontWeight, color: Color, mono: Boolean = false) = TextStyle(
    fontFamily = if (mono) Theme.mono else Theme.sans,
    fontSize = size,
    fontWeight = weight,
    color = color,
)

@Composable
private fun DrawerRow(onClick: (() -> Unit)? = null, content: @Composable RowScope.() -> Unit) {
    val tap = if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier
    Row(
        Modifier.fillMaxWidth().then(tap).rule().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
private fun RowScope.Lines(name: String, detail: String?, nameStyle: TextStyle, detailMono: Boolean) {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        BasicText(name, style = nameStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (detail != null) BasicText(detail, style = text(11.sp, FontWeight.Normal, Theme.text2, mono = detailMono), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A small grey label, or a green one for [live]. */
@Composable
private fun Tag(label: String, live: Boolean = false, size: TextUnit = 11.sp) {
    BasicText(
        label,
        Modifier.clip(RoundedCornerShape(4.dp)).background(if (live) Theme.green.copy(alpha = 0.1f) else Theme.chip).padding(horizontal = 8.dp, vertical = 2.dp),
        style = text(size, FontWeight.SemiBold, if (live) Theme.green else Theme.text2),
    )
}

/** The PCs: the connected one, those that answered (tap to pick), the other paired ones and addresses added by hand. */
@Composable
private fun ColumnScope.ConnectionDrawer(state: AppState, onClose: () -> Unit) {
    Header(stringResource(Copy.string.section_connection), onClose)
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        val rows = pcRows(state)
        if (rows.isEmpty()) {
            DrawerRow { BasicText(stringResource(Copy.string.phone_no_pcs), style = text(13.sp, FontWeight.Normal, Theme.text2)) }
        }
        rows.forEach { row ->
            val pick = row.chooseId?.let { id ->
                {
                    Owlmic.post(AppMsg.ChoosePc(id))
                    onClose()
                }
            }
            DrawerRow(onClick = pick) {
                Lines(
                    row.name ?: stringResource(Copy.string.phone_pc_direct_ip),
                    meta(row),
                    text(14.sp, FontWeight.Bold, Theme.text),
                    detailMono = true,
                )
                when (row.badge) {
                    PcBadge.ACTIVE -> Active()
                    PcBadge.READY -> Tag(stringResource(Copy.string.phone_pc_ready))
                    PcBadge.STANDBY -> Tag(stringResource(Copy.string.phone_pc_standby))
                    null -> Unit
                }
            }
        }
    }
    LinksBar(state)
}

@Composable
private fun meta(row: PcRow): String? = when {
    row.name == null -> row.address
    row.badge == PcBadge.STANDBY -> stringResource(Copy.string.phone_pc_paired)
    row.link != null -> {
        val link = if (row.hotspot) stringResource(Copy.string.phone_link_hotspot) else stringResource(linkName(row.link))
        row.address?.let { stringResource(Copy.string.phone_pc_meta, it, link) } ?: link
    }
    else -> null
}

private fun linkName(link: LinkKind) = when (link) {
    LinkKind.USB_DEBUGGING -> Copy.string.setting_link_usb_debugging
    LinkKind.USB_TETHERING -> Copy.string.setting_link_usb_tethering
    LinkKind.WIFI -> Copy.string.setting_link_wifi
    LinkKind.BLUETOOTH -> Copy.string.setting_link_bluetooth
}

@Composable
private fun Active() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(Theme.green))
        BasicText(stringResource(Copy.string.phone_pc_active), style = text(11.sp, FontWeight.SemiBold, Theme.green))
    }
}

/** The four links, each switched on or off for this PC (the same settings as Settings → Connection). */
@Composable
private fun ColumnScope.LinksBar(state: AppState) {
    Row(
        Modifier.fillMaxWidth().drawBehind { drawLine(Theme.rule, Offset(0f, 0.5.dp.toPx()), Offset(size.width, 0.5.dp.toPx()), 1.dp.toPx()) }
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LINK_SETTINGS.forEach { (link, id) ->
            val on = (state.settings[id] ?: "on") == "on"
            val name = stringResource(linkName(link))
            val description = "$name, ${stringResource(if (on) Copy.string.value_on else Copy.string.value_off)}"
            val shape = RoundedCornerShape(10.dp)
            Box(
                Modifier.weight(1f).height(Tokens.Phone.LINK_BUTTON_DP.dp).clip(shape)
                    .background(if (on) Theme.chipOn else Theme.bg).border(1.dp, if (on) Theme.chipOnEdge else Theme.chipOffEdge, shape)
                    .semantics { contentDescription = description }
                    .clickable(role = Role.Switch) { Owlmic.post(AppMsg.Setting(id, if (on) "off" else "on")) },
                contentAlignment = Alignment.Center,
            ) {
                val glyph = if (link == LinkKind.WIFI) Glyph.WIFI_LINK else glyphOf(link)
                Icon(glyph, if (on) Theme.text else Theme.disabled, null, Modifier.alpha(if (on) 1f else 0.45f), size = 18.dp, stroke = 2.2f)
            }
        }
    }
}

/** How to get going in three steps, the privacy policy, and the version. */
@Composable
private fun ColumnScope.HelpDrawer(onClose: () -> Unit) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    // A phone without a browser has nothing to open a link with; the tap then does nothing.
    val open: (String) -> Unit = { url -> runCatching { uri.openUri(url) } }
    val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
    Header(stringResource(Copy.string.phone_help), onClose)
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        Step("01", Copy.string.phone_step_download, Copy.string.phone_step_download_detail, onClick = { open(RELEASES) }) {
            Icon(Glyph.OPEN, Theme.text2, null, size = 14.dp, stroke = Tokens.Phone.GLYPH_STROKE)
        }
        Step("02", Copy.string.phone_step_connect, Copy.string.phone_step_connect_detail) {
            Tag(stringResource(Copy.string.phone_step_auto), size = 10.sp)
        }
        Step("03", Copy.string.phone_step_stream, Copy.string.phone_step_stream_detail) {
            Tag(stringResource(Copy.string.phone_step_live), live = true, size = 10.sp)
        }
        DrawerRow(onClick = { open(PRIVACY) }) {
            BasicText(stringResource(Copy.string.ui_privacy), Modifier.weight(1f), style = text(13.sp, FontWeight.SemiBold, Theme.text))
            Icon(Glyph.NEXT, Theme.text2, null, size = 14.dp, stroke = Tokens.Phone.GLYPH_STROKE)
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        val dim = text(11.sp, FontWeight.Normal, Theme.faint, mono = true)
        BasicText(stringResource(Copy.string.phone_footer), Modifier.weight(1f), style = dim)
        BasicText(stringResource(Copy.string.ui_version_tag, version), style = dim)
    }
}

@Composable
private fun Step(number: String, name: Int, detail: Int, onClick: (() -> Unit)? = null, end: @Composable () -> Unit) {
    DrawerRow(onClick = onClick) {
        BasicText(number, style = text(12.sp, FontWeight.Bold, Theme.text2, mono = true))
        Lines(stringResource(name), stringResource(detail), text(13.sp, FontWeight.SemiBold, Theme.text), detailMono = false)
        end()
    }
}

private const val RELEASES = "https://github.com/diveshpatil9104/owlmic/releases"
private const val PRIVACY = "https://github.com/diveshpatil9104/owlmic/blob/main/docs/user/privacy.md"
