package ru.rikko.music

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

/**
 * Переупаковка FLAC из MP4-контейнера в нативный .flac — без перекодирования.
 *
 * Яндекс отдаёт lossless как FLAC внутри MP4. Нативные FLAC-декодеры такой файл
 * не понимают (ждут сигнатуру fLaC), поэтому достаём STREAMINFO из бокса dfLa и
 * сами аудиокадры точно по таблицам stsz/stco/stsc, после чего собираем
 * обычный FLAC-контейнер. Аудио при этом побайтово то же самое.
 *
 * Порт проверенной реализации из расширения (lib/flacremux.js).
 */
object FlacRemux {

    class NotFlacMp4(message: String) : Exception(message)
    private const val MAX_SAMPLES = 100_000
    private const val MAX_CHUNKS = 100_000
    private interface Bytes {
        val size: Int
        operator fun get(index: Int): Byte
        fun copy(start: Int, end: Int): ByteArray
    }
    private class ArrayBytes(private val data: ByteArray) : Bytes {
        override val size get() = data.size
        override fun get(index: Int) = data[index]
        override fun copy(start: Int, end: Int) = data.copyOfRange(start, end)
    }
    private class MappedBytes(private val data: java.nio.ByteBuffer) : Bytes {
        override val size get() = data.limit()
        override fun get(index: Int): Byte = data.get(index)
        override fun copy(start: Int, end: Int): ByteArray =
            ByteArray(end - start).also { target ->
                val view = data.duplicate()
                view.position(start)
                view.get(target)
            }
    }

    private fun fits(b: Bytes, offset: Long, length: Long): Boolean =
        offset >= 0 && length >= 0 && offset <= b.size.toLong() &&
            length <= b.size.toLong() - offset

    private fun rd32(b: Bytes, o: Int): Int {
        if (!fits(b, o.toLong(), 4)) throw NotFlacMp4("Неполная таблица MP4")
        return ((b[o].toInt() and 0xff) shl 24) or
                ((b[o + 1].toInt() and 0xff) shl 16) or
                ((b[o + 2].toInt() and 0xff) shl 8) or
                (b[o + 3].toInt() and 0xff)
    }

    private fun boxEnd(b: Bytes, signatureIndex: Int): Long {
        val start = signatureIndex - 4
        val rawSize = rd32(b, start).toLong() and 0xffffffffL
        val size = if (rawSize == 1L) {
            ((rd32(b, signatureIndex + 4).toLong() and 0xffffffffL) shl 32) or
                (rd32(b, signatureIndex + 8).toLong() and 0xffffffffL)
        } else rawSize
        return start.toLong() + size
    }

    /** Ищет только сигнатуры с корректным заголовком и границами MP4-бокса. */
    private fun findSig(b: Bytes, sig: String, from: Int = 0): Int {
        val s = sig.toByteArray(Charsets.US_ASCII)
        var i = maxOf(from, 4)
        while (i + s.size <= b.size) {
            var ok = true
            for (j in s.indices) {
                if (b[i + j] != s[j]) { ok = false; break }
            }
            if (ok) {
                val start = i - 4
                val rawSize = rd32(b, start).toLong() and 0xffffffffL
                val boxSize = if (rawSize == 1L && fits(b, start.toLong(), 16)) {
                    ((rd32(b, i + 4).toLong() and 0xffffffffL) shl 32) or
                        (rd32(b, i + 8).toLong() and 0xffffffffL)
                } else rawSize
                if (boxSize >= (if (rawSize == 1L) 16 else 8) &&
                    fits(b, start.toLong(), boxSize)) return i
            }
            i++
        }
        return -1
    }

    fun isMp4(b: ByteArray): Boolean =
        b.size > 8 && b[4] == 'f'.code.toByte() && b[5] == 't'.code.toByte() &&
                b[6] == 'y'.code.toByte() && b[7] == 'p'.code.toByte()

    fun isNativeFlac(b: ByteArray): Boolean =
        b.size > 4 && b[0] == 'f'.code.toByte() && b[1] == 'L'.code.toByte() &&
                b[2] == 'a'.code.toByte() && b[3] == 'C'.code.toByte()

