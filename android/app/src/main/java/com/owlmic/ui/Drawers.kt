package com.owlmic.ui

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.owlmic.Owlmic
import com.owlmic.core.design.Tokens
import com.owlmic.core.hub.AppState
import com.owlmic.core.hub.LinkKind
import com.owlmic.hub.AppMsg
import kotlinx.coroutines.launch
import com.owlmic.core.R as Copy

/** The pages that open over the preview from the top bar: the PCs and links, and setup and help. */
enum class Drawer { CONNECTION, HELP }

/**
 * Slides down from the top bar, where its tile is, and fades in. Content only: no header; the tile that opened it, or
 * Back, closes it. Sideways, the page is laid out for the reader and turns with the phone, but still comes from the
 * screen's top edge, from its tile.
 */
@Composable
fun AnimatedDrawer(seq: TurnSequence, drawer: Drawer?, state: AppState) {
    // The drawer that is closing keeps its content while it slides away.
    var shown by remember { mutableStateOf(drawer ?: Drawer.CONNECTION) }
    if (drawer != null) shown = drawer
    val ease = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
    val slide = Tokens.Phone.DRAWER_SLIDE_MS.toInt()
    val fade = Tokens.Phone.DRAWER_FADE_MS.toInt()
    AnimatedVisibility(
        drawer != null,
        Modifier.fillMaxSize(),
        enter = slideInVertically(tween(slide, easing = ease)) { -it } + fadeIn(tween(fade)),
        exit = slideOutVertically(tween(slide, easing = ease)) { -it } + fadeOut(tween(fade)),
    ) {
        val fading by seq.fade
        Column(Modifier.fillMaxSize().background(Theme.bg).upright(seq.layout, swap = true).graphicsLayer { alpha = fading }) {
            when (shown) {
                Drawer.CONNECTION -> ConnectionDrawer(state)
                Drawer.HELP -> InfoDrawer()
            }
        }
    }
}

/** A 1 dp line under a row, end to end. */
private fun Modifier.rule() = drawBehind {
    val y = size.height - 0.5.dp.toPx()
    drawLine(Theme.rule, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
}

/**
 * The PCs: the connected one first with a tick, then the others this phone used, new ones that answered and addresses
 * added by hand; blocked PCs below a line. A row is only the name; it opens in place to show its address, the link it
 * used, when it was last connected and what can be done with it. Reconnect and the links stay at the bottom.
 */
@Composable
private fun ColumnScope.ConnectionDrawer(state: AppState) {
    val list = pcList(state)
    var opened by rememberSaveable { mutableStateOf<String?>(null) }
    val toggle = { key: String -> opened = if (opened == key) null else key }
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).animateContentSize()) {
        if (list.rows.isEmpty()) {
            BasicText(stringResource(Copy.string.phone_no_pcs), Modifier.padding(20.dp), style = Theme.pageDetail)
        }
        list.rows.forEach { row -> PcEntry(row, opened == row.key) { toggle(row.key) } }
        if (list.blocked.isNotEmpty()) {
            Box(Modifier.fillMaxWidth().padding(top = 16.dp).height(1.dp).background(Theme.chipOnEdge))
            list.blocked.forEach { row -> PcEntry(row, opened == row.key) { toggle(row.key) } }
        }
    }
    Footer(state)
}

@Composable
private fun PcEntry(row: PcRow, open: Boolean, onToggle: () -> Unit) {
    val name = row.name?.takeIf { it.isNotEmpty() } ?: if (row.kind == PcKind.ADDRESS) stringResource(Copy.string.phone_pc_direct_ip) else row.address.orEmpty()
    val connected = stringResource(Copy.string.ui_connected)
    Column(Modifier.fillMaxWidth().rule()) {
        Row(
            Modifier
                .fillMaxWidth()
                .semantics { if (row.kind == PcKind.CURRENT) stateDescription = connected }
                .clickable(role = Role.Button, onClick = onToggle)
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                if (row.kind == PcKind.CURRENT) Icon(Glyph.CHECK, Theme.text, null, size = 24.dp, stroke = 2.6f)
            }
            BasicText(name, Modifier.weight(1f), style = Theme.pageName, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.fillMaxWidth().padding(start = 58.dp, end = 20.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Where and how: the address, then the link it used, as a quiet icon.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicText(row.address.orEmpty(), Modifier.weight(1f), style = Theme.pageText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    row.link?.let { Icon(glyphOf(it), Theme.text2, stringResource(linkName(it)), size = 20.dp, stroke = Tokens.Phone.GLYPH_STROKE) }
                }
                // When, and what can be done.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    BasicText(if (row.kind == PcKind.ADDRESS) "" else lastConnected(row), Modifier.weight(1f), style = Theme.pageText.copy(color = Theme.text2), maxLines = 1)
                    val id = row.id
                    if (row.canConnect && id != null) TextAction(stringResource(Copy.string.ui_connect)) { Owlmic.post(AppMsg.ChoosePc(id)) }
                    when {
                        row.kind == PcKind.ADDRESS -> row.address?.let { a -> TextAction(stringResource(Copy.string.ui_remove)) { Owlmic.post(AppMsg.RemoveAddress(a)) } }
                        id == null -> Unit
                        row.kind == PcKind.BLOCKED -> TextAction(stringResource(Copy.string.ui_unblock)) { Owlmic.post(AppMsg.Unblock(id)) }
                        else -> TextAction(stringResource(Copy.string.ui_block)) { Owlmic.post(AppMsg.Block(id, row.name.orEmpty())) }
                    }
                }
            }
        }
    }
}

