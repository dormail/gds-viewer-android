package com.example.gdsviewer.scene

import com.example.gdsviewer.db.Library
import kotlin.math.abs
import kotlin.math.max

/**
 * Bounding boxes of all cells, including everything they place (computed once, bottom-up, in the
 * constructor). Cyclic references are ignored. Thread-safe after construction.
 */
class CellBounds(val library: Library) {
    private val boxes: Array<Box?>
    private val layerBoxes: Array<LayerBoxes>

    init {
        val cells = library.cells
        val n = cells.size
        val order = IntArray(n)
        // rank[c] = position of c in the finishing order of a depth-first search. A reference
        // c -> d with rank[d] >= rank[c] points to a cell that is still "open" (an ancestor or c
        // itself), i.e. it closes a cycle, and is ignored; all others point to finished cells.
        val rank = IntArray(n)
        depthFirstOrder(order, rank)

        val layerCount = library.layers.size
        val acc = DoubleArray(4 * layerCount) // per layer minX, minY, maxX, maxY; min > max = unused
        resetAcc(acc)
        // The layers a cell touches, so that per-cell work does not grow with the total number of layers.
        val used = Used(layerCount)
        val m = RefMatrix()
        boxes = arrayOfNulls(n)
        layerBoxes = Array(n) { LayerBoxes.EMPTY }

        for (cell in order) {
            val c = cells[cell]
            addOwnShapes(c, acc, used)
            val refs = c.refs
            for (i in 0 until refs.size) {
                val child = refs.cell[i]
                if (rank[child] >= rank[cell]) continue
                val cl = layerBoxes[child]
                if (cl.size == 0) continue
                m.load(refs, i)
                for (k in 0 until m.cornerCount) {
                    val tx = m.cornerX(k)
                    val ty = m.cornerY(k)
                    for (j in 0 until cl.size) {
                        m.mapBox(cl.boxes[4 * j], cl.boxes[4 * j + 1], cl.boxes[4 * j + 2], cl.boxes[4 * j + 3], tx, ty)
                        used.add(cl.layers[j])
                        val s = 4 * cl.layers[j]
                        if (m.minX < acc[s]) acc[s] = m.minX
                        if (m.minY < acc[s + 1]) acc[s + 1] = m.minY
                        if (m.maxX > acc[s + 2]) acc[s + 2] = m.maxX
                        if (m.maxY > acc[s + 3]) acc[s + 3] = m.maxY
                    }
                }
            }
            extract(cell, acc, used)
        }
    }

    /** Bounding box of the cell including all children (DBU), or null if there are no shapes anywhere below it. */
    fun bounds(cell: Int): Box? = boxes[cell]

    /** Per-layer bounding boxes of the cell including all children (DBU). */
    fun layerBounds(cell: Int): LayerBoxes = layerBoxes[cell]

    /** Fills [order] with the cells children-first, and [rank] with each cell's position in it. Iterative, so deep hierarchies are fine. */
    private fun depthFirstOrder(order: IntArray, rank: IntArray) {
        val cells = library.cells
        val n = cells.size
        val state = ByteArray(n) // 0 = new, 1 = on the stack, 2 = finished
        val stackCell = IntArray(n)
        val stackPos = IntArray(n)
        var done = 0
        for (root in 0 until n) {
            if (state[root].toInt() != 0) continue
            var sp = 0
            stackCell[0] = root; stackPos[0] = 0; state[root] = 1
            while (sp >= 0) {
                val cell = stackCell[sp]
                val refs = cells[cell].refs
                var p = stackPos[sp]
                while (p < refs.size && state[refs.cell[p]].toInt() != 0) p++
                if (p < refs.size) {
                    stackPos[sp] = p + 1
                    val child = refs.cell[p]
                    state[child] = 1
                    sp++
                    stackCell[sp] = child; stackPos[sp] = 0
                } else {
                    state[cell] = 2
                    rank[cell] = done
                    order[done++] = cell
                    sp--
                }
            }
        }
    }

