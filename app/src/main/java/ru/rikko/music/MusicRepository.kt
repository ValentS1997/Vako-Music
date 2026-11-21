package ru.rikko.music

import org.json.JSONObject
import org.json.JSONArray
import java.net.URLEncoder

/** Small adapter around Yandex's undocumented endpoints. */
object MusicRepository {
    private const val API = "https://api.music.yandex.net"

    data class Artist(val id: String, val name: String)
    data class UserPlaylist(val owner: String, val kind: String, val title: String, val count: Int)

    private fun ownUid(token: String): String {
        val account = result(Http.getString("$API/account/status", token)).optJSONObject("account")
            ?: throw IllegalStateException("Не удалось получить профиль Яндекс Музыки")
        return account.optString("uid").takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Не найден ID аккаунта")
    }

    private fun result(json: String): JSONObject = JSONObject(json).optJSONObject("result") ?: JSONObject()

    fun ownPlaylists(token: String): List<UserPlaylist> {
        val uid = ownUid(token)
        val response = JSONObject(Http.getString("$API/users/$uid/playlists/list", token))
        val items = response.optJSONArray("result") ?: JSONArray()
        return (0 until items.length()).mapNotNull { i ->
            val item = items.optJSONObject(i) ?: return@mapNotNull null
            val kind = item.optString("kind")
            if (kind.isBlank()) return@mapNotNull null
            val owner = item.optJSONObject("owner")?.optString("uid")?.takeIf { it.isNotBlank() } ?: uid
            if (owner != uid) return@mapNotNull null
            UserPlaylist(
                owner = owner,
                kind = kind,
                title = item.optString("title").ifBlank { "Плейлист $kind" },
                count = item.optInt("trackCount", 0)
            )
        }
    }

