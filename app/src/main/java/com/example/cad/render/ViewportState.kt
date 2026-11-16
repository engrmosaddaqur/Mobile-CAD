package com.example.cad.render

import androidx.compose.ui.geometry.Offset
import com.example.cad.model.Bounds2D
import com.example.cad.model.Vec2
import kotlin.math.max
import kotlin.math.min

data class ViewportSnapshot(
    val centerWorld: Vec2,
    val zoom: Double
)

data class ViewportState(
    val centerWorld: Vec2 = Vec2(0.0, 0.0),
    val zoom: Double = 6.0, // pixels per world unit
    val viewWidthPx: Float = 1080f,
    val viewHeightPx: Float = 1400f,
    val history: List<ViewportSnapshot> = emptyList()
) {
    fun screenToWorld(screen: Offset): Vec2 {
        val wx = centerWorld.x + (screen.x - viewWidthPx * 0.5f) / zoom
        val wy = centerWorld.y - (screen.y - viewHeightPx * 0.5f) / zoom // Y-up in CAD
        return Vec2(wx, wy)
    }

    fun worldToScreen(world: Vec2): Offset {
        val sx = (viewWidthPx * 0.5f + (world.x - centerWorld.x) * zoom).toFloat()
        val sy = (viewHeightPx * 0.5f - (world.y - centerWorld.y) * zoom).toFloat()
        return Offset(sx, sy)
    }

    fun visibleWorldBounds(): Bounds2D {
        val halfW = (viewWidthPx * 0.5f) / zoom
        val halfH = (viewHeightPx * 0.5f) / zoom
        return Bounds2D(
            minX = centerWorld.x - halfW,
            minY = centerWorld.y - halfH,
            maxX = centerWorld.x + halfW,
            maxY = centerWorld.y + halfH
        )
    }

    fun panByScreenDelta(delta: Offset): ViewportState {
        val dxWorld = -delta.x / zoom
        val dyWorld = delta.y / zoom
        return copy(centerWorld = Vec2(centerWorld.x + dxWorld, centerWorld.y + dyWorld))
    }

    fun zoomAtScreenPoint(factor: Double, pivotScreen: Offset, pushHistory: Boolean = false): ViewportState {
        val newZoom = (zoom * factor).coerceIn(0.02, 4000.0)
        val worldBefore = screenToWorld(pivotScreen)
        val newCenterX = worldBefore.x - (pivotScreen.x - viewWidthPx * 0.5f) / newZoom
        val newCenterY = worldBefore.y + (pivotScreen.y - viewHeightPx * 0.5f) / newZoom
        val nextHistory = if (pushHistory) (history + ViewportSnapshot(centerWorld, zoom)).takeLast(20) else history
        return copy(centerWorld = Vec2(newCenterX, newCenterY), zoom = newZoom, history = nextHistory)
    }

    fun zoomToBounds(bounds: Bounds2D, paddingRatio: Double = 0.82): ViewportState {
        val nextHistory = (history + ViewportSnapshot(centerWorld, zoom)).takeLast(20)
        val bw = max(1.0, bounds.width)
        val bh = max(1.0, bounds.height)
        val zX = (viewWidthPx * paddingRatio) / bw
        val zY = (viewHeightPx * paddingRatio) / bh
        val targetZoom = min(zX, zY).coerceIn(0.05, 800.0)
        return copy(
            centerWorld = bounds.center,
            zoom = targetZoom,
            history = nextHistory
        )
    }

    fun zoomPrevious(): ViewportState {
        if (history.isEmpty()) return this
        val last = history.last()
        return copy(
            centerWorld = last.centerWorld,
            zoom = last.zoom,
            history = history.dropLast(1)
        )
    }
}