    /** STREAMINFO лежит в боксе dfLa сразу после версии и флагов. */
    private fun extractStreamInfo(b: Bytes): ByteArray? {
        val dfla = findSig(b, "dfLa")
        if (dfla < 0) return null
        val si = dfla + 4 + 4
        if (si + 4 > b.size) return null
        val blockSize = ((b[si + 1].toInt() and 0xff) shl 16) or
                ((b[si + 2].toInt() and 0xff) shl 8) or (b[si + 3].toInt() and 0xff)
        val end = si.toLong() + 4 + blockSize
        if (blockSize != 34 || !fits(b, si.toLong(), 4L + blockSize) ||
            end > boxEnd(b, dfla)) return null
        val block = b.copy(si, end.toInt())
        // Тип 0 — это и есть STREAMINFO
        if ((block[0].toInt() and 0x7f) != 0) return null
        return block
    }

    /** Где в MP4 лежат аудиокадры: куски «смещение — длина», идущие подряд кадры склеены. */
    class Plan(
        internal val streamInfo: ByteArray,
        internal val offsets: LongArray,
        internal val lengths: IntArray,
        internal val count: Int,
    )

    /**
     * Находим кадры по таблицам. Брать mdat целиком нельзя: между чанками
     * встречается выравнивание, из-за которого декодер спотыкается.
     */
    private fun frameRanges(b: Bytes, streamInfo: ByteArray): Plan? {
        val stszI = findSig(b, "stsz")
        if (stszI < 0) return null
        var ds = stszI + 4 + 4
        val uniformSize = rd32(b, ds)
        val count = rd32(b, ds + 4)
        if (count !in 1..MAX_SAMPLES || uniformSize < 0) return null
        if (!fits(b, ds.toLong() + 8, if (uniformSize == 0) count.toLong() * 4 else 0) ||
            ds.toLong() + 8 + (if (uniformSize == 0) count.toLong() * 4 else 0) >
            boxEnd(b, stszI)) return null
        val sizes = IntArray(count)
        if (uniformSize == 0) {
            for (j in 0 until count) sizes[j] = rd32(b, ds + 8 + j * 4)
        } else {
            for (j in 0 until count) sizes[j] = uniformSize
        }

        // Смещения чанков: stco (32 бита) либо co64 (64 бита)
        val chunkOffsets: LongArray
        val stcoI = findSig(b, "stco")
        if (stcoI >= 0) {
            ds = stcoI + 4 + 4
            val cc = rd32(b, ds)
            if (cc !in 1..MAX_CHUNKS || !fits(b, ds.toLong() + 4, cc.toLong() * 4) ||
                ds.toLong() + 4 + cc.toLong() * 4 > boxEnd(b, stcoI)) return null
            chunkOffsets = LongArray(cc)
            for (j in 0 until cc) chunkOffsets[j] = rd32(b, ds + 4 + j * 4).toLong() and 0xffffffffL
        } else {
            val co64I = findSig(b, "co64")
            if (co64I < 0) return null
            ds = co64I + 4 + 4
            val cc = rd32(b, ds)
            if (cc !in 1..MAX_CHUNKS || !fits(b, ds.toLong() + 4, cc.toLong() * 8) ||
                ds.toLong() + 4 + cc.toLong() * 8 > boxEnd(b, co64I)) return null
            chunkOffsets = LongArray(cc)
            for (j in 0 until cc) {
                val hi = rd32(b, ds + 4 + j * 8).toLong() and 0xffffffffL
                val lo = rd32(b, ds + 8 + j * 8).toLong() and 0xffffffffL
                chunkOffsets[j] = (hi shl 32) or lo
            }
        }
        val ccount = chunkOffsets.size
        if (ccount == 0) return null

        // stsc: сколько сэмплов в каждом чанке
        val stscI = findSig(b, "stsc")
        if (stscI < 0) return null
        ds = stscI + 4 + 4
        val sc = rd32(b, ds)
        if (sc !in 1..MAX_CHUNKS || !fits(b, ds.toLong() + 4, sc.toLong() * 12) ||
            ds.toLong() + 4 + sc.toLong() * 12 > boxEnd(b, stscI)) return null
        val firstChunk = IntArray(sc)
        val samplesPerChunk = IntArray(sc)
        for (j in 0 until sc) {
            firstChunk[j] = rd32(b, ds + 4 + j * 12)
            samplesPerChunk[j] = rd32(b, ds + 8 + j * 12)
        }
        val spcPerChunk = IntArray(ccount + 1)
        for (idx in 0 until sc) {
            val fc = firstChunk[idx]
            val spc = samplesPerChunk[idx]
            val nextFc = if (idx + 1 < sc) firstChunk[idx + 1] else ccount + 1
            if (fc !in 1..ccount || nextFc <= fc || nextFc > ccount + 1 || spc <= 0) return null
            var c = fc
            while (c < nextFc && c <= ccount) {
                spcPerChunk[c] = spc
                c++
            }
        }

        var total = 0L
        for (s in sizes) {
            if (s <= 0) return null
            total += s
        }
        if (total <= 0 || total > Int.MAX_VALUE) return null

        val offsets = LongArray(count)
        val lengths = IntArray(count)
        var n = 0
        var sampleIdx = 0
        for (c in 1..ccount) {
            var off = chunkOffsets[c - 1]
            val spc = spcPerChunk[c]
            var k = 0
            while (k < spc && sampleIdx < count) {
                val sz = sizes[sampleIdx]
                if (!fits(b, off, sz.toLong())) return null
                if (n > 0 && offsets[n - 1] + lengths[n - 1] == off) {
                    if (lengths[n - 1].toLong() + sz > Int.MAX_VALUE) return null
                    lengths[n - 1] += sz
                } else {
                    offsets[n] = off
                    lengths[n] = sz
                    n++
                }
                off += sz
                sampleIdx++
                k++
            }
        }
        if (sampleIdx != count) return null
        return Plan(streamInfo, offsets, lengths, n)
    }

