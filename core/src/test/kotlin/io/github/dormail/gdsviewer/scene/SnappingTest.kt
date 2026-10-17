package io.github.dormail.gdsviewer.scene

import io.github.dormail.gdsviewer.assertEquals
import io.github.dormail.gdsviewer.assertNotNull
import io.github.dormail.gdsviewer.assertNull
import io.github.dormail.gdsviewer.assertFailsWith
import io.github.dormail.gdsviewer.db.Library
import io.github.dormail.gdsviewer.geometry.Affine
import java.util.concurrent.CancellationException
import org.junit.Test

class SnappingTest {
    private fun all(l: Library) = BooleanArray(l.layers.size) { true }

    private fun snap(l: Library, x: Double, y: Double, r: Double, visible: BooleanArray = all(l)): DoubleArray? {
        val idx = LibraryIndex(l)
        return Snapping.snap(idx, idx.frame(l.topCells.first().index), visible, x, y, r)
    }

    private fun assertPoint(expected: DoubleArray, actual: DoubleArray?) {
        val a = assertNotNull(actual)
        assertEquals(expected[0], a[0], 1e-9)
        assertEquals(expected[1], a[1], 1e-9)
    }

    @Test fun rectCornersAndEdges() {
        val l = lib { beginCell("T").addBox(layerIndex(1, 0), 0, 0, 1000, 1000) }
        // Frame center is (500, 500) DBU = world origin; 1 DBU = 0.001 µm.
        assertPoint(doubleArrayOf(0.5, 0.5), snap(l, 0.6, 0.55, 0.5))
        assertPoint(doubleArrayOf(-0.5, -0.5), snap(l, -0.45, -0.6, 0.5))
        // Nearer to an edge than to any corner: closest edge point.
        assertPoint(doubleArrayOf(0.1, 0.5), snap(l, 0.1, 0.6, 0.3))
        // Inside the rectangle, near the left edge.
        assertPoint(doubleArrayOf(-0.5, 0.0), snap(l, -0.4, 0.0, 0.2))
    }

    @Test fun nothingNearbyIsNull() {
        val l = lib { beginCell("T").addBox(layerIndex(1, 0), 0, 0, 1000, 1000) }
        assertNull(snap(l, 5.0, 5.0, 0.5))
        assertNull(snap(l, 0.0, 0.0, 0.1)) // inside, but far from every edge
    }

    @Test fun cornerWithinHalfRadiusWinsOverCloserEdge() {
        val l = lib { beginCell("T").addBox(layerIndex(1, 0), 0, 0, 1000, 1000) }
        // 0.1 right of the corner's x, 0.04 above the top edge: corner at distance ~0.108 < radius/2.
        assertPoint(doubleArrayOf(0.5, 0.5), snap(l, 0.6, 0.54, 0.25))
    }

    @Test fun invisibleLayersAreIgnored() {
        val l = lib {
            val a = layerIndex(1, 0); val b = layerIndex(2, 0)
            val t = beginCell("T")
            t.addBox(a, 0, 0, 1000, 1000)
            t.addBox(b, 1050, 0, 2000, 1000)
        }
        // World: rect a spans x -1.0 .. 0.0, rect b 0.05 .. 1.0 (the frame center is at 1000 DBU).
        assertPoint(doubleArrayOf(0.0, 0.0), snap(l, 0.02, 0.0, 0.1, booleanArrayOf(true, true)))
        assertPoint(doubleArrayOf(0.05, 0.0), snap(l, 0.02, 0.0, 0.1, booleanArrayOf(false, true)))
        assertPoint(doubleArrayOf(0.0, 0.0), snap(l, 0.02, 0.0, 0.1, booleanArrayOf(true, false)))
        assertNull(snap(l, 0.02, 0.0, 0.1, booleanArrayOf(false, false)))
    }

