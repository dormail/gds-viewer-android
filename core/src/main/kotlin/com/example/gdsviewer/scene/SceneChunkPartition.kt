package com.example.gdsviewer.scene

import com.example.gdsviewer.db.Cell
import com.example.gdsviewer.db.Library
import kotlin.math.roundToInt

/** Splits the own shapes of a cell into [Chunk]s, and its placements into [RefBucket]s. */
internal object ChunkPartition {
    const val MAX_SINGLE_SHAPES = 2048
    const val TARGET_SHAPES = 2048
    const val MAX_TILE_SHAPES = 4096
    const val MAX_VERTICES = 65535
    private const val MAX_DEPTH = 24
    private const val BUCKET_SIZE = 256

    /**
     * Shapes of a cell numbered 0 until n: rectangles first, then polygons, then paths, each in
     * store order. Holds the bounding box and center of every shape.
     */
    private class Shapes(val cell: Cell, library: Library) {
        val r = cell.rects.size
        val p = cell.polygons.size
        val n = r + p + cell.paths.size
        val box = DoubleArray(4 * n)
        val cx = DoubleArray(n)
        val cy = DoubleArray(n)

        init {
            val rs = cell.rects
            for (i in 0 until r) set(i, rs.xy[4 * i].toDouble(), rs.xy[4 * i + 1].toDouble(), rs.xy[4 * i + 2].toDouble(), rs.xy[4 * i + 3].toDouble())
            val ps = cell.polygons
            for (i in 0 until p) {
                var x0 = Int.MAX_VALUE; var y0 = Int.MAX_VALUE; var x1 = Int.MIN_VALUE; var y1 = Int.MIN_VALUE
                for (k in ps.start[i] until ps.start[i + 1]) {
                    val x = ps.xy[2 * k]; val y = ps.xy[2 * k + 1]
                    if (x < x0) x0 = x; if (x > x1) x1 = x
                    if (y < y0) y0 = y; if (y > y1) y1 = y
                }
                set(r + i, x0.toDouble(), y0.toDouble(), x1.toDouble(), y1.toDouble())
            }
            val ws = cell.paths
            for (i in 0 until ws.size) {
                var x0 = Int.MAX_VALUE; var y0 = Int.MAX_VALUE; var x1 = Int.MIN_VALUE; var y1 = Int.MIN_VALUE
                for (k in ws.start[i] until ws.start[i + 1]) {
                    val x = ws.xy[2 * k]; val y = ws.xy[2 * k + 1]
                    if (x < x0) x0 = x; if (x > x1) x1 = x
                    if (y < y0) y0 = y; if (y > y1) y1 = y
                }
                val g = CellBounds.pathGrow(ws.width[i], ws.type[i], ws.beginExtension[i], ws.endExtension[i])
                set(r + p + i, x0 - g, y0 - g, x1 + g, y1 + g)
            }
        }

        private fun set(s: Int, x0: Double, y0: Double, x1: Double, y1: Double) {
            box[4 * s] = x0; box[4 * s + 1] = y0; box[4 * s + 2] = x1; box[4 * s + 3] = y1
            cx[s] = (x0 + x1) / 2; cy[s] = (y0 + y1) / 2
        }

        fun layer(s: Int): Int = if (s < r) cell.rects.layer[s] else if (s < r + p) cell.polygons.layer[s - r] else cell.paths.layer[s - r - p]

        /** Rough number of mesh vertices of shape [s]. */
        fun vertices(s: Int): Int = when {
            s < r -> 4
            s < r + p -> cell.polygons.pointCount(s - r)
            else -> 8 * cell.paths.pointCount(s - r - p) + 16
        }

        fun vertices(items: IntArray): Long {
            var sum = 0L
            for (s in items) sum += vertices(s)
            return sum
        }
    }

    /** Chunks of [cell]; ids are firstId, firstId + 1, ... where firstId = [allocateIds] (number of chunks). */
    fun chunks(library: Library, cell: Int, allocateIds: (Int) -> Int): List<Chunk> {
        val c = library.cells[cell]
        val shapes = Shapes(c, library)
        if (shapes.n == 0) return emptyList()
        val all = IntArray(shapes.n) { it }

        val groups = ArrayList<IntArray>()
        val single = shapes.n <= MAX_SINGLE_SHAPES && shapes.vertices(all) <= MAX_VERTICES
        if (single) {
            groups += all
        } else {
            for (tile in SpatialGrid.grid(shapes.cx, shapes.cy, all, TARGET_SHAPES)) split(shapes, tile, 0, groups)
        }
        val firstId = allocateIds(groups.size)
        return groups.mapIndexed { k, items -> makeChunk(library, shapes, firstId + k, cell, items, single) }
    }

    private fun split(shapes: Shapes, items: IntArray, depth: Int, out: MutableList<IntArray>) {
        val fits = items.size <= MAX_TILE_SHAPES && shapes.vertices(items) <= MAX_VERTICES
        if (items.size <= 1 || fits) {
            out += items
            return
        }
        val parts = if (depth >= MAX_DEPTH) null else SpatialGrid.quad(shapes.cx, shapes.cy, items)
        if (parts == null) {
            // Shapes that cannot be told apart by position (e.g. millions on the same spot): cut by count,
            // so that no chunk (and mesh) grows without limit.
            cutByCount(shapes, items, out)
            return
        }
        for (part in parts) split(shapes, part, depth + 1, out)
    }

