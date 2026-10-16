package com.example.gdsviewer.scene

import com.example.gdsviewer.assertEquals
import com.example.gdsviewer.assertNotNull
import com.example.gdsviewer.assertNull
import com.example.gdsviewer.assertTrue
import org.junit.Test

class CellBoundsTest {
    private fun assertBox(b: Box?, x0: Double, y0: Double, x1: Double, y1: Double) {
        val box = assertNotNull(b)
        assertEquals(x0, box.minX, 1e-9); assertEquals(y0, box.minY, 1e-9)
        assertEquals(x1, box.maxX, 1e-9); assertEquals(y1, box.maxY, 1e-9)
    }

    /** Child "C" is a rectangle (0,0)-(10,20) on layer 5; the parent places it with the given transform. */
    private fun placed(place: com.example.gdsviewer.db.CellBuilder.(Int) -> Unit): CellBounds {
        val l = lib {
            val c = beginCell("C"); c.addBox(layerIndex(5, 0), 0, 0, 10, 20)
            val p = beginCell("P"); p.place(cellIndex("C"))
        }
        return CellBounds(l)
    }

    @Test fun ownShapesAndLayers() {
        val l = lib {
            val c = beginCell("A")
            c.addBox(layerIndex(2, 0), 0, 0, 10, 10)
            c.addBox(layerIndex(1, 0), -5, 3, 4, 8)
            c.addPolygon(layerIndex(1, 0), intArrayOf(0, 0, 20, 0, 0, 30), 3)
        }
        val b = CellBounds(l)
        assertBox(b.bounds(0), -5.0, 0.0, 20.0, 30.0)
        val lb = b.layerBounds(0)
        assertEquals(2, lb.size)
        // Layers are sorted by number: (1,0) is index 0.
        assertEquals(0, lb.layers[0]); assertEquals(1, lb.layers[1])
        assertBox(lb.box(0), -5.0, 0.0, 20.0, 30.0)
        assertBox(lb.box(1), 0.0, 0.0, 10.0, 10.0)
    }

    @Test fun rotatedPlacement() {
        // 90 degrees ccw: (x, y) -> (-y, x): box x -20..0, y 0..10, then moved by (100, 100).
        val b = placed { addRef(it, 100, 100, angleDegrees = 90.0) }
        assertBox(b.bounds(1), 80.0, 100.0, 100.0, 110.0)
    }

    @Test fun mirroredPlacement() {
        val b = placed { addRef(it, 100, 100, mirror = true) }
        assertBox(b.bounds(1), 100.0, 80.0, 110.0, 100.0)
    }

    @Test fun magnifiedRotatedMirroredPlacement() {
        // mirror: y -> -y gives (0..10, -20..0); scale 2: (0..20, -40..0); rotate 180: (-20..0, 0..40); move.
        val b = placed { addRef(it, 10, 10, mirror = true, magnification = 2.0, angleDegrees = 180.0) }
        assertBox(b.bounds(1), -10.0, 10.0, 10.0, 50.0)
    }

    @Test fun arbitraryAngle() {
        val b = placed { addRef(it, 0, 0, angleDegrees = 45.0) }
        val s = Math.sqrt(0.5)
        // corners (0,0) (10,0) (0,20) (10,20) -> x' = (x - y) s, y' = (x + y) s
        assertBox(b.bounds(1), -20 * s, 0.0, 10 * s, 30 * s)
    }

    @Test fun arrayUsesCornerInstances() {
        val b = placed { addArray(it, 0, 0, false, 1.0, 0.0, 3, 2, 50.0, 0.0, 0.0, 100.0) }
        assertBox(b.bounds(1), 0.0, 0.0, 110.0, 120.0)
    }

    @Test fun skewedAndRotatedArray() {
        // Rotated by 90: child box x -20..0, y 0..10. Steps (0, 30) per column and (-40, 0) per row.
        val b = placed { addArray(it, 0, 0, false, 1.0, 90.0, 2, 3, 0.0, 30.0, -40.0, 0.0) }
        assertBox(b.bounds(1), -100.0, 0.0, 0.0, 40.0)
    }

