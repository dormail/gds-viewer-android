package com.example.gdsviewer.db

import com.example.gdsviewer.gds.GdsFormatException
import java.io.EOFException
import java.io.FilterInputStream
import java.io.InputStream
import java.io.PushbackInputStream
import java.util.concurrent.CancellationException
import java.util.zip.GZIPInputStream
import java.util.zip.ZipException
import kotlin.math.max

/**
 * Fast reader for GDSII stream files that fills a [LibraryBuilder] directly (no per-shape objects).
 */
object GdsReader {
    /**
     * Decodes a GDSII 8-byte real: 1 sign bit, 7 bits exponent (excess 64, base 16) and a 56-bit
     * mantissa. value = mantissa / 2^56 * 16^(exponent - 64).
     */
    internal fun decodeReal8(bytes: ByteArray, offset: Int): Double {
        val b0 = bytes[offset].toInt() and 0xFF
        var mantissa = 0L
        for (i in 1..7) mantissa = (mantissa shl 8) or (bytes[offset + i].toLong() and 0xFF)
        if (mantissa == 0L) return 0.0
        val exponent = (b0 and 0x7F) - 64
        // 16^e = 2^(4e); dividing by 2^56 is folded into the same power of two.
        val value = Math.scalb(mantissa.toDouble(), 4 * exponent - 56)
        return if (b0 and 0x80 != 0) -value else value
    }

    private const val BUFFER_SIZE = 1 shl 20
    private const val PROGRESS_STEP = 1L shl 20
    private const val CANCEL_POLL_MASK = 1023 // poll isCancelled every 1024 records (and after every buffer refill)

    /**
     * Most bytes the reader accepts after unpacking. Real files of this app are up to a few hundred
     * MB; a small gzip file can unpack to many GB, which must not eat all memory or time.
     */
    const val MAX_UNPACKED_BYTES = 2L shl 30

    /**
     * Reads a whole GDSII file (plain or gzip-compressed, detected by the magic bytes). Does not close [input].
     *
     * Throws [GdsFormatException] with a user-readable message for invalid files (also for files
     * that are larger than [MAX_UNPACKED_BYTES] after unpacking), and
     * [CancellationException] when [isCancelled] returns true (polled every thousand records and after every buffer refill).
     * [onProgress] is called about every 1 MB with the number of bytes consumed from [input] so far
     * (compressed bytes for .gz files).
     */
    fun read(input: InputStream, isCancelled: () -> Boolean = { false }, onProgress: ((Long) -> Unit)? = null): Library =
        read(input, isCancelled, onProgress, MAX_UNPACKED_BYTES)

    /** [read] with a configurable unpacked size limit (so tests do not need multi-GB inputs). */
    internal fun read(input: InputStream, isCancelled: () -> Boolean, onProgress: ((Long) -> Unit)?, maxUnpackedBytes: Long): Library {
        try {
            // Peek at the first two bytes to detect gzip, then put them back.
            val pushback = PushbackInputStream(input, 2)
            val head = ByteArray(2)
            var got = 0
            while (got < 2) {
                val n = pushback.read(head, got, 2 - got)
                if (n < 0) break
                got += n
            }
            if (got > 0) pushback.unread(head, 0, got)
            val gzip = got == 2 && head[0] == 0x1F.toByte() && head[1] == 0x8B.toByte()

            val counting = CountingInputStream(pushback)
            val stream: InputStream = if (gzip) GZIPInputStream(counting, 64 * 1024) else counting
            return Reader(stream, counting, isCancelled, onProgress, maxUnpackedBytes).readLibrary()
        } catch (e: EOFException) {
            throw GdsFormatException("File ends unexpectedly")
        } catch (e: ZipException) {
            throw GdsFormatException("The compressed file is damaged")
        }
    }

    /** Counts the bytes read from the raw (possibly compressed) input. */
    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        var count = 0L
            private set

