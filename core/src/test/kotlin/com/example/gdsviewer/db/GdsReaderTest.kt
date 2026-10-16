package com.example.gdsviewer.db

import com.example.gdsviewer.assertEquals
import com.example.gdsviewer.assertFailsWith
import com.example.gdsviewer.assertNull
import com.example.gdsviewer.assertTrue
import com.example.gdsviewer.gds.GdsFormatException
import com.example.gdsviewer.gds.LayerKey
import com.example.gdsviewer.gds.TestGdsWriter
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.CancellationException
import java.util.zip.GZIPOutputStream

class GdsReaderTest {
    private fun w(name: String = "TOP") = TestGdsWriter().header().bgnlib().libname("LIB").units().bgnstr(name)
    private fun TestGdsWriter.finish() = apply { endstr(); endlib() }
    private fun read(bytes: ByteArray) = GdsReader.read(ByteArrayInputStream(bytes))
    private fun readOne(f: TestGdsWriter.() -> Unit): Cell = read(w().apply(f).finish().bytes()).cells.single()

    private fun Cell.rect(i: Int) = rects.xy.copyOfRange(4 * i, 4 * i + 4).toList()
    private fun Cell.poly(i: Int): List<Int> =
        polygons.xy.copyOfRange(2 * polygons.start[i], 2 * polygons.start[i + 1]).toList()

    /** Delivers at most [chunk] bytes per read call, to exercise buffer refills. */
    private class SlowStream(private val data: ByteArray, private val chunk: Int) : InputStream() {
        private var p = 0
        override fun read(): Int = if (p < data.size) data[p++].toInt() and 0xFF else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (p >= data.size) return -1
            val n = minOf(len, chunk, data.size - p)
            System.arraycopy(data, p, b, off, n)
            p += n
            return n
        }
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    // ---- Header ----

    @Test fun unitsAndLibraryName() {
        val lib = read(TestGdsWriter().header().bgnlib().libname("MYLIB").units(1e-3, 5e-9).bgnstr("A").endstr().endlib().bytes())
        assertEquals("MYLIB", lib.name)
        assertEquals(5e-9, lib.dbUnitMeters, 1e-20)
        assertEquals(5e-3, lib.dbUnitMicrons, 1e-15)
        assertEquals("A", lib.cells.single().name)
    }

    @Test fun invalidUnitsKeepDefault() {
        val lib = read(TestGdsWriter().header().bgnlib().libname("L").units(1e-3, 0.0).bgnstr("A").endstr().endlib().bytes())
        assertEquals(1e-9, lib.dbUnitMeters, 1e-20)
    }

    @Test fun gzipInput() {
        val bytes = w().boundary(1, 0, 0, 0, 10, 0, 10, 5, 0, 5, 0, 0).finish().bytes()
        val lib = read(gzip(bytes))
        assertEquals(1, lib.cells.single().rects.size)
        assertEquals("LIB", lib.name)
    }

    @Test fun damagedGzip() {
        val gz = gzip(manyRectangles(2000))
        gz[gz.size / 2] = (gz[gz.size / 2] + 1).toByte()
        val e = assertFailsWith<GdsFormatException> { read(gz) }
        assertTrue(e.message == "The compressed file is damaged" || e.message == "File ends unexpectedly", e.message)
    }

    @Test fun missingEndlibAndZeroPaddingTolerated() {
        val bytes = w().boundary(1, 0, 0, 0, 10, 0, 10, 5, 0, 5, 0, 0).endstr().bytes()
        assertEquals(1, read(bytes).cells.single().rects.size)
        assertEquals(1, read(bytes + ByteArray(100)).cells.single().rects.size)
    }

    // ---- Boundaries and boxes ----

    @Test fun boundaryClosingPointRemoved() {
        val c = readOne { boundary(1, 0, 0, 0, 10, 0, 10, 10, 0, 0) } // triangle, closed
        assertEquals(0, c.rects.size)
        assertEquals(listOf(0, 0, 10, 0, 10, 10), c.poly(0))
        assertEquals(3, c.polygons.pointCount(0))
    }

    @Test fun boundaryWithoutClosingPointKept() {
        val c = readOne { boundary(1, 0, 0, 0, 10, 0, 10, 10) }
        assertEquals(listOf(0, 0, 10, 0, 10, 10), c.poly(0))
    }

