package com.example.gdsviewer.geometry

import com.example.gdsviewer.assertEquals
import kotlin.math.abs
import org.junit.Test

class TriangulatorTest {
    private fun polygonArea(p: DoubleArray): Double {
        val n = p.size / 2
        var s = 0.0
        for (i in 0 until n) {
            val j = (i + 1) % n
            s += p[2 * i] * p[2 * j + 1] - p[2 * j] * p[2 * i + 1]
        }
        return abs(s) / 2
    }

    private fun trianglesArea(l: DoubleList): Double {
        var s = 0.0
        for (i in 0 until l.size step 6) {
            val d = l.data
            s += abs((d[i + 2] - d[i]) * (d[i + 5] - d[i + 1]) - (d[i + 4] - d[i]) * (d[i + 3] - d[i + 1])) / 2
        }
        return s
    }

    private fun check(vararg pts: Double) {
        val out = DoubleList()
        Triangulator.triangulate(pts, pts.size / 2, out)
        assertEquals(polygonArea(pts), trianglesArea(out), 1e-9)
    }

    @Test fun square() = check(0.0, 0.0, 10.0, 0.0, 10.0, 10.0, 0.0, 10.0)

    @Test fun clockwiseSquare() = check(0.0, 0.0, 0.0, 10.0, 10.0, 10.0, 10.0, 0.0)

    @Test fun lShape() = check(0.0, 0.0, 20.0, 0.0, 20.0, 10.0, 10.0, 10.0, 10.0, 20.0, 0.0, 20.0)

    @Test
    fun star() {
        val pts = DoubleArray(20)
        for (i in 0 until 10) {
            val r = if (i % 2 == 0) 10.0 else 4.0
            val a = Math.PI * i / 5
            pts[2 * i] = r * Math.cos(a)
            pts[2 * i + 1] = r * Math.sin(a)
        }
        check(*pts)
    }

    @Test
    fun comb() {
        // Base bar with 5 teeth going up.
        val pts = ArrayList<Double>()
        pts += listOf(0.0, 0.0, 30.0, 0.0, 30.0, 5.0)
        for (t in 4 downTo 0) {
            val x = t * 6.0
            pts += listOf(x + 5.0, 5.0, x + 5.0, 20.0 + t, x + 3.0, 20.0 + t, x + 3.0, 5.0)
        }
        pts += listOf(0.0, 5.0)
        check(*pts.toDoubleArray())
    }

    @Test
    fun keyhole() {
        // Outer square, cut line from (0,0) to the inner square, inner square clockwise, and back.
        val pts = doubleArrayOf(
            0.0, 0.0, 10.0, 0.0, 10.0, 10.0, 0.0, 10.0, 0.0, 0.0,
            2.0, 2.0, 2.0, 8.0, 8.0, 8.0, 8.0, 2.0, 2.0, 2.0,
        )
        val out = DoubleList()
        Triangulator.triangulate(pts, pts.size / 2, out)
        assertEquals(100.0 - 36.0, trianglesArea(out), 1e-9)
    }

    @Test
    fun degenerateInputsGiveNothing() {
        val out = DoubleList()
        Triangulator.triangulate(doubleArrayOf(0.0, 0.0, 1.0, 1.0), 2, out)
        Triangulator.triangulate(doubleArrayOf(0.0, 0.0, 1.0, 1.0, 2.0, 2.0), 3, out)
        assertEquals(0, out.size)
    }
}
