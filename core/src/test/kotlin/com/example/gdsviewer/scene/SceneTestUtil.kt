package com.example.gdsviewer.scene

import com.example.gdsviewer.db.Library
import com.example.gdsviewer.db.LibraryBuilder
import kotlin.math.abs

/** Builds a [Library] with a small DSL: `lib { cell("A") { box(0, 0, 0, 10, 10) } }`. */
internal fun lib(block: LibraryBuilder.() -> Unit): Library {
    val b = LibraryBuilder()
    b.block()
    return b.build("test", 1e-9)
}

/** Sum of the areas of all triangles of layer slot [k] of [mesh]. */
internal fun triangleArea(mesh: ChunkMesh, k: Int): Double {
    var sum = 0.0
    var t = mesh.triangleStart[k]
    while (t < mesh.triangleStart[k + 1]) {
        val a = mesh.triangles[t]; val b = mesh.triangles[t + 1]; val c = mesh.triangles[t + 2]
        val v = mesh.vertices
        val ax = v[2 * a].toDouble(); val ay = v[2 * a + 1].toDouble()
        sum += abs((v[2 * b] - ax) * (v[2 * c + 1] - ay) - (v[2 * c] - ax) * (v[2 * b + 1] - ay)) / 2
        t += 3
    }
    return sum
}

/** World (µm) -> screen view that shows world point ([cx], [cy]) in the middle of a [w] x [h] viewport at [scale] px per µm (y flipped). */
internal fun testView(cx: Double, cy: Double, scale: Double, w: Int, h: Int): com.example.gdsviewer.geometry.Affine =
    com.example.gdsviewer.geometry.Affine.translate(w / 2.0, h / 2.0) *
        com.example.gdsviewer.geometry.Affine.scale(scale, -scale) *
        com.example.gdsviewer.geometry.Affine.translate(-cx, -cy)