    @Test fun degenerateBoundariesIgnored() {
        assertEquals(0, readOne { boundary(1, 0, 0, 0, 10, 0, 0, 0) }.shapeCount)
    }

    @Test fun axisAlignedQuadBecomesRectInAllOrders() {
        val c = readOne {
            boundary(1, 0, 0, 0, 10, 0, 10, 5, 0, 5, 0, 0) // counter-clockwise, horizontal first
            boundary(1, 0, 0, 0, 0, 5, 10, 5, 10, 0, 0, 0) // clockwise, vertical first
            boundary(1, 0, 10, 5, 0, 5, 0, 0, 10, 0, 10, 5) // starting elsewhere
            boundary(1, 0, 10, 5, 10, 0, 0, 0, 0, 5, 10, 5)
        }
        assertEquals(4, c.rects.size)
        assertEquals(0, c.polygons.size)
        for (i in 0 until 4) assertEquals(listOf(0, 0, 10, 5), c.rect(i))
    }

    @Test fun nonRectQuadStaysPolygon() {
        val c = readOne { boundary(1, 0, 0, 0, 10, 0, 12, 5, 0, 5, 0, 0) }
        assertEquals(0, c.rects.size)
        assertEquals(listOf(0, 0, 10, 0, 12, 5, 0, 5), c.poly(0))
    }

    @Test fun boxRecordBecomesRect() {
        val lib = read(w().box(7, 3, 0, 0, 20, 0, 20, 30, 0, 30, 0, 0).finish().bytes())
        assertEquals(listOf(0, 0, 20, 30), lib.cells.single().rect(0))
        assertEquals(listOf(LayerKey(7, 3)), lib.layers)
    }

    @Test fun textAndNodeIgnored() {
        val c = readOne {
            text(1, 5, 5, "hello")
            rec(0x15, 0); rec(0x0D, 2, byteArrayOf(0, 1)); rec(0x2A, 2, byteArrayOf(0, 0)); rec(0x10, 3, ByteArray(8)); rec(0x11, 0)
        }
        assertEquals(0, c.shapeCount)
        assertEquals(0, c.refs.size)
    }

    // ---- Paths ----

    private fun path(type: Int, width: Int, bgn: Int, end: Int, vararg xy: Int): Cell =
        readOne { path(1, 0, width, type, bgn, end, *xy) }

    @Test fun straightPathsBecomeRects() {
        assertEquals(listOf(0, -5, 100, 5), path(0, 10, 0, 0, 0, 0, 100, 0).rect(0))
        assertEquals(listOf(-5, -5, 105, 5), path(2, 10, 0, 0, 0, 0, 100, 0).rect(0))
        assertEquals(listOf(-3, -5, 107, 5), path(4, 10, 3, 7, 0, 0, 100, 0).rect(0))
        assertEquals(listOf(-5, 0, 5, 100), path(0, 10, 0, 0, 0, 0, 0, 100).rect(0))
        assertEquals(listOf(-5, -5, 5, 105), path(2, 10, 0, 0, 0, 0, 0, 100).rect(0))
        assertEquals(listOf(-5, -3, 5, 107), path(4, 10, 3, 7, 0, 0, 0, 100).rect(0))
        assertEquals(0, path(0, 10, 0, 0, 0, 0, 100, 0).paths.size)
    }

    @Test fun reversedPathDirection() {
        // Begin is at x = 100, so the begin extension (3) reaches to 103 and the end extension (7) to -7.
        assertEquals(listOf(-7, -5, 103, 5), path(4, 10, 3, 7, 100, 0, 0, 0).rect(0))
        assertEquals(listOf(-5, -5, 105, 5), path(2, 10, 0, 0, 100, 0, 0, 0).rect(0))
        assertEquals(listOf(-5, 0, 5, 100), path(0, 10, 0, 0, 0, 100, 0, 0).rect(0))
    }

    @Test fun negativeWidthIsAbsolute() {
        assertEquals(listOf(0, -5, 100, 5), path(0, -10, 0, 0, 0, 0, 100, 0).rect(0))
        val c = path(0, -7, 0, 0, 0, 0, 100, 0) // odd width: stays a path
        assertEquals(1, c.paths.size)
        assertEquals(7, c.paths.width[0])
    }