        override fun read(): Int = super.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }
        override fun skip(n: Long): Long = super.skip(n).also { count += it }
        override fun markSupported(): Boolean = false
    }

    private class Reader(
        private val stream: InputStream,
        private val counter: CountingInputStream,
        private val isCancelled: () -> Boolean,
        private val onProgress: ((Long) -> Unit)?,
        private val maxUnpackedBytes: Long,
    ) {
        // Input buffer: unread bytes are buf[pos until limit].
        private val buf = ByteArray(BUFFER_SIZE)
        private var pos = 0
        private var limit = 0
        private var eof = false
        private var unpacked = 0L // bytes taken from the (possibly decompressed) stream so far
        private var nextProgress = PROGRESS_STEP

        // Current record: type, and its data at buf[dataPos until dataPos + len].
        private var type = 0
        private var dataPos = 0
        private var len = 0

        private val builder = LibraryBuilder()
        private var libName = ""
        private var dbUnitMeters = 1e-9
        private var cell: CellBuilder? = null

        // Points of the current element. A record holds at most 65531 data bytes = 16382 ints.
        private val xy = IntArray(16384)
        private var xyCount = 0 // number of ints (2 per point)

        // Current element state (reset by startElement).
        private var kind = 0
        private var layer = 0
        private var datatype = 0
        private var width = 0
        private var pathType = 0
        private var bgnExtn = 0
        private var endExtn = 0
        private var sname = ""
        private var mirror = false
        private var mag = 1.0
        private var angle = 0.0
        private var cols = 1
        private var rows = 1

        fun readLibrary(): Library {
            var first = true
            var records = 0
            while (true) {
                if ((++records and CANCEL_POLL_MASK) == 0 && isCancelled()) throw CancellationException()
                val avail = available(4)
                if (avail == 0) {
                    if (first) throw GdsFormatException("This is not a GDSII file")
                    break // missing ENDLIB is tolerated
                }
                if (avail < 2) throw EOFException()
                val total = ((buf[pos].toInt() and 0xFF) shl 8) or (buf[pos + 1].toInt() and 0xFF)
                if (total == 0) {
                    // Zero padding after ENDLIB.
                    if (first) throw GdsFormatException("This is not a GDSII file")
                    break
                }
                if (avail < 4) throw EOFException()
                val t = buf[pos + 2].toInt() and 0xFF
                if (first && t != 0x00) throw GdsFormatException("This is not a GDSII file")
                first = false
                if (total < 4) throw GdsFormatException("Invalid record length $total")
                // (Odd lengths are not valid GDS, but the old parser accepted them, so we do too.)
                if (available(total) < total) throw EOFException()
                type = t
                dataPos = pos + 4
                len = total - 4
                pos += total
                if (type == 0x04) break // ENDLIB
                handle()
            }
            return builder.build(libName, dbUnitMeters)
        }

        /**
         * Makes sure at least [n] unread bytes are in the buffer if the input has them (n <= 65535)
         * and returns how many are available (less than [n] only at the end of the input).
         */
        private fun available(n: Int): Int {
            if (limit - pos >= n || eof) return limit - pos
            // Move the unread rest to the front, then refill.
            if (pos > 0) {
                System.arraycopy(buf, pos, buf, 0, limit - pos)
                limit -= pos
                pos = 0
            }
            do {
                val r = stream.read(buf, limit, buf.size - limit)
                if (r < 0) {
                    eof = true
                } else {
                    limit += r
                    unpacked += r
                    if (unpacked > maxUnpackedBytes) throw GdsFormatException("The file is larger than 2 GB when unpacked.")
                    // Records can be big and numerous between two polls in readLibrary, so also poll per refill.
                    if (isCancelled()) throw CancellationException()
                    reportProgress()
                }
            } while (limit < n && !eof)
            return limit - pos
        }

        private fun reportProgress() {
            val cb = onProgress ?: return
            val c = counter.count
            if (c >= nextProgress) {
                cb(c)
                nextProgress = c - c % PROGRESS_STEP + PROGRESS_STEP
            }
        }

        private fun handle() {
            when (type) {
                0x02 -> libName = string()
                0x03 -> {
                    val meters = real8(8)
                    if (meters > 0.0 && meters.isFinite()) dbUnitMeters = meters
                }
                0x06 -> cell = builder.beginCell(string())
                0x07 -> cell = null
                0x08 -> startElement(BOUNDARY)
                0x09 -> startElement(PATH)
                0x0A -> startElement(SREF)
                0x0B -> startElement(AREF)
                0x0C -> startElement(TEXT)
                0x15 -> startElement(NODE)
                0x2D -> startElement(BOX)
                0x0D -> layer = uint16(0)
                0x0E, 0x16, 0x2E -> datatype = uint16(0) // DATATYPE, TEXTTYPE, BOXTYPE
                0x0F -> width = int32(0)
                0x10 -> readPoints()
                0x11 -> endElement()
                0x12 -> sname = string()
                0x13 -> {
                    cols = uint16(0)
                    rows = uint16(2)
                }
                0x1A -> mirror = (uint16(0) and 0x8000) != 0
                0x1B -> mag = real8(0)
                0x1C -> angle = real8(0)
                0x21 -> pathType = int16(0)
                0x30 -> bgnExtn = int32(0)
                0x31 -> endExtn = int32(0)
                // Everything else (HEADER, BGNLIB, BGNSTR, TEXT string, properties, ...) is ignored.
            }
        }

        private fun readPoints() {
            if (len % 4 != 0) tooShort()
            val n = len / 4
            var p = dataPos
            val b = buf
            for (i in 0 until n) {
                xy[i] = ((b[p].toInt() and 0xFF) shl 24) or ((b[p + 1].toInt() and 0xFF) shl 16) or
                    ((b[p + 2].toInt() and 0xFF) shl 8) or (b[p + 3].toInt() and 0xFF)
                p += 4
            }
            xyCount = n
        }

        private fun startElement(k: Int) {
            kind = k
            layer = 0
            datatype = 0
            width = 0
            pathType = 0
            bgnExtn = 0
            endExtn = 0
            xyCount = 0
            sname = ""
            mirror = false
            mag = 1.0
            angle = 0.0
            cols = 1
            rows = 1
        }

        /** Hands the collected element to the current cell builder. */
        private fun endElement() {
            val c = cell
            if (c != null) {
                when (kind) {
                    BOUNDARY, BOX -> {
                        var n = xyCount / 2
                        // GDS repeats the first point at the end; we do not.
                        if (n >= 2 && xy[0] == xy[2 * n - 2] && xy[1] == xy[2 * n - 1]) n--
                        if (n >= 3) c.addPolygon(builder.layerIndex(layer, datatype), xy, n)
                    }
                    PATH ->
                        if (xyCount >= 2) {
                            c.addPath(builder.layerIndex(layer, datatype), width, pathType, bgnExtn, endExtn, xy, xyCount / 2)
                        }
                    SREF ->
                        if (xyCount >= 2) c.addRef(builder.cellIndex(sname), xy[0], xy[1], mirror, mag, angle)
                    AREF ->
                        if (xyCount >= 6) {
                            val nc = max(1, cols)
                            val nr = max(1, rows)
                            // P0 = origin, P1 = origin + cols * columnStep, P2 = origin + rows * rowStep.
                            // Done in Double to avoid Int overflow.
                            c.addArray(
                                builder.cellIndex(sname), xy[0], xy[1], mirror, mag, angle, nc, nr,
                                (xy[2].toDouble() - xy[0]) / nc, (xy[3].toDouble() - xy[1]) / nc,
                                (xy[4].toDouble() - xy[0]) / nr, (xy[5].toDouble() - xy[1]) / nr,
                            )
                        }
                    // TEXT and NODE are ignored.
                }
            }
            kind = NONE
        }

        // ---- Bounds-checked accessors for the current record's data ----

        private fun tooShort(): Nothing =
            throw GdsFormatException("Record ${recordName(type)} is too short or malformed")

        private fun need(offset: Int, count: Int) {
            if (offset < 0 || offset + count > len) tooShort()
        }

        private fun uint16(offset: Int): Int {
            need(offset, 2)
            val p = dataPos + offset
            return ((buf[p].toInt() and 0xFF) shl 8) or (buf[p + 1].toInt() and 0xFF)
        }

        private fun int16(offset: Int): Int = uint16(offset).toShort().toInt()

        private fun int32(offset: Int): Int {
            need(offset, 4)
            val p = dataPos + offset
            return ((buf[p].toInt() and 0xFF) shl 24) or ((buf[p + 1].toInt() and 0xFF) shl 16) or
                ((buf[p + 2].toInt() and 0xFF) shl 8) or (buf[p + 3].toInt() and 0xFF)
        }

        private fun real8(offset: Int): Double {
            need(offset, 8)
            return decodeReal8(buf, dataPos + offset)
        }

        /** ISO-8859-1 text with trailing NUL padding removed. */
        private fun string(): String {
            var end = len
            while (end > 0 && buf[dataPos + end - 1].toInt() == 0) end--
            return String(buf, dataPos, end, Charsets.ISO_8859_1)
        }

        private fun recordName(t: Int): String = when (t) {
            0x03 -> "UNITS"
            0x06 -> "STRNAME"
            0x0D -> "LAYER"
            0x0E -> "DATATYPE"
            0x0F -> "WIDTH"
            0x10 -> "XY"
            0x12 -> "SNAME"
            0x13 -> "COLROW"
            0x16 -> "TEXTTYPE"
            0x1A -> "STRANS"
            0x1B -> "MAG"
            0x1C -> "ANGLE"
            0x21 -> "PATHTYPE"
            0x2E -> "BOXTYPE"
            0x30 -> "BGNEXTN"
            0x31 -> "ENDEXTN"
            else -> "0x%02X".format(t)
        }
    }

    // Element kinds.
    private const val NONE = 0
    private const val BOUNDARY = 1
    private const val BOX = 2
    private const val PATH = 3
    private const val SREF = 4
    private const val AREF = 5
    private const val TEXT = 6
    private const val NODE = 7
}
