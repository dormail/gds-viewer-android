package io.github.dormail.gdsviewer.render

import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import android.util.SparseArray
import io.github.dormail.gdsviewer.geometry.Affine
import io.github.dormail.gdsviewer.scene.Chunk
import io.github.dormail.gdsviewer.scene.ChunkMesh
import io.github.dormail.gdsviewer.scene.DrawList
import io.github.dormail.gdsviewer.scene.MeshCache
import io.github.dormail.gdsviewer.scene.TinyRaster
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.opengles.GL10

/** How one layer is drawn (by layer index into Library.layers); [color] is ARGB. Styles are given bottom layer first. */
class SceneLayerStyle(val layer: Int, val color: Int, val visible: Boolean)

/** The GPU copy of one [ChunkMesh]: a vertex buffer and one element buffer (triangle indices, then line indices). */
private class GpuMesh(
    val chunk: Chunk,
    val vbo: Int,
    val ebo: Int,
    /** GL_UNSIGNED_SHORT or GL_UNSIGNED_INT. */
    val indexType: Int,
    val indexSize: Int,
    /** Index (not byte) position of the first line index in the element buffer. */
    val lineBase: Int,
    val layers: IntArray,
    val triangleStart: IntArray,
    val lineStart: IntArray,
    val bytes: Long,
) {
    /** Frame number in which the mesh was last drawn (for least-recently-used eviction). */
    var lastUsed = 0L

    /** Serial of the draw list that last needed this mesh; evicting meshes of the current list is forbidden. */
    var listSerial = 0
}

/** One instanced draw call (triangles and/or lines) of one instance group for one layer. */
private class DrawItem(
    val mesh: GpuMesh,
    /** Byte offset of the group's transforms in the instance buffer. */
    val instanceOffset: Int,
    val instanceCount: Int,
    val triangleOffset: Int,
    val triangleCount: Int,
    val lineOffset: Int,
    val lineCount: Int,
    /** Typical shape size on screen is this times the view scale (px): decides whether outlines are drawn. */
    val shapeSize: Double,
)

/**
 * Draws a [DrawList] with OpenGL ES 3.0 using GPU instancing: every chunk mesh is uploaded once and
 * drawn once per group with all its placement transforms. All callbacks run on the GL thread; the
 * public fields are written by the UI thread (hence @Volatile) and read at the start of each frame.
 */
class SceneRenderer : GLSurfaceView.Renderer {
    @Volatile var drawList: DrawList? = null

    /** The mesh source. When this instance changes (new file), all GPU data is deleted. */
    @Volatile var meshes: MeshCache? = null
    @Volatile var styles: List<SceneLayerStyle> = emptyList()

    /** World (µm, y up) to screen pixels (y down). */
    @Volatile var view: Affine = Affine.IDENTITY
    @Volatile var background: Int = 0xFF101014.toInt()
    @Volatile var fillAlpha: Float = 0.4f

    /**
     * Called on the GL thread with chunks of the current draw list whose mesh is neither on the GPU nor
     * in the cache, closest to the screen center first. The app builds (some of) them in the background,
     * then calls [meshesReady] and requestRender. Only one request is outstanding at a time; chunks
     * that are still missing are reported again after [meshesReady], so big views stream in batches.
     */
    @Volatile var onMissingMeshes: ((List<Chunk>) -> Unit)? = null

    /** Set while the app is building meshes we asked for; see [onMissingMeshes]. */
    @Volatile private var missingRequested = false

    /** Tells the renderer that the meshes requested via [onMissingMeshes] are built (or the attempt ended). Any thread. */
    fun meshesReady() {
        missingRequested = false
    }

    /** Called on the GL thread when uploads are still pending, so the host should call GLSurfaceView.requestRender(). */
    @Volatile var requestRender: (() -> Unit)? = null

    /** When more mesh data than this is on the GPU, meshes not needed by the current draw list are deleted. */
    var gpuBudgetBytes: Long = 384L shl 20

    // --- GL objects (all invalid after the context is lost) ---
    private var meshProgram = 0
    private var meshMatrixLocation = 0
    private var meshColorLocation = 0
    private var rasterProgram = 0
    private var rasterMatrixLocation = 0
    private var rasterColorLocation = 0
    private var rasterTextureLocation = 0
    private var instanceBuffer = 0
    private var instanceBufferBytes = 0
    private var quadBuffer = 0
    private var lineWidth = 1f
    private var width = 1
    private var height = 1

