package io.github.dormail.gdsviewer.dxf

import io.github.dormail.gdsviewer.db.CellBuilder
import io.github.dormail.gdsviewer.db.Library
import io.github.dormail.gdsviewer.db.LibraryBuilder
import io.github.dormail.gdsviewer.gds.GdsFormatException
import io.github.dormail.gdsviewer.geometry.Triangulator
import java.io.InputStream
import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Reads ASCII DXF files (AutoCAD's drawing exchange format) into a [Library].
 *
 * A DXF file is a list of pairs of lines: a number (the "group code") and a value. The reader
 * walks through them once, without making one object per line, and fills a [LibraryBuilder].
 *
 * What is drawn: closed polylines (polygons), open polylines with a width (paths), circles,
 * solids and block references (INSERT, also as arrays). Blocks become cells, the model-space
 * entities go into a cell called "TOP". Everything else (lines, arcs, text, hatches, ...) is
 * skipped. Curved polyline segments (bulges) are cut into short straight pieces. The database unit
 * is always 1 nm; drawing units come from the header ($INSUNITS, micrometers if missing).
 */
object DxfReader {
    /** Name of the cell that receives the model-space entities (KLayout's convention). */
    const val TOP_CELL = "TOP"

    /** Longest line accepted, in bytes. Longer lines mean the file is not a text DXF. */
    const val MAX_LINE_BYTES = 4096

    /** Most points of one polygon or path (also after cutting arcs into pieces); larger shapes are dropped, like the triangulator does. */
    const val MAX_POINTS = Triangulator.MAX_POINTS

    /** Pieces per full circle for arcs (bulges); partial arcs get proportionally fewer, at least one. Circles are polygons with this many corners. */
    const val SEGMENTS_PER_CIRCLE = 64

    /** Longest layer or block name kept, in bytes. */
    const val MAX_NAME_BYTES = 255

    /** Most different blocks (defined or referenced) a file may have. */
    const val MAX_BLOCKS = 100_000

    /** Largest column / row count of an INSERT array (the same limit as GDS arrays). */
    const val MAX_ARRAY_COUNT = 65535

    /** Most bytes a file may have. */
    const val MAX_BYTES = 2L shl 30

    private const val BUFFER_SIZE = 1 shl 20
    private const val PROGRESS_STEP = 1L shl 20
    private const val CANCEL_POLL_MASK = 1023 // poll isCancelled every 1024 lines (and after every buffer refill)
    private const val DB_UNIT_METERS = 1e-9

    /**
     * Reads a whole ASCII DXF file. Does not close [input].
     *
     * Throws [GdsFormatException] with a user-readable message for invalid files and
     * [CancellationException] when [isCancelled] returns true. [onProgress] is called about every
     * 1 MB with the number of bytes consumed so far.
     */
    fun read(input: InputStream, isCancelled: () -> Boolean = { false }, onProgress: ((Long) -> Unit)? = null): Library =
        Reader(input, isCancelled, onProgress).readLibrary()

    /** Metres per drawing unit for an $INSUNITS value; unknown values and 0 (unitless) give micrometers. */
    internal fun metersPerUnit(insUnits: Int): Double = when (insUnits) {
        1 -> 0.0254
        2 -> 0.3048
        4 -> 1e-3
        5 -> 1e-2
        6 -> 1.0
        8 -> 2.54e-8
        9 -> 2.54e-5
        10 -> 0.9144
        11 -> 1e-10
        12 -> 1e-9
        13 -> 1e-6
        14 -> 0.1
        else -> 1e-6
    }

    // Entity kinds.
    private const val T_NONE = 0
    private const val T_SECTION = 1
    private const val T_LWPOLY = 2
    private const val T_POLYLINE = 3
    private const val T_VERTEX = 4
    private const val T_CIRCLE = 5
    private const val T_SOLID = 6
    private const val T_INSERT = 7
    private const val T_BLOCK = 8

    // Sections.
    private const val S_NONE = 0
    private const val S_HEADER = 1
    private const val S_BLOCKS = 2
    private const val S_ENTITIES = 3
    private const val S_OTHER = 4

    private val POW10 = DoubleArray(23).also { var p = 1.0; for (i in it.indices) { it[i] = p; p *= 10.0 } }
    private val CIRCLE_COS = DoubleArray(SEGMENTS_PER_CIRCLE) { cos(2 * PI * it / SEGMENTS_PER_CIRCLE) }
    private val CIRCLE_SIN = DoubleArray(SEGMENTS_PER_CIRCLE) { sin(2 * PI * it / SEGMENTS_PER_CIRCLE) }

    private class Reader(
        private val stream: InputStream,
        private val isCancelled: () -> Boolean,
        private val onProgress: ((Long) -> Unit)?,
    ) {
        // ---- input buffer: unread bytes are buf[pos until limit] ----
        private val buf = ByteArray(BUFFER_SIZE)
        private var pos = 0
        private var limit = 0
        private var eof = false
        private var total = 0L
        private var nextProgress = PROGRESS_STEP
        private var lines = 0

        // ---- current line / group ----
        private var lineStart = 0
        private var lineEnd = 0
        private var code = 0
        private var valStart = 0
        private var valEnd = 0

        // ---- library ----
        private val builder = LibraryBuilder()
        private val top: CellBuilder = builder.beginCell(TOP_CELL)
        private val blockNames = HashSet<String>()
        private var cell: CellBuilder? = null
        private var baseX = 0.0
        private var baseY = 0.0
        private var scale = 1000.0 // nm per drawing unit

        // ---- parser state ----
        private var section = S_NONE
        private var type = T_NONE
        private var wantUnits = false

        // Layer of the current entity (bytes) and the last layer that was looked up.
        private val layerBytes = ByteArray(MAX_NAME_BYTES)
        private var layerLen = 0
        private val cachedLayerBytes = ByteArray(MAX_NAME_BYTES)
        private var cachedLayerLen = -1
        private var cachedLayerIndex = 0

        // Block name of the current INSERT, and the last one looked up.
        private val refBytes = ByteArray(MAX_NAME_BYTES)
        private var refLen = -1
        private val cachedRefBytes = ByteArray(MAX_NAME_BYTES)
        private var cachedRefLen = -1
        private var cachedRefIndex = 0

        private var blockName = ""

        // Polyline vertices (LWPOLYLINE, POLYLINE + VERTEX), in drawing units before mirroring.
        private val vx = DoubleArray(MAX_POINTS)
        private val vy = DoubleArray(MAX_POINTS)
        private val vBulge = DoubleArray(MAX_POINTS)
        private val vStartWidth = DoubleArray(MAX_POINTS)
        private val vEndWidth = DoubleArray(MAX_POINTS)
        private var vn = 0
        private var polyActive = false
        private var polySkip = false
        private var polyOverflow = false
        private var polyFlags = 0
        private var polyStartDefault = 0.0
        private var polyEndDefault = 0.0
        private var slotActive = false
        private var mirrorX = false

        // Output points (nm) of the shape being emitted.
        private val out = IntArray(2 * MAX_POINTS)
        private var outN = 0
        private var outFull = false

        // Circle / solid / insert fields.
        private var cx = 0.0
        private var cy = 0.0
        private var radius = 0.0
        private val sx = DoubleArray(4)
        private val sy = DoubleArray(4)
        private var solidHas3 = false
        private var scaleX = 1.0
        private var scaleY = 1.0
        private var rotation = 0.0
        private var cols = 1
        private var rows = 1
        private var colSpacing = 0.0
        private var rowSpacing = 0.0

        /** Number of INSERTs with non-uniform scale that were drawn with an average scale. */
        @Suppress("unused")
        private var approximated = 0

        fun readLibrary(): Library {
            var first = true
            while (nextGroup()) {
                if (first) {
                    // A DXF file starts with "0 SECTION" (comments are skipped before).
                    if (code != 0 || !valueIs("SECTION")) throw notDxf()
                    first = false
                }
                if (code == 0) {
                    finishEntity()
                    if (valueIs("EOF")) {
                        flushPolyline()
                        break
                    }
                    startEntity()
                } else {
                    onGroup()
                }
            }
            if (first) throw notDxf()
            finishEntity()
            flushPolyline()
            return builder.build("DXF", DB_UNIT_METERS)
        }

        private fun notDxf() = GdsFormatException("This is not a valid DXF file")

        // ------------------------------------------------------------ reading lines

        /** Reads the next group (code and value) into [code] and valStart..valEnd; false at a clean end of input. */
        private fun nextGroup(): Boolean {
            while (true) {
                // Group code line (blank lines are skipped).
                var c: Int
                do {
                    if (!nextLine()) return false
                    c = parseCode()
                } while (c == BLANK)
                if (!nextLine()) throw GdsFormatException("File ends unexpectedly")
                var s = lineStart
                var e = lineEnd
                while (s < e && isWs(buf[s])) s++
                while (e > s && isWs(buf[e - 1])) e--
                valStart = s
                valEnd = e
                if (c == 999) continue // comment
                code = c
                return true
            }
        }

        private fun parseCode(): Int {
            var i = lineStart
            var e = lineEnd
            while (i < e && isWs(buf[i])) i++
            while (e > i && isWs(buf[e - 1])) e--
            if (i == e) return BLANK
            if (e - i > 6) throw notDxf()
            var v = 0
            while (i < e) {
                val d = buf[i++] - '0'.code.toByte()
                if (d < 0 || d > 9) throw notDxf()
                v = v * 10 + d
            }
            return v
        }

        private fun nextLine(): Boolean {
            if ((++lines and CANCEL_POLL_MASK) == 0 && isCancelled()) throw CancellationException()
            var scan = pos
            while (true) {
                val b = buf
                val lim = limit
                var i = scan
                while (i < lim && b[i] != NL) i++
                if (i < lim) {
                    var e = i
                    if (e > pos && b[e - 1] == CR) e--
                    if (e - pos > MAX_LINE_BYTES) throw tooLong()
                    lineStart = pos
                    lineEnd = e
                    pos = i + 1
                    return true
                }
                if (eof) {
                    if (pos == limit) return false
                    var e = limit
                    if (e > pos && b[e - 1] == CR) e--
                    if (e - pos > MAX_LINE_BYTES) throw tooLong()
                    lineStart = pos
                    lineEnd = e
                    pos = limit
                    return true
                }
                if (limit - pos > MAX_LINE_BYTES + 1) throw tooLong()
                scan = limit - pos
                fill()
            }
        }

        private fun tooLong() = GdsFormatException("This is not a text DXF file (a line is longer than $MAX_LINE_BYTES characters)")

        private fun fill() {
            if (pos > 0) {
                System.arraycopy(buf, pos, buf, 0, limit - pos)
                limit -= pos
                pos = 0
            }
            val r = stream.read(buf, limit, buf.size - limit)
            if (r < 0) {
                eof = true
                return
            }
            if (total == 0L && limit == 0 && r >= 3 && buf[0] == 0xEF.toByte() && buf[1] == 0xBB.toByte() && buf[2] == 0xBF.toByte()) {
                // UTF-8 byte order mark.
                System.arraycopy(buf, 3, buf, 0, r - 3)
                total += r
                limit += r - 3
            } else {
                limit += r
                total += r
            }
            if (total > MAX_BYTES) throw GdsFormatException("The file is larger than 2 GB.")
            if (isCancelled()) throw CancellationException()
            val cb = onProgress
            if (cb != null && total >= nextProgress) {
                cb(total)
                nextProgress = total - total % PROGRESS_STEP + PROGRESS_STEP
            }
        }

        // ------------------------------------------------------------ values

        private fun valueIs(s: String): Boolean {
            if (valEnd - valStart != s.length) return false
            for (i in s.indices) if (buf[valStart + i].toInt() != s[i].code) return false
            return true
        }

        private fun valueStartsWithIgnoreCase(s: String): Boolean {
            if (valEnd - valStart < s.length) return false
            for (i in s.indices) if ((buf[valStart + i].toInt() or 0x20) != (s[i].code or 0x20)) return false
            return true
        }

        /** The current value as a finite number. */
        private fun num(): Double {
            val b = buf
            var i = valStart
            val e = valEnd
            var neg = false
            if (i < e && (b[i] == MINUS || b[i] == PLUS)) { neg = b[i] == MINUS; i++ }
            var mant = 0L
            var digits = 0
            var exp10 = 0
            var any = false
            while (i < e) {
                val d = b[i] - ZERO
                if (d < 0 || d > 9) break
                if (digits < 18) { mant = mant * 10 + d; if (mant != 0L) digits++ } else exp10++
                any = true
                i++
            }
            if (i < e && b[i] == DOT) {
                i++
                while (i < e) {
                    val d = b[i] - ZERO
                    if (d < 0 || d > 9) break
                    if (digits < 18) { mant = mant * 10 + d; if (mant != 0L) digits++; exp10-- }
                    any = true
                    i++
                }
            }
            var ok = any
            if (ok && i < e && (b[i] == 'e'.code.toByte() || b[i] == 'E'.code.toByte())) {
                i++
                var eneg = false
                if (i < e && (b[i] == MINUS || b[i] == PLUS)) { eneg = b[i] == MINUS; i++ }
                var ev = 0
                var edigits = 0
                while (i < e) {
                    val d = b[i] - ZERO
                    if (d < 0 || d > 9) break
                    if (ev < 100000) ev = ev * 10 + d
                    edigits++
                    i++
                }
                if (edigits == 0) ok = false
                exp10 += if (eneg) -ev else ev
            }
            if (ok && i == e && digits <= 15 && exp10 >= -22 && exp10 <= 22) {
                var v = mant.toDouble()
                v = if (exp10 >= 0) v * POW10[exp10] else v / POW10[-exp10]
                return if (neg) -v else v
            }
            // Unusual spelling or many digits: let Java parse it.
            val v = try {
                String(b, valStart, valEnd - valStart, Charsets.ISO_8859_1).toDouble()
            } catch (_: NumberFormatException) {
                throw invalidNumber()
            }
            if (!v.isFinite()) throw invalidNumber()
            return v
        }

        private fun invalidNumber() = GdsFormatException("The file contains an invalid number")

        private fun int(): Int = num().let { if (it >= Int.MAX_VALUE) Int.MAX_VALUE else if (it <= Int.MIN_VALUE) Int.MIN_VALUE else it.toInt() }

        /** Copies the current value (cut to [MAX_NAME_BYTES]) to [dst] and returns its length. */
        private fun copyValue(dst: ByteArray): Int {
            val n = min(valEnd - valStart, MAX_NAME_BYTES)
            System.arraycopy(buf, valStart, dst, 0, n)
            return n
        }

        private fun valueString(): String = String(buf, valStart, min(valEnd - valStart, MAX_NAME_BYTES), Charsets.UTF_8)

        /** Drawing units to nm (rounded); fails for numbers that do not fit an Int. */
        private fun nm(v: Double): Int {
            val r = Math.rint(v * scale)
            if (!(abs(r) <= Int.MAX_VALUE)) throw GdsFormatException("Coordinates are too large")
            return r.toInt()
        }

        // ------------------------------------------------------------ entities

        private fun startEntity() {
            if (polyActive && !valueIs("VERTEX")) flushPolyline()
            type = T_NONE
            when {
                valueIs("SECTION") -> type = T_SECTION
                valueIs("ENDSEC") -> { section = S_NONE; cell = null }
                valueIs("ENDBLK") -> { cell = null; baseX = 0.0; baseY = 0.0 }
                section == S_BLOCKS && valueIs("BLOCK") -> {
                    type = T_BLOCK
                    blockName = ""
                    baseX = 0.0
                    baseY = 0.0
                }
                section != S_ENTITIES && section != S_BLOCKS -> {}
                valueIs("LWPOLYLINE") -> {
                    type = T_LWPOLY
                    beginPolyline(skip = false)
                }
                valueIs("POLYLINE") -> {
                    type = T_POLYLINE
                    beginPolyline(skip = false)
                }
                valueIs("VERTEX") -> {
                    type = T_VERTEX
                    slotActive = false
                    if (polyActive && !polySkip) {
                        if (vn >= MAX_POINTS) {
                            polyOverflow = true
                        } else {
                            newVertex()
                            slotActive = true
                        }
                    }
                }
                valueIs("CIRCLE") -> {
                    type = T_CIRCLE
                    resetCommon()
                    cx = 0.0; cy = 0.0; radius = 0.0
                }
                valueIs("SOLID") -> {
                    type = T_SOLID
                    resetCommon()
                    sx.fill(0.0); sy.fill(0.0)
                    solidHas3 = false
                }
                valueIs("INSERT") -> {
                    type = T_INSERT
                    resetCommon()
                    refLen = -1
                    cx = 0.0; cy = 0.0
                    scaleX = 1.0; scaleY = 1.0; rotation = 0.0
                    cols = 1; rows = 1
                    colSpacing = 0.0; rowSpacing = 0.0
                }
                // SEQEND closes a POLYLINE (flushPolyline above); all other entities are skipped.
            }
        }

        private fun resetCommon() {
            layerBytes[0] = '0'.code.toByte()
            layerLen = 1
            mirrorX = false
        }

        private fun beginPolyline(skip: Boolean) {
            resetCommon()
            polyActive = true
            polySkip = skip
            polyOverflow = false
            polyFlags = 0
            polyStartDefault = 0.0
            polyEndDefault = 0.0
            vn = 0
        }

        private fun newVertex() {
            vx[vn] = 0.0; vy[vn] = 0.0; vBulge[vn] = 0.0
            vStartWidth[vn] = Double.NaN; vEndWidth[vn] = Double.NaN
            vn++
        }

        private fun onGroup() {
            if (section == S_HEADER) {
                if (code == 9) wantUnits = valueIs("\$INSUNITS")
                else if (code == 70 && wantUnits) {
                    wantUnits = false
                    scale = metersPerUnit(int()) / DB_UNIT_METERS
                }
                return
            }
            when (type) {
                T_SECTION -> if (code == 2) {
                    section = when {
                        valueIs("HEADER") -> S_HEADER
                        valueIs("BLOCKS") -> S_BLOCKS
                        valueIs("ENTITIES") -> S_ENTITIES
                        else -> S_OTHER
                    }
                    cell = if (section == S_ENTITIES) top else null
                    baseX = 0.0; baseY = 0.0
                    type = T_NONE
                }
                T_BLOCK -> when (code) {
                    2 -> blockName = valueString()
                    10 -> baseX = num()
                    20 -> baseY = num()
                }
                T_LWPOLY -> when (code) {
                    8 -> layerLen = copyValue(layerBytes)
                    70 -> polyFlags = int()
                    43 -> { val w = num(); polyStartDefault = w; polyEndDefault = w }
                    230 -> mirrorX = num() < 0
                    10 -> if (vn >= MAX_POINTS) polyOverflow = true else { newVertex(); vx[vn - 1] = num() }
                    20 -> if (vn > 0 && !polyOverflow) vy[vn - 1] = num()
                    42 -> if (vn > 0 && !polyOverflow) vBulge[vn - 1] = num()
                    40 -> if (vn > 0 && !polyOverflow) vStartWidth[vn - 1] = num()
                    41 -> if (vn > 0 && !polyOverflow) vEndWidth[vn - 1] = num()
                }
                T_POLYLINE -> when (code) {
                    8 -> layerLen = copyValue(layerBytes)
                    70 -> {
                        polyFlags = int()
                        // 3D polylines (8), polygon meshes (16) and polyface meshes (64) are not 2D drawings.
                        if (polyFlags and (8 or 16 or 64) != 0) polySkip = true
                    }
                    40 -> polyStartDefault = num()
                    41 -> polyEndDefault = num()
                    230 -> mirrorX = num() < 0
                }
                T_VERTEX -> if (slotActive) when (code) {
                    10 -> vx[vn - 1] = num()
                    20 -> vy[vn - 1] = num()
                    42 -> vBulge[vn - 1] = num()
                    40 -> vStartWidth[vn - 1] = num()
                    41 -> vEndWidth[vn - 1] = num()
                    70 -> if (int() and (16 or 64 or 128) != 0) { vn--; slotActive = false } // spline control or mesh vertex
                }
                T_CIRCLE -> when (code) {
                    8 -> layerLen = copyValue(layerBytes)
                    10 -> cx = num()
                    20 -> cy = num()
                    40 -> radius = num()
                    230 -> mirrorX = num() < 0
                }
                T_SOLID -> when (code) {
                    8 -> layerLen = copyValue(layerBytes)
                    in 10..13 -> { sx[code - 10] = num(); if (code == 13) solidHas3 = true }
                    in 20..23 -> sy[code - 20] = num()
                    230 -> mirrorX = num() < 0
                }
                T_INSERT -> when (code) {
                    8 -> layerLen = copyValue(layerBytes)
                    2 -> refLen = copyValue(refBytes)
                    10 -> cx = num()
                    20 -> cy = num()
                    41 -> scaleX = num()
                    42 -> scaleY = num()
                    50 -> rotation = num()
                    70 -> cols = int()
                    71 -> rows = int()
                    44 -> colSpacing = num()
                    45 -> rowSpacing = num()
                }
            }
        }

        /** The entity that just ended is complete: draw it. */
        private fun finishEntity() {
            when (type) {
                T_LWPOLY -> flushPolyline()
                T_CIRCLE -> emitCircle()
                T_SOLID -> emitSolid()
                T_INSERT -> emitInsert()
                T_BLOCK -> startBlock()
                // POLYLINE is drawn when its SEQEND (or any other entity) follows its vertices.
            }
            type = T_NONE
        }

        private fun startBlock() {
            val name = blockName
            cell = if (name.startsWith("*Model_Space", ignoreCase = true) || name.startsWith("*Paper_Space", ignoreCase = true)) {
                null
            } else {
                registerBlock(name)
                builder.beginCell(name)
            }
        }

        private fun registerBlock(name: String) {
            if (blockNames.size >= MAX_BLOCKS && name !in blockNames) {
                throw GdsFormatException("The file has more than $MAX_BLOCKS blocks")
            }
            blockNames.add(name)
        }

        private fun layer(): Int {
            if (layerLen != cachedLayerLen || !sameBytes(layerBytes, cachedLayerBytes, layerLen)) {
                cachedLayerIndex = builder.layerIndex(String(layerBytes, 0, layerLen, Charsets.UTF_8))
                System.arraycopy(layerBytes, 0, cachedLayerBytes, 0, layerLen)
                cachedLayerLen = layerLen
            }
            return cachedLayerIndex
        }

        private fun sameBytes(a: ByteArray, b: ByteArray, n: Int): Boolean {
            for (i in 0 until n) if (a[i] != b[i]) return false
            return true
        }

        // ------------------------------------------------------------ shapes

        private fun resetOut() {
            outN = 0
            outFull = false
        }

        /** Appends a point given in drawing units (mirroring and block base point applied here). Drops repeated points. */
        private fun addPoint(x: Double, y: Double) {
            val xi = nm((if (mirrorX) -x else x) - baseX)
            val yi = nm(y - baseY)
            if (outN > 0 && out[2 * outN - 2] == xi && out[2 * outN - 1] == yi) return
            if (outN >= MAX_POINTS) { outFull = true; return }
            out[2 * outN] = xi
            out[2 * outN + 1] = yi
            outN++
        }

        /** Appends the curved or straight segment from vertex [i] to point ([x1], [y1]), without its end point. */
        private fun addSegment(i: Int, x1: Double, y1: Double) {
            val x0 = vx[i]
            val y0 = vy[i]
            addPoint(x0, y0)
            val b = vBulge[i]
            if (!(abs(b) > 1e-9)) return
            val bulge = b.coerceIn(-1e6, 1e6)
            val dx = x1 - x0
            val dy = y1 - y0
            val len = sqrt(dx * dx + dy * dy)
            if (!(len > 0.0) || !len.isFinite()) return
            val included = 4.0 * atan(bulge) // signed, counter-clockwise positive
            val pieces = max(1, ceil(SEGMENTS_PER_CIRCLE * abs(included) / (2 * PI)).toInt())
            if (pieces == 1) return
            // Center: on the left of the chord for positive bulges, at distance h from its midpoint.
            val h = (len / 2) * (1 - bulge * bulge) / (2 * bulge)
            val ccx = (x0 + x1) / 2 - dy / len * h
            val ccy = (y0 + y1) / 2 + dx / len * h
            val ux = x0 - ccx
            val uy = y0 - ccy
            for (k in 1 until pieces) {
                val a = included * k / pieces
                val c = cos(a)
                val s = sin(a)
                addPoint(ccx + ux * c - uy * s, ccy + ux * s + uy * c)
                if (outFull) return
            }
        }

        private fun flushPolyline() {
            if (!polyActive) return
            polyActive = false
            val c = cell ?: return
            val n = vn
            if (polySkip || polyOverflow || n < 2) return
            val closed = polyFlags and 1 != 0
            resetOut()
            if (closed) {
                for (i in 0 until n) {
                    val j = if (i + 1 < n) i + 1 else 0
                    addSegment(i, vx[j], vy[j])
                    if (outFull) return
                }
                while (outN > 1 && out[0] == out[2 * outN - 2] && out[1] == out[2 * outN - 1]) outN--
                if (outN >= 3) c.addPolygon(layer(), out, outN)
            } else {
                // Width: the same non-zero width on every segment, otherwise the line is not drawn.
                var w = Double.NaN
                for (i in 0 until n - 1) {
                    val s = if (vStartWidth[i].isNaN()) polyStartDefault else vStartWidth[i]
                    val e = if (vEndWidth[i].isNaN()) polyEndDefault else vEndWidth[i]
                    if (s != e || (!w.isNaN() && s != w)) return
                    w = s
                }
                if (!(w > 0.0)) return
                val width = nm(w)
                if (width <= 0) return
                for (i in 0 until n - 1) {
                    addSegment(i, vx[i + 1], vy[i + 1])
                    if (outFull) return
                }
                addPoint(vx[n - 1], vy[n - 1])
                if (outFull) return
                if (outN >= 2) c.addPath(layer(), width, 0, 0, 0, out, outN)
            }
        }

        private fun emitCircle() {
            val c = cell ?: return
            if (!(radius > 0.0)) return
            resetOut()
            for (k in 0 until SEGMENTS_PER_CIRCLE) addPoint(cx + radius * CIRCLE_COS[k], cy + radius * CIRCLE_SIN[k])
            while (outN > 1 && out[0] == out[2 * outN - 2] && out[1] == out[2 * outN - 1]) outN--
            if (outN >= 3) c.addPolygon(layer(), out, outN)
        }

        private fun emitSolid() {
            val c = cell ?: return
            resetOut()
            // DXF stores the corners in "Z" order: 0, 1, 3, 2. A fourth corner equal to the third (or missing) means a triangle.
            addPoint(sx[0], sy[0])
            addPoint(sx[1], sy[1])
            if (solidHas3 && !(sx[3] == sx[2] && sy[3] == sy[2])) addPoint(sx[3], sy[3])
            addPoint(sx[2], sy[2])
            while (outN > 1 && out[0] == out[2 * outN - 2] && out[1] == out[2 * outN - 1]) outN--
            if (outN >= 3) c.addPolygon(layer(), out, outN)
        }

        private fun emitInsert() {
            val c = cell ?: return
            if (refLen < 0) return
            if (scaleX == 0.0 || scaleY == 0.0) return
            val ax = abs(scaleX)
            val ay = abs(scaleY)
            val mag: Double
            if (ax == ay) {
                mag = ax
            } else {
                mag = sqrt(ax * ay) // not representable as a GDS reference: use the average scale
                approximated++
            }
            if (!(mag in 1e-6..1e6)) return
            // DXF: scale (sx, sy), then rotate. GDS: mirror y, scale, then rotate. A negative x scale is a 180 degree turn.
            val mirror = (scaleX < 0) != (scaleY < 0)
            var angle = rotation
            if (scaleX < 0) angle += 180.0
            angle = ((angle % 360.0) + 360.0) % 360.0

            if (refLen != cachedRefLen || !sameBytes(refBytes, cachedRefBytes, refLen)) {
                val name = String(refBytes, 0, refLen, Charsets.UTF_8)
                registerBlock(name)
                cachedRefIndex = builder.cellIndex(name)
                System.arraycopy(refBytes, 0, cachedRefBytes, 0, refLen)
                cachedRefLen = refLen
            }
            val x = nm(cx - baseX)
            val y = nm(cy - baseY)
            val nc = cols.coerceIn(1, MAX_ARRAY_COUNT)
            val nr = rows.coerceIn(1, MAX_ARRAY_COUNT)
            if (nc == 1 && nr == 1) {
                c.addRef(cachedRefIndex, x, y, mirror, mag, angle)
                return
            }
            // Spacings are measured in the rotated frame of the insertion (not scaled by it).
            val rad = Math.toRadians(rotation)
            val co = cos(rad)
            val si = sin(rad)
            val cs = colSpacing * scale
            val rs = rowSpacing * scale
            val csx = cs * co
            val csy = cs * si
            val rsx = -rs * si
            val rsy = rs * co
            val limitStep = Int.MAX_VALUE.toDouble()
            if (!(abs(csx) <= limitStep && abs(csy) <= limitStep && abs(rsx) <= limitStep && abs(rsy) <= limitStep)) {
                throw GdsFormatException("Coordinates are too large")
            }
            c.addArray(cachedRefIndex, x, y, mirror, mag, angle, nc, nr, csx, csy, rsx, rsy)
        }
    }

    private fun isWs(b: Byte): Boolean = b.toInt() in 0..32

    private const val NL = '\n'.code.toByte()
    private const val CR = '\r'.code.toByte()
    private const val MINUS = '-'.code.toByte()
    private const val PLUS = '+'.code.toByte()
    private const val DOT = '.'.code.toByte()
    private const val ZERO = '0'.code.toByte()
    private const val BLANK = -1
}
