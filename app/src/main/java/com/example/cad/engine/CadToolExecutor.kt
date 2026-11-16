package com.example.cad.engine

import com.example.cad.geometry.GeometryEngine
import com.example.cad.geometry.GeometryOperations
import com.example.cad.io.CadExportEngine
import com.example.cad.model.Bounds2D
import com.example.cad.model.CadDocument
import com.example.cad.model.CadEntity
import com.example.cad.model.CadEntity.*
import com.example.cad.model.DimensionType
import com.example.cad.model.Vec2
import com.example.cad.render.PreviewGeometry
import java.util.Locale
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

data class ToolStepOutcome(
    val updatedDocument: CadDocument? = null,
    val nextSession: ToolSession,
    val promptMessage: String,
    val measurement: MeasurementReadout? = null,
    val shouldCommitHistory: Boolean = false,
    val resetToSelect: Boolean = false,
    val historyLabel: String? = null
)

object CadToolExecutor {

    fun buildPreview(
        activeTool: ActiveTool,
        session: ToolSession,
        cursorWorld: Vec2?,
        doc: CadDocument,
        config: ToolNumericConfig,
        rectSelectionDrag: Pair<Vec2, Vec2>?
    ): PreviewGeometry {
        if (rectSelectionDrag != null) {
            return PreviewGeometry(selectionRect = rectSelectionDrag)
        }
        val cur = cursorWorld ?: return PreviewGeometry()
        val pts = session.collectedPoints
        val layer = doc.currentLayerName

        return when (activeTool) {
            is ActiveTool.Draw -> when (activeTool.tool) {
                DrawTool.LINE -> if (pts.isNotEmpty()) {
                    val last = pts.last()
                    val dist = last.distanceTo(cur)
                    val ang = (cur - last).angleDeg()
                    PreviewGeometry(
                        entities = listOf(CadLine(layerName = layer, start = last, end = cur)),
                        measurementText = String.format(Locale.US, "%.2f %s  ∠%.1f°", dist, doc.units.shortLabel, ang),
                        measurementAnchor = cur
                    )
                } else PreviewGeometry()

                DrawTool.POLYLINE, DrawTool.SPLINE, DrawTool.TRIANGLE -> if (pts.isNotEmpty()) {
                    val all = pts + cur
                    val previewEnt = if (activeTool.tool == DrawTool.SPLINE && all.size >= 3) {
                        CadSpline(layerName = layer, controlPoints = all)
                    } else {
                        CadPolyline(layerName = layer, points = all, isClosed = activeTool.tool == DrawTool.TRIANGLE && all.size == 3)
                    }
                    val dist = pts.last().distanceTo(cur)
                    PreviewGeometry(
                        entities = listOf(previewEnt),
                        measurementText = String.format(Locale.US, "%.2f %s", dist, doc.units.shortLabel),
                        measurementAnchor = cur
                    )
                } else PreviewGeometry()

                DrawTool.RECTANGLE -> if (pts.size == 1) {
                    val a = pts[0]
                    val rectPts = listOf(a, Vec2(cur.x, a.y), cur, Vec2(a.x, cur.y))
                    val w = kotlin.math.abs(cur.x - a.x)
                    val h = kotlin.math.abs(cur.y - a.y)
                    PreviewGeometry(
                        entities = listOf(CadPolyline(layerName = layer, points = rectPts, isClosed = true)),
                        measurementText = String.format(Locale.US, "%.2f × %.2f %s", w, h, doc.units.shortLabel),
                        measurementAnchor = cur
                    )
                } else PreviewGeometry()

                DrawTool.CIRCLE -> if (pts.size == 1) {
                    val r = max(0.01, pts[0].distanceTo(cur))
                    PreviewGeometry(
                        entities = listOf(CadCircle(layerName = layer, center = pts[0], radius = r)),
                        measurementText = String.format(Locale.US, "R %.2f %s", r, doc.units.shortLabel),
                        measurementAnchor = cur
                    )
                } else PreviewGeometry()

                DrawTool.POLYGON -> if (pts.size == 1) {
                    val poly = buildRegularPolygon(pts[0], cur, config.polygonSides, layer)
                    val r = pts[0].distanceTo(cur)
                    PreviewGeometry(
                        entities = listOf(poly),
                        measurementText = String.format(Locale.US, "%d sides  R %.2f", config.polygonSides, r),
                        measurementAnchor = cur
                    )
                } else PreviewGeometry()

                DrawTool.ARC -> when (pts.size) {
                    1 -> PreviewGeometry(
                        entities = listOf(CadLine(layerName = layer, start = pts[0], end = cur)),
                        measurementText = String.format(Locale.US, "R %.2f", pts[0].distanceTo(cur)),
                        measurementAnchor = cur
                    )
                    2 -> {
                        val r = max(0.1, pts[0].distanceTo(pts[1]))
                        val a1 = (pts[1] - pts[0]).angleDeg()
                        val a2 = (cur - pts[0]).angleDeg()
                        PreviewGeometry(
                            entities = listOf(CadArc(layerName = layer, center = pts[0], radius = r, startAngleDeg = a1, endAngleDeg = a2)),
                            measurementText = String.format(Locale.US, "Sweep %.1f°", GeometryEngine.normalizeSweepDeg(a1, a2)),
                            measurementAnchor = cur
                        )
                    }
                    else -> PreviewGeometry()
                }

                DrawTool.ELLIPSE -> when (pts.size) {
                    1 -> PreviewGeometry(
                        entities = listOf(CadLine(layerName = layer, start = pts[0], end = cur)),
                        measurementText = String.format(Locale.US, "Major R %.2f", pts[0].distanceTo(cur)),
                        measurementAnchor = cur
                    )
                    2 -> {
                        val majorOffset = pts[1] - pts[0]
                        val majorLen = max(0.1, majorOffset.length())
                        val minorLen = max(0.1, pts[0].distanceTo(cur))
                        val ratio = (minorLen / majorLen).coerceIn(0.05, 1.0)
                        PreviewGeometry(
                            entities = listOf(CadEllipse(layerName = layer, center = pts[0], majorAxisEndOffset = majorOffset, minorToMajorRatio = ratio)),
                            measurementText = String.format(Locale.US, "Ratio %.2f", ratio),
                            measurementAnchor = cur
                        )
                    }
                    else -> PreviewGeometry()
                }

                DrawTool.RAY -> if (pts.size == 1) {
                    PreviewGeometry(entities = listOf(CadRay(layerName = layer, origin = pts[0], direction = (cur - pts[0]).normalized())))
                } else PreviewGeometry()

                DrawTool.XLINE -> if (pts.size == 1) {
                    PreviewGeometry(entities = listOf(CadXLine(layerName = layer, origin = pts[0], direction = (cur - pts[0]).normalized())))
                } else PreviewGeometry()

                DrawTool.POINT -> PreviewGeometry()
            }

            is ActiveTool.Modify -> when (activeTool.tool) {
                ModifyTool.MOVE, ModifyTool.COPY -> if (pts.size == 1) {
                    val delta = cur - pts[0]
                    val ghosts = doc.entities.filter { it.isSelected }.map { GeometryOperations.translate(it, delta, newId = true) }
                    PreviewGeometry(
                        entities = ghosts,
                        measurementText = String.format(Locale.US, "Δ %.2f", delta.length()),
                        measurementAnchor = cur
                    )
                } else PreviewGeometry()

                ModifyTool.MIRROR -> if (pts.size == 1) {
                    val ghosts = doc.entities.filter { it.isSelected }.map { GeometryOperations.mirror(it, pts[0], cur, newId = true) }
                    PreviewGeometry(entities = ghosts + CadLine(layerName = "CONSTRUCTION", start = pts[0], end = cur))
                } else PreviewGeometry()

                else -> PreviewGeometry()
            }

            is ActiveTool.Dimension -> if (pts.size == 2 && (activeTool.type == DimensionType.LINEAR || activeTool.type == DimensionType.ALIGNED)) {
                val previewDim = CadDimension(
                    layerName = "DIMENSIONS",
                    dimType = activeTool.type,
                    p1 = pts[0],
                    p2 = pts[1],
                    dimLinePoint = cur
                )
                PreviewGeometry(entities = listOf(previewDim))
            } else PreviewGeometry()

            is ActiveTool.Measure -> when (activeTool.tool) {
                MeasureTool.DISTANCE -> if (pts.size == 1) {
                    val d = pts[0].distanceTo(cur)
                    PreviewGeometry(
                        entities = listOf(CadLine(layerName = "CONSTRUCTION", start = pts[0], end = cur)),
                        measurementText = String.format(Locale.US, "%.3f %s", d, doc.units.shortLabel),
                        measurementAnchor = cur
                    )
                } else PreviewGeometry()
                MeasureTool.AREA, MeasureTool.PERIMETER -> if (pts.isNotEmpty()) {
                    PreviewGeometry(entities = listOf(CadPolyline(layerName = "CONSTRUCTION", points = pts + cur, isClosed = true)))
                } else PreviewGeometry()
                else -> PreviewGeometry()
            }

            else -> PreviewGeometry()
        }
    }

