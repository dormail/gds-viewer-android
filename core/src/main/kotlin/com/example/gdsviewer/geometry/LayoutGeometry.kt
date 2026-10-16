package com.example.gdsviewer.geometry

import com.example.gdsviewer.gds.LayerKey

/**
 * Drawable geometry of one layer, in "world" coordinates: micrometers, relative to the center of
 * the displayed cell (see [LayoutGeometry.originX]). y points up.
 */
class LayerGeometry(val key: LayerKey) {
    /** Filled area: 3 vertices (x, y) per triangle = 6 floats per triangle. */
    val triangles = FloatChunks()

    /** Shape outlines: 2 vertices (x, y) per line segment = 4 floats per segment. */
    val lines = FloatChunks()

    /** Number of polygons and paths on this layer (after expanding all cell instances). */
    var shapeCount = 0L
}

/** The flattened, ready-to-draw contents of one cell. */
class LayoutGeometry(
    val cellName: String,
    /** Layers sorted by [LayerKey]; only layers that contain shapes. */
    val layers: List<LayerGeometry>,
    /** Bounding box in world coordinates (µm, relative to the origin). All 0 if the cell is empty. */
    val minX: Double,
    val minY: Double,
    val maxX: Double,
    val maxY: Double,
    /** Absolute layout position (µm) of world coordinate (0, 0). Absolute = world + origin. */
    val originX: Double,
    val originY: Double,
    /** Size of one database unit in µm (e.g. 0.001 for a 1 nm grid). */
    val dbUnitMicrons: Double,
    /** True if the layout was too big and only part of it was converted. */
    val truncated: Boolean,
) {
    val isEmpty: Boolean get() = layers.isEmpty()
}
