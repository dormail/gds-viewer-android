package io.github.dormail.gdsviewer.scene

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.abs

/**
 * Finds which elements of an array placement (AREF) can touch a given area, without looking at
 * every element. Shared by [SceneBuilder] and [Snapping].
 */
internal object ArrayRange {
    /**
     * Returns intArrayOf(firstCol, lastCol, firstRow, lastRow) (inclusive), or null if no element
     * can reach [area].
     *
     * [ex0]..[ey1] is the box of element (0, 0) and ([ux], [uy]) / ([vx], [vy]) the offsets between
     * neighboring columns / rows, all in parent cell coordinates; [area] is given in the same
     * coordinates. Element (c, r) is element (0, 0) moved by c*u + r*v, so it can only reach the
     * area if c*u + r*v lies in the box Q of "all shifts that make the two boxes overlap". The
     * result is a bounding range: elements inside it may still miss the area, so callers check each.
     */
    fun visible(
        ex0: Double, ey0: Double, ex1: Double, ey1: Double,
        ux: Double, uy: Double, vx: Double, vy: Double,
        cols: Int, rows: Int, area: Box,
    ): IntArray? {
        val qx0 = area.minX - ex1; val qx1 = area.maxX - ex0
        val qy0 = area.minY - ey1; val qy1 = area.maxY - ey0
        if (qx0 > qx1 || qy0 > qy1) return null

        if (rows == 1) {
            val c = solve1d(ux, uy, qx0, qy0, qx1, qy1, cols) ?: return null
            return intArrayOf(c[0], c[1], 0, 0)
        }
        if (cols == 1) {
            val r = solve1d(vx, vy, qx0, qy0, qx1, qy1, rows) ?: return null
            return intArrayOf(0, 0, r[0], r[1])
        }

        val det = ux * vy - vx * uy
        val scale = (abs(ux) + abs(uy)) * (abs(vx) + abs(vy))
        if (scale > 0.0 && abs(det) > 1e-9 * scale) {
            // Solve c*u + r*v = q for the four corners of Q; the extremes of c and r bound the range.
            var cMin = Double.MAX_VALUE; var cMax = -Double.MAX_VALUE
            var rMin = Double.MAX_VALUE; var rMax = -Double.MAX_VALUE
            for (k in 0 until 4) {
                val qx = if (k and 1 == 0) qx0 else qx1
                val qy = if (k and 2 == 0) qy0 else qy1
                val c = (vy * qx - vx * qy) / det
                val r = (-uy * qx + ux * qy) / det
                if (c < cMin) cMin = c; if (c > cMax) cMax = c
                if (r < rMin) rMin = r; if (r > rMax) rMax = r
            }
            val c0 = maxOf(0.0, floor(cMin - SLACK)); val c1 = minOf(cols - 1.0, ceil(cMax + SLACK))
            val r0 = maxOf(0.0, floor(rMin - SLACK)); val r1 = minOf(rows - 1.0, ceil(rMax + SLACK))
            if (c0 > c1 || r0 > r1) return null
            return intArrayOf(c0.toInt(), c1.toInt(), r0.toInt(), r1.toInt())
        }
        // Steps are zero or parallel: no cheap bound, take everything (callers check each element).
        return intArrayOf(0, cols - 1, 0, rows - 1)
    }

    /** Which indices k in 0 until [n] have k * (ux, uy) inside the box (qx0, qy0)..(qx1, qy1)? Null if none. */
    private fun solve1d(ux: Double, uy: Double, qx0: Double, qy0: Double, qx1: Double, qy1: Double, n: Int): IntArray? {
        var lo = 0.0
        var hi = n - 1.0
        for (axis in 0 until 2) {
            val u = if (axis == 0) ux else uy
            val q0 = if (axis == 0) qx0 else qy0
            val q1 = if (axis == 0) qx1 else qy1
            if (abs(u) < 1e-12) {
                if (q0 > 0.0 || q1 < 0.0) return null // the element never moves into the area on this axis
            } else {
                val a = q0 / u; val b = q1 / u
                lo = maxOf(lo, floor(minOf(a, b) - SLACK))
                hi = minOf(hi, ceil(maxOf(a, b) + SLACK))
            }
        }
        if (lo > hi) return null
        return intArrayOf(lo.toInt(), hi.toInt())
    }

    private const val SLACK = 1e-6
}
