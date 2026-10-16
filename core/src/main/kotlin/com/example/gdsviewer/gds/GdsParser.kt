package com.example.gdsviewer.gds

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipException
import kotlin.math.max

/** Reads GDSII stream files (plain or gzip-compressed). */
object GdsParser {
    private const val BUFFER_SIZE = 64 * 1024

    /** Parses a whole GDSII file. Throws [GdsFormatException] for invalid files. Does not close [input]. */
    fun parse(input: InputStream): GdsLibrary {
        try {
            return RecordReader(openStream(input)).readLibrary()
        } catch (e: EOFException) {
            throw GdsFormatException("File ends unexpectedly")
        } catch (e: ZipException) {
            throw GdsFormatException("The compressed file is damaged")
        }
    }

    /** Wraps the input in a buffer and transparently unzips it if it starts with the gzip magic bytes. */
    private fun openStream(input: InputStream): DataInputStream {
        val buffered = BufferedInputStream(input, BUFFER_SIZE)
        buffered.mark(2)
        val b0 = buffered.read()
        val b1 = buffered.read()
        buffered.reset()
        val plain: InputStream =
            if (b0 == 0x1F && b1 == 0x8B) BufferedInputStream(GZIPInputStream(buffered, BUFFER_SIZE), BUFFER_SIZE) else buffered
        return DataInputStream(plain)
    }

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

    /** The kinds of element we collect between an element start record and ENDEL. */
    private enum class Kind { NONE, BOUNDARY, BOX, PATH, SREF, AREF, TEXT, NODE }

    /** Does the actual work; holds the "current element" state while records stream by. */
    private class RecordReader(private val stream: DataInputStream) {
        private val buf = ByteArray(BUFFER_SIZE)
        private var type = 0
        private var len = 0 // data length of the current record

        private val cells = LinkedHashMap<String, GdsCell>()
        private val layerKeys = HashMap<Int, LayerKey>()
        private var libName = ""
        private var dbUnitMeters = 1e-9
        private var cell: GdsCell? = null

        // Current element state (reset by startElement).
        private var kind = Kind.NONE
        private var layer = 0
        private var datatype = 0
        private var width = 0
        private var pathType = 0
        private var bgnExtn = 0
        private var endExtn = 0
        private var xy = IntArray(0)
        private var sname = ""
        private var mirror = false
        private var mag = 1.0
        private var angle = 0.0
        private var cols = 1
        private var rows = 1
        private var string = ""

        fun readLibrary(): GdsLibrary {
            var first = true
            while (true) {
                val h0 = stream.read()
                if (h0 < 0) {
                    if (first) throw GdsFormatException("This is not a GDSII file")
                    break // missing ENDLIB is tolerated
                }
                val h1 = stream.read()
                if (h1 < 0) throw EOFException()
                val total = (h0 shl 8) or h1
                if (total == 0) {
                    // Zero padding after ENDLIB.
                    if (first) throw GdsFormatException("This is not a GDSII file")
                    break
                }
                val t = stream.read()
                val dataType = stream.read()
                if (t < 0 || dataType < 0) throw EOFException()
                if (first && t != 0x00) throw GdsFormatException("This is not a GDSII file")
                first = false
                if (total < 4) throw GdsFormatException("Invalid record length $total")
                type = t
                len = total - 4
                stream.readFully(buf, 0, len)
                if (type == 0x04) break // ENDLIB
                handle()
            }
            return GdsLibrary(libName, dbUnitMeters, cells)
        }

        private fun handle() {
            when (type) {
                0x02 -> libName = string()
                0x03 -> {
                    val meters = real8(8)
                    if (meters > 0.0 && meters.isFinite()) dbUnitMeters = meters
                }
                0x06 -> {
                    val name = string()
                    cell = cells.getOrPut(name) { GdsCell(name) }
                }
                0x07 -> cell = null
                0x08 -> startElement(Kind.BOUNDARY)
                0x09 -> startElement(Kind.PATH)
                0x0A -> startElement(Kind.SREF)
                0x0B -> startElement(Kind.AREF)
                0x0C -> startElement(Kind.TEXT)
                0x15 -> startElement(Kind.NODE)
                0x2D -> startElement(Kind.BOX)
                0x0D -> layer = uint16(0)
                0x0E, 0x16, 0x2E -> datatype = uint16(0) // DATATYPE, TEXTTYPE, BOXTYPE
                0x0F -> width = int32(0)
                0x10 -> {
                    val n = len / 4
                    if (len % 4 != 0) tooShort()
                    xy = IntArray(n) { int32(it * 4) }
                }
                0x11 -> endElement()
                0x12 -> sname = string()
                0x13 -> {
                    cols = uint16(0)
                    rows = uint16(2)
                }
                0x19 -> string = string()
                0x1A -> mirror = (uint16(0) and 0x8000) != 0
                0x1B -> mag = real8(0)
                0x1C -> angle = real8(0)
                0x21 -> pathType = int16(0)
                0x30 -> bgnExtn = int32(0)
                0x31 -> endExtn = int32(0)
                // Everything else (HEADER, BGNLIB, BGNSTR, properties, ...) is ignored.
            }
        }