    fun handlePointTap(
        activeTool: ActiveTool,
        point: Vec2,
        doc: CadDocument,
        session: ToolSession,
        config: ToolNumericConfig,
        hitToleranceWorld: Double
    ): ToolStepOutcome {
        val visibleLayers = doc.layers.filter { it.isOn && !it.isFrozen }.map { it.name }.toSet()
        val lockedLayers = doc.layers.filter { it.isLocked }.map { it.name }.toSet()

        val rawOutcome = when (activeTool) {
            is ActiveTool.Draw -> handleDrawTap(activeTool.tool, point, doc, session, config)
            is ActiveTool.Modify -> handleModifyTap(activeTool.tool, point, doc, session, config, visibleLayers, lockedLayers, hitToleranceWorld)
            is ActiveTool.Dimension -> handleDimensionTap(activeTool.type, point, doc, session, visibleLayers, hitToleranceWorld)
            is ActiveTool.Measure -> handleMeasureTap(activeTool.tool, point, doc, session, visibleLayers, hitToleranceWorld)
            else -> ToolStepOutcome(nextSession = session, promptMessage = "Ready")
        }
        return if (rawOutcome.shouldCommitHistory && rawOutcome.historyLabel == null) {
            rawOutcome.copy(historyLabel = activeTool.displayName)
        } else {
            rawOutcome
        }
    }

