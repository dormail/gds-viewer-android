package com.example.gdsviewer.gds

import com.example.gdsviewer.assertEquals
import com.example.gdsviewer.assertFailsWith
import com.example.gdsviewer.assertSame
import com.example.gdsviewer.assertTrue
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import org.junit.Test

class GdsParserTest {
    private fun parse(bytes: ByteArray) = GdsParser.parse(ByteArrayInputStream(bytes))

    private fun base() = TestGdsWriter().header().bgnlib().libname("LIB").units(1e-3, 1e-9)

    @Test
    fun decodeReal8RoundTrips() {
        for (v in doubleArrayOf(1.0, 0.001, 1e-9, -2.5, 0.0)) {
            val decoded = GdsParser.decodeReal8(encodeReal8(v), 0)
            if (v == 0.0) assertEquals(0.0, decoded) else assertTrue(Math.abs(decoded - v) / Math.abs(v) < 1e-15, "$v -> $decoded")
        }
    }

    @Test
    fun parsesUnitsCellsAndBoundary() {
        val w = base().bgnstr("A")
            .boundary(1, 0, 0, 0, 10, 0, 10, 20, 0, 20, 0, 0) // closing point repeated
            .boundary(2, 40000, 0, 0, 5, 0, 5, 5)
            .endstr().bgnstr("B").endstr().endlib()
        val lib = parse(w.bytes())
        assertEquals("LIB", lib.name)
        assertEquals(1e-9, lib.dbUnitMeters, 1e-24)
        assertEquals(setOf("A", "B"), lib.cells.keys)
        val a = lib.cells.getValue("A")
        assertEquals(2, a.polygons.size)
        assertEquals(4, a.polygons[0].xy.size / 2)
        assertEquals(LayerKey(1, 0), a.polygons[0].layer)
        assertEquals(40000, a.polygons[1].layer.datatype) // unsigned
    }

    @Test
    fun internsLayerKeys() {
        val lib = parse(base().bgnstr("A").boundary(1, 0, 0, 0, 1, 0, 1, 1).boundary(1, 0, 0, 0, 2, 0, 2, 2).endstr().endlib().bytes())
        val p = lib.cells.getValue("A").polygons
        assertSame(p[0].layer, p[1].layer)
    }

    @Test
    fun parsesPath() {
        val lib = parse(base().bgnstr("A").path(3, 1, 200, 4, 50, 70, 0, 0, 1000, 0, 1000, 500).endstr().endlib().bytes())
        val p = lib.cells.getValue("A").paths.single()
        assertEquals(LayerKey(3, 1), p.layer)
        assertEquals(200, p.width)
        assertEquals(4, p.pathType)
        assertEquals(50, p.beginExtension)
        assertEquals(70, p.endExtension)
        assertEquals(listOf(0, 0, 1000, 0, 1000, 500), p.xy.toList())
    }

    @Test
    fun parsesSrefWithTransform() {
        val lib = parse(
            base().bgnstr("A").endstr().bgnstr("T")
                .sref("A", 10, 20, mirror = true, mag = 2.0, angle = 90.0).sref("A", 1, 2).endstr().endlib().bytes(),
        )
        val refs = lib.cells.getValue("T").refs
        assertEquals("A", refs[0].cellName)
        assertEquals(10, refs[0].x)
        assertEquals(20, refs[0].y)
        assertTrue(refs[0].mirrorX)
        assertEquals(2.0, refs[0].magnification, 1e-12)
        assertEquals(90.0, refs[0].angleDegrees, 1e-12)
        assertEquals(1.0, refs[1].magnification, 0.0)
        assertEquals(0.0, refs[1].angleDegrees, 0.0)
        assertEquals(1, refs[1].columns)
    }

    @Test
    fun parsesArefSteps() {
        val lib = parse(base().bgnstr("T").aref("A", 3, 2, 100, 100, 160, 100, 100, 160).endstr().endlib().bytes())
        val r = lib.cells.getValue("T").refs.single()
        assertEquals(3, r.columns)
        assertEquals(2, r.rows)
        assertEquals(20.0, r.columnStepX, 1e-12)
        assertEquals(0.0, r.columnStepY, 1e-12)
        assertEquals(0.0, r.rowStepX, 1e-12)
        assertEquals(30.0, r.rowStepY, 1e-12)
        assertEquals(100, r.x)
    }

    @Test
    fun parsesText() {
        val lib = parse(base().bgnstr("A").text(5, 7, 8, "hello").endstr().endlib().bytes())
        val t = lib.cells.getValue("A").texts.single()
        assertEquals("hello", t.text)
        assertEquals(7, t.x)
        assertEquals(8, t.y)
        assertEquals(LayerKey(5, 0), t.layer)
    }

    @Test
    fun readsGzip() {
        val raw = base().bgnstr("A").boundary(1, 0, 0, 0, 1, 0, 1, 1).endstr().endlib().bytes()
        val zipped = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(raw) } }.toByteArray()
        assertEquals(1, parse(zipped).cells.getValue("A").polygons.size)
    }

    @Test
    fun zeroPaddingAfterEndlibIsIgnored() {
        val raw = base().bgnstr("A").endstr().endlib().bytes() + ByteArray(2048)
        assertEquals(1, parse(raw).cells.size)
    }

    @Test
    fun missingEndlibIsFine() {
        assertEquals(1, parse(base().bgnstr("A").endstr().bytes()).cells.size)
    }

    @Test
    fun garbageIsRejected() {
        val e = assertFailsWith<GdsFormatException> { parse("hello world, this is no layout".toByteArray()) }
        assertEquals("This is not a GDSII file", e.message)
        assertFailsWith<GdsFormatException> { parse(ByteArray(0)) }
    }

    @Test
    fun truncatedFileIsRejected() {
        val raw = base().bgnstr("A").endstr().endlib().bytes()
        val e = assertFailsWith<GdsFormatException> { parse(raw.copyOf(raw.size - 2)) }
        assertEquals("File ends unexpectedly", e.message)
    }

    @Test
    fun shortRecordIsRejected() {
        val w = base().bgnstr("A")
        w.rec(0x08, 0)
        w.rec(0x0D, 2, ByteArray(1))
        w.rec(0x11, 0)
        val e = assertFailsWith<GdsFormatException> { parse(w.bytes()) }
        assertTrue(e.message!!.contains("LAYER"))
    }
}
