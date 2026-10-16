package com.example.gdsviewer.render

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import com.example.gdsviewer.geometry.Affine
import com.example.gdsviewer.geometry.FloatChunks
import com.example.gdsviewer.geometry.LayoutGeometry
import com.example.gdsviewer.gds.LayerKey
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.opengles.GL10

/** How one layer is drawn: [color] is ARGB; styles are given bottom layer first. */
class LayerStyle(val key: LayerKey, val color: Int, val visible: Boolean)

/** Vertex buffers of one chunk of floats on the GPU. */
private class GpuBuffer(val id: Int, val vertexCount: Int)

/** GPU buffers of one layer: filled triangles and outline lines. */
private class GpuLayer(val fill: List<GpuBuffer>, val outline: List<GpuBuffer>)

/**
 * Draws a [LayoutGeometry] with OpenGL ES 2.0. All callbacks run on the GL thread; the public
 * fields are written by the UI thread (hence @Volatile) and read at the start of each frame.
 */
class LayoutRenderer : GLSurfaceView.Renderer {
    @Volatile var geometry: LayoutGeometry? = null
    @Volatile var styles: List<LayerStyle> = emptyList()
    /** World (µm, y up) to screen pixels (y down). */
    @Volatile var view: Affine = Affine.IDENTITY

    /** Canvas color (ARGB) and opacity of shape fills; set from the UI thread, read by the GL thread. */
    @Volatile var background = 0xFF101014.toInt()
    @Volatile var fillAlpha = 0.4f

    private var program = 0
    private var matrixLocation = 0
    private var colorLocation = 0
    private var positionLocation = 0
    private var lineWidth = 1f
    private var width = 1
    private var height = 1

    private var uploaded: LayoutGeometry? = null
    private val gpuLayers = HashMap<LayerKey, GpuLayer>()
    private val matrix = FloatArray(16)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        matrixLocation = GLES20.glGetUniformLocation(program, "uMatrix")
        colorLocation = GLES20.glGetUniformLocation(program, "uColor")
        positionLocation = GLES20.glGetAttribLocation(program, "aPosition")

        val range = FloatArray(2)
        GLES20.glGetFloatv(GLES20.GL_ALIASED_LINE_WIDTH_RANGE, range, 0)
        lineWidth = minOf(2f, range[1]).coerceAtLeast(1f)

