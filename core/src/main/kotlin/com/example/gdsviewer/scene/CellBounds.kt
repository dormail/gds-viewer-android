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
        val m = RefMatrix()
        boxes = arrayOfNulls(n)
        layerBoxes = Array(n) { LayerBoxes.EMPTY }

        for (cell in order) {
            val c = cells[cell]
            addOwnShapes(c, acc)
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
                        val s = 4 * cl.layers[j]
                        if (m.minX < acc[s]) acc[s] = m.minX
                        if (m.minY < acc[s + 1]) acc[s + 1] = m.minY
                        if (m.maxX > acc[s + 2]) acc[s + 2] = m.maxX
                        if (m.maxY > acc[s + 3]) acc[s + 3] = m.maxY
                    }
                }
            }
            extract(cell, acc)
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

    private fun addOwnShapes(c: com.example.gdsviewer.db.Cell, acc: DoubleArray) {
        val r = c.rects
        for (i in 0 until r.size) {
            val s = 4 * r.layer[i]
            val k = 4 * i
            grow(acc, s, r.xy[k].toDouble(), r.xy[k + 1].toDouble(), r.xy[k + 2].toDouble(), r.xy[k + 3].toDouble())
        }
        val p = c.polygons
        for (i in 0 until p.size) {
            var x0 = Int.MAX_VALUE; var y0 = Int.MAX_VALUE; var x1 = Int.MIN_VALUE; var y1 = Int.MIN_VALUE
            for (k in p.start[i] until p.start[i + 1]) {
                val x = p.xy[2 * k]; val y = p.xy[2 * k + 1]
                if (x < x0) x0 = x; if (x > x1) x1 = x
                if (y < y0) y0 = y; if (y > y1) y1 = y
            }
            if (x0 <= x1) grow(acc, 4 * p.layer[i], x0.toDouble(), y0.toDouble(), x1.toDouble(), y1.toDouble())
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
            grow(acc, 4 * w.layer[i], x0 - g, y0 - g, x1 + g, y1 + g)
        }
    }

    private fun grow(acc: DoubleArray, s: Int, x0: Double, y0: Double, x1: Double, y1: Double) {
        if (x0 < acc[s]) acc[s] = x0
        if (y0 < acc[s + 1]) acc[s + 1] = y0
        if (x1 > acc[s + 2]) acc[s + 2] = x1
        if (y1 > acc[s + 3]) acc[s + 3] = y1
    }

    /** Moves the finished accumulator of [cell] into the result arrays and resets it. */
    private fun extract(cell: Int, acc: DoubleArray) {
        var used = 0
        for (l in 0 until acc.size / 4) if (acc[4 * l] <= acc[4 * l + 2]) used++
        if (used == 0) return
        val layers = IntArray(used)
        val b = DoubleArray(4 * used)
        var k = 0
        var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
        for (l in 0 until acc.size / 4) {
            if (acc[4 * l] > acc[4 * l + 2]) continue
            layers[k] = l
            System.arraycopy(acc, 4 * l, b, 4 * k, 4)
            x0 = minOf(x0, acc[4 * l]); y0 = minOf(y0, acc[4 * l + 1])
            x1 = max(x1, acc[4 * l + 2]); y1 = max(y1, acc[4 * l + 3])
            k++
        }
        layerBoxes[cell] = LayerBoxes(layers, b)
        boxes[cell] = Box(x0, y0, x1, y1)
        resetAcc(acc)
    }

    private fun resetAcc(acc: DoubleArray) {
        for (l in 0 until acc.size / 4) {
            acc[4 * l] = Double.MAX_VALUE; acc[4 * l + 1] = Double.MAX_VALUE
            acc[4 * l + 2] = -Double.MAX_VALUE; acc[4 * l + 3] = -Double.MAX_VALUE
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
