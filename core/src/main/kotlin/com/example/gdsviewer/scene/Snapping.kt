package com.example.gdsviewer.scene

/** Finds the closest shape corner or edge to a point, for the ruler (works on the hierarchy, nothing is flattened). */
object Snapping {
    /**
     * Returns the snapped point as doubleArrayOf(x, y) in world µm, or null if nothing is within
     * [radius] (world µm). Corners within radius / 2 win over edges. Only shapes on layers with
     * visibleLayers[layerIndex] == true count.
     */
    fun snap(
        index: LibraryIndex,
        frame: CellFrame,
        visibleLayers: BooleanArray,
        x: Double,
        y: Double,
        radius: Double,
        isCancelled: () -> Boolean = { false },
    ): DoubleArray? = TODO()
}
