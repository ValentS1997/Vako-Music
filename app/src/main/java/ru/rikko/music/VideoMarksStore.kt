package ru.rikko.music

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class VideoBookmark(val uri: String, val title: String, val positionMs: Long, val label: String)

/** User video favorites and position bookmarks; media files are not modified. */
class VideoMarksStore(context: Context) {
    private val prefs = context.getSharedPreferences("vako_video_marks", Context.MODE_PRIVATE)

    fun favorites(): Set<String> = runCatching {
        val array = JSONArray(prefs.getString("favorites", "[]"))
        (0 until array.length()).map(array::getString).toSet()
    }.getOrDefault(emptySet())

    fun isFavorite(uri: String): Boolean = uri in favorites()

    fun toggleFavorite(uri: String): Boolean {
        val values = favorites().toMutableSet()
        val added = if (uri in values) { values.remove(uri); false } else { values.add(uri); true }
        prefs.edit().putString("favorites", JSONArray(values.toList()).toString()).apply()
        return added
    }

    fun bookmarks(): List<VideoBookmark> = runCatching {
        val array = JSONArray(prefs.getString("bookmarks", "[]"))
        (0 until array.length()).map { index ->
            val value = array.getJSONObject(index)
            VideoBookmark(value.getString("uri"), value.optString("title", "Видео"),
                value.getLong("positionMs"), value.optString("label", ""))
        }
    }.getOrDefault(emptyList())

    fun addBookmark(bookmark: VideoBookmark) {
        val items = listOf(bookmark) + bookmarks().filterNot {
            it.uri == bookmark.uri && kotlin.math.abs(it.positionMs - bookmark.positionMs) < 1000
        }
        writeBookmarks(items)
    }

    fun removeBookmark(bookmark: VideoBookmark) {
        writeBookmarks(bookmarks().filterNot { it.uri == bookmark.uri && it.positionMs == bookmark.positionMs })
    }

    private fun writeBookmarks(items: List<VideoBookmark>) {
        val array = JSONArray()
        items.forEach { array.put(JSONObject().put("uri", it.uri).put("title", it.title)
            .put("positionMs", it.positionMs).put("label", it.label)) }
        prefs.edit().putString("bookmarks", array.toString()).apply()
    }
}
