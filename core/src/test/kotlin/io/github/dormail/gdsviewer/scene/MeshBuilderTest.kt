package io.github.dormail.gdsviewer.scene

import io.github.dormail.gdsviewer.assertEquals
import io.github.dormail.gdsviewer.assertTrue
import java.util.Random
import org.junit.Test

class MeshBuilderTest {
    private fun meshOf(l: io.github.dormail.gdsviewer.db.Library, cell: Int = 0): ChunkMesh {
        val chunk = LibraryIndex(l).content(cell).chunks.single()
        return MeshBuilder.build(l, chunk)
    }

    private fun checkConsistent(m: ChunkMesh) {
        assertEquals(m.layers.size + 1, m.triangleStart.size)
        assertEquals(m.layers.size + 1, m.lineStart.size)
        assertEquals(0, m.triangleStart[0]); assertEquals(0, m.lineStart[0])
        assertEquals(m.triangles.size, m.triangleStart.last()); assertEquals(m.lines.size, m.lineStart.last())
        for (k in 0 until m.layers.size) {
            assertTrue(m.triangleStart[k] <= m.triangleStart[k + 1] && m.lineStart[k] <= m.lineStart[k + 1])
            if (k > 0) assertTrue(m.layers[k - 1] < m.layers[k])
        }
        assertEquals(0, m.triangles.size % 3); assertEquals(0, m.lines.size % 2)
        assertTrue(m.triangles.all { it in 0 until m.vertexCount } && m.lines.all { it in 0 until m.vertexCount })
    }

    @Test fun rectsGroupedByLayerWithAreaAndOutline() {
        val rnd = Random(1)
        var areaA = 0.0; var areaB = 0.0
        val l = lib {
            val c = beginCell("A")
            val a = layerIndex(7, 0); val b = layerIndex(3, 0) // b sorts before a
            for (i in 0 until 500) {
                val x = rnd.nextInt(10000); val y = rnd.nextInt(10000); val w = 1 + rnd.nextInt(50); val h = 1 + rnd.nextInt(50)
                if (i % 2 == 0) { c.addBox(a, x, y, x + w, y + h); areaA += w * h } else { c.addBox(b, x, y, x + w, y + h); areaB += w * h }
            }
        }
        val m = meshOf(l)
        checkConsistent(m)
        assertEquals(listOf(0, 1), m.layers.toList()) // sorted layer indices
        assertEquals(areaB, triangleArea(m, 0), 1.0) // layer index 0 = (3, 0)
        assertEquals(areaA, triangleArea(m, 1), 1.0)
        assertEquals(500 * 4 * 2, m.lines.size)
        assertEquals(500 * 6, m.triangles.size)
        assertEquals(500 * 4, m.vertexCount)
        assertEquals(250 * 8, m.lineStart[1])
    }

    @Test fun verticesAreRelativeToChunkOrigin() {
        val l = lib {
            beginCell("A").addBox(layerIndex(1, 0), 1_000_000, 2_000_000, 1_000_100, 2_000_050)
        }
        val chunk = LibraryIndex(l).content(0).chunks.single()
        assertEquals(1_000_050, chunk.originX); assertEquals(2_000_025, chunk.originY)
        val m = MeshBuilder.build(l, chunk)
        assertEquals(listOf(-50f, -25f, 50f, -25f, 50f, 25f, -50f, 25f), m.vertices.toList())
    }

    @Test fun polygonsConvexAndConcave() {
        val l = lib {
            val c = beginCell("A")
            val layer = layerIndex(1, 0)
            // L shape (concave), area 3 * 100 * 100.
            c.addPolygon(layer, intArrayOf(0, 0, 200, 0, 200, 100, 100, 100, 100, 200, 0, 200), 6)
            // Convex hexagon far away (clockwise), area: trapezoid pair.
            c.addPolygon(layer, intArrayOf(1000, 0, 1000, 100, 1100, 150, 1200, 100, 1200, 0, 1100, -50), 6)
        }
        val m = meshOf(l)
        checkConsistent(m)
        assertEquals(1, m.layers.size)
        assertEquals(30000.0 + 30000.0, triangleArea(m, 0), 1e-3) // L: 3 squares; hexagon: 200x100 + two triangles of 5000
        assertEquals(12, m.lines.size / 2) // one outline segment per edge
        assertEquals(12, m.vertexCount)
        assertEquals(4 + 4, m.triangles.size / 3)
    }

