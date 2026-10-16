package com.example.gdsviewer.scene

import com.example.gdsviewer.assertEquals
import com.example.gdsviewer.assertFailsWith
import com.example.gdsviewer.assertNotNull
import com.example.gdsviewer.assertNull
import com.example.gdsviewer.assertTrue
import com.example.gdsviewer.db.Library
import com.example.gdsviewer.geometry.Affine
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import java.util.Random
import org.junit.Test

class SceneBuilderTest {
    private class Setup(val library: Library) {
        val index = LibraryIndex(library)
        val meshes = MeshCache(library)
        val builder = SceneBuilder(index, meshes)
        fun frame(name: String) = index.frame(library.cell(name)!!.index)
    }

    private fun request(
        frame: CellFrame, cx: Double, cy: Double, scale: Double, w: Int = 400, h: Int = 400,
        margin: Double = 0.0, lod: Double = 4.0, maxInstances: Int = 1_000_000,
    ) = SceneRequest(frame, testView(cx, cy, scale, w, h), w, h, margin, lod, 2.0, maxInstances)

    /** Total number of chunk instances (all groups). */
    private fun DrawList.total() = groups.sumOf { it.count }

    @Test fun singleCellOneGroup() {
        val s = Setup(lib { beginCell("A").addBox(layerIndex(1, 0), 0, 0, 1000, 1000) })
        val f = s.frame("A")
        val dl = s.builder.build(request(f, 0.0, 0.0, 200.0))
        assertEquals(1, dl.groups.size)
        assertEquals(1, dl.groups[0].count)
        assertEquals(1, dl.stats.instances)
        assertNull(dl.raster)
        // Mesh vertices (chunk origin + vertex) -> world: the box is 1 µm wide, centered on the origin.
        val chunk = dl.groups[0].chunk
        val mesh = assertNotNull(s.meshes.peek(chunk))
        for (v in 0 until mesh.vertexCount) {
            val wx = world(dl, dl.groups[0], 0, mesh.vertices[2 * v], mesh.vertices[2 * v + 1], true)
            val wy = world(dl, dl.groups[0], 0, mesh.vertices[2 * v], mesh.vertices[2 * v + 1], false)
            assertEquals(0.5, Math.abs(wx), 1e-5)
            assertEquals(0.5, Math.abs(wy), 1e-5)
        }
    }

    /** Applies instance [i] of [g] to a mesh vertex and adds the anchor back. */
    private fun world(dl: DrawList, g: InstanceGroup, i: Int, vx: Float, vy: Float, wantX: Boolean): Double {
        val t = g.transforms; val o = 6 * i
        return if (wantX) t[o] * vx + t[o + 2] * vy + t[o + 4] + dl.anchorX
        else t[o + 1] * vx + t[o + 3] * vy + t[o + 5] + dl.anchorY
    }