    private fun addOwnShapes(c: com.example.gdsviewer.db.Cell, acc: DoubleArray, used: Used) {
        val r = c.rects
        for (i in 0 until r.size) {
            val k = 4 * i
            grow(acc, used, r.layer[i], r.xy[k].toDouble(), r.xy[k + 1].toDouble(), r.xy[k + 2].toDouble(), r.xy[k + 3].toDouble())
        }
        val p = c.polygons
        for (i in 0 until p.size) {
            var x0 = Int.MAX_VALUE; var y0 = Int.MAX_VALUE; var x1 = Int.MIN_VALUE; var y1 = Int.MIN_VALUE
            for (k in p.start[i] until p.start[i + 1]) {
                val x = p.xy[2 * k]; val y = p.xy[2 * k + 1]
                if (x < x0) x0 = x; if (x > x1) x1 = x
                if (y < y0) y0 = y; if (y > y1) y1 = y
            }
            if (x0 <= x1) grow(acc, used, p.layer[i], x0.toDouble(), y0.toDouble(), x1.toDouble(), y1.toDouble())
        }
        val w = c.paths
        for (i in 0 until w.size) {
            var x0 = Int.MAX_VALUE; var y0 = Int.MAX_VALUE; var x1 = Int.MIN_VALUE; var y1 = Int.MIN_VALUE
            for (k in w.start[i] until w.start[i + 1]) {
                val x = w.xy[2 * k]; val y = w.xy[2 * k + 1]
                if (x < x0) x0 = x; if (x > x1) x1 = x
                if (y < y0) y0 = y; if (y > y1) y1 = y
            }
            if (x0 > x1) continue
            val g = pathGrow(w.width[i], w.type[i], w.beginExtension[i], w.endExtension[i])
            grow(acc, used, w.layer[i], x0 - g, y0 - g, x1 + g, y1 + g)
        }
    }

    private fun grow(acc: DoubleArray, used: Used, layer: Int, x0: Double, y0: Double, x1: Double, y1: Double) {
        used.add(layer)
        val s = 4 * layer
        if (x0 < acc[s]) acc[s] = x0
        if (y0 < acc[s + 1]) acc[s + 1] = y0
        if (x1 > acc[s + 2]) acc[s + 2] = x1
        if (y1 > acc[s + 3]) acc[s + 3] = y1
    }

    /** Moves the finished accumulator of [cell] into the result arrays and resets it. */
    private fun extract(cell: Int, acc: DoubleArray, used: Used) {
        val count = used.sortedCount()
        var n = 0
        for (i in 0 until count) if (acc[4 * used.list[i]] <= acc[4 * used.list[i] + 2]) n++
        if (n > 0) {
            val layers = IntArray(n)
            val b = DoubleArray(4 * n)
            var k = 0
            var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
            for (i in 0 until count) {
                val l = used.list[i]
                if (acc[4 * l] > acc[4 * l + 2]) continue
                layers[k] = l
                System.arraycopy(acc, 4 * l, b, 4 * k, 4)
                x0 = minOf(x0, acc[4 * l]); y0 = minOf(y0, acc[4 * l + 1])
                x1 = max(x1, acc[4 * l + 2]); y1 = max(y1, acc[4 * l + 3])
                k++
            }
            layerBoxes[cell] = LayerBoxes(layers, b)
            boxes[cell] = Box(x0, y0, x1, y1)
        }
        for (i in 0 until count) {
            val l = used.list[i]
            acc[4 * l] = Double.MAX_VALUE; acc[4 * l + 1] = Double.MAX_VALUE
            acc[4 * l + 2] = -Double.MAX_VALUE; acc[4 * l + 3] = -Double.MAX_VALUE
        }
        used.clear()
    }

    private fun resetAcc(acc: DoubleArray) {
        for (l in 0 until acc.size / 4) {
            acc[4 * l] = Double.MAX_VALUE; acc[4 * l + 1] = Double.MAX_VALUE
            acc[4 * l + 2] = -Double.MAX_VALUE; acc[4 * l + 3] = -Double.MAX_VALUE
        }
    }

    /** The set of layers touched while collecting one cell. */
    private class Used(layerCount: Int) {
        private val seen = BooleanArray(layerCount)
        var list = IntArray(16)
        private var size = 0

        fun add(layer: Int) {
            if (seen[layer]) return
            seen[layer] = true
            if (size == list.size) list = list.copyOf(size * 2)
            list[size++] = layer
        }

        /** Sorts the list ascending and returns its length. */
        fun sortedCount(): Int {
            java.util.Arrays.sort(list, 0, size)
            return size
        }

        fun clear() {
            for (i in 0 until size) seen[list[i]] = false
            size = 0
        }
    }

    internal companion object {
        /** How far a path's outline can reach beyond its center line points (generous: the same in all directions). */
        fun pathGrow(width: Int, type: Int, beginExtension: Int, endExtension: Int): Double {
            val hw = abs(width.toDouble()) / 2
            return hw + when (type) {
                2 -> hw
                4 -> max(abs(beginExtension), abs(endExtension)).toDouble()
                else -> 0.0
            }
        }
    }
}
