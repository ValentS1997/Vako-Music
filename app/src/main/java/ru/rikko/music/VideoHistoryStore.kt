package ru.rikko.music

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class VideoHistoryEntry(val uri: String, val title: String, val viewedAt: Long, val positionMs: Long)

class VideoHistoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("vako_video_history", Context.MODE_PRIVATE)

    fun all(): List<VideoHistoryEntry> = runCatching {
        val array = JSONArray(prefs.getString("entries", "[]"))
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            VideoHistoryEntry(item.getString("uri"), item.optString("title", "Видео"),
                item.getLong("viewedAt"), item.optLong("positionMs"))
        }.sortedByDescending { it.viewedAt }
    }.getOrDefault(emptyList())

    fun record(uri: String, title: String, positionMs: Long) {
        val entries = listOf(VideoHistoryEntry(uri, title, System.currentTimeMillis(), positionMs)) + all().filter { it.uri != uri }
        val array = JSONArray()
        entries.take(100).forEach { entry ->
            array.put(JSONObject().put("uri", entry.uri).put("title", entry.title)
                .put("viewedAt", entry.viewedAt).put("positionMs", entry.positionMs))
        }
        prefs.edit().putString("entries", array.toString()).apply()
    }

    fun clear() { prefs.edit().remove("entries").apply() }
}