    @Test fun nestedTransformsReproduceWorldCoordinates() {
        val s = Setup(lib {
            val ly = layerIndex(1, 0)
            val a = beginCell("A")
            a.addBox(ly, 100, 50, 900, 400)
            a.addPolygon(ly, intArrayOf(1000, 0, 1500, 0, 1200, 600), 3)
            val b = beginCell("B")
            b.addRef(cellIndex("A"), 300, -200, true, 1.5, 30.0)
            b.addBox(ly, 0, 0, 10, 10)
            val t = beginCell("T")
            t.addRef(cellIndex("B"), 5000, 2000, false, 2.0, 90.0)
            t.addRef(cellIndex("B"), -4000, 1000, true, 1.0, 180.0)
        })
        val f = s.frame("T")
        val dl = s.builder.build(request(f, 0.0, 0.0, 20.0, margin = 0.25)) // everything in view
        val top = s.library.cell("T")!!; val b = s.library.cell("B")!!
        val aIdx = s.library.cell("A")!!.index
        val g = dl.groups.first { it.chunk.cell == aIdx }
        assertEquals(2, g.count)
        val mesh = assertNotNull(s.meshes.peek(g.chunk))
        // Expected world positions of A's mesh vertices, for either top-level placement (order is by distance).
        val chains = (0 until 2).map { f.root * top.refs.transform(it) * b.refs.transform(0) }
        for (i in 0 until g.count) {
            var matched = false
            for (chain in chains) {
                var ok = true
                for (v in 0 until mesh.vertexCount) {
                    val ax = mesh.vertices[2 * v] + g.chunk.originX.toDouble()
                    val ay = mesh.vertices[2 * v + 1] + g.chunk.originY.toDouble()
                    val ex = chain.mapX(ax, ay); val ey = chain.mapY(ax, ay)
                    if (Math.abs(ex - world(dl, g, i, mesh.vertices[2 * v], mesh.vertices[2 * v + 1], true)) > 1e-3 ||
                        Math.abs(ey - world(dl, g, i, mesh.vertices[2 * v], mesh.vertices[2 * v + 1], false)) > 1e-3) ok = false
                }
                if (ok) matched = true
            }
            assertTrue(matched, "instance $i matches one of the nested placements")
        }
        assertTrue(g.maxScale > 0.0029 && g.maxScale < 0.0031) // 2.0 * 1.5 * 0.001 µm per DBU
    }

    @Test fun cullingKeepsOnlyIntersectingChunks() {
        // 10 x 10 individual placements, 10 µm pitch, cell is 5 µm wide.
        val s = Setup(lib {
            val ly = layerIndex(1, 0)
            beginCell("A").addBox(ly, 0, 0, 5000, 5000)
            val t = beginCell("T")
            for (i in 0 until 10) for (j in 0 until 10) t.addRef(cellIndex("A"), i * 10000, j * 10000)
        })
        val f = s.frame("T")
        val bottomLeftX = f.minX + 7 // zoom into the bottom-left corner: 20 x 20 µm window
        val bottomLeftY = f.minY + 7
        val req = request(f, bottomLeftX, bottomLeftY, 20.0)
        val dl = s.builder.build(req)
        assertEquals(bruteForce(s, f, dl.coverage), dl.total())
        assertTrue(dl.total() in 1..9)
        assertEquals(bottomLeftX - 10, dl.coverage.minX, 1e-9)
        assertEquals(bottomLeftX + 10, dl.coverage.maxX, 1e-9)
        assertEquals(bottomLeftY - 10, dl.coverage.minY, 1e-9)
        assertEquals(bottomLeftY + 10, dl.coverage.maxY, 1e-9)
        assertEquals(bottomLeftX, dl.anchorX, 1e-9)
    }

    /** Number of placements of T's child A whose box intersects [area]. */
    private fun bruteForce(s: Setup, f: CellFrame, area: Box): Int {
        val top = s.library.cell("T")!!
        val ab = s.index.bounds.bounds(s.library.cell("A")!!.index)!!
        var n = 0
        for (r in 0 until top.refs.size) {
            for (row in 0 until top.refs.rows(r)) for (col in 0 until top.refs.columns(r)) {
                if (ab.transformed(f.root * top.refs.transform(r, col, row)).intersects(area)) n++
            }
        }
        return n
    }

