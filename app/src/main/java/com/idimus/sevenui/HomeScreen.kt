package com.idimus.sevenui

import androidx.compose.ui.res.stringResource
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import androidx.compose.foundation.border
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import androidx.compose.runtime.remember
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val cellHighlight = RoundedCornerShape(18.dp)
private const val EDIT_SCALE = 0.75f

@Composable
fun HomeScreen(state: LauncherState) {
    val startPage = state.settings.defaultHomePage.coerceIn(0, state.layout.home.size - 1)
    val pager = rememberPagerState(if (state.homeLoops) state.loopAnchor(startPage) else startPage) {
        state.homeVirtualCount()
    }
    state.homePager = pager
    // Switching loop on/off or changing the page count re-anchors so the same page stays visible.
    val loops = state.homeLoops
    val realCount = state.layout.home.size
    LaunchedEffect(loops, realCount) {
        // Entering or leaving edit mode changes the pager's page count, and the pager clamps its
        // position before this runs, so the page has to come from before the switch.
        val real = (state.takeAnchorPage() ?: state.homeIndex(pager.currentPage)).coerceAtMost(realCount - 1)
        val target = if (loops) state.loopAnchor(real) else real
        if (target != pager.currentPage) pager.scrollToPage(target)
    }

    val d = state.drag
    val showRemove = d != null && d.moved && !state.drawerOpen && d.source !is Slot.Drawer
    val homeAlpha = 1f - state.drawerProgress
    val edit = state.editMode
    // Springs: bouncy on enter, nearly critically damped on exit.
    val editSpring = if (edit) spring<Float>(dampingRatio = 0.67f, stiffness = 225f)
    else spring(dampingRatio = 0.99f, stiffness = 360f)
    val pageScale by animateFloatAsState(if (edit) EDIT_SCALE else 1f, editSpring, label = "editScale")
    val sidePadding by animateDpAsState(if (edit) 24.dp else 0.dp, label = "editPadding")

    Column(
        Modifier
            .fillMaxSize()
            .alpha(homeAlpha)
            .systemBarsPadding()
            .pointerInput(Unit) { detectScreenGestures(state, fromDrawer = false) }
            .pointerInput(Unit) { detectPinchIn(state::enterEditMode) }
            .pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = { state.enterEditMode() },
                    onTap = {
                        // The "+" card's tap also reaches here; exiting would drop the page it just added.
                        if (state.editMode && !state.consumeAddPageTap()) state.exitEditMode()
                        state.resizing = null
                    },
                )
            },
    ) {
        Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) {
            if (showRemove) DropZoneBar(stringResource(R.string.remove), active = d?.zone == Zone.REMOVE)
        }
        HorizontalPager(
            state = pager,
            // Touches are handled by detectScreenGestures, which picks one axis per gesture.
            userScrollEnabled = false,
            contentPadding = PaddingValues(horizontal = sidePadding),
            pageSpacing = if (edit) 8.dp else 0.dp,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .onGloballyPositioned { state.homeGrid = it.boundsInRoot() },
        ) { virtual ->
            val page = state.homeIndex(virtual)
            Box(Modifier.fillMaxSize().graphicsLayer { scaleX = pageScale; scaleY = pageScale }) {
                if (state.isAddPage(page) && state.drag == null) {
                    AddPageCard(state)
                } else {
                    if (edit) EditPageFrame(state, page)
                    HomePage(state, page)
                }
            }
        }
        PageDots(
            state.homeRealCount(), state.currentHomePage(), Modifier.align(Alignment.CenterHorizontally),
            homePage = state.settings.defaultHomePage,
            lastIsAdd = edit && state.drag == null,
            onSelect = state::goToHomePage,
        )
        if (edit) {
            EditBar(state)
        } else {
            if (state.settings.homeSearchBar) HomeSearchBar(state)
            Dock(state, Modifier.onGloballyPositioned { state.dockArea = it.boundsInRoot() })
        }
    }
}

