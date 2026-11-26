package com.idimus.sevenui

import android.app.Activity
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import kotlin.math.roundToInt

val ICON_SIZE = 60.dp

val labelStyle = TextStyle(
    color = Color.White,
    fontSize = 12.sp,
    textAlign = TextAlign.Center,
    shadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 1f), 4f),
)

@Composable
fun LauncherScreen(state: LauncherState) {
    val density = LocalDensity.current
    state.touchSlop = LocalViewConfiguration.current.touchSlop
    state.edgePx = with(density) { 28.dp.toPx() }
    state.uiScope = rememberCoroutineScope()
    state.drawerAnim = remember { Animatable(if (state.drawerOpen) 1f else 0f) }
    val progress = state.drawerProgress

    val dragging = state.drag != null
    LaunchedEffect(dragging) {
        if (dragging) while (true) {
            delay(100)
            state.tick()
        }
    }
    val menuShown = state.menu != null && state.drag?.moved != true
    WallpaperBlur(progress, state.openFolder != null || state.settingsOpen || menuShown)
    // A home screen must never finish on Back.
    BackHandler { state.onBack() }

    Box(Modifier.fillMaxSize().pointerInput(Unit) { trackDrag(state) }) {
        HomeScreen(state)
        if (progress > 0f) {
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    alpha = progress
                    val s = 0.94f + 0.06f * progress
                    scaleX = s
                    scaleY = s
                },
            ) { DrawerScreen(state) }
        }
        FolderOverlay(state)
        DragOverlay(state)
        IconPopup(state)
        AnimatedVisibility(visible = state.widgetPickerOpen, enter = fadeIn(), exit = fadeOut()) {
            WidgetPicker(state)
        }
        AnimatedVisibility(visible = state.settingsOpen, enter = fadeIn(), exit = fadeOut()) {
            SettingsScreen(state)
        }
    }
}

/**
 * Blurs the wallpaper behind the window, as the reference design does behind the Apps screen and folders.
 * [progress] is the Apps screen fade and is used as is, so blur and fade move in step;
 * only the on/off overlays ([full]) get their own short animation.
 */
@Composable
private fun WallpaperBlur(progress: Float, full: Boolean) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val window = (LocalView.current.context as? Activity)?.window ?: return
    val overlay by animateFloatAsState(if (full) 1f else 0f, tween(150), label = "blur")
    val amount = maxOf(progress, overlay)
    SideEffect { window.setBackgroundBlurRadius((amount * 60f).roundToInt()) }
}

/**
 * Vertical finger speed over the last 80 ms of samples, in px/s (positive = down).
 * Compose's VelocityTracker reported 0 for these hand-rolled gesture loops on a real device,
 * so this keeps raw samples, including the batched historical ones, and the lift-off point.
 */
private class FingerSpeed {
    private val times = ArrayDeque<Long>()
    private val points = ArrayDeque<Offset>()

    fun add(change: PointerInputChange) {
        for (h in change.historical) push(h.uptimeMillis, h.position)
        push(change.uptimeMillis, change.position)
    }

    private fun push(t: Long, p: Offset) {
        times.addLast(t)
        points.addLast(p)
        while (times.size > 2 && t - times.first() > 80) {
            times.removeFirst()
            points.removeFirst()
        }
    }

    private fun velocity(): Offset {
        if (times.size < 2) return Offset.Zero
        val dt = (times.last() - times.first()).coerceAtLeast(1)
        return (points.last() - points.first()) * (1000f / dt)
    }

    fun velocityX() = velocity().x
    fun velocityY() = velocity().y
}

/**
 * One handler per screen picks a single axis for each gesture, as the reference launcher does: vertical
 * drives the Home/Apps transition (up from Home, down from Apps), horizontal moves pages.
 * Vertical needs the normal touch slop, paging twice that (Android's paging touch slop), so
 * a slightly sideways swipe up or down still counts as vertical. It watches the Initial pass
 * because widgets consume touches first; consuming here makes them receive a cancel.
 */
