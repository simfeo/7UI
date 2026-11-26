package com.idimus.sevenui

import android.content.Context
import android.net.Uri
import org.json.JSONObject

/**
 * Home layout and settings in one JSON file the user keeps, so 7UI can be reinstalled
 * (for example when switching to a differently signed build) without setting it up again.
 */
object Backup {
    private const val FORMAT = 1

    fun write(context: Context, uri: Uri) {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val settings = JSONObject()
        for ((k, v) in prefs.all) {
            when (v) {
                is Boolean, is Int, is String -> settings.put(k, JSONObject().put("type", v.javaClass.simpleName).put("value", v))
                else -> {}
            }
        }
        val layout = LayoutStore(context).load()?.let(LayoutStore::toJson)
        val root = JSONObject()
            .put("app", "7UI")
            .put("format", FORMAT)
            .put("settings", settings)
            .put("layout", layout ?: JSONObject.NULL)
        context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(root.toString(2).toByteArray()) }
    }

    /** Throws on a file that is not a 7UI backup; the caller reloads the launcher afterwards. */
    fun read(context: Context, uri: Uri) {
        val text = context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
        val root = JSONObject(text)
        require(root.optString("app") == "7UI") { "Not a 7UI backup" }

        val settings = root.getJSONObject("settings")
        val edit = context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear()
        for (k in settings.keys()) {
            val e = settings.getJSONObject(k)
            when (e.getString("type")) {
                "Boolean" -> edit.putBoolean(k, e.getBoolean("value"))
                "Integer" -> edit.putInt(k, e.getInt("value"))
                "String" -> edit.putString(k, e.getString("value"))
            }
        }
        edit.commit()

        val layout = root.optJSONObject("layout") ?: return
        val m = LayoutStore.parse(layout)
        // Widget ids from another install cannot be bound again; only ids still alive here are kept.
        val ours = LauncherWidgetHost(context).appWidgetIds.toSet()
        fun alive(key: String) = !isWidgetKey(key) || parseWidget(key).id in ours
        val kept = m.copy(home = m.home.map { page -> page.filterValues(::alive) })
        LayoutStore(context).save(LayoutOps.cleanup(kept))
    }
}
