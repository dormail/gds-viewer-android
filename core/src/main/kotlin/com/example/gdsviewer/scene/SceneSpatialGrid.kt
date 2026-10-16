package com.example.gdsviewer.scene

import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sqrt

/** Helpers to sort items (identified by an int id with a center point) into spatial groups. */
internal object SpatialGrid {
    /**
     * Sorts [items] (ids into [cx] / [cy], ascending) into the cells of a roughly square grid
     * over their centers, sized for [perCell] items per cell on average. Returns the non-empty
     * cells; ids stay ascending within each.
     */
    fun grid(cx: DoubleArray, cy: DoubleArray, items: IntArray, perCell: Int): List<IntArray> {
        if (items.size <= perCell) return listOf(items)
        val b = centerBounds(cx, cy, items)
        val w = b[2] - b[0]
        val h = b[3] - b[1]
        if (w == 0.0 && h == 0.0) return listOf(items)
        val target = items.size / perCell + 1
        val nx: Int
        val ny: Int
        if (h == 0.0) { nx = target; ny = 1 }
        else if (w == 0.0) { nx = 1; ny = target }
        else {
            val side = sqrt(w * h / target)
            nx = ceil(w / side).toInt().coerceIn(1, 2 * target)
            ny = ceil(h / side).toInt().coerceIn(1, 2 * target)
        }
        return distribute(cx, cy, items, b, nx, ny)
    }

    /** Splits [items] in four at the middle of their centers' bounding box; null if that does not separate them. */
    fun quad(cx: DoubleArray, cy: DoubleArray, items: IntArray): List<IntArray>? {
        val b = centerBounds(cx, cy, items)
        if (b[2] == b[0] && b[3] == b[1]) return null
        val nx = if (b[2] > b[0]) 2 else 1
        val ny = if (b[3] > b[1]) 2 else 1
        return distribute(cx, cy, items, b, nx, ny)
    }

    private fun distribute(cx: DoubleArray, cy: DoubleArray, items: IntArray, b: DoubleArray, nx: Int, ny: Int): List<IntArray> {
        val w = b[2] - b[0]
        val h = b[3] - b[1]
        val tiles = IntArray(items.size)
        val counts = IntArray(nx * ny + 1)
        for (k in items.indices) {
            val id = items[k]
            val ix = if (w > 0) min(nx - 1, ((cx[id] - b[0]) / w * nx).toInt()) else 0
            val iy = if (h > 0) min(ny - 1, ((cy[id] - b[1]) / h * ny).toInt()) else 0
            val t = iy * nx + ix
            tiles[k] = t
            counts[t + 1]++
        }
        for (t in 0 until nx * ny) counts[t + 1] += counts[t]
        val sorted = IntArray(items.size)
        val fill = counts.copyOf()
        for (k in items.indices) sorted[fill[tiles[k]]++] = items[k]
        val result = ArrayList<IntArray>()
        for (t in 0 until nx * ny) if (counts[t + 1] > counts[t]) result += sorted.copyOfRange(counts[t], counts[t + 1])
        return result
    }

    private fun centerBounds(cx: DoubleArray, cy: DoubleArray, items: IntArray): DoubleArray {
        var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
        for (id in items) {
            if (cx[id] < x0) x0 = cx[id]; if (cx[id] > x1) x1 = cx[id]
            if (cy[id] < y0) y0 = cy[id]; if (cy[id] > y1) y1 = cy[id]
        }
        return doubleArrayOf(x0, y0, x1, y1)
    }
}
