package io.github.dormail.gdsviewer.db

import io.github.dormail.gdsviewer.gds.GdsFormatException
import io.github.dormail.gdsviewer.gds.LayerKey
import kotlin.math.abs
import kotlin.math.max

/**
 * Collects cells, shapes and placements (e.g. while a file is read) and turns them into a
 * [Library]. Not thread-safe; use from one thread.
 *
 * Cells may be referenced before they are defined. References to cells that are never defined
 * are dropped by [build]. Defining a cell name twice appends to the first definition.
 */
class LibraryBuilder {
    private val indexByName = HashMap<String, Int>()
    private val names = ArrayList<String>()
    private val builders = ArrayList<CellBuilder?>() // null = referenced but not defined (yet)

    private val layerIndexByKey = HashMap<Int, Int>()
    private val layerKeys = ArrayList<LayerKey>()

    /** Index of the cell called [name], reserving one if the name is new (for references). */
    fun cellIndex(name: String): Int = indexByName.getOrPut(name) {
        names += name
        builders += null
        names.size - 1
    }

    /** Starts (or continues) the definition of the cell called [name]. */
    fun beginCell(name: String): CellBuilder {
        val index = cellIndex(name)
        return builders[index] ?: CellBuilder(index, name).also { builders[index] = it }
    }

    /** Index of the layer with GDS numbers [layer] / [datatype] (0..65535 each). */
    fun layerIndex(layer: Int, datatype: Int): Int = layerIndexByKey.getOrPut((layer shl 16) or (datatype and 0xFFFF)) {
        // Real designs use a few hundred layer/datatype pairs. Per-layer bookkeeping (colors, bitmaps) must stay small.
        if (layerKeys.size >= MAX_LAYERS) throw GdsFormatException("The file uses more than $MAX_LAYERS layers")
        layerKeys += LayerKey(layer, datatype)
        layerKeys.size - 1
    }

    companion object {
        /** Most distinct layer/datatype pairs a file may use. */
        const val MAX_LAYERS = 10_000
    }

    /** Builds the library. The builder must not be used afterwards. */
    fun build(name: String, dbUnitMeters: Double): Library {
        // Layers sorted by number; shapes are renumbered accordingly.
        val order = layerKeys.indices.sortedBy { layerKeys[it] }
        val newLayer = IntArray(layerKeys.size)
        order.forEachIndexed { newIndex, old -> newLayer[old] = newIndex }
        val layers = order.map { layerKeys[it] }

        // Cells that were only referenced but never defined are dropped; the rest are renumbered.
        val newCell = IntArray(builders.size)
        var count = 0
        for (i in builders.indices) newCell[i] = if (builders[i] != null) count++ else -1

        val cells = ArrayList<Cell>(count)
        for (i in builders.indices) {
            val b = builders[i] ?: continue
            cells += b.build(newCell[i], newLayer, newCell)
            builders[i] = null // free the growable buffers early
        }
        return Library(name, dbUnitMeters, cells, layers)
    }
}

/** Collects the contents of one cell. Coordinates are DBU; layers are indices from [LibraryBuilder.layerIndex]. */
class CellBuilder internal constructor(val index: Int, val name: String) {
    private val rectXY = IntBuf()
    private val rectLayer = IntBuf()

    private val polyXY = IntBuf()
    private val polyStart = IntBuf().apply { add(0) }
    private val polyLayer = IntBuf()

    private val pathXY = IntBuf()
    private val pathStart = IntBuf().apply { add(0) }
    private val pathLayer = IntBuf()
    private val pathWidth = IntBuf()
    private val pathType = IntBuf()
    private val pathBegin = IntBuf()
    private val pathEnd = IntBuf()

    private val refCell = IntBuf()
    private val refX = IntBuf()
    private val refY = IntBuf()
    private val refOrientation = ByteBuf()
    private val refExtra = IntBuf()
    private val extraMirror = ByteBuf()
    private val extraMag = DoubleBuf()
    private val extraAngle = DoubleBuf()
    private val extraCols = IntBuf()
    private val extraRows = IntBuf()
    private val extraSteps = DoubleBuf()

    /** Adds an axis-aligned rectangle given by two opposite corners. Empty rectangles are ignored. */
    fun addBox(layer: Int, x0: Int, y0: Int, x1: Int, y1: Int) {
        if (x0 == x1 || y0 == y1) return
        rectXY.add(minOf(x0, x1)); rectXY.add(minOf(y0, y1)); rectXY.add(maxOf(x0, x1)); rectXY.add(maxOf(y0, y1))
        rectLayer.add(layer)
    }

