package io.github.dormail.gdsviewer.scene

import io.github.dormail.gdsviewer.assertEquals
import io.github.dormail.gdsviewer.geometry.Affine
import org.junit.Test

class SceneRasterTest {
    private fun mark(r: SceneRaster, layer: Int, x: Double) =
        r.fillBox(layer, 1.0, 0.0, 0.0, 1.0, 0.0, 0.0, x, 1.0, x + 1, 2.0)

    @Test fun moreThan128LayersEachGetTheirOwnBitmap() {
        val r = SceneRaster(810, 1650, Affine.IDENTITY, 300)
        for (layer in 0 until 300) mark(r, layer, layer.toDouble())
        val t = r.finish()!!
        assertEquals(300, t.layers.size)
        for (k in 0 until 300) {
            assertEquals(true, t.isSet(k, t.layers[k], 1))
            assertEquals(false, t.isSet(k, (t.layers[k] + 1) % 300, 1))
        }
    }

    @Test fun beyondTheBudgetLayersShareOneBitmap() {
        // 1000 x 1000 pixels = 125 KB per bitmap, so about 537 fit into 64 MiB.
        val bytes = (1000L * 1000 + 63) / 64 * 8
        val fit = (SceneRaster.MAX_BITMAP_BYTES / bytes).toInt()
        val count = fit + 20
        val r = SceneRaster(1000, 1000, Affine.IDENTITY, count)
        for (layer in 0 until count) mark(r, layer, (layer % 900).toDouble())
        val t = r.finish()!!
        // The own bitmaps plus the one shared bitmap.
        assertEquals(fit + 1, t.layers.size)
        val shared = t.layers.indexOf(fit)
        for (layer in fit until count) assertEquals(true, t.isSet(shared, layer % 900, 1))
    }
}