@Composable
private fun HomePage(state: LauncherState, page: Int) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cw = maxWidth / Grid.HOME_COLS
        val ch = maxHeight / Grid.HOME_ROWS
        val d = state.drag
        val target = d?.target
        if (target is Slot.Home && target.page == page && !d.merge && d.moved) {
            val (sx, sy) = if (isWidgetKey(d.key)) parseWidget(d.key).let { it.spanX to it.spanY } else 1 to 1
            val fits = state.layout.fitsAt(page, target.cell, sx, sy, ignore = d.key)
            Highlight(cw * (target.cell % Grid.HOME_COLS), ch * (target.cell / Grid.HOME_COLS), cw * sx, ch * sy, fits)
        }
        for ((cell, appKey) in state.layout.home.getOrNull(page).orEmpty()) {
            if (isWidgetKey(appKey)) {
                val w = parseWidget(appKey)
                val dragged = d != null && d.moved && d.key == appKey
                key(appKey) {
                    HomeWidget(
                        state, w,
                        Modifier
                            .offset(cw * (cell % Grid.HOME_COLS), ch * (cell / Grid.HOME_COLS))
                            .size(cw * w.spanX, ch * w.spanY)
                            .alpha(if (dragged) 0.3f else 1f)
                            .padding(6.dp),
                    )
                }
                if (state.resizing == Slot.Home(page, cell)) {
                    ResizeFrame(state, Slot.Home(page, cell), appKey, cw, ch)
                }
                continue
            }
            key(appKey) {
                ItemCell(
                    state, appKey, Slot.Home(page, cell),
                    Modifier
                        .offset(cw * (cell % Grid.HOME_COLS), ch * (cell / Grid.HOME_COLS))
                        .size(cw, ch),
                )
            }
        }
    }
}

@Composable
private fun HomeWidget(state: LauncherState, w: WidgetSpec, modifier: Modifier) {
    val host = state.widgetHost ?: return
    val info = remember(w.id) { state.widgetInfo(w.id) } ?: return
    BoxWithConstraints(modifier) {
        val wDp = maxWidth.value
        val hDp = maxHeight.value
        AndroidView(
            factory = { ctx -> host.createView(ctx, w.id, info) },
            update = { view -> view.reportSize(wDp, hDp) },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** Tells the widget how big it is, so it can pick the right layout. */
private fun AppWidgetHostView.reportSize(wDp: Float, hDp: Float) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        updateAppWidgetSize(Bundle(), listOf(SizeF(wDp, hDp)))
    } else {
        @Suppress("DEPRECATION")
        updateAppWidgetSize(null, wDp.toInt(), hDp.toInt(), wDp.toInt(), hDp.toInt())
    }
}

/** Edit mode page card with the "make default" and "delete page" buttons on top. */
@Composable
private fun EditPageFrame(state: LauncherState, page: Int) {
    val isDefault = state.settings.defaultHomePage == page
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(24.dp)),
    ) {
        Row(
            Modifier.align(Alignment.TopCenter).offset(y = (-44).dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            RoundIcon(if (isDefault) Icons.Filled.Home else Icons.Outlined.Home) { state.setDefaultPage(page) }
            if (state.layout.home.size > 1) RoundIcon(Icons.Filled.Delete) { state.deletePage(page) }
        }
    }
}

@Composable
private fun AddPageCard(state: LauncherState) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(24.dp))
            .clickable { state.addHomePage() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add_page), tint = Color.White, modifier = Modifier.size(48.dp))
    }
}

@Composable
private fun RoundIcon(icon: ImageVector, onClick: () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .background(Color.Black.copy(alpha = 0.35f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun EditBar(state: LauncherState) {
    Row(
        Modifier.fillMaxWidth().height(96.dp).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EditBarButton(stringResource(R.string.wallpaper), Icons.Filled.Edit) { state.openWallpaperPicker() }
        EditBarButton(stringResource(R.string.widgets), Icons.Filled.Add) { state.widgetPickerOpen = true }
        EditBarButton(stringResource(R.string.settings), Icons.Filled.Settings) { state.settingsOpen = true }
    }
}

@Composable
private fun EditBarButton(label: String, icon: ImageVector, onClick: () -> Unit) {
    Column(
        Modifier.width(88.dp).clickable(onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(6.dp))
        Text(label, style = labelStyle.copy(fontSize = 13.sp))
    }
}

/** Search bar between the grid and the dock. */
@Composable
private fun HomeSearchBar(state: LauncherState) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 6.dp)
            .height(48.dp)
            .background(Color.White.copy(alpha = 0.85f), CircleShape)
            .clickable { state.openWebSearch() }
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = Color(0xFF444444))
        Spacer(Modifier.width(12.dp))
        Text(stringResource(R.string.search), color = Color(0xFF555555), fontSize = 16.sp)
    }
}

@Composable
private fun Dock(state: LauncherState, modifier: Modifier) {
    val d = state.drag
    Row(modifier.fillMaxWidth().height(96.dp).padding(horizontal = 12.dp)) {
        repeat(Grid.DOCK_SLOTS) { cell ->
            Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                if (d?.target == Slot.Dock(cell) && !d.merge) {
                    Box(Modifier.fillMaxSize().padding(6.dp).background(Color.White.copy(alpha = 0.18f), cellHighlight))
                }
                val k = state.layout.dock[cell]
                if (k != null) ItemCell(state, k, Slot.Dock(cell), Modifier.fillMaxSize(), showLabel = false)
            }
        }
    }
}

