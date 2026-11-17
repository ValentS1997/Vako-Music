package ru.rikko.music

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class VideoPlaylist(val name: String, val items: List<String>)

class VideoPlaylistStore(context: Context) {
    private val preferences = context.getSharedPreferences("vako_video_playlists", Context.MODE_PRIVATE)

    fun all(): List<VideoPlaylist> = runCatching {
        val root = JSONObject(preferences.getString("playlists", "{}") ?: "{}")
        root.keys().asSequence().map { name ->
            val array = root.getJSONArray(name)
            VideoPlaylist(name, (0 until array.length()).map { array.getString(it) })
        }.sortedBy { it.name.lowercase() }.toList()
    }.getOrDefault(emptyList())

    fun get(name: String): VideoPlaylist? = all().firstOrNull { it.name == name }

    fun create(name: String, items: List<String> = emptyList()) {
        val clean = validName(name)
        require(get(clean) == null) { "Плейлист с таким названием уже есть" }
        put(clean, items)
    }

    fun put(name: String, items: List<String>) = edit { root ->
        root.put(name, JSONArray(items.distinct()))
    }

    fun add(name: String, items: List<String>) {
        val old = requireNotNull(get(name)) { "Плейлист не найден" }
        put(name, old.items + items)
    }

    fun remove(name: String, uri: String) {
        val old = requireNotNull(get(name)) { "Плейлист не найден" }
        put(name, old.items.filterNot { it == uri })
    }

    fun rename(oldName: String, newName: String) {
        val old = requireNotNull(get(oldName)) { "Плейлист не найден" }
        val clean = validName(newName)
        require(clean == oldName || get(clean) == null) { "Плейлист с таким названием уже есть" }
        edit { root -> root.remove(oldName); root.put(clean, JSONArray(old.items)) }
    }

    fun delete(name: String) = edit { it.remove(name) }

    fun move(name: String, uri: String, offset: Int) {
        val old = requireNotNull(get(name)) { "Плейлист не найден" }
        val items = old.items.toMutableList()
        val index = items.indexOf(uri)
        val target = index + offset
        if (index >= 0 && target in items.indices) {
            items.add(target, items.removeAt(index))
            put(name, items)
        }
    }

    private fun validName(value: String): String = value.trim().also {
        require(it.isNotEmpty()) { "Введите название плейлиста" }
    }

    private inline fun edit(change: (JSONObject) -> Unit) {
        val root = runCatching { JSONObject(preferences.getString("playlists", "{}") ?: "{}") }.getOrDefault(JSONObject())
        change(root)
        preferences.edit().putString("playlists", root.toString()).apply()
    }
}