@Composable
private fun lastConnected(row: PcRow): String = when {
    row.kind == PcKind.CURRENT -> stringResource(Copy.string.phone_pc_now)
    row.lastConnectedAt <= 0 -> stringResource(Copy.string.phone_pc_never)
    else -> DateUtils.getRelativeTimeSpanString(row.lastConnectedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
}

/** A text-only action, as in "Block" or "Connect". */
@Composable
private fun TextAction(text: String, onClick: () -> Unit) {
    BasicText(
        text,
        Modifier.clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button, onClick = onClick).padding(vertical = 8.dp),
        style = Theme.pageText.copy(fontWeight = Theme.pageName.fontWeight),
    )
}

fun linkName(link: LinkKind) = when (link) {
    LinkKind.USB_DEBUGGING -> Copy.string.setting_link_usb_debugging
    LinkKind.USB_TETHERING -> Copy.string.setting_link_usb_tethering
    LinkKind.WIFI -> Copy.string.setting_link_wifi
    LinkKind.BLUETOOTH -> Copy.string.setting_link_bluetooth
}

/**
 * Five equal buttons: Reconnect, then, past a line like the one above them, the four links, each switched on (bright) or
 * off (faint) for this PC (the same settings as in settings).
 */
@Composable
private fun Footer(state: AppState) {
    Row(
        Modifier.fillMaxWidth().drawBehind { drawLine(Theme.rule, Offset(0f, 0.5.dp.toPx()), Offset(size.width, 0.5.dp.toPx()), 1.dp.toPx()) }
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val spin = remember { Animatable(0f) }
        val scope = rememberCoroutineScope()
        FooterButton(stringResource(Copy.string.ui_reconnect), on = true, Modifier.weight(1f), onClick = {
            Owlmic.post(AppMsg.Reconnect)
            scope.launch {
                spin.snapTo(0f)
                spin.animateTo(360f, tween(600))
            }
        }) { fg -> Icon(Glyph.RECONNECT, fg, null, Modifier.graphicsLayer { rotationZ = spin.value }, size = Tokens.Phone.LINK_GLYPH_DP.dp, stroke = Tokens.Phone.GLYPH_STROKE) }
        Box(Modifier.width(1.dp).height(32.dp).background(Theme.rule))
        LINK_SETTINGS.forEach { (link, id) ->
            val on = (state.settings[id] ?: "on") == "on"
            val name = stringResource(linkName(link))
            val description = "$name, ${stringResource(if (on) Copy.string.value_on else Copy.string.value_off)}"
            FooterButton(description, on, Modifier.weight(1f), role = Role.Switch, onClick = { Owlmic.post(AppMsg.Setting(id, if (on) "off" else "on")) }) { fg ->
                Icon(glyphOf(link), fg, null, size = Tokens.Phone.LINK_GLYPH_DP.dp, stroke = Tokens.Phone.GLYPH_STROKE)
            }
        }
    }
}

@Composable
private fun FooterButton(
    description: String,
    on: Boolean,
    modifier: Modifier,
    role: Role = Role.Button,
    onClick: () -> Unit,
    content: @Composable (fg: androidx.compose.ui.graphics.Color) -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier.height(Tokens.Phone.LINK_BUTTON_DP.dp).clip(shape)
            .background(if (on) Theme.chipOn else Theme.bg).border(1.dp, if (on) Theme.chipOnEdge else Theme.chipOffEdge, shape)
            .semantics { contentDescription = description }
            .clickable(role = role, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content(if (on) Theme.text else Theme.faint) }
}

/** The info page: six buttons, the How-to and the credits opening as pages of their own, and the version. */
private enum class InfoPage { GRID, HOW_TO, CREDITS }

@Composable
private fun ColumnScope.InfoDrawer() {
    var page by rememberSaveable { mutableStateOf(InfoPage.GRID) }
    BackHandler(page != InfoPage.GRID) { page = InfoPage.GRID }
    AnimatedContent(page, Modifier.weight(1f).fillMaxWidth(), transitionSpec = { fadeIn(tween(Theme.STATE_MS * 2)) togetherWith fadeOut(tween(Theme.STATE_MS)) }, label = "info") { p ->
        when (p) {
            InfoPage.GRID -> InfoGrid { page = it }
            InfoPage.HOW_TO -> SubPage({ page = InfoPage.GRID }) { HowTo() }
            InfoPage.CREDITS -> SubPage({ page = InfoPage.GRID }) { Credits() }
        }
    }
}

