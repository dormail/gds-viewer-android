package io.github.dormail.gdsviewer.db

import io.github.dormail.gdsviewer.assertEquals
import io.github.dormail.gdsviewer.assertFailsWith
import io.github.dormail.gdsviewer.assertNotNull
import io.github.dormail.gdsviewer.assertSame
import io.github.dormail.gdsviewer.assertTrue
import io.github.dormail.gdsviewer.gds.GdsFormatException
import io.github.dormail.gdsviewer.gds.LayerKey
import io.github.dormail.gdsviewer.scene.CellBounds
import io.github.dormail.gdsviewer.scene.LayerStats
import io.github.dormail.gdsviewer.scene.LibraryIndex
import io.github.dormail.gdsviewer.scene.MeshCache
import io.github.dormail.gdsviewer.scene.SceneBuilder
import io.github.dormail.gdsviewer.scene.SceneRequest
import io.github.dormail.gdsviewer.scene.testView
import org.junit.Test

class LibraryMergeTest {
    private fun build(name: String, unit: Double, block: LibraryBuilder.() -> Unit): Library {
        val b = LibraryBuilder()
        b.block()
        return b.build(name, unit)
    }

    /** "TOP" with a box on 1/0 and a placement of "SUB" (box on 2/0, array of it on 1/0). */
    private fun first() = build("one", 1e-9) {
        val l1 = layerIndex(1, 0); val l2 = layerIndex(2, 0)
        beginCell("SUB").addBox(l2, 0, 0, 10, 10)
        val top = beginCell("TOP")
        top.addBox(l1, 0, 0, 100, 50)
        top.addArray(cellIndex("SUB"), 200, 0, false, 1.0, 0.0, 3, 2, 20.0, 0.0, 0.0, 30.0)
    }

    private fun second(unit: Double = 1e-9, scale: Int = 1) = build("two", unit) {
        val l1 = layerIndex(1, 0); val l83 = layerIndex(83, 0)
        beginCell("SUB").addBox(l83, 0, 0, 5 * scale, 5 * scale)
        val top = beginCell("TOP")
        top.addPolygon(l1, intArrayOf(-50 * scale, -50 * scale, 0, -50 * scale, 0, 0), 3)
        top.addRef(cellIndex("SUB"), 0, 70 * scale)
    }

    private fun merged(a: Library = first(), b: Library = second()): Library =
        LibraryMerge.merge(listOf(LibraryMerge.Part(a, a.cell("TOP")!!.index, 1), LibraryMerge.Part(b, b.cell("TOP")!!.index, 2)))

    @Test fun cellsLayersAndRefs() {
        val a = first(); val b = second()
        val m = merged(a, b)
        assertEquals("one + two", m.name)
        assertEquals(listOf("SUB@1", "TOP@1", "SUB@2", "TOP@2", "Overlay"), m.cells.map { it.name })
        for ((i, c) in m.cells.withIndex()) { assertEquals(i, c.index); assertSame(c, m.cell(c.name)) }
        assertEquals(
            listOf(LayerKey(1, 0, file = 1), LayerKey(2, 0, file = 1), LayerKey(1, 0, file = 2), LayerKey(83, 0, file = 2)),
            m.layers,
        )
        assertEquals(listOf("1/0@1", "2/0@1", "1/0@2", "83/0@2"), m.layers.map { it.toString() })
        assertEquals(LayerKey(1, 0).toString(), "1/0")
        assertTrue(LayerKey(5, 0, file = 1) < LayerKey(1, 0, file = 2))

        // Shapes point at the right keys.
        assertEquals(LayerKey(2, 0, file = 1), m.layers[m.cell("SUB@1")!!.rects.layer[0]])
        assertEquals(LayerKey(1, 0, file = 1), m.layers[m.cell("TOP@1")!!.rects.layer[0]])
        assertEquals(LayerKey(83, 0, file = 2), m.layers[m.cell("SUB@2")!!.rects.layer[0]])
        assertEquals(LayerKey(1, 0, file = 2), m.layers[m.cell("TOP@2")!!.polygons.layer[0]])
        // Refs point at the renamed cells; the big arrays are shared.
        assertEquals(m.cell("SUB@1")!!.index, m.cell("TOP@1")!!.refs.cell[0])
        assertEquals(m.cell("SUB@2")!!.index, m.cell("TOP@2")!!.refs.cell[0])
        assertEquals(3, m.cell("TOP@1")!!.refs.columns(0))
        assertSame(a.cell("TOP")!!.rects.xy, m.cell("TOP@1")!!.rects.xy)
        assertSame(b.cell("TOP")!!.polygons.xy, m.cell("TOP@2")!!.polygons.xy)
        assertSame(a.cell("TOP")!!.refs.x, m.cell("TOP@1")!!.refs.x)
        // The top cell references both tops; the others are not referenced.
        val top = m.cell("Overlay")!!
        assertEquals(listOf(m.cell("TOP@1")!!.index, m.cell("TOP@2")!!.index), top.refs.cell.toList())
        assertEquals(1.0, top.refs.magnification(0), 0.0)
        assertEquals(listOf(top), m.topCells)
    }

