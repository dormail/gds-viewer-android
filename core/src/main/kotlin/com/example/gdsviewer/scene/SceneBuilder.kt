package com.example.gdsviewer.scene

/**
 * Builds the [DrawList] for one view: walks the cell hierarchy from the top cell, skips everything
 * outside the visible area (plus margin), sends instances that are too small into a [TinyRaster],
 * and collects the transforms of all visible chunks. Also makes sure the meshes of the visible
 * chunks are built (via [MeshCache.ensure]). Can run on any thread; one call at a time per instance
 * is not required (no shared mutable state besides the thread-safe index and cache).
 */
class SceneBuilder(val index: LibraryIndex, val meshes: MeshCache) {
    /** Throws java.util.concurrent.CancellationException when [isCancelled] returns true. */
    fun build(request: SceneRequest, isCancelled: () -> Boolean = { false }): DrawList = TODO()
}