    @Test fun nestedRotatedInstances() {
        val l = lib {
            val ly = layerIndex(1, 0)
            beginCell("A").addBox(ly, 0, 0, 1000, 500)
            beginCell("B").addRef(cellIndex("A"), 0, 2000, true, 1.0, 0.0)
            val t = beginCell("T")
            t.addRef(cellIndex("B"), 10000, 0, false, 1.0, 90.0)
            t.addBox(ly, -3000, -3000, -2000, -2000) // widens the bounds, so the frame center is not at the instance
        }
        val idx = LibraryIndex(l)
        val top = l.cell("T")!!
        val frame = idx.frame(top.index)
        val b = l.cell("B")!!
        val chain = frame.root * top.refs.transform(0) * b.refs.transform(0)
        for ((cx, cy) in listOf(0 to 0, 1000 to 0, 1000 to 500, 0 to 500)) {
            val ex = chain.mapX(cx.toDouble(), cy.toDouble()); val ey = chain.mapY(cx.toDouble(), cy.toDouble())
            val r = Snapping.snap(idx, frame, all(l), ex + 0.02, ey - 0.03, 0.2)
            assertPoint(doubleArrayOf(ex, ey), r)
        }
    }

    @Test fun arrayElements() {
        val l = lib {
            val ly = layerIndex(1, 0)
            beginCell("A").addBox(ly, 0, 0, 1000, 500)
            // Rotated array with a magnification.
            beginCell("T").addArray(cellIndex("A"), 0, 0, false, 2.0, 30.0, 10, 10, 5000.0, 0.0, 0.0, 5000.0)
        }
        val idx = LibraryIndex(l)
        val top = l.cell("T")!!
        val frame = idx.frame(top.index)
        for ((col, row) in listOf(3 to 4, 0 to 0, 9 to 9, 9 to 0)) {
            val t = frame.root * top.refs.transform(0, col, row)
            val ex = t.mapX(1000.0, 500.0); val ey = t.mapY(1000.0, 500.0)
            assertPoint(doubleArrayOf(ex, ey), Snapping.snap(idx, frame, all(l), ex + 0.01, ey + 0.01, 0.1))
        }
    }

    @Test fun paths() {
        val l = lib {
            val ly = layerIndex(1, 0)
            val t = beginCell("T")
            t.addPath(ly, 200, 0, 0, 0, intArrayOf(0, 0, 5000, 0, 5000, 5000), 3)
        }
        val idx = LibraryIndex(l)
        val frame = idx.frame(0)
        // End cap corners of the first segment: (0, +-100).
        val ex = frame.root.mapX(0.0, 100.0); val ey = frame.root.mapY(0.0, 100.0)
        assertPoint(doubleArrayOf(ex, ey), Snapping.snap(idx, frame, all(l), ex + 0.01, ey + 0.02, 0.1))
        // An edge point of the long side (y = -100 DBU) halfway along.
        val sx = frame.root.mapX(2500.0, -100.0); val sy = frame.root.mapY(2500.0, -100.0)
        assertPoint(doubleArrayOf(sx, sy), Snapping.snap(idx, frame, all(l), sx, sy - 0.03, 0.1))
    }

    @Test fun polygons() {
        val l = lib { beginCell("T").addPolygon(layerIndex(1, 0), intArrayOf(0, 0, 4000, 0, 0, 3000), 3) }
        val idx = LibraryIndex(l)
        val frame = idx.frame(0)
        val ex = frame.root.mapX(4000.0, 0.0); val ey = frame.root.mapY(4000.0, 0.0)
        assertPoint(doubleArrayOf(ex, ey), Snapping.snap(idx, frame, all(l), ex - 0.02, ey + 0.02, 0.2))
        // On the hypotenuse, away from both corners.
        val hx = frame.root.mapX(2000.0, 1500.0); val hy = frame.root.mapY(2000.0, 1500.0)
        assertPoint(doubleArrayOf(hx, hy), Snapping.snap(idx, frame, all(l), hx + 0.03, hy + 0.04, 0.1))
    }

    @Test fun cancellation() {
        val l = lib { beginCell("T").addBox(layerIndex(1, 0), 0, 0, 1000, 1000) }
        val idx = LibraryIndex(l)
        assertFailsWith<CancellationException> {
            Snapping.snap(idx, idx.frame(0), all(l), 0.5, 0.5, 0.5) { true }
        }
    }
}
