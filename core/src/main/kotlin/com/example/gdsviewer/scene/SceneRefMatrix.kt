package com.example.gdsviewer.scene

import com.example.gdsviewer.db.RefStore

/**
 * The geometry of one placement (SREF / AREF) as plain doubles, so bounds code can handle
 * millions of placements without allocating an [com.example.gdsviewer.geometry.Affine] each.
 * Mutable and not thread-safe: use one per thread.
 *
 * Instance (col, row) maps (x, y) to (a x + c y + tx, b x + d y + ty) with
 * tx = [x] + col * [colX] + row * [rowX] and ty = [y] + col * [colY] + row * [rowY].
 * The linear part matches [RefStore.transform] (and `Affine.placement`).
 */
internal class RefMatrix {
    var a = 1.0; var b = 0.0; var c = 0.0; var d = 1.0
    var x = 0.0; var y = 0.0
    var colX = 0.0; var colY = 0.0; var rowX = 0.0; var rowY = 0.0
    var cols = 1; var rows = 1

    /** Result of [mapBox]. */
    var minX = 0.0; var minY = 0.0; var maxX = 0.0; var maxY = 0.0

    fun load(refs: RefStore, i: Int) {
        val e = refs.extra[i]
        x = refs.x[i].toDouble()
        y = refs.y[i].toDouble()
        if (e < 0) {
            val o = refs.orientation[i].toInt()
            val f = if (o and 4 != 0) -1.0 else 1.0
            val cos = quarterCos(o and 3)
            val sin = quarterCos((o + 3) and 3) // sin(k * 90 deg) = cos((k - 1) * 90 deg)
            a = cos; b = sin; c = -sin * f; d = cos * f
            colX = 0.0; colY = 0.0; rowX = 0.0; rowY = 0.0
            cols = 1; rows = 1
        } else {
            val m = refs.magnification(i)
            val f = if (refs.mirrored(i)) -1.0 else 1.0
            val deg = refs.angleDegrees(i)
            val q = quarter(deg)
            val cos: Double
            val sin: Double
            if (q >= 0) {
                cos = quarterCos(q); sin = quarterCos((q + 3) and 3)
            } else {
                val r = Math.toRadians(deg)
                cos = Math.cos(r); sin = Math.sin(r)
            }
            a = m * cos; b = m * sin; c = -m * sin * f; d = m * cos * f
            colX = refs.columnStepX(i); colY = refs.columnStepY(i)
            rowX = refs.rowStepX(i); rowY = refs.rowStepY(i)
            cols = refs.columns(i); rows = refs.rows(i)
        }
    }

    /** Bounding box of the box (x0, y0, x1, y1) transformed by the linear part and moved by (tx, ty); result in [minX]... */
    fun mapBox(x0: Double, y0: Double, x1: Double, y1: Double, tx: Double, ty: Double) {
        val ax0 = a * x0; val ax1 = a * x1; val cy0 = c * y0; val cy1 = c * y1
        minX = tx + minOf(ax0, ax1) + minOf(cy0, cy1)
        maxX = tx + maxOf(ax0, ax1) + maxOf(cy0, cy1)
        val bx0 = b * x0; val bx1 = b * x1; val dy0 = d * y0; val dy1 = d * y1
        minY = ty + minOf(bx0, bx1) + minOf(dy0, dy1)
        maxY = ty + maxOf(bx0, bx1) + maxOf(dy0, dy1)
    }

    /** Translation of one of the four corner instances (k = 0..3: column 0 / last, row 0 / last). */
    fun cornerX(k: Int): Double = x + (if (k and 1 != 0) cols - 1 else 0) * colX + (if (k and 2 != 0) rows - 1 else 0) * rowX
    fun cornerY(k: Int): Double = y + (if (k and 1 != 0) cols - 1 else 0) * colY + (if (k and 2 != 0) rows - 1 else 0) * rowY

    /** Number of distinct corner instances to look at (1 for a plain placement). */
    val cornerCount: Int get() = if (cols == 1 && rows == 1) 1 else 4

    /** Bounding box of all instances of a child with box (x0, y0, x1, y1), result in [minX]... (call [load] first). */
    fun mapAll(x0: Double, y0: Double, x1: Double, y1: Double) {
        var bx0 = Double.MAX_VALUE; var by0 = Double.MAX_VALUE
        var bx1 = -Double.MAX_VALUE; var by1 = -Double.MAX_VALUE
        for (k in 0 until cornerCount) {
            mapBox(x0, y0, x1, y1, cornerX(k), cornerY(k))
            if (minX < bx0) bx0 = minX; if (maxX > bx1) bx1 = maxX
            if (minY < by0) by0 = minY; if (maxY > by1) by1 = maxY
        }
        minX = bx0; minY = by0; maxX = bx1; maxY = by1
    }

    private fun quarterCos(q: Int): Double = when (q and 3) { 0 -> 1.0; 2 -> -1.0; else -> 0.0 }

    /** 0..3 if [deg] is an exact multiple of 90 degrees, else -1. */
    private fun quarter(deg: Double): Int = when (((deg % 360.0) + 360.0) % 360.0) {
        0.0 -> 0; 90.0 -> 1; 180.0 -> 2; 270.0 -> 3; else -> -1
    }
}
