package ru.rikko.music

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class BrowseGroup(val key: String, val title: String, val subtitle: String, val songs: List<LocalSong>) {
    val durationMs: Long get() = songs.sumOf(LocalSong::durationMs)
}

object LibraryBrowse {
    const val ALBUMS = 4
    const val ARTISTS = 5
    const val GENRES = 6
    const val FAVORITES = 7

    fun groups(songs: List<LocalSong>, mode: Int): List<BrowseGroup> {
        val groups = when (mode) {
            ALBUMS -> songs.groupBy { it.album.ifBlank { "Без альбома" } + "\u001f" + it.artist.ifBlank { "Неизвестный исполнитель" } }
            ARTISTS -> songs.groupBy { it.artist.ifBlank { "Неизвестный исполнитель" } }
            GENRES -> songs.groupBy { it.genre.ifBlank { "Без жанра" } }
            else -> emptyMap()
        }
        return groups.map { (key, tracks) ->
            when (mode) {
                ALBUMS -> BrowseGroup(key, key.substringBefore('\u001f'), key.substringAfter('\u001f'), tracks)
                else -> BrowseGroup(key, key, "", tracks)
            }
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
    }

    fun duration(milliseconds: Long): String {
        val seconds = milliseconds.coerceAtLeast(0) / 1000
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds % 60)
            else "%d:%02d".format(minutes, seconds % 60)
    }
}

object LocalArtwork {
    private val cache = LruCache<String, Bitmap>(64)

    suspend fun get(context: Context, song: LocalSong): Bitmap? = withContext(Dispatchers.IO) {
        val key = song.uri.toString()
        cache.get(key)?.let { return@withContext it }
        val image = try {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, song.uri)
                retriever.embeddedPicture?.let { bytes ->
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                        BitmapFactory.Options().apply { inSampleSize = 4 })
                }
            } finally { retriever.release() }
        } catch (_: Exception) { null }
        if (image != null) cache.put(key, image)
        image
    }
}
