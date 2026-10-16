package com.example.gdsviewer.geometry

import com.example.gdsviewer.assertEquals
import com.example.gdsviewer.assertNotNull
import com.example.gdsviewer.assertNull
import com.example.gdsviewer.gds.GdsCell
import com.example.gdsviewer.gds.GdsLibrary
import com.example.gdsviewer.gds.GdsPolygon
import com.example.gdsviewer.gds.LayerKey
import org.junit.Test

class SnapperTest {
    private val l1 = LayerKey(1, 0)

    /** A 100 nm square (0.1 µm), centered at the world origin: corners at +-0.05. */
    private fun geometry(): LayoutGeometry {
        val cell = GdsCell("T").apply {
            polygons += GdsPolygon(l1, intArrayOf(0, 0, 100, 0, 100, 100, 0, 100))
        }
        return LayoutFlattener(GdsLibrary("L", 1e-9, mapOf("T" to cell))).flatten("T")
    }

    @Test
    fun snapsToCorner() {
        val p = assertNotNull(Snapper.snap(geometry(), setOf(l1), 0.052, 0.049, 0.02))
        assertEquals(0.05, p[0], 1e-6)
        assertEquals(0.05, p[1], 1e-6)
    }

    @Test
    fun snapsToEdge() {
        // Near the left edge (x = -0.05), far from the corners.
        val p = assertNotNull(Snapper.snap(geometry(), setOf(l1), -0.045, 0.004, 0.02))
        assertEquals(-0.05, p[0], 1e-6)
        assertEquals(0.004, p[1], 1e-6)
    }

    @Test
    fun nothingNearby() = assertNull(Snapper.snap(geometry(), setOf(l1), 1.0, 1.0, 0.02))

    @Test
    fun ignoresInvisibleLayers() = assertNull(Snapper.snap(geometry(), setOf(LayerKey(9, 0)), 0.05, 0.05, 0.02))
}