    @Test fun tinyPlacementsGoToRaster() {
        val s = Setup(lib {
            val ly = layerIndex(1, 0)
            beginCell("A").addBox(ly, 0, 0, 100, 100) // 0.1 µm
            val t = beginCell("T")
            for (i in 0 until 50) for (j in 0 until 50) t.addRef(cellIndex("A"), i * 1000, j * 1000)
        })
        val f = s.frame("T")
        // 49.1 µm wide layout in 400 px: each cell is about 0.8 px.
        val dl = s.builder.build(request(f, 0.3, 0.3, 8.0)) // offset so no cell center sits exactly on a pixel border
        assertEquals(0, dl.groups.size)
        assertEquals(2500L, dl.stats.tinyItems)
        val raster = assertNotNull(dl.raster)
        assertEquals(1, raster.layers.size)
        assertEquals(200, raster.width) // 400 px / rasterPixel 2
        val top = s.library.cell("T")!!
        for (r in 0 until top.refs.size) {
            val w = f.root * top.refs.transform(r)
            val cx = w.mapX(50.0, 50.0); val cy = w.mapY(50.0, 50.0)
            val px = Math.floor(raster.worldToRaster.mapX(cx, cy)).toInt()
            val py = Math.floor(raster.worldToRaster.mapY(cx, cy)).toInt()
            assertTrue(raster.isSet(0, px, py), "pixel for placement $r $px $py ${raster.width} ${raster.height} ${raster.bits[0].sumOf { java.lang.Long.bitCount(it) }}")
        }
        // Zoomed in: the same cells are vector groups.
        val zoomed = s.builder.build(request(f, 0.0, 0.0, 400.0))
        assertTrue(zoomed.groups.isNotEmpty())
        assertNull(zoomed.raster)
    }

    @Test fun smallChunksOfBigCellsGoToRaster() {
        // One big cell with a tiny rect far away from a big rect: the tiny one is below the LOD limit.
        val s = Setup(lib {
            val ly = layerIndex(1, 0)
            val a = beginCell("A")
            a.addBox(ly, 0, 0, 100000, 100000)
            beginCell("T").addRef(cellIndex("A"), 0, 0)
        })
        val dl = s.builder.build(request(s.frame("T"), 0.0, 0.0, 1.0)) // 100 µm in 100 px: not tiny
        assertEquals(1, dl.total())
        val tiny = s.builder.build(request(s.frame("T"), 0.0, 0.0, 0.01))
        assertEquals(0, tiny.total())
        assertNotNull(tiny.raster)
    }

    private fun arrayLib(n: Int, angle: Double = 0.0, mag: Double = 1.0, stepX: Double = 2000.0, stepY: Double = 0.0, rowX: Double = 0.0, rowY: Double = 2000.0) = Setup(lib {
        beginCell("A").addBox(layerIndex(1, 0), 0, 0, 1000, 1000)
        beginCell("T").addArray(cellIndex("A"), 0, 0, false, mag, angle, n, n, stepX, stepY, rowX, rowY)
    })

    @Test fun arrayZoomedOutIsConstantTimeAndRaster() {
        val s = arrayLib(1000)
        val f = s.frame("T")
        s.builder.build(request(f, 0.0, 0.0, 0.4, 800, 800)) // warm up
        val t0 = System.nanoTime()
        val dl = s.builder.build(request(f, 0.0, 0.0, 0.4, 800, 800))
        val ms = (System.nanoTime() - t0) / 1e6
        println("PERF AREF 1000x1000 zoomed out: $ms ms (traversal ${dl.stats.traversalMillis} ms)")
        assertEquals(0, dl.groups.size)
        val raster = assertNotNull(dl.raster)
        val bits = raster.bits[0].sumOf { java.lang.Long.bitCount(it) }
        assertTrue(bits > raster.width * raster.height / 2, "array area is filled")
        assertTrue(ms < 200.0)
    }

    @Test fun arrayZoomedInEmitsExactlyTheVisibleElements() {
        val s = arrayLib(1000)
        val f = s.frame("T")
        // 40 x 40 µm window (pitch 2 µm) in the middle-left of the array.
        val t0 = System.nanoTime()
        val dl = s.builder.build(request(f, f.minX + 500.0, 0.0, 20.0, 800, 800))
        println("PERF AREF 1000x1000 zoomed in: ${(System.nanoTime() - t0) / 1e6} ms, ${dl.total()} instances")
        assertEquals(bruteForce(s, f, dl.coverage), dl.total())
        assertTrue(dl.total() in 380..500)
    }

