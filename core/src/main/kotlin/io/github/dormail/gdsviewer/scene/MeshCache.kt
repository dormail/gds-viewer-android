package io.github.dormail.gdsviewer.scene

import io.github.dormail.gdsviewer.db.Library
import java.util.concurrent.atomic.AtomicLong

/**
 * Builds [ChunkMesh]es on demand and keeps them until the renderer has uploaded them to the GPU
 * (then the CPU copy is dropped; [isUploaded] remembers it is on the GPU). Cached meshes that are
 * not uploaded are evicted least-recently-used beyond [budgetBytes]. Thread-safe.
 */
class MeshCache(val library: Library, val budgetBytes: Long = 128L shl 20) {
    private val lock = Any()

    // Access-ordered: iteration starts with the least recently used mesh. Keyed by chunk id.
    private val cache = LinkedHashMap<Int, ChunkMesh>(64, 0.75f, true)
    private var cachedBytes = 0L
    private val uploaded = HashSet<Int>()

    /** The mesh of [chunk], building it if it is not cached (may take a while for big chunks). */
    fun get(chunk: Chunk): ChunkMesh {
        synchronized(lock) { cache[chunk.id] }?.let { return it }
        val mesh = MeshBuilder.build(library, chunk) // outside the lock: other threads keep working
        synchronized(lock) {
            cache[chunk.id]?.let { return it } // another thread was faster
            cache[chunk.id] = mesh
            cachedBytes += mesh.byteSize
            evict()
        }
        return mesh
    }

    /** The cached mesh of [chunk], or null. Never builds. */
    fun peek(chunk: Chunk): ChunkMesh? = synchronized(lock) { cache[chunk.id] }

    /**
     * Builds the meshes of [chunks] that are neither cached nor uploaded, on several cores. Stops
     * early when [isCancelled] returns true, or once about [maxBytes] of new meshes were built (so
     * a huge view can be streamed to the GPU in batches without overflowing the cache). Returns
     * true if all [chunks] are now cached or uploaded.
     */
    fun ensure(chunks: Collection<Chunk>, maxBytes: Long = Long.MAX_VALUE, isCancelled: () -> Boolean = { false }): Boolean {
        val todo = synchronized(lock) { chunks.filter { it.id !in cache && it.id !in uploaded } }
        if (todo.isEmpty()) return true
        val built = AtomicLong()
        todo.parallelStream().forEach { chunk ->
            if (!isCancelled() && built.get() < maxBytes) built.addAndGet(get(chunk).byteSize)
        }
        return synchronized(lock) { chunks.all { it.id in cache || it.id in uploaded } }
    }

    /** Called by the renderer after uploading: drops the CPU copy and remembers that the chunk is on the GPU. */
    fun markUploaded(chunk: Chunk) {
        synchronized(lock) {
            cache.remove(chunk.id)?.let { cachedBytes -= it.byteSize }
            uploaded += chunk.id
        }
    }

    /** Called by the renderer when it deleted the GPU copy of [chunk]. */
    fun markEvicted(chunk: Chunk) {
        synchronized(lock) { uploaded -= chunk.id }
    }

    /** Called by the renderer when all GPU data is gone (e.g. a new OpenGL context). */
    fun markAllEvicted() {
        synchronized(lock) { uploaded.clear() }
    }

    fun isUploaded(chunk: Chunk): Boolean = synchronized(lock) { chunk.id in uploaded }

    /** Drops least recently used meshes until the budget holds; always keeps the newest one. Call with the lock held. */
    private fun evict() {
        if (cachedBytes <= budgetBytes) return
        val it = cache.entries.iterator()
        while (cachedBytes > budgetBytes && cache.size > 1 && it.hasNext()) {
            val e = it.next()
            cachedBytes -= e.value.byteSize
            it.remove()
        }
    }
}
