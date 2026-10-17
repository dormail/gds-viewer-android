package io.github.dormail.gdsviewer.ui

import android.opengl.GLSurfaceView
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.layout.onSizeChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.dormail.gdsviewer.R
import io.github.dormail.gdsviewer.render.SceneLayerStyle
import io.github.dormail.gdsviewer.render.SceneRenderer
import io.github.dormail.gdsviewer.ui.theme.LayerPalette
import io.github.dormail.gdsviewer.ui.theme.LocalDarkTheme
import io.github.dormail.gdsviewer.ui.theme.canvasColor
import io.github.dormail.gdsviewer.ui.theme.canvasColorInt
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.exp
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

private const val ANIM_MS = 250 // duration of zoom / snap animations
private const val TWO_FINGER_TAP_MS = 300L // max time for a two-finger tap

/** The layout drawing (OpenGL) with touch handling and overlays (ruler, scale bar, compass). */
@Composable
fun LayoutViewer(vm: ViewerViewModel) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val dark = LocalDarkTheme.current
    val renderer = remember { SceneRenderer() }
    val glView = remember {
        GLSurfaceView(context).apply {
            setEGLContextClientVersion(3)
            setEGLConfigChooser(SceneRenderer.ConfigChooser())
            preserveEGLContextOnPause = true
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
            // The renderer streams meshes to the GPU over several frames and asks for missing ones.
            val surface = this
            renderer.requestRender = { surface.requestRender() }
            renderer.onMissingMeshes = { chunks ->
                vm.buildMissingMeshes(chunks) {
                    renderer.meshesReady()
                    surface.requestRender()
                }
            }
        }
    }

    // GLSurfaceView needs to be told when the app is paused/resumed (Android lifecycle).
    DisposableEffect(lifecycleOwner, glView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> glView.onResume()
                Lifecycle.Event.ON_PAUSE -> glView.onPause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // Free the GPU memory now: the view is going away (e.g. screen rotation). Wait briefly
            // so this runs on the GL thread before the view is detached.
            val released = CountDownLatch(1)
            glView.queueEvent {
                renderer.release()
                released.countDown()
            }
            released.await(500, TimeUnit.MILLISECONDS)
            glView.onPause()
        }
    }

    // Animations run in a coroutine scope with a frame clock; a new touch cancels them.
    val scope = rememberCoroutineScope()
    val animJob = remember { mutableStateOf<Job?>(null) }
    val animSpec = tween<Float>(durationMillis = ANIM_MS, easing = FastOutSlowInEasing)

    // Animated zoom by [factor] around [center]: each frame applies only the step since the last one.
    fun animateZoom(factor: Double, center: Offset) {
        animJob.value?.cancel()
        animJob.value = scope.launch {
            var last = 0f
            animate(0f, 1f, animationSpec = animSpec) { t, _ ->
                vm.zoomAround(center.x.toDouble(), center.y.toDouble(), factor.pow((t - last).toDouble()))
                last = t
            }
        }
    }

    // Animated rotation by [deg] around the screen center, then lock exactly onto the snap angle.
    fun animateRotation(deg: Double) {
        animJob.value?.cancel()
        animJob.value = scope.launch {
            var last = 0f
            animate(0f, 1f, animationSpec = animSpec) { t, _ ->
                vm.rotateAround(vm.viewportCenterX(), vm.viewportCenterY(), deg * (t - last))
                last = t
            }
            vm.settleRotation()
        }
    }

    val grabRadius = with(density) { 40.dp.toPx() }
    val snapRadius = with(density) { 24.dp.toPx() }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { glView },
            modifier = Modifier.fillMaxSize(),
            update = {
                // State read here is tracked: when it changes, this block runs again.
                // The mesh cache is set before the draw list (the renderer reads them in the other order).
                renderer.meshes = vm.meshes
                renderer.drawList = vm.drawList
                renderer.background = canvasColorInt(dark)
                renderer.fillAlpha = LayerPalette.fillAlpha(dark)
                renderer.styles = vm.layers.map {
                    SceneLayerStyle(it.layer, LayerPalette.color(it.paletteIndex, dark), it.visible)
                }
                renderer.view = vm.view
                glView.requestRender()
            },
        )

        // Transparent overlay that receives all touches.
        Box(
            Modifier
                .fillMaxSize()
                .onSizeChanged { vm.onViewportChanged(it.width, it.height) }
                .pointerInput(Unit) {
                    // Remembered between gestures, to recognise a double-tap.
                    var lastTapUpTime = -1L
                    var lastTapPos = Offset.Zero
                    val slop = viewConfiguration.touchSlop
                    val quickZoomPx = 120.dp.toPx() // finger travel that doubles the zoom (x e)

                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        animJob.value?.cancel() // a new touch stops any running animation
                        val downTime = down.uptimeMillis
                        // Is this touch the second tap of a double-tap?
                        val isSecondTap = lastTapUpTime >= 0 &&
                            downTime - lastTapUpTime <= viewConfiguration.doubleTapTimeoutMillis &&
                            (down.position - lastTapPos).getDistance() <= slop * 4
                        lastTapUpTime = -1L

                        val downPositions = mutableMapOf(down.id to down.position)
                        var maxPointers = 1
                        var moved = false // some finger went beyond the touch slop
                        var multi = false // two or more fingers were down at some point
                        var panning = false // single finger has passed the slop: pan / ruler drag
                        var measuring = false // a ruler drag is running
                        var quickZoom = false // double-tap-and-slide zoom is running
                        var upTime = downTime

                        while (true) {
                            val event = awaitPointerEvent()
                            upTime = event.changes.maxOf { it.uptimeMillis }
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            for (c in pressed) {
                                val start = downPositions.getOrPut(c.id) { c.position }
                                if ((c.position - start).getDistance() > slop) moved = true
                            }
                            maxPointers = max(maxPointers, pressed.size)

                            if (pressed.size >= 2) {
                                // Two or more fingers: pinch / pan / rotate right away.
                                if (measuring) vm.rulerCancel()
                                measuring = false
                                quickZoom = false
                                multi = true
                                panning = true
                                val c = event.calculateCentroid(useCurrent = true)
                                if (c != Offset.Unspecified) {
                                    val pan = event.calculatePan()
                                    vm.onTransform(
                                        c.x.toDouble(), c.y.toDouble(),
                                        pan.x.toDouble(), pan.y.toDouble(),
                                        event.calculateZoom().toDouble(),
                                        event.calculateRotation().toDouble(),
                                    )
                                }
                            } else {
                                val p = pressed.first()
                                if (!panning && !quickZoom) {
                                    // Wait until the finger has really moved before doing anything.
                                    val dy = p.position.y - down.position.y
                                    if (isSecondTap && !multi && abs(dy) > slop) {
                                        quickZoom = true
                                        // Catch up on the slop, so the touched spot is under the finger.
                                        val move = p.position - down.position
                                        vm.onTransform(
                                            p.position.x.toDouble(), p.position.y.toDouble(),
                                            move.x.toDouble(), move.y.toDouble(),
                                            exp(move.y / quickZoomPx).toDouble(), 0.0,
                                        )
                                    } else if ((p.position - down.position).getDistance() > slop) {
                                        panning = true
                                        if (vm.rulerMode && !multi) {
                                            vm.rulerDown(down.position.x, down.position.y, grabRadius)
                                            measuring = true
                                        }
                                    }
                                } else if (quickZoom) {
                                    // Sliding down zooms in, up zooms out (like Google Maps). The spot
                                    // under the finger stays under it: zoom around the finger and move
                                    // along with it.
                                    val move = p.position - p.previousPosition
                                    vm.onTransform(
                                        p.position.x.toDouble(), p.position.y.toDouble(),
                                        move.x.toDouble(), move.y.toDouble(),
                                        exp(move.y / quickZoomPx).toDouble(), 0.0,
                                    )
                                } else if (measuring) {
                                    vm.rulerMove(p.position.x, p.position.y)
                                } else {
                                    val pan = event.calculatePan()
                                    vm.onTransform(
                                        p.position.x.toDouble(), p.position.y.toDouble(),
                                        pan.x.toDouble(), pan.y.toDouble(), 1.0, 0.0,
                                    )
                                }
                            }
                            event.changes.forEach { it.consume() }
                        }
                        if (measuring) vm.rulerUp(snapRadius)

                        val duration = upTime - downTime
                        if (maxPointers == 2 && !moved && duration <= TWO_FINGER_TAP_MS && downPositions.size == 2) {
                            // Two-finger tap: zoom out around the midpoint of the two fingers.
                            val c = downPositions.values.reduce { a, b -> a + b } / 2f
                            animateZoom(0.5, c)
                        } else if (maxPointers == 1 && !moved && !panning && !quickZoom &&
                            duration <= viewConfiguration.longPressTimeoutMillis
                        ) {
                            if (isSecondTap) {
                                animateZoom(2.0, down.position) // double-tap: zoom in
                            } else {
                                lastTapUpTime = upTime // first tap: remember it
                                lastTapPos = down.position
                            }
                        }

                        // After the twist: maybe glide onto the nearest snap angle.
                        val snapDeg = vm.onGestureEnd()
                        if (snapDeg != null) animateRotation(snapDeg)
                    }
                },
        )

        if (vm.frame != null) RulerAndScaleOverlay(vm)

        val frame = vm.frame
        val rotation = vm.view.rotationDegrees.toFloat()
        if (abs(rotation) > 0.1f) {
            SmallFloatingActionButton(
                onClick = { vm.resetRotation() },
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
            ) {
                Icon(
                    painterResource(R.drawable.ic_navigation), contentDescription = "Reset rotation",
                    modifier = Modifier.rotate(rotation),
                )
            }
        }
        // A thin progress bar while building the view takes a moment (big layouts).
        if (vm.sceneSlow) LinearProgressIndicator(Modifier.align(Alignment.TopCenter).fillMaxWidth())
        if (frame != null && frame.isEmpty) {
            Text(
                "Cell ${frame.name} contains no shapes",
                color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

/** Draws the ruler line, the scale bar and the readout card on top of the layout. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.RulerAndScaleOverlay(vm: ViewerViewModel) {
    val density = LocalDensity.current
    val view = vm.view
    val ruler = vm.ruler
    val dbUnit = vm.frame?.dbUnitMicrons ?: 0.001
    val dark = LocalDarkTheme.current
    val textColor = MaterialTheme.colorScheme.onSurface
    val subtleColor = MaterialTheme.colorScheme.onSurfaceVariant
    // Ruler colors: a bright line with a dark halo on the dark canvas, the reverse on the light one.
    val lineColor = if (dark) Color.White else Color(0xFF212121)
    val haloColor = if (dark) Color(0xCC000000) else Color(0xCCFFFFFF)
    val canvasBg = canvasColor(dark)

    // Scale bar: pick a "nice" length (1, 2 or 5 x 10^k µm) close to 100 dp on screen.
    val scale = view.scale // pixels per µm
    val targetUm = with(density) { 100.dp.toPx() } / scale
    val barUm = niceLength(targetUm)
    val barPx = (barUm * scale).toFloat()

    Canvas(Modifier.fillMaxSize()) {
        if (ruler != null) {
            val p1 = Offset(view.mapX(ruler.x1, ruler.y1).toFloat(), view.mapY(ruler.x1, ruler.y1).toFloat())
            val p2 = Offset(view.mapX(ruler.x2, ruler.y2).toFloat(), view.mapY(ruler.x2, ruler.y2).toFloat())
            // Halo first so the line is visible on any background.
            drawLine(haloColor, p1, p2, strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round)
            drawLine(lineColor, p1, p2, strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            for (p in listOf(p1, p2)) {
                drawCircle(haloColor, radius = 7.dp.toPx(), center = p)
                drawCircle(lineColor, radius = 5.dp.toPx(), center = p)
                drawCircle(canvasBg, radius = 2.dp.toPx(), center = p)
            }
        }
    }

    // Scale bar at the bottom-start corner.
    Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
        Text(formatLength(barUm), color = textColor, style = MaterialTheme.typography.labelMedium)
        Canvas(Modifier.size(width = with(density) { barPx.toDp() }, height = 10.dp)) {
            val y = size.height / 2
            val w = 2.dp.toPx()
            drawLine(textColor, Offset(0f, y), Offset(size.width, y), strokeWidth = w)
            drawLine(textColor, Offset(0f, 0f), Offset(0f, size.height), strokeWidth = w)
            drawLine(textColor, Offset(size.width, 0f), Offset(size.width, size.height), strokeWidth = w)
        }
    }

    if (ruler != null) {
        val decimals = max(0, ceil(-log10(dbUnit)).toInt())
        val dx = ruler.x2 - ruler.x1
        val dy = ruler.y2 - ruler.y1
        Card(
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f)),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    String.format(Locale.ROOT, "%.${decimals}f µm", hypot(dx, dy)),
                    color = textColor, style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    String.format(Locale.ROOT, "dx %.${decimals}f   dy %.${decimals}f µm", abs(dx), abs(dy)),
                    color = subtleColor, style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    } else if (vm.rulerMode) {
        Text(
            "Drag to measure · double-tap to zoom · two fingers to move",
            color = subtleColor, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
        )
    }
}

/** Rounds [um] down to 1, 2 or 5 times a power of ten. */
private fun niceLength(um: Double): Double {
    if (um <= 0 || um.isNaN() || um.isInfinite()) return 1.0
    val pow10 = 10.0.pow(floor(log10(um)))
    val m = um / pow10
    val nice = when {
        m >= 5 -> 5.0
        m >= 2 -> 2.0
        else -> 1.0
    }
    return nice * pow10
}

/** Formats a length given in µm with a fitting unit (nm, µm or mm). */
private fun formatLength(um: Double): String {
    val (value, unit) = when {
        um < 1.0 -> um * 1000 to "nm"
        um >= 1000.0 -> um / 1000 to "mm"
        else -> um to "µm"
    }
    val text = String.format(Locale.ROOT, "%.3f", value).trimEnd('0').trimEnd('.')
    return "$text $unit"
}
