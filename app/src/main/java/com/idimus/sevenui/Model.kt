package com.idimus.sevenui

import android.content.ComponentName
import android.content.pm.LauncherActivityInfo
import android.os.UserHandle
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

object Grid {
    const val HOME_COLS = 4
    const val HOME_ROWS = 6
    const val DOCK_SLOTS = 4
    const val FOLDER_COLS = 3
    val DRAWER_GRIDS = listOf(4 to 5, 5 to 5, 5 to 6)

    var drawerCols = 5
    var drawerRows = 6
    val drawerPage get() = drawerCols * drawerRows

    fun cellAt(rect: Rect, cols: Int, rows: Int, p: Offset): Int {
        val c = ((p.x - rect.left) / (rect.width / cols)).toInt().coerceIn(0, cols - 1)
        val r = ((p.y - rect.top) / (rect.height / rows)).toInt().coerceIn(0, rows - 1)
        return r * cols + c
    }

    /** Inner part of a cell means "drop onto this icon" (make a folder), the rest means "drop beside it". */
    fun isCellCenter(rect: Rect, cols: Int, rows: Int, p: Offset): Boolean {
        val cw = rect.width / cols
        val ch = rect.height / rows
        val fx = ((p.x - rect.left) % cw) / cw
        val fy = ((p.y - rect.top) % ch) / ch
        return fx in 0.22f..0.78f && fy in 0.12f..0.7f
    }
}

const val FOLDER_PREFIX = "folder:"

fun isFolderKey(key: String) = key.startsWith(FOLDER_PREFIX)
fun folderId(key: String) = key.removePrefix(FOLDER_PREFIX)

const val WIDGET_PREFIX = "widget:"

/** A widget is stored at its top-left cell as "widget:<appWidgetId>:<spanX>x<spanY>". */
data class WidgetSpec(val id: Int, val spanX: Int, val spanY: Int)

fun isWidgetKey(key: String) = key.startsWith(WIDGET_PREFIX)
fun widgetKey(id: Int, spanX: Int, spanY: Int) = "$WIDGET_PREFIX$id:${spanX}x$spanY"
fun parseWidget(key: String): WidgetSpec {
    val (id, span) = key.removePrefix(WIDGET_PREFIX).split(':')
    val (x, y) = span.split('x')
    return WidgetSpec(id.toInt(), x.toInt(), y.toInt())
}

data class AppEntry(
    val key: String,
    val label: String,
    val component: ComponentName,
    val user: UserHandle,
    val info: LauncherActivityInfo,
)

/** [color] indexes [FOLDER_COLORS]. */
data class Folder(val name: String, val items: List<String>, val color: Int = 0)

/** Five folder colors; index 0 is the neutral translucent default. */
val FOLDER_COLORS = listOf(0x52FFFFFF, 0xCC3E91FF, 0xCC34C759, 0xCCFF9F0A, 0xCCFF453A).map { it.toInt() }

sealed interface Slot {
    data class Home(val page: Int, val cell: Int) : Slot
    data class Dock(val cell: Int) : Slot
    data class Drawer(val page: Int, val index: Int) : Slot
    data class InFolder(val id: String, val index: Int) : Slot
    data object Search : Slot
}

/**
 * Home and dock are sparse grids (cell -> key), so icons can sit anywhere with gaps.
 * The drawer is a list of pages without gaps, like a custom order: inserting
 * into a full page pushes its last icon onto the next page.
 * A key is an app or "folder:<id>". Home icons are shortcuts, so an app may be on home
 * and in the drawer at once; a drawer folder owns its apps, they are not elsewhere in the drawer.
 */
data class LayoutModel(
    val home: List<Map<Int, String>>,
    val dock: Map<Int, String>,
    val drawer: List<List<String>>,
    val folders: Map<String, Folder> = emptyMap(),
) {
    /** Every cell of a home page that something covers; a widget covers its whole span. */
    fun homeOccupancy(page: Int): Map<Int, String> {
        val out = HashMap<Int, String>()
        for ((cell, key) in home.getOrNull(page).orEmpty()) {
            if (!isWidgetKey(key)) {
                out[cell] = key
                continue
            }
            val w = parseWidget(key)
            val c0 = cell % Grid.HOME_COLS
            val r0 = cell / Grid.HOME_COLS
            for (r in r0 until r0 + w.spanY) for (c in c0 until c0 + w.spanX) out[r * Grid.HOME_COLS + c] = key
        }
        return out
    }

    /** True when a spanX x spanY block at [cell] is on the grid and covers nothing but [ignore]. */
    fun fitsAt(page: Int, cell: Int, spanX: Int, spanY: Int, ignore: String? = null): Boolean {
        val c0 = cell % Grid.HOME_COLS
        val r0 = cell / Grid.HOME_COLS
        if (c0 + spanX > Grid.HOME_COLS || r0 + spanY > Grid.HOME_ROWS) return false
        val occ = homeOccupancy(page)
        for (r in r0 until r0 + spanY) for (c in c0 until c0 + spanX) {
            val k = occ[r * Grid.HOME_COLS + c]
            if (k != null && k != ignore) return false
        }
        return true
    }

    fun keyAt(slot: Slot): String? = when (slot) {
        is Slot.Home -> homeOccupancy(slot.page)[slot.cell]
        is Slot.Dock -> dock[slot.cell]
        is Slot.Drawer -> drawer.getOrNull(slot.page)?.getOrNull(slot.index)
        is Slot.InFolder -> folders[slot.id]?.items?.getOrNull(slot.index)
        Slot.Search -> null
    }

    fun put(slot: Slot, key: String): LayoutModel = when (slot) {
        is Slot.Home -> {
            val pages = home.toMutableList()
            while (pages.size <= slot.page) pages.add(emptyMap())
            pages[slot.page] = pages[slot.page] + (slot.cell to key)
            copy(home = pages)
        }
        is Slot.Dock -> copy(dock = dock + (slot.cell to key))
        else -> this
    }

    fun clear(slot: Slot): LayoutModel = when (slot) {
        is Slot.Home -> copy(home = home.mapIndexed { i, p -> if (i == slot.page) p - slot.cell else p })
        is Slot.Dock -> copy(dock = dock - slot.cell)
        else -> this
    }

    fun isDrawerFolder(id: String) = drawer.any { page -> page.any { it == FOLDER_PREFIX + id } }

    companion object {
        val EMPTY = LayoutModel(listOf(emptyMap()), emptyMap(), listOf(emptyList()))
    }
}
