package io.github.dormail.gdsviewer.security

import io.github.dormail.gdsviewer.db.GdsReader
import io.github.dormail.gdsviewer.db.Library
import io.github.dormail.gdsviewer.db.LibraryBuilder
import io.github.dormail.gdsviewer.gds.GdsFormatException
import io.github.dormail.gdsviewer.gds.TestGdsWriter
import io.github.dormail.gdsviewer.geometry.Affine
import io.github.dormail.gdsviewer.geometry.IntList
import io.github.dormail.gdsviewer.geometry.Triangulator
import io.github.dormail.gdsviewer.scene.DrawList
import io.github.dormail.gdsviewer.scene.LibraryIndex
import io.github.dormail.gdsviewer.scene.MeshBuilder
import io.github.dormail.gdsviewer.scene.MeshCache
import io.github.dormail.gdsviewer.scene.SceneBuilder
import io.github.dormail.gdsviewer.scene.SceneRequest
import io.github.dormail.gdsviewer.scene.Snapping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Random
import java.util.concurrent.CancellationException
import java.util.zip.GZIPOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Regression tests for hostile GDS files: each one is a crafted input that used to hang or use
 * all memory (found with [UntrustedInputProbeTest]). Every test must finish quickly.
 */
class MaliciousInputTest {

    // ---------------------------------------------------------------- helpers

    private fun i16(vararg v: Int) = ByteArray(v.size * 2) { (v[it / 2] shr (if (it % 2 == 0) 8 else 0)).toByte() }
    private fun i32(vararg v: Int) = ByteArray(v.size * 4) { (v[it / 4] shr (8 * (3 - it % 4))).toByte() }

    private class G {
        val w = TestGdsWriter()
        init { w.header(); w.bgnlib(); w.libname("L"); w.units() }
        fun cell(name: String, body: TestGdsWriter.() -> Unit) { w.bgnstr(name); w.body(); w.endstr() }
        fun end(): ByteArray { w.endlib(); return w.bytes() }
    }

    private fun TestGdsWriter.box10(layer: Int = 1, x: Int = 0, y: Int = 0, s: Int = 10) =
        boundary(layer, 0, x, y, x + s, y, x + s, y + s, x, y + s, x, y)

    private fun gz(raw: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        GZIPOutputStream(o).use { it.write(raw) }
        return o.toByteArray()
    }

    private fun read(bytes: ByteArray): Library = GdsReader.read(ByteArrayInputStream(bytes))

    /** Reads the file and builds the scene for a phone-sized view that fits the top cell, like the app does. */
    private fun fitScene(bytes: ByteArray, zoom: Double = 1.0): Pair<DrawList, MeshCache> {
        val lib = read(bytes)
        val index = LibraryIndex(lib)
        val top = index.defaultTopCell()!!
        val frame = index.frame(top.index)
        val w = 1080
        val h = 2400
        val s = zoom * 0.9 * min(w / max(frame.maxX - frame.minX, 1e-3), h / max(frame.maxY - frame.minY, 1e-3))
        val view = Affine.translate(w / 2.0, h / 2.0) * Affine.scale(s, -s)
        val cache = MeshCache(lib)
        return SceneBuilder(index, cache).build(SceneRequest(frame, view, w, h)) to cache
    }

    // ---------------------------------------------------------------- 1. hierarchy loops and blow-ups

    @Test(timeout = 20_000)
    fun referenceLoopsAndFanOutDoNotHang() {
        // A cell placing itself twice: 2^64 visits without loop detection. The loops are simply not followed.
        val self = G().apply { cell("A") { box10(); sref("A", 0, 0); sref("A", 1, 1) } }.end()
        val (selfList, _) = fitScene(self)
        assertFalse(selfList.stats.budgetExceeded)
        assertEquals(1, selfList.stats.instances) // just the one box

        val mutual = G().apply {
            cell("A") { box10(); sref("B", 0, 0); sref("B", 1, 1) }
            cell("B") { box10(); sref("A", 0, 0); sref("A", 1, 1) }
        }.end()
        fitScene(mutual)

        // No loop, but 2^40 placements that overlap exactly (so nothing is "too small"): the work budget stops it.
        val dag = G().apply {
            cell("L40") { box10() }
            for (i in 39 downTo 0) cell("L$i") { sref("L${i + 1}", 0, 0); sref("L${i + 1}", 3, 3) }
        }.end()
        val (dagList, _) = fitScene(dag)
        assertTrue(dagList.stats.budgetExceeded)
        assertNotNull(dagList.raster) // what was not visited is still drawn, as raster

        // Arrays of arrays of arrays.
        val nested = G().apply {
            cell("L3") { box10() }
            for (i in 2 downTo 0) cell("L$i") { aref("L${i + 1}", 255, 255, 0, 0, 255 * 100, 0, 0, 255 * 100) }
        }.end()
        assertTrue(fitScene(nested).first.stats.budgetExceeded)
    }

