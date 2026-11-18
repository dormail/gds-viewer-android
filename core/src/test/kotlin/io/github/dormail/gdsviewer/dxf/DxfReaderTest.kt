package io.github.dormail.gdsviewer.dxf

import io.github.dormail.gdsviewer.assertEquals
import io.github.dormail.gdsviewer.assertFailsWith
import io.github.dormail.gdsviewer.assertTrue
import io.github.dormail.gdsviewer.db.Cell
import io.github.dormail.gdsviewer.db.LayoutReader
import io.github.dormail.gdsviewer.db.Library
import io.github.dormail.gdsviewer.gds.GdsFormatException
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CancellationException

class DxfReaderTest {
    /** Builds DXF text from "code value" pairs given as alternating arguments. */
    private fun dxf(vararg pairs: Any): String = pairs.toList().chunked(2).joinToString("") { "${it[0]}\n${it[1]}\n" }

    private fun header(units: Int?): Array<Any> =
        if (units == null) arrayOf(0, "SECTION", 2, "HEADER", 0, "ENDSEC")
        else arrayOf(0, "SECTION", 2, "HEADER", 9, "\$INSUNITS", 70, units, 0, "ENDSEC")

    private fun entities(vararg body: Any): Array<Any> = arrayOf(0, "SECTION", 2, "ENTITIES", *body, 0, "ENDSEC")
    private fun blocks(vararg body: Any): Array<Any> = arrayOf(0, "SECTION", 2, "BLOCKS", *body, 0, "ENDSEC")
    private fun block(name: String, bx: Number, by: Number, vararg body: Any): Array<Any> =
        arrayOf(0, "BLOCK", 8, "0", 2, name, 70, 0, 10, bx, 20, by, 3, name, *body, 0, "ENDBLK")

    private fun file(units: Int?, vararg sections: Array<Any>): String =
        dxf(*header(units), *sections.flatMap { it.toList() }.toTypedArray(), 0, "EOF")

    private fun read(text: String): Library = DxfReader.read(ByteArrayInputStream(text.toByteArray()))
    private fun readEntities(units: Int?, vararg body: Any): Cell = read(file(units, entities(*body))).cell("TOP")!!

    private fun Cell.rect(i: Int) = rects.xy.copyOfRange(4 * i, 4 * i + 4).toList()
    private fun Cell.poly(i: Int): List<Int> = polygons.xy.copyOfRange(2 * polygons.start[i], 2 * polygons.start[i + 1]).toList()
    private fun Cell.path(i: Int): List<Int> = paths.xy.copyOfRange(2 * paths.start[i], 2 * paths.start[i + 1]).toList()

    private val unitSquare = arrayOf<Any>(0, "LWPOLYLINE", 70, 1, 10, 0, 20, 0, 10, 1, 20, 0, 10, 1, 20, 1, 10, 0, 20, 1)

    @Test fun libraryBasics() {
        val lib = read(file(13, entities()))
        assertEquals("DXF", lib.name)
        assertEquals(1e-9, lib.dbUnitMeters, 0.0)
        assertEquals("TOP", lib.cells.single().name)
    }

    @Test fun closedRectangleBecomesRect() {
        val c = readEntities(13, 0, "LWPOLYLINE", 8, "L", 70, 1, 10, 0, 20, 0, 10, 100, 20, 0, 10, 100, 20, 50, 10, 0, 20, 50)
        assertEquals(1, c.rects.size)
        assertEquals(listOf(0, 0, 100_000, 50_000), c.rect(0))
    }

    @Test fun unitsAreConverted() {
        val body = arrayOf<Any>(0, "LWPOLYLINE", 70, 1, 10, 0, 20, 0, 10, 2, 20, 0, 10, 2, 20, 3)
        fun tri(u: Int?) = readEntities(u, *body).poly(0)
        assertEquals(listOf(0, 0, 2000, 0, 2000, 3000), tri(13))
        assertEquals(listOf(0, 0, 2_000_000, 0, 2_000_000, 3_000_000), tri(4))
        assertEquals(listOf(0, 0, 2000, 0, 2000, 3000), tri(0)) // unitless: micrometers
        assertEquals(listOf(0, 0, 2000, 0, 2000, 3000), tri(null))
        assertEquals(listOf(0, 0, 2000, 0, 2000, 3000), tri(77))
        assertEquals(listOf(0, 0, 50_800_000, 0, 50_800_000, 76_200_000), tri(1))
        assertEquals(listOf(0, 0, 2, 0, 2, 3), tri(12))
    }

