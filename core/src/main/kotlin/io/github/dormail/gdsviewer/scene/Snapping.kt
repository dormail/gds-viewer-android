package io.github.dormail.gdsviewer.scene

import io.github.dormail.gdsviewer.db.Cell
import io.github.dormail.gdsviewer.geometry.Affine
import io.github.dormail.gdsviewer.geometry.DoubleList
import io.github.dormail.gdsviewer.geometry.PathGeometry
import java.util.concurrent.CancellationException

/** Finds the closest shape corner or edge to a point, for the ruler (works on the hierarchy, nothing is flattened). */
object Snapping {
    private const val MAX_DEPTH = 64
    private const val MAX_SHAPES = 300_000

    /** Limit of steps (cell instances, shapes and outline points looked at) per call: well under 100 ms on a desktop. */
    private const val MAX_WORK = 1_500_000L

    /**
     * Returns the snapped point as doubleArrayOf(x, y) in world µm, or null if nothing is within
     * [radius] (world µm). Corners within radius / 2 win over edges. Only shapes on layers with
     * visibleLayers[layerIndex] == true count.
     *
     * Throws [CancellationException] when [isCancelled] returns true (polled regularly). Hostile
     * files (huge arrays, reference loops, enormous polygons) cannot make it run long: after a
     * fixed amount of work it stops looking and returns the best point found so far.
     */
    fun snap(
        index: LibraryIndex,
        frame: CellFrame,
        visibleLayers: BooleanArray,
        x: Double,
        y: Double,
        radius: Double,
        isCancelled: () -> Boolean = { false },
    ): DoubleArray? {
        if (frame.isEmpty) return null
        val search = Search(index, visibleLayers, x, y, radius, isCancelled)
        search.visit(frame.cell, frame.root, 0)
        return search.result()
    }

