package com.example.gdsviewer.scene

import com.example.gdsviewer.db.Library

object LayerStats {
    /**
     * Number of shapes per layer index when [topCell] is fully expanded (each placement counts its
     * cell's shapes again, arrays count columns * rows times). Layers not used below [topCell] are 0.
     */
    fun shapeCounts(library: Library, topCell: Int): LongArray {
        val cells = library.cells
        val n = cells.size
        val result = LongArray(library.layers.size)
        if (topCell !in 0 until n) return result

        // Depth-first search from the top cell; `order` gets the cells children-first. A reference
        // to a cell that is still open (on the stack) closes a cycle and is ignored.
        val order = IntArray(n)
        val rank = IntArray(n) { -1 }
        val state = ByteArray(n) // 0 = new, 1 = open, 2 = finished
        val stackCell = IntArray(n)
        val stackPos = IntArray(n)
        var finished = 0
        var sp = 0
        stackCell[0] = topCell; stackPos[0] = 0; state[topCell] = 1
        while (sp >= 0) {
            val cell = stackCell[sp]
            val refs = cells[cell].refs
            var p = stackPos[sp]
            var descended = false
            while (p < refs.size) {
                val child = refs.cell[p++]
                if (state[child].toInt() == 0) {
                    stackPos[sp] = p
                    state[child] = 1
                    sp++
                    stackCell[sp] = child; stackPos[sp] = 0
                    descended = true
                    break
                }
            }
            if (descended) continue
            state[cell] = 2
            rank[cell] = finished
            order[finished++] = cell
            sp--
        }

        // Parents before children: placements of a cell = sum over its parents of (their count * array size).
        // Counted in Double, as nested arrays can exceed the Long range.
        val placements = DoubleArray(n)
        placements[topCell] = 1.0
        val sums = DoubleArray(result.size)
        for (k in finished - 1 downTo 0) {
            val cell = order[k]
            val count = placements[cell]
            val c = cells[cell]
            val refs = c.refs
            for (i in 0 until refs.size) {
                val child = refs.cell[i]
                if (rank[child] < 0 || rank[child] >= rank[cell]) continue // cycle edge
                placements[child] += count * refs.columns(i).toDouble() * refs.rows(i).toDouble()
            }
            for (l in c.rects.layer) sums[l] += count
            for (l in c.polygons.layer) sums[l] += count
            for (l in c.paths.layer) sums[l] += count
        }
        for (l in sums.indices) result[l] = if (sums[l] >= Long.MAX_VALUE.toDouble()) Long.MAX_VALUE else sums[l].toLong()
        return result
    }
}
