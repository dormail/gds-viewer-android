package com.example.gdsviewer.scene

import com.example.gdsviewer.db.Library
import com.example.gdsviewer.geometry.DoubleList
import com.example.gdsviewer.geometry.IntList
import com.example.gdsviewer.geometry.PathGeometry
import com.example.gdsviewer.geometry.Triangulator

/** Turns the shapes of a [Chunk] into a [ChunkMesh] (fill triangles + outline segments per layer). */
object MeshBuilder {
    fun build(library: Library, chunk: Chunk): ChunkMesh {
        val cell = library.cells[chunk.cell]
        val layerCount = library.layers.size

        // Sort the members by layer (stable counting sort), once per kind of shape.
        val rects = byLayer(cell.rects.layer, chunk.rects, layerCount)
        val polygons = byLayer(cell.polygons.layer, chunk.polygons, layerCount)
        val paths = byLayer(cell.paths.layer, chunk.paths, layerCount)

        val rectCount = rects.order.size
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

        for (layer in 0 until layerCount) {
            val r0 = rects.offsets[layer]; val r1 = rects.offsets[layer + 1]
            val p0 = polygons.offsets[layer]; val p1 = polygons.offsets[layer + 1]
            val w0 = paths.offsets[layer]; val w1 = paths.offsets[layer + 1]
            if (r0 == r1 && p0 == p1 && w0 == w1) continue
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
        }
        triangleStart += triangles.size
        lineStart += lines.size
        return ChunkMesh(
            chunk, vertices.toArray(), triangles.toArray(), lines.toArray(),
            layerList.toIntArray(), triangleStart.toIntArray(), lineStart.toIntArray(),
        )
    }

    /** Member indices of one kind of shape sorted by layer; layer l occupies order[offsets[l] until offsets[l + 1]]. */
    private class ByLayer(val order: IntArray, val offsets: IntArray)

    private fun byLayer(layerOf: IntArray, members: IntArray?, layerCount: Int): ByLayer {
        val n = members?.size ?: layerOf.size
        val offsets = IntArray(layerCount + 1)
        for (k in 0 until n) offsets[layerOf[if (members == null) k else members[k]] + 1]++
        for (l in 0 until layerCount) offsets[l + 1] += offsets[l]
        val fill = offsets.copyOf()
        val order = IntArray(n)
        for (k in 0 until n) {
            val i = if (members == null) k else members[k]
            order[fill[layerOf[i]]++] = i
        }
        return ByLayer(order, offsets)
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