        private fun startElement(k: Kind) {
            kind = k
            layer = 0
            datatype = 0
            width = 0
            pathType = 0
            bgnExtn = 0
            endExtn = 0
            xy = IntArray(0)
            sname = ""
            mirror = false
            mag = 1.0
            angle = 0.0
            cols = 1
            rows = 1
            string = ""
        }

        /** Turns the collected element state into a model object and stores it in the current cell. */
        private fun endElement() {
            val c = cell
            if (c != null) {
                when (kind) {
                    Kind.BOUNDARY, Kind.BOX -> {
                        var n = xy.size / 2
                        // GDS repeats the first point at the end; we do not.
                        if (n >= 2 && xy[0] == xy[2 * n - 2] && xy[1] == xy[2 * n - 1]) n--
                        if (n >= 3) c.polygons += GdsPolygon(key(), xy.copyOf(2 * n))
                    }
                    Kind.PATH ->
                        if (xy.size >= 2) c.paths += GdsPath(key(), width, pathType, bgnExtn, endExtn, xy)
                    Kind.TEXT ->
                        if (xy.size >= 2) c.texts += GdsText(key(), xy[0], xy[1], string)
                    Kind.SREF ->
                        if (xy.size >= 2) c.refs += GdsRef(sname, xy[0], xy[1], mirror, mag, angle)
                    Kind.AREF ->
                        if (xy.size >= 6) {
                            val nc = max(1, cols)
                            val nr = max(1, rows)
                            // P0 = origin, P1 = origin + cols * columnStep, P2 = origin + rows * rowStep.
                            // Done in Double to avoid Int overflow.
                            c.refs += GdsRef(
                                sname, xy[0], xy[1], mirror, mag, angle, nc, nr,
                                (xy[2].toDouble() - xy[0]) / nc, (xy[3].toDouble() - xy[1]) / nc,
                                (xy[4].toDouble() - xy[0]) / nr, (xy[5].toDouble() - xy[1]) / nr,
                            )
                        }
                    Kind.NODE, Kind.NONE -> {}
                }
            }
            kind = Kind.NONE
        }

        /** Shares one LayerKey object between all shapes of the same layer/datatype. */
        private fun key(): LayerKey = layerKeys.getOrPut((layer shl 16) or datatype) { LayerKey(layer, datatype) }

        // ---- Bounds-checked accessors for the current record's data ----

        private fun tooShort(): Nothing =
            throw GdsFormatException("Record ${recordName(type)} is too short or malformed")

        private fun need(offset: Int, count: Int) {
            if (offset < 0 || offset + count > len) tooShort()
        }

        private fun uint16(offset: Int): Int {
            need(offset, 2)
            return ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)
        }

        private fun int16(offset: Int): Int = uint16(offset).toShort().toInt()

        private fun int32(offset: Int): Int {
            need(offset, 4)
            return ((buf[offset].toInt() and 0xFF) shl 24) or ((buf[offset + 1].toInt() and 0xFF) shl 16) or
                ((buf[offset + 2].toInt() and 0xFF) shl 8) or (buf[offset + 3].toInt() and 0xFF)
        }

        private fun real8(offset: Int): Double {
            need(offset, 8)
            return decodeReal8(buf, offset)
        }

        /** ISO-8859-1 text with trailing NUL padding removed. */
        private fun string(): String {
            var end = len
            while (end > 0 && buf[end - 1].toInt() == 0) end--
            return String(buf, 0, end, Charsets.ISO_8859_1)
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
}