/** Two rows of three: How-to, Privacy Policy, Rate, GitHub, Donate, Credits. The version at the very bottom. */
@Composable
private fun InfoGrid(onPage: (InfoPage) -> Unit) {
    val context = LocalContext.current
    val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Square, up to a size: sideways the page is wide, and the buttons stay buttons.
        val side = minOf((maxWidth - 40.dp - 16.dp) / 3, 132.dp)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoButton(Glyph.HOW_TO, Copy.string.ui_how_to, side) { onPage(InfoPage.HOW_TO) }
                    InfoButton(Glyph.SHIELD, Copy.string.ui_privacy, side) { Links.open(context, Links.PRIVACY) }
                    InfoButton(Glyph.STAR, Copy.string.ui_rate, side) { Links.rate(context) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoButton(Glyph.GITHUB, Copy.string.ui_github, side) { Links.open(context, Links.REPO) }
                    InfoButton(Glyph.COFFEE, Copy.string.ui_donate, side) { Links.open(context, Links.DONATE) }
                    InfoButton(Glyph.PEOPLE, Copy.string.ui_credits, side) { onPage(InfoPage.CREDITS) }
                }
            }
            BasicText(
                stringResource(Copy.string.ui_version_tag, version),
                Modifier.padding(top = 32.dp),
                style = Theme.pageDetail.copy(color = Theme.faint, fontFamily = Theme.mono, textAlign = TextAlign.Center),
            )
        }
    }
}

@Composable
private fun InfoButton(glyph: Glyph, label: Int, side: Dp, onClick: () -> Unit) {
    SquareButton(stringResource(label), Modifier.size(side), onClick = onClick) { Icon(glyph, Theme.ink, null, size = 28.dp, stroke = 2f) }
}

/** A page of the info drawer: only a back arrow at the top left, then the content. */
@Composable
private fun SubPage(onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        val back = stringResource(Copy.string.ui_back)
        Box(
            Modifier.padding(start = 8.dp, top = 8.dp).size(Theme.touch).clip(CircleShape).clickable(role = Role.Button, onClickLabel = back, onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Glyph.BACK, Theme.text, back, size = 24.dp, stroke = Tokens.Phone.TOP_GLYPH_STROKE)
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp), content = content)
    }
}

/** The three setup steps as numbered circles on a line. */
@Composable
private fun HowTo() {
    val context = LocalContext.current
    Step(1, Copy.string.phone_step_download, Copy.string.phone_step_download_detail, last = false) { Links.open(context, Links.RELEASES) }
    Step(2, Copy.string.phone_step_connect, Copy.string.phone_step_connect_detail, last = false)
    Step(3, Copy.string.phone_step_stream, Copy.string.phone_step_stream_detail, last = true)
}

/** The people who built Owlmic, each opening their GitHub page, then everyone on GitHub. */
@Composable
private fun Credits() {
    val context = LocalContext.current
    Links.CONTRIBUTORS_LIST.forEach { (name, login) ->
        Row(
            Modifier.fillMaxWidth().heightIn(min = Theme.touch).rule().clickable(role = Role.Button) { Links.open(context, Links.profile(login)) }.padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(name, Modifier.weight(1f), style = Theme.pageText)
            BasicText("@$login", style = Theme.pageDetail.copy(fontFamily = Theme.mono, fontSize = 14.sp))
        }
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = Theme.touch).clickable(role = Role.Button) { Links.open(context, Links.CONTRIBUTORS) }.padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(stringResource(Copy.string.ui_github), Modifier.weight(1f), style = Theme.pageDetail)
        Icon(Glyph.OPEN, Theme.text2, null, size = 18.dp, stroke = Tokens.Phone.GLYPH_STROKE)
    }
}

/** A numbered circle on the path that joins the steps, then the step; [onClick] makes it open something. */
@Composable
private fun Step(number: Int, name: Int, detail: Int, last: Boolean, onClick: (() -> Unit)? = null) {
    val tap = if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).then(tap), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.width(36.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(36.dp).clip(CircleShape).border(1.5.dp, Theme.ink, CircleShape), contentAlignment = Alignment.Center) {
                BasicText(number.toString(), style = Theme.pageName.copy(fontFamily = Theme.mono, fontSize = 16.sp, color = Theme.ink))
            }
            if (!last) Box(Modifier.width(1.5.dp).weight(1f).background(Theme.chipOnEdge))
        }
        Column(Modifier.weight(1f).padding(top = 5.dp, bottom = if (last) 0.dp else 28.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(stringResource(name), style = Theme.pageName)
                if (onClick != null) Icon(Glyph.OPEN, Theme.text2, null, Modifier.padding(start = 8.dp), size = 18.dp, stroke = Tokens.Phone.GLYPH_STROKE)
            }
            BasicText(stringResource(detail), style = Theme.pageDetail)
        }
    }
}
