package ru.rikko.music

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

data class LocalVideo(
    val uri: Uri,
    val name: String,
    val volume: String,
    val path: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val addedSeconds: Long,
    val width: Int,
    val height: Int,
    val modifiedSeconds: Long = 0
)

object VideoStore {
    fun scan(context: Context): List<LocalVideo> {
        val columns = arrayOf(
            MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.RELATIVE_PATH, MediaStore.Video.Media.VOLUME_NAME,
            MediaStore.Video.Media.DURATION, MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT, MediaStore.Video.Media.DATE_MODIFIED
        )
        val videos = mutableListOf<LocalVideo>()
        context.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, columns, null, null, null)?.use { cursor ->
            val indices = columns.map(cursor::getColumnIndexOrThrow)
            while (cursor.moveToNext()) {
                fun string(i: Int) = cursor.getString(indices[i]).orEmpty()
                fun long(i: Int) = if (cursor.isNull(indices[i])) 0L else cursor.getLong(indices[i])
                fun int(i: Int) = if (cursor.isNull(indices[i])) 0 else cursor.getInt(indices[i])
                videos += LocalVideo(
                    ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, long(0)),
                    string(1), string(3), string(2).trim('/'), long(4), long(5), long(6), int(7), int(8), long(9)
                )
            }
        }
        return videos
    }

    fun formatDuration(milliseconds: Long): String = LibraryBrowse.duration(milliseconds)
}