        // A new GL context has none of our old buffers: forget them (their ids are invalid now).
        gpuLayers.clear()
        uploaded = null
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = maxOf(w, 1)
        height = maxOf(h, 1)
        GLES20.glViewport(0, 0, w, h)
    }

    override fun onDrawFrame(gl: GL10?) {
        val geo = geometry
        if (geo !== uploaded) upload(geo)

        GLES20.glClearColor(
            ((background shr 16) and 0xFF) / 255f,
            ((background shr 8) and 0xFF) / 255f,
            (background and 0xFF) / 255f,
            1f,
        )
        GLES20.glClearStencil(0)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_STENCIL_BUFFER_BIT)
        if (geo == null) return

        GLES20.glUseProgram(program)
        fillMatrix(view)
        GLES20.glUniformMatrix4fv(matrixLocation, 1, false, matrix, 0)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glEnableVertexAttribArray(positionLocation)
        GLES20.glLineWidth(lineWidth)

        var stencilRef = 0
        for (style in styles) {
            if (!style.visible) continue
            val layer = gpuLayers[style.key] ?: continue
            stencilRef++
            if (stencilRef > 255) {
                GLES20.glClear(GLES20.GL_STENCIL_BUFFER_BIT)
                stencilRef = 1
            }
            // Fill: the stencil makes each pixel get blended only once per layer, even where
            // shapes of the same layer overlap.
            GLES20.glEnable(GLES20.GL_STENCIL_TEST)
            GLES20.glStencilFunc(GLES20.GL_NOTEQUAL, stencilRef, 0xFF)
            GLES20.glStencilOp(GLES20.GL_KEEP, GLES20.GL_KEEP, GLES20.GL_REPLACE)
            setColor(style.color, fillAlpha)
            draw(layer.fill, GLES20.GL_TRIANGLES)

            GLES20.glDisable(GLES20.GL_STENCIL_TEST)
            setColor(style.color, 1f)
            draw(layer.outline, GLES20.GL_LINES)
        }
        GLES20.glDisableVertexAttribArray(positionLocation)
    }

    private fun setColor(argb: Int, alpha: Float) {
        GLES20.glUniform4f(
            colorLocation,
            ((argb shr 16) and 0xFF) / 255f,
            ((argb shr 8) and 0xFF) / 255f,
            (argb and 0xFF) / 255f,
            alpha,
        )
    }

    private fun draw(buffers: List<GpuBuffer>, mode: Int) {
        for (b in buffers) {
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, b.id)
            GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false, 0, 0)
            GLES20.glDrawArrays(mode, 0, b.vertexCount)
        }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    /** Builds the world-to-clip-space matrix (column-major) from the view and surface size. */
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

    /** Replaces the GPU data with that of [geo]. */
    private fun upload(geo: LayoutGeometry?) {
        for (layer in gpuLayers.values) {
            deleteBuffers(layer.fill)
            deleteBuffers(layer.outline)
        }
        gpuLayers.clear()
        if (geo != null) {
            for (layer in geo.layers) {
                gpuLayers[layer.key] = GpuLayer(uploadChunks(layer.triangles), uploadChunks(layer.lines))
            }
        }
        uploaded = geo
    }

    private fun uploadChunks(chunks: FloatChunks): List<GpuBuffer> {
        val result = ArrayList<GpuBuffer>()
        for (chunk in chunks.chunks) {
            val data = chunk.duplicate()
            data.flip() // limit = number of valid floats (the position of the original)
            val floats = data.limit()
            if (floats == 0) continue
            val ids = IntArray(1)
            GLES20.glGenBuffers(1, ids, 0)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, ids[0])
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, floats * 4, data, GLES20.GL_STATIC_DRAW)
            result += GpuBuffer(ids[0], floats / 2)
        }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        return result
    }

    private fun deleteBuffers(buffers: List<GpuBuffer>) {
        if (buffers.isEmpty()) return
        GLES20.glDeleteBuffers(buffers.size, IntArray(buffers.size) { buffers[it].id }, 0)
    }

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

    /** Picks an EGL config with a stencil buffer, preferring 4x multisampling (smooth edges). */
    class ConfigChooser : GLSurfaceView.EGLConfigChooser {
        override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
            val base = intArrayOf(
                EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_STENCIL_SIZE, 8,
                EGL10.EGL_RENDERABLE_TYPE, 4, // EGL_OPENGL_ES2_BIT
            )
            val multisample = intArrayOf(EGL10.EGL_SAMPLE_BUFFERS, 1, EGL10.EGL_SAMPLES, 4)
            val attempts = listOf(
                base + multisample + EGL10.EGL_NONE,
                base + EGL10.EGL_NONE,
                intArrayOf(EGL10.EGL_RENDERABLE_TYPE, 4, EGL10.EGL_NONE),
            )
            for (attrs in attempts) {
                val configs = arrayOfNulls<EGLConfig>(1)
                val count = IntArray(1)
                if (egl.eglChooseConfig(display, attrs, configs, 1, count) && count[0] > 0) {
                    configs[0]?.let { return it }
                }
            }
            throw IllegalStateException("No suitable OpenGL ES 2.0 configuration found")
        }
    }

    private companion object {
        const val VERTEX_SHADER =
            "uniform mat4 uMatrix; attribute vec2 aPosition; " +
                "void main(){ gl_Position = uMatrix * vec4(aPosition, 0.0, 1.0); }"
        const val FRAGMENT_SHADER =
            "precision mediump float; uniform vec4 uColor; void main(){ gl_FragColor = uColor; }"
    }
}
