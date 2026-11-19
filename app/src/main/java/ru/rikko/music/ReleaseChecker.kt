package ru.rikko.music

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class VakoRelease(val version: String, val pageUrl: String)

/** GitHub's latest published stable release; no APK is installed automatically. */
object ReleaseChecker {
    private const val ENDPOINT = "https://api.github.com/repos/ValentS1997/Vako-Music/releases/latest"

    fun latest(): VakoRelease? {
        val connection = URL(ENDPOINT).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "Vako-Music-Android")
        try {
            if (connection.responseCode == 404) return null
            if (connection.responseCode != 200) error("GitHub: HTTP ${connection.responseCode}")
            val body = connection.inputStream.bufferedReader().use { reader ->
                val buffer = CharArray(4096)
                val text = StringBuilder()
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    require(text.length + count <= 64 * 1024) { "Ответ GitHub слишком большой" }
                    text.append(buffer, 0, count)
                }
                text.toString()
            }
            val data = JSONObject(body)
            val version = data.getString("tag_name")
            val pageUrl = data.getString("html_url")
            require(pageUrl.startsWith("https://github.com/ValentS1997/Vako-Music/releases/"))
            return VakoRelease(version, pageUrl)
        } finally { connection.disconnect() }
    }

    fun isNewer(latest: String, current: String): Boolean {
        fun parts(value: String): List<Int>? {
            val clean = value.removePrefix("v").substringBefore('-')
            return clean.split('.').map { it.toIntOrNull() ?: return null }
        }
        val left = parts(latest) ?: return false
        val right = parts(current) ?: return false
        for (index in 0 until maxOf(left.size, right.size)) {
            val difference = left.getOrElse(index) { 0 }.compareTo(right.getOrElse(index) { 0 })
            if (difference != 0) return difference > 0
        }
        return false
    }
}
