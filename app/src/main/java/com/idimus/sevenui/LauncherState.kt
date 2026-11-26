package com.idimus.sevenui

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Zone { REMOVE, ADD_TO_HOME }

data class DragState(
    val key: String,
    val source: Slot,
    val start: Offset,
    val pointer: Offset,
    val moved: Boolean = false,
    val target: Slot? = null,
    val merge: Boolean = false,
    val zone: Zone? = null,
    /** For widgets: which of its cells the finger grabbed, so the drop keeps that cell under the finger. */
    val grabCol: Int = 0,
    val grabRow: Int = 0,
)

data class Shortcut(val info: ShortcutInfo, val label: String, val icon: ImageBitmap?)

data class MenuRequest(val key: String, val source: Slot, val anchor: Rect, val shortcuts: List<Shortcut>)

/** Virtual page count for a looping pager; real page = virtual % count. */
const val LOOP_PAGES = 10_000

/** Density of the screen the reference pixel constants were tuned on. */
const val ONE_UI_DENSITY = 3.5f

/** The reference launcher maps 800 px of finger travel to the full Home/Apps transition. */
const val DRAWER_SWIPE_DP = 800f / ONE_UI_DENSITY

class LauncherState(context: Context, private val scope: CoroutineScope) {
    private val repo = AppRepository(context)
    private val store = LayoutStore(context)
    val settings = Settings(context)
    private val iconPx = (64 * context.resources.displayMetrics.density).toInt()
    private val shortcutIconPx = (24 * context.resources.displayMetrics.density).toInt()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val ioSerial = Dispatchers.IO.limitedParallelism(1)

    var apps by mutableStateOf<Map<String, AppEntry>>(emptyMap())
        private set
    var sortedApps by mutableStateOf<List<AppEntry>>(emptyList())
        private set
    var layout by mutableStateOf(LayoutModel.EMPTY)
        private set
    val icons = mutableStateMapOf<String, ImageBitmap>()

    var drawerOpen by mutableStateOf(false)
        private set
    var editMode by mutableStateOf(false)
        private set
    var settingsOpen by mutableStateOf(false)
    var openFolder by mutableStateOf<String?>(null)
    var query by mutableStateOf("")
    var searching by mutableStateOf(false)
    var drag by mutableStateOf<DragState?>(null)
        private set
    var menu by mutableStateOf<MenuRequest?>(null)

    // Geometry and pagers are written by the UI, all in root coordinates.
    var homeGrid = Rect.Zero
    var dockArea = Rect.Zero
    var drawerGrid = Rect.Zero
    var homePager: PagerState? = null
    var drawerPager: PagerState? = null
    var touchSlop = 16f
    var edgePx = 60f

    private var callback: LauncherApps.Callback? = null
    private var edgeTicks = 0
    private var zoneTicks = 0

    init {
        applyDrawerGridSetting()
    }

    fun start() {
        scope.launch {
            val stored = withContext(Dispatchers.IO) { store.load() }
            if (stored != null) layout = stored
            refreshApps(firstRun = stored == null)
        }
        callback = repo.registerCallback { packages ->
            icons.keys.removeAll { key -> packages.any { key.startsWith("$it/") } }
            scope.launch { refreshApps(firstRun = false) }
        }
    }

    fun stop() {
        callback?.let(repo::unregisterCallback)
    }

    /**
     * A widget whose app was uninstalled, or whose id is not bound to this launcher, draws
     * nothing but would keep its cells taken, so nothing could be dropped there.
     */
    private fun dropDeadWidgets(m: LayoutModel): LayoutModel {
        // Bound to this launcher's own host: ids restored from another install (or another
        // package) may still resolve to provider info but can never show here.
        val ours = widgetHost?.appWidgetIds?.toSet() ?: return m
        val dead = m.home.flatMap { it.values }.filter {
            isWidgetKey(it) && (parseWidget(it).id !in ours || widgetInfo(parseWidget(it).id) == null)
        }
        if (dead.isEmpty()) return m
        dead.map { parseWidget(it).id }.filter { it in ours }.forEach { widgetHost?.deleteAppWidgetId(it) }
        return m.copy(home = m.home.map { page -> page.filterValues { it !in dead } })
    }

