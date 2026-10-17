package io.github.dormail.gdsviewer.ui.theme

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
}