    @Test fun pathsThatStayPaths() {
        val odd = path(0, 7, 0, 0, 0, 0, 100, 0)
        assertEquals(1, odd.paths.size); assertEquals(0, odd.rects.size)

        val round = path(1, 10, 0, 0, 0, 0, 100, 0)
        assertEquals(1, round.paths.size)
        assertEquals(1, round.paths.type[0])

        val multi = path(2, 10, 0, 0, 0, 0, 100, 0, 100, 50)
        assertEquals(1, multi.paths.size)
        assertEquals(listOf(0, 0, 100, 0, 100, 50), multi.paths.xy.toList())
        assertEquals(3, multi.paths.pointCount(0))

        assertEquals(1, path(0, 10, 0, 0, 0, 0, 100, 100).paths.size) // diagonal

        val ext = path(4, 10, 3, 9, 0, 0, 100, 100)
        assertEquals(3, ext.paths.beginExtension[0])
        assertEquals(9, ext.paths.endExtension[0])
        assertEquals(10, ext.paths.width[0])
        assertEquals(4, ext.paths.type[0])
    }

    // ---- References ----

    private fun refLib(f: TestGdsWriter.() -> Unit): Library =
        read(TestGdsWriter().header().bgnlib().libname("L").units().bgnstr("CHILD").endstr().bgnstr("TOP").apply(f).finish().bytes())

    private fun Library.top() = cell("TOP")!!

    @Test fun srefWithMirrorMagAngle() {
        val lib = refLib { sref("CHILD", 10, 20, mirror = true, mag = 2.0, angle = 90.0) }
        val r = lib.top().refs
        assertEquals(1, r.size)
        assertEquals(lib.cell("CHILD")!!.index, r.cell[0])
        assertTrue(r.extra[0] >= 0)
        assertTrue(r.mirrored(0))
        assertEquals(2.0, r.magnification(0), 0.0)
        assertEquals(90.0, r.angleDegrees(0), 0.0)
        val t = r.transform(0)
        // mirror about x, scale 2, rotate 90, move by (10, 20)
        assertEquals(10.0, t.mapX(1.0, 0.0), 1e-9); assertEquals(22.0, t.mapY(1.0, 0.0), 1e-9)
        assertEquals(12.0, t.mapX(0.0, 1.0), 1e-9); assertEquals(20.0, t.mapY(0.0, 1.0), 1e-9)
    }

    @Test fun simpleOrientationStorage() {
        val lib = refLib {
            sref("CHILD", 1, 2)
            sref("CHILD", 3, 4, angle = 90.0)
            sref("CHILD", 5, 6, angle = 180.0)
            sref("CHILD", 7, 8, angle = 270.0)
            sref("CHILD", 9, 10, mirror = true, angle = 90.0)
            sref("CHILD", 11, 12, mag = 1.0)
            sref("CHILD", 13, 14, angle = -90.0)
            sref("CHILD", 15, 16, angle = 45.0)
            sref("CHILD", 17, 18, mag = 0.5)
        }
        val r = lib.top().refs
        for (i in 0..6) assertEquals(-1, r.extra[i])
        assertEquals(listOf(0.0, 90.0, 180.0, 270.0, 90.0, 0.0, 270.0), (0..6).map { r.angleDegrees(it) })
        assertTrue(r.mirrored(4))
        assertTrue(!r.mirrored(3))
        assertTrue(r.extra[7] >= 0) // 45 degrees
        assertTrue(r.extra[8] >= 0) // magnification
        assertEquals(1, r.columns(0))
        // Rotating (1, 0) by 270 degrees gives (0, -1); then move by (7, 8).
        val t = r.transform(3)
        assertEquals(7.0, t.mapX(1.0, 0.0), 1e-9); assertEquals(7.0, t.mapY(1.0, 0.0), 1e-9)
    }