    private suspend fun refreshApps(firstRun: Boolean) {
        val list = withContext(Dispatchers.IO) { repo.loadAll() }.sortedBy { it.label.lowercase() }
        apps = list.associateBy { it.key }
        sortedApps = list
        val keys = list.map { it.key }
        layout = if (firstRun) LayoutOps.initial(keys, repo.defaultDockKeys(list))
        else dropDeadWidgets(LayoutOps.reconcile(layout, keys))
        persist()
        scope.launch(Dispatchers.Default) {
            for (e in list) {
                if (e.key in icons) continue
                val bmp = repo.loadIcon(e, iconPx)
                withContext(Dispatchers.Main) { icons[e.key] = bmp }
            }
        }
    }

    private fun persist() {
        val snapshot = layout
        scope.launch(ioSerial) { store.save(snapshot) }
    }

    private fun commit(m: LayoutModel) {
        layout = LayoutOps.cleanup(m)
        persist()
    }

    // ---- Paging ----

    val homeLoops get() = settings.loopPages && layout.home.size > 1 && !editMode

    // No extra drop page in loop mode: changing the count mid-drag would reshuffle the modulo.
    fun homeRealCount() =
        layout.home.size + if (editMode || (drag?.moved == true && !drawerOpen && !settings.loopPages)) 1 else 0

    fun homeVirtualCount() = if (homeLoops) LOOP_PAGES else homeRealCount()

    fun homeIndex(virtual: Int) = virtual % homeRealCount().coerceAtLeast(1)

    fun currentHomePage() = homePager?.let { homeIndex(it.currentPage) } ?: 0

    /** Virtual page closest to the middle of the loop that shows real page [real]. */
    fun loopAnchor(real: Int): Int {
        val n = homeRealCount().coerceAtLeast(1)
        return LOOP_PAGES / 2 - (LOOP_PAGES / 2) % n + real
    }

    /** Set by the UI. Pager animations need Compose's frame clock, which [scope] does not have. */
    var uiScope: CoroutineScope? = null

    private fun animatePager(pager: PagerState?, page: Int) {
        val p = pager ?: return
        uiScope?.launch { p.animateScrollToPage(page) }
    }

    /** Moves pages with the finger; [dx] is finger movement in px (positive = right). */
    fun dragPagerBy(pager: PagerState?, dx: Float) {
        pager?.dispatchRawDelta(-dx)
    }

    /**
     * Page settle from the reference launcher: a fling over 500 dp/s turns one page
     * its way; otherwise the page turns only past 40% of the width. Fling snaps take
     * min(5 * (half + half * influence) / speed, 900 ms); plain snaps take 750 ms.
     */
    fun settlePager(pagerState: PagerState?, startPage: Int, velocityX: Float, pageWidth: Float) {
        val pager = pagerState ?: return
        val moved = (pager.currentPage + pager.currentPageOffsetFraction) - startPage
        val fling = abs(velocityX) > 500f * density
        val step = when {
            fling -> if (velocityX < 0f) 1 else -1
            abs(moved) > 0.4f -> if (moved > 0f) 1 else -1
            else -> 0
        }
        val target = (startPage + step).coerceIn(0, pager.pageCount - 1)
        val remaining = abs(target - (pager.currentPage + pager.currentPageOffsetFraction)) * pageWidth
        val duration = if (!fling) 750 else {
            val half = pageWidth / 2f
            val f = min(1f, remaining / pageWidth)
            val influence = sin((f - 0.5f) * 0.3f * PI.toFloat() / 2f)
            val minSnapVelocity = 1500f * density
            min((((half + half * influence) / max(minSnapVelocity, abs(velocityX))) * 1000f).roundToInt() * 5, 900)
        }
        uiScope?.launch { pager.animateScrollToPage(target, animationSpec = tween(duration, easing = QuinticOut)) }
    }

    private val indicatorTargets = mutableMapOf<PagerState, Int>()

