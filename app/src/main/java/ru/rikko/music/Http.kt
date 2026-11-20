package ru.rikko.music

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Минимальный HTTP-клиент на HttpURLConnection — без сторонних библиотек. */
object Http {

    private const val UA = "Mozilla/5.0 (Linux; Android 10) MusicDownloader/1.0"

    /** Потоковая загрузка аудио в закрытый временный файл приложения. */
    fun downloadToFile(
        url: String,
        destination: File,
        onProgress: ((Long, Long) -> Unit)? = null,
        maxBytes: Long = 512L * 1024 * 1024,
    ) {
        var current = url
        repeat(6) {
            val connection = open(current, null)
            connection.instanceFollowRedirects = false
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?: throw IOException("Перенаправление без адреса")
                    current = URL(URL(current), location).toString()
                } else {
                    if (code != HttpURLConnection.HTTP_OK) throw HttpError(code, "")
                    val total = connection.contentLengthLong
                    if (total > maxBytes) throw IOException("Аудиофайл превышает лимит 512 МБ")
                    var read = 0L
                    connection.inputStream.use { input ->
                        destination.outputStream().buffered(64 * 1024).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                if (Thread.currentThread().isInterrupted)
                                    throw java.io.InterruptedIOException("Загрузка отменена")
                                val count = input.read(buffer)
                                if (count < 0) break
                                read += count
                                if (read > maxBytes) throw IOException("Аудиофайл превышает лимит 512 МБ")
                                output.write(buffer, 0, count)
                                onProgress?.invoke(read, total)
                            }
                        }
                    }
                    if (total >= 0 && read != total)
                        throw java.io.EOFException("Соединение закрыто до окончания загрузки")
                    return
                }
            } finally { connection.disconnect() }
        }
        throw IOException("Слишком много перенаправлений при скачивании")
    }

    private fun open(url: String, token: String?, method: String = "GET"): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 30_000
        c.readTimeout = 120_000
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept", "application/json")
        // Мимикаем мобильное приложение — иначе API не отдаёт lossless
        c.setRequestProperty("X-Yandex-Music-Client", "YandexMusicAndroid/24023621")
        if (!token.isNullOrBlank()) c.setRequestProperty("Authorization", "OAuth $token")
        return c
    }

    fun getString(url: String, token: String? = null): String {
        val c = open(url, token)
        try {
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw HttpError(code, body)
            return body
        } finally { c.disconnect() }
    }

    fun postString(url: String, body: String, token: String? = null): String {
        val c = open(url, token, "POST")
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        try {
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val resp = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw HttpError(code, resp)
            return resp
        } finally { c.disconnect() }
    }

    /**
     * Скачивание в память с прогрессом (нужно для расшифровки и тегов).
     *
     * Редиректы проходим вручную: HttpURLConnection не следует за ними при
     * смене протокола, а Яндекс отвечает 308 на часть треков и уводит на другой
     * CDN-узел — без этого файл сохранялся пустым (см. обсуждение #51).
     */
    fun getBytes(
        url: String,
        onProgress: ((Long, Long) -> Unit)? = null,
        maxBytes: Int = 96 * 1024 * 1024,
    ): ByteArray {
        require(maxBytes > 0)
        var current = url
        var hops = 0
        while (true) {
            val probe = open(current, null)
            probe.instanceFollowRedirects = false
            val code = probe.responseCode
            if (code !in 300..399) { probe.disconnect(); break }
            val location = probe.getHeaderField("Location")
            probe.disconnect()
            if (location.isNullOrBlank()) break
            current = java.net.URL(java.net.URL(current), location).toString()
            if (++hops > 5) throw Exception("Слишком много перенаправлений при скачивании")
        }

        val c = open(current, null)
        try {
            val code = c.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw HttpError(code, "")
            val total = c.contentLengthLong
            if (total > maxBytes) throw DownloadSizeException(maxBytes)
            if (total in 1..Int.MAX_VALUE) {
                // Сразу массив нужного размера: toByteArray() у ByteArrayOutputStream
                // сделал бы вторую копию трека, а FLAC бывает по 40–60 МБ (issue #76)
                val data = ByteArray(total.toInt())
                var read = 0
                c.inputStream.use { input ->
                    while (read < data.size) {
                        val n = input.read(data, read, minOf(64 * 1024, data.size - read))
                        if (n < 0) throw java.io.EOFException("Соединение закрыто до окончания загрузки")
                        if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Загрузка отменена")
                        read += n
                        onProgress?.invoke(read.toLong(), total)
                    }
                }
                return data
            }
            val out = ByteArrayOutputStream(1 shl 20)
            val buf = ByteArray(64 * 1024)
            var read: Long = 0
            c.inputStream.use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (read + n > maxBytes) throw DownloadSizeException(maxBytes)
                    if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Загрузка отменена")
                    out.write(buf, 0, n)
                    read += n
                    onProgress?.invoke(read, total)
                }
            }
            return out.toByteArray()
        } finally { c.disconnect() }
    }

    class DownloadSizeException(maxBytes: Int) :
        java.io.IOException("Файл больше допустимого размера: " + maxBytes / 1024 / 1024 + " МБ")

    class HttpError(val code: Int, val body: String) :
        Exception("HTTP $code ${body.take(200)}")
}