    /**
     * Adds a polygon with [pointCount] points from [xy] (x, y, x, y, ...; the first point not
     * repeated at the end). Rectangles are stored as rectangles; fewer than 3 points are ignored.
     */
    fun addPolygon(layer: Int, xy: IntArray, pointCount: Int) {
        if (pointCount < 3) return
        if (pointCount == 4) {
            val x0 = xy[0]; val y0 = xy[1]; val x1 = xy[2]; val y1 = xy[3]
            val x2 = xy[4]; val y2 = xy[5]; val x3 = xy[6]; val y3 = xy[7]
            val verticalFirst = x0 == x1 && y1 == y2 && x2 == x3 && y3 == y0
            val horizontalFirst = y0 == y1 && x1 == x2 && y2 == y3 && x3 == x0
            if (verticalFirst || horizontalFirst) {
                addBox(layer, x0, y0, x2, y2)
                return
            }
        }
        for (i in 0 until 2 * pointCount) polyXY.add(xy[i])
        polyStart.add(polyStart.last() + pointCount)
        polyLayer.add(layer)
    }

    /**
     * Adds a path (wire). [width] may be negative (GDS "absolute width"). Straight horizontal or
     * vertical paths with flat or extended ends are stored as rectangles. Paths without points are
     * ignored.
     */
    fun addPath(layer: Int, width: Int, type: Int, beginExtension: Int, endExtension: Int, xy: IntArray, pointCount: Int) {
        if (pointCount < 1) return
        val w = abs(width)
        if (pointCount == 2 && type != 1 && w > 0 && w % 2 == 0 && addPathAsBox(layer, w, type, beginExtension, endExtension, xy)) return
        for (i in 0 until 2 * pointCount) pathXY.add(xy[i])
        pathStart.add(pathStart.last() + pointCount)
        pathLayer.add(layer)
        pathWidth.add(w)
        pathType.add(type)
        pathBegin.add(beginExtension)
        pathEnd.add(endExtension)
    }

    /** Stores a straight 2-point path as a rectangle if possible; returns false if not. */
    private fun addPathAsBox(layer: Int, w: Int, type: Int, beginExtension: Int, endExtension: Int, xy: IntArray): Boolean {
        val hw = (w / 2).toLong()
        val bx = xy[0].toLong(); val by = xy[1].toLong(); val ex = xy[2].toLong(); val ey = xy[3].toLong()
        val begin = when (type) { 2 -> hw; 4 -> beginExtension.toLong(); else -> 0L }
        val end = when (type) { 2 -> hw; 4 -> endExtension.toLong(); else -> 0L }
        val x0: Long; val y0: Long; val x1: Long; val y1: Long
        if (by == ey && bx != ex) {
            val dir = if (ex > bx) 1 else -1
            x0 = bx - dir * begin; x1 = ex + dir * end; y0 = by - hw; y1 = by + hw
            if ((x1 - x0) * dir <= 0) return false // extensions longer than the path itself
        } else if (bx == ex && by != ey) {
            val dir = if (ey > by) 1 else -1
            y0 = by - dir * begin; y1 = ey + dir * end; x0 = bx - hw; x1 = bx + hw
            if ((y1 - y0) * dir <= 0) return false
        } else {
            return false
        }
        val lo = Int.MIN_VALUE.toLong(); val hi = Int.MAX_VALUE.toLong()
        if (minOf(x0, x1, y0, y1) < lo || maxOf(x0, x1, y0, y1) > hi) return false
        addBox(layer, x0.toInt(), y0.toInt(), x1.toInt(), y1.toInt())
        return true
    }

    /** Adds a single placement (GDS SREF) of cell [cell] (an index from [LibraryBuilder.cellIndex]). */
    fun addRef(cell: Int, x: Int, y: Int, mirror: Boolean = false, magnification: Double = 1.0, angleDegrees: Double = 0.0) {
        val quarter = quarterTurns(angleDegrees)
        if (magnification == 1.0 && quarter >= 0) {
            refCell.add(cell); refX.add(x); refY.add(y)
            refOrientation.add((quarter or (if (mirror) 4 else 0)).toByte())
            refExtra.add(-1)
        } else {
            addArray(cell, x, y, mirror, magnification, angleDegrees, 1, 1, 0.0, 0.0, 0.0, 0.0)
        }
    }