    /**
     * Indicator navigation as in the reference launcher: always start from
     * where the pages are right now (even mid-scroll), take the same 750 ms quintic ease-out
     * for any distance, and ignore a repeat request for the target already being scrolled to.
     * Compose's animateScrollToPage restarted on every call and teleported for long jumps.
     */
    private fun smoothScrollToPage(pager: PagerState?, target: Int) {
        val p = pager ?: return
        val page = target.coerceIn(0, p.pageCount - 1)
        if (indicatorTargets[p] == page && p.isScrollInProgress) return
        indicatorTargets[p] = page
        uiScope?.launch {
            p.scroll {
                val pageSpan = (p.layoutInfo.pageSize + p.layoutInfo.pageSpacing).toFloat()
                if (pageSpan <= 0f) return@scroll
                val start = p.currentPage + p.currentPageOffsetFraction
                var applied = start
                Animatable(start).animateTo(page.toFloat(), tween(750, easing = QuinticOut)) {
                    scrollBy((value - applied) * pageSpan)
                    applied = value
                }
            }
        }
    }

    fun goToHomePage(real: Int) {
        val pager = homePager ?: return
        smoothScrollToPage(pager, pager.currentPage + (real - currentHomePage()))
    }

    fun goToDrawerPage(page: Int) = smoothScrollToPage(drawerPager, page)

    fun drawerPageCount(): Int {
        val d = drag
        val extra = d != null && drawerOpen && (d.source is Slot.Drawer || d.source is Slot.InFolder)
        return previewDrawer().size + if (extra) 1 else 0
    }

    fun previewDrawer(): List<List<String>> {
        val d = drag
        val t = d?.target
        val movable = d != null && (d.source is Slot.Drawer || d.source is Slot.InFolder)
        return if (drawerOpen && movable && !d!!.merge && t is Slot.Drawer)
            LayoutOps.drawerMove(layout.drawer, d.key, t.page, t.index)
        else layout.drawer
    }

    // ---- Actions ----

    fun launch(entry: AppEntry, bounds: Rect?) {
        val r = bounds?.let {
            android.graphics.Rect(it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt())
        }
        repo.launch(entry, r)
    }

    fun onItemTap(key: String, bounds: Rect?) {
        if (editMode) {
            exitEditMode()
            return
        }
        if (isFolderKey(key)) openFolder = folderId(key)
        else apps[key]?.let { launch(it, bounds) }
    }

    fun startShortcut(s: Shortcut) {
        repo.startShortcut(s.info)
        menu = null
    }

    fun openAppInfo(key: String) {
        apps[key]?.let(repo::openAppInfo)
        menu = null
    }

    fun uninstall(key: String) {
        apps[key]?.let(repo::uninstall)
        menu = null
    }

    fun isOnHome(key: String): Boolean {
        val keys = layout.home.flatMap { it.values } + layout.dock.values
        return keys.any { it == key || (isFolderKey(it) && key in layout.folders[folderId(it)]?.items.orEmpty()) }
    }

    /** [page] is filled first, spilling to other pages when full; -1 means the first free cell. */
    fun addToHome(key: String, page: Int = -1) {
        commit(LayoutOps.addToHome(layout, key, page))
        menu = null
    }

    fun removeFromHome(slot: Slot) {
        commit(layout.clear(slot))
        menu = null
    }

    fun removeFromFolder(slot: Slot.InFolder) {
        layout.keyAt(slot)?.let { commit(LayoutOps.takeOutOfFolder(layout, slot.id, it)) }
        menu = null
        if (layout.folders[slot.id] == null) openFolder = null
    }

    fun ungroup(key: String) {
        commit(LayoutOps.ungroup(layout, folderId(key)))
        menu = null
    }

    // ---- Widgets ----

    private val density = context.resources.displayMetrics.density
    private val widgetManager = AppWidgetManager.getInstance(context)
    var widgetHost: LauncherWidgetHost? = null
    var widgetPickerOpen by mutableStateOf(false)

    /** Set by the activity: binding and configuring a widget need activity results. */
    var onAddWidget: (AppWidgetProviderInfo) -> Unit = {}
    var onReconfigureWidget: (Int) -> Unit = {}
    var onBackup: () -> Unit = {}
    var onRestore: () -> Unit = {}

    fun widgetProviders(): List<AppWidgetProviderInfo> =
        widgetManager.getInstalledProvidersForProfile(android.os.Process.myUserHandle())

    fun widgetInfo(id: Int): AppWidgetProviderInfo? = widgetManager.getAppWidgetInfo(id)

    fun spanFor(info: AppWidgetProviderInfo): Pair<Int, Int> {
        val cw = if (homeGrid.width > 0) homeGrid.width / density / Grid.HOME_COLS else 90f
        val ch = if (homeGrid.height > 0) homeGrid.height / density / Grid.HOME_ROWS else 100f
        return widgetSpan(info, cw, ch, density)
    }

