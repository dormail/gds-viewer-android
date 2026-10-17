package io.github.dormail.gdsviewer.scene

import io.github.dormail.gdsviewer.geometry.Affine
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The per-layer bitmaps of a [TinyRaster] while they are being filled. One bit per raster pixel;
 * a layer's bitmap is only allocated when something is first drawn into it. At most
 * [MAX_BITMAPS] bitmaps are made: later layers all draw into one shared bitmap (shown in the
 * color of the first layer that did not get its own), so memory stays bounded for files with
 * thousands of layers.
 */
internal class SceneRaster(val width: Int, val height: Int, val worldToRaster: Affine, layerCount: Int) {
    private val bits = arrayOfNulls<LongArray>(layerCount)
    private val words = (width.toLong() * height + 63) / 64
    private var bitmapCount = 0
    private var sharedLayer = -1 // the layer index that owns the shared bitmap, or -1

    // Box corners in raster pixels, result of the last mapBox call.
    private var x0 = 0.0; private var y0 = 0.0; private var x1 = 0.0; private var y1 = 0.0

    /**
     * Marks the pixels covered by the box (bx0, by0)..(bx1, by1) on [layer]. The box is given in a
     * coordinate system that the transform (ta, tb, tc, td, ttx, tty) (as [Affine]) maps to world µm.
     * Boxes smaller than a pixel still set the one pixel under their center.
     */
    fun fillBox(
        layer: Int, ta: Double, tb: Double, tc: Double, td: Double, ttx: Double, tty: Double,
        bx0: Double, by0: Double, bx1: Double, by1: Double,
    ): Int {
        // Compose world -> raster with the given transform, without allocating.
        val r = worldToRaster
        val a = r.a * ta + r.c * tb; val b = r.b * ta + r.d * tb
        val c = r.a * tc + r.c * td; val d = r.b * tc + r.d * td
        val tx = r.a * ttx + r.c * tty + r.tx; val ty = r.b * ttx + r.d * tty + r.ty
        val ax0 = a * bx0; val ax1 = a * bx1; val cy0 = c * by0; val cy1 = c * by1
        x0 = tx + minOf(ax0, ax1) + minOf(cy0, cy1)
        x1 = tx + maxOf(ax0, ax1) + maxOf(cy0, cy1)
        val bx0v = b * bx0; val bx1v = b * bx1; val dy0 = d * by0; val dy1 = d * by1
        y0 = ty + minOf(bx0v, bx1v) + minOf(dy0, dy1)
        y1 = ty + maxOf(bx0v, bx1v) + maxOf(dy0, dy1)
        return fill(layer)
    }

    /** Fills the box in x0..y1 and returns the number of raster rows it touched (a measure of the work done). */
    private fun fill(layer: Int): Int {
        if (x1 < 0 || y1 < 0 || x0 > width || y0 > height) return 0
        var px0 = floor(x0); var px1 = ceil(x1) - 1
        var py0 = floor(y0); var py1 = ceil(y1) - 1
        if (x1 - x0 < 1.0) { px0 = floor((x0 + x1) / 2); px1 = px0 }
        if (y1 - y0 < 1.0) { py0 = floor((y0 + y1) / 2); py1 = py0 }
        if (px0 < 0) px0 = 0.0
        if (py0 < 0) py0 = 0.0
        if (px1 > width - 1) px1 = width - 1.0
        if (py1 > height - 1) py1 = height - 1.0
        if (px0 > px1 || py0 > py1) return 0
        val arr = bitmapFor(layer)
        val ix0 = px0.toInt(); val ix1 = px1.toInt()
        for (y in py0.toInt()..py1.toInt()) setRange(arr, y * width + ix0, y * width + ix1)
        return py1.toInt() - py0.toInt() + 1
    }

    private fun bitmapFor(layer: Int): LongArray {
        bits[layer]?.let { return it }
        if (bitmapCount < MAX_BITMAPS) {
            bitmapCount++
            return LongArray(words.toInt()).also { bits[layer] = it }
        }
        if (sharedLayer < 0) {
            sharedLayer = layer
            return LongArray(words.toInt()).also { bits[layer] = it }
        }
        return bits[sharedLayer]!!
    }

    /** Sets bits [from]..[to] (inclusive) using whole-word masks. */
    private fun setRange(arr: LongArray, from: Int, to: Int) {
        val w0 = from ushr 6; val w1 = to ushr 6
        val m0 = -1L shl (from and 63)
        val m1 = -1L ushr (63 - (to and 63))
        if (w0 == w1) {
            arr[w0] = arr[w0] or (m0 and m1)
        } else {
            arr[w0] = arr[w0] or m0
            for (w in w0 + 1 until w1) arr[w] = -1L
            arr[w1] = arr[w1] or m1
        }
    }

    /** The finished raster, or null if nothing was drawn. */
    fun finish(): TinyRaster? {
        val used = bits.indices.filter { bits[it] != null }
        if (used.isEmpty()) return null
        return TinyRaster(width, height, worldToRaster, used.toIntArray(), Array(used.size) { bits[used[it]]!! })
    }

    private companion object {
        /** Own bitmaps (about 180 KB each for a phone screen). Real designs have far fewer layers in view. */
        const val MAX_BITMAPS = 128
    }
}