    /** Adds an array placement (GDS AREF). Steps are the offsets between neighboring columns / rows (DBU). */
    fun addArray(
        cell: Int, x: Int, y: Int, mirror: Boolean, magnification: Double, angleDegrees: Double,
        columns: Int, rows: Int, columnStepX: Double, columnStepY: Double, rowStepX: Double, rowStepY: Double,
    ) {
        refCell.add(cell); refX.add(x); refY.add(y)
        refOrientation.add(0)
        refExtra.add(extraCols.size)
        extraMirror.add(if (mirror) 1 else 0)
        extraMag.add(magnification)
        extraAngle.add(angleDegrees)
        extraCols.add(max(1, columns))
        extraRows.add(max(1, rows))
        extraSteps.add(columnStepX); extraSteps.add(columnStepY); extraSteps.add(rowStepX); extraSteps.add(rowStepY)
    }

    /** 0..3 if [deg] is an exact multiple of 90 degrees, otherwise -1. */
    private fun quarterTurns(deg: Double): Int = when (((deg % 360.0) + 360.0) % 360.0) {
        0.0 -> 0
        90.0 -> 1
        180.0 -> 2
        270.0 -> 3
        else -> -1
    }

    internal fun build(newIndex: Int, newLayer: IntArray, newCell: IntArray): Cell {
        fun remapLayers(buf: IntBuf): IntArray = buf.toArray().also { a -> for (i in a.indices) a[i] = newLayer[a[i]] }

        val rects = RectStore(rectXY.toArray(), remapLayers(rectLayer))
        val polygons = PolygonStore(polyXY.toArray(), polyStart.toArray(), remapLayers(polyLayer))
        val paths = PathStore(
            pathXY.toArray(), pathStart.toArray(), remapLayers(pathLayer),
            pathWidth.toArray(), pathType.toArray(), pathBegin.toArray(), pathEnd.toArray(),
        )

        // Keep only references to defined cells, renumbered.
        val keep = (0 until refCell.size).filter { newCell[refCell[it]] >= 0 }
        val all = keep.size == refCell.size
        fun ints(buf: IntBuf): IntArray = if (all) buf.toArray() else IntArray(keep.size) { buf[keep[it]] }
        val cellIdx = ints(refCell).also { a -> for (i in a.indices) a[i] = newCell[a[i]] }
        val orientation = if (all) refOrientation.toArray() else ByteArray(keep.size) { refOrientation[keep[it]] }
        val refs = RefStore(
            cellIdx, ints(refX), ints(refY), orientation, ints(refExtra),
            BooleanArray(extraMirror.size) { extraMirror[it].toInt() != 0 },
            extraMag.toArray(), extraAngle.toArray(), extraCols.toArray(), extraRows.toArray(), extraSteps.toArray(),
        )
        return Cell(newIndex, name, rects, polygons, paths, refs)
    }
}

// Small growable primitive arrays (no boxing).

private val EMPTY_INTS = IntArray(0)
private val EMPTY_DOUBLES = DoubleArray(0)
private val EMPTY_BYTES = ByteArray(0)

internal class IntBuf {
    // Starts empty (shared, zero length) so that cells with nothing of a kind cost nothing; hundreds of thousands of cells are possible.
    private var data = EMPTY_INTS
    var size = 0
        private set

    fun add(v: Int) {
        if (size == data.size) data = data.copyOf(max(4, size + (size shr 1)))
        data[size++] = v
    }

    operator fun get(i: Int): Int = data[i]
    fun last(): Int = data[size - 1]
    fun toArray(): IntArray = if (size == 0) EMPTY_INTS else data.copyOf(size)
}

internal class DoubleBuf {
    private var data = EMPTY_DOUBLES
    var size = 0
        private set

    fun add(v: Double) {
        if (size == data.size) data = data.copyOf(max(4, size * 2))
        data[size++] = v
    }

    fun toArray(): DoubleArray = if (size == 0) EMPTY_DOUBLES else data.copyOf(size)
}

internal class ByteBuf {
    private var data = EMPTY_BYTES
    var size = 0
        private set

    fun add(v: Byte) {
        if (size == data.size) data = data.copyOf(max(4, size + (size shr 1)))
        data[size++] = v
    }

    operator fun get(i: Int): Byte = data[i]
    fun toArray(): ByteArray = if (size == 0) EMPTY_BYTES else data.copyOf(size)
}
