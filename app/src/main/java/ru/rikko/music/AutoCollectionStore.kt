package ru.rikko.music

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class AutoCollection(val name: String, val tracks: List<Track>, val updatedAt: Long = System.currentTimeMillis())

/** Local snapshots. No background synchronization with Yandex playlists. */
class AutoCollectionStore(context: Context) {
    private val prefs = context.getSharedPreferences("vako_auto_collections", Context.MODE_PRIVATE)

    fun all(): List<AutoCollection> = runCatching {
        val data = JSONArray(prefs.getString("items", "[]") ?: "[]")
        (0 until data.length()).mapNotNull { index ->
            val item = data.optJSONObject(index) ?: return@mapNotNull null
            val tracks = item.optJSONArray("tracks") ?: JSONArray()
            AutoCollection(item.optString("name"), (0 until tracks.length()).mapNotNull { i ->
                tracks.optJSONObject(i)?.let { t ->
                    Track(t.optString("id"), t.optString("albumId"), t.optString("title"),
                        t.optString("artist"), coverUri = t.optString("coverUri"))
                        .takeIf { it.trackId.isNotBlank() }
                }
            }, item.optLong("updatedAt", 0L))
        }.filter { it.name.isNotBlank() }
    }.getOrDefault(emptyList())

    fun save(name: String, tracks: List<Track>) {
        val clean = name.trim()
        require(clean.isNotBlank()) { "Введите название подборки" }
        val current = all()
        require(current.none { it.name.equals(clean, true) }) { "Подборка с таким названием уже есть" }
        require(current.size < 10) { "Можно сохранить не более 10 подборок" }
        require(tracks.isNotEmpty()) { "Не удалось найти треки для подборки" }
        write(current + AutoCollection(clean, tracks.distinctBy { "${it.trackId}:${it.albumId}" }.take(100)))
    }

    fun delete(name: String) = write(all().filterNot { it.name == name })

    fun rename(oldName: String, newName: String) {
        val clean = newName.trim()
        require(clean.isNotBlank()) { "Введите название подборки" }
        val current = all()
        require(current.any { it.name == oldName }) { "Подборка не найдена" }
        require(current.none { it.name != oldName && it.name.equals(clean, true) }) { "Такое название уже есть" }
        write(current.map { if (it.name == oldName) it.copy(name = clean) else it })
    }

    fun replace(name: String, tracks: List<Track>) {
        require(tracks.isNotEmpty()) { "Не удалось найти треки для подборки" }
        val current = all()
        require(current.any { it.name == name }) { "Подборка не найдена" }
        write(current.map { if (it.name == name) it.copy(
            tracks = tracks.distinctBy { t -> "${t.trackId}:${t.albumId}" }.take(100),
            updatedAt = System.currentTimeMillis()) else it })
    }

    private fun write(items: List<AutoCollection>) {
        val data = JSONArray()
        items.forEach { collection ->
            val tracks = JSONArray()
            collection.tracks.forEach { t -> tracks.put(JSONObject().put("id", t.trackId)
                .put("albumId", t.albumId).put("title", t.title).put("artist", t.artist)
                .put("coverUri", t.coverUri)) }
            data.put(JSONObject().put("name", collection.name).put("tracks", tracks)
                .put("updatedAt", collection.updatedAt))
        }
        prefs.edit().putString("items", data.toString()).apply()
    }
}