    private fun be32(n: Int): ByteArray = byteArrayOf(
        ((n ushr 24) and 0xff).toByte(), ((n ushr 16) and 0xff).toByte(),
        ((n ushr 8) and 0xff).toByte(), (n and 0xff).toByte()
    )

    private fun le32(n: Int): ByteArray = byteArrayOf(
        (n and 0xff).toByte(), ((n ushr 8) and 0xff).toByte(),
        ((n ushr 16) and 0xff).toByte(), ((n ushr 24) and 0xff).toByte()
    )

    /** VORBIS_COMMENT: длины пишутся little-endian. */
    private fun vorbisComment(track: Track): ByteArray {
        val vendor = "MusicDownloader".toByteArray(Charsets.UTF_8)
        val comments = mutableListOf<ByteArray>()
        fun add(key: String, value: String) {
            if (value.isNotBlank()) comments.add((key + "=" + value).toByteArray(Charsets.UTF_8))
        }
        add("TITLE", track.title)
        add("ARTIST", track.artist)
        add("ALBUM", track.album)
        add("ALBUMARTIST", track.albumArtist)
        add("DATE", track.year)
        add("GENRE", track.genre)
        if (track.trackNum > 0) add("TRACKNUMBER", track.trackNum.toString())
        if (track.trackTotal > 0) add("TRACKTOTAL", track.trackTotal.toString())
        if (track.discNum > 0) add("DISCNUMBER", track.discNum.toString())
        add("LYRICS", track.lyrics)

        val out = ByteArrayOutputStream()
        out.write(le32(vendor.size)); out.write(vendor)
        out.write(le32(comments.size))
        for (c in comments) { out.write(le32(c.size)); out.write(c) }
        return out.toByteArray()
    }

