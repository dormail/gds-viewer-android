package io.github.dormail.gdsviewer.db

import io.github.dormail.gdsviewer.gds.GdsFormatException
import io.github.dormail.gdsviewer.gds.LayerKey
import kotlin.math.abs

/**
 * Puts several loaded layouts into ONE [Library], so they can be shown on top of each other
 * (overlay) by the normal drawing code.
 *
 * The big coordinate arrays are shared with the input libraries, not copied; only the small
 * per-shape layer numbers and per-placement cell numbers are rebuilt.
 */
object LibraryMerge {
    /** Most files that may be overlaid. */
    const val MAX_PARTS = 8

    /** One input: a loaded library, the cell to show from it, and its file number (1-based, shown as "@n"). */
    class Part(val library: Library, val topCell: Int, val file: Int)

    /** Builds one library that shows every part's top cell on top of each other, at the same origin. */
    fun merge(parts: List<Part>, topName: String = "Overlay"): Library {
        if (parts.isEmpty()) throw GdsFormatException("No files to show")
        if (parts.size > MAX_PARTS) throw GdsFormatException("At most $MAX_PARTS files can be shown on top of each other")
        for (p in parts) {
            if (p.topCell !in p.library.cells.indices) throw GdsFormatException("The chosen cell does not exist in ${p.library.name}")
            if (p.file < 1) throw GdsFormatException("Invalid file number ${p.file}")
        }
        val totalLayers = parts.sumOf { it.library.layers.size.toLong() }
        if (totalLayers > LibraryBuilder.MAX_LAYERS) {
            throw GdsFormatException("The files together use more than ${LibraryBuilder.MAX_LAYERS} layers")
        }

        // Layers: union of all parts' layers tagged with the file number, sorted.
        val all = ArrayList<LayerKey>()
        val partBase = IntArray(parts.size)
        for ((k, p) in parts.withIndex()) {
            partBase[k] = all.size
            for (key in p.library.layers) all += key.copy(file = p.file)
        }
        val order = all.indices.sortedBy { all[it] }
        val newLayerOf = IntArray(all.size)
        order.forEachIndexed { newIndex, old -> newLayerOf[old] = newIndex }
        val layers = order.map { all[it] }

        val dbUnit = parts.minOf { it.library.dbUnitMeters }

        // Cells: all cells of all parts, renamed "name@file", then the new top cell.
        val usedNames = HashSet<String>()
        fun unique(base: String): String {
            var n = base
            var i = 2
            while (!usedNames.add(n)) n = "$base#${i++}"
            return n
        }
        val cells = ArrayList<Cell>()
        val cellBase = IntArray(parts.size)
        for ((k, p) in parts.withIndex()) {
            cellBase[k] = cells.size
            val lib = p.library
            val layerMap = IntArray(lib.layers.size) { newLayerOf[partBase[k] + it] }
            for (c in lib.cells) {
                cells += Cell(
                    cells.size, unique("${c.name}@${p.file}"),
                    RectStore(c.rects.xy, remap(c.rects.layer, layerMap)),
                    PolygonStore(c.polygons.xy, c.polygons.start, remap(c.polygons.layer, layerMap)),
                    PathStore(
                        c.paths.xy, c.paths.start, remap(c.paths.layer, layerMap), c.paths.width, c.paths.type,
                        c.paths.beginExtension, c.paths.endExtension,
                    ),
                    c.refs.withCells(IntArray(c.refs.size) { c.refs.cell[it] + cellBase[k] }),
                )
            }
        }

        // The new top cell: one placement per part, magnified when the database units differ.
        val n = parts.size
        val mags = DoubleArray(n) { parts[it].library.dbUnitMeters / dbUnit }
        var extraCount = 0
        val extra = IntArray(n) { if (abs(mags[it] - 1.0) <= 1e-9) -1 else extraCount++ }
        val extraMag = DoubleArray(extraCount)
        for (i in 0 until n) if (extra[i] >= 0) extraMag[extra[i]] = mags[i]
        val topRefs = RefStore(
            IntArray(n) { cellBase[it] + parts[it].topCell }, IntArray(n), IntArray(n), ByteArray(n), extra,
            BooleanArray(extraCount), extraMag, DoubleArray(extraCount),
            IntArray(extraCount) { 1 }, IntArray(extraCount) { 1 }, DoubleArray(4 * extraCount),
        )
        cells += Cell(
            cells.size, unique(topName),
            RectStore(IntArray(0), IntArray(0)),
            PolygonStore(IntArray(0), intArrayOf(0), IntArray(0)),
            PathStore(IntArray(0), intArrayOf(0), IntArray(0), IntArray(0), IntArray(0), IntArray(0), IntArray(0)),
            topRefs,
        )

        return Library(parts.joinToString(" + ") { it.library.name }, dbUnit, cells, layers)
    }

    private fun remap(old: IntArray, map: IntArray) = IntArray(old.size) { map[old[it]] }
}
