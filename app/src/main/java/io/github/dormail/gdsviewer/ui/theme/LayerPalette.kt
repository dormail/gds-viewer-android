package io.github.dormail.gdsviewer.ui.theme

import io.github.dormail.gdsviewer.gds.LayerKey

/**
 * Layer colors. A layer stores only its index; the color depends on the theme: lighter shades
 * on the dark canvas, darker shades (same hue family, same index) on the light canvas.
 */
object LayerPalette {
    private val DARK = intArrayOf(
        0xFF4FC3F7.toInt(), 0xFFEF5350.toInt(), 0xFF66BB6A.toInt(), 0xFFFFCA28.toInt(),
        0xFFAB47BC.toInt(), 0xFFFF7043.toInt(), 0xFF26C6DA.toInt(), 0xFFD4E157.toInt(),
        0xFFEC407A.toInt(), 0xFF8D6E63.toInt(), 0xFF5C6BC0.toInt(), 0xFF9CCC65.toInt(),
        0xFFFFA726.toInt(), 0xFF7E57C2.toInt(), 0xFF29B6F6.toInt(), 0xFFBDBDBD.toInt(),
    )
    private val LIGHT = intArrayOf(
        0xFF0277BD.toInt(), 0xFFC62828.toInt(), 0xFF2E7D32.toInt(), 0xFFFF8F00.toInt(),
        0xFF6A1B9A.toInt(), 0xFFD84315.toInt(), 0xFF00838F.toInt(), 0xFF9E9D24.toInt(),
        0xFFAD1457.toInt(), 0xFF4E342E.toInt(), 0xFF283593.toInt(), 0xFF558B2F.toInt(),
        0xFFEF6C00.toInt(), 0xFF4527A0.toInt(), 0xFF0288D1.toInt(), 0xFF616161.toInt(),
    )

    val size get() = DARK.size

    /** ARGB color of palette entry [index] (wraps around). */
    fun color(index: Int, dark: Boolean): Int = (if (dark) DARK else LIGHT)[index % size]

    /** Opacity of the shape fill (outlines are always opaque). */
    fun fillAlpha(dark: Boolean): Float = if (dark) 0.4f else 0.35f

    // Viridis colour map (perceptually ordered: dark purple -> blue -> green -> yellow), given as
    // nine evenly spaced control points; colours in between are mixed linearly.
    private val VIRIDIS = intArrayOf(
        0xFF440154.toInt(), 0xFF472D7B.toInt(), 0xFF3B528B.toInt(), 0xFF2C728E.toInt(),
        0xFF21918C.toInt(), 0xFF28AE80.toInt(), 0xFF5EC962.toInt(), 0xFFADDC30.toInt(),
        0xFFFDE725.toInt(),
    )

    /**
     * The gray value 0..255 of a layer, if it is a DXF layer whose name is a whole number in that
     * range (gray-scale lithography files), otherwise null.
     */
    fun grayValue(key: LayerKey): Int? =
        if (key.name != null && key.layer in 0..255) key.layer else null

    /**
     * Colour for gray value [value] (0 = dark end, 255 = bright end of the colour map). The ends
     * are pulled in a little so they stay visible on the canvas: the dark end is lifted on the
     * dark canvas, the yellow end is darkened on the light canvas.
     */
    fun grayColor(value: Int, dark: Boolean): Int {
        val v = value.coerceIn(0, 255) / 255f
        val t = if (dark) 0.12f + 0.88f * v else 0.9f * v
        val pos = t * (VIRIDIS.size - 1)
        val i = pos.toInt().coerceAtMost(VIRIDIS.size - 2)
        val f = pos - i
        val a = VIRIDIS[i]
        val b = VIRIDIS[i + 1]
        fun mix(shift: Int): Int {
            val x = (a shr shift) and 0xFF
            val y = (b shr shift) and 0xFF
            return (x + (y - x) * f + 0.5f).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    /** Colour of a layer: from the gray value if it has one, otherwise from the palette. */
    fun color(key: LayerKey, paletteIndex: Int, dark: Boolean): Int {
        val gray = grayValue(key)
        return if (gray != null) grayColor(gray, dark) else color(paletteIndex, dark)
    }
}
