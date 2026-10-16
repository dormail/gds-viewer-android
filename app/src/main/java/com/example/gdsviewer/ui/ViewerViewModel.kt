package com.example.gdsviewer.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.gdsviewer.gds.GdsLibrary
import com.example.gdsviewer.gds.GdsParser
import com.example.gdsviewer.gds.LayerKey
import com.example.gdsviewer.geometry.Affine
import com.example.gdsviewer.geometry.LayoutFlattener
import com.example.gdsviewer.geometry.LayoutGeometry
import com.example.gdsviewer.geometry.Snapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** What the main screen is currently showing. */
sealed interface ViewerState {
    data object Empty : ViewerState
    data class Loading(val message: String) : ViewerState
    data class Failed(val message: String) : ViewerState
    data object Ready : ViewerState
}

/** One layer as shown in the UI. [color] is ARGB. */
data class LayerUi(val key: LayerKey, val color: Int, val visible: Boolean, val shapeCount: Long)

/** A measuring line between two points, in world coordinates (µm). */
data class Ruler(val x1: Double, val y1: Double, val x2: Double, val y2: Double)

/**
 * Holds everything the screen shows: the loaded layout, layer settings, the camera ([view]) and
 * the ruler. A ViewModel survives screen rotation, so the layout does not reload.
 * State uses Compose `mutableStateOf`, so the UI updates automatically when values change.
 */
class ViewerViewModel(application: Application) : AndroidViewModel(application) {
    var state: ViewerState by mutableStateOf(ViewerState.Empty); private set
    var fileName: String? by mutableStateOf(null); private set
    var library: GdsLibrary? by mutableStateOf(null); private set
    var geometry: LayoutGeometry? by mutableStateOf(null); private set
    var layers: List<LayerUi> by mutableStateOf(emptyList()); private set

    /** Maps world coordinates (µm, y up) to screen pixels (y down). */
    var view: Affine by mutableStateOf(Affine.IDENTITY); private set
    var rulerMode by mutableStateOf(false); private set
    var ruler: Ruler? by mutableStateOf(null); private set
    var rotationLocked by mutableStateOf(false); private set
    var snapEnabled by mutableStateOf(true); private set

    private var loadJob: Job? = null
    private var viewportW = 0
    private var viewportH = 0
    private var pendingFit = false

    // Ruler dragging: which endpoints move with the finger (1 or 2), or both for a new ruler.
    private var dragNew = false
    private var dragEnd = 0 // 0 = none, 1 = first point, 2 = second point

    // ---------------------------------------------------------------- loading

    fun openUri(uri: Uri) {
        val resolver = getApplication<Application>().contentResolver
        val name = try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (e: Exception) {
            null
        } ?: uri.lastPathSegment ?: "layout"
        load(name) { resolver.openInputStream(uri) ?: throw java.io.IOException("Cannot open the file.") }
    }

    fun openSample() {
        val assets = getApplication<Application>().assets
        load("sample.gds") { assets.open("sample.gds") }
    }