    /** PICTURE: тут, наоборот, big-endian. */
    private fun pictureBlock(cover: ByteArray, mime: String): ByteArray {
        val mimeB = mime.toByteArray(Charsets.US_ASCII)
        val out = ByteArrayOutputStream()
        out.write(be32(3))                 // 3 — обложка (передняя сторона)
        out.write(be32(mimeB.size)); out.write(mimeB)
        out.write(be32(0))                 // описание пустое
        out.write(be32(0)); out.write(be32(0))   // ширина, высота — неизвестны
        out.write(be32(0)); out.write(be32(0))   // глубина цвета, палитра
        out.write(be32(cover.size)); out.write(cover)
        return out.toByteArray()
    }

    private fun metaBlockHeader(type: Int, size: Int, isLast: Boolean): ByteArray =
        byteArrayOf(
            (((if (isLast) 0x80 else 0)) or type).toByte(),
            ((size ushr 16) and 0xff).toByte(),
            ((size ushr 8) and 0xff).toByte(),
            (size and 0xff).toByte()
        )

    /**
     * Проверяет, что внутри FLAC, и находит кадры. Бросает NotFlacMp4 до того,
     * как что-то записано, — вызывающий код тогда сохраняет исходный файл как .m4a.
     */
    private fun plan(bytes: Bytes): Plan {
        val streamInfo = extractStreamInfo(bytes)
            ?: throw NotFlacMp4("В контейнере нет FLAC (бокс dfLa не найден)")
        val plan = frameRanges(bytes, streamInfo)
            ?: throw NotFlacMp4("Не удалось извлечь аудиокадры")
        if (plan.count == 0) throw NotFlacMp4("Аудиокадры пустые")
        return plan
    }

    fun plan(mp4: ByteArray): Plan = plan(ArrayBytes(mp4))

    /** Таблицы MP4 читаются из файла через отображение без полной копии в Java heap. */
    fun plan(file: File): Plan {
        if (file.length() <= 0 || file.length() > Int.MAX_VALUE)
            throw NotFlacMp4("Размер контейнера не поддерживается")
        RandomAccessFile(file, "r").use { input ->
            return plan(MappedBytes(input.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())))
        }
    }

    /**
     * Пишет нативный FLAC сразу в out. Кадры не собираются в отдельный массив:
     * раньше здесь появлялись ещё три копии трека, и длинные FLAC роняли
     * приложение посреди плейлиста (issue #76).
     */
    fun write(mp4: ByteArray, plan: Plan, track: Track, cover: ByteArray?, out: OutputStream) {
        writeMetadata(plan, track, cover, out)
        for (i in 0 until plan.count) {
            out.write(mp4, plan.offsets[i].toInt(), plan.lengths[i])
        }
    }

    fun write(file: File, plan: Plan, track: Track, cover: ByteArray?, out: OutputStream) {
        writeMetadata(plan, track, cover, out)
        RandomAccessFile(file, "r").use { input ->
            val buffer = ByteArray(64 * 1024)
            for (i in 0 until plan.count) {
                input.seek(plan.offsets[i])
                var remaining = plan.lengths[i]
                while (remaining > 0) {
                    val n = minOf(remaining, buffer.size)
                    input.readFully(buffer, 0, n)
                    out.write(buffer, 0, n)
                    remaining -= n
                }
            }
        }
    }

    private fun writeMetadata(plan: Plan, track: Track, cover: ByteArray?, out: OutputStream) {
        val si = plan.streamInfo.copyOf()
        si[0] = (si[0].toInt() and 0x7f).toByte()   // снимаем флаг «последний блок»

        val blocks = mutableListOf<Pair<Int, ByteArray>>()   // тип к содержимому
        blocks.add(0 to si.copyOfRange(4, si.size))          // STREAMINFO без заголовка
        blocks.add(4 to vorbisComment(track))
        if (cover != null && cover.isNotEmpty()) {
            blocks.add(6 to pictureBlock(cover, "image/jpeg"))
        }

        out.write("fLaC".toByteArray(Charsets.US_ASCII))
        for ((i, blk) in blocks.withIndex()) {
            val isLast = i == blocks.size - 1
            out.write(metaBlockHeader(blk.first, blk.second.size, isLast))
            out.write(blk.second)
        }
    }
}