    @Test fun layerBoundsIncludeChildren() {
        val l = lib {
            val c = beginCell("C"); c.addBox(layerIndex(5, 0), 0, 0, 10, 20)
            val p = beginCell("P")
            p.addBox(layerIndex(1, 0), 0, 0, 1, 1)
            p.addRef(cellIndex("C"), 100, 0)
            p.addRef(cellIndex("C"), 200, 0)
        }
        val b = CellBounds(l)
        val lb = b.layerBounds(1)
        assertEquals(2, lb.size)
        assertBox(lb.box(0), 0.0, 0.0, 1.0, 1.0)
        assertBox(lb.box(1), 100.0, 0.0, 210.0, 20.0)
        assertBox(b.bounds(1), 0.0, 0.0, 210.0, 20.0)
    }

    @Test fun pathsGrowByHalfWidthAndExtensions() {
        val l = lib {
            val c = beginCell("A")
            c.addPath(layerIndex(1, 0), 10, 0, 0, 0, intArrayOf(0, 0, 100, 50, 200, 0), 3) // grow 5
            c.addPath(layerIndex(2, 0), 10, 2, 0, 0, intArrayOf(1000, 0, 1100, 50, 1200, 0), 3) // grow 10
            c.addPath(layerIndex(3, 0), 10, 4, -7, 3, intArrayOf(2000, 0, 2100, 50, 2200, 0), 3) // grow 5 + 7
        }
        val lb = CellBounds(l).layerBounds(0)
        assertBox(lb.box(0), -5.0, -5.0, 205.0, 55.0)
        assertBox(lb.box(1), 990.0, -10.0, 1210.0, 60.0)
        assertBox(lb.box(2), 1988.0, -12.0, 2212.0, 62.0)
    }

    @Test fun emptyCells() {
        val l = lib {
            beginCell("E")
            val p = beginCell("P"); p.addRef(cellIndex("E"), 5, 5)
        }
        val b = CellBounds(l)
        assertNull(b.bounds(0)); assertEquals(0, b.layerBounds(0).size)
        assertNull(b.bounds(1)); assertEquals(0, b.layerBounds(1).size)
    }

    @Test fun cyclesAreIgnored() {
        val l = lib {
            val a = beginCell("A"); a.addBox(layerIndex(1, 0), 0, 0, 10, 10)
            a.addRef(cellIndex("B"), 100, 0)
            a.addRef(cellIndex("A"), 5, 5) // directly cyclic
            val b = beginCell("B"); b.addBox(layerIndex(1, 0), 0, 0, 1, 1)
            b.addRef(cellIndex("A"), 0, 0) // indirectly cyclic
        }
        val b = CellBounds(l)
        // The result depends on where the cycle is cut, but it must be finite and contain the own shapes.
        val a = assertNotNull(b.bounds(0)); val bb = assertNotNull(b.bounds(1))
        assertTrue(a.contains(Box(0.0, 0.0, 10.0, 10.0)))
        assertTrue(bb.contains(Box(0.0, 0.0, 1.0, 1.0)))
        assertTrue(a.width < 1e6 && bb.width < 1e6)
    }

    @Test fun deepHierarchyAndManyCellsAreFast() {
        val n = 10_000
        val l = lib {
            val layer = layerIndex(1, 0)
            for (i in 0 until n) {
                val c = beginCell("C$i")
                c.addBox(layer, 0, 0, 1, 1)
                if (i + 1 < n) c.addRef(cellIndex("C${i + 1}"), 1, 0) // a chain 10k deep
            }
        }
        val t = System.nanoTime()
        val b = CellBounds(l)
        println("bounds of 10k-deep chain: ${(System.nanoTime() - t) / 1_000_000} ms")
        assertBox(b.bounds(0), 0.0, 0.0, n.toDouble(), 1.0)
    }

    @Test fun millionPlacementsAreFast() {
        val l = lib {
            val layer = layerIndex(1, 0)
            beginCell("C").addBox(layer, 0, 0, 1, 1)
            val p = beginCell("P")
            val c = cellIndex("C")
            for (i in 0 until 1_000_000) p.addRef(c, (i % 1000) * 2, (i / 1000) * 2, angleDegrees = 90.0 * (i % 4))
        }
        val t = System.nanoTime()
        val b = CellBounds(l)
        println("bounds with 1M placements: ${(System.nanoTime() - t) / 1_000_000} ms")
        assertBox(b.bounds(1), 0.0, -1.0, 1999.0, 1999.0)
    }
}
