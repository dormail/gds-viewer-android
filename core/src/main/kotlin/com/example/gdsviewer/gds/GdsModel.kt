package com.example.gdsviewer.gds

/** Identifies a drawing layer: GDS layer number plus datatype, written as "layer/datatype" (e.g. "1/0"). */
data class LayerKey(val layer: Int, val datatype: Int) : Comparable<LayerKey> {
    override fun compareTo(other: LayerKey): Int =
        if (layer != other.layer) layer.compareTo(other.layer) else datatype.compareTo(other.datatype)

    override fun toString() = "$layer/$datatype"
}

/**
 * The contents of a GDSII file.
 *
 * All coordinates are integers in "database units". [dbUnitMeters] says how big one database
 * unit is in meters (typically 1e-9, i.e. a 1 nm grid).
 */
class GdsLibrary(
    val name: String,
    val dbUnitMeters: Double,
    val cells: Map<String, GdsCell>,
) {
    /** Cells that no other cell references. These are the natural starting points for viewing. */
    val topCells: List<GdsCell> by lazy {
        val referenced = HashSet<String>()
        for (cell in cells.values) for (ref in cell.refs) referenced += ref.cellName
        cells.values.filter { it.name !in referenced }.ifEmpty { cells.values.toList() }
    }

    /** Every layer used anywhere in the library, sorted. */
    val layers: List<LayerKey> by lazy {
        val keys = HashSet<LayerKey>()
        for (cell in cells.values) {
            for (p in cell.polygons) keys += p.layer
            for (p in cell.paths) keys += p.layer
        }
        keys.sorted()
    }
}

/** A cell (called "structure" in GDS): shapes plus references to other cells. */
class GdsCell(val name: String) {
    val polygons = ArrayList<GdsPolygon>()
    val paths = ArrayList<GdsPath>()
    val texts = ArrayList<GdsText>()
    val refs = ArrayList<GdsRef>()
}

/**
 * A filled polygon (GDS BOUNDARY or BOX).
 * [xy] holds x0, y0, x1, y1, ... The first point is NOT repeated at the end.
 */
class GdsPolygon(val layer: LayerKey, val xy: IntArray)

/**
 * A wire with a width (GDS PATH). [xy] holds the center line points x0, y0, x1, y1, ...
 *
 * [pathType]: 0 = flush ends, 1 = round ends, 2 = ends extended by half the width,
 * 4 = ends extended by [beginExtension] / [endExtension].
 * A negative [width] means "absolute width" in GDS; treat it as its absolute value.
 */
class GdsPath(
    val layer: LayerKey,
    val width: Int,
    val pathType: Int,
    val beginExtension: Int,
    val endExtension: Int,
    val xy: IntArray,
)

/** A text label (GDS TEXT). Parsed but not drawn yet. */
class GdsText(val layer: LayerKey, val x: Int, val y: Int, val text: String)

/**
 * A placement of another cell (GDS SREF; GDS AREF for arrays).
 *
 * Each instance is first mirrored about the x axis (if [mirrorX]), then scaled by
 * [magnification], then rotated counter-clockwise by [angleDegrees], and finally moved to its
 * position. Instance (col, row) of an array sits at
 * (x, y) + col * (columnStepX, columnStepY) + row * (rowStepX, rowStepY).
 * A simple SREF has columns = rows = 1.
 */
class GdsRef(
    val cellName: String,
    val x: Int,
    val y: Int,
    val mirrorX: Boolean = false,
    val magnification: Double = 1.0,
    val angleDegrees: Double = 0.0,
    val columns: Int = 1,
    val rows: Int = 1,
    val columnStepX: Double = 0.0,
    val columnStepY: Double = 0.0,
    val rowStepX: Double = 0.0,
    val rowStepY: Double = 0.0,
)

/** Thrown when a file is not a valid GDSII file. The message is shown to the user. */
class GdsFormatException(message: String) : Exception(message)