    fun requestAddWidget(info: AppWidgetProviderInfo) = onAddWidget(info)

    fun placeWidget(id: Int, info: AppWidgetProviderInfo) {
        val (sx, sy) = spanFor(info)
        commit(LayoutOps.addWidget(layout, currentHomePage(), WidgetSpec(id, sx, sy)))
        widgetPickerOpen = false
        editMode = false
    }

    private fun findWidget(id: Int): Pair<Slot.Home, String>? {
        for ((page, cells) in layout.home.withIndex()) {
            val e = cells.entries.firstOrNull { isWidgetKey(it.value) && parseWidget(it.value).id == id } ?: continue
            return Slot.Home(page, e.key) to e.value
        }
        return null
    }

    /**
     * Set once the current touch became a swipe. A widget's own long-press timer keeps running
     * after the pager takes the gesture, because the widget view stops getting moves.
     */
    var screenSwipeActive = false

    fun onWidgetLongPress(id: Int, pointer: Offset, anchor: Rect): Boolean {
        if (editMode || screenSwipeActive) return false
        val (slot, key) = findWidget(id) ?: return false
        resizing = null
        val cw = homeGrid.width / Grid.HOME_COLS
        val ch = homeGrid.height / Grid.HOME_ROWS
        val w = parseWidget(key)
        onLongPress(key, slot, pointer, anchor, draggable = !settings.lockLayout)
        drag = drag?.copy(
            grabCol = ((pointer.x - anchor.left) / cw).toInt().coerceIn(0, w.spanX - 1),
            grabRow = ((pointer.y - anchor.top) / ch).toInt().coerceIn(0, w.spanY - 1),
        )
        return true
    }

    /** Widget being resized, by its slot; the home page shows handles around it. */
    var resizing by mutableStateOf<Slot.Home?>(null)

    fun canResize(key: String): Boolean =
        widgetInfo(parseWidget(key).id)?.resizeMode?.let { it != AppWidgetProviderInfo.RESIZE_NONE } == true

    fun startResize(slot: Slot) {
        menu = null
        resizing = slot as? Slot.Home
    }

    /** Smallest span the widget allows, from its declared minimum resize size. */
    fun minSpan(key: String): Pair<Int, Int> {
        val info = widgetInfo(parseWidget(key).id) ?: return 1 to 1
        val cw = homeGrid.width / density / Grid.HOME_COLS
        val ch = homeGrid.height / density / Grid.HOME_ROWS
        // A few dp of slack: widgets declare sizes for the stock launcher's cells, and a minimum that
        // overshoots our cell by a hair should not cost a whole extra cell.
        val slack = 12f
        val x = kotlin.math.ceil((info.minResizeWidth / density - slack) / cw).toInt().coerceIn(1, Grid.HOME_COLS)
        val y = kotlin.math.ceil((info.minResizeHeight / density - slack) / ch).toInt().coerceIn(1, Grid.HOME_ROWS)
        return x to y
    }

    fun applyResize(slot: Slot.Home, key: String, col: Int, row: Int, spanX: Int, spanY: Int) {
        val cell = row * Grid.HOME_COLS + col
        if (!layout.fitsAt(slot.page, cell, spanX, spanY, ignore = key)) return
        val id = parseWidget(key).id
        val newKey = widgetKey(id, spanX, spanY)
        commit(layout.clear(slot).put(Slot.Home(slot.page, cell), newKey))
        resizing = Slot.Home(slot.page, cell)
    }

    fun removeWidget(slot: Slot, key: String) {
        widgetHost?.deleteAppWidgetId(parseWidget(key).id)
        commit(layout.clear(slot))
        menu = null
    }

    fun reconfigureWidget(key: String) {
        menu = null
        onReconfigureWidget(parseWidget(key).id)
    }

    // ---- Edge panel ----

    private val appContext = context.applicationContext

    fun setEdgePanel(enabled: Boolean) {
        settings.edgePanel = enabled
        if (enabled && !android.provider.Settings.canDrawOverlays(appContext)) {
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.fromParts("package", appContext.packageName, null),
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(intent)
            return
        }
        EdgePanelService.sync(appContext, enabled)
    }

