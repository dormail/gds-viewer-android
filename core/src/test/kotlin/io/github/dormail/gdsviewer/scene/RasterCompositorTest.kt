package io.github.dormail.gdsviewer.scene

import io.github.dormail.gdsviewer.assertEquals
import io.github.dormail.gdsviewer.assertTrue
import io.github.dormail.gdsviewer.geometry.Affine
import java.util.Random
import org.junit.Test

class RasterCompositorTest {
    private fun raster(w: Int, h: Int, layers: IntArray, bits: Array<LongArray>) =
        TinyRaster(w, h, Affine.IDENTITY, layers, bits)

    private fun randomRaster(rnd: Random, w: Int, h: Int, layerCount: Int, density: Double, fullWords: Boolean): TinyRaster {
        val words = (w * h + 63) / 64
        val bits = Array(layerCount) {
            LongArray(words) {
                when {
                    fullWords && rnd.nextDouble() < 0.3 -> -1L
                    rnd.nextDouble() < 0.4 -> 0L
                    else -> {
                        var v = 0L
                        for (b in 0 until 64) if (rnd.nextDouble() < density) v = v or (1L shl b)
                        v
                    }
                }
            }
        }
        return raster(w, h, IntArray(layerCount) { it }, bits)
    }

    /** Simple reference: blend the layers one after the other onto the background, per pixel, in floating point. */
    private fun reference(r: TinyRaster, styles: List<RasterLayerStyle>, alpha: Float, bg: IntArray): IntArray {
        val out = IntArray(r.width * r.height * 3)
        for (y in 0 until r.height) for (x in 0 until r.width) {
            val c = doubleArrayOf(bg[0].toDouble(), bg[1].toDouble(), bg[2].toDouble())
            for (s in styles) {
                if (!s.visible) continue
                val k = r.layers.indexOf(s.layer)
                if (k < 0 || !r.isSet(k, x, y)) continue
                c[0] = c[0] * (1 - alpha) + ((s.argb shr 16) and 0xFF) * alpha
                c[1] = c[1] * (1 - alpha) + ((s.argb shr 8) and 0xFF) * alpha
                c[2] = c[2] * (1 - alpha) + (s.argb and 0xFF) * alpha
            }
            val i = (y * r.width + x) * 3
            for (j in 0..2) out[i + j] = Math.round(c[j]).toInt()
        }
        return out
    }

    /** Result of drawing the composed (premultiplied) image over the background. */
    private fun over(img: ByteArray, r: TinyRaster, bg: IntArray): IntArray {
        val out = IntArray(r.width * r.height * 3)
        for (p in 0 until r.width * r.height) {
            val a = img[p * 4 + 3].toInt() and 0xFF
            for (j in 0..2) {
                val c = img[p * 4 + j].toInt() and 0xFF
                out[p * 3 + j] = c + Math.round(bg[j] * (255 - a) / 255.0).toInt()
            }
        }
        return out
    }

    private fun assertClose(expected: IntArray, actual: IntArray, tol: Int) {
        for (i in expected.indices) {
            assertTrue(Math.abs(expected[i] - actual[i]) <= tol, "index $i: expected ${expected[i]} got ${actual[i]}")
        }
    }

    private fun palette(rnd: Random, n: Int) =
        List(n) { RasterLayerStyle(it, 0xFF000000.toInt() or rnd.nextInt(0x1000000), true) }

    private fun byte(img: ByteArray, i: Int) = img[i].toInt() and 0xFF

