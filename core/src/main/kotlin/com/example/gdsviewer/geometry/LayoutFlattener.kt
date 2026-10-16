package com.example.gdsviewer.geometry

import com.example.gdsviewer.gds.GdsCell
import com.example.gdsviewer.gds.GdsLibrary
import com.example.gdsviewer.gds.GdsRef
import com.example.gdsviewer.gds.LayerKey
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.max

/**
 * Converts a cell and everything it references into triangles and outline segments per layer
 * ("flattening" the hierarchy). Create one instance per [flatten] call.
 *
 * @param maxFloats memory budget: stop (and set [LayoutGeometry.truncated]) when the output would
 *   exceed this many floats in total.
 * @param isCancelled polled regularly; when it returns true, [flatten] throws
 *   java.util.concurrent.CancellationException.
 */
class LayoutFlattener(
    private val library: GdsLibrary,
    private val maxFloats: Long = DEFAULT_MAX_FLOATS,
    private val isCancelled: () -> Boolean = { false },
) {
    /** A bounding box in database units. */
    private class Bounds(var minX: Double, var minY: Double, var maxX: Double, var maxY: Double) {
        constructor() : this(Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE)

        val isEmpty get() = minX > maxX

        fun add(x: Double, y: Double) {
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
    }

    /** Geometry of one layer of a cell's own shapes (not its children), in database units. */
    private class LocalLayer {
        val triangles = DoubleList()
        val lines = DoubleList()
        var shapeCount = 0L
    }

    private val dbu = library.dbUnitMeters * 1e6

    private val boundsMemo = HashMap<String, Bounds?>()
    private val boundsInProgress = HashSet<String>()

    /** How many times each cell is instantiated below the flattened cell. */
    private val placementCount = HashMap<String, Double>()

    /** Cached own-shape geometry of cells that are placed more than once. */
    private val localCache = HashMap<String, Map<LayerKey, LocalLayer>>()

    // Output state.
    private val outLayers = HashMap<LayerKey, LayerGeometry>()
    private var totalFloats = 0L
    private var truncated = false
    private var pollCounter = 0

    // Reusable scratch buffers for generating one element at a time.
    private val scratchTriangles = DoubleList(1024)
    private val scratchLines = DoubleList(1024)
    private var scratchPoints = DoubleArray(256)

    /** The top cell to show by default (the top cell with the largest bounding box), or null if there are no cells. */
    fun defaultCell(): GdsCell? {
        var best: GdsCell? = null
        var bestArea = -2.0
        for (cell in library.topCells) {
            val b = cellBounds(cell)
            val area = if (b == null) -1.0 else (b.maxX - b.minX) * (b.maxY - b.minY)
            if (area > bestArea) {
                best = cell
                bestArea = area
            }
        }
        return best
    }

    fun flatten(cellName: String): LayoutGeometry {
        val cell = library.cells[cellName] ?: throw IllegalArgumentException("Unknown cell: $cellName")
        checkCancelled()
        val bounds = cellBounds(cell)
        if (bounds == null || bounds.isEmpty) {
            return LayoutGeometry(cellName, emptyList(), 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, dbu, false)
        }
        val cx = (bounds.minX + bounds.maxX) / 2
        val cy = (bounds.minY + bounds.maxY) / 2

        computePlacementCounts(cell)

        // Root transform: database units -> µm, with the bounding box center moved to (0, 0).
        val root = Affine(dbu, 0.0, 0.0, dbu, -cx * dbu, -cy * dbu)
        emit(cell, root, 0)

        val layers = outLayers.values.sortedBy { it.key }
        return LayoutGeometry(
            cellName, layers,
            (bounds.minX - cx) * dbu, (bounds.minY - cy) * dbu, (bounds.maxX - cx) * dbu, (bounds.maxY - cy) * dbu,
            cx * dbu, cy * dbu, dbu, truncated,
        )
    }

    private fun checkCancelled() {
        if (isCancelled()) throw CancellationException("Flattening cancelled")
    }

    // ---------------------------------------------------------------- bounds

    /** Bounding box of a cell including all children, in database units. Null if it contains no shapes. */
    private fun cellBounds(cell: GdsCell): Bounds? {
        if (boundsMemo.containsKey(cell.name)) return boundsMemo[cell.name]
        if (!boundsInProgress.add(cell.name)) return null // cyclic reference: ignore
        val b = Bounds()
        for (p in cell.polygons) {
            val xy = p.xy
            for (i in 0 until xy.size / 2) b.add(xy[2 * i].toDouble(), xy[2 * i + 1].toDouble())
        }
        for (p in cell.paths) {
            val hw = abs(p.width.toDouble()) / 2
            val grow = hw + when (p.pathType) {
                2 -> hw
                4 -> max(abs(p.beginExtension), abs(p.endExtension)).toDouble()
                else -> 0.0
            }
            val xy = p.xy
            for (i in 0 until xy.size / 2) {
                val x = xy[2 * i].toDouble()
                val y = xy[2 * i + 1].toDouble()
                b.add(x - grow, y - grow)
                b.add(x + grow, y + grow)
            }
        }
        for (ref in cell.refs) {
            val child = library.cells[ref.cellName] ?: continue
            val cb = cellBounds(child) ?: continue
            // Instances are rotated/mirrored copies, so the extreme ones are the 4 corner instances.
            for (col in intArrayOf(0, ref.columns - 1)) {
                for (row in intArrayOf(0, ref.rows - 1)) {
                    val t = instanceTransform(ref, col, row)
                    for (corner in 0 until 4) {
                        val x = if (corner and 1 == 0) cb.minX else cb.maxX
                        val y = if (corner and 2 == 0) cb.minY else cb.maxY
                        b.add(t.mapX(x, y), t.mapY(x, y))
                    }
                }
            }
        }
        boundsInProgress.remove(cell.name)
        val result = if (b.isEmpty) null else b
        boundsMemo[cell.name] = result
        return result
    }

    /** Transform of instance (col, row) of [ref] relative to the parent cell. */
    private fun instanceTransform(ref: GdsRef, col: Int, row: Int): Affine {
        val ox = ref.x + col * ref.columnStepX + row * ref.rowStepX
        val oy = ref.y + col * ref.columnStepY + row * ref.rowStepY
        return Affine.placement(ox, oy, ref.mirrorX, ref.magnification, ref.angleDegrees)
    }

    // ------------------------------------------------------ placement counts

    /** Counts how often each cell is instantiated when [top] is flattened. */
    private fun computePlacementCounts(top: GdsCell) {
        // DFS post-order, reversed = parents before children (a topological order).
        val order = ArrayList<GdsCell>()
        val visited = HashSet<String>()
        fun dfs(cell: GdsCell) {
            if (!visited.add(cell.name)) return
            for (ref in cell.refs) library.cells[ref.cellName]?.let { dfs(it) }
            order += cell
        }
        dfs(top)
        order.reverse()
        placementCount[top.name] = 1.0
        for (cell in order) {
            val n = placementCount[cell.name] ?: continue
            for (ref in cell.refs) {
                val child = library.cells[ref.cellName] ?: continue
                placementCount[child.name] =
                    (placementCount[child.name] ?: 0.0) + n * ref.columns.toDouble() * ref.rows.toDouble()
            }
        }
    }

    // --------------------------------------------------------------- emitting

    private fun emit(cell: GdsCell, transform: Affine, depth: Int) {
        if (truncated || depth > MAX_DEPTH) return
        checkCancelled()

        if ((placementCount[cell.name] ?: 1.0) > 1.0) {
            // Placed many times: build the shapes once, then just copy them transformed.
            for ((key, local) in cachedGeometry(cell)) {
                if (!writeLists(layerFor(key), local.triangles, local.lines, transform)) return
                layerFor(key).shapeCount += local.shapeCount
            }
        } else {
            for (p in cell.polygons) {
                if (++pollCounter % POLL_INTERVAL == 0) checkCancelled()
                scratchTriangles.clear()
                scratchLines.clear()
                addPolygon(p.xy, scratchTriangles, scratchLines)
                val layer = layerFor(p.layer)
                layer.shapeCount++
                if (!writeLists(layer, scratchTriangles, scratchLines, transform)) return
            }
            for (p in cell.paths) {
                if (++pollCounter % POLL_INTERVAL == 0) checkCancelled()
                scratchTriangles.clear()
                scratchLines.clear()
                PathGeometry.build(p, scratchTriangles, scratchLines)
                val layer = layerFor(p.layer)
                layer.shapeCount++
                if (!writeLists(layer, scratchTriangles, scratchLines, transform)) return
            }
        }

        for (ref in cell.refs) {
            val child = library.cells[ref.cellName] ?: continue
            for (row in 0 until ref.rows) {
                for (col in 0 until ref.columns) {
                    if (truncated) return
                    emit(child, transform * instanceTransform(ref, col, row), depth + 1)
                }
            }
        }
    }

    /** The cell's own polygons and paths per layer in database units (children excluded), built once. */
    private fun cachedGeometry(cell: GdsCell): Map<LayerKey, LocalLayer> {
        localCache[cell.name]?.let { return it }
        val layers = HashMap<LayerKey, LocalLayer>()
        fun local(key: LayerKey) = layers.getOrPut(key) { LocalLayer() }
        for (p in cell.polygons) {
            if (++pollCounter % POLL_INTERVAL == 0) checkCancelled()
            val l = local(p.layer)
            addPolygon(p.xy, l.triangles, l.lines)
            l.shapeCount++
        }
        for (p in cell.paths) {
            if (++pollCounter % POLL_INTERVAL == 0) checkCancelled()
            val l = local(p.layer)
            PathGeometry.build(p, l.triangles, l.lines)
            l.shapeCount++
        }
        localCache[cell.name] = layers
        return layers
    }

    /** Appends fill triangles and the closed outline of a polygon (database units). */
    private fun addPolygon(xy: IntArray, triangles: DoubleList, lines: DoubleList) {
        val n = xy.size / 2
        if (scratchPoints.size < xy.size) scratchPoints = DoubleArray(xy.size * 2)
        for (i in xy.indices) scratchPoints[i] = xy[i].toDouble()
        Triangulator.triangulate(scratchPoints, n, triangles)
        for (i in 0 until n) {
            val j = if (i + 1 == n) 0 else i + 1
            lines.add(scratchPoints[2 * i], scratchPoints[2 * i + 1])
            lines.add(scratchPoints[2 * j], scratchPoints[2 * j + 1])
        }
    }

    private fun layerFor(key: LayerKey): LayerGeometry = outLayers.getOrPut(key) { LayerGeometry(key) }

    /**
     * Writes the transformed triangles and lines into [layer]. Returns false (and sets
     * [truncated]) if the float budget ran out.
     */
    private fun writeLists(layer: LayerGeometry, triangles: DoubleList, lines: DoubleList, t: Affine): Boolean {
        val tri = triangles.data
        var i = 0
        while (i < triangles.size) {
            if (totalFloats + 6 > maxFloats) { truncated = true; return false }
            layer.triangles.reserve(6)
            for (k in 0 until 3) {
                val x = tri[i + 2 * k]
                val y = tri[i + 2 * k + 1]
                layer.triangles.put(t.mapX(x, y).toFloat())
                layer.triangles.put(t.mapY(x, y).toFloat())
            }
            totalFloats += 6
            i += 6
        }
        val ln = lines.data
        i = 0
        while (i < lines.size) {
            if (totalFloats + 4 > maxFloats) { truncated = true; return false }
            layer.lines.reserve(4)
            for (k in 0 until 2) {
                val x = ln[i + 2 * k]
                val y = ln[i + 2 * k + 1]
                layer.lines.put(t.mapX(x, y).toFloat())
                layer.lines.put(t.mapY(x, y).toFloat())
            }
            totalFloats += 4
            i += 4
        }
        return true
    }

    companion object {
        const val DEFAULT_MAX_FLOATS = 32_000_000L
        private const val MAX_DEPTH = 64
        private const val POLL_INTERVAL = 1000
    }
}
