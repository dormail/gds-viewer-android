package io.github.dormail.gdsviewer.scene

import java.util.Arrays

/** How one layer is drawn in the tiny raster: [layer] is the layer index, [argb] its color (its alpha byte is ignored; the opacity is `fillAlpha`). */
class RasterLayerStyle(val layer: Int, val argb: Int, val visible: Boolean)

/**
 * Turns the per-layer bits of a [TinyRaster] into ONE image, so the GPU needs a single texture and a
 * single draw call however many layers there are.
 *
 * Layers are blended bottom to top in the order of the style list with the usual "over" rule, each in
 * its color at opacity `fillAlpha`; hidden layers are skipped. The image holds premultiplied alpha
 * (red, green and blue are already multiplied by alpha), as bytes in the order R, G, B, A. Drawing it
 * with blend function (ONE, ONE_MINUS_SRC_ALPHA) over any background gives the same result (up to
 * rounding to 8 bits) as drawing every layer as a semi-transparent fill in turn.
 *
 * Speed: the bitmaps are walked 64 pixels (one Long) at a time and empty words are skipped. A layer
 * that covers all 64 pixels of a word is blended into a "pending" color for the whole word with one
 * calculation instead of 64, which keeps even a raster where every layer is completely filled fast.
 * The output array is reused between calls (it is overwritten by the next call).
 *
 * Math: channels are integers 0..32768 (32768 = 1.0). One "over" step is `C' = S + C * (1 - a)` where
 * S is the layer's premultiplied color; alpha uses the same formula with S = a.
 */
class RasterCompositor {
    private var out = ByteArray(0)

    // Per-word scratch: the 64 pixels' colors so far.
    private val pr = IntArray(64)
    private val pg = IntArray(64)
    private val pb = IntArray(64)
    private val pa = IntArray(64)

    /**
     * Composes [raster] and returns the image: at least `width * height * 4` bytes, row by row from the
     * top row, pixel (x, y) at index `(y * width + x) * 4`. The array is owned by this object and
     * overwritten by the next call.
     */
    fun compose(raster: TinyRaster, styles: List<RasterLayerStyle>, fillAlpha: Float): ByteArray {
        val n = raster.width * raster.height
        if (out.size < n * 4) out = ByteArray(n * 4) else Arrays.fill(out, 0, n * 4, 0)
        if (n <= 0) return out

        // Which bitmap belongs to which layer index.
        var maxLayer = -1
        for (l in raster.layers) if (l > maxLayer) maxLayer = l
        val bitmapOfLayer = IntArray(maxLayer + 1) { -1 }
        for (k in raster.layers.indices) if (raster.layers[k] >= 0) bitmapOfLayer[raster.layers[k]] = k

        // The drawn layers, bottom first, with their premultiplied colors.
        val a = Math.round(fillAlpha.coerceIn(0f, 1f) * ONE)
        var m = 0
        val maps = arrayOfNulls<LongArray>(styles.size)
        val sr = IntArray(styles.size)
        val sg = IntArray(styles.size)
        val sb = IntArray(styles.size)
        for (s in styles) {
            if (!s.visible || s.layer < 0 || s.layer > maxLayer) continue
            val k = bitmapOfLayer[s.layer]
            if (k < 0) continue
            maps[m] = raster.bits[k]
            sr[m] = premultiply((s.argb shr 16) and 0xFF, a)
            sg[m] = premultiply((s.argb shr 8) and 0xFF, a)
            sb[m] = premultiply(s.argb and 0xFF, a)
            m++
        }
        if (m == 0 || a == 0) return out
        val inv = ONE - a

        val wordCount = (n + 63) ushr 6
        for (w in 0 until wordCount) {
            // Pending: color of the layers that cover the whole word and are not yet applied to pr..pa.
            var qr = 0; var qg = 0; var qb = 0; var qa = 0
            var pending = false
            var touched = false // pr..pa hold real values (otherwise they count as all zero)
            var any = false
            for (i in 0 until m) {
                val bits = maps[i]!![w]
                if (bits == 0L) continue
                any = true
                if (bits == -1L) {
                    // Whole word: P' = S + P * (1 - a).
                    if (pending) {
                        qr = sr[i] + scale(qr, inv)
                        qg = sg[i] + scale(qg, inv)
                        qb = sb[i] + scale(qb, inv)
                        qa = a + scale(qa, inv)
                    } else {
                        qr = sr[i]; qg = sg[i]; qb = sb[i]; qa = a
                        pending = true
                    }
                    continue
                }
                // Partial word: first apply the pending color to all 64 pixels.
                if (pending) {
                    applyPending(touched, qr, qg, qb, qa)
                    touched = true
                    pending = false
                } else if (!touched) {
                    Arrays.fill(pr, 0); Arrays.fill(pg, 0); Arrays.fill(pb, 0); Arrays.fill(pa, 0)
                    touched = true
                }
                var rest = bits
                while (rest != 0L) {
                    val p = java.lang.Long.numberOfTrailingZeros(rest)
                    rest = rest and (rest - 1)
                    pr[p] = sr[i] + scale(pr[p], inv)
                    pg[p] = sg[i] + scale(pg[p], inv)
                    pb[p] = sb[i] + scale(pb[p], inv)
                    pa[p] = a + scale(pa[p], inv)
                }
            }
            if (!any) continue

            val base = w shl 6
            val count = minOf(64, n - base)
            var o = base * 4
            if (!touched) {
                // Only whole-word layers: all pixels have the same color.
                val r = to8(qr); val g = to8(qg); val b = to8(qb); val al = to8(qa)
                for (p in 0 until count) {
                    out[o] = r; out[o + 1] = g; out[o + 2] = b; out[o + 3] = al
                    o += 4
                }
                continue
            }
            if (pending) applyPending(true, qr, qg, qb, qa)
            for (p in 0 until count) {
                out[o] = to8(pr[p]); out[o + 1] = to8(pg[p]); out[o + 2] = to8(pb[p]); out[o + 3] = to8(pa[p])
                o += 4
            }
        }
        return out
    }

    /** Puts the whole-word color (qr..qa) on top of the 64 pixels (which count as zero if [touched] is false). */
    private fun applyPending(touched: Boolean, qr: Int, qg: Int, qb: Int, qa: Int) {
        if (!touched) {
            Arrays.fill(pr, qr); Arrays.fill(pg, qg); Arrays.fill(pb, qb); Arrays.fill(pa, qa)
            return
        }
        val f = ONE - qa
        for (p in 0 until 64) {
            pr[p] = qr + scale(pr[p], f)
            pg[p] = qg + scale(pg[p], f)
            pb[p] = qb + scale(pb[p], f)
            pa[p] = qa + scale(pa[p], f)
        }
    }

    private companion object {
        const val ONE = 32768

        /** color8 / 255 * alpha, in 0..32768 units. */
        fun premultiply(color8: Int, a: Int) = (color8 * a + 127) / 255

        /** c * f / 32768, rounded; c and f are at most 32768, so the product fits in an Int. */
        fun scale(c: Int, f: Int) = (c * f + 16384) shr 15

        fun to8(c: Int): Byte = ((c * 255 + 16384) shr 15).coerceAtMost(255).toByte()
    }
}
