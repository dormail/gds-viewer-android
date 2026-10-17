package io.github.dormail.gdsviewer.geometry

/**
 * Splits simple polygons into triangles. Convex polygons (nearly all of them: rectangles) use a
 * cheap triangle fan; other polygons use "ear clipping".
 */
internal object Triangulator {
    /**
     * Above this many points we only draw the outline. A GDS XY record holds at most 8191 points,
     * so files cannot exceed this; it guards other callers.
     */
    const val MAX_POINTS = 8192

    /**
     * Most steps ([isEar] looks at one remaining vertex per step) that ear clipping may take for
     * one polygon, about 30 ms. Ear clipping is O(n^2) and much worse for self-intersecting
     * polygons (random 8190-point polygons take seconds), so it gives up and the polygon is drawn
     * as outline only. Ordinary concave polygons of a few thousand points need far less.
     */
    const val MAX_EAR_CLIP_STEPS = 12_000_000L

    /**
     * [pts] = x0, y0, x1, y1, ... ([n] points, first point not repeated at the end).
     * Appends 3 vertices per triangle to [out].
     */
    fun triangulate(pts: DoubleArray, n: Int, out: DoubleList) {
        val idx = IntList()
        triangulateIndices(pts, n, idx, 0)
        for (k in 0 until idx.size) out.add(pts[2 * idx.data[k]], pts[2 * idx.data[k] + 1])
    }

    /**
     * Like [triangulate], but appends 3 indices per triangle to [out] instead of coordinates. An
     * index is the point's number in [pts] plus [base] (the vertex number of point 0 in the mesh).
     * Appends nothing for polygons with zero area, more than [MAX_POINTS] points, or
     * that need more than [MAX_EAR_CLIP_STEPS] steps (self-intersecting, hostile).
     */
    fun triangulateIndices(pts: DoubleArray, n: Int, out: IntList, base: Int) {
        if (n < 3 || n > MAX_POINTS) return

        // Shoelace formula: positive = counter-clockwise.
        var area2 = 0.0
        for (i in 0 until n) {
            val j = if (i + 1 == n) 0 else i + 1
            area2 += pts[2 * i] * pts[2 * j + 1] - pts[2 * j] * pts[2 * i + 1]
        }
        if (area2 == 0.0) return
        if (n == 3) {
            out.add(base, base + 1, base + 2)
            return
        }

        if (isConvex(pts, n, area2 > 0)) {
            for (i in 1 until n - 1) {
                out.add(base, base + i, base + i + 1)
            }
            return
        }
        val before = out.size
        if (!earClip(pts, n, area2 > 0, out, base)) out.truncate(before)
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

    /** Returns false if it gave up because of [MAX_EAR_CLIP_STEPS]; then [out] holds a partial result the caller must drop. */
    private fun earClip(pts: DoubleArray, n: Int, ccw: Boolean, out: IntList, base: Int): Boolean {
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
        val steps = LongArray(1)

        while (remaining > 3) {
            if (++steps[0] > MAX_EAR_CLIP_STEPS) return false
            val p = prev[cur]
            val q = next[cur]
            val cross = (x[cur] - x[p]) * (y[q] - y[cur]) - (y[cur] - y[p]) * (x[q] - x[cur])
            var remove = false
            var emit = false
            if (cross == 0.0) {
                remove = true // collinear point or zero-width spike: nothing to draw
            } else if (cross > 0 && isEar(x, y, next, p, cur, q, steps)) {
                remove = true
                emit = true
            } else if (++stall > remaining) {
                // No ear found in a whole pass (bad polygon): drop a vertex so we always finish.
                remove = true
                emit = cross > 0
            }
            if (remove) {
                if (emit) emitTriangle(ccw, n, base, p, cur, q, out)
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
            if (cross > 0) emitTriangle(ccw, n, base, p, cur, q, out)
        }
        return true
    }

    /** Emits a triangle given by positions in the counter-clockwise working copy as indices of the original points. */
    private fun emitTriangle(ccw: Boolean, n: Int, base: Int, a: Int, b: Int, c: Int, out: IntList) {
        if (ccw) out.add(base + a, base + b, base + c) else out.add(base + n - 1 - a, base + n - 1 - b, base + n - 1 - c)
    }

    /**
     * True if triangle (p, cur, q) contains no other remaining vertex. Vertices at the same
     * coordinates as a triangle corner are skipped: "keyhole" polygons (holes connected to the
     * outline by a cut line) visit the same point twice.
     */
    private fun isEar(x: DoubleArray, y: DoubleArray, next: IntArray, p: Int, cur: Int, q: Int, steps: LongArray): Boolean {
        val minX = minOf(x[p], x[cur], x[q])
        val maxX = maxOf(x[p], x[cur], x[q])
        val minY = minOf(y[p], y[cur], y[q])
        val maxY = maxOf(y[p], y[cur], y[q])
        var v = next[q]
        var scanned = 0L
        while (v != p) {
            scanned++
            val vx = x[v]
            val vy = y[v]
            v = next[v]
            if (vx < minX || vx > maxX || vy < minY || vy > maxY) continue
            if ((vx == x[p] && vy == y[p]) || (vx == x[cur] && vy == y[cur]) || (vx == x[q] && vy == y[q])) continue
            // Inside (inclusive) if the point is on the left of, or on, all three edges (CCW triangle).
            if (side(x[p], y[p], x[cur], y[cur], vx, vy) >= 0 &&
                side(x[cur], y[cur], x[q], y[q], vx, vy) >= 0 &&
                side(x[q], y[q], x[p], y[p], vx, vy) >= 0
            ) { steps[0] += scanned; return false }
        }
        steps[0] += scanned
        return true
    }

    /** Positive if (px, py) is left of the directed line a -> b. */
    private fun side(ax: Double, ay: Double, bx: Double, by: Double, px: Double, py: Double): Double =
        (bx - ax) * (py - ay) - (by - ay) * (px - ax)
}

/** A growable list of ints (no boxing). */
internal class IntList(capacity: Int = 64) {
    var data = IntArray(maxOf(capacity, 4))
        private set
    var size = 0
        private set

    fun add(a: Int) {
        if (size + 1 > data.size) grow(1)
        data[size++] = a
    }

    fun add(a: Int, b: Int) {
        if (size + 2 > data.size) grow(2)
        data[size++] = a; data[size++] = b
    }

    fun add(a: Int, b: Int, c: Int) {
        if (size + 3 > data.size) grow(3)
        data[size++] = a; data[size++] = b; data[size++] = c
    }

    private fun grow(extra: Int) {
        data = data.copyOf(maxOf(data.size * 2, size + extra))
    }

    fun clear() {
        size = 0
    }

    /** Drops everything after the first [newSize] entries. */
    fun truncate(newSize: Int) {
        size = newSize
    }

    fun toArray(): IntArray = data.copyOf(size)
}
