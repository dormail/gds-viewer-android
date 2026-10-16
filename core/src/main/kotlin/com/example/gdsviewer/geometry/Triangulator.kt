package com.example.gdsviewer.geometry

/**
 * Splits simple polygons into triangles. Convex polygons (nearly all of them: rectangles) use a
 * cheap triangle fan; other polygons use "ear clipping".
 */
internal object Triangulator {
    /** Above this many points ear clipping gets too slow (it is O(n^2)), so we only draw the outline. */
    const val MAX_POINTS = 20000

    /**
     * [pts] = x0, y0, x1, y1, ... ([n] points, first point not repeated at the end).
     * Appends 3 vertices per triangle to [out].
     */
    fun triangulate(pts: DoubleArray, n: Int, out: DoubleList) {
        if (n < 3 || n > MAX_POINTS) return

        // Shoelace formula: positive = counter-clockwise.
        var area2 = 0.0
        for (i in 0 until n) {
            val j = if (i + 1 == n) 0 else i + 1
            area2 += pts[2 * i] * pts[2 * j + 1] - pts[2 * j] * pts[2 * i + 1]
        }
        if (area2 == 0.0) return
        if (n == 3) {
            out.add(pts[0], pts[1]); out.add(pts[2], pts[3]); out.add(pts[4], pts[5])
            return
        }

        if (isConvex(pts, n, area2 > 0)) {
            for (i in 1 until n - 1) {
                out.add(pts[0], pts[1])
                out.add(pts[2 * i], pts[2 * i + 1])
                out.add(pts[2 * i + 2], pts[2 * i + 3])
            }
            return
        }
        earClip(pts, n, area2 > 0, out)
    }

    /** True if every turn goes the same way as the polygon's orientation (or is straight). */
    private fun isConvex(pts: DoubleArray, n: Int, ccw: Boolean): Boolean {
        for (i in 0 until n) {
            val p = if (i == 0) n - 1 else i - 1
            val q = if (i + 1 == n) 0 else i + 1
            val cross = (pts[2 * i] - pts[2 * p]) * (pts[2 * q + 1] - pts[2 * i + 1]) -
                (pts[2 * i + 1] - pts[2 * p + 1]) * (pts[2 * q] - pts[2 * i])
            if (if (ccw) cross < 0 else cross > 0) return false
        }
        return true
    }

    private fun earClip(pts: DoubleArray, n: Int, ccw: Boolean, out: DoubleList) {
        // Work on a counter-clockwise copy so "convex vertex" always means cross > 0.
        val x = DoubleArray(n)
        val y = DoubleArray(n)
        for (i in 0 until n) {
            val s = if (ccw) i else n - 1 - i
            x[i] = pts[2 * s]
            y[i] = pts[2 * s + 1]
        }
        // Doubly linked ring of the vertices that are still left.
        val prev = IntArray(n) { if (it == 0) n - 1 else it - 1 }
        val next = IntArray(n) { if (it + 1 == n) 0 else it + 1 }
        var remaining = n
        var cur = 0
        var stall = 0

        while (remaining > 3) {
            val p = prev[cur]
            val q = next[cur]
            val cross = (x[cur] - x[p]) * (y[q] - y[cur]) - (y[cur] - y[p]) * (x[q] - x[cur])
            var remove = false
            var emit = false
            if (cross == 0.0) {
                remove = true // collinear point or zero-width spike: nothing to draw
            } else if (cross > 0 && isEar(x, y, next, p, cur, q)) {
                remove = true
                emit = true
            } else if (++stall > remaining) {
                // No ear found in a whole pass (bad polygon): drop a vertex so we always finish.
                remove = true
                emit = cross > 0
            }
            if (remove) {
                if (emit) emitTriangle(x, y, p, cur, q, out)
                next[p] = q
                prev[q] = p
                remaining--
                cur = p
                stall = 0
            } else {
                cur = next[cur]
            }
        }
        if (remaining == 3) {
            val p = prev[cur]
            val q = next[cur]
            val cross = (x[cur] - x[p]) * (y[q] - y[cur]) - (y[cur] - y[p]) * (x[q] - x[cur])
            if (cross > 0) emitTriangle(x, y, p, cur, q, out)
        }
    }

    private fun emitTriangle(x: DoubleArray, y: DoubleArray, a: Int, b: Int, c: Int, out: DoubleList) {
        out.add(x[a], y[a]); out.add(x[b], y[b]); out.add(x[c], y[c])
    }

    /**
     * True if triangle (p, cur, q) contains no other remaining vertex. Vertices at the same
     * coordinates as a triangle corner are skipped: "keyhole" polygons (holes connected to the
     * outline by a cut line) visit the same point twice.
     */
    private fun isEar(x: DoubleArray, y: DoubleArray, next: IntArray, p: Int, cur: Int, q: Int): Boolean {
        val minX = minOf(x[p], x[cur], x[q])
        val maxX = maxOf(x[p], x[cur], x[q])
        val minY = minOf(y[p], y[cur], y[q])
        val maxY = maxOf(y[p], y[cur], y[q])
        var v = next[q]
        while (v != p) {
            val vx = x[v]
            val vy = y[v]
            v = next[v]
            if (vx < minX || vx > maxX || vy < minY || vy > maxY) continue
            if ((vx == x[p] && vy == y[p]) || (vx == x[cur] && vy == y[cur]) || (vx == x[q] && vy == y[q])) continue
            // Inside (inclusive) if the point is on the left of, or on, all three edges (CCW triangle).
            if (side(x[p], y[p], x[cur], y[cur], vx, vy) >= 0 &&
                side(x[cur], y[cur], x[q], y[q], vx, vy) >= 0 &&
                side(x[q], y[q], x[p], y[p], vx, vy) >= 0
            ) return false
        }
        return true
    }

    /** Positive if (px, py) is left of the directed line a -> b. */
    private fun side(ax: Double, ay: Double, bx: Double, by: Double, px: Double, py: Double): Double =
        (bx - ax) * (py - ay) - (by - ay) * (px - ax)
}
