package com.example.gdsviewer.geometry

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Turns a GDS wire (center line + width) into filled triangles and an outline. */
internal object PathGeometry {
    private const val CAP_SEGMENTS = 8

    /** Below this value of 1 + n1.n2 a corner is so sharp that a miter would be extremely long. */
    private const val MITER_LIMIT = 0.125

    /**
     * Appends fill triangles (3 vertices each) to [triangles] and closed outline segments (2
     * points each) to [lines], for the path with [pointCount] center line points starting at point
     * number [firstPoint] of [xy] (x, y, x, y, ...), with [width] and the GDS path [pathType]
     * (0 flush, 1 round, 2 half-width extension, 4 custom extensions). Coordinates stay in
     * database units.
     */
    fun build(
        xy: IntArray, firstPoint: Int, pointCount: Int, width: Int, pathType: Int,
        beginExtension: Int, endExtension: Int, triangles: DoubleList, lines: DoubleList,
    ) {
        // Copy the center line, dropping consecutive duplicate points (they have no direction).
        val px = DoubleArray(pointCount)
        val py = DoubleArray(pointCount)
        var m = 0
        for (i in 0 until pointCount) {
            val x = xy[2 * (firstPoint + i)].toDouble()
            val y = xy[2 * (firstPoint + i) + 1].toDouble()
            if (m > 0 && px[m - 1] == x && py[m - 1] == y) continue
            px[m] = x
            py[m] = y
            m++
        }
        if (m < 2) return

        val hw = abs(width.toDouble()) / 2
        if (hw == 0.0) {
            // Zero-width wire: just draw the center line.
            for (i in 0 until m - 1) segment(lines, px[i], py[i], px[i + 1], py[i + 1])
            return
        }

        // Unit direction u and left normal n = (-u.y, u.x) of every segment.
        val segs = m - 1
        val ux = DoubleArray(segs)
        val uy = DoubleArray(segs)
        val nx = DoubleArray(segs)
        val ny = DoubleArray(segs)
        for (i in 0 until segs) {
            val dx = px[i + 1] - px[i]
            val dy = py[i + 1] - py[i]
            val l = sqrt(dx * dx + dy * dy)
            ux[i] = dx / l
            uy[i] = dy / l
            nx[i] = -uy[i]
            ny[i] = ux[i]
        }

        // Extend the two ends according to the path type.
        val extBegin: Double
        val extEnd: Double
        when (pathType) {
            2 -> { extBegin = hw; extEnd = hw }
            4 -> { extBegin = beginExtension.toDouble(); extEnd = endExtension.toDouble() }
            else -> { extBegin = 0.0; extEnd = 0.0 }
        }
        px[0] -= ux[0] * extBegin
        py[0] -= uy[0] * extBegin
        px[m - 1] += ux[segs - 1] * extEnd
        py[m - 1] += uy[segs - 1] * extEnd
        val round = pathType == 1
        val last = segs - 1

        // ---- Fill ----
        for (i in 0 until segs) {
            val ax = px[i]; val ay = py[i]; val bx = px[i + 1]; val by = py[i + 1]
            val ox = nx[i] * hw; val oy = ny[i] * hw
            // Quad with corners a+, b+, b-, a- split into two triangles.
            triangles.add(ax + ox, ay + oy); triangles.add(bx + ox, by + oy); triangles.add(bx - ox, by - oy)
            triangles.add(ax + ox, ay + oy); triangles.add(bx - ox, by - oy); triangles.add(ax - ox, ay - oy)
        }
        for (j in 1 until m - 1) {
            // Turning left leaves a gap on the right (outer) side and vice versa.
            val cross = ux[j - 1] * uy[j] - uy[j - 1] * ux[j]
            if (cross == 0.0) continue
            val o = if (cross > 0) -hw else hw
            val x = px[j]; val y = py[j]
            val ax = x + o * nx[j - 1]; val ay = y + o * ny[j - 1]
            val bx = x + o * nx[j]; val by = y + o * ny[j]
            val d = 1 + nx[j - 1] * nx[j] + ny[j - 1] * ny[j]
            if (d < MITER_LIMIT) {
                triangles.add(x, y); triangles.add(ax, ay); triangles.add(bx, by)
            } else {
                val mx = x + o * (nx[j - 1] + nx[j]) / d
                val my = y + o * (ny[j - 1] + ny[j]) / d
                triangles.add(x, y); triangles.add(ax, ay); triangles.add(mx, my)
                triangles.add(x, y); triangles.add(mx, my); triangles.add(bx, by)
            }
        }
        if (round) {
            cap(triangles, px[m - 1], py[m - 1], nx[last], ny[last], ux[last], uy[last], hw, 1.0)
            cap(triangles, px[0], py[0], nx[0], ny[0], ux[0], uy[0], hw, -1.0)
        }

        // ---- Outline: left side forward, end cap, right side backward, start cap ----
        val o = DoubleList(4 * m + 4 * CAP_SEGMENTS)
        o.add(px[0] + nx[0] * hw, py[0] + ny[0] * hw)
        for (j in 1 until m - 1) join(o, px[j], py[j], nx[j - 1], ny[j - 1], nx[j], ny[j], hw)
        o.add(px[m - 1] + nx[last] * hw, py[m - 1] + ny[last] * hw)
        if (round) {
            for (k in 1 until CAP_SEGMENTS) {
                val t = Math.PI * k / CAP_SEGMENTS
                o.add(
                    px[m - 1] + hw * (nx[last] * cos(t) + ux[last] * sin(t)),
                    py[m - 1] + hw * (ny[last] * cos(t) + uy[last] * sin(t)),
                )
            }
        }
        o.add(px[m - 1] - nx[last] * hw, py[m - 1] - ny[last] * hw)
        for (j in m - 2 downTo 1) join(o, px[j], py[j], nx[j], ny[j], nx[j - 1], ny[j - 1], -hw)
        o.add(px[0] - nx[0] * hw, py[0] - ny[0] * hw)
        if (round) {
            for (k in 1 until CAP_SEGMENTS) {
                val t = Math.PI * k / CAP_SEGMENTS
                o.add(
                    px[0] + hw * (-nx[0] * cos(t) - ux[0] * sin(t)),
                    py[0] + hw * (-ny[0] * cos(t) - uy[0] * sin(t)),
                )
            }
        }
        val count = o.size / 2
        for (i in 0 until count) {
            val j = if (i + 1 == count) 0 else i + 1
            segment(lines, o.data[2 * i], o.data[2 * i + 1], o.data[2 * j], o.data[2 * j + 1])
        }
    }