    /** The handle is placed when the service starts, so moving it restarts the service. */
    fun setEdgePanelLeft(left: Boolean) {
        settings.edgePanelLeft = left
        if (settings.edgePanel) {
            EdgePanelService.sync(appContext, false)
            EdgePanelService.sync(appContext, true)
        }
    }

    fun addToEdgePanel(key: String) {
        EdgePrefs.add(appContext, key)
        menu = null
    }

    var focusFolderName by mutableStateOf(false)
    var isDefaultHome by mutableStateOf(true)
    var requestDefaultHome: () -> Unit = {}

    fun editFolderName(id: String) {
        menu = null
        openFolder = id
        focusFolderName = true
    }

    fun setFolderColor(id: String, color: Int) {
        val f = layout.folders[id] ?: return
        commit(layout.copy(folders = layout.folders + (id to f.copy(color = color))))
    }

    fun renameFolder(id: String, name: String) {
        val f = layout.folders[id] ?: return
        commit(layout.copy(folders = layout.folders + (id to f.copy(name = name))))
    }

    /** 0 = home, 1 = Apps screen. Follows the finger during a swipe, springs to 0 or 1 on release. */
    var drawerAnim: Animatable<Float, AnimationVector1D>? = null

    // While a finger drives the transition, progress is a plain state updated on every move.
    // Feeding each move to the Animatable as its own coroutine let the steps cancel each other.
    private var fingerProgress by mutableStateOf<Float?>(null)

    val drawerProgress get() = fingerProgress ?: drawerAnim?.value ?: if (drawerOpen) 1f else 0f

    fun dragDrawerBy(delta: Float) {
        val a = drawerAnim ?: return
        if (fingerProgress == null) uiScope?.launch { a.stop() }
        fingerProgress = ((fingerProgress ?: a.value) + delta).coerceIn(0f, 1f)
    }

    /**
     * Release rule: a fling over 1 px/ms decides
     * by its direction; without one, the swipe commits only past 40%. The settle duration
     * shrinks with finger speed and with the distance left. The reference pixel values are for a
     * ~3.5x density screen, so they are rescaled to this one.
     */
    fun settleDrawer(velocityY: Float, fromDrawer: Boolean) {
        val toTarget = (if (fromDrawer) velocityY else -velocityY) / 1000f * (ONE_UI_DENSITY / density)
        val q = if (fromDrawer) 1f - drawerProgress else drawerProgress
        val fling = abs(toTarget) > 1f
        val commit = if (fling) toTarget > 0f else q > 0.4f
        val remaining = if (commit) 1f - q else q
        val duration = max(100f, 1200f / max(2f, abs(toTarget) * 0.5f) * max(0.2f, remaining)).toInt()
        val open = commit != fromDrawer
        if (open) openDrawer(duration) else closeDrawer(duration)
    }

    private fun animateDrawer(target: Float, durationMs: Int?) {
        val a = drawerAnim ?: return
        val spec: AnimationSpec<Float> =
            if (durationMs == null) spring(dampingRatio = 1f, stiffness = 500f)
            else tween(durationMs, easing = FastOutSlowInEasing)
        val from = fingerProgress
        uiScope?.launch {
            // Hand over from the finger value only once the Animatable holds it, so nothing jumps.
            if (from != null) {
                a.snapTo(from)
                fingerProgress = null
            }
            a.animateTo(target, spec)
        }
    }

    fun openDrawer(durationMs: Int? = null) {
        if (!drawerOpen) {
            query = ""
            searching = false
        }
        drawerOpen = true
        animateDrawer(1f, durationMs)
    }

    fun closeDrawer(durationMs: Int? = null) {
        drawerOpen = false
        query = ""
        searching = false
        animateDrawer(0f, durationMs)
    }

    private var anchorPage: Int? = null

    fun takeAnchorPage() = anchorPage.also { anchorPage = null }

    fun enterEditMode() {
        if (!drawerOpen && drag == null) {
            menu = null
            // Only looping changes the pager's count here; otherwise no re-anchor runs to take it.
            if (homeLoops) anchorPage = currentHomePage()
            editMode = true
        }
    }

    fun exitEditMode() {
        if (settings.loopPages && layout.home.size > 1) anchorPage = currentHomePage().coerceAtMost(layout.home.size - 1)
        editMode = false
    }

    private var addPageTapped = false

