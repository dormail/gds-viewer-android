package com.example.gdsviewer.geometry

import com.example.gdsviewer.gds.LayerKey
import kotlin.math.max
import kotlin.math.min

/** Finds the closest shape corner or edge to a point, used to make the ruler snap to shapes. */
object Snapper {
    /**
     * Returns the snapped point as doubleArrayOf(x, y) in world coordinates, or null if nothing is
     * within [radius] (world units, µm). Corners within radius / 2 win over edges.
     * Only layers in [visibleLayers] are considered.
     */
    fun snap(
        geometry: LayoutGeometry,
        visibleLayers: Set<LayerKey>,
        x: Double,
        y: Double,
        radius: Double,
    ): DoubleArray? {
        val cornerLimit = radius / 2
        var bestCorner = cornerLimit * cornerLimit
        var cornerX = 0.0
        var cornerY = 0.0
        var foundCorner = false
        var bestEdge = radius * radius
        var edgeX = 0.0
        var edgeY = 0.0
        var foundEdge = false

        for (layer in geometry.layers) {
            if (layer.key !in visibleLayers) continue
            for (chunk in layer.lines.chunks) {
                val end = chunk.position()
                var i = 0
                while (i + 3 < end) {
                    val x1 = chunk.get(i).toDouble()
                    val y1 = chunk.get(i + 1).toDouble()
                    val x2 = chunk.get(i + 2).toDouble()
                    val y2 = chunk.get(i + 3).toDouble()
                    i += 4

                    // Quick reject: the segment's bounding box is farther away than the radius.
                    if (x < min(x1, x2) - radius || x > max(x1, x2) + radius ||
                        y < min(y1, y2) - radius || y > max(y1, y2) + radius
                    ) continue

                    // Corners (segment end points).
                    val d1 = sq(x1 - x, y1 - y)
                    if (d1 <= bestCorner) { bestCorner = d1; cornerX = x1; cornerY = y1; foundCorner = true }
                    val d2 = sq(x2 - x, y2 - y)
                    if (d2 <= bestCorner) { bestCorner = d2; cornerX = x2; cornerY = y2; foundCorner = true }

                    // Closest point on the segment: project (x, y) onto it and clamp to 0..1.
                    val dx = x2 - x1
                    val dy = y2 - y1
                    val len2 = dx * dx + dy * dy
                    val t = if (len2 == 0.0) 0.0 else (((x - x1) * dx + (y - y1) * dy) / len2).coerceIn(0.0, 1.0)
                    val qx = x1 + t * dx
                    val qy = y1 + t * dy
                    val d = sq(qx - x, qy - y)
                    if (d <= bestEdge) { bestEdge = d; edgeX = qx; edgeY = qy; foundEdge = true }
                }
            }
        }
        return when {
            foundCorner -> doubleArrayOf(cornerX, cornerY)
            foundEdge -> doubleArrayOf(edgeX, edgeY)
            else -> null
        }
    }

    private fun sq(dx: Double, dy: Double) = dx * dx + dy * dy
}
