package ru.rikko.music

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class HttpRedirectTest {
    private val defaultFactory: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }

    @After fun resetFactory() { Http.connectionFactory = defaultFactory }

    private class Reply(url: URL, private val status: Int, private val location: String? = null) :
        HttpURLConnection(url) {
        override fun connect() { connected = true }
        override fun disconnect() { connected = false }
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getHeaderField(name: String?) = if (name.equals("Location", true)) location else null
        override fun getContentLengthLong() = if (status == 200) 3L else -1L
        override fun getInputStream() = ByteArrayInputStream(byteArrayOf(1, 2, 3))
    }

    private fun stub(routes: Map<String, Pair<Int, String?>>): MutableList<String> {
        val visited = mutableListOf<String>()
        Http.connectionFactory = { url ->
            visited += url.toString()
            val (status, location) = routes[url.toString()] ?: error("Unexpected URL: $url")
            Reply(url, status, location)
        }
        return visited
    }

    @Test fun initialHttpIsRejectedBeforeConnectingByBothMethods() {
        val visited = stub(emptyMap())
        assertThrows(IOException::class.java) { Http.getBytes("http://example.test/audio") }
        val destination = File.createTempFile("media-url", ".part")
        try {
            assertThrows(IOException::class.java) { Http.downloadToFile("http://example.test/audio", destination) }
        } finally { destination.delete() }
        assertTrue(visited.isEmpty())
    }

    @Test fun downgradeIsRejectedByBothMethods() {
        val routes = mapOf("https://example.test/audio" to (302 to "http://example.test/audio"))
        val visited = stub(routes)
        assertThrows(IOException::class.java) { Http.getBytes("https://example.test/audio") }
        val destination = File.createTempFile("media-url", ".part")
        try {
            assertThrows(IOException::class.java) { Http.downloadToFile("https://example.test/audio", destination) }
        } finally { destination.delete() }
        assertEquals(listOf("https://example.test/audio", "https://example.test/audio"), visited)
    }

    @Test fun relativeHttpsRedirectWorksForBothMethods() {
        val routes = mapOf(
            "https://example.test/audio" to (308 to "/cdn/audio"),
            "https://example.test/cdn/audio" to (200 to null),
        )
        val visited = stub(routes)
        assertArrayEquals(byteArrayOf(1, 2, 3), Http.getBytes("https://example.test/audio"))
        val destination = File.createTempFile("media-url", ".part")
        try {
            Http.downloadToFile("https://example.test/audio", destination)
            assertArrayEquals(byteArrayOf(1, 2, 3), destination.readBytes())
        } finally { destination.delete() }
        assertEquals(4, visited.size)
    }

    @Test fun absoluteHttpsRedirectWorksWithoutOAuthHeader() {
        val replies = mutableListOf<Reply>()
        Http.connectionFactory = { url ->
            Reply(url, if (url.host == "example.test") 302 else 200,
                if (url.host == "example.test") "https://cdn.example.test/audio" else null)
                .also(replies::add)
        }
        assertArrayEquals(byteArrayOf(1, 2, 3), Http.getBytes("https://example.test/audio"))
        val destination = File.createTempFile("media-url", ".part")
        try {
            Http.downloadToFile("https://example.test/audio", destination)
            assertArrayEquals(byteArrayOf(1, 2, 3), destination.readBytes())
        } finally { destination.delete() }
        replies.forEach { assertNull(it.getRequestProperty("Authorization")) }
    }

    @Test fun redirectHopLimitAppliesToBothMethods() {
        val routes = (0..6).associate { n ->
            "https://example.test/$n" to (302 to "/${n + 1}")
        }
        stub(routes)
        assertThrows(IOException::class.java) { Http.getBytes("https://example.test/0") }
        val destination = File.createTempFile("media-url", ".part")
        try {
            assertThrows(IOException::class.java) { Http.downloadToFile("https://example.test/0", destination) }
        } finally { destination.delete() }
    }
}
