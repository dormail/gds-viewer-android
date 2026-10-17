package io.github.dormail.gdsviewer.gds

import java.io.ByteArrayOutputStream
import kotlin.math.abs

/** Encodes a Double as a GDSII 8-byte real (excess-64, base 16). */
fun encodeReal8(v: Double): ByteArray {
    val b = ByteArray(8)
    if (v == 0.0) return b
    var m = abs(v)
    var e = 64
    while (m >= 1.0) { m /= 16; e++ }
    while (m < 1.0 / 16) { m *= 16; e-- }
    val mant = (m * 72057594037927936.0).toLong() // m * 2^56
    b[0] = ((if (v < 0) 0x80 else 0) or e).toByte()
    for (i in 1..7) b[i] = (mant shr (8 * (7 - i))).toByte()
    return b
}

/** A tiny GDSII writer used only by tests to build byte arrays. */
class TestGdsWriter {
    private val out = ByteArrayOutputStream()

    fun rec(type: Int, dataType: Int, data: ByteArray = ByteArray(0)) {
        val len = data.size + 4
        out.write(len shr 8); out.write(len and 0xFF); out.write(type); out.write(dataType); out.write(data)
    }

    private fun i16(vararg v: Int) = ByteArray(v.size * 2) { (v[it / 2] shr (if (it % 2 == 0) 8 else 0)).toByte() }
    private fun i32(vararg v: Int) = ByteArray(v.size * 4) { (v[it / 4] shr (8 * (3 - it % 4))).toByte() }
    private fun real8(vararg v: Double) = v.fold(ByteArray(0)) { acc, d -> acc + encodeReal8(d) }
    private fun str(s: String): ByteArray {
        val b = s.toByteArray(Charsets.ISO_8859_1)
        return if (b.size % 2 == 0) b else b + 0
    }

    fun header() = apply { rec(0x00, 2, i16(600)) }
    fun bgnlib() = apply { rec(0x01, 2, i16(*IntArray(12))) }
    fun libname(n: String) = apply { rec(0x02, 6, str(n)) }
    fun units(user: Double = 1e-3, meters: Double = 1e-9) = apply { rec(0x03, 5, real8(user, meters)) }
    fun endlib() = apply { rec(0x04, 0) }
    fun bgnstr(name: String) = apply { rec(0x05, 2, i16(*IntArray(12))); rec(0x06, 6, str(name)) }
    fun endstr() = apply { rec(0x07, 0) }
    private fun endel() = rec(0x11, 0)

    fun boundary(layer: Int, datatype: Int, vararg xy: Int) = apply {
        rec(0x08, 0); rec(0x0D, 2, i16(layer)); rec(0x0E, 2, i16(datatype)); rec(0x10, 3, i32(*xy)); endel()
    }

    /** A BOX element (record 0x2D); [xy] should list 5 points (first repeated at the end). */
    fun box(layer: Int, boxtype: Int, vararg xy: Int) = apply {
        rec(0x2D, 0); rec(0x0D, 2, i16(layer)); rec(0x2E, 2, i16(boxtype)); rec(0x10, 3, i32(*xy)); endel()
    }

    fun path(layer: Int, datatype: Int, width: Int, pathType: Int, bgn: Int, end: Int, vararg xy: Int) = apply {
        rec(0x09, 0); rec(0x0D, 2, i16(layer)); rec(0x0E, 2, i16(datatype)); rec(0x21, 2, i16(pathType))
        rec(0x0F, 3, i32(width)); rec(0x30, 3, i32(bgn)); rec(0x31, 3, i32(end)); rec(0x10, 3, i32(*xy)); endel()
    }

    private fun strans(mirror: Boolean, mag: Double?, angle: Double?) {
        rec(0x1A, 1, i16(if (mirror) 0x8000 else 0))
        if (mag != null) rec(0x1B, 5, real8(mag))
        if (angle != null) rec(0x1C, 5, real8(angle))
    }

    fun sref(name: String, x: Int, y: Int, mirror: Boolean = false, mag: Double? = null, angle: Double? = null) = apply {
        rec(0x0A, 0); rec(0x12, 6, str(name)); strans(mirror, mag, angle); rec(0x10, 3, i32(x, y)); endel()
    }

    fun aref(name: String, cols: Int, rows: Int, vararg xy: Int) = apply {
        rec(0x0B, 0); rec(0x12, 6, str(name)); rec(0x13, 2, i16(cols, rows)); rec(0x10, 3, i32(*xy)); endel()
    }

    fun text(layer: Int, x: Int, y: Int, s: String) = apply {
        rec(0x0C, 0); rec(0x0D, 2, i16(layer)); rec(0x16, 2, i16(0)); rec(0x10, 3, i32(x, y)); rec(0x19, 6, str(s)); endel()
    }

    fun bytes(): ByteArray = out.toByteArray()
}
