package io.github.dormail.gdsviewer.scene

import io.github.dormail.gdsviewer.assertEquals
import io.github.dormail.gdsviewer.assertNotNull
import io.github.dormail.gdsviewer.assertNull
import io.github.dormail.gdsviewer.assertTrue
import io.github.dormail.gdsviewer.db.Library
import java.util.Random
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.junit.Test

class LibraryIndexTest {
    /** Every shape of [cell] must be in exactly one chunk. */
    private fun assertPartition(library: Library, chunks: List<Chunk>, cell: Int) {
        val c = library.cells[cell]
        val seenR = IntArray(c.rects.size); val seenP = IntArray(c.polygons.size); val seenW = IntArray(c.paths.size)
        for (ch in chunks) {
            ch.rects?.forEach { seenR[it]++ }
            ch.polygons?.forEach { seenP[it]++ }
            ch.paths?.forEach { seenW[it]++ }
            // Member arrays sorted ascending.
            for (a in listOfNotNull(ch.rects, ch.polygons, ch.paths)) for (i in 1 until a.size) assertTrue(a[i - 1] < a[i])
        }
        assertTrue(seenR.all { it == 1 } && seenP.all { it == 1 } && seenW.all { it == 1 }, "every shape in exactly one chunk")
        assertEquals(c.shapeCount, chunks.sumOf { it.shapeCount })
    }

    private fun estimatedVertices(library: Library, ch: Chunk): Long {
        val c = library.cells[ch.cell]
        var v = 4L * (ch.rects?.size ?: 0)
        ch.polygons?.forEach { v += c.polygons.pointCount(it) }
        ch.paths?.forEach { v += 8L * c.paths.pointCount(it) + 16 }
        return v
    }

    @Test fun smallCellIsOneChunkWithAllMembers() {
        val l = lib {
            val c = beginCell("A")
            for (i in 0 until 100) c.addBox(layerIndex(1, 0), i * 10, 0, i * 10 + 5, 5)
            c.addPolygon(layerIndex(2, 0), intArrayOf(0, 0, 10, 0, 0, 10), 3)
        }
        val idx = LibraryIndex(l)
        val content = idx.content(0)
        assertEquals(1, content.chunks.size)
        val ch = content.chunks[0]
        assertNull(ch.rects); assertNull(ch.polygons); assertNull(ch.paths)
        assertEquals(101, ch.shapeCount)
        assertEquals(0, ch.id)
        assertTrue(ch.bounds.contains(Box(0.0, 0.0, 995.0, 10.0)))
        assertEquals(995.0, ch.bounds.width, 1e-9)
        assertEquals(2, ch.layerBounds.size)
        assertEquals(498, ch.originX); // rounded center of 0..995
        assertEquals(5.0, ch.typicalShapeSize, 1e-9)
    }

    @Test fun emptyCellHasNoChunks() {
        val l = lib { beginCell("A") }
        val c = LibraryIndex(l).content(0)
        assertEquals(0, c.chunks.size); assertEquals(0, c.refBuckets.size)
    }

    @Test fun manyRandomRectsArePartitioned() {
        val rnd = Random(1)
        val l = lib {
            val c = beginCell("A")
            for (i in 0 until 50_000) {
                val x = rnd.nextInt(1_000_000); val y = rnd.nextInt(1_000_000)
                c.addBox(layerIndex(rnd.nextInt(3), 0), x, y, x + 1 + rnd.nextInt(100), y + 1 + rnd.nextInt(100))
            }
        }
        val chunks = LibraryIndex(l).content(0).chunks
        println("50k random rects -> ${chunks.size} chunks, largest ${chunks.maxOf { it.shapeCount }}")
        assertTrue(chunks.size > 10)
        assertPartition(l, chunks, 0)
        for (ch in chunks) {
            assertTrue(ch.shapeCount <= 4096)
            assertTrue(estimatedVertices(l, ch) <= 65535)
            assertTrue(ch.rects != null)
            // Bounds are the union of the member boxes: every member lies within.
            for (i in ch.rects!!) {
                val xy = l.cells[0].rects.xy
                assertTrue(ch.bounds.contains(Box(xy[4 * i].toDouble(), xy[4 * i + 1].toDouble(), xy[4 * i + 2].toDouble(), xy[4 * i + 3].toDouble())))
            }
        }
        assertEquals(chunks.indices.toList(), chunks.map { it.id })
    }

    @Test fun denseClusterIsSplitRecursively() {
        val rnd = Random(2)
        val l = lib {
            val c = beginCell("A")
            val layer = layerIndex(1, 0)
            // One far-away rectangle makes the grid coarse; 30k rectangles sit in a tiny area.
            c.addBox(layer, 1_000_000, 1_000_000, 1_000_010, 1_000_010)
            for (i in 0 until 30_000) {
                val x = rnd.nextInt(2000); val y = rnd.nextInt(2000)
                c.addBox(layer, x, y, x + 1 + rnd.nextInt(3), y + 1 + rnd.nextInt(3))
            }
        }
        val chunks = LibraryIndex(l).content(0).chunks
        println("dense cluster -> ${chunks.size} chunks, largest ${chunks.maxOf { it.shapeCount }}")
        assertPartition(l, chunks, 0)
        assertTrue(chunks.all { it.shapeCount <= 4096 })
    }