    @Test fun numberFormats() {
        val c = readEntities(13, 0, "LWPOLYLINE", 70, 1, 10, "1.5E1", 20, "-0.5", 10, " +30 ", 20, "-.5", 10, "3.0e1", 20, "1e1")
        assertEquals(listOf(15_000, -500, 30_000, -500, 30_000, 10_000), c.poly(0))
    }

    @Test fun bulgeMakesArc() {
        // Half circle from (0,0) to (10,0) with bulge 1 (counter-clockwise: below the chord), closed by a straight line.
        val pts = readEntities(13, 0, "LWPOLYLINE", 70, 1, 10, 0, 20, 0, 42, 1, 10, 10, 20, 0).poly(0)
        val n = pts.size / 2
        assertEquals(33, n) // the start point plus 31 inner points plus the end point; 32 pieces for half a circle
        for (i in 0 until n) {
            val dx = pts[2 * i] - 5000.0
            val dy = pts[2 * i + 1].toDouble()
            assertEquals(5000.0, Math.hypot(dx, dy), 2.0)
            assertTrue(dy <= 0)
        }
        // A negative bulge bends the other way.
        val d = readEntities(13, 0, "LWPOLYLINE", 70, 1, 10, 0, 20, 0, 42, -1, 10, 10, 20, 0).poly(0)
        assertTrue(d.filterIndexed { i, _ -> i % 2 == 1 }.all { it >= 0 })
        // A tiny bulge needs only one piece, so no extra points.
        assertEquals(3, readEntities(13, 0, "LWPOLYLINE", 70, 1, 10, 0, 20, 0, 42, 0.001, 10, 10, 20, 0, 10, 5, 20, 5).poly(0).size / 2)
    }

    @Test fun openPolylineWithWidthIsPath() {
        val c = readEntities(13, 0, "LWPOLYLINE", 70, 0, 43, 2, 10, 0, 20, 0, 10, 10, 20, 0, 10, 10, 20, 10)
        assertEquals(1, c.paths.size)
        assertEquals(2000, c.paths.width[0])
        assertEquals(listOf(0, 0, 10_000, 0, 10_000, 10_000), c.path(0))
        // Widths from 40/41 when all equal.
        val d = readEntities(13, 0, "LWPOLYLINE", 70, 0, 10, 0, 20, 0, 40, 1, 41, 1, 10, 10, 20, 0, 40, 1, 41, 1, 10, 10, 20, 10)
        assertEquals(1, d.paths.size)
        assertEquals(1000, d.paths.width[0])
        // Different widths and zero widths are not drawn.
        assertEquals(0, readEntities(13, 0, "LWPOLYLINE", 70, 0, 10, 0, 20, 0, 40, 1, 41, 2, 10, 10, 20, 0, 10, 10, 20, 10).shapeCount)
        assertEquals(0, readEntities(13, 0, "LWPOLYLINE", 70, 0, 10, 0, 20, 0, 10, 10, 20, 0).shapeCount)
        // A single straight wire becomes a rectangle.
        assertEquals(1, readEntities(13, 0, "LWPOLYLINE", 70, 0, 43, 2, 10, 0, 20, 0, 10, 10, 20, 0).rects.size)
    }

    @Test fun mirroredExtrusion() {
        val c = readEntities(13, 0, "LWPOLYLINE", 70, 1, 10, 1, 20, 0, 10, 3, 20, 0, 10, 3, 20, 1, 230, -1)
        assertEquals(listOf(-1000, 0, -3000, 0, -3000, 1000), c.poly(0))
    }

