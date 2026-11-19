package ru.rikko.music

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

data class VideoEdit(val uri: Uri, val name: String? = null, val path: String? = null) {
    fun values(): ContentValues = ContentValues().apply {
        name?.let { put(MediaStore.MediaColumns.DISPLAY_NAME, it) }
        path?.let { put(MediaStore.MediaColumns.RELATIVE_PATH, it) }
    }
}

object VideoMediaManager {
    data class EditReport(val changed: Int, val failed: List<VideoEdit>)
    data class CopyReport(val copied: Int, val failed: List<LocalVideo>)

    fun applyEdits(context: Context, edits: List<VideoEdit>): EditReport {
        val report = BatchOperations.run(edits) { edit ->
            context.contentResolver.update(edit.uri, edit.values(), null, null) > 0
        }
        return EditReport(report.succeeded.size, report.failed.map { it.item })
    }

    fun rename(video: LocalVideo, value: String): VideoEdit {
        val clean = validName(value)
        val extension = video.name.substringAfterLast('.', "").takeIf { it.isNotEmpty() }?.let { ".$it" }.orEmpty()
        return VideoEdit(video.uri, name = if (clean.endsWith(extension, true)) clean else clean + extension)
    }

    fun move(videos: List<LocalVideo>, volume: String, path: String): List<VideoEdit> {
        require(videos.all { it.volume == volume }) { "Перенос возможен только на том же накопителе. Для другого используйте копирование." }
        val target = path.trim('/') + "/"
        return videos.map { VideoEdit(it.uri, path = target) }
    }

    fun renameFolder(videos: List<LocalVideo>, volume: String, oldPath: String, newName: String): List<VideoEdit> {
        val clean = validName(newName)
        val parent = oldPath.substringBeforeLast('/', "")
        val destination = if (parent.isEmpty()) clean else "$parent/$clean"
        require(destination != oldPath) { "Введите другое название" }
        require(videos.none { it.volume == volume && (it.path == destination || it.path.startsWith("$destination/")) }) {
            "Папка с таким названием уже существует"
        }
        return videos.filter { it.volume == volume && (it.path == oldPath || it.path.startsWith("$oldPath/")) }
            .map { VideoEdit(it.uri, path = destination + it.path.removePrefix(oldPath) + "/") }
    }

    fun moveFolder(videos: List<LocalVideo>, volume: String, oldPath: String, destination: String): List<VideoEdit> {
        require(destination != oldPath && !destination.startsWith("$oldPath/")) { "Нельзя переместить папку внутрь самой себя" }
        val name = oldPath.substringAfterLast('/')
        val target = (destination.trim('/') + "/" + name).trim('/')
        require(target != oldPath) { "Выберите другую папку" }
        require(videos.none { it.volume == volume && (it.path == target || it.path.startsWith("$target/")) }) {
            "В папке назначения уже есть папка с таким названием"
        }
        return videos.filter { it.volume == volume && (it.path == oldPath || it.path.startsWith("$oldPath/")) }
            .map { VideoEdit(it.uri, path = target + it.path.removePrefix(oldPath) + "/") }
    }

    fun copy(context: Context, videos: List<LocalVideo>, volume: String, path: String): CopyReport {
        var count = 0
        val failed = mutableListOf<LocalVideo>()
        for (video in videos) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, video.name)
                put(MediaStore.MediaColumns.MIME_TYPE, context.contentResolver.getType(video.uri) ?: "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, path.trim('/') + "/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val destination = try {
                context.contentResolver.insert(MediaStore.Video.Media.getContentUri(volume), values)
            } catch (_: Exception) { null }
            if (destination == null) {
                failed += video
                continue
            }
            try {
                context.contentResolver.openInputStream(video.uri).use { input ->
                    requireNotNull(input) { "Не удалось открыть ${video.name}" }
                    context.contentResolver.openOutputStream(destination).use { output ->
                        requireNotNull(output) { "Не удалось создать копию ${video.name}" }
                        input.copyTo(output)
                    }
                }
                if (context.contentResolver.update(destination,
                        ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) <= 0
                ) throw IllegalStateException("Не удалось завершить сохранение видео")
                count++
            } catch (error: Exception) {
                try { context.contentResolver.delete(destination, null, null) }
                catch (_: Exception) { }
                failed += video
            }
        }
        return CopyReport(count, failed)
    }

    private fun validName(value: String): String = value.trim().also {
        require(it.isNotEmpty() && it !in listOf(".", "..") && it.none { character -> character in "/\\\u0000" }) {
            "Введите название без символов / и \\"
        }
    }
}