    @Test fun vertexLimitSplitsCellsWithFewButBigPolygons() {
        val rnd = Random(3)
        val l = lib {
            val c = beginCell("A")
            val layer = layerIndex(1, 0)
            // 1000 polygons with 100 points each = 100k vertices, but only 1000 shapes.
            for (i in 0 until 1000) {
                val pts = IntArray(200)
                for (k in 0 until 100) {
                    val a = 2 * Math.PI * k / 100
                    pts[2 * k] = (i * 1000 + 300 * Math.cos(a)).toInt(); pts[2 * k + 1] = (rnd.nextInt(10) * 1000 + 300 * Math.sin(a)).toInt()
                }
                c.addPolygon(layer, pts, 100)
            }
        }
        val chunks = LibraryIndex(l).content(0).chunks
        assertTrue(chunks.size > 1)
        assertPartition(l, chunks, 0)
        assertTrue(chunks.all { estimatedVertices(l, it) <= 65535 })
    }

    @Test fun placementBucketsPartitionPlacements() {
        val rnd = Random(4)
        val l = lib {
            beginCell("C").addBox(layerIndex(1, 0), 0, 0, 10, 10)
            beginCell("Empty")
            val p = beginCell("P")
            for (i in 0 until 10_000) p.addRef(cellIndex("C"), rnd.nextInt(100_000), rnd.nextInt(100_000))
            p.addRef(cellIndex("Empty"), 5, 5) // excluded: no bounds
            p.addArray(cellIndex("C"), 0, 0, false, 1.0, 0.0, 10, 10, 20.0, 0.0, 0.0, 20.0)
        }
        val buckets = LibraryIndex(l).content(2).refBuckets
        val refs = l.cells[2].refs
        val seen = IntArray(refs.size)
        for (b in buckets) {
            assertTrue(b.refs.size <= 2 * 256 + 256)
            for (i in 1 until b.refs.size) assertTrue(b.refs[i - 1] < b.refs[i])
            for (r in b.refs) {
                seen[r]++
                val child = Box(0.0, 0.0, 10.0, 10.0)
                val t = refs.transform(r)
                var box = child.transformed(t)
                if (refs.isArray(r)) box = box.union(child.transformed(refs.transform(r, 9, 9)))
                assertTrue(b.bounds.contains(box))
            }
        }
        for (i in 0 until refs.size) assertEquals(if (i == 10_000) 0 else 1, seen[i])
        println("10k placements -> ${buckets.size} buckets")
        assertTrue(buckets.size in 20..120)
    }

    @Test fun fewPlacementsAreOneBucket() {
        val l = lib {
            beginCell("C").addBox(layerIndex(1, 0), 0, 0, 10, 10)
            val p = beginCell("P")
            for (i in 0 until 200) p.addRef(cellIndex("C"), i * 100, 0)
        }
        val b = LibraryIndex(l).content(1).refBuckets
        assertEquals(1, b.size); assertEquals(200, b[0].refs.size)
        assertEquals(19910.0, b[0].bounds.maxX, 1e-9)
    }

    @Test fun contentIsCachedAndChunkIdsUniqueUnderConcurrency() {
        val rnd = Random(5)
        val l = lib {
            val layer = layerIndex(1, 0)
            for (k in 0 until 40) {
                val c = beginCell("C$k")
                for (i in 0 until 5000) { val x = rnd.nextInt(100000); c.addBox(layer, x, x, x + 5, x + 5) }
            }
        }
        val idx = LibraryIndex(l)
        val pool = Executors.newFixedThreadPool(8)
        val tasks = (0 until 200).map { n -> Callable { idx.content(n % 40) } }
        val results = pool.invokeAll(tasks).map { it.get() }
        pool.shutdown()
        for (n in results.indices) assertTrue(results[n] === idx.content(n % 40))
        val ids = (0 until 40).flatMap { idx.content(it).chunks.map { c -> c.id } }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals((0 until ids.size).toSet(), ids.toSet()) // 0, 1, 2, ... without gaps
    }

    @Test fun defaultTopCellIsLargestTopCell() {
        val l = lib {
            beginCell("Small").addBox(layerIndex(1, 0), 0, 0, 10, 10)
            beginCell("Empty")
            val big = beginCell("Big"); big.addBox(layerIndex(1, 0), 0, 0, 100, 100)
            big.addRef(cellIndex("Sub"), 0, 0)
            beginCell("Sub").addBox(layerIndex(1, 0), 0, 0, 500, 500)
        }
        assertEquals("Big", assertNotNull(LibraryIndex(l).defaultTopCell()).name)
        assertNull(LibraryIndex(lib { }).defaultTopCell())
        assertEquals("E", assertNotNull(LibraryIndex(lib { beginCell("E") }).defaultTopCell()).name)
    }
}
