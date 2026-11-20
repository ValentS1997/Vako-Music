package ru.rikko.music

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.net.Uri
import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream

/**
 * Скачивание трека: получить ссылку, расшифровать при необходимости,
 * переупаковать FLAC, вшить теги и сохранить в общую папку «Музыка».
 */
class Downloader(private val context: Context) {

    data class Progress(
        val current: Int,
        val total: Int,
        val title: String,
        val bytes: Long = 0,
        val totalBytes: Long = 0,
    )

    data class Result(val fileName: String, val format: String, val uri: Uri? = null)

    /**
     * @param wantNativeFlac переупаковывать FLAC-в-MP4 в настоящий .flac
     * @param wantLyrics вшивать текст песни
     */
    fun downloadTrack(
        track: Track,
        token: String,
        quality: Quality,
        wantNativeFlac: Boolean,
        wantLyrics: Boolean,
        onBytes: ((Long, Long) -> Unit)? = null,
    ): Result {
        val source = YandexMusic.getAudioSource(track, quality, token)
        if (wantLyrics && track.lyrics.isBlank()) {
            track.lyrics = YandexMusic.fetchLyrics(track.trackId, token)
        }
        return save(source, track, wantNativeFlac, onBytes)
    }

    /** Скачивание и обработка через временный файл без полной копии трека в heap. */
    fun save(
        source: AudioSource,
        track: Track,
        wantNativeFlac: Boolean,
        onBytes: ((Long, Long) -> Unit)? = null,
    ): Result {
        val temporary = File.createTempFile("vako-audio-", ".part", context.cacheDir)
        try {
            Http.downloadToFile(source.url, temporary, onBytes)
            if (temporary.length() < 1024) throw Exception("Аудиофайл пустой или неполный")
            source.encryptionKey?.let { Crypto.decryptAesCtrFileInPlace(temporary, it) }
            val header = temporary.inputStream().use { input ->
                ByteArray(16).also { input.read(it) }
            }
            val cover = try {
                YandexMusic.coverUrl(track.coverUri)?.let {
                    Http.getBytes(it, maxBytes = 4 * 1024 * 1024)
                }
            } catch (_: Exception) { null }
            val name = track.baseName()
            return when {
                FlacRemux.isNativeFlac(header) ->
                    saveToMusic("$name.flac", "audio/flac") { out -> temporary.inputStream().use { it.copyTo(out) } }
                FlacRemux.isMp4(header) -> {
                    val plan = if (wantNativeFlac) {
                        try { FlacRemux.plan(temporary) } catch (_: FlacRemux.NotFlacMp4) { null }
                    } else null
                    if (plan != null)
                        saveToMusic("$name.flac", "audio/flac") {
                            FlacRemux.write(temporary, plan, track, cover, it)
                        }
                    else {
                        if (source.codec.equals("flac", ignoreCase = true))
                            throw Exception("Полученный FLAC в MP4 повреждён или не поддерживается")
                        saveToMusic("$name.m4a", "audio/mp4") { out ->
                            temporary.inputStream().use { it.copyTo(out) }
                        }
                    }
                }
                isMp3(header) ->
                    saveToMusic("$name.mp3", "audio/mpeg") {
                        Id3.writeTagged(temporary, track, cover, it)
                    }
                else -> throw Exception("Получен неподдерживаемый аудиоформат")
            }
        } finally {
            temporary.delete()
        }
    }

    private fun isMp3(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        if (bytes[0] == 'I'.code.toByte() && bytes[1] == 'D'.code.toByte() &&
            bytes[2] == '3'.code.toByte()) return true
        return (bytes[0].toInt() and 0xff) == 0xff &&
            (bytes[1].toInt() and 0xe0) == 0xe0
    }

    /** Сохранение в общую папку «Музыка/MusicDownloader». */
    private fun saveToMusic(fileName: String, mime: String, write: (OutputStream) -> Unit): Result {
        val subDir = "MusicDownloader"
        var savedUri: Uri? = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_MUSIC + File.separator + subDir
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw Exception("Не удалось создать файл в папке «Музыка»")
            savedUri = uri
            try {
                val out = resolver.openOutputStream(uri) ?: throw Exception("Не удалось записать файл")
                BufferedOutputStream(out, 256 * 1024).use(write)
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                if (resolver.update(uri, values, null, null) != 1)
                    throw Exception("Не удалось завершить сохранение файла")
            } catch (e: Throwable) {
                // Ошибка записи и ошибка публикации одинаково требуют очистки.
                try { resolver.delete(uri, null, null) } catch (cleanup: Exception) {
                    e.addSuppressed(cleanup)
                }
                throw e
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                subDir
            )
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, fileName)
            try {
                BufferedOutputStream(file.outputStream(), 256 * 1024).use(write)
            } catch (e: Throwable) {
                file.delete()
                throw e
            }
        }
        return Result(fileName, fileName.substringAfterLast('.'), savedUri)
    }
}
