package com.idimus.sevenui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var loopPages by PrefBool(prefs, "loop_pages", false)
    var swipeDownForNotifications by PrefBool(prefs, "swipe_down_notifications", true)
    var homeSearchBar by PrefBool(prefs, "home_search_bar", true)
    var lockLayout by PrefBool(prefs, "lock_layout", false)
    var edgePanel by PrefBool(prefs, "edge_panel", false)
    var edgePanelLeft by PrefBool(prefs, "edge_panel_left", false)
    var defaultHomePage by PrefInt(prefs, "default_home_page", 0)

    /** Index into [Grid.DRAWER_GRIDS]. */
    var drawerGrid by PrefInt(prefs, "drawer_grid", 2)
}

private class PrefBool(
    private val prefs: SharedPreferences,
    private val name: String,
    default: Boolean,
) : ReadWriteProperty<Any?, Boolean> {
    private val state = mutableStateOf(prefs.getBoolean(name, default))
    override fun getValue(thisRef: Any?, property: KProperty<*>) = state.value
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) {
        state.value = value
        prefs.edit().putBoolean(name, value).apply()
    }
}

private class PrefInt(
    private val prefs: SharedPreferences,
    private val name: String,
    default: Int,
) : ReadWriteProperty<Any?, Int> {
    private val state = mutableStateOf(prefs.getInt(name, default))
    override fun getValue(thisRef: Any?, property: KProperty<*>) = state.value
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) {
        state.value = value
        prefs.edit().putInt(name, value).apply()
    }
}