suspend fun PointerInputScope.detectScreenGestures(state: LauncherState, fromDrawer: Boolean) = awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
    // Search results scroll on their own.
    if (fromDrawer && state.searching) return@awaitEachGesture
    // Resize handles get their drags; this Initial-pass handler would otherwise win.
    if (state.resizing != null) return@awaitEachGesture
    state.screenSwipeActive = false
    val pager = if (fromDrawer) state.drawerPager else state.homePager
    val distance = DRAWER_SWIPE_DP.dp.toPx()
    val speed = FingerSpeed()
    speed.add(down)
    val startPage = pager?.currentPage ?: 0
    var total = Offset.Zero
    var decided = false
    var vertical = false
    var horizontal = false
    var driving = false

    fun finishEarly() {
        if (driving) state.settleDrawer(0f, fromDrawer)
        if (horizontal) state.settlePager(pager, startPage, 0f, size.width.toFloat())
    }

    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        val change = event.changes.firstOrNull() ?: break
        speed.add(change)
        if (state.drag != null || event.changes.size > 1 || (state.editMode && !horizontal && decided)) {
            finishEarly()
            return@awaitEachGesture
        }
        val delta = change.position - change.previousPosition
        total += delta
        if (!decided) {
            val ax = kotlin.math.abs(total.x)
            val ay = kotlin.math.abs(total.y)
            val slop = viewConfiguration.touchSlop
            if (ay >= maxOf(slop, ax) && !state.editMode) {
                decided = true
                vertical = true
                driving = if (fromDrawer) total.y > 0f else total.y < 0f
                state.screenSwipeActive = true
            } else if (ax > maxOf(slop * 2f, ay)) {
                decided = true
                horizontal = true
                state.screenSwipeActive = true
                state.dragPagerBy(pager, total.x - delta.x)
            }
        }
        if (driving) {
            state.dragDrawerBy(-delta.y / distance)
            change.consume()
        } else if (horizontal) {
            state.dragPagerBy(pager, delta.x)
            change.consume()
        }
        // The lift event is handled above too, so a quick flick decided only at lift-off counts.
        if (!change.pressed) break
    }
    when {
        driving -> state.settleDrawer(speed.velocityY(), fromDrawer)
        horizontal -> state.settlePager(pager, startPage, speed.velocityX(), size.width.toFloat())
        !fromDrawer && vertical && total.y > 48.dp.toPx() -> state.onSwipeDown()
    }
}

/**
 * Drag is started by an icon's long press but driven from here: the icon may be
 * recomposed onto another page mid-drag, which would kill a gesture owned by the icon.
 * The Initial pass sees events before the pagers, so consuming them stops page swipes.
 */
private suspend fun PointerInputScope.trackDrag(state: LauncherState) = awaitEachGesture {
    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        val change = event.changes.firstOrNull() ?: break
        if (state.drag != null) {
            state.onPointerMove(change.position)
            if (state.drag?.moved == true) change.consume()
        }
        if (event.changes.none { it.pressed }) {
            state.endDrag()
            break
        }
    }
}

private class CoordsRef {
    var coords: LayoutCoordinates? = null
}

/** An app or a folder at [slot]. */
@Composable
fun ItemCell(
    state: LauncherState,
    key: String,
    slot: Slot,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true,
    dragEnabled: Boolean = true,
) {
    val entry = state.apps[key]
    val folder = if (isFolderKey(key)) state.layout.folders[folderId(key)] else null
    if (entry == null && folder == null) return
    val ref = remember { CoordsRef() }
    val d = state.drag
    val hidden = d != null && d.moved && d.key == key &&
        (d.source == slot || (slot is Slot.Drawer && d.target is Slot.Drawer && !d.merge))
    val mergeTarget = d != null && d.moved && d.merge && d.target == slot
    val scale by animateFloatAsState(if (mergeTarget) 1.1f else 1f, tween(100), label = "mergeScale")
    val draggable = dragEnabled && !state.editMode &&
        !(state.settings.lockLayout && (slot is Slot.Home || slot is Slot.Dock))
    val view = LocalView.current

    Column(
        modifier
            .onGloballyPositioned { ref.coords = it }
            .alpha(if (hidden) 0f else 1f)
            .pointerInput(key, slot, draggable) {
                detectTapGestures(
                    onTap = { state.onItemTap(key, ref.coords?.boundsInWindow()) },
                    onLongPress = { local ->
                        val c = ref.coords ?: return@detectTapGestures
                        // Same buzz as a widget long press (LongPressWidgetView).
                        view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                        state.onLongPress(key, slot, c.localToRoot(local), c.boundsInRoot(), draggable)
                    },
                )
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.scale(scale)) {
            if (folder != null) FolderIcon(state, folder, ICON_SIZE)
            else AppIcon(state.icons[key], ICON_SIZE)
        }
        if (showLabel) {
            Text(
                folder?.name ?: entry!!.label,
                style = labelStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp),
            )
        }
    }
}

@Composable
fun AppIcon(bitmap: ImageBitmap?, size: Dp, modifier: Modifier = Modifier) {
    if (bitmap == null) Spacer(modifier.size(size))
    else Image(bitmap, contentDescription = null, modifier = modifier.size(size))
}

