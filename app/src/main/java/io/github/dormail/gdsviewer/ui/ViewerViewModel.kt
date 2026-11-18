package io.github.dormail.gdsviewer.ui

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.dormail.gdsviewer.data.RecentFile
import io.github.dormail.gdsviewer.data.ThemeMode
import io.github.dormail.gdsviewer.ui.theme.LayerPalette
import io.github.dormail.gdsviewer.data.RecentFiles
import io.github.dormail.gdsviewer.db.LayoutReader
import io.github.dormail.gdsviewer.db.Library
import io.github.dormail.gdsviewer.db.LibraryMerge
import io.github.dormail.gdsviewer.gds.LayerKey
import io.github.dormail.gdsviewer.geometry.Affine
import io.github.dormail.gdsviewer.scene.Box
import io.github.dormail.gdsviewer.scene.CellFrame
import io.github.dormail.gdsviewer.scene.Chunk
import io.github.dormail.gdsviewer.scene.DrawList
import io.github.dormail.gdsviewer.scene.LayerStats
import io.github.dormail.gdsviewer.scene.LibraryIndex
import io.github.dormail.gdsviewer.scene.MeshCache
import io.github.dormail.gdsviewer.scene.SceneBuilder
import io.github.dormail.gdsviewer.scene.SceneRequest
import io.github.dormail.gdsviewer.scene.Snapping
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/** What the main screen is currently showing. */
sealed interface ViewerState {
    data object Empty : ViewerState
    data class Loading(val message: String) : ViewerState
    data class Failed(val message: String) : ViewerState
    data object Ready : ViewerState
}

/**
 * One layer as shown in the UI. [layer] is its index in [Library.layers]; its color is
 * [io.github.dormail.gdsviewer.ui.theme.LayerPalette] entry [paletteIndex].
 */
data class LayerUi(val key: LayerKey, val layer: Int, val paletteIndex: Int, val visible: Boolean, val shapeCount: Long)

/**
 * One file that is open. [file] is its number ("@2" in layer names); it stays the same while other
 * files are added or removed. [topCell] is the cell of [library] that is shown.
 */
class OpenFile(val name: String, val library: Library, val topCell: Int, val file: Int)

/** A measuring line between two points, in world coordinates (µm). */
data class Ruler(val x1: Double, val y1: Double, val x2: Double, val y2: Double)

/** Where a file came from; decides what [ViewerViewModel] remembers after it loaded. */
private sealed interface Source {
    /** The bundled sample: never added to the recent files. */
    data object Sample : Source

    /** A file chosen by the user (picker, "Open with", "Share"): added to the recent files. */
    data class External(val uri: Uri) : Source

    /** An entry of the recent files: it is only moved to the top, not copied again. */
    data class Recent(val id: String) : Source
}

/**
 * Holds everything the screen shows: the loaded layout, layer settings, the camera ([view]) and
 * the ruler. A ViewModel survives screen rotation, so the layout does not reload.
 * State uses Compose `mutableStateOf`, so the UI updates automatically when values change.
 */
class ViewerViewModel(application: Application) : AndroidViewModel(application) {
    var state: ViewerState by mutableStateOf(ViewerState.Empty); private set
    /** All open files. One entry is the normal case; more than one means they are shown on top of each other. */
    var files: List<OpenFile> by mutableStateOf(emptyList()); private set
    /** What the top bar shows: the file name, or the first name plus "+1", "+2"... with several files. */
    val fileName: String?
        get() = when (files.size) {
            0 -> null
            1 -> files[0].name
            else -> "${files[0].name} +${files.size - 1}"
        }
    /** True when several files are shown on top of each other. */
    val isOverlay: Boolean get() = files.size > 1
    /** Progress text while a file is being added (the current view stays visible), else null. */
    var addProgress: String? by mutableStateOf(null); private set
    /** A short message for a Snackbar, such as "Could not add this file". The screen clears it with [messageShown]. */
    var message: String? by mutableStateOf(null); private set
    fun messageShown() { message = null }
    var library: Library? by mutableStateOf(null); private set
    /** The cell being shown and its coordinate frame, or null before a file has loaded. */
    var frame: CellFrame? by mutableStateOf(null); private set
    var layers: List<LayerUi> by mutableStateOf(emptyList()); private set

