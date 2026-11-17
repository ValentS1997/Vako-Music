package ru.rikko.music

/** Tree of indexed audio paths. Each node contains only files directly in that directory. */
class FolderNode(val key: String, val label: String) {
    val children = linkedMapOf<String, FolderNode>()
    val songs = mutableListOf<LocalSong>()
    var totalTracks: Int = 0
        private set
    var totalDurationMs: Long = 0
        private set

    fun add(song: LocalSong, parts: List<String>) {
        totalTracks++
        totalDurationMs += song.durationMs
        if (parts.isEmpty()) {
            songs.add(song)
            return
        }
        val part = parts.first()
        children.getOrPut(part) {
            FolderNode(if (key.isEmpty()) part else "$key/$part", part)
        }.add(song, parts.drop(1))
    }

    fun allSongs(): List<LocalSong> = songs + children.values.flatMap(FolderNode::allSongs)

    fun find(path: String): FolderNode? {
        if (path.isEmpty()) return this
        var node = this
        for (part in path.split('/')) node = node.children[part] ?: return null
        return node
    }
}

fun buildFolderTree(songs: List<LocalSong>): FolderNode {
    val root = FolderNode("", "Накопители")
    for (song in songs) {
        val volume = song.volume.ifBlank { "selected" }
        val folderParts = song.folder.split('/').filter(String::isNotBlank)
        root.add(song, listOf(volume) + folderParts)
    }
    return root
}

fun volumeLabel(volume: String): String = when (volume) {
    "external_primary", "external" -> "Память телефона"
    "selected" -> "Добавленные файлы"
    else -> "Карта памяти · $volume"
}