@Composable
private fun Highlight(x: Dp, y: Dp, w: Dp, h: Dp, fits: Boolean = true) {
    Box(
        Modifier
            .offset(x, y)
            .size(w, h)
            .padding(6.dp)
            .background(if (fits) Color.White.copy(alpha = 0.18f) else Color(0x55FF453A), cellHighlight)
    )
}

/**
 * Frame with a handle on each side. Dragging a handle snaps the widget's edge to whole
 * cells; the change is committed when the finger lifts, and only if the new area is free.
 */
@Composable
private fun ResizeFrame(state: LauncherState, slot: Slot.Home, key: String, cw: Dp, ch: Dp) {
    val w = parseWidget(key)
    val (minX, minY) = remember(key) { state.minSpan(key) }
    val info = remember(w.id) { state.widgetInfo(w.id) }
    val horizontal = info != null && info.resizeMode and AppWidgetProviderInfo.RESIZE_HORIZONTAL != 0
    val vertical = info != null && info.resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL != 0
    val col0 = slot.cell % Grid.HOME_COLS
    val row0 = slot.cell / Grid.HOME_COLS
    // Edges in cells: left, top, right (exclusive), bottom (exclusive).
    var edges by remember(key, slot) { mutableStateOf(listOf(col0, row0, col0 + w.spanX, row0 + w.spanY)) }
    val density = LocalDensity.current
    val cwPx = with(density) { cw.toPx() }
    val chPx = with(density) { ch.toPx() }
    val (l, t, r, b) = edges
    val fits = state.layout.fitsAt(slot.page, t * Grid.HOME_COLS + l, r - l, b - t, ignore = key)

    Box(
        Modifier
            .offset(cw * l, ch * t)
            .size(cw * (r - l), ch * (b - t))
            .border(2.dp, if (fits) Color.White else Color(0xFFFF453A), RoundedCornerShape(20.dp)),
    )

    @Composable
    fun Handle(x: Dp, y: Dp, enabled: Boolean, onDrag: (Float, Float) -> Unit) {
        if (!enabled) return
        var acc by remember { mutableStateOf(Offset.Zero) }
        Box(
            Modifier
                .offset(x - 14.dp, y - 14.dp)
                .size(28.dp)
                // Side handles sit on the screen edge, where the system Back swipe would take a drag inward.
                .systemGestureExclusion()
                .background(Color.White, CircleShape)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { acc = Offset.Zero },
                        onDragEnd = {
                            val (nl, nt, nr, nb) = edges
                            state.applyResize(slot, key, nl, nt, nr - nl, nb - nt)
                        },
                    ) { change, delta ->
                        change.consume()
                        acc += delta
                        onDrag(acc.x, acc.y)
                    }
                },
        )
    }

    val start = listOf(col0, row0, col0 + w.spanX, row0 + w.spanY)
    fun cells(px: Float, size: Float) = kotlin.math.round(px / size).toInt()
    Handle(cw * l, ch * (t + b) / 2, horizontal) { dx, _ ->
        edges = edges.toMutableList().also { it[0] = (start[0] + cells(dx, cwPx)).coerceIn(0, it[2] - minX) }
    }
    Handle(cw * r, ch * (t + b) / 2, horizontal) { dx, _ ->
        edges = edges.toMutableList().also { it[2] = (start[2] + cells(dx, cwPx)).coerceIn(it[0] + minX, Grid.HOME_COLS) }
    }
    Handle(cw * (l + r) / 2, ch * t, vertical) { _, dy ->
        edges = edges.toMutableList().also { it[1] = (start[1] + cells(dy, chPx)).coerceIn(0, it[3] - minY) }
    }
    Handle(cw * (l + r) / 2, ch * b, vertical) { _, dy ->
        edges = edges.toMutableList().also { it[3] = (start[3] + cells(dy, chPx)).coerceIn(it[1] + minY, Grid.HOME_ROWS) }
    }
}

/** Fires once per gesture when two fingers pinch in by ~25%. Observes only, consumes nothing. */
private suspend fun PointerInputScope.detectPinchIn(onPinch: () -> Unit) = awaitEachGesture {
    awaitFirstDown(requireUnconsumed = false)
    var zoom = 1f
    var fired = false
    while (true) {
        val event = awaitPointerEvent()
        if (event.changes.none { it.pressed }) break
        if (event.changes.size >= 2) {
            zoom *= event.calculateZoom()
            if (!fired && zoom < 0.75f) {
                fired = true
                onPinch()
            }
        }
    }
}
