package io.github.dormail.gdsviewer.scene

import io.github.dormail.gdsviewer.db.Library
import io.github.dormail.gdsviewer.geometry.DoubleList
import io.github.dormail.gdsviewer.geometry.IntList
import io.github.dormail.gdsviewer.geometry.PathGeometry
import io.github.dormail.gdsviewer.geometry.Triangulator

/** Turns the shapes of a [Chunk] into a [ChunkMesh] (fill triangles + outline segments per layer). */
object MeshBuilder {
    fun build(library: Library, chunk: Chunk): ChunkMesh {
        val cell = library.cells[chunk.cell]

        // Sort the members by layer (stable), once per kind of shape. Only the layers that are used
        // take part, so the work does not depend on how many layers the library has.
        val rects = byLayer(cell.rects.layer, chunk.rects)
        val polygons = byLayer(cell.polygons.layer, chunk.polygons)
        val paths = byLayer(cell.paths.layer, chunk.paths)

        val rectCount = rects.size
        val vertices = FloatBuf(8 * rectCount + 2 * cell.polygons.xy.size.coerceAtMost(1 shl 20) + 64)
        val triangles = IntList(6 * rectCount + 64)
        val lines = IntList(8 * rectCount + 64)

        val layerList = ArrayList<Int>()
        val triangleStart = ArrayList<Int>()
        val lineStart = ArrayList<Int>()

        val ox = chunk.originX.toLong()
        val oy = chunk.originY.toLong()
        var scratch = DoubleArray(64)
        val triSoup = DoubleList()
        val lineSoup = DoubleList()
        val rs = cell.rects
        val ps = cell.polygons
        val ws = cell.paths

        var r0 = 0; var p0 = 0; var w0 = 0 // start of the next layer's members in each sorted list
        while (r0 < rects.size || p0 < polygons.size || w0 < paths.size) {
            // The next layer is the smallest one still waiting in any of the three lists.
            val layer = minOf(rects.layerAt(r0), polygons.layerAt(p0), paths.layerAt(w0))
            val r1 = rects.end(r0, layer); val p1 = polygons.end(p0, layer); val w1 = paths.end(w0, layer)
            layerList += layer
            triangleStart += triangles.size
            lineStart += lines.size

            for (k in r0 until r1) {
                val i = rects.order[k]
                val x0 = (rs.xy[4 * i] - ox).toFloat()
                val y0 = (rs.xy[4 * i + 1] - oy).toFloat()
                val x1 = (rs.xy[4 * i + 2] - ox).toFloat()
                val y1 = (rs.xy[4 * i + 3] - oy).toFloat()
                val v = vertices.size / 2
                vertices.add4(x0, y0, x1, y0, x1, y1, x0, y1)
                triangles.add(v, v + 1, v + 2); triangles.add(v, v + 2, v + 3)
                lines.add(v, v + 1); lines.add(v + 1, v + 2); lines.add(v + 2, v + 3); lines.add(v + 3, v)
            }

            for (k in p0 until p1) {
                val i = polygons.order[k]
                val first = ps.start[i]
                val n = ps.pointCount(i)
                if (scratch.size < 2 * n) scratch = DoubleArray(2 * n + n / 2)
                val v = vertices.size / 2
                for (j in 0 until n) {
                    val x = (ps.xy[2 * (first + j)] - ox).toDouble()
                    val y = (ps.xy[2 * (first + j) + 1] - oy).toDouble()
                    scratch[2 * j] = x
                    scratch[2 * j + 1] = y
                    vertices.add2(x.toFloat(), y.toFloat())
                }
                Triangulator.triangulateIndices(scratch, n, triangles, v)
                for (j in 0 until n) lines.add(v + j, v + if (j + 1 == n) 0 else j + 1)
            }

            for (k in w0 until w1) {
                val i = paths.order[k]
                triSoup.clear()
                lineSoup.clear()
                PathGeometry.build(
                    ws.xy, ws.start[i], ws.pointCount(i), ws.width[i], ws.type[i],
                    ws.beginExtension[i], ws.endExtension[i], triSoup, lineSoup,
                )
                // The soups have 3 (triangles) or 2 (lines) vertices per primitive, no sharing.
                var v = vertices.size / 2
                for (j in 0 until triSoup.size / 2) vertices.add2((triSoup.data[2 * j] - ox).toFloat(), (triSoup.data[2 * j + 1] - oy).toFloat())
                for (j in 0 until triSoup.size / 6) triangles.add(v + 3 * j, v + 3 * j + 1, v + 3 * j + 2)
                v += triSoup.size / 2
                for (j in 0 until lineSoup.size / 2) vertices.add2((lineSoup.data[2 * j] - ox).toFloat(), (lineSoup.data[2 * j + 1] - oy).toFloat())
                for (j in 0 until lineSoup.size / 4) lines.add(v + 2 * j, v + 2 * j + 1)
            }
            r0 = r1; p0 = p1; w0 = w1
        }
        triangleStart += triangles.size
        lineStart += lines.size
        return ChunkMesh(
            chunk, vertices.toArray(), triangles.toArray(), lines.toArray(),
            layerList.toIntArray(), triangleStart.toIntArray(), lineStart.toIntArray(),
        )
    }

    /** Member indices of one kind of shape sorted by layer (ties in member order), with their layers alongside. */
    private class ByLayer(val order: IntArray, val layers: IntArray) {
        val size: Int get() = order.size

        /** Layer of the member at sorted position [k], or Int.MAX_VALUE past the end. */
        fun layerAt(k: Int): Int = if (k < layers.size) layers[k] else Int.MAX_VALUE

        /** End (exclusive) of the run of members of [layer] starting at [from]; [from] if that member has another layer. */
        fun end(from: Int, layer: Int): Int {
            var k = from
            while (k < layers.size && layers[k] == layer) k++
            return k
        }
    }

    private fun byLayer(layerOf: IntArray, members: IntArray?): ByLayer {
        val n = members?.size ?: layerOf.size
        // Key = layer in the high bits, member position in the low bits: sorting keeps the original order within a layer.
        val keys = LongArray(n) { k -> (layerOf[if (members == null) k else members[k]].toLong() shl 32) or k.toLong() }
        keys.sort()
        val order = IntArray(n)
        val layers = IntArray(n)
        for (k in 0 until n) {
            val pos = (keys[k] and 0xFFFFFFFFL).toInt()
            order[k] = if (members == null) pos else members[pos]
            layers[k] = (keys[k] ushr 32).toInt()
        }
        return ByLayer(order, layers)
    }

    private class FloatBuf(capacity: Int) {
        private var data = FloatArray(maxOf(capacity, 16))
        var size = 0
            private set

        private fun ensure(extra: Int) {
            if (size + extra > data.size) data = data.copyOf(maxOf(data.size * 2, size + extra))
        }

        fun add2(a: Float, b: Float) {
            ensure(2)
            data[size++] = a; data[size++] = b
        }

        fun add4(a: Float, b: Float, c: Float, d: Float, e: Float, f: Float, g: Float, h: Float) {
            ensure(8)
            data[size] = a; data[size + 1] = b; data[size + 2] = c; data[size + 3] = d
            data[size + 4] = e; data[size + 5] = f; data[size + 6] = g; data[size + 7] = h
            size += 8
        }

        fun toArray(): FloatArray = data.copyOf(size)
    }
}