    @Test fun arrayWithRotatedStepsAndMagnification() {
        // Rotated array, shear-ish steps (not axis aligned), various windows.
        val s = arrayLib(60, angle = 30.0, mag = 1.5, stepX = 2500.0, stepY = 700.0, rowX = -600.0, rowY = 2200.0)
        val f = s.frame("T")
        val rnd = Random(3)
        var nonEmpty = 0
        repeat(30) {
            val cx = f.minX + rnd.nextDouble() * (f.maxX - f.minX)
            val cy = f.minY + rnd.nextDouble() * (f.maxY - f.minY)
            val scale = 5.0 + rnd.nextDouble() * 30
            val dl = s.builder.build(request(f, cx, cy, scale, 300, 300, margin = 0.1))
            assertEquals(bruteForce(s, f, dl.coverage), dl.total())
            if (dl.total() > 0) nonEmpty++
        }
        assertTrue(nonEmpty > 10)
    }

    @Test fun arrayWithOneRowAndDegenerateSteps() {
        val s = Setup(lib {
            beginCell("A").addBox(layerIndex(1, 0), 0, 0, 1000, 1000)
            val t = beginCell("T")
            t.addArray(cellIndex("A"), 0, 0, false, 1.0, 0.0, 200, 1, 3000.0, 100.0, 777.0, 777.0) // one row, odd row step
            t.addArray(cellIndex("A"), 0, 20000, false, 1.0, 0.0, 30, 30, 2000.0, 0.0, 2000.0, 0.0) // parallel steps
        })
        val f = s.frame("T")
        val dl = s.builder.build(request(f, f.minX + 30.0, f.minY + 5.0, 15.0))
        assertEquals(bruteForce(s, f, dl.coverage), dl.total())
        assertTrue(dl.total() > 0)
    }

    @Test fun maxInstancesCapSendsTheRestToRaster() {
        val s = Setup(lib {
            beginCell("A").addBox(layerIndex(1, 0), 0, 0, 5000, 5000)
            val t = beginCell("T")
            for (i in 0 until 10) for (j in 0 until 10) t.addRef(cellIndex("A"), i * 10000, j * 10000)
        })
        val dl = s.builder.build(request(s.frame("T"), 0.0, 0.0, 4.0, maxInstances = 10))
        assertEquals(10, dl.total())
        assertEquals(90L, dl.stats.tinyItems)
        assertNotNull(dl.raster)
        // An array exceeding the budget becomes one raster block.
        val a = arrayLib(100)
        val dl2 = a.builder.build(request(a.frame("T"), 0.0, 0.0, 4.0, maxInstances = 50))
        assertEquals(0, dl2.total())
        assertNotNull(dl2.raster)
    }

    @Test fun emptyFrame() {
        val s = Setup(lib { beginCell("E"); beginCell("A").addBox(layerIndex(1, 0), 0, 0, 10, 10) })
        val f = s.frame("E")
        assertTrue(f.isEmpty)
        val dl = s.builder.build(request(f, 0.0, 0.0, 1.0, margin = 0.25))
        assertEquals(0, dl.groups.size)
        assertNull(dl.raster)
        assertEquals(0, dl.stats.instances)
        assertEquals(0L, dl.stats.tinyItems)
        assertEquals(-300.0, dl.coverage.minX, 1e-9) // 400 px viewport + 25% margin on each side, 1 px per µm
        assertEquals(300.0, dl.coverage.maxX, 1e-9)
    }

    @Test fun cancellation() {
        val s = Setup(lib {
            beginCell("A").addBox(layerIndex(1, 0), 0, 0, 5000, 5000)
            val t = beginCell("T")
            for (i in 0 until 100) for (j in 0 until 100) t.addRef(cellIndex("A"), i * 10000, j * 10000)
        })
        val req = request(s.frame("T"), 0.0, 0.0, 1.0)
        assertFailsWith<CancellationException> { s.builder.build(req) { true } }
        val calls = AtomicInteger()
        assertFailsWith<CancellationException> { s.builder.build(req) { calls.incrementAndGet() > 1 } }
    }