    private fun handleDrawTap(
        tool: DrawTool,
        pt: Vec2,
        doc: CadDocument,
        session: ToolSession,
        config: ToolNumericConfig
    ): ToolStepOutcome {
        val layer = doc.currentLayerName
        if (doc.layerByName(layer).isLocked) {
            return ToolStepOutcome(nextSession = session, promptMessage = "Current layer '$layer' is locked. Unlock or switch layer.")
        }
        val pts = session.collectedPoints

        return when (tool) {
            DrawTool.POINT -> {
                val ent = CadPoint(layerName = layer, position = pt)
                ToolStepOutcome(
                    updatedDocument = doc.copy(entities = doc.entities + ent, isDirty = true),
                    nextSession = ToolSession(),
                    promptMessage = "POINT created at (${fmt(pt.x)}, ${fmt(pt.y)})",
                    shouldCommitHistory = true
                )
            }
            DrawTool.LINE -> {
                if (pts.isEmpty()) {
                    ToolStepOutcome(nextSession = session.copy(collectedPoints = listOf(pt)), promptMessage = "LINE: Specify next point (or tap Done)")
                } else {
                    val line = CadLine(layerName = layer, start = pts.last(), end = pt)
                    ToolStepOutcome(
                        updatedDocument = doc.copy(entities = doc.entities + line, isDirty = true),
                        nextSession = session.copy(collectedPoints = listOf(pt)),
                        promptMessage = "LINE: Specify next point (Length ${fmt(line.start.distanceTo(line.end))} ${doc.units.shortLabel}) or Done",
                        shouldCommitHistory = true
                    )
                }
            }
            DrawTool.POLYLINE -> {
                val nextPts = pts + pt
                ToolStepOutcome(
                    nextSession = session.copy(collectedPoints = nextPts),
                    promptMessage = "PLINE (${nextPts.size} pts): Specify next vertex, Close, or Done"
                )
            }
            DrawTool.SPLINE -> {
                val nextPts = pts + pt
                ToolStepOutcome(
                    nextSession = session.copy(collectedPoints = nextPts),
                    promptMessage = "SPLINE (${nextPts.size} pts): Specify next control point or tap Done"
                )
            }
            DrawTool.TRIANGLE -> {
                val nextPts = pts + pt
                if (nextPts.size < 3) {
                    ToolStepOutcome(
                        nextSession = session.copy(collectedPoints = nextPts),
                        promptMessage = "TRIANGLE: Specify vertex ${nextPts.size + 1} of 3"
                    )
                } else {
                    val tri = CadPolyline(layerName = layer, points = nextPts, isClosed = true)
                    ToolStepOutcome(
                        updatedDocument = doc.copy(entities = doc.entities + tri, isDirty = true),
                        nextSession = ToolSession(),
                        promptMessage = "TRIANGLE created",
                        shouldCommitHistory = true
                    )
                }
            }
            DrawTool.RECTANGLE -> {
                if (pts.isEmpty()) {
                    ToolStepOutcome(nextSession = session.copy(collectedPoints = listOf(pt)), promptMessage = "RECTANGLE: Specify opposite corner")
                } else {
                    val a = pts[0]
                    val rect = CadPolyline(
                        layerName = layer,
                        points = listOf(a, Vec2(pt.x, a.y), pt, Vec2(a.x, pt.y)),
                        isClosed = true
                    )
                    ToolStepOutcome(
                        updatedDocument = doc.copy(entities = doc.entities + rect, isDirty = true),
                        nextSession = ToolSession(),
                        promptMessage = "RECTANGLE created (${fmt(kotlin.math.abs(pt.x - a.x))} × ${fmt(kotlin.math.abs(pt.y - a.y))})",
                        shouldCommitHistory = true
                    )
                }
            }
            DrawTool.CIRCLE -> {
                if (pts.isEmpty()) {
                    ToolStepOutcome(nextSession = session.copy(collectedPoints = listOf(pt)), promptMessage = "CIRCLE: Specify radius point or enter radius value")
                } else {
                    val r = max(0.05, pts[0].distanceTo(pt))
                    val circle = CadCircle(layerName = layer, center = pts[0], radius = r)
                    ToolStepOutcome(
                        updatedDocument = doc.copy(entities = doc.entities + circle, isDirty = true),
                        nextSession = ToolSession(),
                        promptMessage = "CIRCLE created (R=${fmt(r)}, D=${fmt(r * 2)})",
                        shouldCommitHistory = true
                    )
                }
            }
            DrawTool.POLYGON -> {
                if (pts.isEmpty()) {
                    ToolStepOutcome(nextSession = session.copy(collectedPoints = listOf(pt)), promptMessage = "POLYGON (${config.polygonSides} sides): Specify vertex radius point")
                } else {
                    val poly = buildRegularPolygon(pts[0], pt, config.polygonSides, layer)
                    ToolStepOutcome(
                        updatedDocument = doc.copy(entities = doc.entities + poly, isDirty = true),
                        nextSession = ToolSession(),
                        promptMessage = "POLYGON (${config.polygonSides} sides) created",
                        shouldCommitHistory = true
                    )
                }
            }
            DrawTool.ARC -> when (pts.size) {
                0 -> ToolStepOutcome(nextSession = session.copy(collectedPoints = listOf(pt)), promptMessage = "ARC: Specify start point on arc")
                1 -> ToolStepOutcome(nextSession = session.copy(collectedPoints = pts + pt), promptMessage = "ARC: Specify end angle point")
                else -> {
                    val center = pts[0]
                    val r = max(0.1, center.distanceTo(pts[1]))
                    val a1 = (pts[1] - center).angleDeg()
                    val a2 = (pt - center).angleDeg()
                    val arc = CadArc(layerName = layer, center = center, radius = r, startAngleDeg = a1, endAngleDeg = a2)
                    ToolStepOutcome(
                        updatedDocument = doc.copy(entities = doc.entities + arc, isDirty = true),
                        nextSession = ToolSession(),
                        promptMessage = "ARC created (R=${fmt(r)})",
                        shouldCommitHistory = true
                    )
                }
            }
            DrawTool.ELLIPSE -> when (pts.size) {
                0 -> ToolStepOutcome(nextSession = session.copy(collectedPoints = listOf(pt)), promptMessage = "ELLIPSE: Specify major axis endpoint")
                1 -> ToolStepOutcome(nextSession = session.copy(collectedPoints = pts + pt), promptMessage = "ELLIPSE: Specify minor axis distance point")
                else -> {
                    val majorOff = pts[1] - pts[0]
                    val majorLen = max(0.1, majorOff.length())
                    val minorLen = max(0.1, pts[0].distanceTo(pt))
                    val ratio = (minorLen / majorLen).coerceIn(0.05, 1.0)
                    val ell = CadEllipse(layerName = layer, center = pts[0], majorAxisEndOffset = majorOff, minorToMajorRatio = ratio)
                    ToolStepOutcome(
                        updatedDocument = doc.copy(entities = doc.entities + ell, isDirty = true),
                        nextSession = ToolSession(),
                        promptMessage = "ELLIPSE created",
                        shouldCommitHistory = true
                    )
                }
            }
            DrawTool.RAY -> if (pts.isEmpty()) {
                ToolStepOutcome(nextSession = session.copy(collectedPoints = listOf(pt)), promptMessage = "RAY: Specify direction point")
            } else {
                val ray = CadRay(layerName = "CONSTRUCTION", origin = pts[0], direction = (pt - pts[0]).normalized())
                ToolStepOutcome(
                    updatedDocument = doc.copy(entities = doc.entities + ray, isDirty = true),
                    nextSession = ToolSession(),
                    promptMessage = "RAY created",
                    shouldCommitHistory = true
                )
            }
            DrawTool.XLINE -> if (pts.isEmpty()) {
                ToolStepOutcome(nextSession = session.copy(collectedPoints = listOf(pt)), promptMessage = "XLINE: Specify through point")
            } else {
                val xl = CadXLine(layerName = "CONSTRUCTION", origin = pts[0], direction = (pt - pts[0]).normalized())
                ToolStepOutcome(
                    updatedDocument = doc.copy(entities = doc.entities + xl, isDirty = true),
                    nextSession = ToolSession(),
                    promptMessage = "XLINE created",
                    shouldCommitHistory = true
                )
            }
        }
    }

