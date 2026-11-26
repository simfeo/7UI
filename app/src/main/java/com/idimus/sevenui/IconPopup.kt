package com.idimus.sevenui

import androidx.compose.ui.res.stringResource
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt

private val popupBg = Color(0xFC202023)
private val popupText = Color(0xFFF2F2F2)

private data class PopupAction(val label: String, val icon: ImageVector, val enabled: Boolean = true, val run: () -> Unit)

/**
 * Quick options: a title row with the app name and an App info button, the app's
 * shortcuts, then a row of global options.
 */
@Composable
fun IconPopup(state: LauncherState) {
    val req = state.menu ?: return
    // The reference design drops the app on the first free cell; here Add to Home asks for the page instead.
    var pickPage by remember(req) { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val actions = actionsFor(state, req, context::getString) { pickPage = true }
    val density = LocalDensity.current
    val gap = with(density) { 4.dp.roundToPx() }
    val title = when {
        isFolderKey(req.key) -> state.layout.folders[folderId(req.key)]?.name
        isWidgetKey(req.key) -> state.widgetInfo(parseWidget(req.key).id)?.loadLabel(context.packageManager)
        else -> state.apps[req.key]?.label
    }

    val anim = remember(req) { Animatable(0f) }
    LaunchedEffect(req) { anim.animateTo(1f, tween(250)) }

    // Dim behind the popup, only once it is clear the long press is not turning into a drag.
    if (state.drag?.moved != true) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = anim.value }
                .background(Color.Black.copy(alpha = 0.55f))
                .pointerInput(Unit) { detectTapGestures { state.menu = null } },
        )
    }

    // A box at the icon's position anchors the popup to the icon, not to the screen.
    Box(
        Modifier
            .offset { IntOffset(req.anchor.left.roundToInt(), req.anchor.top.roundToInt()) }
            .size(with(density) { req.anchor.width.toDp() }, with(density) { req.anchor.height.toDp() }),
    ) {
        Popup(
            popupPositionProvider = AboveOrBelow(gap, with(density) { 38.dp.roundToPx() }, with(density) { 11.dp.roundToPx() }),
            onDismissRequest = { state.menu = null },
            properties = PopupProperties(focusable = false, dismissOnClickOutside = true),
        ) {
            Column(
                Modifier
                    .width(265.dp)
                    .graphicsLayer {
                        alpha = (anim.value * 1.67f).coerceAtMost(1f)
                        scaleX = 0.9f + 0.1f * anim.value
                        scaleY = 0.9f + 0.1f * anim.value
                    }
                    .shadow(3.dp, RoundedCornerShape(20.dp))
                    .background(popupBg, RoundedCornerShape(20.dp)),
            ) {
                TitleRow(title.orEmpty(), showInfo = state.apps.containsKey(req.key)) { state.openAppInfo(req.key) }
                for (s in req.shortcuts) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { state.startShortcut(s) }
                            .padding(horizontal = 18.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppIcon(s.icon, 26.dp)
                        Spacer(Modifier.width(15.dp))
                        Text(s.label, color = popupText, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (pickPage) {
                    HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                    Text(
                        stringResource(R.string.add_to_home_page), color = popupText.copy(alpha = 0.7f), fontSize = 12.sp,
                        modifier = Modifier.padding(start = 18.dp, top = 10.dp),
                    )
                    androidx.compose.foundation.lazy.LazyRow(
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.layout.home.size) { page ->
                            Box(
                                Modifier
                                    .size(40.dp)
                                    .background(Color.White.copy(alpha = 0.1f), CircleShape)
                                    .clickable { state.addToHome(req.key, page) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("${page + 1}", color = popupText, fontSize = 15.sp)
                            }
                        }
                    }
                } else if (actions.isNotEmpty()) {
                    HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                    Row(
                        Modifier.fillMaxWidth().height(55.dp).padding(horizontal = 4.5.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        for (a in actions) ActionButton(a, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun TitleRow(title: String, showInfo: Boolean, onInfo: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(58.dp).padding(start = 20.dp, end = 5.dp, top = 6.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            color = popupText,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (showInfo) {
            Box(
                Modifier.size(32.dp, 30.dp).clickable(onClick = onInfo),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.app_info), tint = popupText, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun ActionButton(a: PopupAction, modifier: Modifier) {
    val tint = if (a.enabled) popupText else popupText.copy(alpha = 0.4f)
    Column(
        modifier.clickable(enabled = a.enabled, onClick = a.run).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(a.icon, contentDescription = a.label, tint = tint, modifier = Modifier.size(19.dp))
        Spacer(Modifier.height(3.dp))
        Text(a.label, color = tint, fontSize = 10.sp, textAlign = TextAlign.Center, maxLines = 1)
    }
}

private fun actionsFor(state: LauncherState, req: MenuRequest, str: (Int) -> String, onPickPage: () -> Unit): List<PopupAction> {
    val key = req.key
    val list = mutableListOf<PopupAction>()
    if (isWidgetKey(key)) {
        if (state.canResize(key)) {
            list += PopupAction(str(R.string.resize), Icons.Filled.Create) { state.startResize(req.source) }
        }
        if (state.widgetInfo(parseWidget(key).id)?.configure != null) {
            list += PopupAction(str(R.string.settings), Icons.Filled.Settings) { state.reconfigureWidget(key) }
        }
        list += PopupAction(str(R.string.remove), Icons.Filled.Clear) { state.removeWidget(req.source, key) }
        return list
    }
    if (isFolderKey(key)) {
        val id = folderId(key)
        list += PopupAction(str(R.string.edit_name), Icons.Filled.Create) { state.editFolderName(id) }
        when (req.source) {
            is Slot.Home, is Slot.Dock -> list += PopupAction(str(R.string.remove), Icons.Filled.Clear) { state.removeFromHome(req.source) }
            is Slot.Drawer -> list += PopupAction(str(R.string.ungroup), Icons.AutoMirrored.Filled.List) { state.ungroup(key) }
            else -> {}
        }
        return list
    }
    when (val s = req.source) {
        is Slot.Home, is Slot.Dock -> list += PopupAction(str(R.string.remove), Icons.Filled.Clear) { state.removeFromHome(s) }
        is Slot.InFolder -> list += PopupAction(str(R.string.remove), Icons.Filled.Clear) { state.removeFromFolder(s) }
        // The reference design greys this out for apps already on Home.
        else -> list += PopupAction(str(R.string.add_to_home), Icons.Filled.Home, enabled = !state.isOnHome(key)) {
            if (state.layout.home.size > 1) onPickPage() else state.addToHome(key)
        }
    }
    if (state.settings.edgePanel) {
        list += PopupAction(str(R.string.edge_panel), Icons.Filled.Add) { state.addToEdgePanel(key) }
    }
    list += PopupAction(str(R.string.uninstall), Icons.Filled.Delete) { state.uninstall(key) }
    return list
}

private class AboveOrBelow(private val gap: Int, private val minTop: Int, private val side: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = (anchorBounds.center.x - popupContentSize.width / 2)
            .coerceIn(side, (windowSize.width - popupContentSize.width - side).coerceAtLeast(side))
        val above = anchorBounds.top - popupContentSize.height - gap
        val y = if (above >= minTop) above else anchorBounds.bottom + gap
        return IntOffset(x, y)
    }
}