    @Test fun degeneratePolygonHasOutlineOnly() {
        val l = lib {
            // Three collinear points: zero area.
            beginCell("A").addPolygon(layerIndex(1, 0), intArrayOf(0, 0, 10, 10, 20, 20), 3)
        }
        val m = meshOf(l)
        assertEquals(0, m.triangles.size); assertEquals(3, m.lines.size / 2)
    }

    @Test fun pathProducesFillAndOutline() {
        val l = lib {
            // L-shaped path of width 20: two segments (each 2 triangles) + a join; layer sorted index 0.
            beginCell("A").addPath(layerIndex(1, 0), 20, 0, 0, 0, intArrayOf(0, 0, 100, 0, 100, 100), 3)
        }
        val m = meshOf(l)
        checkConsistent(m)
        assertTrue(m.triangles.size >= 12)
        // Total fill = two 100 x 20 bars + a 10 x 10 miter corner piece: 4000 - overlap handled by the join, so check range.
        val area = triangleArea(m, 0)
        assertTrue(area in 3900.0..4300.0, "area $area")
        assertTrue(m.lines.size >= 12)
        // Everything lies within the expanded bounding box (relative to origin).
        val chunk = m.chunk
        for (i in 0 until m.vertexCount) {
            assertTrue(m.vertices[2 * i] + chunk.originX in -1f..111f)
            assertTrue(m.vertices[2 * i + 1] + chunk.originY in -11f..101f)
        }
    }

    @Test fun mixedKindsShareLayerRange() {
        val l = lib {
            val c = beginCell("A")
            val layer = layerIndex(1, 0)
            c.addBox(layer, 0, 0, 10, 10)
            c.addPolygon(layer, intArrayOf(0, 100, 50, 100, 0, 150), 3)
            c.addPath(layer, 10, 0, 0, 0, intArrayOf(0, 200, 100, 200, 100, 300), 3)
        }
        val m = meshOf(l)
        checkConsistent(m)
        assertEquals(1, m.layers.size)
        assertEquals(m.triangles.size, m.triangleStart[1])
    }

    @Test fun splitChunksCoverTheSameArea() {
        val rnd = Random(7)
        val l = lib {
            val c = beginCell("A")
            for (i in 0 until 20_000) {
                val x = (i % 200) * 100; val y = (i / 200) * 100
                c.addBox(layerIndex(1, 0), x, y, x + 1 + rnd.nextInt(90), y + 1 + rnd.nextInt(90))
            }
        }
        val chunks = LibraryIndex(l).content(0).chunks
        assertTrue(chunks.size > 1)
        var total = 0.0
        for (ch in chunks) {
            val m = MeshBuilder.build(l, ch)
            checkConsistent(m)
            total += triangleArea(m, 0)
        }
        val rs = l.cells[0].rects
        var expected = 0.0
        for (i in 0 until rs.size) expected += (rs.xy[4 * i + 2] - rs.xy[4 * i]).toDouble() * (rs.xy[4 * i + 3] - rs.xy[4 * i + 1])
        assertEquals(expected, total, expected * 1e-6)
    }

    @Test fun timingOfMillionRects() {
        val l = lib {
            val c = beginCell("A")
            val a = layerIndex(1, 0); val b = layerIndex(2, 0)
            for (i in 0 until 1_000_000) {
                val x = (i % 1000) * 20; val y = (i / 1000) * 20
                c.addBox(if (i % 3 == 0) a else b, x, y, x + 10, y + 10)
            }
        }
        val chunk = Chunk(
            0, 0, 0, 0, Box(0.0, 0.0, 20000.0, 20000.0), LayerBoxes.EMPTY, 1_000_000, 10.0,
            null, null, null,
        )
        MeshBuilder.build(l, chunk) // warm up
        val t = System.nanoTime()
        val m = MeshBuilder.build(l, chunk)
        val ms = (System.nanoTime() - t) / 1_000_000
        println("meshing 1M rects: $ms ms (${m.byteSize shr 20} MB)")
        checkConsistent(m)
        assertEquals(4_000_000, m.vertexCount)
    }
}