    private fun handleModifyTap(
        tool: ModifyTool,
        pt: Vec2,
        doc: CadDocument,
        session: ToolSession,
        config: ToolNumericConfig,
        visibleLayers: Set<String>,
        lockedLayers: Set<String>,
        tol: Double
    ): ToolStepOutcome {
        val selected = doc.entities.filter { it.isSelected && it.layerName !in lockedLayers }
        val pts = session.collectedPoints

        return when (tool) {
            ModifyTool.MOVE -> {
                if (selected.isEmpty()) return ToolStepOutcome(nextSession = session, promptMessage = "MOVE: Select objects first, then tap base point")
                if (pts.isEmpty()) {
                    ToolStepOutcome(nextSession = session.copy(collectedPoints = listOf(pt)), promptMessage = "MOVE: Specify destination point")
                } else {
                    val delta = pt - pts[0]
                    val movedIds = selected.map { it.id }.toSet()
                    val updated = doc.entities.map { if (it.id in movedIds) GeometryOperations.translate(it, delta) else it }
                    val synced = GeometryOperations.updateAssociativeDimensions(updated)
                    ToolStepOutcome(
                        updatedDocument = doc.copy(entities = synced, isDirty = true),
                        nextSession = ToolSession(),
                        promptMessage = "Moved ${selected.size} object(s) by (${fmt(delta.x)}, ${fmt(delta.y)})",
                        shouldCommitHistory = true,
                        resetToSelect = true
                    )
                }
            }
            ModifyTool.COPY -> {
                if (selected.isEmpty()) return ToolStepOutcome(nextSession = session, promptMessage = "COPY: Select objects first, then tap base point")
                if (pts.isEmpty()) {
                    ToolStepOutcome(nextSession = session.copy(collectedPoints = listOf(pt)), promptMessage = "COPY: Specify displacement point (or tap Done)")
                } else {
                    val delta = pt - pts[0]
                    val copies = selected.map { GeometryOperations.translate(it, delta, newId = true).copyCommon(isSelected = false) }
                    ToolStepOutcome(
                        updatedDocument = doc.copy(entities = doc.entities + copies, isDirty = true),
                        nextSession = session,
                        promptMessage = "Copied ${copies.size} object(s). Tap another destination or Done",
                        shouldCommitHistory = true
                    )
                }
            }
            ModifyTool.ROTATE -> {
                if (selected.isEmpty()) return ToolStepOutcome(nextSession = session, promptMessage = "ROTATE: Select objects first")
                val rotIds = selected.map { it.id }.toSet()
                val updated = doc.entities.map { if (it.id in rotIds) GeometryOperations.rotate(it, pt, config.rotateAngleDeg) else it }
                val synced = GeometryOperations.updateAssociativeDimensions(updated)
                ToolStepOutcome(
                    updatedDocument = doc.copy(entities = synced, isDirty = true),
                    nextSession = ToolSession(),
                    promptMessage = "Rotated ${selected.size} object(s) by ${fmt(config.rotateAngleDeg)}°",
                    shouldCommitHistory = true,
                    resetToSelect = true
                )
            }
            ModifyTool.SCALE -> {
                if (selected.isEmpty()) return ToolStepOutcome(nextSession = session, promptMessage = "SCALE: Select objects first")
                val scaleIds = selected.map { it.id }.toSet()
                val updated = doc.entities.map { if (it.id in scaleIds) GeometryOperations.scale(it, pt, config.scaleFactor) else it }
                val synced = GeometryOperations.updateAssociativeDimensions(updated)
                ToolStepOutcome(
                    updatedDocument = doc.copy(entities = synced, isDirty = true),
                    nextSession = ToolSession(),
                    promptMessage = "Scaled ${selected.size} object(s) by ${fmt(config.scaleFactor)}x",
                    shouldCommitHistory = true,
                    resetToSelect = true
                )
            }
            ModifyTool.MIRROR -> {
                if (selected.isEmpty()) return ToolStepOutcome(nextSession = session, promptMessage = "MIRROR: Select objects first")
                if (pts.isEmpty()) {
                    ToolStepOutcome(nextSession = session.copy(collectedPoints = listOf(pt)), promptMessage = "MIRROR: Specify second point of mirror line")
                } else {
                    val mirrored = selected.map { GeometryOperations.mirror(it, pts[0], pt, newId = true).copyCommon(isSelected = false) }
                    ToolStepOutcome(
                        updatedDocument = doc.copy(entities = doc.entities + mirrored, isDirty = true),
                        nextSession = ToolSession(),
                        promptMessage = "Mirrored ${mirrored.size} object(s)",
                        shouldCommitHistory = true,
                        resetToSelect = true
                    )
                }
            }
            ModifyTool.OFFSET -> {
                if (session.targetEntityIds.isEmpty()) {
                    val hit = GeometryEngine.hitTest(pt, doc.entities, tol, doc.blocks, visibleLayers)
                        ?: return ToolStepOutcome(nextSession = session, promptMessage = "OFFSET: No entity found at tap point")
                    ToolStepOutcome(
                        nextSession = session.copy(targetEntityIds = listOf(hit.id)),
                        promptMessage = "OFFSET (${fmt(config.offsetDistance)}): Tap side to create offset copy"
                    )
                } else {
                    val target = doc.entities.firstOrNull { it.id == session.targetEntityIds.first() }
                        ?: return ToolStepOutcome(nextSession = ToolSession(), promptMessage = "OFFSET: Target missing")
                    val off = GeometryOperations.offset(target, config.offsetDistance, pt)
                        ?: return ToolStepOutcome(nextSession = ToolSession(), promptMessage = "OFFSET: Cannot offset this entity type")
                    ToolStepOutcome(
                        updatedDocument = doc.copy(entities = doc.entities + off, isDirty = true),
                        nextSession = ToolSession(),
                        promptMessage = "Offset created (${fmt(config.offsetDistance)} ${doc.units.shortLabel})",
                        shouldCommitHistory = true
                    )
                }
            }
            ModifyTool.TRIM -> {
                val hit = GeometryEngine.hitTest(pt, doc.entities, tol, doc.blocks, visibleLayers)
                    ?: return ToolStepOutcome(nextSession = session, promptMessage = "TRIM: Tap a line segment to trim")
                if (hit.layerName in lockedLayers) return ToolStepOutcome(nextSession = session, promptMessage = "TRIM: Layer is locked")
                val trimmed = GeometryOperations.trimEntity(hit, pt, doc.entities)
                    ?: return ToolStepOutcome(nextSession = session, promptMessage = "TRIM: No intersecting cutting edges found on this segment")
                val updated = doc.entities.flatMap { if (it.id == hit.id) trimmed else listOf(it) }
                ToolStepOutcome(
                    updatedDocument = doc.copy(entities = updated, isDirty = true),
                    nextSession = session,
                    promptMessage = "Trimmed segment",
                    shouldCommitHistory = true
                )
            }
            ModifyTool.EXTEND -> {
                val hit = GeometryEngine.hitTest(pt, doc.entities, tol, doc.blocks, visibleLayers)
                    ?: return ToolStepOutcome(nextSession = session, promptMessage = "EXTEND: Tap near end of line to extend")
                if (hit !is CadLine) return ToolStepOutcome(nextSession = session, promptMessage = "EXTEND: Select a LINE entity to extend")
                val extended = GeometryOperations.extendEntity(hit, pt, doc.entities)
                    ?: return ToolStepOutcome(nextSession = session, promptMessage = "EXTEND: No boundary edge found in extension path")
                val updated = doc.entities.map { if (it.id == hit.id) extended else it }
                ToolStepOutcome(
                    updatedDocument = doc.copy(entities = updated, isDirty = true),
                    nextSession = session,
                    promptMessage = "Extended line to boundary",
                    shouldCommitHistory = true
                )
            }
            ModifyTool.STRETCH -> {
                when {
                    session.stretchWindow == null && pts.isEmpty() ->
                        ToolStepOutcome(nextSession = session.copy(collectedPoints = listOf(pt)), promptMessage = "STRETCH: Specify opposite corner of stretch window")
                    session.stretchWindow == null && pts.size == 1 -> {
                        val a = pts[0]
                        val win = Bounds2D(min(a.x, pt.x), min(a.y, pt.y), max(a.x, pt.x), max(a.y, pt.y))
                        ToolStepOutcome(
                            nextSession = session.copy(collectedPoints = emptyList(), stretchWindow = a to pt),
                            promptMessage = "STRETCH: Specify base point for displacement"
                        )
                    }
                    session.stretchWindow != null && pts.isEmpty() ->
                        ToolStepOutcome(nextSession = session.copy(collectedPoints = listOf(pt)), promptMessage = "STRETCH: Specify destination point")
                    else -> {
                        val (wa, wb) = session.stretchWindow!!
                        val win = Bounds2D(min(wa.x, wb.x), min(wa.y, wb.y), max(wa.x, wb.x), max(wa.y, wb.y))
                        val delta = pt - pts[0]
                        val updated = doc.entities.map { if (it.layerName !in lockedLayers) GeometryOperations.stretch(it, win, delta) else it }
                        val synced = GeometryOperations.updateAssociativeDimensions(updated)
                        ToolStepOutcome(
                            updatedDocument = doc.copy(entities = synced, isDirty = true),
                            nextSession = ToolSession(),
                            promptMessage = "Stretched vertices inside window",
                            shouldCommitHistory = true,
                            resetToSelect = true
                        )
                    }
                }
            }
            ModifyTool.FILLET -> {
                val hit = GeometryEngine.hitTest(pt, doc.entities, tol, doc.blocks, visibleLayers)
                if (hit !is CadLine) return ToolStepOutcome(nextSession = session, promptMessage = "FILLET: Tap a LINE entity")
                if (session.targetEntityIds.isEmpty()) {
                    ToolStepOutcome(nextSession = session.copy(targetEntityIds = listOf(hit.id)), promptMessage = "FILLET (R=${fmt(config.filletRadius)}): Tap second line")
                } else {
                    val l1 = doc.entities.firstOrNull { it.id == session.targetEntityIds.first() } as? CadLine
                        ?: return ToolStepOutcome(nextSession = ToolSession(), promptMessage = "FILLET: First line missing")
                    if (l1.id == hit.id) return ToolStepOutcome(nextSession = session, promptMessage = "FILLET: Tap a different second line")
                    val res = GeometryOperations.filletLines(l1, hit, config.filletRadius)
                        ?: return ToolStepOutcome(nextSession = ToolSession(), promptMessage = "FILLET: Lines are parallel or invalid radius")
                    val (nl1, nl2, arc) = res
                    val updated = doc.entities.map {
                        when (it.id) {
                            l1.id -> nl1
                            hit.id -> nl2
                            else -> it
                        }
                    } + listOfNotNull(arc)
                    ToolStepOutcome(
                        updatedDocument = doc.copy(entities = updated, isDirty = true),
                        nextSession = ToolSession(),
                        promptMessage = "Fillet applied (R=${fmt(config.filletRadius)})",
                        shouldCommitHistory = true
                    )
                }
            }
            ModifyTool.CHAMFER -> {
                val hit = GeometryEngine.hitTest(pt, doc.entities, tol, doc.blocks, visibleLayers)
                if (hit !is CadLine) return ToolStepOutcome(nextSession = session, promptMessage = "CHAMFER: Tap a LINE entity")
                if (session.targetEntityIds.isEmpty()) {
                    ToolStepOutcome(nextSession = session.copy(targetEntityIds = listOf(hit.id)), promptMessage = "CHAMFER (${fmt(config.chamferDist1)}×${fmt(config.chamferDist2)}): Tap second line")
                } else {
                    val l1 = doc.entities.firstOrNull { it.id == session.targetEntityIds.first() } as? CadLine
                        ?: return ToolStepOutcome(nextSession = ToolSession(), promptMessage = "CHAMFER: First line missing")
                    val res = GeometryOperations.chamferLines(l1, hit, config.chamferDist1, config.chamferDist2)
                        ?: return ToolStepOutcome(nextSession = ToolSession(), promptMessage = "CHAMFER: Lines are parallel")
                    val (nl1, nl2, bevel) = res
                    val updated = doc.entities.map {
                        when (it.id) {
                            l1.id -> nl1
                            hit.id -> nl2
                            else -> it
                        }
                    } + bevel
                    ToolStepOutcome(
                        updatedDocument = doc.copy(entities = updated, isDirty = true),
                        nextSession = ToolSession(),
                        promptMessage = "Chamfer applied",
                        shouldCommitHistory = true
                    )
                }
            }
            ModifyTool.ARRAY -> {
                if (selected.isEmpty()) return ToolStepOutcome(nextSession = session, promptMessage = "ARRAY: Select objects first")
                val added = if (config.arrayPolarMode) {
                    GeometryOperations.arrayPolar(selected, pt, config.arrayPolarCount, config.arrayPolarAngleDeg)
                } else {
                    GeometryOperations.arrayRectangular(selected, config.arrayRows, config.arrayCols, config.arrayRowSpacing, config.arrayColSpacing)
                }
                ToolStepOutcome(
                    updatedDocument = doc.copy(entities = doc.entities + added, isDirty = true),
                    nextSession = ToolSession(),
                    promptMessage = "Created ${added.size} arrayed object(s)",
                    shouldCommitHistory = true,
                    resetToSelect = true
                )
            }
            ModifyTool.BREAK -> {
                val hit = GeometryEngine.hitTest(pt, doc.entities, tol, doc.blocks, visibleLayers)
                    ?: return ToolStepOutcome(nextSession = session, promptMessage = "BREAK: Tap entity to split at point")
                val parts = GeometryOperations.breakEntity(hit, pt)
                    ?: return ToolStepOutcome(nextSession = session, promptMessage = "BREAK: Cannot break this entity at selected location")
                val updated = doc.entities.flatMap { if (it.id == hit.id) parts else listOf(it) }
                ToolStepOutcome(
                    updatedDocument = doc.copy(entities = updated, isDirty = true),
                    nextSession = ToolSession(),
                    promptMessage = "Entity broken into ${parts.size} part(s)",
                    shouldCommitHistory = true
                )
            }
            ModifyTool.ERASE, ModifyTool.DELETE -> {
                val hit = GeometryEngine.hitTest(pt, doc.entities, tol, doc.blocks, visibleLayers)
                    ?: return ToolStepOutcome(nextSession = session, promptMessage = "ERASE: Tap entity to delete")
                if (hit.layerName in lockedLayers) return ToolStepOutcome(nextSession = session, promptMessage = "ERASE: Entity is on locked layer")
                ToolStepOutcome(
                    updatedDocument = doc.copy(entities = doc.entities.filterNot { it.id == hit.id }, isDirty = true),
                    nextSession = session,
                    promptMessage = "Erased 1 entity. Tap another or Done",
                    shouldCommitHistory = true
                )
            }
            else -> ToolStepOutcome(nextSession = session, promptMessage = "Ready")
        }
    }