    @Test fun groupsAreOrderedByDistanceToViewCenter() {
        val s = Setup(lib {
            val ly = layerIndex(1, 0)
            val t = beginCell("T")
            val xs = intArrayOf(0, 90000, 30000, 60000, 10000, 120000)
            for ((k, x) in xs.withIndex()) {
                beginCell("C$k").addBox(ly, 0, 0, 5000, 5000)
                t.addRef(cellIndex("C$k"), x, 0)
            }
        })
        val f = s.frame("T")
        val dl = s.builder.build(request(f, f.minX + 61.0, 0.0, 3.0, 600, 100, margin = 1.0)) // centered on C3 (x = 60000)
        assertEquals(6, dl.groups.size)
        assertEquals("C3", s.library.cells[dl.groups[0].chunk.cell].name)
        val names = dl.groups.map { s.library.cells[it.chunk.cell].name }
        assertEquals(listOf("C3", "C1", "C2", "C4", "C0", "C5").toSet(), names.toSet())
        // Distances from the center, in order of the xs .
        val centers = mapOf("C0" to 0, "C1" to 90000, "C2" to 30000, "C3" to 60000, "C4" to 10000, "C5" to 120000)
        val ds = names.map { Math.abs(centers[it]!! - 60000) }
        for (i in 1 until ds.size) assertTrue(ds[i - 1] <= ds[i])
    }

    @Test fun nestedMeshesAreBuiltForSmallScenes() {
        val s = Setup(lib {
            beginCell("A").addBox(layerIndex(1, 0), 0, 0, 1000, 1000)
            beginCell("T").addRef(cellIndex("A"), 0, 0)
        })
        val dl = s.builder.build(request(s.frame("T"), 0.0, 0.0, 100.0))
        assertNotNull(s.meshes.peek(dl.groups[0].chunk))
    }

    @Test fun performanceStdCellLikeFlatTop() {
        val rnd = Random(1)
        val types = 150
        val rows = 1000
        val perRow = 1000
        val l = lib {
            val layers = IntArray(4) { layerIndex(it + 1, 0) }
            for (k in 0 until types) {
                val c = beginCell("S$k")
                val w = 400 + rnd.nextInt(2000)
                for (j in 0 until 10) c.addBox(layers[rnd.nextInt(4)], rnd.nextInt(w - 100), rnd.nextInt(1600), rnd.nextInt(w - 100) + 100, 1700)
                c.addBox(layers[0], 0, 0, w, 100)
            }
            val t = beginCell("T")
            for (r in 0 until rows) {
                var x = 0
                for (i in 0 until perRow) {
                    t.addRef(cellIndex("S${rnd.nextInt(types)}"), x, r * 2000, r % 2 == 1, 1.0, if (r % 2 == 1) 180.0 else 0.0)
                    x += 800 + rnd.nextInt(1500)
                }
            }
        }
        val s = Setup(l)
        val f = s.frame("T")
        val tb = System.nanoTime()
        val first = s.builder.build(request(f, 0.0, 0.0, 800.0 / (f.maxX - f.minX), 1000, 800))
        val firstMs = (System.nanoTime() - tb) / 1e6
        var best = Double.MAX_VALUE
        repeat(5) {
            val t0 = System.nanoTime()
            s.builder.build(request(f, 0.0, 0.0, 800.0 / (f.maxX - f.minX), 1000, 800))
            best = minOf(best, (System.nanoTime() - t0) / 1e6)
        }
        println("PERF 1M placements flat top, fully zoomed out: first build (incl. index) $firstMs ms, best of 5 $best ms, " +
            "instances ${first.stats.instances}, tiny ${first.stats.tinyItems}")
        assertTrue(best < 1000.0)
        // Zoomed into a small area the cost drops again.
        val t1 = System.nanoTime()
        val zoom = s.builder.build(request(f, 0.0, 0.0, 800.0 / ((f.maxX - f.minX) / 50), 1000, 800))
        println("PERF 1M placements flat top, zoomed 50x: ${(System.nanoTime() - t1) / 1e6} ms, instances ${zoom.stats.instances}")
    }
}
