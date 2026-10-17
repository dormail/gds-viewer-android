package io.github.dormail.gdsviewer.geometry

import kotlin.math.max

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