    private fun handleDimensionTap(
        type: DimensionType,
        pt: Vec2,
        doc: CadDocument,
        session: ToolSession,
        visibleLayers: Set<String>,
        tol: Double
    ): ToolStepOutcome {
        val pts = session.collectedPoints
        return when (type) {
            DimensionType.LINEAR, DimensionType.ALIGNED -> when (pts.size) {
                0 -> ToolStepOutcome(nextSession = session.copy(collectedPoints = listOf(pt)), promptMessage = "${type.commandName}: Specify second extension origin")
                1 -> ToolStepOutcome(nextSession = session.copy(collectedPoints = pts + pt), promptMessage = "${type.commandName}: Tap dimension line placement")
                else -> {
                    val dim = CadDimension(layerName = "DIMENSIONS", dimType = type, p1 = pts[0], p2 = pts[1], dimLinePoint = pt)
                    val v = GeometryEngine.computeDimensionValue(dim, doc.dimensionStyle.scaleFactor)
                    val txt = CadExportEngine.formatDimText(type, v, doc.dimensionStyle.decimalPrecision, doc.dimensionStyle.units.shortLabel)
                    ToolStepOutcome(
                        updatedDocument = doc.copy(entities = doc.entities + dim, isDirty = true),
                        nextSession = ToolSession(lastBaselineOrigin = pts[0], lastContinueEnd = pts[1]),
                        promptMessage = "${type.commandName}: Placed $txt",
                        shouldCommitHistory = true
                    )
                }
            }
            DimensionType.CONTINUE -> {
                val prevEnd = session.lastContinueEnd ?: doc.entities.filterIsInstance<CadDimension>().lastOrNull()?.p2
                if (prevEnd == null) {
                    ToolStepOutcome(nextSession = session.copy(collectedPoints = listOf(pt)), promptMessage = "DIMCONTINUE: No prior dimension; specify next point")
                } else {
                    val dimLine = doc.entities.filterIsInstance<CadDimension>().lastOrNull()?.dimLinePoint ?: (pt + Vec2(0.0, 10.0))
                    val dim = CadDimension(layerName = "DIMENSIONS", dimType = DimensionType.CONTINUE, p1 = prevEnd, p2 = pt, dimLinePoint = dimLine)
                    ToolStepOutcome(
                        updatedDocument = doc.copy(entities = doc.entities + dim, isDirty = true),
                        nextSession = session.copy(lastContinueEnd = pt),
                        promptMessage = "DIMCONTINUE: Added chained dimension",
                        shouldCommitHistory = true
                    )
                }
            }
            DimensionType.BASELINE -> {
                val baseOrigin = session.lastBaselineOrigin ?: doc.entities.filterIsInstance<CadDimension>().lastOrNull()?.p1
                if (baseOrigin == null) {
                    ToolStepOutcome(nextSession = session.copy(collectedPoints = listOf(pt)), promptMessage = "DIMBASELINE: Specify base origin")
                } else {
                    val offsetCount = doc.entities.filterIsInstance<CadDimension>().count { it.dimType == DimensionType.BASELINE } + 1
                    val dim = CadDimension(layerName = "DIMENSIONS", dimType = DimensionType.BASELINE, p1 = baseOrigin, p2 = pt, dimLinePoint = pt + Vec2(0.0, 12.0 * offsetCount))
                    ToolStepOutcome(
                        updatedDocument = doc.copy(entities = doc.entities + dim, isDirty = true),
                        nextSession = session.copy(lastBaselineOrigin = baseOrigin),
                        promptMessage = "DIMBASELINE: Added baseline dimension",
                        shouldCommitHistory = true
                    )
                }
            }
            DimensionType.ANGULAR -> when (pts.size) {
                0 -> ToolStepOutcome(nextSession = session.copy(collectedPoints = listOf(pt)), promptMessage = "DIMANGULAR: Specify first leg point")
                1 -> ToolStepOutcome(nextSession = session.copy(collectedPoints = pts + pt), promptMessage = "DIMANGULAR: Specify second leg point")
                else -> {
                    val dim = CadDimension(layerName = "DIMENSIONS", dimType = DimensionType.ANGULAR, p1 = pts[0], p2 = pts[1], p3 = pt, dimLinePoint = (pts[1] + pt) * 0.5)
                    ToolStepOutcome(
                        updatedDocument = doc.copy(entities = doc.entities + dim, isDirty = true),
                        nextSession = ToolSession(),
                        promptMessage = "DIMANGULAR placed",
                        shouldCommitHistory = true
                    )
                }
            }
            DimensionType.RADIUS, DimensionType.DIAMETER, DimensionType.ARC_LENGTH -> {
                val hit = GeometryEngine.hitTest(pt, doc.entities, tol, doc.blocks, visibleLayers)
                val dim = when {
                    hit is CadCircle && (type == DimensionType.RADIUS || type == DimensionType.DIAMETER) -> {
                        val dir = (pt - hit.center).normalized()
                        CadDimension(
                            layerName = "DIMENSIONS",
                            dimType = type,
                            p1 = hit.center,
                            p2 = hit.center + dir * hit.radius,
                            dimLinePoint = hit.center + dir * (hit.radius + 14.0),
                            associatedEntityIds = listOf(hit.id)
                        )
                    }
                    hit is CadArc -> {
                        if (type == DimensionType.ARC_LENGTH) {
                            val pStart = Vec2.fromPolar(hit.center, hit.radius, hit.startAngleDeg)
                            val pEnd = Vec2.fromPolar(hit.center, hit.radius, hit.endAngleDeg)
                            CadDimension(
                                layerName = "DIMENSIONS",
                                dimType = type,
                                p1 = hit.center,
                                p2 = pStart,
                                p3 = pEnd,
                                dimLinePoint = pt,
                                associatedEntityIds = listOf(hit.id)
                            )
                        } else {
                            val dir = (pt - hit.center).normalized()
                            CadDimension(
                                layerName = "DIMENSIONS",
                                dimType = type,
                                p1 = hit.center,
                                p2 = hit.center + dir * hit.radius,
                                dimLinePoint = hit.center + dir * (hit.radius + 14.0),
                                associatedEntityIds = listOf(hit.id)
                            )
                        }
                    }
                    else -> null
                } ?: return ToolStepOutcome(nextSession = session, promptMessage = "${type.commandName}: Tap a Circle or Arc entity")

                ToolStepOutcome(
                    updatedDocument = doc.copy(entities = doc.entities + dim, isDirty = true),
                    nextSession = ToolSession(),
                    promptMessage = "${type.commandName} placed (associative)",
                    shouldCommitHistory = true
                )
            }
            DimensionType.LEADER, DimensionType.MULTILEADER -> if (pts.isEmpty()) {
                ToolStepOutcome(nextSession = session.copy(collectedPoints = listOf(pt)), promptMessage = "${type.commandName}: Specify callout landing point")
            } else {
                val dim = CadDimension(
                    layerName = "DIMENSIONS",
                    dimType = type,
                    p1 = pts[0],
                    p2 = pt,
                    dimLinePoint = pt,
                    leaderLabel = if (type == DimensionType.MULTILEADER) "M-NOTE" else "CALLOUT"
                )
                ToolStepOutcome(
                    updatedDocument = doc.copy(entities = doc.entities + dim, isDirty = true),
                    nextSession = ToolSession(),
                    promptMessage = "${type.commandName} created (edit label in Properties)",
                    shouldCommitHistory = true
                )
            }
        }
    }

