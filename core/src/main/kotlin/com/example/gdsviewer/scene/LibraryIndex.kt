package com.example.gdsviewer.scene

import com.example.gdsviewer.db.Cell
import com.example.gdsviewer.db.Library
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Spatial index of a [Library]: bounding boxes of all cells (eager) and per-cell [CellContent]
 * (chunks and placement buckets, built lazily on first use). Thread-safe.
 */
class LibraryIndex(val library: Library) {
    val bounds: CellBounds = CellBounds(library)

    private val nextChunkId = AtomicInteger(0)

    // The lazy values are created cheaply inside computeIfAbsent and evaluated outside of it, so
    // building one big cell never blocks other cells; concurrent callers for the same cell wait
    // for the one build.
    private val contents = ConcurrentHashMap<Int, Lazy<CellContent>>()

    /** The chunks and placement buckets of [cell], built on first use. */
    fun content(cell: Int): CellContent =
        contents.computeIfAbsent(cell) { lazy(LazyThreadSafetyMode.SYNCHRONIZED) { buildContent(cell) } }.value

    private fun buildContent(cell: Int): CellContent {
        val chunks = ChunkPartition.chunks(library, cell) { count -> nextChunkId.getAndAdd(count) }
        return CellContent(chunks, ChunkPartition.buckets(library, bounds, cell))
    }

    /** The top cell with the largest bounding box area (empty cells count as smallest), or null if there are no cells. */
    fun defaultTopCell(): Cell? {
        var best: Cell? = null
        var bestArea = -1.0
        for (cell in library.topCells) {
            val b = bounds.bounds(cell.index)
            val area = if (b == null) -1.0 else b.width * b.height
            if (best == null || area > bestArea) {
                best = cell
                bestArea = area
            }
        }
        return best
    }

    /** The frame for showing [cell] as the top cell. */
    fun frame(cell: Int): CellFrame = CellFrame(cell, library.cells[cell].name, bounds.bounds(cell), library.dbUnitMicrons)
}