    @Test fun arefStepsAndInstanceTransform() {
        // Origin (100, 200), 3 columns, 2 rows, slightly tilted steps.
        val lib = refLib { aref("CHILD", 3, 2, 100, 200, 100 + 3 * 50, 200 + 3 * 5, 100 - 2 * 10, 200 + 2 * 70) }
        val r = lib.top().refs
        assertEquals(1, r.size)
        assertTrue(r.isArray(0))
        assertEquals(3, r.columns(0)); assertEquals(2, r.rows(0))
        assertEquals(6L, r.instanceCount(0))
        assertEquals(50.0, r.columnStepX(0), 1e-12); assertEquals(5.0, r.columnStepY(0), 1e-12)
        assertEquals(-10.0, r.rowStepX(0), 1e-12); assertEquals(70.0, r.rowStepY(0), 1e-12)
        val t = r.transform(0, 2, 1)
        assertEquals(100.0 + 100.0 - 10.0, t.tx, 1e-9)
        assertEquals(200.0 + 10.0 + 70.0, t.ty, 1e-9)
    }

    @Test fun arefFractionalStepsNotRounded() {
        val lib = refLib { aref("CHILD", 3, 1, 0, 0, 10, 0, 0, 5) }
        assertEquals(10.0 / 3, lib.top().refs.columnStepX(0), 1e-12)
    }

    @Test fun arefWithTooFewPointsIgnored() {
        assertEquals(0, refLib { aref("CHILD", 3, 2, 0, 0) }.top().refs.size)
    }

    @Test fun undefinedReferencesDroppedAndCellsRenumbered() {
        val lib = read(
            TestGdsWriter().header().bgnlib().libname("L").units()
                .bgnstr("A").sref("MISSING", 0, 0).sref("B", 1, 1).aref("MISSING", 2, 2, 0, 0, 10, 0, 0, 10).endstr()
                .bgnstr("B").endstr()
                .endlib().bytes(),
        )
        assertEquals(listOf("A", "B"), lib.cells.map { it.name })
        val refs = lib.cell("A")!!.refs
        assertEquals(1, refs.size)
        assertEquals(1, refs.cell[0])
        assertEquals(1, refs.x[0])
        assertEquals(listOf("A"), lib.topCells.map { it.name })
        assertNull(lib.cell("MISSING"))
    }

    @Test fun forwardReferences() {
        val lib = read(
            TestGdsWriter().header().bgnlib().libname("L").units()
                .bgnstr("TOP").sref("LATER", 5, 6).endstr()
                .bgnstr("LATER").boundary(1, 0, 0, 0, 4, 0, 4, 4, 0, 4, 0, 0).endstr()
                .endlib().bytes(),
        )
        assertEquals(listOf("TOP", "LATER"), lib.cells.map { it.name })
        val r = lib.cell("TOP")!!.refs
        assertEquals(1, r.cell[0])
        assertEquals(1, lib.cells[r.cell[0]].rects.size)
    }

    @Test fun duplicateCellDefinitionsMerged() {
        val lib = read(
            TestGdsWriter().header().bgnlib().libname("L").units()
                .bgnstr("A").boundary(1, 0, 0, 0, 4, 0, 4, 4, 0, 4, 0, 0).endstr()
                .bgnstr("B").endstr()
                .bgnstr("A").boundary(1, 0, 10, 10, 14, 10, 14, 14, 10, 14, 10, 10).endstr()
                .endlib().bytes(),
        )
        assertEquals(listOf("A", "B"), lib.cells.map { it.name })
        assertEquals(2, lib.cell("A")!!.rects.size)
    }

    // ---- Layers ----

    @Test fun layersSortedAndRemapped() {
        val sq = intArrayOf(0, 0, 4, 0, 4, 4, 0, 4, 0, 0)
        val lib = read(
            w().boundary(5, 0, *sq).boundary(1, 0, *sq).boundary(3, 1, *sq).boundary(1, 0, 0, 0, 8, 0, 8, 8, 0, 8, 0, 0)
                .path(3, 0, 10, 0, 0, 0, 0, 0, 100, 100)
                .finish().bytes(),
        )
        assertEquals(listOf(LayerKey(1, 0), LayerKey(3, 0), LayerKey(3, 1), LayerKey(5, 0)), lib.layers)
        val c = lib.cells.single()
        assertEquals(
            listOf(LayerKey(5, 0), LayerKey(1, 0), LayerKey(3, 1), LayerKey(1, 0)),
            (0 until 4).map { lib.layers[c.rects.layer[it]] },
        )
        assertEquals(LayerKey(3, 0), lib.layers[c.paths.layer[0]])
    }

    // ---- Errors ----

    private fun message(bytes: ByteArray): String = assertFailsWith<GdsFormatException> { read(bytes) }.message!!

