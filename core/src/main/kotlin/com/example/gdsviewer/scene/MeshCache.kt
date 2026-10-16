package com.example.gdsviewer.scene

import com.example.gdsviewer.db.Library

/**
 * Builds [ChunkMesh]es on demand and keeps them until the renderer has uploaded them to the GPU
 * (then the CPU copy is dropped; [isUploaded] remembers it is on the GPU). Cached meshes that are
 * not uploaded are evicted least-recently-used beyond [budgetBytes]. Thread-safe.
 */
class MeshCache(val library: Library, val budgetBytes: Long = 128L shl 20) {
    /** The mesh of [chunk], building it if it is not cached (may take a while for big chunks). */
    fun get(chunk: Chunk): ChunkMesh = TODO()

    /** The cached mesh of [chunk], or null. Never builds. */
    fun peek(chunk: Chunk): ChunkMesh? = TODO()

    /** Builds the meshes of all [chunks] that are neither cached nor uploaded. Stops early when [isCancelled] returns true. */
    fun ensure(chunks: Collection<Chunk>, isCancelled: () -> Boolean = { false }): Unit = TODO()

    /** Called by the renderer after uploading: drops the CPU copy and remembers that the chunk is on the GPU. */
    fun markUploaded(chunk: Chunk): Unit = TODO()

    /** Called by the renderer when it deleted the GPU copy of [chunk]. */
    fun markEvicted(chunk: Chunk): Unit = TODO()

    /** Called by the renderer when all GPU data is gone (e.g. a new OpenGL context). */
    fun markAllEvicted(): Unit = TODO()

    fun isUploaded(chunk: Chunk): Boolean = TODO()
}