    /** What the renderer draws for the current view; rebuilt in the background as the view changes. */
    var drawList: DrawList? by mutableStateOf(null); private set
    /** Meshes of the loaded file, shared with the renderer (which uploads them to the GPU). */
    var meshes: MeshCache? by mutableStateOf(null); private set
    /** True while building the draw list for a new view takes noticeably long (shown as a progress bar). */
    var sceneSlow by mutableStateOf(false); private set

    private var index: LibraryIndex? = null
    private var sceneBuilder: SceneBuilder? = null
    private var sceneJob: Job? = null
    private var sceneAgain = false // the view changed again while a draw list was being built

    private val recents = RecentFiles(application)

    /** The saved theme choice (System default / Light / Dark). */
    var themeMode: ThemeMode by mutableStateOf(ThemeMode.load(application)); private set

    fun chooseTheme(mode: ThemeMode) {
        themeMode = mode
        ThemeMode.save(getApplication(), mode)
    }

    /** Recently opened files, newest first. */
    val recentFiles: List<RecentFile> get() = recents.entries

    private var viewState by mutableStateOf(Affine.IDENTITY)

    /** Maps world coordinates (µm, y up) to screen pixels (y down). Changing it may start a new draw list. */
    var view: Affine
        get() = viewState
        private set(value) {
            viewState = value
            requestScene()
        }
    var rulerMode by mutableStateOf(false); private set
    var ruler: Ruler? by mutableStateOf(null); private set
    var rotationLocked by mutableStateOf(false); private set
    var snapEnabled by mutableStateOf(true); private set

    // Rotation snapping (see onTransform). The view starts "held" at 0 degrees.
    private var rotationHeld = true // true = view is held at a snap angle
    private var pendingRotation = 0.0 // twist collected while held, in degrees
    private var snapArmed = false // after breaking out, snapping only works once we moved away

    private var loadJob: Job? = null
    private var viewportW = 0
    private var viewportH = 0
    private var pendingFit = false

    // Ruler dragging: which endpoints move with the finger (1 or 2), or both for a new ruler.
    private var dragNew = false
    private var dragEnd = 0 // 0 = none, 1 = first point, 2 = second point

    // ---------------------------------------------------------------- loading

    private fun displayName(uri: Uri): String {
        val resolver = getApplication<Application>().contentResolver
        return try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (e: Exception) {
            null
        } ?: uri.lastPathSegment ?: "layout"
    }

    fun openUri(uri: Uri) {
        val resolver = getApplication<Application>().contentResolver
        val name = displayName(uri)
        load(name, Source.External(uri)) {
            resolver.openInputStream(uri) ?: throw java.io.IOException("Cannot open the file.")
        }
    }

    /** Reopens an entry of [recentFiles]. If its file is gone, shows an error and drops the entry. */
    fun openRecent(id: String) {
        val entry = recents.find(id) ?: return
        load(entry.name, Source.Recent(id)) {
            try {
                recents.openStream(entry)
            } catch (e: java.io.IOException) { // includes FileNotFoundException
                gone(id)
            } catch (e: SecurityException) { // access permission was revoked
                gone(id)
            }
        }
    }

    private fun gone(id: String): Nothing {
        recents.remove(id) // runs on the IO thread inside load()
        throw java.io.IOException("This file is no longer available. Open it again from your files.")
    }

    fun removeRecent(id: String) {
        viewModelScope.launch(Dispatchers.IO) { recents.remove(id) }
    }

    fun openSample() {
        val assets = getApplication<Application>().assets
        load("sample.gds", Source.Sample) { assets.open("sample.gds") }
    }