    /**
     * Adds the outline corner(s) at the interior vertex (x, y), offset sideways by [offset] from
     * a segment with normal (n1) to one with normal (n2). A normal corner is a single "miter"
     * point where the two offset lines meet; very sharp corners are cut off (bevel) with two points.
     */
    private fun join(
        o: DoubleList, x: Double, y: Double,
        n1x: Double, n1y: Double, n2x: Double, n2y: Double, offset: Double,
    ) {
        val d = 1 + n1x * n2x + n1y * n2y
        if (d < MITER_LIMIT) {
            o.add(x + offset * n1x, y + offset * n1y)
            o.add(x + offset * n2x, y + offset * n2y)
        } else {
            o.add(x + offset * (n1x + n2x) / d, y + offset * (n1y + n2y) / d)
        }
    }

    /**
     * Half-disc triangle fan at (x, y). [dir] = +1 for the end cap (bulges along u), -1 for the
     * start cap (bulges against u).
     */
    private fun cap(
        t: DoubleList, x: Double, y: Double,
        nx: Double, ny: Double, ux: Double, uy: Double, hw: Double, dir: Double,
    ) {
        var prevX = x + hw * nx * dir
        var prevY = y + hw * ny * dir
        for (k in 1..CAP_SEGMENTS) {
            val a = Math.PI * k / CAP_SEGMENTS
            val cx = x + hw * dir * (nx * cos(a) + ux * sin(a))
            val cy = y + hw * dir * (ny * cos(a) + uy * sin(a))
            t.add(x, y); t.add(prevX, prevY); t.add(cx, cy)
            prevX = cx
            prevY = cy
        }
    }

    private fun segment(lines: DoubleList, x1: Double, y1: Double, x2: Double, y2: Double) {
        lines.add(x1, y1)
        lines.add(x2, y2)
    }
}