    @Test fun polylineVertexSeqend() {
        val c = readEntities(
            13,
            0, "POLYLINE", 8, "P", 66, 1, 70, 1,
            0, "VERTEX", 8, "P", 10, 0, 20, 0,
            0, "VERTEX", 10, 4, 20, 0,
            0, "VERTEX", 10, 4, 20, 4,
            0, "SEQEND",
            0, "POLYLINE", 8, "P", 70, 9, // 3D: skipped
            0, "VERTEX", 10, 0, 20, 0, 30, 1,
            0, "VERTEX", 10, 5, 20, 0, 30, 1,
            0, "VERTEX", 10, 5, 20, 5, 30, 1,
            0, "SEQEND",
            0, "POLYLINE", 8, "W", 70, 0, 40, 1, 41, 1,
            0, "VERTEX", 10, 0, 20, 0,
            0, "VERTEX", 10, 0, 20, 8,
            0, "VERTEX", 10, 5, 20, 8,
            0, "SEQEND",
        )
        assertEquals(1, c.polygons.size)
        assertEquals(listOf(0, 0, 4000, 0, 4000, 4000), c.poly(0))
        assertEquals(1, c.paths.size)
        assertEquals(1000, c.paths.width[0])
    }

    @Test fun circleAndSolid() {
        val c = readEntities(13, 0, "CIRCLE", 8, "C", 10, 10, 20, 20, 40, 5)
        assertEquals(1, c.polygons.size)
        assertEquals(64, c.polygons.pointCount(0))
        assertEquals(listOf(15_000, 20_000), c.poly(0).take(2))
        // SOLID: corner order is 10, 11, 13, 12. This one is a rectangle.
        val s = readEntities(13, 0, "SOLID", 10, 0, 20, 0, 11, 10, 21, 0, 12, 0, 22, 5, 13, 10, 23, 5)
        assertEquals(listOf(0, 0, 10_000, 5000), s.rect(0))
        val s2 = readEntities(13, 0, "SOLID", 10, 0, 20, 0, 11, 10, 21, 0, 12, 12, 22, 5, 13, 10, 23, 5)
        assertEquals(listOf(0, 0, 10_000, 0, 10_000, 5000, 12_000, 5000), s2.poly(0))
        // Fourth corner equal to the third: a triangle.
        val t = readEntities(13, 0, "SOLID", 10, 0, 20, 0, 11, 10, 21, 0, 12, 0, 22, 5, 13, 0, 23, 5)
        assertEquals(listOf(0, 0, 10_000, 0, 0, 5000), t.poly(0))
    }

    private fun lib(vararg body: Any): Library =
        read(file(13, blocks(*block("B", 0, 0, *unitSquare)), entities(*body)))

    private fun insert(vararg g: Any): Array<Any> = arrayOf(0, "INSERT", 8, "0", 2, "B", *g)

    @Test fun insertSimpleAndRotated() {
        val l = lib(*insert(10, 5, 20, 7), *insert(10, 0, 20, 0, 50, 90))
        val top = l.cell("TOP")!!
        assertEquals(2, top.refs.size)
        assertEquals(l.cell("B")!!.index, top.refs.cell[0])
        assertEquals(5000, top.refs.x[0])
        assertEquals(7000, top.refs.y[0])
        assertEquals(0.0, top.refs.angleDegrees(0), 0.0)
        assertEquals(90.0, top.refs.angleDegrees(1), 0.0)
        assertEquals(false, top.refs.mirrored(1))
    }

    @Test fun insertScaleAndMirror() {
        val r = lib(
            *insert(41, 2, 42, 2),
            *insert(41, 1, 42, -1),
            *insert(41, -1, 42, 1),
            *insert(41, -1, 42, -1),
            *insert(41, 2, 42, 8, 50, 30),
        ).cell("TOP")!!.refs
        assertEquals(2.0, r.magnification(0), 1e-12)
        assertEquals(false, r.mirrored(0))
        assertEquals(true, r.mirrored(1)); assertEquals(0.0, r.angleDegrees(1), 0.0)
        assertEquals(true, r.mirrored(2)); assertEquals(180.0, r.angleDegrees(2), 0.0)
        assertEquals(false, r.mirrored(3)); assertEquals(180.0, r.angleDegrees(3), 0.0)
        // Non-uniform: geometric mean.
        assertEquals(4.0, r.magnification(4), 1e-12)
        assertEquals(30.0, r.angleDegrees(4), 1e-9)
    }