    private fun handleMeasureTap(
        tool: MeasureTool,
        pt: Vec2,
        doc: CadDocument,
        session: ToolSession,
        visibleLayers: Set<String>,
        tol: Double
    ): ToolStepOutcome {
        val pts = session.collectedPoints
        val u = doc.units.shortLabel
        return when (tool) {
            MeasureTool.COORDINATE -> ToolStepOutcome(
                nextSession = ToolSession(),
                promptMessage = "ID: X=${fmt(pt.x)} $u, Y=${fmt(pt.y)} $u",
                measurement = MeasurementReadout("Coordinate (ID)", "X: ${fmt(pt.x)} $u   Y: ${fmt(pt.y)} $u", "Distance from origin: ${fmt(pt.length())} $u")
            )
            MeasureTool.DISTANCE -> if (pts.isEmpty()) {
                ToolStepOutcome(nextSession = session.copy(collectedPoints = listOf(pt)), promptMessage = "DIST: Specify second point")
            } else {
                val a = pts[0]
                val dist = a.distanceTo(pt)
                val dx = pt.x - a.x
                val dy = pt.y - a.y
                val ang = (pt - a).angleDeg()
                ToolStepOutcome(
                    nextSession = ToolSession(),
                    promptMessage = "Distance = ${fmt(dist)} $u",
                    measurement = MeasurementReadout(
                        "Distance",
                        "${fmt(dist)} $u",
                        "ΔX: ${fmt(dx)} $u | ΔY: ${fmt(dy)} $u | Angle: ${fmt(ang)}°"
                    )
                )
            }
            MeasureTool.AREA, MeasureTool.PERIMETER -> {
                val hit = if (pts.isEmpty()) GeometryEngine.hitTest(pt, doc.entities, tol, doc.blocks, visibleLayers) else null
                if (hit is CadCircle) {
                    val area = PI * hit.radius * hit.radius
                    val perim = 2.0 * PI * hit.radius
                    ToolStepOutcome(
                        nextSession = ToolSession(),
                        promptMessage = "Circle Area=${fmt(area)} $u², Circumference=${fmt(perim)} $u",
                        measurement = MeasurementReadout(if (tool == MeasureTool.AREA) "Area" else "Perimeter", "${fmt(if (tool == MeasureTool.AREA) area else perim)} ${if (tool == MeasureTool.AREA) "$u²" else u}", "Circumference: ${fmt(perim)} $u | Area: ${fmt(area)} $u²")
                    )
                } else if (hit is CadPolyline && hit.points.size >= 3) {
                    val (area, perim) = GeometryEngine.polygonAreaAndPerimeter(hit.points, hit.isClosed)
                    ToolStepOutcome(
                        nextSession = ToolSession(),
                        promptMessage = "Polyline Area=${fmt(area)} $u², Perimeter=${fmt(perim)} $u",
                        measurement = MeasurementReadout(if (tool == MeasureTool.AREA) "Area" else "Perimeter", "${fmt(if (tool == MeasureTool.AREA) area else perim)} ${if (tool == MeasureTool.AREA) "$u²" else u}", "Perimeter: ${fmt(perim)} $u | Vertices: ${hit.points.size}")
                    )
                } else {
                    val nextPts = pts + pt
                    val (area, perim) = GeometryEngine.polygonAreaAndPerimeter(nextPts, closed = nextPts.size >= 3)
                    ToolStepOutcome(
                        nextSession = session.copy(collectedPoints = nextPts),
                        promptMessage = "${tool.commandName} (${nextPts.size} pts): Area=${fmt(area)} $u², Perim=${fmt(perim)} $u",
                        measurement = if (nextPts.size >= 2) MeasurementReadout(tool.label, if (tool == MeasureTool.AREA) "${fmt(area)} $u²" else "${fmt(perim)} $u", "Area: ${fmt(area)} $u² | Perimeter: ${fmt(perim)} $u") else null
                    )
                }
            }
            MeasureTool.RADIUS, MeasureTool.DIAMETER -> {
                val hit = GeometryEngine.hitTest(pt, doc.entities, tol, doc.blocks, visibleLayers)
                val r = when (hit) {
                    is CadCircle -> hit.radius
                    is CadArc -> hit.radius
                    else -> null
                } ?: return ToolStepOutcome(nextSession = session, promptMessage = "${tool.label}: Tap a Circle or Arc")
                val valShown = if (tool == MeasureTool.RADIUS) r else r * 2.0
                ToolStepOutcome(
                    nextSession = ToolSession(),
                    promptMessage = "${tool.label} = ${fmt(valShown)} $u",
                    measurement = MeasurementReadout(tool.label, "${fmt(valShown)} $u", "Radius: ${fmt(r)} $u | Diameter: ${fmt(r * 2)} $u")
                )
            }
            MeasureTool.ANGLE -> when (pts.size) {
                0 -> ToolStepOutcome(nextSession = session.copy(collectedPoints = listOf(pt)), promptMessage = "ANGLE: Specify first ray point")
                1 -> ToolStepOutcome(nextSession = session.copy(collectedPoints = pts + pt), promptMessage = "ANGLE: Specify second ray point")
                else -> {
                    val v1 = (pts[1] - pts[0]).normalized()
                    val v2 = (pt - pts[0]).normalized()
                    val deg = Math.toDegrees(acos(v1.dot(v2).coerceIn(-1.0, 1.0)))
                    ToolStepOutcome(
                        nextSession = ToolSession(),
                        promptMessage = "Angle = ${fmt(deg)}°",
                        measurement = MeasurementReadout("Angle", "${fmt(deg)}°", "Complement: ${fmt(180.0 - deg)}°")
                    )
                }
            }
        }
    }

    fun buildRegularPolygon(center: Vec2, vertexPt: Vec2, sides: Int, layer: String): CadPolyline {
        val n = sides.coerceIn(3, 64)
        val r = max(0.1, center.distanceTo(vertexPt))
        val startAngle = (vertexPt - center).angleRad()
        val pts = (0 until n).map { i ->
            val a = startAngle + 2.0 * PI * i / n
            Vec2(center.x + r * cos(a), center.y + r * sin(a))
        }
        return CadPolyline(layerName = layer, points = pts, isClosed = true)
    }

    private fun fmt(v: Double): String = String.format(Locale.US, "%.2f", v)
}
