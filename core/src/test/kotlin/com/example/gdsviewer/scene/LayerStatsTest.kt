package com.example.gdsviewer.scene

import com.example.gdsviewer.assertEquals
import org.junit.Test

class LayerStatsTest {
    private val square = intArrayOf(0, 0, 10, 0, 10, 10, 0, 10)
    private val triangle = intArrayOf(0, 0, 10, 0, 0, 10)

    @Test fun nestingAndArrays() {
        val l = lib {
            val l0 = layerIndex(1, 0); val l1 = layerIndex(2, 0); layerIndex(3, 0)
            val c = beginCell("C"); c.addBox(l0, 0, 0, 5, 5); c.addPolygon(l1, triangle, 3)
            val b = beginCell("B")
            for (i in 0 until 3) b.addBox(l0, i * 10, 0, i * 10 + 5, 5)
            b.addArray(cellIndex("C"), 0, 0, false, 1.0, 0.0, 2, 3, 20.0, 0.0, 0.0, 20.0)
            val t = beginCell("Top")
            t.addRef(cellIndex("B"), 0, 0)
            t.addRef(cellIndex("B"), 100, 0, true, 1.0, 90.0)
            t.addPath(l1, 4, 0, 0, 0, intArrayOf(0, 0, 10, 0, 10, 10), 3)
        }
        val counts = LayerStats.shapeCounts(l, l.cell("Top")!!.index)
        assertEquals(3, counts.size)
        // Layer 1/0: 2 placements of B with (3 + 6 C rects); layer 2/0: 2 * 6 polygons + the top's path.
        assertEquals(18L, counts[0])
        assertEquals(13L, counts[1])
        assertEquals(0L, counts[2])
        // Starting lower in the hierarchy only counts what is below.
        assertEquals(9L, LayerStats.shapeCounts(l, l.cell("B")!!.index)[0])
    }

    @Test fun cyclesTerminate() {
        val l = lib {
            val l0 = layerIndex(1, 0)
            val a = beginCell("A"); a.addBox(l0, 0, 0, 5, 5)
            val b = beginCell("B"); b.addBox(l0, 0, 0, 5, 5)
            a.addRef(cellIndex("B"), 0, 0)
            b.addRef(cellIndex("A"), 0, 0)
            b.addRef(cellIndex("B"), 0, 0) // self reference
        }
        val counts = LayerStats.shapeCounts(l, 0)
        assertEquals(2L, counts[0]) // A once, B once; the cyclic references are ignored
    }

    @Test fun hugeCountsSaturate() {
        val l = lib {
            val l0 = layerIndex(1, 0)
            for (i in 0 until 20) {
                val c = beginCell("C$i")
                c.addBox(l0, 0, 0, 5, 5)
                if (i < 19) c.addArray(cellIndex("C${i + 1}"), 0, 0, false, 1.0, 0.0, 1000, 1000, 10.0, 0.0, 0.0, 10.0)
            }
        }
        assertEquals(Long.MAX_VALUE, LayerStats.shapeCounts(l, 0)[0])
    }
}
