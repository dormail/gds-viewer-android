package com.example.gdsviewer.geometry

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A 2D affine transform (rotation, scaling, mirroring and translation):
 *
 *     x' = a * x + c * y + tx
 *     y' = b * x + d * y + ty
 *
 * Used for two things: placing cell instances inside their parent cell, and mapping layout
 * coordinates to screen pixels (the "view").
 */
class Affine(
    val a: Double,
    val b: Double,
    val c: Double,
    val d: Double,
    val tx: Double,
    val ty: Double,
) {
    fun mapX(x: Double, y: Double): Double = a * x + c * y + tx
    fun mapY(x: Double, y: Double): Double = b * x + d * y + ty

    /** Returns the transform that first applies [other] and then this one. */
    operator fun times(other: Affine) = Affine(
        a * other.a + c * other.b,
        b * other.a + d * other.b,
        a * other.c + c * other.d,
        b * other.c + d * other.d,
        a * other.tx + c * other.ty + tx,
        b * other.tx + d * other.ty + ty,
    )

    fun inverse(): Affine {
        val det = a * d - b * c
        val ia = d / det
        val ib = -b / det
        val ic = -c / det
        val id = a / det
        return Affine(ia, ib, ic, id, -(ia * tx + ic * ty), -(ib * tx + id * ty))
    }

    /** How much lengths are scaled (assumes uniform scaling, which is all we ever use). */
    val scale: Double get() = sqrt(abs(a * d - b * c))

    /** Angle in degrees by which the x axis is rotated (in the target coordinate system). */
    val rotationDegrees: Double get() = Math.toDegrees(atan2(b, a))

    override fun toString() = "Affine(a=$a, b=$b, c=$c, d=$d, tx=$tx, ty=$ty)"

    companion object {
        val IDENTITY = Affine(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)

        fun translate(x: Double, y: Double) = Affine(1.0, 0.0, 0.0, 1.0, x, y)

        fun scale(sx: Double, sy: Double = sx) = Affine(sx, 0.0, 0.0, sy, 0.0, 0.0)

        /** Rotation by [degrees]: counter-clockwise when y points up, clockwise on screen (y down). */
        fun rotate(degrees: Double): Affine {
            val (cos, sin) = cosSin(degrees)
            return Affine(cos, sin, -sin, cos, 0.0, 0.0)
        }

        /** Placement of a GDS cell instance: mirror about x (optional), scale, rotate, move to (x, y). */
        fun placement(x: Double, y: Double, mirrorX: Boolean, magnification: Double, angleDegrees: Double): Affine {
            val (cos, sin) = cosSin(angleDegrees)
            val m = magnification
            val f = if (mirrorX) -1.0 else 1.0
            return Affine(m * cos, m * sin, -m * sin * f, m * cos * f, x, y)
        }

        /** cos and sin, exact for multiples of 90 degrees (so rotated rectangles stay on the grid). */
        private fun cosSin(degrees: Double): Pair<Double, Double> =
            when (((degrees % 360.0) + 360.0) % 360.0) {
                0.0 -> 1.0 to 0.0
                90.0 -> 0.0 to 1.0
                180.0 -> -1.0 to 0.0
                270.0 -> 0.0 to -1.0
                else -> Math.toRadians(degrees).let { cos(it) to sin(it) }
            }
    }
}