    /** Cuts [items] (ascending) into consecutive pieces of at most MAX_TILE_SHAPES shapes and about MAX_VERTICES vertices. */
    private fun cutByCount(shapes: Shapes, items: IntArray, out: MutableList<IntArray>) {
        var start = 0
        var vertices = 0L
        for (k in items.indices) {
            val v = shapes.vertices(items[k])
            if (k > start && (k - start >= MAX_TILE_SHAPES || vertices + v > MAX_VERTICES)) {
                out += items.copyOfRange(start, k)
                start = k
                vertices = 0
            }
            vertices += v
        }
        out += items.copyOfRange(start, items.size)
    }

    private fun makeChunk(library: Library, s: Shapes, id: Int, cell: Int, items: IntArray, single: Boolean): Chunk {
        // Per-layer boxes, only for the layers the items use (not for all layers of the library).
        val itemLayers = IntArray(items.size) { s.layer(items[it]) }
        val sortedLayers = itemLayers.sortedArray()
        var distinct = 0
        for (k in sortedLayers.indices) if (k == 0 || sortedLayers[k] != sortedLayers[k - 1]) sortedLayers[distinct++] = sortedLayers[k]
        val layerIds = sortedLayers.copyOf(distinct)
        val boxes = DoubleArray(4 * layerIds.size)
        for (k in layerIds.indices) {
            boxes[4 * k] = Double.MAX_VALUE; boxes[4 * k + 1] = Double.MAX_VALUE
            boxes[4 * k + 2] = -Double.MAX_VALUE; boxes[4 * k + 3] = -Double.MAX_VALUE
        }
        var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
        var rc = 0; var pc = 0
        for ((j, it) in items.withIndex()) {
            val b = 4 * it
            val l = 4 * java.util.Arrays.binarySearch(layerIds, itemLayers[j])
            if (s.box[b] < boxes[l]) boxes[l] = s.box[b]
            if (s.box[b + 1] < boxes[l + 1]) boxes[l + 1] = s.box[b + 1]
            if (s.box[b + 2] > boxes[l + 2]) boxes[l + 2] = s.box[b + 2]
            if (s.box[b + 3] > boxes[l + 3]) boxes[l + 3] = s.box[b + 3]
            if (s.box[b] < x0) x0 = s.box[b]
            if (s.box[b + 1] < y0) y0 = s.box[b + 1]
            if (s.box[b + 2] > x1) x1 = s.box[b + 2]
            if (s.box[b + 3] > y1) y1 = s.box[b + 3]
            if (it < s.r) rc++ else if (it < s.r + s.p) pc++
        }

        // Median of max(width, height), from at most ~255 evenly spaced members.
        val sampleCount = minOf(items.size, 255)
        val sizes = DoubleArray(sampleCount) { j ->
            val b = 4 * items[(j.toLong() * items.size / sampleCount).toInt()]
            maxOf(s.box[b + 2] - s.box[b], s.box[b + 3] - s.box[b + 1])
        }
        sizes.sort()
        val typical = sizes[sampleCount / 2]

        var rects: IntArray? = null
        var polygons: IntArray? = null
        var paths: IntArray? = null
        if (!single) {
            // items are ascending, so they split into rects | polygons | paths at two points.
            rects = IntArray(rc) { items[it] }
            polygons = IntArray(pc) { items[rc + it] - s.r }
            paths = IntArray(items.size - rc - pc) { items[rc + pc + it] - s.r - s.p }
        }
        return Chunk(
            id, cell,
            ((x0 + x1) / 2).roundToInt(), ((y0 + y1) / 2).roundToInt(),
            Box(x0, y0, x1, y1), LayerBoxes(layerIds, boxes), items.size, typical,
            rects, polygons, paths,
        )
    }

    /** Placement buckets of [cell]; placements of cells without shapes are left out. */
    fun buckets(library: Library, bounds: CellBounds, cell: Int): List<RefBucket> {
        val refs = library.cells[cell].refs
        val total = refs.size
        if (total == 0) return emptyList()
        val boxes = DoubleArray(4 * total)
        val cx = DoubleArray(total)
        val cy = DoubleArray(total)
        val keep = IntArray(total)
        var n = 0
        val m = RefMatrix()
        for (i in 0 until total) {
            val cb = bounds.bounds(refs.cell[i]) ?: continue
            m.load(refs, i)
            m.mapAll(cb.minX, cb.minY, cb.maxX, cb.maxY)
            boxes[4 * i] = m.minX; boxes[4 * i + 1] = m.minY; boxes[4 * i + 2] = m.maxX; boxes[4 * i + 3] = m.maxY
            cx[i] = (m.minX + m.maxX) / 2; cy[i] = (m.minY + m.maxY) / 2
            keep[n++] = i
        }
        if (n == 0) return emptyList()
        val items = keep.copyOf(n)
        val groups = if (n <= BUCKET_SIZE) listOf(items) else SpatialGrid.grid(cx, cy, items, BUCKET_SIZE)
        return groups.map { g ->
            var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
            for (i in g) {
                if (boxes[4 * i] < x0) x0 = boxes[4 * i]
                if (boxes[4 * i + 1] < y0) y0 = boxes[4 * i + 1]
                if (boxes[4 * i + 2] > x1) x1 = boxes[4 * i + 2]
                if (boxes[4 * i + 3] > y1) y1 = boxes[4 * i + 3]
            }
            RefBucket(Box(x0, y0, x1, y1), g)
        }
    }
}
