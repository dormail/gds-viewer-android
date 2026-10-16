package com.example.gdsviewer.scene

import com.example.gdsviewer.geometry.Affine
import kotlin.math.max
import kotlin.math.min

// Shared data types of the hierarchical renderer. How the pieces fit together:
//
//   Library (db)  --LibraryIndex-->  per cell: Chunks (spatial pieces of the cell's own shapes)
//                                    and RefBuckets (spatially grouped placements)
//   Chunk  --MeshCache/MeshBuilder-->  ChunkMesh (triangles + outlines, uploaded to the GPU once)
//   SceneBuilder: walks the hierarchy for the current view and produces a DrawList: for every
//   visible chunk the list of transforms it is drawn with (GPU instancing), plus a TinyRaster
//   with everything that is too small to draw as vectors.
//
// Coordinate systems:
//   DBU    integer database units of a cell (each cell has its own coordinate system)
//   world  µm, relative to the center of the displayed top cell (see CellFrame), y up
//   screen pixels, y down; ViewModel.view maps world -> screen

/** An axis-aligned box. The unit depends on where it is used (DBU or µm). */
class Box(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double) {
    val width: Double get() = maxX - minX
    val height: Double get() = maxY - minY
    val centerX: Double get() = (minX + maxX) / 2
    val centerY: Double get() = (minY + maxY) / 2

    fun intersects(o: Box): Boolean = minX <= o.maxX && o.minX <= maxX && minY <= o.maxY && o.minY <= maxY

    fun contains(o: Box): Boolean = minX <= o.minX && minY <= o.minY && maxX >= o.maxX && maxY >= o.maxY

    fun union(o: Box): Box = Box(min(minX, o.minX), min(minY, o.minY), max(maxX, o.maxX), max(maxY, o.maxY))

    /** Grows the box by [d] on every side. */
    fun expanded(d: Double): Box = Box(minX - d, minY - d, maxX + d, maxY + d)

    /** Bounding box of this box after transforming it with [t]. */
    fun transformed(t: Affine): Box {
        var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE
        var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
        for (corner in 0 until 4) {
            val x = if (corner and 1 == 0) minX else maxX
            val y = if (corner and 2 == 0) minY else maxY
            val tx = t.mapX(x, y); val ty = t.mapY(x, y)
            if (tx < x0) x0 = tx; if (tx > x1) x1 = tx
            if (ty < y0) y0 = ty; if (ty > y1) y1 = ty
        }
        return Box(x0, y0, x1, y1)
    }

    override fun toString() = "Box($minX, $minY, $maxX, $maxY)"
}

/**
 * Bounding boxes per layer. Entry k is layer [layers]`[k]` (a layer index, sorted ascending) with
 * the box minX = boxes[4k], minY = boxes[4k + 1], maxX = boxes[4k + 2], maxY = boxes[4k + 3].
 */
class LayerBoxes(val layers: IntArray, val boxes: DoubleArray) {
    val size: Int get() = layers.size

    fun box(k: Int): Box = Box(boxes[4 * k], boxes[4 * k + 1], boxes[4 * k + 2], boxes[4 * k + 3])

    companion object {
        val EMPTY = LayerBoxes(IntArray(0), DoubleArray(0))
    }
}

/**
 * What is displayed as the top cell, and where "world" coordinates come from: world (0, 0) is the
 * center of the cell's bounding box, and world units are µm.
 */
class CellFrame(
    val cell: Int,
    val name: String,
    /** Bounding box of the cell including all children (DBU), or null if it contains no shapes. */
    val bounds: Box?,
    val dbUnitMicrons: Double,
) {
    val isEmpty: Boolean get() = bounds == null

    /** Absolute position (DBU) of world (0, 0). */
    val originX: Double get() = bounds?.centerX ?: 0.0
    val originY: Double get() = bounds?.centerY ?: 0.0

    /** Maps the cell's DBU coordinates to world µm. */
    val root: Affine get() = Affine(dbUnitMicrons, 0.0, 0.0, dbUnitMicrons, -originX * dbUnitMicrons, -originY * dbUnitMicrons)

    /** Bounding box in world µm (all 0 if the cell is empty). */
    val minX: Double get() = bounds?.let { (it.minX - originX) * dbUnitMicrons } ?: 0.0
    val minY: Double get() = bounds?.let { (it.minY - originY) * dbUnitMicrons } ?: 0.0
    val maxX: Double get() = bounds?.let { (it.maxX - originX) * dbUnitMicrons } ?: 0.0
    val maxY: Double get() = bounds?.let { (it.maxY - originY) * dbUnitMicrons } ?: 0.0
}

/**
 * A spatial piece of one cell's own shapes (its children are not included). Chunks are the unit
 * of meshing, culling and GPU upload: small cells have one chunk, cells with many shapes are split
 * into tiles. Chunks are created by [LibraryIndex.content] and never change.
 */