    fun addHomePage() {
        commit(layout.copy(home = layout.home + listOf(emptyMap())))
        addPageTapped = true
    }

    fun consumeAddPageTap() = addPageTapped.also { addPageTapped = false }

    /** Edit mode shows one extra page after the last, a "+" card that adds a page. */
    fun isAddPage(page: Int) = editMode && page == layout.home.size

    fun setDefaultPage(page: Int) {
        settings.defaultHomePage = page
    }

    fun deletePage(page: Int) {
        if (layout.home.size <= 1) return
        commit(layout.copy(home = layout.home.filterIndexed { i, _ -> i != page }))
        if (settings.defaultHomePage >= layout.home.size) settings.defaultHomePage = 0
    }

    fun applyDrawerGridSetting() {
        val (c, r) = Grid.DRAWER_GRIDS.getOrElse(settings.drawerGrid) { Grid.DRAWER_GRIDS.last() }
        Grid.drawerCols = c
        Grid.drawerRows = r
    }

    fun setDrawerGrid(index: Int) {
        settings.drawerGrid = index
        applyDrawerGridSetting()
        commit(LayoutOps.rechunkDrawer(layout))
    }

    fun openWebSearch() = repo.openWebSearch()
    fun openWallpaperPicker() = repo.openWallpaperPicker()

    fun onSwipeDown() {
        if (settings.swipeDownForNotifications) repo.expandNotifications()
    }

    fun onBack() {
        when {
            menu != null -> menu = null
            resizing != null -> resizing = null
            openFolder != null -> openFolder = null
            widgetPickerOpen -> widgetPickerOpen = false
            settingsOpen -> settingsOpen = false
            searching -> {
                searching = false
                query = ""
            }
            editMode -> exitEditMode()
            else -> closeDrawer()
        }
    }

    fun onHomePressed() {
        menu = null
        drag = null
        openFolder = null
        settingsOpen = false
        widgetPickerOpen = false
        editMode = false
        resizing = null
        closeDrawer()
        val target = settings.defaultHomePage.coerceIn(0, layout.home.size - 1)
        animatePager(homePager, if (homeLoops) loopAnchor(target) else target)
    }

    // ---- Popup and drag ----

    fun showMenu(key: String, source: Slot, anchor: Rect) {
        val shortcuts = apps[key]?.let { entry ->
            repo.shortcuts(entry).map {
                Shortcut(it, (it.shortLabel ?: it.longLabel ?: "").toString(), repo.shortcutIcon(it, shortcutIconPx))
            }
        }.orEmpty()
        menu = MenuRequest(key, source, anchor, shortcuts)
    }

    /** Long press shows the popup at once; moving the finger past the slop turns it into a drag. */
    fun onLongPress(key: String, source: Slot, pointer: Offset, anchor: Rect, draggable: Boolean) {
        showMenu(key, source, anchor)
        if (draggable) {
            edgeTicks = 0
            zoneTicks = 0
            drag = DragState(key, source, pointer, pointer)
        }
    }

    fun onPointerMove(p: Offset) {
        val d = drag ?: return
        if (!d.moved && (p - d.start).getDistance() <= touchSlop) {
            drag = d.copy(pointer = p)
            return
        }
        if (!d.moved) {
            menu = null
            openFolder = null
        }
        drag = retarget(d.copy(pointer = p, moved = true))
    }