    // --- resident meshes ---
    private var ownerCache: MeshCache? = null
    private val resident = SparseArray<GpuMesh>() // by Chunk.id
    private var residentBytes = 0L

    // --- state of the prepared draw list ---
    private var preparedList: DrawList? = null
    private var listSerial = 0
    private var instanceOffsets = IntArray(0) // byte offset of each group in the instance buffer
    private var unresolved = IntArray(0) // indices of groups whose mesh is not on the GPU yet
    private var unresolvedCount = 0
    private var layerItemsDirty = false
    private var logPending = false
    private var uploadsPending = 0

    /** Draw calls per layer index (null: nothing resident for that layer). */
    private var layerItems = arrayOfNulls<ArrayList<DrawItem>>(0)

    // --- tiny raster ---
    private var rasterLayers = IntArray(0)
    private var rasterTextures = IntArray(0)

    private val matrix = FloatArray(16)
    private val idArray = IntArray(2)
    private var staging: ByteBuffer = ByteBuffer.allocateDirect(1 shl 20).order(ByteOrder.nativeOrder())
    private var frame = 0L

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        meshProgram = buildProgram(MESH_VERTEX_SHADER, MESH_FRAGMENT_SHADER)
        meshMatrixLocation = GLES20.glGetUniformLocation(meshProgram, "uMatrix")
        meshColorLocation = GLES20.glGetUniformLocation(meshProgram, "uColor")
        rasterProgram = buildProgram(RASTER_VERTEX_SHADER, RASTER_FRAGMENT_SHADER)
        rasterMatrixLocation = GLES20.glGetUniformLocation(rasterProgram, "uMatrix")
        rasterColorLocation = GLES20.glGetUniformLocation(rasterProgram, "uColor")
        rasterTextureLocation = GLES20.glGetUniformLocation(rasterProgram, "uMask")

        val range = FloatArray(2)
        GLES20.glGetFloatv(GLES20.GL_ALIASED_LINE_WIDTH_RANGE, range, 0)
        lineWidth = minOf(2f, range[1]).coerceAtLeast(1f)