class Chunk(
    /** Unique within one [LibraryIndex] (0, 1, 2, ...); used as the key of GPU caches. */
    val id: Int,
    /** Index of the cell the shapes belong to. */
    val cell: Int,
    /** Mesh vertex coordinates are relative to this point (DBU), to keep float precision. */
    val originX: Int,
    val originY: Int,
    /** Bounding box of the chunk's shapes in cell coordinates (DBU). */
    val bounds: Box,
    /** Bounding boxes per layer of the chunk's shapes (DBU). */
    val layerBounds: LayerBoxes,
    val shapeCount: Int,
    /** A typical shape size: median of max(width, height) of the shapes' bounding boxes (DBU). */
    val typicalShapeSize: Double,
    /** Member shapes: indices into the cell's rects / polygons / paths; null means all shapes of that kind. */
    val rects: IntArray?,
    val polygons: IntArray?,
    val paths: IntArray?,
)

/** A group of placements of one cell, close to each other, for skipping off-screen placements quickly. */
class RefBucket(
    /** Bounding box (DBU, parent cell coordinates) of all instances of these placements, children included. */
    val bounds: Box,
    /** Indices into the cell's [com.example.gdsviewer.db.RefStore]. */
    val refs: IntArray,
)

/** The spatial index of one cell: its own shapes as chunks, and its placements in buckets. */
class CellContent(val chunks: List<Chunk>, val refBuckets: List<RefBucket>)

/**
 * Drawable geometry of a [Chunk], ready for uploading to the GPU.
 *
 * Vertices are (x, y) pairs in DBU relative to the chunk origin. Triangles (3 indices each) and
 * outline segments (2 indices each) are grouped by layer: layer [layers]`[k]` uses
 * triangles[triangleStart[k] until triangleStart[k + 1]] and lines[lineStart[k] until lineStart[k + 1]].
 */
class ChunkMesh(
    val chunk: Chunk,
    val vertices: FloatArray,
    val triangles: IntArray,
    val lines: IntArray,
    val layers: IntArray,
    val triangleStart: IntArray,
    val lineStart: IntArray,
) {
    val vertexCount: Int get() = vertices.size / 2

    /** Approximate memory use in bytes (also used for GPU budgets). */
    val byteSize: Long get() = 4L * (vertices.size + triangles.size + lines.size)
}

/** What the [SceneBuilder] should build a [DrawList] for. */
class SceneRequest(
    val frame: CellFrame,
    /** World (µm) to screen pixels, as ViewModel.view. */
    val view: Affine,
    val viewportWidth: Int,
    val viewportHeight: Int,
    /** Extra area around the screen to include, as a fraction of its size on each side (so panning shows content right away). */
    val margin: Double = 0.25,
    /** Cell instances and chunks smaller than this many pixels are drawn into the [TinyRaster] instead of as vectors. */
    val lodPixels: Double = 4.0,
    /** Size of one raster pixel in screen pixels. */
    val rasterPixel: Double = 2.0,
    /** Safety limit for the number of vector instances; beyond it, instances go to the raster. */
    val maxInstances: Int = 1_000_000,
)

/**
 * The placements of one chunk to draw. Instance i uses the transform
 * a = transforms[6i], b = [6i+1], c = [6i+2], d = [6i+3], tx = [6i+4], ty = [6i+5]
 * (x' = a x + c y + tx, y' = b x + d y + ty, as [Affine]) which maps the chunk's mesh coordinates
 * (DBU relative to the chunk origin) to world µm relative to the anchor ([DrawList.anchorX], [DrawList.anchorY]).
 */
class InstanceGroup(
    val chunk: Chunk,
    val transforms: FloatArray,
    val count: Int,
    /** Largest scale (µm per DBU) among the instances, for deciding whether outlines are worth drawing. */
    val maxScale: Double,
)

/**
 * Everything too small to draw as vectors, as one bitmap per layer. The bitmap is aligned with the
 * screen at the time it was built; [worldToRaster] maps world µm (not anchor-relative) to raster
 * pixel coordinates (pixel (px, py) covers px..px+1, py..py+1).
 *
 * Layer [layers]`[k]` uses bits[k]: bit (px, py) is word (py * width + px) / 64, bit (py * width + px) % 64.
 */
class TinyRaster(
    val width: Int,
    val height: Int,
    val worldToRaster: Affine,
    val layers: IntArray,
    val bits: Array<LongArray>,
) {
    fun isSet(k: Int, px: Int, py: Int): Boolean {
        val i = py * width + px
        return bits[k][i ushr 6] and (1L shl (i and 63)) != 0L
    }
}

class SceneStats(
    /** Number of vector instances (sum of InstanceGroup.count). */
    val instances: Int,
    /** Number of cell instances or chunks that went into the raster. */
    val tinyItems: Long,
    /** Wall time of the traversal and of building missing meshes, in ms. */
    val traversalMillis: Long,
    val meshMillis: Long,
)

/** The result of [SceneBuilder.build]: what to draw for one view. Immutable. */
class DrawList(
    val request: SceneRequest,
    /** World position (µm) that instance translations are relative to (keeps float precision when zoomed in). */
    val anchorX: Double,
    val anchorY: Double,
    val groups: List<InstanceGroup>,
    /** Null if nothing was too small. */
    val raster: TinyRaster?,
    /** World area (µm) this list covers; content outside it was skipped. */
    val coverage: Box,
    val stats: SceneStats,
)
