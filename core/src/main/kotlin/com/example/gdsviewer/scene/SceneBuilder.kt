package com.example.gdsviewer.scene

import com.example.gdsviewer.db.RefStore
import com.example.gdsviewer.geometry.Affine
import java.util.concurrent.CancellationException
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Builds the [DrawList] for one view: walks the cell hierarchy from the top cell, skips everything
 * outside the visible area (plus margin), sends instances that are too small into a [TinyRaster],
 * and collects the transforms of all visible chunks. Also makes sure the meshes of the visible
 * chunks are built (via [MeshCache.ensure]). Can run on any thread; one call at a time per instance
 * is not required (no shared mutable state besides the thread-safe index and cache).
 */
class SceneBuilder(val index: LibraryIndex, val meshes: MeshCache) {
    /** Throws java.util.concurrent.CancellationException when [isCancelled] returns true. */
    fun build(request: SceneRequest, isCancelled: () -> Boolean = { false }): DrawList {
        val t0 = System.nanoTime()
        if (isCancelled()) throw CancellationException("Scene build cancelled")

        val vw = request.viewportWidth.toDouble()
        val vh = request.viewportHeight.toDouble()
        val mx = request.margin * vw
        val my = request.margin * vh
        val inverse = request.view.inverse()
        val visible = Box(-mx, -my, vw + mx, vh + my).transformed(inverse)
        val anchorX = visible.centerX
        val anchorY = visible.centerY

        if (request.frame.isEmpty) {
            return DrawList(request, anchorX, anchorY, emptyList(), null, visible, SceneStats(0, 0, 0, 0))
        }

        val rp = request.rasterPixel
        val worldToRaster = Affine.scale(1 / rp) * Affine.translate(mx, my) * request.view
        val raster = SceneRaster(
            max(1, ceil((vw + 2 * mx) / rp).toInt()), max(1, ceil((vh + 2 * my) / rp).toInt()),
            worldToRaster, index.library.layers.size,
        )
        val walk = Traversal(request, visible, request.view.scale, anchorX, anchorY, raster, isCancelled)
        val root = request.frame.root
        walk.visit(request.frame.cell, root.a, root.b, root.c, root.d, root.tx, root.ty, 0)

        // Closest groups first: the renderer uploads in this order, so the middle of the screen appears first.
        val groups = walk.groups.values.sortedBy { it.minDistance }.map { it.finish() }
        val t1 = System.nanoTime()
        meshes.ensure(groups.map { it.chunk }, MESH_BYTES_PER_BUILD, isCancelled)
        val t2 = System.nanoTime()

        val stats = SceneStats(
            walk.instances, walk.tinyItems, (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000,
            walk.visits, walk.budgetExceeded,
        )
        return DrawList(request, anchorX, anchorY, groups, raster.finish(), visible, stats)
    }

    /** The transforms collected for one chunk while walking. */
    private class GroupBuilder(val chunk: Chunk) {
        var transforms = FloatArray(6 * 4)
        var count = 0
        var maxScale = 0.0
        var minDistance = Double.MAX_VALUE // squared distance from the view center, over all instances

        fun add(a: Double, b: Double, c: Double, d: Double, tx: Double, ty: Double, distance: Double) {
            if (6 * (count + 1) > transforms.size) transforms = transforms.copyOf(transforms.size * 2)
            val o = 6 * count++
            transforms[o] = a.toFloat(); transforms[o + 1] = b.toFloat(); transforms[o + 2] = c.toFloat()
            transforms[o + 3] = d.toFloat(); transforms[o + 4] = tx.toFloat(); transforms[o + 5] = ty.toFloat()
            val s = sqrt(Math.abs(a * d - b * c))
            if (s > maxScale) maxScale = s
            if (distance < minDistance) minDistance = distance
        }

        fun finish() = InstanceGroup(chunk, transforms.copyOf(6 * count), count, maxScale)
    }

    /**
     * The state of one walk over the hierarchy. Transforms are passed around as six doubles
     * (as [Affine]) instead of objects, because big layouts have millions of placements.
     */
    private inner class Traversal(
        val request: SceneRequest,
        val view: Box,
        val scale: Double,
        val anchorX: Double,
        val anchorY: Double,
        val raster: SceneRaster,
        val isCancelled: () -> Boolean,
    ) {
        val groups = HashMap<Int, GroupBuilder>()
        var instances = 0
        var tinyItems = 0L
        var visits = 0L
            private set
        var budgetExceeded = false
            private set
        // Sum of the on-screen areas (world units²) of repeated vector instances of the same chunk.
        // Every instance costs the GPU its area in pixels, so a hostile file with a million stacked
        // full-screen copies would stall it. First instances are not counted: chunk boxes of long
        // wires are mostly empty and overlap a lot, so their box areas say little about the cost.
        private var coveredArea = 0.0
        private val maxCoveredArea = MAX_OVERDRAW * view.width * view.height
        private var work = 0L // visits plus the extra cost of big raster fills and many layers: what the budget limits

        // Cells on the current path from the top cell. A real layout never places a cell inside itself;
        // a hostile file can, and each such loop would multiply the work.
        private val onPath = BooleanArray(index.library.cells.size)
        private val lod = request.lodPixels
        private val library = index.library
        private val bounds = index.bounds
        private val m = RefMatrix()

        // Result of mapBox.
        private var bx0 = 0.0; private var by0 = 0.0; private var bx1 = 0.0; private var by1 = 0.0

        /** Bounding box of (x0, y0, x1, y1) under the transform, stored in bx0... */
        private fun mapBox(
            a: Double, b: Double, c: Double, d: Double, tx: Double, ty: Double,
            x0: Double, y0: Double, x1: Double, y1: Double,
        ) {
            val ax0 = a * x0; val ax1 = a * x1; val cy0 = c * y0; val cy1 = c * y1
            bx0 = tx + minOf(ax0, ax1) + minOf(cy0, cy1)
            bx1 = tx + maxOf(ax0, ax1) + maxOf(cy0, cy1)
            val bb0 = b * x0; val bb1 = b * x1; val dy0 = d * y0; val dy1 = d * y1
            by0 = ty + minOf(bb0, bb1) + minOf(dy0, dy1)
            by1 = ty + maxOf(bb0, bb1) + maxOf(dy0, dy1)
        }

        /**
         * True once the traversal has done more than [MAX_WORK] steps. Normal files stay far below it;
         * a hostile file (e.g. arrays of arrays of arrays that all overlap) would otherwise run for years.
         */
        private fun overBudget(): Boolean {
            if (work <= MAX_WORK) return false
            budgetExceeded = true
            return true
        }

        private fun hitsView() = bx0 <= view.maxX && view.minX <= bx1 && by0 <= view.maxY && view.minY <= by1
        private fun tooSmall() = max(bx1 - bx0, by1 - by0) * scale < lod

        private fun rasterize(layers: LayerBoxes, a: Double, b: Double, c: Double, d: Double, tx: Double, ty: Double) {
            var rows = 0
            for (k in 0 until layers.size) {
                val o = 4 * k
                rows += raster.fillBox(
                    layers.layers[k], a, b, c, d, tx, ty,
                    layers.boxes[o], layers.boxes[o + 1], layers.boxes[o + 2], layers.boxes[o + 3],
                )
            }
            tinyItems++
            // Tiny items fill a row or two and count nothing extra; big boxes (instances drawn into the
            // raster because the budget or the instance limit ran out) and cells with very many layers do.
            work += (rows shr 6) + (layers.size shr 4)
        }

        /** Visits one instance of [cell] whose cell coordinates map to world by the given transform. */
        fun visit(cell: Int, a: Double, b: Double, c: Double, d: Double, tx: Double, ty: Double, depth: Int) {
            if (depth > MAX_DEPTH) return // backstop; cycles are already cut by onPath
            if (onPath[cell]) return // a reference loop
            onPath[cell] = true
            visitCell(cell, a, b, c, d, tx, ty, depth)
            onPath[cell] = false
        }

        private fun visitCell(cell: Int, a: Double, b: Double, c: Double, d: Double, tx: Double, ty: Double, depth: Int) {
            if ((visits++ and 4095L) == 0L && isCancelled()) throw CancellationException("Scene build cancelled")
            work++
            if (work > HARD_LIMIT) return // even drawing the rest into the raster would take too long
            val cb = bounds.bounds(cell) ?: return
            mapBox(a, b, c, d, tx, ty, cb.minX, cb.minY, cb.maxX, cb.maxY)
            if (!hitsView()) return
            if (depth > 0 && (tooSmall() || overBudget())) {
                rasterize(bounds.layerBounds(cell), a, b, c, d, tx, ty)
                return
            }

            val content = index.content(cell)
            for (chunk in content.chunks) addChunk(chunk, a, b, c, d, tx, ty)

            val refs = library.cells[cell].refs
            for (bucket in content.refBuckets) {
                val bb = bucket.bounds
                mapBox(a, b, c, d, tx, ty, bb.minX, bb.minY, bb.maxX, bb.maxY)
                if (!hitsView()) continue
                for (r in bucket.refs) {
                    val child = refs.cell[r]
                    if (bounds.bounds(child) == null) continue
                    m.load(refs, r)
                    if (m.cols == 1 && m.rows == 1) {
                        // T * placement, written out.
                        visit(
                            child,
                            a * m.a + c * m.b, b * m.a + d * m.b, a * m.c + c * m.d, b * m.c + d * m.d,
                            a * m.x + c * m.y + tx, b * m.x + d * m.y + ty, depth + 1,
                        )
                    } else {
                        visitArray(refs, r, child, a, b, c, d, tx, ty, depth)
                    }
                }
            }
        }

        private fun addChunk(chunk: Chunk, a: Double, b: Double, c: Double, d: Double, tx: Double, ty: Double) {
            val cb = chunk.bounds
            mapBox(a, b, c, d, tx, ty, cb.minX, cb.minY, cb.maxX, cb.maxY)
            if (!hitsView()) return
            if (tooSmall() || instances >= request.maxInstances) {
                rasterize(chunk.layerBounds, a, b, c, d, tx, ty)
                return
            }
            val group = groups.getOrPut(chunk.id) { GroupBuilder(chunk) }
            if (group.count > 0) {
                coveredArea += (minOf(bx1, view.maxX) - maxOf(bx0, view.minX)) * (minOf(by1, view.maxY) - maxOf(by0, view.minY))
                if (coveredArea > maxCoveredArea) {
                    budgetExceeded = true
                    rasterize(chunk.layerBounds, a, b, c, d, tx, ty)
                    return
                }
            }
            val cx = a * cb.centerX + c * cb.centerY + tx - anchorX
            val cy = b * cb.centerX + d * cb.centerY + ty - anchorY
            // The mesh origin is the chunk origin, so move the transform there.
            val ox = chunk.originX.toDouble(); val oy = chunk.originY.toDouble()
            group.add(a, b, c, d, a * ox + c * oy + tx - anchorX, b * ox + d * oy + ty - anchorY, cx * cx + cy * cy)
            instances++
        }

        /**
         * An array placement: ref [r] of [refs], placing [child], inside a cell whose transform to
         * world is T = (a..ty). Only the elements that can reach the view are visited.
         */
        private fun visitArray(
            refs: RefStore, r: Int, child: Int, a: Double, b: Double, c: Double, d: Double, tx: Double, ty: Double, depth: Int,
        ) {
            val am = RefMatrix() // arrays are rare enough to allocate; the shared one is reused by the recursion
            am.load(refs, r)
            val cb = bounds.bounds(child) ?: return
            // E: the child's box for element (0, 0), in parent coordinates.
            am.mapBox(cb.minX, cb.minY, cb.maxX, cb.maxY, am.x, am.y)
            val ex0 = am.minX; val ey0 = am.minY; val ex1 = am.maxX; val ey1 = am.maxY
            mapBox(a, b, c, d, tx, ty, ex0, ey0, ex1, ey1)
            val tiny = tooSmall()

            val toParent = Affine(a, b, c, d, tx, ty).inverse()
            val range = ArrayRange.visible(
                ex0, ey0, ex1, ey1, am.colX, am.colY, am.rowX, am.rowY, am.cols, am.rows, view.transformed(toParent),
            ) ?: return
            val c0 = range[0]; val c1 = range[1]; val r0 = range[2]; val r1 = range[3]
            val count = (c1 - c0 + 1).toLong() * (r1 - r0 + 1)

            if ((tiny && count > TINY_ENUMERATE_LIMIT) || count > request.maxInstances - instances || overBudget()) {
                // Too many to look at one by one: draw the box around the whole array, per layer.
                val layers = bounds.layerBounds(child)
                for (k in 0 until layers.size) {
                    var ux0 = Double.MAX_VALUE; var uy0 = Double.MAX_VALUE
                    var ux1 = -Double.MAX_VALUE; var uy1 = -Double.MAX_VALUE
                    for (corner in 0 until am.cornerCount) {
                        am.mapBox(
                            layers.boxes[4 * k], layers.boxes[4 * k + 1], layers.boxes[4 * k + 2], layers.boxes[4 * k + 3],
                            am.cornerX(corner), am.cornerY(corner),
                        )
                        ux0 = minOf(ux0, am.minX); uy0 = minOf(uy0, am.minY)
                        ux1 = maxOf(ux1, am.maxX); uy1 = maxOf(uy1, am.maxY)
                    }
                    work += raster.fillBox(layers.layers[k], a, b, c, d, tx, ty, ux0, uy0, ux1, uy1) shr 6
                }
                tinyItems++
                return
            }

            // All elements share the linear part; only the translation changes.
            val la = a * am.a + c * am.b; val lb = b * am.a + d * am.b
            val lc = a * am.c + c * am.d; val ld = b * am.c + d * am.d
            for (row in r0..r1) {
                for (col in c0..c1) {
                    val px = am.x + col * am.colX + row * am.rowX
                    val py = am.y + col * am.colY + row * am.rowY
                    visit(child, la, lb, lc, ld, a * px + c * py + tx, b * px + d * py + ty, depth + 1)
                }
            }
        }
    }

    private companion object {
        const val MAX_DEPTH = 64

        /**
         * Traversal steps per scene (cell instances looked at, plus extra for big raster fills and
         * cells with very many layers) before the remaining instances are only drawn into the raster.
         * The 157 MB standard-cell stress file needs 2.9 million at "fit to screen".
         */
        const val MAX_WORK = 6_000_000L

        /**
         * Repeated vector instances of one scene may cover at most this many times the viewed area
         * (more go into the raster). Real layouts stay at a few times; only stacked copies get near it.
         */
        const val MAX_OVERDRAW = 16.0

        /** Beyond this the walk stops altogether, so the rest of a hostile layout is left out. */
        const val HARD_LIMIT = 2 * MAX_WORK

        /** Arrays of tiny elements with more visible elements than this are drawn as one block instead of element by element. */
        const val TINY_ENUMERATE_LIMIT = 20_000
        const val MESH_BYTES_PER_BUILD = 48L shl 20
    }
}