    private fun retarget(d: DragState): DragState {
        val p = d.pointer
        if (drawerOpen) {
            val fromDrawer = d.source is Slot.Drawer || d.source is Slot.InFolder
            val zone = if (p.y < drawerGrid.top && fromDrawer) Zone.ADD_TO_HOME else null
            if (!drawerGrid.contains(p) || searching) return d.copy(zone = zone, target = null, merge = false)
            val page = drawerPager?.currentPage ?: 0
            val cell = Grid.cellAt(drawerGrid, Grid.drawerCols, Grid.drawerRows, p)
            val occupant = layout.drawer.getOrNull(page)?.getOrNull(cell)
            val merge = occupant != null && occupant != d.key && !isFolderKey(d.key) &&
                Grid.isCellCenter(drawerGrid, Grid.drawerCols, Grid.drawerRows, p)
            return d.copy(zone = zone, target = Slot.Drawer(page, cell), merge = merge)
        }
        val zone = if (p.y < homeGrid.top && d.source !is Slot.Drawer) Zone.REMOVE else null
        if (isWidgetKey(d.key)) {
            if (!homeGrid.contains(p)) return d.copy(zone = zone, target = null, merge = false)
            val w = parseWidget(d.key)
            val cell = Grid.cellAt(homeGrid, Grid.HOME_COLS, Grid.HOME_ROWS, p)
            val c = (cell % Grid.HOME_COLS - d.grabCol).coerceIn(0, Grid.HOME_COLS - w.spanX)
            val r = (cell / Grid.HOME_COLS - d.grabRow).coerceIn(0, Grid.HOME_ROWS - w.spanY)
            return d.copy(zone = zone, target = Slot.Home(currentHomePage(), r * Grid.HOME_COLS + c), merge = false)
        }
        val target = when {
            homeGrid.contains(p) ->
                Slot.Home(currentHomePage(), Grid.cellAt(homeGrid, Grid.HOME_COLS, Grid.HOME_ROWS, p))
            dockArea.contains(p) ->
                Slot.Dock(Grid.cellAt(dockArea, Grid.DOCK_SLOTS, 1, p))
            else -> null
        }
        val occupant = target?.let(layout::keyAt)
        return d.copy(zone = zone, target = target, merge = occupant != null && occupant != d.key)
    }

    /** Runs every ~100 ms while dragging: dwell on a page edge flips the page, dwell on top of the drawer jumps to home. */
    suspend fun tick() {
        val d = drag ?: return
        if (!d.moved) return
        if (drawerOpen && d.zone == Zone.ADD_TO_HOME) {
            if (++zoneTicks >= 5) {
                zoneTicks = 0
                closeDrawer()
            }
        } else {
            zoneTicks = 0
        }
        val pager = (if (drawerOpen) drawerPager else homePager) ?: return
        val rect = if (drawerOpen) drawerGrid else homeGrid
        val p = d.pointer
        val dir = when {
            p.y !in rect.top..rect.bottom -> 0
            p.x < rect.left + edgePx -> -1
            p.x > rect.right - edgePx -> 1
            else -> 0
        }
        if (dir == 0) {
            edgeTicks = 0
        } else if (++edgeTicks >= 2) {
            edgeTicks = 0
            val page = pager.currentPage + dir
            if (page in 0 until pager.pageCount) pager.animateScrollToPage(page)
        }
        drag?.let { drag = retarget(it) }
    }

    fun endDrag() {
        val d = drag ?: return
        drag = null
        if (!d.moved) return
        val m = layout
        if (isWidgetKey(d.key)) {
            val t = d.target
            when {
                d.zone == Zone.REMOVE -> removeWidget(d.source, d.key)
                t is Slot.Home -> {
                    val w = parseWidget(d.key)
                    if (m.fitsAt(t.page, t.cell, w.spanX, w.spanY, ignore = d.key)) {
                        commit(m.clear(d.source).put(t, d.key))
                    }
                }
            }
            return
        }
        val next = when (d.zone) {
            Zone.REMOVE -> m.clear(d.source)
            Zone.ADD_TO_HOME -> if (isFolderKey(d.key)) m else LayoutOps.addToHome(m, d.key)
            null -> when (val t = d.target) {
                is Slot.Drawer -> when {
                    d.merge -> LayoutOps.merge(m, d.key, d.source, t)
                    d.source is Slot.Drawer || d.source is Slot.InFolder -> {
                        val r = LayoutOps.detach(m, d.key, d.source, toDrawer = true)
                        r.copy(drawer = LayoutOps.drawerMove(r.drawer, d.key, t.page, t.index))
                    }
                    else -> m
                }
                is Slot.Home, is Slot.Dock -> LayoutOps.dropOnHome(m, d.key, d.source, t)
                null -> {
                    val copyable = d.source is Slot.Drawer || d.source is Slot.InFolder
                    if (copyable && !drawerOpen && !isFolderKey(d.key)) LayoutOps.addToHome(m, d.key) else m
                }
                else -> m
            }
        }
        commit(next)
        // The reference design opens a freshly made folder once its creation animation has played.
        val created = layout.folders.keys - m.folders.keys
        created.firstOrNull()?.let { id ->
            scope.launch {
                delay(450)
                if (drag == null && layout.folders.containsKey(id)) openFolder = id
            }
        }
    }
}
