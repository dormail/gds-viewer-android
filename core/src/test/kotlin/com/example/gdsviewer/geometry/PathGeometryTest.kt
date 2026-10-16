package com.example.gdsviewer.geometry

import com.example.gdsviewer.assertEquals
import com.example.gdsviewer.gds.GdsPath
import com.example.gdsviewer.gds.LayerKey
import org.junit.Test

class PathGeometryTest {
    private fun path(width: Int, type: Int, bgn: Int, end: Int, vararg xy: Int) =
        GdsPath(LayerKey(1, 0), width, type, bgn, end, xy)

    /** Shoelace area of the closed outline given as segments (x1, y1, x2, y2). */
    private fun outlineArea(lines: DoubleList): Double {
        var s = 0.0
        for (i in 0 until lines.size step 4) {
            val d = lines.data
            s += d[i] * d[i + 3] - d[i + 2] * d[i + 1]
        }
        return Math.abs(s) / 2
    }

    private fun build(p: GdsPath): Pair<DoubleList, DoubleList> {
        val t = DoubleList()
        val l = DoubleList()
        PathGeometry.build(p, t, l)
        return t to l
    }

    @Test
    fun flushStraight() {
        val (t, l) = build(path(10, 0, 0, 0, 0, 0, 100, 0))
        assertEquals(10.0 * 100, outlineArea(l), 1e-9)
        assertEquals(12, t.size) // 2 triangles
    }

    @Test
    fun squareEnds() = assertEquals(10.0 * 110, outlineArea(build(path(10, 2, 0, 0, 0, 0, 100, 0)).second), 1e-9)

    @Test
    fun customExtensions() =
        assertEquals(10.0 * (100 + 3 + 7), outlineArea(build(path(10, 4, 3, 7, 0, 0, 100, 0)).second), 1e-9)

    @Test
    fun rightAngleCorner() {
        val (t, l) = build(path(10, 0, 0, 0, 0, 0, 100, 0, 100, 60))
        assertEquals(10.0 * 160, outlineArea(l), 1e-9)
        assertEquals(36, t.size) // 2 quads + 2 wedge triangles
    }

    @Test
    fun rightAngleCornerOtherDirectionAndDuplicates() {
        val (_, l) = build(path(10, 0, 0, 0, 0, 0, 0, 0, 100, 0, 100, -60))
        assertEquals(10.0 * 160, outlineArea(l), 1e-9)
    }

    @Test
    fun zeroWidthIsOnlyLines() {
        val (t, l) = build(path(0, 0, 0, 0, 0, 0, 10, 0, 10, 10))
        assertEquals(0, t.size)
        assertEquals(2 * 4, l.size)
    }

    @Test
    fun roundEndsHaveMoreArea() {
        val area = outlineArea(build(path(10, 1, 0, 0, 0, 0, 100, 0)).second)
        // Rectangle plus two half discs (approximated by 8-gon arcs, slightly smaller than a disc).
        assertEquals(1000 + Math.PI * 25, area, 3.0)
    }

    @Test
    fun singlePointGivesNothing() {
        val (t, l) = build(path(10, 0, 0, 0, 5, 5, 5, 5))
        assertEquals(0, t.size + l.size)
    }
}