    private fun load(name: String, open: () -> java.io.InputStream) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                state = ViewerState.Loading("Reading $name…")
                val lib = withContext(Dispatchers.IO) { open().use { GdsParser.parse(it) } }
                if (lib.cells.isEmpty()) {
                    state = ViewerState.Failed("This file contains no cells.")
                    return@launch
                }
                val cell = withContext(Dispatchers.Default) { LayoutFlattener(lib).defaultCell() }
                if (cell == null) {
                    state = ViewerState.Failed("This file contains no cells.")
                    return@launch
                }
                buildCell(lib, cell.name, name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                state = ViewerState.Failed("Not enough memory to open this file.")
            } catch (e: Exception) {
                state = ViewerState.Failed(e.message ?: "Could not open this file.")
            }
        }
    }

    /** Flattens [cellName] and publishes the result. Must run inside the load job's coroutine. */
    private suspend fun buildCell(lib: GdsLibrary, cellName: String, name: String) {
        state = ViewerState.Loading("Building $cellName…")
        val geo = withContext(Dispatchers.Default) {
            LayoutFlattener(lib, isCancelled = { !isActive }).flatten(cellName)
        }
        val previous = layers.associate { it.key to it.visible }
        library = lib
        fileName = name
        geometry = geo
        layers = geo.layers.map { lg ->
            val index = lib.layers.indexOf(lg.key).coerceAtLeast(0)
            LayerUi(lg.key, PALETTE[index % PALETTE.size], previous[lg.key] ?: true, lg.shapeCount)
        }
        ruler = null
        dragEnd = 0
        fitToView()
        state = ViewerState.Ready
    }

    fun selectCell(name: String) {
        val lib = library ?: return
        val file = fileName ?: return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                buildCell(lib, name, file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                state = ViewerState.Failed("Not enough memory to open this cell.")
            } catch (e: Exception) {
                state = ViewerState.Failed(e.message ?: "Could not build this cell.")
            }
        }
    }

    // ----------------------------------------------------------------- layers

    fun toggleLayer(key: LayerKey) {
        layers = layers.map { if (it.key == key) it.copy(visible = !it.visible) else it }
    }

    fun soloLayer(key: LayerKey) {
        layers = layers.map { it.copy(visible = it.key == key) }
    }

    fun setAllLayersVisible(visible: Boolean) {
        layers = layers.map { it.copy(visible = visible) }
    }

    // ------------------------------------------------------------------- view

    /** Called whenever the size of the drawing area (in pixels) changes. */
    fun onViewportChanged(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val firstTime = viewportW == 0
        val oldW = viewportW
        val oldH = viewportH
        viewportW = w
        viewportH = h
        if (firstTime || pendingFit) {
            fitToView()
        } else {
            // Keep whatever is at the screen center at the center.
            view = Affine.translate((w - oldW) / 2.0, (h - oldH) / 2.0) * view
        }
    }

    fun fitToView() {
        val geo = geometry
        if (geo == null || viewportW == 0) {
            pendingFit = true
            return
        }
        pendingFit = false
        val bw = max(geo.maxX - geo.minX, 1e-3)
        val bh = max(geo.maxY - geo.minY, 1e-3)
        val s = 0.9 * min(viewportW / bw, viewportH / bh)
        val cx = (geo.minX + geo.maxX) / 2
        val cy = (geo.minY + geo.maxY) / 2
        view = Affine.translate(viewportW / 2.0, viewportH / 2.0) * Affine.scale(s, -s) * Affine.translate(-cx, -cy)
    }

    /**
     * Applies one frame of a pinch/pan/rotate gesture around the screen point (cx, cy):
     * pan in pixels, zoom as a factor, rotation in degrees (clockwise on screen).
     */
    fun onTransform(cx: Double, cy: Double, panX: Double, panY: Double, zoom: Double, rotationDeg: Double) {
        val geo = geometry ?: return
        val current = view.scale
        // Zoom limits: between 5% of the "fit" scale and 500 px per database unit.
        val fitScale = 0.9 * min(
            viewportW / max(geo.maxX - geo.minX, 1e-3),
            viewportH / max(geo.maxY - geo.minY, 1e-3),
        )
        val minScale = 0.05 * fitScale
        val maxScale = 500.0 / geo.dbUnitMicrons
        val z = (current * zoom).coerceIn(min(minScale, maxScale), maxScale) / current
        val rot = if (rotationLocked) 0.0 else rotationDeg
        view = Affine.translate(cx, cy) * Affine.rotate(rot) * Affine.scale(z) *
            Affine.translate(-cx + panX, -cy + panY) * view
    }

    fun resetRotation() {
        val cx = viewportW / 2.0
        val cy = viewportH / 2.0
        view = Affine.translate(cx, cy) * Affine.rotate(-view.rotationDegrees) * Affine.translate(-cx, -cy) * view
    }

    fun toggleRotationLock() {
        rotationLocked = !rotationLocked
    }

    // ------------------------------------------------------------------ ruler

    @JvmName("changeRulerMode")
    fun setRulerMode(on: Boolean) {
        rulerMode = on
        if (!on) {
            ruler = null
            dragEnd = 0
        }
    }

    fun toggleSnap() {
        snapEnabled = !snapEnabled
    }

    private fun toWorld(x: Float, y: Float): DoubleArray {
        val inv = view.inverse()
        return doubleArrayOf(inv.mapX(x.toDouble(), y.toDouble()), inv.mapY(x.toDouble(), y.toDouble()))
    }

    private fun screenDistance(wx: Double, wy: Double, x: Float, y: Float): Double =
        hypot(view.mapX(wx, wy) - x, view.mapY(wx, wy) - y)

    fun rulerDown(x: Float, y: Float, grabRadiusPx: Float) {
        val r = ruler
        dragNew = false
        dragEnd = 0
        if (r != null) {
            val d1 = screenDistance(r.x1, r.y1, x, y)
            val d2 = screenDistance(r.x2, r.y2, x, y)
            if (min(d1, d2) <= grabRadiusPx) {
                dragEnd = if (d1 <= d2) 1 else 2
                return
            }
        }
        val w = toWorld(x, y)
        ruler = Ruler(w[0], w[1], w[0], w[1])
        dragNew = true
        dragEnd = 2
    }

    fun rulerMove(x: Float, y: Float) {
        val r = ruler ?: return
        if (dragEnd == 0) return
        val w = toWorld(x, y)
        ruler = if (dragEnd == 1) r.copy(x1 = w[0], y1 = w[1]) else r.copy(x2 = w[0], y2 = w[1])
    }

    /** A second finger touched down: drop a half-made ruler and stop dragging. */
    fun rulerCancel() {
        if (dragNew) ruler = null
        dragNew = false
        dragEnd = 0
    }

    fun rulerUp(snapRadiusPx: Float) {
        val r = ruler
        val wasNew = dragNew
        val end = dragEnd
        dragNew = false
        dragEnd = 0
        if (r == null || end == 0) return
        if (wasNew && hypot(view.mapX(r.x2, r.y2) - view.mapX(r.x1, r.y1), view.mapY(r.x2, r.y2) - view.mapY(r.x1, r.y1)) < 8) {
            ruler = null // a tap clears the ruler
            return
        }
        val geo = geometry ?: return
        if (!snapEnabled) return
        val visible = layers.filter { it.visible }.map { it.key }.toSet()
        val radius = snapRadiusPx / view.scale
        viewModelScope.launch {
            val p1 = if (wasNew || end == 1) {
                withContext(Dispatchers.Default) { Snapper.snap(geo, visible, r.x1, r.y1, radius) }
            } else null
            val p2 = if (wasNew || end == 2) {
                withContext(Dispatchers.Default) { Snapper.snap(geo, visible, r.x2, r.y2, radius) }
            } else null
            if (ruler === r) {
                ruler = Ruler(p1?.get(0) ?: r.x1, p1?.get(1) ?: r.y1, p2?.get(0) ?: r.x2, p2?.get(1) ?: r.y2)
            }
        }
    }

    private companion object {
        val PALETTE = intArrayOf(
            0xFF4FC3F7.toInt(), 0xFFEF5350.toInt(), 0xFF66BB6A.toInt(), 0xFFFFCA28.toInt(),
            0xFFAB47BC.toInt(), 0xFFFF7043.toInt(), 0xFF26C6DA.toInt(), 0xFFD4E157.toInt(),
            0xFFEC407A.toInt(), 0xFF8D6E63.toInt(), 0xFF5C6BC0.toInt(), 0xFF9CCC65.toInt(),
            0xFFFFA726.toInt(), 0xFF7E57C2.toInt(), 0xFF29B6F6.toInt(), 0xFFBDBDBD.toInt(),
        )
    }
}