    @Test fun insertTransformMatchesDxfMath() {
        // Compare the stored transform with the DXF definition: p' = T + R(rot) * S * p.
        val cases = listOf(
            doubleArrayOf(1.0, 1.0, 0.0), doubleArrayOf(-1.0, 1.0, 0.0), doubleArrayOf(1.0, -1.0, 30.0),
            doubleArrayOf(-2.0, 2.0, 45.0), doubleArrayOf(-3.0, -3.0, 10.0), doubleArrayOf(2.0, -2.0, 90.0),
            doubleArrayOf(-1.0, 1.0, 90.0), doubleArrayOf(-1.0, 1.0, 270.0),
        )
        for (c in cases) {
            val sxv = c[0]; val syv = c[1]; val rot = c[2]
            val r = lib(*insert(10, 100, 20, 200, 41, sxv, 42, syv, 50, rot)).cell("TOP")!!.refs
            val t = r.transform(0)
            val rad = Math.toRadians(rot)
            for ((px, py) in listOf(1.0 to 0.0, 0.0 to 1.0, 3.0 to 2.0)) {
                val qx = px * sxv
                val qy = py * syv
                assertEquals(100_000 + qx * Math.cos(rad) - qy * Math.sin(rad), t.mapX(px, py), 1e-6)
                assertEquals(200_000 + qx * Math.sin(rad) + qy * Math.cos(rad), t.mapY(px, py), 1e-6)
            }
        }
    }

    @Test fun insertBasePointIsSubtracted() {
        val l = read(
            file(
                13,
                blocks(*block("B", 2, 3, 0, "LWPOLYLINE", 70, 1, 10, 2, 20, 3, 10, 4, 20, 3, 10, 4, 20, 5)),
                entities(*insert(10, 10, 20, 10)),
            ),
        )
        assertEquals(listOf(0, 0, 2000, 0, 2000, 2000), l.cell("B")!!.poly(0))
        assertEquals(10_000, l.cell("TOP")!!.refs.x[0])
    }

    @Test fun insertArray() {
        val r = lib(*insert(10, 1, 20, 2, 70, 3, 71, 2, 44, 10, 45, 20)).cell("TOP")!!.refs
        assertEquals(3, r.columns(0))
        assertEquals(2, r.rows(0))
        assertEquals(10_000.0, r.columnStepX(0), 1e-9)
        assertEquals(0.0, r.columnStepY(0), 1e-9)
        assertEquals(0.0, r.rowStepX(0), 1e-9)
        assertEquals(20_000.0, r.rowStepY(0), 1e-9)
        // Spacing follows the rotation.
        val rot = lib(*insert(70, 2, 71, 2, 44, 10, 45, 20, 50, 90)).cell("TOP")!!.refs
        assertEquals(0.0, rot.columnStepX(0), 1e-6)
        assertEquals(10_000.0, rot.columnStepY(0), 1e-6)
        assertEquals(-20_000.0, rot.rowStepX(0), 1e-6)
        assertEquals(0.0, rot.rowStepY(0), 1e-6)
        // Counts are capped.
        assertEquals(65535, lib(*insert(70, 99999999, 71, 1, 44, 1)).cell("TOP")!!.refs.columns(0))
    }

    @Test fun nestedBlocksAndMissingBlock() {
        val l = read(
            file(
                13,
                blocks(
                    *block("INNER", 0, 0, *unitSquare),
                    *block("OUTER", 0, 0, 0, "INSERT", 2, "INNER", 10, 5, 20, 5, 0, "INSERT", 2, "MISSING"),
                    *block("*Model_Space", 0, 0, 0, "CIRCLE", 40, 1),
                    *block("*Paper_Space0", 0, 0, 0, "CIRCLE", 40, 1),
                ),
                entities(0, "INSERT", 2, "OUTER"),
            ),
        )
        assertEquals(listOf("INNER", "OUTER", "TOP"), l.cells.map { it.name }.sorted())
        assertEquals(1, l.cell("OUTER")!!.refs.size) // the missing block is dropped
        assertEquals(l.cell("INNER")!!.index, l.cell("OUTER")!!.refs.cell[0])
        assertEquals(listOf("TOP"), l.topCells.map { it.name })
    }