    @Test(timeout = 20_000)
    fun stackedFullScreenCopiesAreNotAllSentToTheGpu() {
        // 200,000 copies of a cell on top of each other, each filling the screen: drawn one by one,
        // they would cost the GPU 200,000 full-screen fills per frame.
        val bytes = G().apply {
            cell("B") { box10() }
            cell("A") { repeat(200_000) { sref("B", 0, 0) } }
        }.end()
        val (list, _) = fitScene(bytes)
        assertTrue("budget flag", list.stats.budgetExceeded)
        assertTrue("instances ${list.stats.instances}", list.stats.instances <= 200)
    }

    // ---------------------------------------------------------------- 2. unpacked size

    @Test(timeout = 20_000)
    fun gzipBombIsRefused() {
        // 6 MB of ignorable records; with a 1 MB limit the reader must stop long before the end.
        val raw = ByteArrayOutputStream()
        raw.write(G().w.bytes())
        val record = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0x99.toByte(), 0) + ByteArray(65531)
        repeat(96) { raw.write(record) }
        val bomb = gz(raw.toByteArray())
        assertTrue(bomb.size < 100_000)
        try {
            GdsReader.read(ByteArrayInputStream(bomb), { false }, null, 1L shl 20)
            fail("expected GdsFormatException")
        } catch (e: GdsFormatException) {
            assertTrue(e.message!!, e.message!!.contains("larger than 2 GB when unpacked"))
        }
        assertEquals(2L shl 30, GdsReader.MAX_UNPACKED_BYTES)
    }

    @Test(timeout = 20_000)
    fun readingCanBeCancelledPromptly() {
        // Few, big records: the old "every 4096 records" poll would not have noticed for hundreds of MB.
        val raw = ByteArrayOutputStream()
        raw.write(G().w.bytes())
        val record = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0x99.toByte(), 0) + ByteArray(65531)
        repeat(64) { raw.write(record) }
        var polls = 0
        try {
            GdsReader.read(ByteArrayInputStream(raw.toByteArray()), { ++polls > 2 })
            fail("expected CancellationException")
        } catch (_: CancellationException) {
        }
        assertTrue(polls < 10)
    }

    // ---------------------------------------------------------------- 3. many empty cells

    @Test(timeout = 20_000)
    fun manyEmptyCellsStayCheap() {
        val g = G()
        for (i in 0 until 300_000) g.cell("C%07d".format(i)) {}
        val lib = read(g.end())
        assertEquals(300_000, lib.cells.size)
    }

    // ---------------------------------------------------------------- 4. many layers

    @Test(timeout = 20_000)
    fun tooManyLayersAreRefused() {
        val g = G()
        for (i in 0 until 20_000) g.cell("C$i") { box10(i, 0, 0, 10) }
        try {
            read(g.end())
            fail("expected GdsFormatException")
        } catch (e: GdsFormatException) {
            assertTrue(e.message!!, e.message!!.contains("more than ${LibraryBuilder.MAX_LAYERS} layers"))
        }
    }

    @Test(timeout = 20_000)
    fun manyLayersStayInBoundedMemory() {
        // 9000 layers of tiny boxes inside B; A is huge, so B's content is far too small to draw as vectors
        // and goes into the raster: one bitmap per layer would be 1.6 GB.
        val layers = 9000
        val bytes = G().apply {
            cell("B") { for (i in 0 until layers) boundary(i, 0, 0, 0, 1, 0, 1, 1, 0, 1, 0, 0) }
            cell("A") { boundary(1, 0, 0, 0, 100_000_000, 0, 100_000_000, 100_000_000, 0, 100_000_000, 0, 0); sref("B", 0, 0) }
        }.end()
        val (list, _) = fitScene(bytes)
        assertTrue(list.raster!!.layers.size <= 129)

        // And the same layers spread over many cells (per-cell work must not depend on the layer count).
        val spread = G().apply {
            for (i in 0 until layers) cell("C$i") { box10(i, 0, 0, 10) }
            cell("TOP") { for (i in 0 until layers) sref("C$i", (i % 100) * 100, (i / 100) * 100) }
        }.end()
        val (list2, cache) = fitScene(spread, zoom = 20.0)
        cache.ensure(list2.groups.map { it.chunk })
    }

    // ---------------------------------------------------------------- 5. snapping

    @Test(timeout = 20_000)
    fun snappingOverHostileArraysReturnsQuickly() {
        val zero = intArrayOf(0, 0, 0, 0, 0, 0)
        val parallel = intArrayOf(0, 0, 20000, 0, 40000, 0)
        for ((cols, steps) in listOf(65535 to zero, 20000 to parallel)) {
            val bytes = G().apply {
                // The middle of B's box (where the query is) holds no shape, so nothing is found early.
                cell("B") { box10(1, 0, 0, 10); box10(1, 1_000_000, 1_000_000, 10) }
                cell("A") { aref("B", cols, cols, *steps) }
            }.end()
            val lib = read(bytes)
            val index = LibraryIndex(lib)
            val frame = index.frame(index.defaultTopCell()!!.index)
            val t0 = System.nanoTime()
            Snapping.snap(index, frame, BooleanArray(lib.layers.size) { true }, 0.0, 0.0, 0.001)
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertTrue("snap took $ms ms", ms < 1000)
        }
        // Cancellation is honored.
        val loop = G().apply { cell("A") { box10(); sref("A", 0, 0); sref("A", 1, 1) } }.end()
        val lib = read(loop)
        val index = LibraryIndex(lib)
        try {
            Snapping.snap(index, index.frame(0), BooleanArray(lib.layers.size) { true }, 5.0, 5.0, 1.0, { true })
            fail("expected CancellationException")
        } catch (_: CancellationException) {
        }
    }

    // ---------------------------------------------------------------- 6. triangulation

    private fun randomPolygon(n: Int, seed: Long, range: Int): IntArray {
        val r = Random(seed)
        return IntArray(2 * n) { r.nextInt(range) }
    }

    @Test(timeout = 20_000)
    fun selfIntersectingPolygonIsDrawnAsOutlineOnly() {
        val xy = randomPolygon(8190, 1, 1_000_000)
        val lib = read(G().apply { cell("A") { boundary(1, 0, *xy) } }.end())
        val t0 = System.nanoTime()
        val mesh = MeshBuilder.build(lib, LibraryIndex(lib).content(0).chunks.single())
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("took $ms ms", ms < 2000)
        assertEquals(0, mesh.triangles.size)
        assertEquals(2 * mesh.vertexCount, mesh.lines.size) // the outline is all there is
        assertEquals(8190, mesh.vertexCount)
    }

    @Test(timeout = 20_000)
    fun ordinaryConcavePolygonsAreStillFilled() {
        // A 2000-point star: concave, needs real ear clipping.
        val n = 2000
        val pts = DoubleArray(2 * n)
        for (i in 0 until n) {
            val a = 2 * Math.PI * i / n
            val r = if (i % 2 == 0) 1_000_000.0 else 500_000.0
            pts[2 * i] = r * Math.cos(a); pts[2 * i + 1] = r * Math.sin(a)
        }
        var area2 = 0.0
        for (i in 0 until n) {
            val j = (i + 1) % n
            area2 += pts[2 * i] * pts[2 * j + 1] - pts[2 * j] * pts[2 * i + 1]
        }
        val out = IntList()
        Triangulator.triangulateIndices(pts, n, out, 0)
        assertEquals(3 * (n - 2), out.size)
        var tri = 0.0
        for (k in 0 until out.size step 3) {
            val a = out.data[k]; val b = out.data[k + 1]; val c = out.data[k + 2]
            tri += abs((pts[2 * b] - pts[2 * a]) * (pts[2 * c + 1] - pts[2 * a + 1]) - (pts[2 * c] - pts[2 * a]) * (pts[2 * b + 1] - pts[2 * a + 1])) / 2
        }
        assertEquals(abs(area2) / 2, tri, abs(area2) * 1e-9)
    }

    // ---------------------------------------------------------------- 7. mesh indices

    @Test(timeout = 20_000)
    fun meshIndicesStayInsideTheVertexList() {
        val bytes = G().apply {
            cell("A") {
                for (k in 0 until 50) boundary(k % 3, 0, *randomPolygon(300, k.toLong(), 100_000))
                box10(7, 0, 0, 5)
                // A long round-ended path: more than 65536 vertices in one mesh (the renderer switches to 32-bit indices there).
                val r = Random(3)
                val xy = IntArray(2 * 8000)
                var x = 0; var y = 0
                for (i in 0 until 8000) { x += r.nextInt(10_000) - 5000; y += r.nextInt(10_000) - 5000; xy[2 * i] = x; xy[2 * i + 1] = y }
                path(2, 0, 400, 1, 0, 0, *xy)
            }
        }.end()
        val lib = read(bytes)
        val index = LibraryIndex(lib)
        var biggest = 0
        for (chunk in index.content(0).chunks) {
            val m = MeshBuilder.build(lib, chunk)
            assertTrue(m.triangles.all { it in 0 until m.vertexCount })
            assertTrue(m.lines.all { it in 0 until m.vertexCount })
            assertEquals(0, m.triangles.size % 3)
            assertEquals(0, m.lines.size % 2)
            assertTrue(m.byteSize < Int.MAX_VALUE)
            biggest = max(biggest, m.vertexCount)
        }
        assertTrue("biggest mesh has $biggest vertices", biggest > 65_536)
    }

    @Test(timeout = 20_000)
    fun shapesAtTheSamePlaceAreStillCutIntoSmallChunks() {
        // Spatial splitting cannot separate identical shapes; they are cut by count so no mesh grows without limit.
        val g = G()
        g.cell("A") { for (i in 0 until 20_000) box10(1, 0, 0, 10) }
        val lib = read(g.end())
        val chunks = LibraryIndex(lib).content(0).chunks
        assertTrue(chunks.size >= 5)
        assertTrue(chunks.all { it.shapeCount <= 4096 })
        assertEquals(20_000, chunks.sumOf { it.shapeCount })
    }
}
