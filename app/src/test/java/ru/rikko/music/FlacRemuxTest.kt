package ru.rikko.music

import org.junit.Assert.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.io.File
import java.io.ByteArrayOutputStream

class FlacRemuxTest {
    private fun base(): ByteArray {
        val data = ByteArray(256)
        "dfLa".toByteArray().copyInto(data, 8)
        data[16] = 0 // STREAMINFO metadata type
        data[19] = 34 // STREAMINFO length
        "stsz".toByteArray().copyInto(data, 60)
        return data
    }

    @Test fun hugeSampleCountIsRejectedBeforeAllocation() {
        val data = base()
        data[72] = 0x7f
        assertThrows(FlacRemux.NotFlacMp4::class.java) { FlacRemux.plan(data) }
    }

    @Test fun truncatedSampleTableIsRejected() {
        val data = base()
        data[75] = 100
        assertThrows(FlacRemux.NotFlacMp4::class.java) { FlacRemux.plan(data) }
    }

    @Test fun truncatedTableHeaderHasControlledError() {
        val data = ByteArray(64)
        "dfLa".toByteArray().copyInto(data, 8)
        data[19] = 34
        "stsz".toByteArray().copyInto(data, 57)
        assertThrows(FlacRemux.NotFlacMp4::class.java) { FlacRemux.plan(data) }
    }

    private fun valid(): ByteArray = base().also { data ->
        data[75] = 1 // один аудиокадр
        data[79] = 4 // размер кадра
        "stco".toByteArray().copyInto(data, 84)
        data[95] = 1 // один чанк
        data[99] = 200.toByte() // смещение чанка
        "stsc".toByteArray().copyInto(data, 104)
        data[115] = 1 // одна запись
        data[119] = 1 // первый чанк
        data[123] = 1 // один кадр в чанке
        byteArrayOf(11, 22, 33, 44).copyInto(data, 200)
    }

    @Test fun filePlanStreamsTheExpectedAudioBytes() {
        val file = File.createTempFile("flac-plan", ".mp4")
        try {
            file.writeBytes(valid())
            val plan = FlacRemux.plan(file)
            assertEquals(1, plan.count)
            val output = ByteArrayOutputStream()
            FlacRemux.write(file, plan, Track("1", title = "test"), null, output)
            assertArrayEquals(byteArrayOf(11, 22, 33, 44), output.toByteArray().takeLast(4).toByteArray())
        } finally { file.delete() }
    }

    @Test fun incompleteSampleCoverageIsRejected() {
        val data = valid()
        data[75] = 2
        data[83] = 4
        assertThrows(FlacRemux.NotFlacMp4::class.java) { FlacRemux.plan(data) }
    }
}