    @Test fun lineEndingsBomAndComments() {
        val body = file(13, entities(*unitSquare))
        assertEquals(1, read(body.replace("\n", "\r\n")).cell("TOP")!!.rects.size)
        assertEquals(1, read("﻿$body").cell("TOP")!!.rects.size)
        val commented = "999\nwritten by a test\n" + body.replace("0\nEOF", "999\nanother\n0\nEOF")
        assertEquals(1, read(commented).cell("TOP")!!.rects.size)
        // Leading spaces in group codes, no final newline.
        assertEquals(1, read(body.replace("\n0\n", "\n  0\n").trimEnd()).cell("TOP")!!.rects.size)
        // One byte per read call.
        val slow = object : InputStream() {
            val d = body.toByteArray()
            var p = 0
            override fun read(): Int = if (p < d.size) d[p++].toInt() and 0xFF else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int = if (p >= d.size) -1 else { b[off] = d[p++]; 1 }
        }
        assertEquals(1, DxfReader.read(slow).cell("TOP")!!.rects.size)
    }

    @Test fun unknownEntitiesAreSkipped() {
        val c = readEntities(
            13, 0, "LINE", 8, "A", 10, 0, 20, 0, 11, 5, 21, 5, 0, "ARC", 10, 1, 20, 1, 40, 3, 0, "TEXT", 1, "hello", 0, "SPLINE", 10, 1, 0, "HATCH", 91, 3,
            0, "CIRCLE", 40, 1, 10, 0, 20, 0,
        )
        assertEquals(1, c.shapeCount)
    }

    @Test fun layerNames() {
        val l = read(
            file(
                13,
                entities(
                    0, "CIRCLE", 8, "83", 40, 1, 0, "CIRCLE", 8, "Defpoints", 40, 1, 0, "CIRCLE", 8, "9", 40, 1, 0, "CIRCLE", 40, 1,
                    0, "CIRCLE", 8, "83", 40, 2,
                ),
            ),
        )
        assertEquals(listOf("Defpoints", "0", "9", "83"), l.layers.map { it.toString() })
        assertEquals(83, l.layers[3].layer)
        assertEquals(-1, l.layers[0].layer)
    }

    @Test fun layoutReaderDetectsDxf() {
        val text = file(13, entities(0, "CIRCLE", 40, 1))
        assertEquals("DXF", LayoutReader.read(ByteArrayInputStream(text.toByteArray())).name)
        assertEquals("DXF", LayoutReader.read(ByteArrayInputStream("﻿${text.replace("\n", "\r\n")}".toByteArray())).name)
        assertEquals("DXF", LayoutReader.read(ByteArrayInputStream("999\nhi\n$text".toByteArray())).name)
    }

    @Test fun errorsAndProgress() {
        assertFailsWith<GdsFormatException> { read("hello world\nthis is no dxf\n") }
        assertFailsWith<GdsFormatException> { read("") }
        assertFailsWith<GdsFormatException> { read("0\nSECTION\n2\nENTITIES\n0\nCIRCLE\n10") } // code without value
        assertFailsWith<GdsFormatException> { read(file(13, entities(0, "CIRCLE", 40, 1, 10, 1e30))) } // too large for 32 bits
        assertFailsWith<CancellationException> { DxfReader.read(ByteArrayInputStream(file(13, entities(0, "CIRCLE", 40, 1)).toByteArray()), { true }) }
        var last = 0L
        val big = "0\nSECTION\n2\nENTITIES\n" + "999\n${"c".repeat(100)}\n".repeat(30_000) + "0\nENDSEC\n0\nEOF\n"
        DxfReader.read(ByteArrayInputStream(big.toByteArray()), onProgress = { last = it })
        assertTrue(last >= 1L shl 20)
    }
}