    /** Liked tracks are a separate library, not an ordinary user playlist. */
    fun likedTracks(token: String, onProgress: (Int, Int) -> Unit = { _, _ -> }): List<Track> {
        val uid = ownUid(token)
        val library = result(Http.getString("$API/users/$uid/likes/tracks", token))
            .optJSONObject("library")?.optJSONArray("tracks") ?: JSONArray()
        val ids = (0 until library.length()).mapNotNull { i ->
            library.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() }
        }
        val resolved = ArrayList<Track>(ids.size)
        for (chunk in ids.chunked(100)) {
            val body = "track-ids=${URLEncoder.encode(chunk.joinToString(","), "UTF-8")}"
            val response = JSONObject(Http.postString("$API/tracks", body, token)).optJSONArray("result") ?: JSONArray()
            val byId = (0 until response.length()).mapNotNull { i ->
                response.optJSONObject(i)?.let(::shapeTrack)
            }.associateBy(Track::trackId)
            chunk.mapNotNullTo(resolved, byId::get)
            onProgress(resolved.size, ids.size)
        }
        return resolved
    }

    fun recommendationSeeds(token: String): List<Track> {
        val uid = ownUid(token)
        val library = result(Http.getString("$API/users/$uid/likes/tracks", token))
            .optJSONObject("library")?.optJSONArray("tracks") ?: JSONArray()
        val ids = (0 until library.length()).mapNotNull { i ->
            library.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() }
        }.take(8)
        if (ids.isEmpty()) return emptyList()
        val body = "track-ids=${URLEncoder.encode(ids.joinToString(","), "UTF-8")}"
        val items = JSONObject(Http.postString("$API/tracks", body, token)).optJSONArray("result") ?: JSONArray()
        return (0 until items.length()).mapNotNull { i ->
            items.optJSONObject(i)?.takeIf { it.optBoolean("available", true) }?.let(::shapeTrack)
        }
    }

    fun playlistTracks(item: UserPlaylist, token: String): List<Track> =
        YandexMusic.fetchPlaylistTracks(YmLink.PlaylistLink(item.owner, item.kind, null), token)

    fun similarTracks(seed: Track, token: String): List<Track> {
        val id = seed.trackId.toLongOrNull() ?: return emptyList()
        val album = seed.albumId.toLongOrNull()
        val key = if (album != null) "$id:$album" else "$id"
        val items = result(Http.getString("$API/tracks/$key/similar", token)).optJSONArray("similarTracks")
            ?: return emptyList()
        return (0 until items.length()).mapNotNull { i -> items.optJSONObject(i)?.let(::shapeTrack) }
    }

    /** Explicit, one-time export; later local edits do not update the remote copy. */
    fun createPlaylist(title: String, tracks: List<Track>, token: String): UserPlaylist {
        require(title.isNotBlank()) { "Введите название плейлиста" }
        require(tracks.isNotEmpty()) { "Подборка пуста" }
        val uid = ownUid(token)
        val body = "title=${URLEncoder.encode(title.trim(), "UTF-8")}&visibility=private"
        val created = result(Http.postString("$API/users/$uid/playlists/create", body, token))
        val kind = created.optString("kind").takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Плейлист создан, но его ID не получен. Проверьте аккаунт перед повтором")
        val item = UserPlaylist(uid, kind, title.trim(), 0)
        try { addPlaylistTracks(item, tracks, token) }
        catch (e: Exception) {
            throw IllegalStateException("Плейлист создан, но треки не добавлены. Проверьте аккаунт перед повтором: ${e.message ?: "ошибка"}", e)
        }
        return item.copy(count = tracks.size)
    }

    fun renamePlaylist(item: UserPlaylist, title: String, token: String) {
        val value = URLEncoder.encode(title.trim(), "UTF-8")
        Http.postString("$API/users/${item.owner}/playlists/${item.kind}/name", "value=$value", token)
    }

    fun deletePlaylist(item: UserPlaylist, token: String) {
        val uid = ownUid(token)
        require(item.owner == uid) { "Можно удалить только свой плейлист" }
        Http.postString("$API/users/$uid/playlists/${item.kind}/delete", "", token)
    }

    fun removePlaylistTrack(item: UserPlaylist, position: Int, displayed: List<Track>, token: String) {
        val current = result(Http.getString("$API/users/${item.owner}/playlists/${item.kind}", token))
        val revision = current.optInt("revision", -1)
        if (revision < 0) throw IllegalStateException("Не получена версия плейлиста")
        val raw = current.optJSONArray("tracks")
            ?: throw IllegalStateException("Не удалось проверить состав плейлиста")
        val checkedPosition = PlaylistRemovalGuard.positionIfUnchanged(displayed, raw, position)
        val diff = JSONArray().put(JSONObject().put("op", "delete").put("from", checkedPosition).put("to", checkedPosition + 1))
        val body = "kind=${URLEncoder.encode(item.kind, "UTF-8")}&revision=$revision&diff=${URLEncoder.encode(diff.toString(), "UTF-8")}"
        Http.postString("$API/users/${item.owner}/playlists/${item.kind}/change", body, token)
    }

    fun addPlaylistTrack(item: UserPlaylist, track: Track, token: String) =
        addPlaylistTracks(item, listOf(track), token)

    fun addPlaylistTracks(item: UserPlaylist, tracks: List<Track>, token: String) {
        if (tracks.isEmpty()) return
        if (tracks.any { it.albumId.isBlank() }) throw IllegalArgumentException("У одного из треков нет ID альбома")
        val current = result(Http.getString("$API/users/${item.owner}/playlists/${item.kind}", token))
        val revision = current.optInt("revision", -1)
        if (revision < 0) throw IllegalStateException("Не получена версия плейлиста")
        val at = current.optJSONArray("tracks")?.length() ?: 0
        val trackIds = JSONArray()
        tracks.forEach { track -> trackIds.put(JSONObject().put("id", track.trackId).put("albumId", track.albumId)) }
        val diff = JSONArray().put(JSONObject().put("op", "insert").put("at", at).put("tracks", trackIds))
        val body = "kind=${URLEncoder.encode(item.kind, "UTF-8")}&revision=$revision&diff=${URLEncoder.encode(diff.toString(), "UTF-8")}"
        Http.postString("$API/users/${item.owner}/playlists/${item.kind}/change", body, token)
    }

    fun searchArtists(query: String, token: String): List<Artist> {
        val url = "$API/search?text=${URLEncoder.encode(query, "UTF-8")}&type=artist&page=0"
        val results = JSONObject(Http.getString(url, token)).optJSONObject("result")
            ?.optJSONObject("artists")?.optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { index ->
            val item = results.optJSONObject(index) ?: return@mapNotNull null
            Artist(item.optString("id"), item.optString("name"))
                .takeIf { it.id.isNotBlank() && it.name.isNotBlank() }
        }
    }

    fun artistTracks(artistId: String, token: String): List<Track> {
        val id = artistId.toLongOrNull() ?: throw IllegalArgumentException("Некорректный исполнитель")
        val results = JSONObject(Http.getString("$API/artists/$id/tracks?page=0&page-size=100", token))
            .optJSONObject("result")?.optJSONArray("tracks") ?: return emptyList()
        return (0 until results.length()).mapNotNull { index ->
            results.optJSONObject(index)?.let(::shapeTrack)
        }
    }

    private fun shapeTrack(item: JSONObject): Track? {
        val artistItems = item.optJSONArray("artists")
        val artists = (0 until (artistItems?.length() ?: 0)).mapNotNull { i ->
            artistItems?.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }
        }.joinToString(", ")
        val album = item.optJSONArray("albums")?.optJSONObject(0)
        return Track(
            trackId = item.optString("id"),
            albumId = album?.optString("id") ?: "",
            title = item.optString("title"),
            artist = artists,
            album = album?.optString("title") ?: "",
            coverUri = item.optString("coverUri")
        ).takeIf { it.trackId.isNotBlank() }
    }

    fun searchTracks(query: String, token: String): List<Track> {
        val url = "$API/search?text=${URLEncoder.encode(query, "UTF-8")}&type=track&page=0"
        val root = JSONObject(Http.getString(url, token))
        val results = root.optJSONObject("result")?.optJSONObject("tracks")?.optJSONArray("results")
            ?: return emptyList()
        return (0 until results.length()).mapNotNull { index ->
            results.optJSONObject(index)?.let(::shapeTrack)
        }
    }

    fun playlistFromUrl(url: String, token: String): List<Track> {
        val link = YandexMusic.parseUrl(url)
            ?: throw IllegalArgumentException("Вставьте ссылку на плейлист, альбом или трек Яндекс Музыки")
        return YandexMusic.listTracks(link, token)
    }
}