    @Test fun emptyAndNotGds() {
        assertEquals("This is not a GDSII file", message(ByteArray(0)))
        assertEquals("This is not a GDSII file", message(ByteArray(64)))
        assertEquals("This is not a GDSII file", message("hello world, this is text".toByteArray()))
        assertEquals("This is not a GDSII file", message(TestGdsWriter().apply { rec(0x05, 2, ByteArray(24)) }.bytes()))
    }

    @Test fun truncatedFiles() {
        val bytes = w().boundary(1, 0, 0, 0, 10, 0, 10, 5, 0, 5, 0, 0).finish().bytes()
        assertEquals("File ends unexpectedly", message(bytes.copyOf(bytes.size - 1)))
        assertEquals("File ends unexpectedly", message(bytes.copyOf(bytes.size - 5))) // ENDLIB cut in the header
        assertEquals("File ends unexpectedly", message(bytes.copyOf(7)))
        assertEquals("File ends unexpectedly", message(bytes.copyOf(3)))
        assertEquals("File ends unexpectedly", message(bytes.copyOf(1)))
    }

    @Test fun invalidRecordLength() {
        val bytes = TestGdsWriter().header().bgnlib().bytes() + byteArrayOf(0, 2, 0x10, 3)
        assertEquals("Invalid record length 2", message(bytes))
    }

    @Test fun malformedRecordsReportName() {
        assertEquals("Record XY is too short or malformed", message(w().apply { rec(0x08, 0); rec(0x10, 3, ByteArray(6)) }.finish().bytes()))
        assertEquals("Record LAYER is too short or malformed", message(w().apply { rec(0x08, 0); rec(0x0D, 2, ByteArray(1)) }.finish().bytes()))
        assertEquals("Record UNITS is too short or malformed", message(TestGdsWriter().header().bgnlib().apply { rec(0x03, 5, ByteArray(8)) }.bytes()))
        assertEquals("Record WIDTH is too short or malformed", message(w().apply { rec(0x09, 0); rec(0x0F, 3, ByteArray(2)) }.finish().bytes()))
    }

    @Test fun cancellation() {
        val bytes = w().boundary(1, 0, 0, 0, 10, 0, 10, 5, 0, 5, 0, 0).finish().bytes()
        // Polled at least every 4096 records, so a tiny file may finish; a big one must be stopped.
        val big = manyRectangles(20000) // 100k+ records
        var polls = 0
        assertFailsWith<CancellationException> { GdsReader.read(ByteArrayInputStream(big), isCancelled = { ++polls > 3 }) }
        assertTrue(polls in 4..40, "polls=$polls")
        assertEquals(1, GdsReader.read(ByteArrayInputStream(bytes), isCancelled = { false }).cells.size)
    }

    // ---- Large files, buffer boundaries, progress ----

    private fun manyRectangles(n: Int): ByteArray {
        val g = TestGdsWriter().header().bgnlib().libname("BIG").units().bgnstr("TOP")
        for (i in 0 until n) g.boundary(i % 7, 0, i, 0, i + 3, 0, i + 3, 5, i, 5, i, 0)
        return g.finish().bytes()
    }

    /** Polygon with [n] distinct points (deterministic from [seed]), closing point appended. */
    private fun bigPolygon(n: Int, seed: Int): IntArray {
        val xy = IntArray(2 * (n + 1))
        for (i in 0 until n) {
            xy[2 * i] = seed * 100000 + i * 3
            xy[2 * i + 1] = (i * 7919L % 10007).toInt() + seed
        }
        xy[2 * n] = xy[0]; xy[2 * n + 1] = xy[1]
        return xy
    }

