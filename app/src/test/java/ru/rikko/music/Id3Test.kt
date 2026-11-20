package ru.rikko.music

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class Id3Test {
    @Test fun streamingTagKeepsAudioAndReplacesOldHeader() {
        val audio = byteArrayOf(0xff.toByte(), 0xfb.toByte(), 0x90.toByte(), 0x64, 1, 2)
        val oldHeader = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(),
            3, 0, 0, 0, 0, 0, 0)
        val file = File.createTempFile("id3-stream", ".mp3")
        try {
            file.writeBytes(oldHeader + audio)
            val output = ByteArrayOutputStream()
            Id3.writeTagged(file, Track("1", title = "Test"), null, output)
            val bytes = output.toByteArray()
            assertEquals("ID3", bytes.copyOfRange(0, 3).toString(Charsets.US_ASCII))
            assertArrayEquals(audio, bytes.takeLast(audio.size).toByteArray())
        } finally { file.delete() }
    }
}
