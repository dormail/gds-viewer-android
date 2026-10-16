package com.example.gdsviewer.scene

import com.example.gdsviewer.db.Library

object LayerStats {
    /**
     * Number of shapes per layer index when [topCell] is fully expanded (each placement counts its
     * cell's shapes again, arrays count columns * rows times). Layers not used below [topCell] are 0.
     */
    fun shapeCounts(library: Library, topCell: Int): LongArray = TODO()
}
