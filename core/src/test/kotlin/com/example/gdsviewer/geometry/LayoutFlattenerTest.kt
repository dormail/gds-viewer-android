package com.example.gdsviewer.geometry

import com.example.gdsviewer.assertEquals
import com.example.gdsviewer.assertFailsWith
import com.example.gdsviewer.assertNotNull
import com.example.gdsviewer.assertTrue
import com.example.gdsviewer.gds.GdsCell
import com.example.gdsviewer.gds.GdsLibrary
import com.example.gdsviewer.gds.GdsPath
import com.example.gdsviewer.gds.GdsPolygon
import com.example.gdsviewer.gds.GdsRef
import com.example.gdsviewer.gds.LayerKey
import java.util.concurrent.CancellationException
import org.junit.Test

class LayoutFlattenerTest {
    private val l1 = LayerKey(1, 0)
    private val l2 = LayerKey(2, 0)

    private fun rect(layer: LayerKey, x0: Int, y0: Int, x1: Int, y1: Int) =
        GdsPolygon(layer, intArrayOf(x0, y0, x1, y0, x1, y1, x0, y1))

    private fun library(vararg cells: GdsCell) = GdsLibrary("L", 1e-9, cells.associateBy { it.name })

    private fun cellA() = GdsCell("A").apply { polygons += rect(l1, 0, 0, 10, 20) }

    /** Extent (minX, minY, maxX, maxY) of the line segments of a layer, as absolute DB units. */
    private fun extent(g: LayoutGeometry, layer: LayerGeometry): DoubleArray {
        val r = doubleArrayOf(1e18, 1e18, -1e18, -1e18)
        for (c in layer.lines.chunks) {
            for (i in 0 until c.position() step 2) {
                val x = (c.get(i) + g.originX) / g.dbUnitMicrons
                val y = (c.get(i + 1) + g.originY) / g.dbUnitMicrons
                r[0] = minOf(r[0], x)
                r[1] = minOf(r[1], y)
                r[2] = maxOf(r[2], x)
                r[3] = maxOf(r[3], y)
            }
        }
        return r
    }

    private fun topLibrary(): GdsLibrary {
        val top = GdsCell("TOP")
        top.refs += GdsRef("A", 100, 0, angleDegrees = 90.0)
        top.refs += GdsRef("A", 0, 100, mirrorX = true)
        top.refs += GdsRef("A", 0, 0, columns = 3, rows = 2, columnStepX = 20.0, rowStepY = 30.0)
        top.paths += GdsPath(l2, 4, 0, 0, 0, intArrayOf(0, 0, 50, 0))
        return library(cellA(), top)
    }

    @Test
    fun shapeCountsAndBounds() {
        val g = LayoutFlattener(topLibrary()).flatten("TOP")
        val by = g.layers.associateBy { it.key }
        assertEquals(listOf(l1, l2), g.layers.map { it.key })
        assertEquals(1L + 1 + 6, by.getValue(l1).shapeCount)
        assertEquals(1L, by.getValue(l2).shapeCount)
        assertEquals(0.001, g.dbUnitMicrons, 1e-15)
        // Rotated copy: x 80..100, y 0..10. Mirrored copy: x 0..10, y 80..100.
        // Array: x 0..50, y 0..80. The path (width 4) grows the bounds by 2 on every side.
        val dbu = 0.001
        assertEquals(-2.0, (g.minX + g.originX) / dbu, 1e-6)
        assertEquals(100.0, (g.maxX + g.originX) / dbu, 1e-6)
        assertEquals(-2.0, (g.minY + g.originY) / dbu, 1e-6)
        assertEquals(100.0, (g.maxY + g.originY) / dbu, 1e-6)
        // The origin is the bounding box center.
        assertEquals(49.0, g.originX / dbu, 1e-6)
        assertEquals(49.0, g.originY / dbu, 1e-6)
        assertEquals(-g.minX, g.maxX, 1e-9)
        assertTrue(!g.truncated)
    }

