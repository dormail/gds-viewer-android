package com.example.gdsviewer.scene

import com.example.gdsviewer.db.Library

/**
 * Bounding boxes of all cells, including everything they place (computed once, bottom-up, in the
 * constructor). Cyclic references are ignored. Thread-safe after construction.
 */
class CellBounds(val library: Library) {
    /** Bounding box of the cell including all children (DBU), or null if there are no shapes anywhere below it. */
    fun bounds(cell: Int): Box? = TODO()

    /** Per-layer bounding boxes of the cell including all children (DBU). */
    fun layerBounds(cell: Int): LayerBoxes = TODO()
}
