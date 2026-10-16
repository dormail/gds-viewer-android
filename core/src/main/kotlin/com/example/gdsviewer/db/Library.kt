package com.example.gdsviewer.db

import com.example.gdsviewer.gds.LayerKey
import com.example.gdsviewer.geometry.Affine

/**
 * A compact, read-only GDSII library ("layout database").
 *
 * Shapes are not stored as one object each but in packed arrays per cell ([RectStore],
 * [PolygonStore], [PathStore], [RefStore]), so a file with millions of shapes needs little memory.
 * All coordinates are integers in database units (DBU). Layers are referred to by their index in
 * [layers]; cells by their index in [cells] (which equals [Cell.index]).
 *
 * Built by [LibraryBuilder]; never modified afterwards, so it can be read from any thread.
 */
class Library internal constructor(
    val name: String,
    /** Size of one database unit in meters (typically 1e-9, a 1 nm grid). */
    val dbUnitMeters: Double,
    val cells: List<Cell>,
    /** Every layer used by a shape, sorted. Shapes store indices into this list. */
    val layers: List<LayerKey>,
) {
    /** Size of one database unit in µm (e.g. 0.001). */
    val dbUnitMicrons: Double get() = dbUnitMeters * 1e6

    private val byName: Map<String, Cell> = cells.associateBy { it.name }

    fun cell(name: String): Cell? = byName[name]

    /** Cells that no other cell references, in file order. If every cell is referenced (a cycle), all cells. */
    val topCells: List<Cell> by lazy {
        val referenced = BooleanArray(cells.size)
        for (cell in cells) {
            val refs = cell.refs
            for (i in 0 until refs.size) referenced[refs.cell[i]] = true
        }
        cells.filter { !referenced[it.index] }.ifEmpty { cells }
    }
}

/** A cell (GDS "structure"): its own shapes plus references to other cells. */
class Cell internal constructor(
    val index: Int,
    val name: String,
    val rects: RectStore,
    val polygons: PolygonStore,
    val paths: PathStore,
    val refs: RefStore,
) {
    /** Number of own shapes (rectangles + polygons + paths), children not included. */
    val shapeCount: Int get() = rects.size + polygons.size + paths.size
}

/**
 * Axis-aligned rectangles: GDS BOXes, BOUNDARYs that are rectangles, and straight horizontal or
 * vertical PATHs with flat or extended ends (these are rectangles too).
 */
class RectStore internal constructor(
    /** x0, y0, x1, y1 per rectangle, with x0 < x1 and y0 < y1. Rectangle i starts at 4 * i. */
    val xy: IntArray,
    /** Layer index per rectangle. */
    val layer: IntArray,
) {
    val size: Int get() = layer.size
}

/** Polygons (GDS BOUNDARY) that are not rectangles. The first point is not repeated at the end. */
class PolygonStore internal constructor(
    /** Points of all polygons: x, y, x, y, ... */
    val xy: IntArray,
    /** Polygon i consists of points start[i] until start[i + 1] (exclusive); size + 1 entries. Point k is at xy[2k], xy[2k + 1]. */
    val start: IntArray,
    /** Layer index per polygon. */
    val layer: IntArray,
) {
    val size: Int get() = layer.size

    fun pointCount(i: Int): Int = start[i + 1] - start[i]
}

/**
 * Wires (GDS PATH) that could not be stored as a rectangle.
 *
 * [type]: 0 = flush ends, 1 = round ends, 2 = ends extended by half the width, 4 = ends extended
 * by [beginExtension] / [endExtension]. [width] is already made non-negative.
 */
class PathStore internal constructor(
    /** Center line points of all paths: x, y, x, y, ... */
    val xy: IntArray,
    /** Path i consists of points start[i] until start[i + 1] (exclusive); size + 1 entries. */
    val start: IntArray,
    val layer: IntArray,
    val width: IntArray,
    val type: IntArray,
    val beginExtension: IntArray,
    val endExtension: IntArray,
) {
    val size: Int get() = layer.size

    fun pointCount(i: Int): Int = start[i + 1] - start[i]
}

/**
 * Placements of other cells (GDS SREF and AREF).
 *
 * Most placements are "simple": magnification 1, a rotation that is a multiple of 90 degrees, and
 * not an array. They only use [cell], [x], [y] and [orientation] (13 bytes each). All others have
 * extra[i] >= 0, an index into the `extra...` arrays.
 *
 * Each instance is first mirrored about the x axis (if mirrored), then scaled by the magnification,
 * then rotated counter-clockwise by the angle, and finally moved to its position. Instance
 * (col, row) of an array sits at (x, y) + col * (columnStepX, columnStepY) + row * (rowStepX, rowStepY).
 */
class RefStore internal constructor(
    /** Index of the placed cell. */
    val cell: IntArray,
    val x: IntArray,
    val y: IntArray,
    /** For simple placements: bits 0-1 = rotation in quarter turns counter-clockwise, bit 2 = mirrored. */
    val orientation: ByteArray,
    /** -1 for simple placements, otherwise the index into the extra arrays. */
    val extra: IntArray,
    private val extraMirror: BooleanArray,
    private val extraMag: DoubleArray,
    private val extraAngle: DoubleArray,
    private val extraCols: IntArray,
    private val extraRows: IntArray,
    /** colStepX, colStepY, rowStepX, rowStepY per extra entry (DBU, may be fractional). */
    private val extraSteps: DoubleArray,
) {
    val size: Int get() = cell.size

    fun isArray(i: Int): Boolean = columns(i) > 1 || rows(i) > 1

    fun columns(i: Int): Int = extra[i].let { if (it < 0) 1 else extraCols[it] }
    fun rows(i: Int): Int = extra[i].let { if (it < 0) 1 else extraRows[it] }

    /** Number of instances: columns * rows. */
    fun instanceCount(i: Int): Long = columns(i).toLong() * rows(i)

    fun mirrored(i: Int): Boolean = extra[i].let { if (it < 0) orientation[i].toInt() and 4 != 0 else extraMirror[it] }
    fun magnification(i: Int): Double = extra[i].let { if (it < 0) 1.0 else extraMag[it] }
    fun angleDegrees(i: Int): Double = extra[i].let { if (it < 0) 90.0 * (orientation[i].toInt() and 3) else extraAngle[it] }

    fun columnStepX(i: Int): Double = step(i, 0)
    fun columnStepY(i: Int): Double = step(i, 1)
    fun rowStepX(i: Int): Double = step(i, 2)
    fun rowStepY(i: Int): Double = step(i, 3)

    private fun step(i: Int, k: Int): Double = extra[i].let { if (it < 0) 0.0 else extraSteps[4 * it + k] }

    /** Transform from the placed cell's coordinates to the parent cell's coordinates for instance (col, row). */
    fun transform(i: Int, col: Int = 0, row: Int = 0): Affine {
        val e = extra[i]
        if (e < 0) {
            val o = orientation[i].toInt()
            return Affine.placement(x[i].toDouble(), y[i].toDouble(), o and 4 != 0, 1.0, 90.0 * (o and 3))
        }
        val s = 4 * e
        val ox = x[i] + col * extraSteps[s] + row * extraSteps[s + 2]
        val oy = y[i] + col * extraSteps[s + 1] + row * extraSteps[s + 3]
        return Affine.placement(ox, oy, extraMirror[e], extraMag[e], extraAngle[e])
    }
}