    private class Search(
        val index: LibraryIndex,
        val visibleLayers: BooleanArray,
        val x: Double,
        val y: Double,
        val radius: Double,
        val isCancelled: () -> Boolean,
    ) {
        private val query = Box(x - radius, y - radius, x + radius, y + radius)
        private val library = index.library
        private var examined = 0
        private var polls = 0
        private var work = 0L

        private var bestCorner = (radius / 2) * (radius / 2)
        private var cornerX = 0.0; private var cornerY = 0.0; private var foundCorner = false
        private var bestEdge = radius * radius
        private var edgeX = 0.0; private var edgeY = 0.0; private var foundEdge = false

        private val tris = DoubleList()
        private val lines = DoubleList()

        fun result(): DoubleArray? = when {
            foundCorner -> doubleArrayOf(cornerX, cornerY)
            foundEdge -> doubleArrayOf(edgeX, edgeY)
            else -> null
        }

        private fun poll() {
            if ((polls++ and 1023) == 0 && isCancelled()) throw CancellationException("Snapping cancelled")
        }

        fun visit(cell: Int, t: Affine, depth: Int) {
            if (depth > MAX_DEPTH || examined > MAX_SHAPES || work > MAX_WORK) return
            work++
            poll()
            val b = index.bounds.bounds(cell) ?: return
            if (!b.transformed(t).intersects(query)) return
            val content = index.content(cell)
            val c = library.cells[cell]

            if (content.chunks.isNotEmpty()) {
                val q = query.transformed(t.inverse()) // the query area in this cell's DBU
                for (chunk in content.chunks) {
                    if (chunk.bounds.intersects(q)) examineChunk(c, chunk, t, q)
                }
            }

            val refs = c.refs
            val m = RefMatrix()
            for (bucket in content.refBuckets) {
                if (!bucket.bounds.transformed(t).intersects(query)) continue
                for (r in bucket.refs) {
                    val child = refs.cell[r]
                    val cb = index.bounds.bounds(child) ?: continue
                    if (!refs.isArray(r)) {
                        visit(child, t * refs.transform(r), depth + 1)
                        continue
                    }
                    m.load(refs, r)
                    m.mapBox(cb.minX, cb.minY, cb.maxX, cb.maxY, m.x, m.y)
                    val range = ArrayRange.visible(
                        m.minX, m.minY, m.maxX, m.maxY, m.colX, m.colY, m.rowX, m.rowY, m.cols, m.rows,
                        query.transformed(t.inverse()),
                    ) ?: continue
                    for (row in range[2]..range[3]) {
                        for (col in range[0]..range[1]) {
                            visit(child, t * refs.transform(r, col, row), depth + 1)
                            if (examined > MAX_SHAPES || ++work > MAX_WORK) return
                        }
                    }
                }
            }
        }

        private fun examineChunk(cell: Cell, chunk: Chunk, t: Affine, q: Box) {
            val rs = cell.rects
            val rects = chunk.rects
            for (k in 0 until (rects?.size ?: rs.size)) {
                val i = rects?.get(k) ?: k
                if (++work > MAX_WORK) return
                if (!visible(rs.layer[i])) continue
                val x0 = rs.xy[4 * i].toDouble(); val y0 = rs.xy[4 * i + 1].toDouble()
                val x1 = rs.xy[4 * i + 2].toDouble(); val y1 = rs.xy[4 * i + 3].toDouble()
                if (!overlaps(q, x0, y0, x1, y1)) continue
                if (++examined > MAX_SHAPES) return
                val px = doubleArrayOf(x0, x1, x1, x0)
                val py = doubleArrayOf(y0, y0, y1, y1)
                closedRing(t, px, py, 4)
            }

            val ps = cell.polygons
            val polys = chunk.polygons
            for (k in 0 until (polys?.size ?: ps.size)) {
                val i = polys?.get(k) ?: k
                if (!visible(ps.layer[i])) continue
                val n = ps.pointCount(i)
                work += n
                if (work > MAX_WORK) return
                val first = ps.start[i]
                var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
                for (j in 0 until n) {
                    val px = ps.xy[2 * (first + j)].toDouble(); val py = ps.xy[2 * (first + j) + 1].toDouble()
                    if (px < x0) x0 = px; if (px > x1) x1 = px
                    if (py < y0) y0 = py; if (py > y1) y1 = py
                }
                if (!overlaps(q, x0, y0, x1, y1)) continue
                if (++examined > MAX_SHAPES) return
                val px = DoubleArray(n) { ps.xy[2 * (first + it)].toDouble() }
                val py = DoubleArray(n) { ps.xy[2 * (first + it) + 1].toDouble() }
                closedRing(t, px, py, n)
            }

            val ws = cell.paths
            val paths = chunk.paths
            for (k in 0 until (paths?.size ?: ws.size)) {
                val i = paths?.get(k) ?: k
                if (!visible(ws.layer[i])) continue
                val n = ws.pointCount(i)
                work += 4L * n // building the outline costs more than a polygon's points
                if (work > MAX_WORK) return
                val first = ws.start[i]
                // Generous growth: half the width, times 4 for sharp mitered corners, plus end extensions.
                val grow = 2.0 * Math.abs(ws.width[i].toDouble()) +
                    maxOf(Math.abs(ws.beginExtension[i]), Math.abs(ws.endExtension[i])).toDouble()
                var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
                for (j in 0 until n) {
                    val px = ws.xy[2 * (first + j)].toDouble(); val py = ws.xy[2 * (first + j) + 1].toDouble()
                    if (px < x0) x0 = px; if (px > x1) x1 = px
                    if (py < y0) y0 = py; if (py > y1) y1 = py
                }
                if (!overlaps(q, x0 - grow, y0 - grow, x1 + grow, y1 + grow)) continue
                if (++examined > MAX_SHAPES) return
                tris.clear(); lines.clear()
                PathGeometry.build(
                    ws.xy, first, n, ws.width[i], ws.type[i], ws.beginExtension[i], ws.endExtension[i], tris, lines,
                )
                var j = 0
                while (j + 3 < lines.size) {
                    segment(
                        t.mapX(lines.data[j], lines.data[j + 1]), t.mapY(lines.data[j], lines.data[j + 1]),
                        t.mapX(lines.data[j + 2], lines.data[j + 3]), t.mapY(lines.data[j + 2], lines.data[j + 3]),
                    )
                    j += 4
                }
                poll()
            }
        }

        private fun visible(layer: Int) = layer < visibleLayers.size && visibleLayers[layer]

        private fun overlaps(q: Box, x0: Double, y0: Double, x1: Double, y1: Double) =
            x0 <= q.maxX && q.minX <= x1 && y0 <= q.maxY && q.minY <= y1

        /** Candidates of a closed outline with [n] points (cell coordinates), transformed to world by [t]. */
        private fun closedRing(t: Affine, px: DoubleArray, py: DoubleArray, n: Int) {
            for (j in 0 until n) {
                val k = if (j + 1 == n) 0 else j + 1
                segment(t.mapX(px[j], py[j]), t.mapY(px[j], py[j]), t.mapX(px[k], py[k]), t.mapY(px[k], py[k]))
            }
            poll()
        }

        /** Considers both end points as corners and the closest point of the segment as an edge point (world µm). */
        private fun segment(x1: Double, y1: Double, x2: Double, y2: Double) {
            val d1 = sq(x1 - x, y1 - y)
            if (d1 <= bestCorner) { bestCorner = d1; cornerX = x1; cornerY = y1; foundCorner = true }
            val d2 = sq(x2 - x, y2 - y)
            if (d2 <= bestCorner) { bestCorner = d2; cornerX = x2; cornerY = y2; foundCorner = true }
            val dx = x2 - x1
            val dy = y2 - y1
            val len2 = dx * dx + dy * dy
            val s = if (len2 == 0.0) 0.0 else (((x - x1) * dx + (y - y1) * dy) / len2).coerceIn(0.0, 1.0)
            val qx = x1 + s * dx
            val qy = y1 + s * dy
            val d = sq(qx - x, qy - y)
            if (d <= bestEdge) { bestEdge = d; edgeX = qx; edgeY = qy; foundEdge = true }
        }

        private fun sq(dx: Double, dy: Double) = dx * dx + dy * dy
    }
}