    @Test fun namesStayUnique() {
        val a = build("a", 1e-9) { beginCell("X").addBox(layerIndex(1, 0), 0, 0, 1, 1); beginCell("X@1").addBox(layerIndex(1, 0), 0, 0, 1, 1) }
        val m = LibraryMerge.merge(listOf(LibraryMerge.Part(a, 0, 1)), topName = "X@1#2")
        assertEquals(m.cells.size, m.cells.map { it.name }.toSet().size)
        assertEquals(3, m.cells.size)
    }

    private fun bounds(l: Library, cell: Int) = assertNotNull(CellBounds(l).bounds(cell))

    @Test fun boundingBoxIsUnion() {
        val a = first(); val b = second()
        val m = merged(a, b)
        val ba = bounds(a, a.cell("TOP")!!.index)
        val bb = bounds(b, b.cell("TOP")!!.index)
        val bm = bounds(m, m.cell("Overlay")!!.index)
        assertEquals(minOf(ba.minX, bb.minX), bm.minX, 1e-9)
        assertEquals(minOf(ba.minY, bb.minY), bm.minY, 1e-9)
        assertEquals(maxOf(ba.maxX, bb.maxX), bm.maxX, 1e-9)
        assertEquals(maxOf(ba.maxY, bb.maxY), bm.maxY, 1e-9)
        // Array: x 200..200+2*20+10, y 0..30+10.
        assertEquals(250.0, ba.maxX, 1e-9)
    }

    @Test fun differentDatabaseUnits() {
        val a = first() // 1 nm
        val b = second(unit = 1e-10, scale = 10) // 0.1 nm, same drawing 10 times finer
        val m = merged(a, b)
        assertEquals(1e-10, m.dbUnitMeters, 0.0)
        val top = m.cell("Overlay")!!
        assertEquals(10.0, top.refs.magnification(0), 1e-12)
        assertEquals(1.0, top.refs.magnification(1), 1e-12)
        val ba = bounds(a, a.cell("TOP")!!.index)
        val bb = bounds(b, b.cell("TOP")!!.index)
        val bm = bounds(m, top.index)
        val um = m.dbUnitMicrons
        assertEquals(minOf(ba.minX * a.dbUnitMicrons, bb.minX * b.dbUnitMicrons), bm.minX * um, 1e-9)
        assertEquals(minOf(ba.minY * a.dbUnitMicrons, bb.minY * b.dbUnitMicrons), bm.minY * um, 1e-9)
        assertEquals(maxOf(ba.maxX * a.dbUnitMicrons, bb.maxX * b.dbUnitMicrons), bm.maxX * um, 1e-9)
        assertEquals(maxOf(ba.maxY * a.dbUnitMicrons, bb.maxY * b.dbUnitMicrons), bm.maxY * um, 1e-9)
    }

    @Test fun layerStatsAndSceneWork() {
        val a = first(); val b = second()
        val m = merged(a, b)
        val counts = LayerStats.shapeCounts(m, m.cell("Overlay")!!.index)
        val ca = LayerStats.shapeCounts(a, a.cell("TOP")!!.index)
        val cb = LayerStats.shapeCounts(b, b.cell("TOP")!!.index)
        assertEquals(ca.toList() + cb.toList(), counts.toList().let { c ->
            // Merged order is file 1 layers then file 2 layers, matching the parts' own order.
            c
        })
        assertEquals(ca.sum() + cb.sum(), counts.sum())

        val index = LibraryIndex(m)
        val frame = index.frame(m.cell("Overlay")!!.index)
        val builder = SceneBuilder(index, MeshCache(m))
        val dl = builder.build(SceneRequest(frame, testView(0.0, 0.0, 1000.0, 400, 400), 400, 400, 0.0, 4.0, 2.0, 1_000_000))
        assertTrue(dl.groups.sumOf { it.count } > 0)
        assertTrue(dl.stats.instances > 0)
    }

    @Test fun limits() {
        val a = first()
        val p = LibraryMerge.Part(a, 0, 1)
        assertFailsWith<GdsFormatException> { LibraryMerge.merge(List(LibraryMerge.MAX_PARTS + 1) { LibraryMerge.Part(a, 0, it + 1) }) }
        assertFailsWith<GdsFormatException> { LibraryMerge.merge(emptyList()) }
        assertFailsWith<GdsFormatException> { LibraryMerge.merge(listOf(LibraryMerge.Part(a, 99, 1))) }
        assertFailsWith<GdsFormatException> { LibraryMerge.merge(listOf(LibraryMerge.Part(a, -1, 1))) }
        assertFailsWith<GdsFormatException> { LibraryMerge.merge(listOf(LibraryMerge.Part(a, 0, 0))) }
        LibraryMerge.merge(List(LibraryMerge.MAX_PARTS) { LibraryMerge.Part(a, 0, it + 1) }) // exactly the limit is fine

        val many = build("many", 1e-9) {
            val c = beginCell("C")
            for (i in 0 until 6000) c.addBox(layerIndex(i, 0), 0, 0, 1, 1)
        }
        assertTrue(p.file == 1)
        assertFailsWith<GdsFormatException> {
            LibraryMerge.merge(listOf(LibraryMerge.Part(many, 0, 1), LibraryMerge.Part(many, 0, 2)))
        }
    }
}