    private fun load(name: String, source: Source, open: () -> java.io.InputStream) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                // Let go of the previous file first: big files need all the memory they can get.
                unloadFile()
                state = ViewerState.Loading("Reading $name…")
                val start = SystemClock.elapsedRealtime()
                val lib = withContext(Dispatchers.IO) {
                    open().use { input ->
                        LayoutReader.read(input, isCancelled = { !isActive }) { bytes ->
                            state = ViewerState.Loading("Reading $name… ${bytes shr 20} MB")
                        }
                    }
                }
                if (lib.cells.isEmpty()) {
                    state = ViewerState.Failed("This file contains no cells.")
                    return@launch
                }
                val readDone = SystemClock.elapsedRealtime()
                state = ViewerState.Loading("Indexing $name…")
                val idx = withContext(Dispatchers.Default) { LibraryIndex(lib) }
                Log.i(
                    TAG,
                    "read in ${readDone - start} ms, indexed in ${SystemClock.elapsedRealtime() - readDone} ms; " +
                        "${lib.cells.size} cells, ${lib.layers.size} layers",
                )
                val cell = idx.defaultTopCell()
                if (cell == null) {
                    state = ViewerState.Failed("This file contains no cells.")
                    return@launch
                }
                val cache = MeshCache(lib)
                library = lib
                index = idx
                meshes = cache
                sceneBuilder = SceneBuilder(idx, cache)
                files = listOf(OpenFile(name, lib, cell.index, 1))
                buildCell(cell.index)
                // Remember the file only now that it has loaded successfully.
                withContext(Dispatchers.IO) {
                    when (source) {
                        Source.Sample -> Unit
                        is Source.External -> recents.record(source.uri, name)
                        is Source.Recent -> recents.touch(source.id)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                state = ViewerState.Failed("Not enough memory to open this file.")
            } catch (e: Exception) {
                state = ViewerState.Failed(e.message ?: "Could not open this file.")
            }
        }
    }

    /** Forgets the loaded file (before loading another one). */
    private fun unloadFile() {
        sceneJob?.cancel()
        library = null
        files = emptyList()
        index = null
        meshes = null
        sceneBuilder = null
        frame = null
        drawList = null
        ruler = null
        dragEnd = 0
    }

    /** Shows [cell] of the loaded file. Must run inside the load job's coroutine. */
    private suspend fun buildCell(cell: Int) {
        val lib = library ?: return
        val idx = index ?: return
        val name = lib.cells[cell].name
        state = ViewerState.Loading("Preparing $name…")
        val counts = withContext(Dispatchers.Default) { LayerStats.shapeCounts(lib, cell) }
        showCell(cell, counts, layers.associate { it.key to it.visible }, keepView = false)
    }

    /**
     * Switches to [cell] of the already assigned [library]/[index]. [previous] says which layers
     * were visible (by key; layers not in it are visible). With [keepView] the zoom and position stay.
     * Does no slow work, so it cannot fail halfway.
     */
    private fun showCell(cell: Int, counts: LongArray, previous: Map<LayerKey, Boolean>, keepView: Boolean) {
        val lib = library ?: return
        val idx = index ?: return
        sceneJob?.cancel()
        drawList = null
        frame = idx.frame(cell)
        layers = counts.indices.filter { counts[it] > 0 }.map { i ->
            val key = lib.layers[i]
            LayerUi(key, i, i % LayerPalette.size, previous[key] ?: true, counts[i])
        }
        ruler = null
        dragEnd = 0
        state = ViewerState.Ready
        if (!keepView) fitToView()
        requestScene()
    }

    fun selectCell(name: String) {
        val cell = library?.cell(name) ?: return
        if (!isOverlay) {
            // Remember the choice, so adding a file later starts from this cell.
            files = files.map { OpenFile(it.name, it.library, cell.index, it.file) }
        }
        loadJob?.cancel()
        addProgress = null
        loadJob = viewModelScope.launch {
            try {
                buildCell(cell.index)
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                state = ViewerState.Failed("Not enough memory to open this cell.")
            } catch (e: Exception) {
                state = ViewerState.Failed(e.message ?: "Could not build this cell.")
            }
        }
    }

    // ---------------------------------------------------------------- overlay

    /** Adds the file behind [uri] to the ones shown (picked with "Add file to view"). */
    fun addUri(uri: Uri) {
        addFile(uri, displayName(uri))
    }

    /**
     * Reads the file [uri] and shows it on top of the open files. Unlike [load] this never replaces
     * the current layout: on any problem the view stays as it was and only a short [message] appears.
     */
    fun addFile(uri: Uri, name: String) {
        if (state !is ViewerState.Ready || files.isEmpty() || addProgress != null) return
        if (files.size >= LibraryMerge.MAX_PARTS) {
            message = "At most ${LibraryMerge.MAX_PARTS} files can be shown on top of each other."
            return
        }
        val resolver = getApplication<Application>().contentResolver
        val current = files
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                addProgress = "Reading $name…"
                val lib = withContext(Dispatchers.IO) {
                    val input = resolver.openInputStream(uri) ?: throw java.io.IOException("Cannot open the file.")
                    input.use {
                        LayoutReader.read(it, isCancelled = { !isActive }) { bytes ->
                            addProgress = "Reading $name… ${bytes shr 20} MB"
                        }
                    }
                }
                addProgress = "Indexing $name…"
                val top = withContext(Dispatchers.Default) { LibraryIndex(lib).defaultTopCell() }
                if (top == null) throw java.io.IOException("This file contains no cells.")
                val used = current.map { it.file }.toSet()
                val number = (1..LibraryMerge.MAX_PARTS).first { it !in used }
                rebuild(current + OpenFile(name, lib, top.index, number))
                withContext(Dispatchers.IO) { recents.record(uri, name) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                message = "Not enough memory to add $name."
            } catch (e: Exception) {
                message = "Could not add $name: ${e.message ?: "unknown error"}"
            } finally {
                addProgress = null
            }
        }
    }

    /** Takes file number [file] out of the overlay. The last remaining file cannot be removed. */
    fun removeFile(file: Int) {
        val current = files
        if (current.size < 2 || current.none { it.file == file } || addProgress != null) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                addProgress = "Updating…"
                rebuild(current.filter { it.file != file })
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                message = "Not enough memory to update the view."
            } catch (e: Exception) {
                message = "Could not remove the file: ${e.message ?: "unknown error"}"
            } finally {
                addProgress = null
            }
        }
    }

    /**
     * Shows exactly [newFiles]: one file directly, several merged into one overlay library. All the
     * slow work happens first; only when it succeeded are the new library and layers swapped in,
     * so a failure leaves the current view untouched. Keeps zoom and position.
     */
    private suspend fun rebuild(newFiles: List<OpenFile>) {
        val oldFiles = files
        val single = newFiles.size == 1
        val lib: Library
        val top: Int
        val shown: List<OpenFile>
        if (single) {
            // Back to the normal single-file path: no merge, layer keys have file = 0.
            val f = newFiles[0]
            shown = listOf(OpenFile(f.name, f.library, f.topCell, 1))
            lib = f.library
            top = f.topCell
        } else {
            shown = newFiles
            lib = withContext(Dispatchers.Default) {
                LibraryMerge.merge(newFiles.map { LibraryMerge.Part(it.library, it.topCell, it.file) })
            }
            top = lib.cell("Overlay")?.index ?: throw java.io.IOException("Could not combine the files.")
        }
        val idx = withContext(Dispatchers.Default) { LibraryIndex(lib) }
        val counts = withContext(Dispatchers.Default) { LayerStats.shapeCounts(lib, top) }
        val cache = MeshCache(lib)

        // Carry layer visibility over by key. A lone file's keys have file = 0; in an overlay they
        // carry the file number (a lone file always counts as number 1).
        val wasOverlay = oldFiles.size > 1
        val previous = HashMap<LayerKey, Boolean>()
        for (l in layers) {
            val k = l.key
            val mapped = when {
                !wasOverlay && !single -> k.copy(file = oldFiles[0].file)
                wasOverlay && single -> if (k.file == newFiles[0].file) k.copy(file = 0) else null
                else -> k
            }
            if (mapped != null) previous[mapped] = l.visible
        }

        library = lib
        index = idx
        meshes = cache
        sceneBuilder = SceneBuilder(idx, cache)
        files = shown
        showCell(top, counts, previous, keepView = true)
    }

    /** Shows or hides all layers of file number [file] of the overlay. */
    fun setFileVisible(file: Int, visible: Boolean) {
        layers = layers.map { if (it.key.file == file) it.copy(visible = visible) else it }
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
        val geo = frame
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
        rotationHeld = true
        pendingRotation = 0.0
    }

    /**
     * Applies one frame of a pinch/pan/rotate gesture around the screen point (cx, cy):
     * pan in pixels, zoom as a factor, rotation in degrees (clockwise on screen).
     *
     * Rotation "sticks" at multiples of 90 degrees: while [rotationHeld] the twist is collected in
     * [pendingRotation] and the view does not turn until it exceeds [BREAKOUT_DEG]. Once free, the
     * view follows the fingers and snaps back onto a snap angle when it comes within [SNAP_DEG].
     */
    fun onTransform(cx: Double, cy: Double, panX: Double, panY: Double, zoom: Double, rotationDeg: Double) {
        if (frame == null) return
        var rot = 0.0
        if (!rotationLocked) {
            if (rotationHeld) {
                pendingRotation += rotationDeg
                if (abs(pendingRotation) > BREAKOUT_DEG) {
                    // Broke out: apply only the part beyond the limit so the view does not jump.
                    rot = pendingRotation - sign(pendingRotation) * BREAKOUT_DEG
                    rotationHeld = false
                    snapArmed = false
                    pendingRotation = 0.0
                }
            } else {
                rot = rotationDeg
            }
        }
        applyTransform(cx, cy, panX, panY, zoom, rot)
        if (!rotationLocked && !rotationHeld) {
            val d = distanceToSnapAngle()
            if (d > ARM_DEG) snapArmed = true // the view has moved away far enough to snap again
            if (snapArmed && d < SNAP_DEG) snapOnto(cx, cy)
        }
    }

    /** Zooms by [factor] around the screen point (cx, cy), respecting the zoom limits. */
    fun zoomAround(cx: Double, cy: Double, factor: Double) {
        applyTransform(cx, cy, 0.0, 0.0, factor, 0.0)
    }

    /** Rotates the view by [deg] around (cx, cy) without any snapping (used by animations). */
    fun rotateAround(cx: Double, cy: Double, deg: Double) {
        applyTransform(cx, cy, 0.0, 0.0, 1.0, deg)
    }

    /**
     * Call when all fingers are lifted. If the view is free and close to a snap angle, returns the
     * rotation (degrees) the caller should animate with [rotateAround]; then call [settleRotation].
     * Returns null if nothing needs to happen.
     */
    fun onGestureEnd(): Double? {
        pendingRotation = 0.0
        if (rotationLocked || rotationHeld) return null
        if (distanceToSnapAngle() >= RELEASE_SNAP_DEG) return null
        return nearestSnapAngle() - view.rotationDegrees
    }

    /** Finishes a snap animation: puts the rotation exactly on the snap angle and holds it there. */
    fun settleRotation() {
        snapOnto(viewportW / 2.0, viewportH / 2.0)
    }

    fun viewportCenterX() = viewportW / 2.0
    fun viewportCenterY() = viewportH / 2.0

    /** The one place that changes [view]: view = T(c) * R(rot) * S(zoom) * T(-c + pan) * view. */
    private fun applyTransform(cx: Double, cy: Double, panX: Double, panY: Double, zoom: Double, rotationDeg: Double) {
        val z = clampZoom(zoom)
        view = Affine.translate(cx, cy) * Affine.rotate(rotationDeg) * Affine.scale(z) *
            Affine.translate(-cx + panX, -cy + panY) * view
    }

    /** Limits a zoom factor so the resulting scale stays within the allowed range. */
    private fun clampZoom(zoom: Double): Double {
        val geo = frame ?: return zoom
        val current = view.scale
        // Zoom limits: between 5% of the "fit" scale and 500 px per database unit.
        val fitScale = 0.9 * min(
            viewportW / max(geo.maxX - geo.minX, 1e-3),
            viewportH / max(geo.maxY - geo.minY, 1e-3),
        )
        val minScale = 0.05 * fitScale
        val maxScale = 500.0 / geo.dbUnitMicrons
        return (current * zoom).coerceIn(min(minScale, maxScale), maxScale) / current
    }

    /** The multiple of 90 degrees closest to the current rotation. */
    private fun nearestSnapAngle(): Double = Math.round(view.rotationDegrees / SNAP_STEP_DEG) * SNAP_STEP_DEG

    /** Angular distance (0..45 degrees) from the current rotation to the nearest snap angle. */
    private fun distanceToSnapAngle(): Double = abs(view.rotationDegrees - nearestSnapAngle())

    /** Rotates exactly onto the nearest snap angle about (cx, cy) and holds it there. */
    private fun snapOnto(cx: Double, cy: Double) {
        // Built from the actual current angle, so the result is exact (to rounding error).
        val correction = nearestSnapAngle() - view.rotationDegrees
        view = Affine.translate(cx, cy) * Affine.rotate(correction) * Affine.translate(-cx, -cy) * view
        rotationHeld = true
        pendingRotation = 0.0
    }

    fun resetRotation() {
        val cx = viewportW / 2.0
        val cy = viewportH / 2.0
        view = Affine.translate(cx, cy) * Affine.rotate(-view.rotationDegrees) * Affine.translate(-cx, -cy) * view
        rotationHeld = true
        pendingRotation = 0.0
    }

    fun toggleRotationLock() {
        rotationLocked = !rotationLocked
    }

    // ------------------------------------------------------------------ scene

    /**
     * Starts building a new draw list if the current one does not fit the view any more. Builds
     * run one at a time in the background; while one runs, the old draw list stays on screen
     * (it is drawn with the current view, so panning and zooming stay smooth).
     */
    private fun requestScene() {
        if (state != ViewerState.Ready || viewportW == 0 || !sceneOutdated()) return
        if (sceneJob?.isActive == true) {
            sceneAgain = true
            return
        }
        sceneJob = viewModelScope.launch {
            // Quick builds (the usual case) show no progress bar; it would only flicker.
            val slowTimer = launch {
                delay(SCENE_SLOW_MS)
                sceneSlow = true
            }
            try {
                do {
                    sceneAgain = false
                    val builder = sceneBuilder ?: break
                    val f = frame ?: break
                    val request = SceneRequest(f, view, viewportW, viewportH)
                    val list = withContext(Dispatchers.Default) { builder.build(request) { !isActive } }
                    val stats = list.stats
                    Log.i(
                        TAG,
                        "scene: ${list.groups.size} chunks, ${stats.instances} instances, ${stats.tinyItems} tiny; " +
                            "traversal ${stats.traversalMillis} ms, meshes ${stats.meshMillis} ms" +
                            if (stats.budgetExceeded) "; simplified (work or overdraw limit reached)" else "",
                    )
                    if (frame === f) drawList = list
                } while (sceneAgain && sceneOutdated())
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                state = ViewerState.Failed("Not enough memory to show this layout.")
            } catch (e: Exception) {
                Log.w(TAG, "scene build failed", e)
                state = ViewerState.Failed("Could not show this layout: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                slowTimer.cancel()
                sceneSlow = false
            }
        }
    }

    /** True if the draw list is missing or was built for a clearly different view. */
    private fun sceneOutdated(): Boolean {
        val list = drawList ?: return true
        val built = list.request
        if (built.frame !== frame || built.viewportWidth != viewportW || built.viewportHeight != viewportH) return true
        // Zoomed in or out a lot: the level of detail no longer fits.
        val ratio = view.scale / built.view.scale
        if (ratio > SCENE_ZOOM_RATIO || ratio < 1 / SCENE_ZOOM_RATIO) return true
        // Rotated: the small-shapes raster is aligned with the screen it was built for.
        var turn = abs(view.rotationDegrees - built.view.rotationDegrees) % 360.0
        if (turn > 180) turn = 360 - turn
        if (turn > SCENE_ROTATION_DEG) return true
        // Moved beyond the margin that was included.
        return !list.coverage.contains(visibleWorldBox())
    }

    /** The world area (µm) currently on screen, as an axis-aligned box. */
    private fun visibleWorldBox(): Box {
        val inv = view.inverse()
        var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE
        var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
        for (corner in 0 until 4) {
            val sx = if (corner and 1 == 0) 0.0 else viewportW.toDouble()
            val sy = if (corner and 2 == 0) 0.0 else viewportH.toDouble()
            val wx = inv.mapX(sx, sy); val wy = inv.mapY(sx, sy)
            x0 = min(x0, wx); x1 = max(x1, wx); y0 = min(y0, wy); y1 = max(y1, wy)
        }
        return Box(x0, y0, x1, y1)
    }

    /**
     * Called (from the GL thread) when the renderer needs meshes that are not built yet. Builds a
     * batch of them in the background (closest to the screen center first), then calls [done]; the
     * renderer uploads them and asks again for the rest. Batches keep memory use bounded.
     */
    fun buildMissingMeshes(chunks: List<Chunk>, done: () -> Unit) {
        val cache = meshes
        if (cache == null) {
            done()
            return
        }
        viewModelScope.launch(Dispatchers.Default) {
            try {
                cache.ensure(chunks, MESH_BATCH_BYTES) { !isActive }
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                return@launch // stop streaming; what is on screen stays
            } catch (e: Exception) {
                Log.w(TAG, "building meshes failed", e)
                return@launch
            }
            done()
        }
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
        val f = frame ?: return
        val idx = index ?: return
        if (!snapEnabled) return
        val visible = BooleanArray(idx.library.layers.size)
        for (layer in layers) if (layer.visible) visible[layer.layer] = true
        val radius = snapRadiusPx / view.scale
        viewModelScope.launch {
            val p1 = if (wasNew || end == 1) {
                withContext(Dispatchers.Default) { Snapping.snap(idx, f, visible, r.x1, r.y1, radius) }
            } else null
            val p2 = if (wasNew || end == 2) {
                withContext(Dispatchers.Default) { Snapping.snap(idx, f, visible, r.x2, r.y2, radius) }
            } else null
            if (ruler === r) {
                ruler = Ruler(p1?.get(0) ?: r.x1, p1?.get(1) ?: r.y1, p2?.get(0) ?: r.x2, p2?.get(1) ?: r.y2)
            }
        }
    }

    private companion object {
        const val TAG = "GdsViewer"

        // Rotation snapping: the snap angles are all multiples of SNAP_STEP_DEG (0, 90, 180, -90).
        const val SNAP_STEP_DEG = 90.0
        const val BREAKOUT_DEG = 20.0 // twist needed to leave a snap angle
        const val ARM_DEG = 10.0 // after breaking out, move this far away before snapping is armed
        const val SNAP_DEG = 4.0 // while twisting: snap when this close to a snap angle
        const val RELEASE_SNAP_DEG = 8.0 // on release: animate onto a snap angle when this close

        // A new draw list is built when the zoom changed by more than this factor or the rotation by this angle.
        const val SCENE_ZOOM_RATIO = 1.6
        const val SCENE_ROTATION_DEG = 3.0
        const val SCENE_SLOW_MS = 400L

        // Meshes are built and uploaded in batches of about this size.
        const val MESH_BATCH_BYTES = 48L shl 20

    }
}
