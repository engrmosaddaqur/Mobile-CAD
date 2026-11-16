package com.example.cad.render

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import com.example.cad.geometry.CadSpatialIndex
import com.example.cad.geometry.GeometryEngine
import com.example.cad.geometry.GeometryOperations
import com.example.cad.geometry.IntArrayList
import com.example.cad.geometry.SnapResult
import com.example.cad.io.CadExportEngine
import com.example.cad.model.Bounds2D
import com.example.cad.model.CadDocument
import com.example.cad.model.CadEntity
import com.example.cad.model.CadEntity.*
import com.example.cad.model.CadLayer
import com.example.cad.model.CadLineType
import com.example.cad.model.CanvasBackgroundTheme
import com.example.cad.model.DimensionType
import com.example.cad.model.OsnapType
import com.example.cad.model.SnapSettings
import com.example.cad.model.Vec2
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

data class PreviewGeometry(
    val entities: List<CadEntity> = emptyList(),
    val measurementText: String? = null,
    val measurementAnchor: Vec2? = null,
    val selectionRect: Pair<Vec2, Vec2>? = null
)

object CadCanvasRenderer {

    private val nativeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        isSubpixelText = true
    }

    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Pre-allocated scratch buffers reused across frames to eliminate GC churn at 60fps
    private val scratchPath = Path()
    private val scratchVisibleIndices = IntArrayList(1024)
    private var scratchStamp = IntArray(1024)
    private var scratchStampSeq = 1

    private val previewDashEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 6f), 0f)
    private val selectedDashEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 6f), 0f)
    private val crossingDashEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
    private val trackingDashEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)

    private val lineTypeDashCache: Map<CadLineType, PathEffect?> = mapOf(
        CadLineType.CONTINUOUS to null,
        CadLineType.DASHED to PathEffect.dashPathEffect(CadLineType.DASHED.dashPatternPx!!, 0f),
        CadLineType.DOTTED to PathEffect.dashPathEffect(CadLineType.DOTTED.dashPatternPx!!, 0f),
        CadLineType.DASHDOT to PathEffect.dashPathEffect(CadLineType.DASHDOT.dashPatternPx!!, 0f),
        CadLineType.CENTER to PathEffect.dashPathEffect(CadLineType.CENTER.dashPatternPx!!, 0f),
        CadLineType.HIDDEN to PathEffect.dashPathEffect(CadLineType.HIDDEN.dashPatternPx!!, 0f)
    )

    fun DrawScope.renderScene(
        doc: CadDocument,
        viewport: ViewportState,
        snapSettings: SnapSettings,
        bgTheme: CanvasBackgroundTheme,
        cursorWorld: Vec2?,
        snapResult: SnapResult?,
        preview: PreviewGeometry,
        spatialIndex: CadSpatialIndex? = null,
        crosshairSizeRatio: Float = 0.06f
    ) {
        val bgColor = Color(bgTheme.backgroundArgb)
        drawRect(color = bgColor)

        val visibleBounds = viewport.visibleWorldBounds()

        // 1. Paper Space Sheet (if Layout tab is active)
        val layout = doc.activeLayout
        if (!layout.isModelSpace) {
            drawPaperSpaceSheet(layout, viewport, bgTheme.isDarkCanvas)
        }

        // 2. Adaptive CAD Grid
        if (snapSettings.gridVisible) {
            drawAdaptiveGrid(viewport, visibleBounds, snapSettings.gridSpacing, bgTheme.isDarkCanvas)
        }

        // 3. X & Y World Axes + UCS Triad
        drawAxesAndUcs(viewport, bgTheme.isDarkCanvas)

        // 4. Entities (spatial-index O(k) frustum culled + adaptive LOD for 60fps on massive DWGs/DXFs)
        val exactLayerMap = HashMap<String, CadLayer>(doc.layers.size * 2)
        val upperLayerMap = HashMap<String, CadLayer>(doc.layers.size * 2)
        for (layer in doc.layers) {
            exactLayerMap[layer.name] = layer
            upperLayerMap[layer.name.uppercase()] = layer
        }
        val expandedBounds = visibleBounds.expand(max(visibleBounds.width, visibleBounds.height) * 0.08)

        val entities = doc.entities
        val useIndex = spatialIndex != null && spatialIndex.entityCount == entities.size

        if (useIndex) {
            val idx = spatialIndex!!
            if (scratchStamp.size < entities.size) {
                scratchStamp = IntArray(entities.size + 256)
            }
            scratchStampSeq++
            if (scratchStampSeq == Int.MAX_VALUE) {
                scratchStamp.fill(0)
                scratchStampSeq = 1
            }

            idx.queryBounds(expandedBounds, scratchVisibleIndices, scratchStamp, scratchStampSeq)
            val visCount = scratchVisibleIndices.size
            val disableDashesForSpeed = visCount > 2500
            val zoom = viewport.zoom

            for (k in 0 until visCount) {
                val eIdx = scratchVisibleIndices[k]
                val entity = entities[eIdx]
                val layer = exactLayerMap[entity.layerName] ?: upperLayerMap[entity.layerName.uppercase()]
                if (layer != null && (!layer.isOn || layer.isFrozen)) continue

                val eb = idx.boundsAt(eIdx)
                val pxW = eb.width * zoom
                val pxH = eb.height * zoom
                val maxDimPx = max(pxW, pxH)

                // Sub-pixel LOD culling when zoomed far out on dense 50k+ entity drawings
                if (!entity.isSelected && entity !is CadRay && entity !is CadXLine) {
                    if (maxDimPx < 0.35 && visCount > 300) continue
                    if (maxDimPx < 1.1 && visCount > 600) {
                        val sp = viewport.worldToScreen(eb.center)
                        val baseArgb = entity.colorArgb ?: layer?.colorArgb ?: 0xFFFFFFFF.toInt()
                        val col = if (!bgTheme.isDarkCanvas && (baseArgb and 0xFFFFFF) == 0xFFFFFF) {
                            Color(0xFF101923)
                        } else Color(baseArgb)
                        drawLine(col, sp, Offset(sp.x + 1.5f, sp.y + 1.5f), strokeWidth = 1.4f)
                        continue
                    }
                }

                drawCadEntity(
                    entity = entity,
                    doc = doc,
                    layer = layer,
                    viewport = viewport,
                    isDarkCanvas = bgTheme.isDarkCanvas,
                    isPreview = false,
                    spatialIndex = idx,
                    screenExtentPx = maxDimPx,
                    suppressDashing = disableDashesForSpeed
                )
            }

            // 5. Object Grips for selected entities (O(selected) instead of scanning all entities)
            for (selIdx in idx.selectedIndices) {
                val entity = entities[selIdx]
                if (!entity.isSelected) continue
                drawEntityGrips(entity, viewport)
            }
        } else {
            val visApprox = entities.size
            val disableDashesForSpeed = visApprox > 2500
            for (entity in entities) {
                val layer = exactLayerMap[entity.layerName] ?: upperLayerMap[entity.layerName.uppercase()]
                if (layer != null && (!layer.isOn || layer.isFrozen)) continue

                if (entity !is CadRay && entity !is CadXLine) {
                    val eb = GeometryEngine.boundsOf(entity, doc.blocks)
                    if (!expandedBounds.intersects(eb)) continue
                    val maxDimPx = max(eb.width, eb.height) * viewport.zoom
                    drawCadEntity(entity, doc, layer, viewport, bgTheme.isDarkCanvas, isPreview = false, spatialIndex = null, screenExtentPx = maxDimPx, suppressDashing = disableDashesForSpeed)
                } else {
                    drawCadEntity(entity, doc, layer, viewport, bgTheme.isDarkCanvas, isPreview = false, spatialIndex = null, screenExtentPx = 500.0, suppressDashing = disableDashesForSpeed)
                }
            }
            for (entity in entities) {
                if (entity.isSelected) drawEntityGrips(entity, viewport)
            }
        }

        // 6. Preview Entities (rubber-band)
        for (pEntity in preview.entities) {
            drawCadEntity(pEntity, doc, null, viewport, bgTheme.isDarkCanvas, isPreview = true, spatialIndex = null, screenExtentPx = 500.0, suppressDashing = false)
        }

        // 7. Window / Crossing Selection Rectangle
        preview.selectionRect?.let { (a, b) ->
            val sa = viewport.worldToScreen(a)
            val sb = viewport.worldToScreen(b)
            val isWindow = b.x >= a.x
            val boxColor = if (isWindow) Color(0xFF2979FF) else Color(0xFF00E676)
            val left = min(sa.x, sb.x)
            val top = min(sa.y, sb.y)
            val w = abs(sb.x - sa.x)
            val h = abs(sb.y - sa.y)
            drawRect(
                color = boxColor.copy(alpha = 0.16f),
                topLeft = Offset(left, top),
                size = Size(w, h)
            )
            drawRect(
                color = boxColor,
                topLeft = Offset(left, top),
                size = Size(w, h),
                style = Stroke(
                    width = 2.2f,
                    pathEffect = if (isWindow) null else crossingDashEffect
                )
            )
        }

        // 8. Polar / Ortho Tracking Ray
        snapResult?.trackingGuide?.let { (a, b) ->
            val sa = viewport.worldToScreen(a)
            val sb = viewport.worldToScreen(b)
            drawLine(
                color = Color(0xFF00E676).copy(alpha = 0.75f),
                start = sa,
                end = sb,
                strokeWidth = 1.6f,
                pathEffect = trackingDashEffect
            )
        }

        // 9. Dynamic Crosshair & OSNAP Marker
        val activePoint = snapResult?.point ?: cursorWorld
        activePoint?.let { wp ->
            val sp = viewport.worldToScreen(wp)
            val crossHalf = max(24f, min(size.width, size.height) * crosshairSizeRatio)
            val crossColor = if (bgTheme.isDarkCanvas) Color(0xFFECF2F8).copy(alpha = 0.55f) else Color(0xFF101923).copy(alpha = 0.55f)
            drawLine(crossColor, Offset(sp.x - crossHalf, sp.y), Offset(sp.x + crossHalf, sp.y), strokeWidth = 1.2f)
            drawLine(crossColor, Offset(sp.x, sp.y - crossHalf), Offset(sp.x, sp.y + crossHalf), strokeWidth = 1.2f)
            drawRect(
                color = crossColor,
                topLeft = Offset(sp.x - 5f, sp.y - 5f),
                size = Size(10f, 10f),
                style = Stroke(width = 1.2f)
            )

            snapResult?.osnapType?.let { osnap ->
                drawOsnapMarker(sp, osnap)
            }
        }

        // 10. Real-time measurement badge on canvas
        if (preview.measurementText != null && preview.measurementAnchor != null) {
            val sp = viewport.worldToScreen(preview.measurementAnchor)
            drawCanvasLabelBadge(
                text = preview.measurementText,
                screenPos = Offset(sp.x + 16f, sp.y - 24f),
                bgColor = 0xDD131B26.toInt(),
                textColor = 0xFF00E5FF.toInt()
            )
        }
    }

    private fun DrawScope.drawEntityGrips(entity: CadEntity, viewport: ViewportState) {
        val grips = GeometryOperations.gripsOf(entity)
        for (g in grips) {
            val sp = viewport.worldToScreen(g)
            drawRect(
                color = Color(0xFF00E5FF),
                topLeft = Offset(sp.x - 6f, sp.y - 6f),
                size = Size(12f, 12f)
            )
            drawRect(
                color = Color(0xFF080C12),
                topLeft = Offset(sp.x - 6f, sp.y - 6f),
                size = Size(12f, 12f),
                style = Stroke(width = 1.8f)
            )
        }
    }

    private fun DrawScope.drawPaperSpaceSheet(
        layout: com.example.cad.model.CadLayout,
        viewport: ViewportState,
        isDarkCanvas: Boolean
    ) {
        val hw = layout.paperWidthMm * 0.5
        val hh = layout.paperHeightMm * 0.5
        val tl = viewport.worldToScreen(Vec2(-hw, hh))
        val br = viewport.worldToScreen(Vec2(hw, -hh))
        val sheetW = br.x - tl.x
        val sheetH = br.y - tl.y
        if (sheetW <= 4f || sheetH <= 4f) return

        val sheetColor = if (isDarkCanvas) Color(0xFF121C29) else Color.White
        drawRect(color = sheetColor, topLeft = tl, size = Size(sheetW, sheetH))
        drawRect(color = Color(0xFF00E5FF).copy(alpha = 0.7f), topLeft = tl, size = Size(sheetW, sheetH), style = Stroke(2.2f))

        // Inner margin & title block
        val margin = 12f
        if (sheetW > 60f && sheetH > 60f) {
            drawRect(
                color = Color(0xFF4D627E),
                topLeft = Offset(tl.x + margin, tl.y + margin),
                size = Size(sheetW - margin * 2, sheetH - margin * 2),
                style = Stroke(1.2f)
            )
            val tbW = min(sheetW * 0.42f, 220f)
            val tbH = min(sheetH * 0.18f, 64f)
            drawRect(
                color = Color(0xFF00E5FF).copy(alpha = 0.45f),
                topLeft = Offset(br.x - margin - tbW, br.y - margin - tbH),
                size = Size(tbW, tbH),
                style = Stroke(1.5f)
            )
            drawIntoCanvas { canvas ->
                nativeTextPaint.color = if (isDarkCanvas) 0xFFECF2F8.toInt() else 0xFF101923.toInt()
                nativeTextPaint.textSize = (tbH * 0.25f).coerceIn(10f, 18f)
                canvas.nativeCanvas.drawText(
                    "${layout.name} — ${layout.paperWidthMm.toInt()}x${layout.paperHeightMm.toInt()}mm",
                    br.x - margin - tbW + 8f,
                    br.y - margin - tbH * 0.55f,
                    nativeTextPaint
                )
            }
        }
    }

    private fun DrawScope.drawAdaptiveGrid(
        viewport: ViewportState,
        bounds: Bounds2D,
        baseSpacing: Double,
        isDarkCanvas: Boolean
    ) {
        val basePx = baseSpacing * viewport.zoom
        val step = if (basePx < 16.0) {
            val factor = 10.0.pow(ceil(log10(28.0 / basePx)))
            baseSpacing * factor
        } else if (basePx > 180.0) {
            val factor = 10.0.pow(floor(log10(48.0 / basePx)))
            max(0.01, baseSpacing * factor)
        } else {
            baseSpacing
        }

        val minorColor = if (isDarkCanvas) Color(0xFF1A2535) else Color(0xFFE1E8F0)
        val majorColor = if (isDarkCanvas) Color(0xFF26364D) else Color(0xFFC8D4E2)

        val halfW = viewport.viewWidthPx * 0.5f
        val halfH = viewport.viewHeightPx * 0.5f
        val cx = viewport.centerWorld.x
        val cy = viewport.centerWorld.y
        val zoom = viewport.zoom

        val startX = floor(bounds.minX / step) * step
        val endX = ceil(bounds.maxX / step) * step
        var x = startX
        var count = 0
        while (x <= endX && count < 180) {
            val sx = (halfW + (x - cx) * zoom).toFloat()
            val isMajor = abs((x / (step * 5.0)) - kotlin.math.round(x / (step * 5.0))) < 1e-4
            drawLine(
                color = if (isMajor) majorColor else minorColor,
                start = Offset(sx, 0f),
                end = Offset(sx, size.height),
                strokeWidth = if (isMajor) 1.3f else 0.8f
            )
            x += step
            count++
        }

        val startY = floor(bounds.minY / step) * step
        val endY = ceil(bounds.maxY / step) * step
        var y = startY
        count = 0
        while (y <= endY && count < 180) {
            val sy = (halfH - (y - cy) * zoom).toFloat()
            val isMajor = abs((y / (step * 5.0)) - kotlin.math.round(y / (step * 5.0))) < 1e-4
            drawLine(
                color = if (isMajor) majorColor else minorColor,
                start = Offset(0f, sy),
                end = Offset(size.width, sy),
                strokeWidth = if (isMajor) 1.3f else 0.8f
            )
            y += step
            count++
        }
    }

    private fun DrawScope.drawAxesAndUcs(viewport: ViewportState, isDarkCanvas: Boolean) {
        val originScreen = viewport.worldToScreen(Vec2.ZERO)
        val axisAlpha = if (isDarkCanvas) 0.42f else 0.5f

        if (originScreen.y in -10f..size.height + 10f) {
            drawLine(Color(0xFFFF5252).copy(alpha = axisAlpha), Offset(0f, originScreen.y), Offset(size.width, originScreen.y), strokeWidth = 1.4f)
        }
        if (originScreen.x in -10f..size.width + 10f) {
            drawLine(Color(0xFF00E676).copy(alpha = axisAlpha), Offset(originScreen.x, 0f), Offset(originScreen.x, size.height), strokeWidth = 1.4f)
        }

        val ucsOrigin = Offset(44f, size.height - 44f)
        drawLine(Color(0xFFFF5252), ucsOrigin, Offset(ucsOrigin.x + 28f, ucsOrigin.y), strokeWidth = 2.5f)
        drawLine(Color(0xFF00E676), ucsOrigin, Offset(ucsOrigin.x, ucsOrigin.y - 28f), strokeWidth = 2.5f)
        drawRect(Color(0xFF9FB0C7), topLeft = Offset(ucsOrigin.x - 4f, ucsOrigin.y - 4f), size = Size(8f, 8f), style = Stroke(1.5f))
    }

    private fun DrawScope.drawCadEntity(
        entity: CadEntity,
        doc: CadDocument,
        layer: CadLayer?,
        viewport: ViewportState,
        isDarkCanvas: Boolean,
        isPreview: Boolean,
        spatialIndex: CadSpatialIndex?,
        screenExtentPx: Double,
        suppressDashing: Boolean
    ) {
        val baseArgb = entity.colorArgb ?: layer?.colorArgb ?: 0xFFFFFFFF.toInt()
        var color = Color(baseArgb)
        if (!isDarkCanvas && (baseArgb and 0xFFFFFF) == 0xFFFFFF) {
            color = Color(0xFF101923)
        }
        val alpha = if (isPreview) 0.85f else (1f - entity.transparency.coerceIn(0f, 0.9f))
        color = if (entity.isSelected) Color(0xFF00E5FF) else if (alpha < 0.999f) color.copy(alpha = alpha) else color

        val lw = entity.lineWeightMm ?: layer?.lineWeightMm ?: 0.25f
        val strokePx = max(1.6f, lw * 5.2f) + (if (entity.isSelected) 1.6f else 0f)
        val lt = entity.lineType ?: layer?.lineType ?: CadLineType.CONTINUOUS
        val dashEffect = when {
            isPreview -> previewDashEffect
            entity.isSelected -> selectedDashEffect
            suppressDashing -> null
            else -> lineTypeDashCache[lt]
        }

        val halfW = viewport.viewWidthPx * 0.5f
        val halfH = viewport.viewHeightPx * 0.5f
        val cxW = viewport.centerWorld.x
        val cyW = viewport.centerWorld.y
        val zoom = viewport.zoom

        when (entity) {
            is CadLine -> {
                val sx = (halfW + (entity.start.x - cxW) * zoom).toFloat()
                val sy = (halfH - (entity.start.y - cyW) * zoom).toFloat()
                val ex = (halfW + (entity.end.x - cxW) * zoom).toFloat()
                val ey = (halfH - (entity.end.y - cyW) * zoom).toFloat()
                drawLine(color, Offset(sx, sy), Offset(ex, ey), strokeWidth = strokePx, cap = StrokeCap.Round, pathEffect = dashEffect)
            }
            is CadPolyline -> if (entity.points.size >= 2) {
                scratchPath.reset()
                val pts = entity.points
                scratchPath.moveTo(
                    (halfW + (pts[0].x - cxW) * zoom).toFloat(),
                    (halfH - (pts[0].y - cyW) * zoom).toFloat()
                )
                // Vertex thinning LOD when a huge polyline spans very few pixels
                val step = if (pts.size > 128 && screenExtentPx < 40.0) max(1, pts.size / 32) else 1
                var i = step
                while (i < pts.size) {
                    val pi = pts[i]
                    scratchPath.lineTo(
                        (halfW + (pi.x - cxW) * zoom).toFloat(),
                        (halfH - (pi.y - cyW) * zoom).toFloat()
                    )
                    i += step
                }
                if (step > 1 && i - step != pts.lastIndex) {
                    val last = pts.last()
                    scratchPath.lineTo(
                        (halfW + (last.x - cxW) * zoom).toFloat(),
                        (halfH - (last.y - cyW) * zoom).toFloat()
                    )
                }
                if (entity.isClosed) scratchPath.close()
                drawPath(scratchPath, color, style = Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = dashEffect))
            }
            is CadCircle -> {
                val sc = viewport.worldToScreen(entity.center)
                val rPx = (entity.radius * zoom).toFloat()
                if (rPx > 0.5f) {
                    drawCircle(color, radius = rPx, center = sc, style = Stroke(width = strokePx, pathEffect = dashEffect))
                }
            }
            is CadArc -> {
                val rPx = entity.radius * zoom
                if (rPx < 0.5) return
                val segs = when {
                    rPx < 8.0 -> 6
                    rPx < 30.0 -> 14
                    rPx < 120.0 -> 26
                    else -> 40
                }
                val sweep = GeometryEngine.normalizeSweepDeg(entity.startAngleDeg, entity.endAngleDeg)
                val count = max(4, (segs * sweep / 360.0).toInt().coerceAtLeast(4))
                scratchPath.reset()
                for (idx in 0..count) {
                    val deg = entity.startAngleDeg + sweep * (idx.toDouble() / count)
                    val rad = Math.toRadians(deg)
                    val wx = entity.center.x + entity.radius * cos(rad)
                    val wy = entity.center.y + entity.radius * sin(rad)
                    val px = (halfW + (wx - cxW) * zoom).toFloat()
                    val py = (halfH - (wy - cyW) * zoom).toFloat()
                    if (idx == 0) scratchPath.moveTo(px, py) else scratchPath.lineTo(px, py)
                }
                drawPath(scratchPath, color, style = Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = dashEffect))
            }
            is CadEllipse -> {
                val major = entity.majorAxisEndOffset
                val majorLen = major.length()
                if (majorLen < 1e-9) return
                val segs = when {
                    screenExtentPx < 10.0 -> 8
                    screenExtentPx < 40.0 -> 18
                    screenExtentPx < 140.0 -> 32
                    else -> 54
                }
                val rotRad = major.angleRad()
                val minorLen = majorLen * entity.minorToMajorRatio
                val cRot = cos(rotRad)
                val sRot = sin(rotRad)
                var sweep = entity.endParamRad - entity.startParamRad
                if (sweep <= 0.0) sweep += 2.0 * PI
                scratchPath.reset()
                for (i in 0..segs) {
                    val t = entity.startParamRad + sweep * (i.toDouble() / segs)
                    val lx = majorLen * cos(t)
                    val ly = minorLen * sin(t)
                    val wx = entity.center.x + lx * cRot - ly * sRot
                    val wy = entity.center.y + lx * sRot + ly * cRot
                    val px = (halfW + (wx - cxW) * zoom).toFloat()
                    val py = (halfH - (wy - cyW) * zoom).toFloat()
                    if (i == 0) scratchPath.moveTo(px, py) else scratchPath.lineTo(px, py)
                }
                drawPath(scratchPath, color, style = Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = dashEffect))
            }
            is CadSpline -> {
                val subdiv = if (screenExtentPx < 40.0) 4 else if (screenExtentPx < 150.0) 8 else 12
                val pts = GeometryEngine.sampleSplinePoints(entity, subdiv)
                if (pts.size >= 2) {
                    scratchPath.reset()
                    scratchPath.moveTo(
                        (halfW + (pts[0].x - cxW) * zoom).toFloat(),
                        (halfH - (pts[0].y - cyW) * zoom).toFloat()
                    )
                    for (i in 1 until pts.size) {
                        val pi = pts[i]
                        scratchPath.lineTo(
                            (halfW + (pi.x - cxW) * zoom).toFloat(),
                            (halfH - (pi.y - cyW) * zoom).toFloat()
                        )
                    }
                    drawPath(scratchPath, color, style = Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = dashEffect))
                }
            }
            is CadPoint -> {
                val sp = viewport.worldToScreen(entity.position)
                drawLine(color, Offset(sp.x - 6f, sp.y - 6f), Offset(sp.x + 6f, sp.y + 6f), strokeWidth = 2f)
                drawLine(color, Offset(sp.x - 6f, sp.y + 6f), Offset(sp.x + 6f, sp.y - 6f), strokeWidth = 2f)
                drawCircle(color, radius = 3.5f, center = sp, style = Stroke(1.5f))
            }
            is CadRay -> {
                val end = entity.origin + entity.direction.normalized() * 50000.0
                drawLine(color, viewport.worldToScreen(entity.origin), viewport.worldToScreen(end), strokeWidth = strokePx, pathEffect = dashEffect)
            }
            is CadXLine -> {
                val dir = entity.direction.normalized() * 50000.0
                drawLine(color, viewport.worldToScreen(entity.origin - dir), viewport.worldToScreen(entity.origin + dir), strokeWidth = strokePx, pathEffect = dashEffect)
            }
            is CadText -> {
                val rawFontPx = (entity.height * zoom).toFloat()
                if (rawFontPx < 3.5f && !entity.isSelected) {
                    if (rawFontPx >= 1.2f) {
                        val sp = viewport.worldToScreen(entity.position)
                        val approxW = (entity.text.length * rawFontPx * 0.55f).coerceAtLeast(3f)
                        drawLine(color.copy(alpha = 0.7f), sp, Offset(sp.x + approxW, sp.y), strokeWidth = 1.4f)
                    }
                    return
                }
                val sp = viewport.worldToScreen(entity.position)
                val fontSizePx = rawFontPx.coerceIn(10f, 96f)
                drawIntoCanvas { canvas ->
                    nativeTextPaint.color = entity.colorArgb ?: layer?.colorArgb ?: if (isDarkCanvas) 0xFFECF2F8.toInt() else 0xFF101923.toInt()
                    if (!isDarkCanvas && (nativeTextPaint.color and 0xFFFFFF) == 0xFFFFFF) {
                        nativeTextPaint.color = 0xFF101923.toInt()
                    }
                    nativeTextPaint.textSize = fontSizePx
                    canvas.nativeCanvas.save()
                    canvas.nativeCanvas.translate(sp.x, sp.y)
                    canvas.nativeCanvas.rotate(-entity.rotationDeg.toFloat())
                    canvas.nativeCanvas.drawText(entity.text, 0f, 0f, nativeTextPaint)
                    canvas.nativeCanvas.restore()
                }
            }
            is CadBlockReference -> {
                val children = spatialIndex?.explodedBlockEntities(entity.id)
                    ?: doc.blocks[entity.blockName]?.let { GeometryEngine.explodeBlockReference(entity, it) }
                    ?: return
                for (sub in children) {
                    val target = if (entity.isSelected) sub.copyCommon(isSelected = true) else sub
                    drawCadEntity(target, doc, layer, viewport, isDarkCanvas, isPreview, spatialIndex, screenExtentPx, suppressDashing)
                }
            }
            is CadDimension -> {
                drawDimensionEntity(entity, doc, viewport, isDarkCanvas, strokePx)
            }
        }
    }

    private fun DrawScope.drawDimensionEntity(
        dim: CadDimension,
        doc: CadDocument,
        viewport: ViewportState,
        isDarkCanvas: Boolean,
        strokePx: Float
    ) {
        val style = doc.dimensionStyle
        val lineColor = if (dim.isSelected) Color(0xFF00E5FF) else Color(style.lineColorArgb)
        val textArgb = if (!isDarkCanvas && (style.textColorArgb and 0xFFFFFF) == 0xFFFFFF) 0xFFB26A00.toInt() else style.textColorArgb

        val sp1 = viewport.worldToScreen(dim.p1)
        val sp2 = viewport.worldToScreen(dim.p2)
        val sDimPt = viewport.worldToScreen(dim.dimLinePoint)

        when (dim.dimType) {
            DimensionType.LINEAR, DimensionType.CONTINUE, DimensionType.BASELINE -> {
                val isHoriz = abs(dim.dimLinePoint.y - (dim.p1.y + dim.p2.y) * 0.5) >=
                    abs(dim.dimLinePoint.x - (dim.p1.x + dim.p2.x) * 0.5)
                val d1 = if (isHoriz) Offset(sp1.x, sDimPt.y) else Offset(sDimPt.x, sp1.y)
                val d2 = if (isHoriz) Offset(sp2.x, sDimPt.y) else Offset(sDimPt.x, sp2.y)
                drawLine(lineColor.copy(alpha = 0.7f), sp1, d1, strokeWidth = strokePx * 0.7f)
                drawLine(lineColor.copy(alpha = 0.7f), sp2, d2, strokeWidth = strokePx * 0.7f)
                drawLine(lineColor, d1, d2, strokeWidth = strokePx)
                drawArrowhead(d1, d2, lineColor)
                drawArrowhead(d2, d1, lineColor)
                val mid = Offset((d1.x + d2.x) * 0.5f, (d1.y + d2.y) * 0.5f - 10f)
                drawDimValueText(dim, doc, mid, textArgb, viewport)
            }
            DimensionType.ALIGNED -> {
                val dir = (dim.p2 - dim.p1).normalized()
                val n = dir.perpendicular()
                val off = (dim.dimLinePoint - dim.p1).dot(n)
                val d1 = viewport.worldToScreen(dim.p1 + n * off)
                val d2 = viewport.worldToScreen(dim.p2 + n * off)
                drawLine(lineColor.copy(alpha = 0.7f), sp1, d1, strokeWidth = strokePx * 0.7f)
                drawLine(lineColor.copy(alpha = 0.7f), sp2, d2, strokeWidth = strokePx * 0.7f)
                drawLine(lineColor, d1, d2, strokeWidth = strokePx)
                drawArrowhead(d1, d2, lineColor)
                drawArrowhead(d2, d1, lineColor)
                val mid = Offset((d1.x + d2.x) * 0.5f, (d1.y + d2.y) * 0.5f - 10f)
                drawDimValueText(dim, doc, mid, textArgb, viewport)
            }
            DimensionType.RADIUS, DimensionType.DIAMETER -> {
                drawLine(lineColor, sp1, sp2, strokeWidth = strokePx)
                drawLine(lineColor, sp2, sDimPt, strokeWidth = strokePx)
                drawArrowhead(sp2, sp1, lineColor)
                drawDimValueText(dim, doc, Offset(sDimPt.x + 8f, sDimPt.y - 8f), textArgb, viewport)
            }
            DimensionType.ANGULAR -> {
                val sp3 = dim.p3?.let { viewport.worldToScreen(it) } ?: sDimPt
                drawLine(lineColor.copy(alpha = 0.7f), sp1, sp2, strokeWidth = strokePx * 0.75f)
                drawLine(lineColor.copy(alpha = 0.7f), sp1, sp3, strokeWidth = strokePx * 0.75f)
                drawDimValueText(dim, doc, sDimPt, textArgb, viewport)
            }
            DimensionType.ARC_LENGTH -> {
                drawLine(lineColor.copy(alpha = 0.7f), sp1, sp2, strokeWidth = strokePx * 0.7f)
                dim.p3?.let { drawLine(lineColor.copy(alpha = 0.7f), sp1, viewport.worldToScreen(it), strokeWidth = strokePx * 0.7f) }
                drawDimValueText(dim, doc, sDimPt, textArgb, viewport)
            }
            DimensionType.LEADER, DimensionType.MULTILEADER -> {
                val landing = Offset(sDimPt.x + 28f, sDimPt.y)
                drawLine(lineColor, sp1, sDimPt, strokeWidth = strokePx)
                drawLine(lineColor, sDimPt, landing, strokeWidth = strokePx)
                drawArrowhead(sp1, sDimPt, lineColor)
                val text = dim.textOverride ?: dim.leaderLabel.ifEmpty { "NOTE" }
                drawCanvasLabelBadge(text, Offset(landing.x + 4f, landing.y - 4f), 0xBB131B26.toInt(), textArgb)
            }
        }
    }

    private fun DrawScope.drawDimValueText(
        dim: CadDimension,
        doc: CadDocument,
        screenPos: Offset,
        textArgb: Int,
        viewport: ViewportState
    ) {
        val style = doc.dimensionStyle
        val valNum = GeometryEngine.computeDimensionValue(dim, style.scaleFactor)
        val label = dim.textOverride ?: CadExportEngine.formatDimText(
            dim.dimType, valNum, style.decimalPrecision, style.units.shortLabel
        )
        drawIntoCanvas { canvas ->
            nativeTextPaint.color = textArgb
            nativeTextPaint.textSize = (style.textHeight * viewport.zoom).toFloat().coerceIn(24f, 44f)
            canvas.nativeCanvas.drawText(label, screenPos.x - 24f, screenPos.y, nativeTextPaint)
        }
    }

    private fun DrawScope.drawArrowhead(tip: Offset, from: Offset, color: Color) {
        val dx = tip.x - from.x
        val dy = tip.y - from.y
        val len = kotlin.math.hypot(dx, dy)
        if (len < 1f) return
        val ux = dx / len
        val uy = dy / len
        val size = 12f
        val wing = 4.5f
        val base = Offset(tip.x - ux * size, tip.y - uy * size)
        val p1 = Offset(base.x - uy * wing, base.y + ux * wing)
        val p2 = Offset(base.x + uy * wing, base.y - ux * wing)
        scratchPath.reset()
        scratchPath.moveTo(tip.x, tip.y)
        scratchPath.lineTo(p1.x, p1.y)
        scratchPath.lineTo(p2.x, p2.y)
        scratchPath.close()
        drawPath(scratchPath, color)
    }

    private fun DrawScope.drawOsnapMarker(center: Offset, type: OsnapType) {
        val c = Color(0xFFFFB300)
        val s = 9f
        val stroke = Stroke(width = 2.4f)
        when (type) {
            OsnapType.ENDPOINT -> drawRect(c, Offset(center.x - s, center.y - s), Size(s * 2, s * 2), style = stroke)
            OsnapType.MIDPOINT -> {
                scratchPath.reset()
                scratchPath.moveTo(center.x, center.y - s - 2f)
                scratchPath.lineTo(center.x - s, center.y + s)
                scratchPath.lineTo(center.x + s, center.y + s)
                scratchPath.close()
                drawPath(scratchPath, c, style = stroke)
            }
            OsnapType.CENTER -> drawCircle(c, radius = s, center = center, style = stroke)
            OsnapType.QUADRANT -> {
                scratchPath.reset()
                scratchPath.moveTo(center.x, center.y - s - 2f)
                scratchPath.lineTo(center.x + s + 2f, center.y)
                scratchPath.lineTo(center.x, center.y + s + 2f)
                scratchPath.lineTo(center.x - s - 2f, center.y)
                scratchPath.close()
                drawPath(scratchPath, c, style = stroke)
            }
            OsnapType.INTERSECTION -> {
                drawLine(c, Offset(center.x - s, center.y - s), Offset(center.x + s, center.y + s), strokeWidth = 2.4f)
                drawLine(c, Offset(center.x - s, center.y + s), Offset(center.x + s, center.y - s), strokeWidth = 2.4f)
            }
            OsnapType.PERPENDICULAR -> {
                drawLine(c, Offset(center.x - s, center.y + s), Offset(center.x + s, center.y + s), strokeWidth = 2.4f)
                drawLine(c, Offset(center.x - s, center.y - s), Offset(center.x - s, center.y + s), strokeWidth = 2.4f)
                drawRect(c, Offset(center.x - s, center.y), Size(s, s), style = stroke)
            }
            OsnapType.TANGENT -> {
                drawCircle(c, radius = s * 0.8f, center = center, style = stroke)
                drawLine(c, Offset(center.x - s, center.y - s * 0.85f), Offset(center.x + s, center.y - s * 0.85f), strokeWidth = 2.4f)
            }
            OsnapType.NEAREST, OsnapType.EXTENSION -> {
                drawLine(c, Offset(center.x - s, center.y - s), Offset(center.x + s, center.y - s), strokeWidth = 2.2f)
                drawLine(c, Offset(center.x - s, center.y + s), Offset(center.x + s, center.y + s), strokeWidth = 2.2f)
                drawLine(c, Offset(center.x - s, center.y - s), Offset(center.x + s, center.y + s), strokeWidth = 2.2f)
                drawLine(c, Offset(center.x + s, center.y - s), Offset(center.x - s, center.y + s), strokeWidth = 2.2f)
            }
        }
    }

    private fun DrawScope.drawCanvasLabelBadge(
        text: String,
        screenPos: Offset,
        bgColor: Int,
        textColor: Int
    ) {
        drawIntoCanvas { canvas ->
            nativeTextPaint.textSize = 26f
            val width = nativeTextPaint.measureText(text)
            val padH = 12f
            val padV = 8f
            badgeBgPaint.color = bgColor
            canvas.nativeCanvas.drawRoundRect(
                screenPos.x - padH,
                screenPos.y - 26f - padV,
                screenPos.x + width + padH,
                screenPos.y + padV,
                10f, 10f, badgeBgPaint
            )
            nativeTextPaint.color = textColor
            canvas.nativeCanvas.drawText(text, screenPos.x, screenPos.y - 2f, nativeTextPaint)
        }
    }
}