/** Folder icon: a tinted rounded square with a 3x3 grid of mini icons. */
@Composable
fun FolderIcon(state: LauncherState, folder: Folder, size: Dp) {
    val mini = size * 0.24f
    Box(
        Modifier
            .size(size)
            .background(Color(FOLDER_COLORS.getOrElse(folder.color) { FOLDER_COLORS[0] }), RoundedCornerShape(size * 0.3f))
            .padding(size * 0.1f),
    ) {
        Column(verticalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxSize()) {
            for (row in 0 until 3) {
                Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                    for (col in 0 until 3) {
                        val k = folder.items.getOrNull(row * 3 + col)
                        Box(Modifier.size(mini)) {
                            if (k != null) AppIcon(state.icons[k], mini)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Page dots; with [onSelect], a tap selects the dot under the finger on release and a
 * clearly horizontal slide scrubs through pages. Nothing happens on touch-down, and a
 * gesture that turns vertical or that the screen swipe handler already took is ignored:
 * the row sits where swipes up start, so reacting on down flipped pages.
 */
@Composable
fun PageDots(
    count: Int,
    current: Int,
    modifier: Modifier = Modifier,
    homePage: Int = -1,
    lastIsAdd: Boolean = false,
    onSelect: ((Int) -> Unit)? = null,
) {
    val dotPx = with(LocalDensity.current) { 12.dp.toPx() }
    val select = Modifier.pointerInput(count, onSelect) {
        if (onSelect == null || count < 2) return@pointerInput
        fun indexAt(x: Float): Int {
            val start = (size.width - count * dotPx) / 2f
            return ((x - start) / dotPx).toInt().coerceIn(0, count - 1)
        }
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            val slop = viewConfiguration.touchSlop
            var total = Offset.Zero
            var scrubbing = false
            var last = -1
            while (true) {
                val change = awaitPointerEvent().changes.firstOrNull() ?: break
                if (change.isConsumed && !scrubbing) break
                total += change.position - change.previousPosition
                if (!change.pressed) {
                    if (!scrubbing && total.getDistance() < slop) onSelect(indexAt(change.position.x))
                    break
                }
                if (!scrubbing) {
                    if (kotlin.math.abs(total.y) > slop) break
                    if (kotlin.math.abs(total.x) > slop && kotlin.math.abs(total.x) > kotlin.math.abs(total.y)) scrubbing = true
                }
                if (scrubbing) {
                    val i = indexAt(change.position.x)
                    if (i != last) {
                        last = i
                        onSelect(i)
                    }
                    change.consume()
                }
            }
        }
    }
    Row(
        modifier.then(if (onSelect != null) Modifier.fillMaxWidth() else Modifier).height(24.dp).then(select),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (count > 1) repeat(count) { i ->
            val tint = Color.White.copy(alpha = if (i == current) 0.95f else 0.4f)
            // The reference design marks the default page with a house and, in edit mode, ends with a "+".
            val glyph = when {
                lastIsAdd && i == count - 1 -> Icons.Filled.Add
                i == homePage -> Icons.Filled.Home
                else -> null
            }
            if (glyph != null) {
                androidx.compose.material3.Icon(glyph, null, tint = tint, modifier = Modifier.padding(horizontal = 1.dp).size(10.dp))
            } else {
                // The reference design draws rings, not filled dots, on both Home and Apps.
                Box(Modifier.padding(horizontal = 3.dp).size(7.dp).border(1.2.dp, tint, CircleShape))
            }
        }
    }
}

@Composable
private fun DragOverlay(state: LauncherState) {
    val d = state.drag ?: return
    if (!d.moved) return
    if (isWidgetKey(d.key)) {
        WidgetDragGhost(state, d)
        return
    }
    val half = with(LocalDensity.current) { (ICON_SIZE / 2).toPx() }
    val folder = if (isFolderKey(d.key)) state.layout.folders[folderId(d.key)] else null
    Box(
        Modifier
            .offset { IntOffset((d.pointer.x - half).roundToInt(), (d.pointer.y - half).roundToInt()) }
            .graphicsLayer {
                scaleX = 1.15f
                scaleY = 1.15f
                alpha = 0.95f
            },
    ) {
        if (folder != null) FolderIcon(state, folder, ICON_SIZE) else AppIcon(state.icons[d.key], ICON_SIZE)
    }
}

/** A translucent card the size of the widget, held under the finger at the grabbed cell. */
@Composable
private fun WidgetDragGhost(state: LauncherState, d: DragState) {
    val w = parseWidget(d.key)
    val cw = state.homeGrid.width / Grid.HOME_COLS
    val ch = state.homeGrid.height / Grid.HOME_ROWS
    val density = LocalDensity.current
    Box(
        Modifier
            .offset {
                IntOffset(
                    (d.pointer.x - cw * (d.grabCol + 0.5f)).roundToInt(),
                    (d.pointer.y - ch * (d.grabRow + 0.5f)).roundToInt(),
                )
            }
            .size(with(density) { (cw * w.spanX).toDp() }, with(density) { (ch * w.spanY).toDp() })
            .padding(6.dp)
            .background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(20.dp)),
    )
}

@Composable
fun DropZoneBar(text: String, active: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(
                if (active) Color.White.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.15f),
                CircleShape,
            )
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Text(text, style = labelStyle.copy(fontSize = 14.sp))
    }
}