        // A new GL context has none of our old objects: forget them (their ids are invalid now).
        resident.clear()
        residentBytes = 0
        instanceBuffer = 0
        instanceBufferBytes = 0
        quadBuffer = 0
        rasterLayers = IntArray(0)
        rasterTextures = IntArray(0)
        layerItems = arrayOfNulls(0)
        preparedList = null
        ownerCache = meshes
        meshes?.markAllEvicted()
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = maxOf(w, 1)
        height = maxOf(h, 1)
        GLES20.glViewport(0, 0, w, h)
    }

    override fun onDrawFrame(gl: GL10?) {
        frame++
        val list = drawList
        val cache = meshes
        if (cache !== ownerCache) {
            // A different file: nothing of the old GPU data is useful (the old cache is dropped by the app).
            deleteAllMeshes()
            ownerCache = cache
            preparedList = null
            // This renderer has uploaded nothing from the cache yet (it may come from an earlier
            // renderer, e.g. before a screen rotation): make the cache forget what was uploaded.
            cache?.markAllEvicted()
        }
        if (list !== preparedList) prepare(list)
        if (list != null && cache != null) {
            resolveMeshes(list, cache)
            if (layerItemsDirty) rebuildLayerItems(list)
            evictIfOverBudget(cache)
        }

        GLES20.glClearColor(
            ((background shr 16) and 0xFF) / 255f,
            ((background shr 8) and 0xFF) / 255f,
            (background and 0xFF) / 255f,
            1f,
        )
        GLES20.glClearStencil(0)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_STENCIL_BUFFER_BIT)
        if (list != null) draw(list)

        if (logPending && list != null) {
            logPending = false
            Log.i(
                TAG,
                "draw list: ${list.groups.size} groups, ${list.stats.instances} instances, " +
                    "${residentBytes shr 20} MB resident, $uploadsPending+$unresolvedCount uploads/missing pending, " +
                    "${rasterLayers.size} raster layers",
            )
        }
        if (uploadsPending > 0) requestRender?.invoke()
    }

    // ---------------------------------------------------------------- drawing

    private fun draw(list: DrawList) {
        val fillAlpha = fillAlpha
        val viewScale = view.scale
        // world -> anchor-relative world -> screen. Combined in Double so zoomed-in views stay precise.
        fillMatrix(view * Affine.translate(list.anchorX, list.anchorY))

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glLineWidth(lineWidth)

        var stencilRef = 0
        for (style in styles) {
            if (!style.visible) continue
            val items = layerItems.getOrNull(style.layer)
            val texture = rasterTextureOf(style.layer)
            if (items.isNullOrEmpty() && texture == 0) continue

            stencilRef++
            if (stencilRef > 255) {
                GLES20.glClear(GLES20.GL_STENCIL_BUFFER_BIT)
                stencilRef = 1
            }
            // Fill: the stencil makes each pixel get blended only once per layer, even where shapes
            // (or shapes and the tiny raster) of the same layer overlap.
            GLES20.glEnable(GLES20.GL_STENCIL_TEST)
            GLES20.glStencilFunc(GLES20.GL_NOTEQUAL, stencilRef, 0xFF)
            GLES20.glStencilOp(GLES20.GL_KEEP, GLES20.GL_KEEP, GLES20.GL_REPLACE)

            if (!items.isNullOrEmpty()) {
                beginMeshDrawing(style.color, fillAlpha)
                for (item in items) {
                    if (item.triangleCount == 0) continue
                    item.mesh.lastUsed = frame
                    drawItem(item, GLES20.GL_TRIANGLES, item.triangleOffset, item.triangleCount)
                }
            }
            if (texture != 0) drawRaster(texture, style.color, fillAlpha)

            // Outlines at full alpha, no stencil. Skipped when shapes are too small to see an outline.
            GLES20.glDisable(GLES20.GL_STENCIL_TEST)
            if (!items.isNullOrEmpty()) {
                var begun = false
                for (item in items) {
                    if (item.lineCount == 0 || item.shapeSize * viewScale < MIN_OUTLINE_PIXELS) continue
                    if (!begun) {
                        beginMeshDrawing(style.color, 1f)
                        begun = true
                    }
                    item.mesh.lastUsed = frame
                    drawItem(item, GLES20.GL_LINES, item.lineOffset, item.lineCount)
                }
            }
        }
        GLES20.glDisable(GLES20.GL_STENCIL_TEST)
        endMeshDrawing()
    }

    /** Selects the instancing program and its color; the matrix is the same for the whole frame. */
    private fun beginMeshDrawing(argb: Int, alpha: Float) {
        GLES20.glUseProgram(meshProgram)
        GLES20.glUniformMatrix4fv(meshMatrixLocation, 1, false, matrix, 0)
        setColor(meshColorLocation, argb, alpha)
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glEnableVertexAttribArray(1)
        GLES20.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribDivisor(0, 0)
        GLES30.glVertexAttribDivisor(1, 1)
        GLES30.glVertexAttribDivisor(2, 1)
    }

    /** One instanced draw call: [count] indices starting at byte [offset] of the mesh's element buffer. */
    private fun drawItem(item: DrawItem, mode: Int, offset: Int, count: Int) {
        val mesh = item.mesh
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mesh.vbo)
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, 0)
        // The group's transforms: (a, b, c, d) then (tx, ty), 24 bytes per instance.
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, instanceBuffer)
        GLES20.glVertexAttribPointer(1, 4, GLES20.GL_FLOAT, false, INSTANCE_STRIDE, item.instanceOffset)
        GLES20.glVertexAttribPointer(2, 2, GLES20.GL_FLOAT, false, INSTANCE_STRIDE, item.instanceOffset + 16)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, mesh.ebo)
        GLES30.glDrawElementsInstanced(mode, count, mesh.indexType, offset, item.instanceCount)
    }

    /** Draws the quad of one raster layer's bitmap (not instanced, so the instance divisors are reset). */
    private fun drawRaster(texture: Int, argb: Int, alpha: Float) {
        endMeshDrawing()
        GLES20.glUseProgram(rasterProgram)
        GLES20.glUniformMatrix4fv(rasterMatrixLocation, 1, false, matrix, 0)
        setColor(rasterColorLocation, argb, alpha)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glUniform1i(rasterTextureLocation, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadBuffer)
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glEnableVertexAttribArray(1)
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 16, 0)
        GLES20.glVertexAttribPointer(1, 2, GLES20.GL_FLOAT, false, 16, 8)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(1)
        GLES20.glDisableVertexAttribArray(0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    /** Resets the attribute state that the instancing program changes, so other programs see defaults. */
    private fun endMeshDrawing() {
        GLES30.glVertexAttribDivisor(1, 0)
        GLES30.glVertexAttribDivisor(2, 0)
        GLES20.glDisableVertexAttribArray(0)
        GLES20.glDisableVertexAttribArray(1)
        GLES20.glDisableVertexAttribArray(2)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    private fun setColor(location: Int, argb: Int, alpha: Float) {
        GLES20.glUniform4f(
            location,
            ((argb shr 16) and 0xFF) / 255f,
            ((argb shr 8) and 0xFF) / 255f,
            (argb and 0xFF) / 255f,
            alpha,
        )
    }

    private fun rasterTextureOf(layer: Int): Int {
        for (i in rasterLayers.indices) if (rasterLayers[i] == layer) return rasterTextures[i]
        return 0
    }

    /** Builds the world-to-clip-space matrix (column-major) from a world-to-screen transform and the surface size. */
    private fun fillMatrix(v: Affine) {
        val w = width.toDouble()
        val h = height.toDouble()
        matrix.fill(0f)
        matrix[0] = (2 * v.a / w).toFloat()
        matrix[1] = (-2 * v.b / h).toFloat()
        matrix[4] = (2 * v.c / w).toFloat()
        matrix[5] = (-2 * v.d / h).toFloat()
        matrix[10] = 1f
        matrix[12] = (2 * v.tx / w - 1).toFloat()
        matrix[13] = (1 - 2 * v.ty / h).toFloat()
        matrix[15] = 1f
    }

    // ---------------------------------------------------------------- new draw list

    /** Uploads the instance transforms and raster of [list] and queues all its meshes for residency checks. */
    private fun prepare(list: DrawList?) {
        preparedList = list
        listSerial++
        deleteRasterTextures()
        unresolvedCount = 0
        uploadsPending = 0
        layerItemsDirty = true
        logPending = list != null
        missingRequested = false
        if (list == null) return

        uploadInstances(list)
        list.raster?.let { uploadRaster(list, it) }

        val groups = list.groups
        if (unresolved.size < groups.size) unresolved = IntArray(groups.size)
        for (i in groups.indices) {
            if (resident.get(groups[i].chunk.id) == null) unresolved[unresolvedCount++] = i
        }
    }

    /** Copies all groups' transforms, back to back, into the instance buffer (grown when needed). */
    private fun uploadInstances(list: DrawList) {
        val groups = list.groups
        if (instanceOffsets.size < groups.size) instanceOffsets = IntArray(groups.size)
        var total = 0
        for (i in groups.indices) {
            instanceOffsets[i] = total
            total += groups[i].count * INSTANCE_STRIDE
        }
        if (instanceBuffer == 0) instanceBuffer = newBuffer()
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, instanceBuffer)
        if (total > instanceBufferBytes) {
            // Over-allocate a bit so slightly bigger lists do not reallocate every time.
            instanceBufferBytes = maxOf(total, instanceBufferBytes + instanceBufferBytes / 2)
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, instanceBufferBytes, null, GLES20.GL_DYNAMIC_DRAW)
        }
        for (i in groups.indices) {
            val g = groups[i]
            if (g.count == 0) continue
            val bytes = g.count * INSTANCE_STRIDE
            val floats = stagingFloats(bytes)
            floats.put(g.transforms, 0, g.count * 6)
            GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, instanceOffsets[i], bytes, staging)
        }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    /** One R8 texture per raster layer (0 or 255 per pixel) plus the quad's corners in anchor-relative world coordinates. */
    private fun uploadRaster(list: DrawList, raster: TinyRaster) {
        val w = raster.width
        val h = raster.height
        if (w <= 0 || h <= 0 || raster.layers.isEmpty()) return
        val count = raster.layers.size
        val textures = IntArray(count)
        GLES20.glGenTextures(count, textures, 0)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        for (k in 0 until count) {
            val bits = raster.bits[k]
            val pixels = stagingBytes(w * h)
            for (i in 0 until w * h) {
                val set = bits[i ushr 6] and (1L shl (i and 63)) != 0L
                pixels.put(i, if (set) 255.toByte() else 0.toByte())
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[k])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES30.GL_R8, w, h, 0,
                GLES30.GL_RED, GLES20.GL_UNSIGNED_BYTE, pixels,
            )
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        rasterLayers = raster.layers.copyOf()
        rasterTextures = textures

        // Quad corners: raster pixels -> world, minus the anchor so the frame's matrix applies.
        val toWorld = raster.worldToRaster.inverse()
        val quad = FloatArray(16)
        for (corner in 0 until 4) {
            val u = (corner and 1).toDouble()
            val v = (corner shr 1).toDouble()
            val px = u * w
            val py = v * h
            quad[corner * 4] = (toWorld.mapX(px, py) - list.anchorX).toFloat()
            quad[corner * 4 + 1] = (toWorld.mapY(px, py) - list.anchorY).toFloat()
            quad[corner * 4 + 2] = u.toFloat()
            quad[corner * 4 + 3] = v.toFloat()
        }
        if (quadBuffer == 0) quadBuffer = newBuffer()
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadBuffer)
        stagingFloats(64).put(quad)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, 64, staging, GLES20.GL_DYNAMIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    private fun deleteRasterTextures() {
        if (rasterTextures.isNotEmpty()) GLES20.glDeleteTextures(rasterTextures.size, rasterTextures, 0)
        rasterTextures = IntArray(0)
        rasterLayers = IntArray(0)
    }

    // ---------------------------------------------------------------- mesh residency

    /**
     * Uploads meshes of the current list that the cache has ready (at most [UPLOAD_BYTES_PER_FRAME] per
     * frame) and reports the ones that do not exist yet. Groups still unresolved are looked at again next frame.
     */
    private fun resolveMeshes(list: DrawList, cache: MeshCache) {
        if (unresolvedCount == 0) return
        val groups = list.groups
        var uploadedBytes = 0L
        var kept = 0
        uploadsPending = 0
        val report = !missingRequested
        var missing: ArrayList<Chunk>? = null
        for (n in 0 until unresolvedCount) {
            val index = unresolved[n]
            val chunk = groups[index].chunk
            if (resident.get(chunk.id) != null) continue // another group uploaded the same chunk
            val mesh = cache.peek(chunk)
            if (mesh == null) {
                unresolved[kept++] = index
                if (report) (missing ?: ArrayList<Chunk>().also { missing = it }).add(chunk)
            } else if (uploadedBytes >= UPLOAD_BYTES_PER_FRAME) {
                unresolved[kept++] = index
                uploadsPending++
            } else {
                uploadedBytes += uploadMesh(mesh)
                cache.markUploaded(chunk)
                layerItemsDirty = true
            }
        }
        unresolvedCount = kept
        missing?.let {
            missingRequested = true
            onMissingMeshes?.invoke(it)
        }
    }

    /** Creates the GPU buffers of [mesh]; returns its size in bytes. */
    private fun uploadMesh(mesh: ChunkMesh): Long {
        val chunk = mesh.chunk
        val vertexFloats = mesh.vertices.size
        val triangleCount = mesh.triangles.size
        val lineCount = mesh.lines.size
        val useShorts = mesh.vertexCount <= 65536
        val indexSize = if (useShorts) 2 else 4
        var vbo = 0
        var ebo = 0

        // A mesh with a bad index is never uploaded (it then draws nothing); see indicesInRange.
        if (vertexFloats > 0 && triangleCount + lineCount > 0 && mesh.indicesInRange()) {
            vbo = newBuffer()
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
            val floats = stagingFloats(vertexFloats * 4)
            floats.put(mesh.vertices, 0, vertexFloats)
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, vertexFloats * 4, staging, GLES20.GL_STATIC_DRAW)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)

            val indexBytes = (triangleCount + lineCount) * indexSize
            ebo = newBuffer()
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ebo)
            stagingBytes(indexBytes)
            if (useShorts) {
                val shorts = staging.asShortBuffer()
                for (i in 0 until triangleCount) shorts.put(i, mesh.triangles[i].toShort())
                for (i in 0 until lineCount) shorts.put(triangleCount + i, mesh.lines[i].toShort())
            } else {
                val ints = staging.asIntBuffer()
                ints.put(mesh.triangles, 0, triangleCount)
                ints.position(triangleCount)
                ints.put(mesh.lines, 0, lineCount)
            }
            GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, indexBytes, staging, GLES20.GL_STATIC_DRAW)
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
        }

        val bytes = vertexFloats * 4L + (triangleCount + lineCount) * indexSize.toLong()
        val gpu = GpuMesh(
            chunk, vbo, ebo,
            if (useShorts) GLES20.GL_UNSIGNED_SHORT else GLES20.GL_UNSIGNED_INT,
            indexSize, triangleCount, mesh.layers, mesh.triangleStart, mesh.lineStart, bytes,
        )
        gpu.lastUsed = frame
        resident.put(chunk.id, gpu)
        residentBytes += bytes
        return bytes
    }

    /** Recomputes the per-layer draw calls from the groups whose meshes are resident. Not done every frame. */
    private fun rebuildLayerItems(list: DrawList) {
        layerItemsDirty = false
        for (items in layerItems) items?.clear()
        val groups = list.groups
        for (g in groups.indices) {
            val group = groups[g]
            val mesh = resident.get(group.chunk.id) ?: continue
            mesh.listSerial = listSerial
            if (group.count == 0 || mesh.vbo == 0) continue
            val shapeSize = group.chunk.typicalShapeSize * group.maxScale
            for (k in mesh.layers.indices) {
                val triFirst = mesh.triangleStart[k]
                val triCount = mesh.triangleStart[k + 1] - triFirst
                val lineFirst = mesh.lineStart[k]
                val lineCount = mesh.lineStart[k + 1] - lineFirst
                if (triCount <= 0 && lineCount <= 0) continue
                val item = DrawItem(
                    mesh, instanceOffsets[g], group.count,
                    triFirst * mesh.indexSize, maxOf(triCount, 0),
                    (mesh.lineBase + lineFirst) * mesh.indexSize, maxOf(lineCount, 0),
                    shapeSize,
                )
                itemsOfLayer(mesh.layers[k]).add(item)
            }
        }
    }

    private fun itemsOfLayer(layer: Int): ArrayList<DrawItem> {
        if (layer >= layerItems.size) layerItems = layerItems.copyOf(maxOf(layer + 1, layerItems.size * 2))
        return layerItems[layer] ?: ArrayList<DrawItem>().also { layerItems[layer] = it }
    }

    /** Deletes the least recently drawn meshes that the current list does not use until the budget is met. */
    private fun evictIfOverBudget(cache: MeshCache) {
        if (residentBytes <= gpuBudgetBytes) return
        val candidates = ArrayList<GpuMesh>()
        for (i in 0 until resident.size()) {
            val mesh = resident.valueAt(i)
            if (mesh.listSerial != listSerial) candidates.add(mesh)
        }
        candidates.sortBy { it.lastUsed }
        for (mesh in candidates) {
            if (residentBytes <= gpuBudgetBytes) break
            deleteMesh(mesh)
            resident.remove(mesh.chunk.id)
            cache.markEvicted(mesh.chunk)
        }
    }

    /**
     * Deletes all GPU objects. Call on the GL thread (GLSurfaceView.queueEvent) before the view goes
     * away; drawing again afterwards needs a new surface (onSurfaceCreated).
     */
    fun release() {
        deleteAllMeshes()
        ownerCache?.markAllEvicted()
        ownerCache = null
        preparedList = null
        deleteRasterTextures()
        idArray[0] = instanceBuffer
        idArray[1] = quadBuffer
        GLES20.glDeleteBuffers(2, idArray, 0)
        instanceBuffer = 0
        instanceBufferBytes = 0
        quadBuffer = 0
        GLES20.glDeleteProgram(meshProgram)
        GLES20.glDeleteProgram(rasterProgram)
        meshProgram = 0
        rasterProgram = 0
    }

    private fun deleteAllMeshes() {
        for (i in 0 until resident.size()) deleteMesh(resident.valueAt(i))
        resident.clear()
        residentBytes = 0
        for (items in layerItems) items?.clear()
        unresolvedCount = 0
    }

    private fun deleteMesh(mesh: GpuMesh) {
        residentBytes -= mesh.bytes
        if (mesh.vbo != 0) {
            idArray[0] = mesh.vbo
            idArray[1] = mesh.ebo
            GLES20.glDeleteBuffers(2, idArray, 0)
        }
        // The item lists may still point to it until the next rebuild (evicted meshes are not in the current list).
    }

    // ---------------------------------------------------------------- helpers

    private fun newBuffer(): Int {
        GLES20.glGenBuffers(1, idArray, 0)
        return idArray[0]
    }

    /** The reusable staging buffer, cleared, with limit [bytes]; grows when needed. */
    private fun stagingBytes(bytes: Int): ByteBuffer {
        if (staging.capacity() < bytes) {
            staging = ByteBuffer.allocateDirect(maxOf(bytes, staging.capacity() * 2)).order(ByteOrder.nativeOrder())
        }
        staging.clear()
        staging.limit(bytes)
        return staging
    }

    /** Like [stagingBytes], returning a float view (position 0) onto it. The byte buffer's own limit is [bytes]. */
    private fun stagingFloats(bytes: Int) = stagingBytes(bytes).asFloatBuffer()

    private fun buildProgram(vertex: String, fragment: String): Int {
        val vs = compile(GLES20.GL_VERTEX_SHADER, vertex)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragment)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) throw RuntimeException("Program link failed: " + GLES20.glGetProgramInfoLog(program))
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        return program
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) throw RuntimeException("Shader compile failed: " + GLES20.glGetShaderInfoLog(shader))
        return shader
    }

    /** Picks an OpenGL ES 3 EGL config with a stencil buffer, preferring 4x multisampling (smooth edges). */
    class ConfigChooser : GLSurfaceView.EGLConfigChooser {
        override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
            val base = intArrayOf(
                EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_STENCIL_SIZE, 8,
                EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
            )
            val multisample = intArrayOf(EGL10.EGL_SAMPLE_BUFFERS, 1, EGL10.EGL_SAMPLES, 4)
            val attempts = listOf(
                base + multisample + EGL10.EGL_NONE,
                base + EGL10.EGL_NONE,
                intArrayOf(EGL10.EGL_STENCIL_SIZE, 8, EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL10.EGL_NONE),
                intArrayOf(EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL10.EGL_NONE),
            )
            for (attrs in attempts) {
                val configs = arrayOfNulls<EGLConfig>(1)
                val count = IntArray(1)
                if (egl.eglChooseConfig(display, attrs, configs, 1, count) && count[0] > 0) {
                    configs[0]?.let { return it }
                }
            }
            throw IllegalStateException("No suitable OpenGL ES 3.0 configuration found")
        }

        private companion object {
            const val EGL_OPENGL_ES3_BIT = 0x40 // EGL_OPENGL_ES3_BIT_KHR
        }
    }

    private companion object {
        const val TAG = "SceneRenderer"

        /** Bytes per instance in the instance buffer: a, b, c, d, tx, ty as floats. */
        const val INSTANCE_STRIDE = 24

        /** Mesh data uploaded to the GPU per frame at most (so the UI stays smooth while loading). */
        const val UPLOAD_BYTES_PER_FRAME = 16L shl 20

        /** Outlines of shapes smaller than this on screen are not drawn. */
        const val MIN_OUTLINE_PIXELS = 3.0

        // Mesh vertices are transformed per instance: world = (a x + c y + tx, b x + d y + ty).
        const val MESH_VERTEX_SHADER = """#version 300 es
layout(location = 0) in vec2 aPos;
layout(location = 1) in vec4 aLin;
layout(location = 2) in vec2 aTrans;
uniform mat4 uMatrix;
void main() {
    vec2 world = vec2(aLin.x * aPos.x + aLin.z * aPos.y + aTrans.x,
                      aLin.y * aPos.x + aLin.w * aPos.y + aTrans.y);
    gl_Position = uMatrix * vec4(world, 0.0, 1.0);
}
"""
        const val MESH_FRAGMENT_SHADER = """#version 300 es
precision mediump float;
uniform vec4 uColor;
out vec4 fragColor;
void main() { fragColor = uColor; }
"""
        const val RASTER_VERTEX_SHADER = """#version 300 es
layout(location = 0) in vec2 aPos;
layout(location = 1) in vec2 aUv;
uniform mat4 uMatrix;
out vec2 vUv;
void main() {
    vUv = aUv;
    gl_Position = uMatrix * vec4(aPos, 0.0, 1.0);
}
"""
        const val RASTER_FRAGMENT_SHADER = """#version 300 es
precision mediump float;
uniform vec4 uColor;
uniform sampler2D uMask;
in vec2 vUv;
out vec4 fragColor;
void main() {
    if (texture(uMask, vUv).r < 0.5) discard;
    fragColor = uColor;
}
"""
    }
}
