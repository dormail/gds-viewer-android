package com.example.gdsviewer.scene

import com.example.gdsviewer.assertEquals
import com.example.gdsviewer.assertNotNull
import com.example.gdsviewer.assertNull
import com.example.gdsviewer.assertSame
import com.example.gdsviewer.assertTrue
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Test

class MeshCacheTest {
    /** [cells] cells with 1000 rects each; one chunk per cell; one mesh = 4 * (8000 + 6000 + 8000) = 88000 bytes. */
    private fun setup(cells: Int, budget: Long): Triple<MeshCache, List<Chunk>, LibraryIndex> {
        val l = lib {
            for (k in 0 until cells) {
                val c = beginCell("C$k")
                for (i in 0 until 1000) c.addBox(layerIndex(1, 0), i * 10, 0, i * 10 + 5, 5)
            }
        }
        val idx = LibraryIndex(l)
        return Triple(MeshCache(l, budget), (0 until cells).map { idx.content(it).chunks.single() }, idx)
    }

    @Test fun getCachesAndPeekNeverBuilds() {
        val (cache, chunks, _) = setup(2, 1L shl 30)
        assertNull(cache.peek(chunks[0]))
        val m = cache.get(chunks[0])
        assertSame(m, cache.get(chunks[0]))
        assertSame(m, cache.peek(chunks[0]))
        assertNull(cache.peek(chunks[1]))
        assertEquals(88000L, m.byteSize)
    }

    @Test fun budgetEvictsLeastRecentlyUsed() {
        val (cache, chunks, _) = setup(4, 2 * 88000L + 10)
        cache.get(chunks[0]); cache.get(chunks[1])
        cache.get(chunks[0]) // touch: chunk 1 is now the oldest
        cache.get(chunks[2])
        assertNotNull(cache.peek(chunks[0])); assertNull(cache.peek(chunks[1])); assertNotNull(cache.peek(chunks[2]))
        cache.get(chunks[3])
        assertNull(cache.peek(chunks[0])); assertNotNull(cache.peek(chunks[2])); assertNotNull(cache.peek(chunks[3]))
    }

    @Test fun oversizedMeshIsStillReturnedAndKept() {
        val (cache, chunks, _) = setup(1, 10)
        val m = cache.get(chunks[0])
        assertSame(m, cache.peek(chunks[0]))
    }

    @Test fun markUploadedDropsCpuCopyAndFreesBudget() {
        val (cache, chunks, _) = setup(3, 2 * 88000L + 10)
        cache.get(chunks[0]); cache.get(chunks[1])
        cache.markUploaded(chunks[0])
        assertNull(cache.peek(chunks[0]))
        assertTrue(cache.isUploaded(chunks[0])); assertTrue(!cache.isUploaded(chunks[1]))
        cache.get(chunks[2]) // fits because chunk 0's bytes were released
        assertNotNull(cache.peek(chunks[1])); assertNotNull(cache.peek(chunks[2]))
        cache.markEvicted(chunks[0])
        assertTrue(!cache.isUploaded(chunks[0]))
        cache.markUploaded(chunks[1]); cache.markUploaded(chunks[2])
        cache.markAllEvicted()
        assertTrue(chunks.none { cache.isUploaded(it) })
    }

    @Test fun ensureBuildsMissingAndSkipsUploaded() {
        val (cache, chunks, _) = setup(6, 1L shl 30)
        cache.get(chunks[0])
        val before = cache.peek(chunks[0])
        cache.markUploaded(chunks[1])
        cache.ensure(chunks)
        assertSame(before, cache.peek(chunks[0]))
        assertNull(cache.peek(chunks[1]))
        for (k in 2 until 6) assertNotNull(cache.peek(chunks[k]))
    }

    @Test fun ensureStopsWhenCancelled() {
        val (cache, chunks, _) = setup(6, 1L shl 30)
        cache.ensure(chunks) { true }
        assertTrue(chunks.all { cache.peek(it) == null })
        val calls = AtomicInteger()
        cache.ensure(chunks) { calls.incrementAndGet() > 2 }
        assertTrue(chunks.count { cache.peek(it) != null } < 6)
    }

    @Test fun concurrencySmokeTest() {
        val (cache, chunks, _) = setup(12, 3 * 88000L)
        val pool = Executors.newFixedThreadPool(8)
        val tasks = (0 until 400).map { n ->
            Callable {
                when (n % 4) {
                    0 -> cache.get(chunks[n % 12]).vertexCount
                    1 -> { cache.markUploaded(chunks[n % 12]); 0 }
                    2 -> { cache.markEvicted(chunks[(n + 5) % 12]); 0 }
                    else -> { cache.ensure(chunks); cache.peek(chunks[0])?.vertexCount ?: 0 }
                }
            }
        }
        pool.invokeAll(tasks).forEach { it.get() }
        pool.shutdown()
        for (c in chunks) assertEquals(4000, cache.get(c).vertexCount)
    }
}
