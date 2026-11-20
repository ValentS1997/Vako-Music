package ru.rikko.music

import android.content.Context
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class SavedQueueStore(context: Context) {
    data class Entry(val localUri: String? = null, val remote: Track? = null)
    data class Snapshot(val entries: List<Entry>, val index: Int = 0)

    private val prefs = context.getSharedPreferences("rikko_saved_queues", Context.MODE_PRIVATE)

    fun names(): List<String> = prefs.getAll().keys.filter { it != "@current" }
        .sortedWith(String.CASE_INSENSITIVE_ORDER)

    fun save(name: String, snapshot: Snapshot): Boolean {
        val clean = name.trim()
        if (clean.isEmpty() || snapshot.entries.isEmpty() || prefs.contains(clean)) return false
        prefs.edit().putString(clean, Codec.encode(snapshot)).apply()
        return true
    }

    fun saveCurrent(snapshot: Snapshot) {
        prefs.edit().putString("@current", Codec.encode(snapshot)).apply()
    }

    fun load(name: String): Snapshot? = prefs.getString(name, null)?.let(Codec::decode)

    fun loadCurrent(): Snapshot? = load("@current")

    fun delete(name: String) {
        if (name != "@current") prefs.edit().remove(name).apply()
    }

    /** Отдельный кодек: формат сохранённых очередей не зависит от Activity и сервиса. */
    internal object Codec {
        fun encode(snapshot: Snapshot): String {
            val json = JSONObject().put("index", snapshot.index)
            val items = JSONArray()
            snapshot.entries.forEach { entry ->
                val item = JSONObject()
                if (entry.localUri != null) item.put("uri", entry.localUri)
                entry.remote?.let { track ->
                    item.put("trackId", track.trackId).put("albumId", track.albumId)
                        .put("title", track.title).put("artist", track.artist)
                        .put("album", track.album).put("coverUri", track.coverUri)
                }
                items.put(item)
            }
            json.put("items", items)
            val bytes = ByteArrayOutputStream()
            GZIPOutputStream(bytes).use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
            return Base64.getEncoder().encodeToString(bytes.toByteArray())
        }

        fun decode(value: String): Snapshot? = runCatching {
            val bytes = Base64.getDecoder().decode(value)
            val json = JSONObject(GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes().toString(Charsets.UTF_8) })
            val items = json.optJSONArray("items") ?: JSONArray()
            val entries = (0 until items.length()).mapNotNull { index ->
                val item = items.optJSONObject(index) ?: return@mapNotNull null
                val uri = item.optString("uri")
                if (uri.isNotBlank()) Entry(localUri = uri) else {
                    val id = item.optString("trackId")
                    if (id.isBlank()) null else Entry(remote = Track(
                        trackId = id, albumId = item.optString("albumId"),
                        title = item.optString("title"), artist = item.optString("artist"),
                        album = item.optString("album"), coverUri = item.optString("coverUri")
                    ))
                }
            }
            Snapshot(entries, if (entries.isEmpty()) 0 else
                json.optInt("index", 0).coerceIn(0, entries.lastIndex))
        }.getOrNull()
    }
}
