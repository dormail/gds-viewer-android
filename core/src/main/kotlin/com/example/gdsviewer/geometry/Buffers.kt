package com.example.gdsviewer.geometry

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

/** A growable list of (x, y) coordinate pairs stored as doubles. */
class DoubleList(capacity: Int = 64) {
    var data = DoubleArray(max(capacity, 2))
        private set

    /** Number of doubles stored (twice the number of points). */
    var size = 0
        private set

    fun add(x: Double, y: Double) {
        if (size + 2 > data.size) data = data.copyOf(max(data.size * 2, size + 2))
        data[size++] = x
        data[size++] = y
    }

    fun clear() {
        size = 0
    }
}

/**
 * A growable list of floats kept in native ("direct") memory chunks, ready for uploading to the
 * GPU. Native memory does not count against the (small) Java heap limit of Android apps.
 *
 * Floats are written in groups: call [reserve] with the group size before writing it with [put]
 * (a triangle is 6 floats, a line segment 4 floats). A group never spans two chunks, so each chunk
 * can be drawn on its own. The number of valid floats in a chunk is its `position()`.
 */
class FloatChunks {
    private val list = ArrayList<FloatBuffer>()
    private var current: FloatBuffer? = null

    /** The chunks written so far. Read them with absolute `get(i)` for i < `position()`. */
    val chunks: List<FloatBuffer> get() = list

    /** Total number of floats written. */
    var size = 0L
        private set

    fun reserve(count: Int) {
        val cur = current
        if (cur == null || cur.remaining() < count) {
            val capacity = if (cur == null) FIRST_CHUNK else min(MAX_CHUNK, cur.capacity() * 2)
            val buffer = ByteBuffer.allocateDirect(capacity * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            list += buffer
            current = buffer
        }
    }

    fun put(value: Float) {
        current!!.put(value)
        size++
    }

    private companion object {
        // Multiples of 12, so both 6-float triangles and 4-float lines fill a chunk exactly.
        const val FIRST_CHUNK = 12 * 512
        const val MAX_CHUNK = 12 * 131072
    }
}
