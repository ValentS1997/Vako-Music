package ru.rikko.music

import org.json.JSONArray
import org.json.JSONObject

/** Rejects positional deletion whenever the remote list differs from the displayed snapshot. */
internal object PlaylistRemovalGuard {
    data class Identity(val trackId: String, val albumId: String)

    fun positionIfUnchanged(displayed: List<Track>, raw: JSONArray, position: Int): Int {
        require(position in displayed.indices) { "Выберите трек заново" }
        if (raw.length() != displayed.size)
            throw IllegalStateException("Плейлист изменился. Список обновлён — выберите трек заново")
        val actual = (0 until raw.length()).map { index -> identity(raw.optJSONObject(index)) }
        val expected = displayed.map { Identity(it.trackId, it.albumId) }
        if (actual.any { it == null } || actual != expected)
            throw IllegalStateException("Плейлист изменился. Список обновлён — выберите трек заново")
        return position
    }

    private fun identity(item: JSONObject?): Identity? {
        if (item == null) return null
        val track = item.optJSONObject("track") ?: item
        val id = track.optString("id").ifBlank { track.optString("realId") }
        if (id.isBlank()) return null
        val album = track.optJSONArray("albums")?.optJSONObject(0)?.optString("id")
            ?.takeIf { it.isNotBlank() } ?: item.optString("albumId")
        return Identity(id, album)
    }
}
