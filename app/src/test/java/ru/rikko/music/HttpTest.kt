package ru.rikko.music

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import java.io.File

class HttpTest {
    private fun respond(
        headers: String,
        body: ByteArray,
        requests: Int = 2,
        status: String = "200 OK",
        test: (String) -> Unit,
    ) {
        ServerSocket(0).use { server ->
            val error = AtomicReference<Throwable?>()
            val worker = Thread {
                try {
                    repeat(requests) {
                        server.accept().use { socket ->
                            val reader = socket.getInputStream().bufferedReader()
                            while (!reader.readLine().isNullOrEmpty()) { }
                            socket.getOutputStream().apply {
                                write(("HTTP/1.1 $status\r\n" + headers + "\r\n\r\n").toByteArray())
                                write(body)
                                flush()
                            }
                        }
                    }
                } catch (e: Throwable) { error.set(e) }
            }
            worker.start()
            test("http://127.0.0.1:${server.localPort}/audio")
            worker.join(3000)
            error.get()?.let { throw AssertionError(it) }
        }
    }

    @Test fun completeResponseIsReturned() {
        val body = byteArrayOf(1, 2, 3, 4)
        respond("Content-Length: 4", body) { url ->
            assertArrayEquals(body, Http.getBytes(url, maxBytes = 4))
        }
    }

    @Test fun truncatedResponseFails() {
        respond("Content-Length: 8", byteArrayOf(1, 2)) { url ->
            assertThrows(java.io.EOFException::class.java) { Http.getBytes(url, maxBytes = 8) }
        }
    }

    @Test fun oversizedResponseFails() {
        respond("Content-Length: 8", byteArrayOf(1, 2)) { url ->
            assertThrows(Http.DownloadSizeException::class.java) { Http.getBytes(url, maxBytes = 4) }
        }
    }

    @Test fun streamedDownloadRejectsTruncation() {
        val file = File.createTempFile("http-download", ".part")
        try {
            respond("Content-Length: 8", byteArrayOf(1, 2), requests = 1) { url ->
                assertThrows(java.io.EOFException::class.java) {
                    Http.downloadToFile(url, file, maxBytes = 8)
                }
            }
        } finally { file.delete() }
    }

    @Test fun streamedDownloadRejectsOversizedHeader() {
        val file = File.createTempFile("http-download", ".part")
        try {
            respond("Content-Length: 8", byteArrayOf(1, 2), requests = 1) { url ->
                assertThrows(java.io.IOException::class.java) {
                    Http.downloadToFile(url, file, maxBytes = 4)
                }
            }
        } finally { file.delete() }
    }

    @Test fun streamedDownloadRejectsUnknownLengthAtTheLimit() {
        val file = File.createTempFile("http-download", ".part")
        try {
            respond("Connection: close", ByteArray(20), requests = 1) { url ->
                assertThrows(java.io.IOException::class.java) {
                    Http.downloadToFile(url, file, maxBytes = 8)
                }
            }
        } finally { file.delete() }
    }

    @Test fun partialContentIsNotPublishedAsACompleteTrack() {
        val file = File.createTempFile("http-download", ".part")
        try {
            respond("Content-Length: 2", byteArrayOf(1, 2), requests = 1,
                status = "206 Partial Content") { url ->
                assertThrows(Http.HttpError::class.java) {
                    Http.downloadToFile(url, file)
                }
            }
        } finally { file.delete() }
    }
}
