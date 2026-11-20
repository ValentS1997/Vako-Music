package ru.rikko.music

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore

data class MediaEdit(val uri: Uri, val name: String? = null, val path: String? = null, val title: String? = null) {
    fun values(): ContentValues = ContentValues().apply {
        name?.let { put(MediaStore.MediaColumns.DISPLAY_NAME, it) }
        path?.let { put(MediaStore.MediaColumns.RELATIVE_PATH, it) }
        title?.let { put(MediaStore.Audio.Media.TITLE, it) }
    }
}

object MediaEdits {
    /** Проверяет назначения всего набора до первого изменения MediaStore. */
    fun validatePlan(resolver: ContentResolver, edits: List<MediaEdit>): String? {
        val targets = mutableSetOf<String>()
        val sources = edits.map { it.uri.toString() }.toSet()
        for (edit in edits) {
            val current = try {
                resolver.query(edit.uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)?.use { cursor ->
                    if (!cursor.moveToFirst()) null else
                        cursor.getString(0) to cursor.getString(1)
                }
            } catch (_: Exception) { null } ?: return "Файл больше не доступен: ${edit.uri.lastPathSegment}"
            val name = edit.name ?: current.first
            val path = edit.path ?: current.second
            if (name.isNullOrBlank() || path.isNullOrBlank() ||
                path.split('/').any { it == ".." } || '/' in name || '\\' in name
            ) return "Некорректное имя или папка назначения"
            val volume = try { MediaStore.getVolumeName(edit.uri) }
                catch (_: Exception) { return "Не удалось определить накопитель" }
            val key = "$volume/${path.trim('/').lowercase()}/${name.lowercase()}"
            if (!targets.add(key)) return "Несколько файлов попадут в одно место: $name"
            val collection = MediaStore.Audio.Media.getContentUri(volume)
            val conflict = try {
                resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                    arrayOf(name, path), null)?.use { cursor ->
                    var found = false
                    while (cursor.moveToNext()) {
                        val uri = android.content.ContentUris.withAppendedId(collection, cursor.getLong(0))
                        if (uri.toString() !in sources) { found = true; break }
                    }
                    found
                } ?: return "Не удалось проверить папку назначения"
            } catch (_: Exception) { return "Не удалось проверить папку назначения" }
            if (conflict) return "В папке уже есть файл $name"
        }
        return null
    }

    fun isMedia(song: LocalSong): Boolean = song.uri.authority == MediaStore.AUTHORITY

    fun displayName(resolver: ContentResolver, song: LocalSong): String =
        resolver.query(song.uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: song.title

    fun renameFile(resolver: ContentResolver, song: LocalSong, newBaseName: String): MediaEdit {
        val base = validName(newBaseName)
        val old = displayName(resolver, song)
        val suffix = old.substringAfterLast('.', "").takeIf { it.isNotBlank() }?.let { ".$it" }.orEmpty()
        return MediaEdit(song.uri, name = if (base.endsWith(suffix, true)) base else base + suffix,
            title = base.removeSuffix(suffix))
    }

    fun moveFile(song: LocalSong, targetFolder: FolderNode): MediaEdit {
        require(isMedia(song)) { "Для этого файла перемещение недоступно" }
        require(song.volume == targetFolder.key.substringBefore('/')) { "Выберите папку на том же накопителе" }
        return MediaEdit(song.uri, path = relativePath(targetFolder.key))
    }

    fun renameFolder(folder: FolderNode, newName: String): List<MediaEdit> {
        require('/' in folder.key) { "Накопитель переименовать нельзя" }
        val clean = validName(newName)
        require(clean != folder.label) { "Введите другое название" }
        val oldPath = relativePath(folder.key).trimEnd('/')
        val newPath = relativePath(folder.key.substringBeforeLast('/') + "/" + clean)
        return folder.allSongs().map { song ->
            require(isMedia(song)) { "В папке есть файлы без доступа MediaStore" }
            require(song.folder == oldPath || song.folder.startsWith("$oldPath/")) { "Путь файла изменился; обновите медиатеку" }
            val tail = song.folder.removePrefix(oldPath).trim('/')
            MediaEdit(song.uri, path = newPath + if (tail.isEmpty()) "" else "$tail/")
        }
    }

    fun moveFolder(folder: FolderNode, target: FolderNode): List<MediaEdit> {
        require('/' in folder.key) { "Накопитель переместить нельзя" }
        require(folder.key.substringBefore('/') == target.key.substringBefore('/')) { "Выберите тот же накопитель" }
        require(target.key != folder.key && !target.key.startsWith(folder.key + "/")) { "Нельзя перенести папку внутрь самой себя" }
        require(folder.label !in target.children) { "В целевой папке уже есть папка с таким названием" }
        val oldPath = relativePath(folder.key).trimEnd('/')
        val newPath = relativePath(target.key) + folder.label + "/"
        return folder.allSongs().map { song ->
            require(isMedia(song)) { "В папке есть файлы без доступа MediaStore" }
            require(song.folder == oldPath || song.folder.startsWith("$oldPath/")) { "Путь файла изменился; обновите медиатеку" }
            val tail = song.folder.removePrefix(oldPath).trim('/')
            MediaEdit(song.uri, path = newPath + if (tail.isEmpty()) "" else "$tail/")
        }
    }

    fun relativePath(folderKey: String): String = folderKey.substringAfter('/', "").let { if (it.isBlank()) "Music/" else "$it/" }

    private fun validName(value: String): String {
        val clean = value.trim()
        require(clean.isNotBlank() && clean !in listOf(".", "..") && clean.none { it in "/\\\u0000" }) {
            "Введите название без символов / и \\"
        }
        return clean
    }
}
