package com.example.gdsviewer.scene

import com.example.gdsviewer.db.Cell
import com.example.gdsviewer.db.Library

/**
 * Spatial index of a [Library]: bounding boxes of all cells (eager) and per-cell [CellContent]
 * (chunks and placement buckets, built lazily on first use). Thread-safe.
 */
class LibraryIndex(val library: Library) {
    val bounds: CellBounds = CellBounds(library)

    /** The chunks and placement buckets of [cell], built on first use. */
    fun content(cell: Int): CellContent = TODO()

    /** The top cell with the largest bounding box area (empty cells count as smallest), or null if there are no cells. */
    fun defaultTopCell(): Cell? = TODO()

    /** The frame for showing [cell] as the top cell. */
    fun frame(cell: Int): CellFrame = CellFrame(cell, library.cells[cell].name, bounds.bounds(cell), library.dbUnitMicrons)
}