    @Test fun matchesSequentialBlendOnRandomRasters() {
        val rnd = Random(1)
        val comp = RasterCompositor()
        for (round in 0 until 60) {
            val w = 1 + rnd.nextInt(40)
            val h = 1 + rnd.nextInt(40)
            val n = 1 + rnd.nextInt(12)
            val r = randomRaster(rnd, w, h, n, rnd.nextDouble(), round % 2 == 0)
            val styles = palette(rnd, n)
            val alpha = listOf(0.4f, 1f, 0.17f, 0.9f)[round % 4]
            val bg = intArrayOf(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256))
            val img = comp.compose(r, styles, alpha)
            assertClose(reference(r, styles, alpha, bg), over(img, r, bg), 3)
        }
    }

    @Test fun hiddenLayersAreSkipped() {
        val r = raster(8, 1, intArrayOf(0, 1), arrayOf(longArrayOf(1L), longArrayOf(1L)))
        val img = RasterCompositor().compose(
            r, listOf(RasterLayerStyle(0, 0xFFFF0000.toInt(), true), RasterLayerStyle(1, 0xFF00FF00.toInt(), false)), 1f,
        )
        assertEquals(255, byte(img, 0))
        assertEquals(0, byte(img, 1))
        assertEquals(255, byte(img, 3))
    }

    @Test fun layerOrderFollowsStyleList() {
        val r = raster(8, 1, intArrayOf(0, 1), arrayOf(longArrayOf(1L), longArrayOf(1L)))
        val red = RasterLayerStyle(0, 0xFFFF0000.toInt(), true)
        val green = RasterLayerStyle(1, 0xFF00FF00.toInt(), true)
        val a = RasterCompositor().compose(r, listOf(red, green), 1f)
        assertEquals(255, byte(a, 1)) // green on top
        assertEquals(0, byte(a, 0))
        val b = RasterCompositor().compose(r, listOf(green, red), 1f)
        assertEquals(255, byte(b, 0)) // red on top
        assertEquals(0, byte(b, 1))
        // Half opacity: top layer counts 50 %, the lower one 25 % (premultiplied).
        val c = RasterCompositor().compose(r, listOf(red, green), 0.5f)
        assertTrue(Math.abs(byte(c, 0) - 64) <= 1)
        assertTrue(Math.abs(byte(c, 1) - 128) <= 1)
        assertTrue(Math.abs(byte(c, 3) - 191) <= 1)
    }

    @Test fun emptyAndUncoveredPixelsAreTransparentAndBufferIsCleared() {
        val comp = RasterCompositor()
        val style = listOf(RasterLayerStyle(0, 0xFFFFFFFF.toInt(), true))
        val img1 = comp.compose(raster(8, 8, intArrayOf(0), arrayOf(longArrayOf(-1L))), style, 1f)
        assertEquals(255, byte(img1, 3))
        val img2 = comp.compose(raster(8, 8, intArrayOf(0), arrayOf(longArrayOf(0L))), style, 1f)
        for (i in 0 until 8 * 8 * 4) assertEquals(0, byte(img2, i))
        val none = comp.compose(raster(8, 8, IntArray(0), emptyArray()), style, 1f)
        for (i in 0 until 8 * 8 * 4) assertEquals(0, byte(none, i))
    }

    @Test fun colorAlphaByteIsIgnoredAndZeroOpacityGivesNothing() {
        val r = raster(8, 1, intArrayOf(0), arrayOf(longArrayOf(1L)))
        val half = RasterLayerStyle(0, 0x80FF0000.toInt(), true)
        val opaque = RasterLayerStyle(0, 0xFFFF0000.toInt(), true)
        val a = RasterCompositor().compose(r, listOf(half), 0.5f).copyOf(4)
        val b = RasterCompositor().compose(r, listOf(opaque), 0.5f).copyOf(4)
        assertEquals(b.toList(), a.toList())
        assertEquals(0, byte(RasterCompositor().compose(r, listOf(opaque), 0f), 3))
    }

    @Test fun unknownLayersAndLastPartialWordAreHandled() {
        // 5 x 5 = 25 pixels: only 25 bits of the word count.
        val r = raster(5, 5, intArrayOf(2), arrayOf(longArrayOf(-1L)))
        val img = RasterCompositor().compose(
            r, listOf(RasterLayerStyle(0, -1, true), RasterLayerStyle(2, 0xFF0000FF.toInt(), true), RasterLayerStyle(99, -1, true)), 1f,
        )
        assertEquals(255, byte(img, 24 * 4 + 2))
    }

    @Test fun timing() {
        val w = 810
        val h = 1650
        val words = (w * h + 63) / 64
        val rnd = Random(5)
        val comp = RasterCompositor()
        val layers = 143
        val styles = palette(rnd, layers)

        // Realistic: every layer has some blobs; a pixel is in about 1-3 layers.
        val sparse = Array(layers) { LongArray(words) }
        for (k in 0 until layers) {
            for (blob in 0 until 60) {
                val cx = rnd.nextInt(w - 60)
                val cy = rnd.nextInt(h - 40)
                for (y in cy until cy + 40) for (x in cx until cx + 60) {
                    val i = y * w + x
                    sparse[k][i ushr 6] = sparse[k][i ushr 6] or (1L shl (i and 63))
                }
            }
        }
        val ids = IntArray(layers) { it }
        val cases = listOf(
            "sparse" to raster(w, h, ids, sparse),
            "every layer completely filled" to raster(w, h, ids, Array(layers) { LongArray(words) { -1L } }),
            "every layer on every second pixel" to raster(w, h, ids, Array(layers) { LongArray(words) { 0x5555555555555555L } }),
        )
        for ((name, r) in cases) {
            comp.compose(r, styles, 0.4f) // warm-up
            var best = Long.MAX_VALUE
            for (i in 0 until 5) {
                val t = System.nanoTime()
                comp.compose(r, styles, 0.4f)
                best = minOf(best, System.nanoTime() - t)
            }
            println("RasterCompositor $w x $h, $layers layers, $name: ${best / 1_000_000.0} ms")
            assertTrue(best < 5_000_000_000L)
        }
    }
}
