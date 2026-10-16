package com.example.gdsviewer.ui

import android.opengl.GLSurfaceView
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.layout.onSizeChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.gdsviewer.R
import com.example.gdsviewer.render.LayerStyle
import com.example.gdsviewer.render.LayoutRenderer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/** The layout drawing (OpenGL) with touch handling and overlays (ruler, scale bar, compass). */
@Composable
fun LayoutViewer(vm: ViewerViewModel) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val renderer = remember { LayoutRenderer() }
    val glView = remember {
        GLSurfaceView(context).apply {
            setEGLContextClientVersion(2)
            setEGLConfigChooser(LayoutRenderer.ConfigChooser())
            preserveEGLContextOnPause = true
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
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
            glView.onPause()
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
                renderer.geometry = vm.geometry
                renderer.styles = vm.layers.map { LayerStyle(it.key, it.color, it.visible) }
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
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var measuring = vm.rulerMode
                        if (measuring) vm.rulerDown(down.position.x, down.position.y, grabRadius)
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            if (measuring && pressed.size > 1) {
                                vm.rulerCancel()
                                measuring = false
                            }
                            if (measuring) {
                                val p = event.changes.firstOrNull { it.id == down.id } ?: pressed.first()
                                vm.rulerMove(p.position.x, p.position.y)
                            } else {
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
                            }
                            event.changes.forEach { it.consume() }
                        }
                        if (measuring) vm.rulerUp(snapRadius)
                    }
                },
        )

        RulerAndScaleOverlay(vm)

        val geo = vm.geometry
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
        if (geo != null && geo.truncated) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
            ) {
                Text(
                    "Layout too large — only part of it is shown",
                    modifier = Modifier.padding(8.dp), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (geo != null && geo.isEmpty) {
            Text(
                "Cell ${geo.cellName} contains no shapes",
                color = Color.White, modifier = Modifier.align(Alignment.Center),
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
    val dbUnit = vm.geometry?.dbUnitMicrons ?: 0.001
    val textColor = Color.White

    // Scale bar: pick a "nice" length (1, 2 or 5 x 10^k µm) close to 100 dp on screen.
    val scale = view.scale // pixels per µm
    val targetUm = with(density) { 100.dp.toPx() } / scale
    val barUm = niceLength(targetUm)
    val barPx = (barUm * scale).toFloat()

    Canvas(Modifier.fillMaxSize()) {
        if (ruler != null) {
            val p1 = Offset(view.mapX(ruler.x1, ruler.y1).toFloat(), view.mapY(ruler.x1, ruler.y1).toFloat())
            val p2 = Offset(view.mapX(ruler.x2, ruler.y2).toFloat(), view.mapY(ruler.x2, ruler.y2).toFloat())
            // Dark outline first so the white line is visible on any background.
            drawLine(Color(0xCC000000), p1, p2, strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round)
            drawLine(Color.White, p1, p2, strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            for (p in listOf(p1, p2)) {
                drawCircle(Color(0xCC000000), radius = 7.dp.toPx(), center = p)
                drawCircle(Color.White, radius = 5.dp.toPx(), center = p)
                drawCircle(Color(0xFF101014), radius = 2.dp.toPx(), center = p)
            }
        }
    }

    // Scale bar at the bottom-start corner.
    Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
        Text(formatLength(barUm), color = textColor, style = MaterialTheme.typography.labelMedium)
        Canvas(Modifier.size(width = with(density) { barPx.toDp() }, height = 10.dp)) {
            val y = size.height / 2
            val w = 2.dp.toPx()
            drawLine(Color.White, Offset(0f, y), Offset(size.width, y), strokeWidth = w)
            drawLine(Color.White, Offset(0f, 0f), Offset(0f, size.height), strokeWidth = w)
            drawLine(Color.White, Offset(size.width, 0f), Offset(size.width, size.height), strokeWidth = w)
        }
    }

    if (ruler != null) {
        val decimals = max(0, ceil(-log10(dbUnit)).toInt())
        val dx = ruler.x2 - ruler.x1
        val dy = ruler.y2 - ruler.y1
        Card(
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xE6202028)),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    String.format(Locale.ROOT, "%.${decimals}f µm", hypot(dx, dy)),
                    color = textColor, style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    String.format(Locale.ROOT, "dx %.${decimals}f   dy %.${decimals}f µm", abs(dx), abs(dy)),
                    color = Color(0xFFBBBBBB), style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    } else if (vm.rulerMode) {
        Text(
            "Drag to measure · two fingers to move",
            color = Color(0xFFBBBBBB), style = MaterialTheme.typography.bodySmall,
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