    @Test
    fun rotatedInstanceCoordinates() {
        val top = GdsCell("TOP").apply { refs += GdsRef("A", 100, 0, angleDegrees = 90.0) }
        val g = LayoutFlattener(library(cellA(), top)).flatten("TOP")
        val e = extent(g, g.layers.single())
        assertEquals(80.0, e[0], 1e-3)
        assertEquals(0.0, e[1], 1e-3)
        assertEquals(100.0, e[2], 1e-3)
        assertEquals(10.0, e[3], 1e-3)
    }

    @Test
    fun mirroredInstanceCoordinates() {
        val top = GdsCell("TOP").apply { refs += GdsRef("A", 0, 100, mirrorX = true) }
        val g = LayoutFlattener(library(cellA(), top)).flatten("TOP")
        val e = extent(g, g.layers.single())
        assertEquals(0.0, e[0], 1e-3)
        assertEquals(80.0, e[1], 1e-3)
        assertEquals(10.0, e[2], 1e-3)
        assertEquals(100.0, e[3], 1e-3)
    }

    @Test
    fun nestedArraysCountShapes() {
        val mid = GdsCell("MID").apply { refs += GdsRef("A", 0, 0, columns = 2, rows = 2, columnStepX = 20.0, rowStepY = 30.0) }
        val top = GdsCell("TOP").apply { refs += GdsRef("MID", 0, 0, columns = 3, rows = 1, columnStepX = 100.0) }
        val g = LayoutFlattener(library(cellA(), mid, top)).flatten("TOP")
        assertEquals(12L, g.layers.single().shapeCount)
    }

    @Test
    fun defaultCellIsLargestTop() {
        val small = GdsCell("SMALL").apply { polygons += rect(l1, 0, 0, 1, 1) }
        val big = GdsCell("BIG").apply { polygons += rect(l1, 0, 0, 100, 100) }
        val empty = GdsCell("EMPTY")
        assertEquals("BIG", LayoutFlattener(library(small, big, empty)).defaultCell()?.name)
        assertEquals(null, LayoutFlattener(library()).defaultCell())
    }

    @Test
    fun emptyCellAndUnknownCell() {
        val g = LayoutFlattener(library(GdsCell("E"))).flatten("E")
        assertTrue(g.isEmpty)
        assertEquals(0.0, g.maxX, 0.0)
        assertFailsWith<IllegalArgumentException> { LayoutFlattener(library()).flatten("nope") }
    }

    @Test
    fun unknownRefsAndCyclesAreSurvived() {
        val a = GdsCell("X").apply {
            polygons += rect(l1, 0, 0, 10, 10)
            refs += GdsRef("X", 5, 5)
            refs += GdsRef("missing", 0, 0)
        }
        val g = LayoutFlattener(library(a)).flatten("X")
        assertNotNull(g.layers.singleOrNull())
    }

    @Test
    fun truncatesWhenBudgetExceeded() {
        val g = LayoutFlattener(topLibrary(), maxFloats = 100).flatten("TOP")
        assertTrue(g.truncated)
        val total = g.layers.sumOf { l -> l.triangles.size + l.lines.size }
        assertTrue(total <= 100)
    }

    @Test
    fun cancellationThrows() {
        assertFailsWith<CancellationException> { LayoutFlattener(topLibrary(), isCancelled = { true }).flatten("TOP") }
    }

    @Test
    fun performanceSanity() {
        val r = GdsCell("R").apply {
            for (i in 0 until 4) polygons += rect(l1, i * 20, 0, i * 20 + 10, 10)
        }
        val top = GdsCell("TOP")
        top.refs += GdsRef("R", 0, 0, columns = 300, rows = 300, columnStepX = 100.0, rowStepY = 100.0)
        for (i in 0 until 50_000) top.polygons += rect(l2, i * 20, 40000, i * 20 + 10, 40010)
        val start = System.nanoTime()
        val g = LayoutFlattener(library(r, top)).flatten("TOP")
        val ms = (System.nanoTime() - start) / 1_000_000
        println("Flatten of 90000 instances + 50000 rects took $ms ms (truncated=${g.truncated})")
        assertTrue(ms < 5000, "took $ms ms")
        assertEquals(360_000L, g.layers.first { it.key == l1 }.shapeCount)
        assertEquals(50_000L, g.layers.first { it.key == l2 }.shapeCount)
    }
}
