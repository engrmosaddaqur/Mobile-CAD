package com.example.ui.workspace

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.cad.engine.ActiveTool
import com.example.cad.engine.CadWorkspaceUiState
import com.example.cad.model.CanvasBackgroundTheme
import com.example.cad.render.CadCanvasRenderer.renderScene
import com.example.ui.theme.CadAmber
import com.example.ui.theme.CadCyan
import com.example.ui.theme.JetBrainsMonoFamily
import java.util.Locale
import kotlin.math.abs

@Composable
fun CadCanvasView(
    state: CadWorkspaceUiState,
    canvasTheme: CanvasBackgroundTheme,
    showCrosshairCoords: Boolean,
    onViewportSizeChanged: (Float, Float) -> Unit,
    onPinchOrPan: (Offset, Float, Offset) -> Unit,
    onMouseWheelZoom: (Float, Offset) -> Unit,
    onPointerHover: (Offset) -> Unit,
    onTap: (Offset) -> Unit,
    onDoubleTap: () -> Unit,
    onSingleDragStart: (Offset) -> Unit,
    onSingleDrag: (Offset, Offset) -> Unit,
    onSingleDragEnd: () -> Unit,
    onZoomExtents: () -> Unit,
    onZoomAll: () -> Unit,
    onZoomWindow: () -> Unit,
    onZoomPrevious: () -> Unit,
    onTogglePanTool: () -> Unit,
    modifier: Modifier = Modifier
) {
    var lastTapTime by remember { mutableLongStateOf(0L) }
    var lastTapPos by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { sz ->
                onViewportSizeChanged(sz.width.toFloat(), sz.height.toFloat())
            }
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .testTag("cad_drawing_canvas")
                // Mouse wheel zoom + hover tracking
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            when (event.type) {
                                PointerEventType.Scroll -> {
                                    val change = event.changes.firstOrNull()
                                    if (change != null) {
                                        val scrollY = -change.scrollDelta.y
                                        if (abs(scrollY) > 0.001f) {
                                            onMouseWheelZoom(scrollY, change.position)
                                            change.consume()
                                        }
                                    }
                                }
                                PointerEventType.Move -> {
                                    event.changes.firstOrNull()?.let { ch ->
                                        onPointerHover(ch.position)
                                    }
                                }
                                else -> Unit
                            }
                        }
                    }
                }
                // Touch & mouse button gestures (2-finger pinch+pan, 1-finger tap/double-tap/drag, middle-mouse pan)
                .pointerInput(state.activeTool) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val startPos = down.position
                        var moved = false
                        var multiTouchUsed = false
                        var singleDragStarted = false
                        var prevPos = startPos
                        val touchSlop = viewConfiguration.touchSlop

                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break

                            // Middle mouse button drag -> immediate pan
                            if (event.buttons.isTertiaryPressed && pressed.size == 1) {
                                multiTouchUsed = true
                                val cur = pressed[0].position
                                val delta = cur - prevPos
                                prevPos = cur
                                if (delta != Offset.Zero) {
                                    onPinchOrPan(delta, 1f, cur)
                                }
                                pressed[0].consume()
                                continue
                            }

                            if (pressed.size >= 2) {
                                multiTouchUsed = true
                                val zoomChange = event.calculateZoom()
                                val panChange = event.calculatePan()
                                val centroid = event.calculateCentroid(useCurrent = true)
                                if (centroid != Offset.Unspecified) {
                                    onPinchOrPan(panChange, zoomChange, centroid)
                                }
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            } else if (!multiTouchUsed && pressed.size == 1) {
                                val ch = pressed[0]
                                val delta = ch.position - prevPos
                                val totalDist = (ch.position - startPos).getDistance()
                                prevPos = ch.position
                                if (!moved && totalDist > touchSlop) {
                                    moved = true
                                    singleDragStarted = true
                                    onSingleDragStart(startPos)
                                }
                                if (singleDragStarted) {
                                    onSingleDrag(ch.position, delta)
                                    ch.consume()
                                }
                            }
                        }

                        if (singleDragStarted) {
                            onSingleDragEnd()
                        } else if (!moved && !multiTouchUsed) {
                            val now = System.currentTimeMillis()
                            if (now - lastTapTime < 280L && (startPos - lastTapPos).getDistance() < 48f) {
                                lastTapTime = 0L
                                onDoubleTap()
                            } else {
                                lastTapTime = now
                                lastTapPos = startPos
                                onTap(startPos)
                            }
                        }
                    }
                }
        ) {
            renderScene(
                doc = state.document,
                viewport = state.viewport,
                snapSettings = state.snapSettings,
                bgTheme = canvasTheme,
                cursorWorld = state.cursorWorld,
                snapResult = state.snapResult,
                preview = state.previewGeometry,
                spatialIndex = state.spatialIndex
            )
        }

        // Top-left Live Coordinate & Active Tool HUD
        if (showCrosshairCoords) {
            val pt = state.snapResult?.point ?: state.cursorWorld ?: state.viewport.centerWorld
            val precision = state.document.dimensionStyle.decimalPrecision.coerceIn(1, 4)
            val fmtStr = "%.${precision}f"
            val coordText = String.format(
                Locale.US,
                "X: $fmtStr  Y: $fmtStr %s",
                pt.x,
                pt.y,
                state.document.units.shortLabel
            )
            Surface(
                color = Color(0xCC131B26),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
                    .border(1.dp, Color(0xFF28374D), RoundedCornerShape(10.dp))
            ) {
                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = coordText,
                            fontFamily = JetBrainsMonoFamily,
                            style = MaterialTheme.typography.labelSmall,
                            color = CadCyan
                        )
                        state.snapResult?.label?.let { snapLabel ->
                            Text(
                                text = "[$snapLabel]",
                                fontFamily = JetBrainsMonoFamily,
                                style = MaterialTheme.typography.labelSmall,
                                color = CadAmber
                            )
                        }
                    }
                    Text(
                        text = "${state.activeTool.displayName} • Zoom ${(state.viewport.zoom * 100 / 6.0).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF9FB0C7)
                    )
                }
            }
        }

        // Top-right Floating Navigation Controls Pill
        Surface(
            color = Color(0xD9131B26),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(10.dp)
                .border(1.dp, Color(0xFF28374D), RoundedCornerShape(12.dp))
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(4.dp)
            ) {
                NavToolButton(
                    icon = Icons.Default.PanTool,
                    desc = "Toggle Pan mode",
                    active = state.activeTool is ActiveTool.Pan,
                    tag = "nav_pan_btn",
                    onClick = onTogglePanTool
                )
                NavToolButton(
                    icon = Icons.Default.FitScreen,
                    desc = "Zoom Extents",
                    active = false,
                    tag = "nav_zoom_extents_btn",
                    onClick = onZoomExtents
                )
                NavToolButton(
                    icon = Icons.Default.CropFree,
                    desc = "Zoom Window",
                    active = state.activeTool is ActiveTool.ZoomWindow,
                    tag = "nav_zoom_window_btn",
                    onClick = onZoomWindow
                )
                NavToolButton(
                    icon = Icons.AutoMirrored.Filled.Undo,
                    desc = "Zoom Previous",
                    active = false,
                    tag = "nav_zoom_prev_btn",
                    onClick = onZoomPrevious
                )
                NavToolButton(
                    icon = Icons.Default.ZoomOutMap,
                    desc = "Zoom All",
                    active = false,
                    tag = "nav_zoom_all_btn",
                    onClick = onZoomAll
                )
            }
        }

        // Live Measurement Result Card (when a Measure tool completes)
        state.measurement?.let { m ->
            Surface(
                color = Color(0xEA131B26),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(12.dp)
                    .border(1.dp, CadCyan.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(Icons.Default.CenterFocusStrong, contentDescription = null, tint = CadCyan)
                    Column {
                        Text(m.title, style = MaterialTheme.typography.labelMedium, color = Color(0xFF9FB0C7))
                        Text(m.primaryValue, fontFamily = JetBrainsMonoFamily, style = MaterialTheme.typography.titleMedium, color = CadCyan)
                        if (m.secondaryDetails.isNotEmpty()) {
                            Text(m.secondaryDetails, fontFamily = JetBrainsMonoFamily, style = MaterialTheme.typography.labelSmall, color = Color(0xFFECF2F8))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NavToolButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    active: Boolean,
    tag: String,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(40.dp)
            .background(
                if (active) CadCyan.copy(alpha = 0.22f) else Color.Transparent,
                RoundedCornerShape(8.dp)
            )
            .testTag(tag)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = desc,
            tint = if (active) CadCyan else Color(0xFFECF2F8),
            modifier = Modifier.size(20.dp)
        )
    }
}