    @Test fun recordsSpanningBufferBoundaries() {
        val g = TestGdsWriter().header().bgnlib().libname("BIG").units().bgnstr("TOP")
        val sizes = IntArray(60) { 7000 + 17 * it } // ~64 KB per XY record, ~3.8 MB in total (buffer is 1 MB)
        for ((k, n) in sizes.withIndex()) {
            g.boundary(1 + k % 3, 0, *bigPolygon(n, k))
            // A few small shapes shift the alignment relative to the buffer.
            repeat(k % 4) { g.boundary(9, 0, 0, 0, 5, 0, 5, 5, 0, 5, 0, 0) }
            g.sref("TOP", k, -k)
        }
        val bytes = g.finish().bytes()
        assertTrue(bytes.size > 3_000_000)
        for (chunk in listOf(Int.MAX_VALUE, 65536, 1000, 777)) {
            val lib = GdsReader.read(if (chunk == Int.MAX_VALUE) ByteArrayInputStream(bytes) else SlowStream(bytes, chunk))
            val c = lib.cells.single()
            assertEquals(sizes.size, c.polygons.size)
            for ((k, n) in sizes.withIndex()) {
                assertEquals(bigPolygon(n, k).copyOf(2 * n).toList(), c.poly(k))
                assertEquals(LayerKey(1 + k % 3, 0), lib.layers[c.polygons.layer[k]])
            }
            assertEquals(sizes.indices.sumOf { it % 4 }, c.rects.size)
            assertEquals(sizes.size, c.refs.size)
            for (k in sizes.indices) { assertEquals(k, c.refs.x[k]); assertEquals(-k, c.refs.y[k]) }
        }
    }

    @Test fun gzipWithSlowSource() {
        val c = GdsReader.read(SlowStream(gzip(manyRectangles(30000)), 500)).cells.single()
        assertEquals(30000, c.rects.size)
        assertEquals(listOf(0, 0, 3, 5), c.rect(0))
        assertEquals(listOf(29999, 0, 30002, 5), c.rect(29999))
    }

    @Test fun progressIncreasesAndStaysWithinSize() {
        val bytes = manyRectangles(100000) // ~6.4 MB
        assertTrue(bytes.size > 5_000_000)
        val seen = ArrayList<Long>()
        GdsReader.read(SlowStream(bytes, 200_000), onProgress = { seen += it })
        assertTrue(seen.size >= 4, "calls=${seen.size}")
        for (i in 1 until seen.size) assertTrue(seen[i] > seen[i - 1])
        assertTrue(seen.last() <= bytes.size)

        // For gzip the numbers count compressed bytes.
        val gz = gzip(bytes)
        val seenGz = ArrayList<Long>()
        GdsReader.read(SlowStream(gz, 50_000), onProgress = { seenGz += it })
        for (i in 1 until seenGz.size) assertTrue(seenGz[i] > seenGz[i - 1])
        assertTrue(seenGz.isEmpty() || seenGz.last() <= gz.size)
    }

    // ---- Throughput (printed; only a very generous bound is asserted) ----

    @Test fun throughput() {
        val cells = 100
        val perCell = 8000
        val g = TestGdsWriter().header().bgnlib().libname("PERF").units()
        for (c in 0 until cells) {
            g.bgnstr("C$c")
            for (i in 0 until perCell) {
                val x = i * 10; val y = c * 10
                g.boundary(i % 5, 0, x, y, x + 8, y, x + 8, y + 6, x, y + 6, x, y)
            }
            repeat(200) { g.path(6, 0, 4, 0, 0, 0, it * 10, 0, it * 10 + 50, 30) }
            repeat(50) { g.sref("C${(c + 1) % cells}", it, c) }
            g.endstr()
        }
        val bytes = g.endlib().bytes()
        val mb = bytes.size / 1e6
        val start = System.nanoTime()
        val lib = read(bytes)
        val seconds = (System.nanoTime() - start) / 1e9
        println(
            "GdsReader throughput: %.1f MB in %.2f s = %.1f MB/s (%d cells, %d rects)"
                .format(mb, seconds, mb / seconds, lib.cells.size, lib.cells.sumOf { it.rects.size }),
        )
        assertEquals(cells * perCell, lib.cells.sumOf { it.rects.size })
        assertEquals(cells * 200, lib.cells.sumOf { it.paths.size })
        assertTrue(seconds < 30.0, "reading took $seconds s")
    }

    @Test
    fun decodeReal8RoundTrips() {
        for (v in doubleArrayOf(1.0, 0.001, 1e-9, -2.5, 0.0)) {
            val decoded = GdsReader.decodeReal8(com.example.gdsviewer.gds.encodeReal8(v), 0)
            if (v == 0.0) {
                org.junit.Assert.assertEquals(0.0, decoded, 0.0)
            } else {
                org.junit.Assert.assertTrue("$v -> $decoded", Math.abs(decoded - v) / Math.abs(v) < 1e-15)
            }
        }
    }
}
