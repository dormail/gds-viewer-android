package com.example.gdsviewer

import org.junit.Assert

/** Small assertion helpers on top of JUnit 4 (this module has no kotlin-test dependency). */
fun assertEquals(expected: Any?, actual: Any?) = Assert.assertEquals(expected, actual)

fun assertEquals(expected: Double, actual: Double, delta: Double) = Assert.assertEquals(expected, actual, delta)

fun assertTrue(condition: Boolean, message: String? = null) = Assert.assertTrue(message, condition)

fun assertSame(expected: Any?, actual: Any?) = Assert.assertSame(expected, actual)

fun assertNull(actual: Any?) = Assert.assertNull(actual)

fun <T : Any> assertNotNull(actual: T?): T {
    Assert.assertNotNull(actual)
    return actual!!
}

inline fun <reified T : Throwable> assertFailsWith(block: () -> Unit): T {
    try {
        block()
    } catch (e: Throwable) {
        if (e is T) return e
        throw e
    }
    throw AssertionError("Expected ${T::class.java.simpleName} but nothing was thrown")
}
