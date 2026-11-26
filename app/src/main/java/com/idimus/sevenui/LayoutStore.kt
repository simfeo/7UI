package com.idimus.sevenui

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class LayoutStore(context: Context) {
    private val file = File(context.filesDir, "layout.json")

    fun load(): LayoutModel? {
        if (!file.exists()) return null
        return try {
            parse(JSONObject(file.readText()))
        } catch (e: Exception) {
            Log.w(TAG, "Unreadable layout, starting fresh", e)
            null
        }
    }

    fun save(m: LayoutModel) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(toJson(m).toString())
        tmp.renameTo(file)
    }

    companion object {
        private const val TAG = "LayoutStore"

        fun toJson(m: LayoutModel) = JSONObject().apply {
            put("home", JSONArray(m.home.map { cellsToJson(it) }))
            put("dock", cellsToJson(m.dock))
            put("drawer", JSONArray(m.drawer.map { JSONArray(it) }))
            put("folders", JSONObject().apply {
                m.folders.forEach { (id, f) ->
                    put(id, JSONObject().put("name", f.name).put("items", JSONArray(f.items)).put("color", f.color))
                }
            })
        }

        fun parse(o: JSONObject): LayoutModel {
            val home = o.getJSONArray("home")
            val drawer = o.getJSONArray("drawer")
            val folders = o.optJSONObject("folders") ?: JSONObject()
            return LayoutModel(
                folders = folders.keys().asSequence().associateWith { id ->
                    val f = folders.getJSONObject(id)
                    val items = f.getJSONArray("items")
                    Folder(f.getString("name"), (0 until items.length()).map { items.getString(it) }, f.optInt("color", 0))
                },
                home = (0 until home.length()).map { cellsFromJson(home.getJSONObject(it)) },
                dock = cellsFromJson(o.getJSONObject("dock")),
                drawer = (0 until drawer.length()).map { i ->
                    val page = drawer.getJSONArray(i)
                    (0 until page.length()).map { page.getString(it) }
                },
            )
        }

        private fun cellsToJson(cells: Map<Int, String>) =
            JSONObject().apply { cells.forEach { (cell, key) -> put(cell.toString(), key) } }

        private fun cellsFromJson(o: JSONObject): Map<Int, String> =
            o.keys().asSequence().associate { it.toInt() to o.getString(it) }
    }
}
