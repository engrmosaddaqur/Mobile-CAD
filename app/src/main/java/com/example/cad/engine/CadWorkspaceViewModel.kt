package com.example.cad.engine

import android.app.Application
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.cad.geometry.CadSpatialIndex
import com.example.cad.geometry.GeometryEngine
import com.example.cad.geometry.GeometryOperations
import com.example.cad.geometry.SnapEngine
import com.example.cad.geometry.SnapResult
import com.example.cad.io.SampleDrawings
import com.example.cad.model.CadDocument
import com.example.cad.model.CadEntity
import com.example.cad.model.CadEntity.*
import com.example.cad.model.CadLayer
import com.example.cad.model.CadLayout
import com.example.cad.model.CadLineType
import com.example.cad.model.CadUnits
import com.example.cad.model.DimensionStyle
import com.example.cad.model.DimensionType
import com.example.cad.model.OsnapType
import com.example.cad.model.SnapSettings
import com.example.cad.model.Vec2
import com.example.cad.render.PreviewGeometry
import com.example.cad.render.ViewportState
import com.example.data.CadDatabase
import com.example.data.CadFileRepository
import com.example.data.CadImportOutcome
import com.example.data.ExportFormat
import com.example.data.UserPreferences
import com.example.data.UserPreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

data class CadWorkspaceUiState(
    val document: CadDocument = CadDocument(),
    val viewport: ViewportState = ViewportState(),
    val activeTool: ActiveTool = ActiveTool.Select,
    val activePanelTab: WorkspacePanelTab? = WorkspacePanelTab.DRAW,
    val snapSettings: SnapSettings = SnapSettings(),
    val toolConfig: ToolNumericConfig = ToolNumericConfig(),
    val session: ToolSession = ToolSession(),
    val cursorWorld: Vec2? = null,
    val snapResult: SnapResult? = null,
    val rectSelectionDrag: Pair<Vec2, Vec2>? = null,
    val measurement: MeasurementReadout? = null,
    val commandPrompt: String = "Ready — Select a tool or type a command (LINE, CIRCLE, MOVE...)",
    val commandHistory: List<CommandHistoryEntry> = listOf(
        CommandHistoryEntry("MOBILE CAD Engine v1.0 initialized. Offline DXF AC1024 engine ready.")
    ),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val statusBannerMessage: String? = null,
    val errorDialogPair: Pair<String, String>? = null,
    val isBusy: Boolean = false,
    val commandBarExpanded: Boolean = false,
    val spatialIndex: CadSpatialIndex? = null
) {
    val selectedEntities: List<CadEntity>
        get() {
            val idx = spatialIndex
            return if (idx != null && idx.entityCount == document.entities.size) {
                val out = ArrayList<CadEntity>(idx.selectedIndices.size)
                for (i in idx.selectedIndices) {
                    val e = document.entities[i]
                    if (e.isSelected) out.add(e)
                }
                out
            } else {
                document.entities.filter { it.isSelected }
            }
        }

    val previewGeometry: PreviewGeometry
        get() = CadToolExecutor.buildPreview(
            activeTool = activeTool,
            session = session,
            cursorWorld = snapResult?.point ?: cursorWorld,
            doc = document,
            config = toolConfig,
            rectSelectionDrag = rectSelectionDrag
        )
}

class CadWorkspaceViewModel(application: Application) : AndroidViewModel(application) {
    private val db = CadDatabase.getInstance(application)
    private val fileRepo = CadFileRepository(application, db.recentDrawingDao())
    private val prefsRepo = UserPreferencesRepository(application)

    private val _uiState = MutableStateFlow(CadWorkspaceUiState())
    val uiState: StateFlow<CadWorkspaceUiState> = _uiState.asStateFlow()

    val userPreferences: StateFlow<UserPreferences> = MutableStateFlow(UserPreferences()).also { flow ->
        viewModelScope.launch {
            prefsRepo.preferencesFlow.collect { prefs ->
                flow.value = prefs
                _uiState.update { state ->
                    state.copy(
                        snapSettings = state.snapSettings.copy(
                            gridSpacing = prefs.gridSpacing,
                            polarIncrementDeg = prefs.polarIncrementDeg
                        )
                    )
                }
            }
        }
    }

    private val undoStack = ArrayDeque<CadDocument>()
    private val redoStack = ArrayDeque<CadDocument>()
    private var autoSaveJob: Job? = null
    private var spatialIndexJob: Job? = null
    private var hasZoomedInitial = false

    fun loadDrawing(drawingId: String, recoverAutosave: Boolean = false) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            val loaded = when (drawingId) {
                "new_blank" -> SampleDrawings.blankDrawing("Drawing_${System.currentTimeMillis() % 1000}")
                else -> fileRepo.loadDrawingById(drawingId, preferRecovery = recoverAutosave)
                    ?: SampleDrawings.architecturalStudioFloorPlan()
            }
            val initialIndex = withContext(Dispatchers.Default) {
                CadSpatialIndex.build(loaded.entities, loaded.blocks)
            }
            setNewDocument(loaded, prebuiltIndex = initialIndex)
            if (loaded.importWarnings.isNotEmpty()) {
                _uiState.update {
                    it.copy(statusBannerMessage = loaded.importWarnings.first())
                }
            }
            _uiState.update { it.copy(isBusy = false) }
            startAutoSaveLoop()
        }
    }

    fun loadNewDocumentWithParams(name: String, projectGroup: String, units: CadUnits) {
        val doc = SampleDrawings.blankDrawing(name.ifBlank { "Untitled_CAD" }, projectGroup.ifBlank { "Default Project" }, units)
        setNewDocument(doc, prebuiltIndex = CadSpatialIndex.build(doc.entities, doc.blocks))
        viewModelScope.launch { fileRepo.saveDrawingInternal(doc, clearRecovery = true) }
        startAutoSaveLoop()
    }

    private fun setNewDocument(doc: CadDocument, prebuiltIndex: CadSpatialIndex? = null) {
        undoStack.clear()
        redoStack.clear()
        hasZoomedInitial = false
        val syncIndex = prebuiltIndex ?: if (doc.entities.size < 1500) {
            CadSpatialIndex.build(doc.entities, doc.blocks)
        } else null

        _uiState.update {
            val bounds = syncIndex?.totalBounds ?: GeometryEngine.boundsOfAll(doc.entities, doc.blocks)
            it.copy(
                document = doc,
                spatialIndex = syncIndex,
                viewport = it.viewport.zoomToBounds(bounds),
                session = ToolSession(),
                activeTool = ActiveTool.Select,
                canUndo = false,
                canRedo = false,
                commandPrompt = "Opened '${doc.name}' (${doc.entities.size} entities, ${doc.layers.size} layers)"
            )
        }
        if (syncIndex == null) {
            scheduleSpatialIndexRebuild(doc)
        }
    }

    private fun scheduleSpatialIndexRebuild(doc: CadDocument) {
        spatialIndexJob?.cancel()
        if (doc.entities.size < 1500) {
            val idx = CadSpatialIndex.build(doc.entities, doc.blocks)
            _uiState.update { state ->
                if (state.document === doc) state.copy(spatialIndex = idx) else state
            }
            return
        }
        spatialIndexJob = viewModelScope.launch {
            val idx = withContext(Dispatchers.Default) {
                CadSpatialIndex.build(doc.entities, doc.blocks)
            }
            _uiState.update { state ->
                if (state.document === doc) state.copy(spatialIndex = idx) else state
            }
        }
    }

    private fun startAutoSaveLoop() {
        autoSaveJob?.cancel()
        autoSaveJob = viewModelScope.launch {
            while (true) {
                val prefs = userPreferences.value
                delay((prefs.autoSaveIntervalSec.coerceAtLeast(10)) * 1000L)
                val current = _uiState.value.document
                if (prefs.autoSaveEnabled && current.isDirty) {
                    fileRepo.writeAutoSave(current)
                }
            }
        }
    }

    fun onViewportSizeChanged(width: Float, height: Float) {
        if (width <= 10f || height <= 10f) return
        _uiState.update { state ->
            val nextVp = state.viewport.copy(viewWidthPx = width, viewHeightPx = height)
            if (!hasZoomedInitial) {
                hasZoomedInitial = true
                val bounds = state.spatialIndex?.totalBounds
                    ?: GeometryEngine.boundsOfAll(state.document.entities, state.document.blocks)
                state.copy(viewport = nextVp.zoomToBounds(bounds))
            } else {
                state.copy(viewport = nextVp)
            }
        }
    }

    fun onPinchOrPan(panDelta: Offset, zoomFactor: Float, centroid: Offset) {
        _uiState.update { state ->
            var vp = state.viewport
            if (kotlin.math.abs(zoomFactor - 1f) > 0.001f) {
                vp = vp.zoomAtScreenPoint(zoomFactor.toDouble(), centroid)
            }
            if (panDelta != Offset.Zero) {
                vp = vp.panByScreenDelta(panDelta)
            }
            state.copy(viewport = vp)
        }
    }

    fun onMouseWheelZoom(scrollY: Float, pointerScreen: Offset) {
        val factor = if (scrollY > 0f) 1.15 else 1.0 / 1.15
        _uiState.update { it.copy(viewport = it.viewport.zoomAtScreenPoint(factor, pointerScreen)) }
    }

    fun zoomExtents() {
        _uiState.update { state ->
            val bounds = state.spatialIndex?.totalBounds
                ?: GeometryEngine.boundsOfAll(state.document.entities, state.document.blocks)
            state.copy(viewport = state.viewport.zoomToBounds(bounds))
        }
    }

    fun zoomAll() {
        _uiState.update { state ->
            val layout = state.document.activeLayout
            val bounds = if (!layout.isModelSpace) {
                com.example.cad.model.Bounds2D(-layout.paperWidthMm * 0.55, -layout.paperHeightMm * 0.55, layout.paperWidthMm * 0.55, layout.paperHeightMm * 0.55)
            } else {
                (state.spatialIndex?.totalBounds ?: GeometryEngine.boundsOfAll(state.document.entities, state.document.blocks)).expand(30.0)
            }
            state.copy(viewport = state.viewport.zoomToBounds(bounds))
        }
    }

    fun zoomPrevious() {
        _uiState.update { it.copy(viewport = it.viewport.zoomPrevious()) }
    }

    fun onPointerHover(screenPos: Offset) {
        _uiState.update { state ->
            val raw = state.viewport.screenToWorld(screenPos)
            val snap = computeSnap(raw, state)
            state.copy(cursorWorld = snap.point, snapResult = snap)
        }
    }

    fun onCanvasTap(screenPos: Offset) {
        val state = _uiState.value
        val raw = state.viewport.screenToWorld(screenPos)
        val tol = 26.0 / state.viewport.zoom

        if (state.activeTool is ActiveTool.Select || state.activeTool is ActiveTool.RectSelect) {
            val visibleLayers = state.document.layers.filter { it.isOn && !it.isFrozen }.map { it.name }.toSet()
            val hit = GeometryEngine.hitTest(raw, state.document.entities, tol, state.document.blocks, visibleLayers, state.spatialIndex)
            val updated = state.document.entities.map { entity ->
                if (hit != null && entity.id == hit.id) entity.copyCommon(isSelected = !entity.isSelected)
                else entity
            }
            val selCount = updated.count { it.isSelected }
            val nextDoc = state.document.copy(entities = updated)
            _uiState.update {
                it.copy(
                    document = nextDoc,
                    cursorWorld = raw,
                    commandPrompt = if (hit != null) "Selected $selCount object(s) [${hit.layerName}]" else "Tap entity to select or drag Window/Crossing box"
                )
            }
            scheduleSpatialIndexRebuild(nextDoc)
            return
        }

        if (state.activeTool is ActiveTool.Pan || state.activeTool is ActiveTool.ZoomWindow) return

        val snap = computeSnap(raw, state)
        val outcome = CadToolExecutor.handlePointTap(
            activeTool = state.activeTool,
            point = snap.point,
            doc = state.document,
            session = state.session,
            config = state.toolConfig,
            hitToleranceWorld = tol
        )
        applyToolOutcome(outcome, snap)
    }

    fun onSingleFingerDragStart(screenPos: Offset) {
        val state = _uiState.value
        val raw = state.viewport.screenToWorld(screenPos)
        if (state.activeTool is ActiveTool.Select) {
            val gripTol = 24.0 / state.viewport.zoom
            // Check if dragging an object grip on any selected entity
            for (entity in state.document.entities) {
                if (!entity.isSelected) continue
                val grips = GeometryOperations.gripsOf(entity)
                grips.forEachIndexed { idx, g ->
                    if (g.distanceTo(raw) <= gripTol) {
                        pushUndoSnapshot(state.document)
                        _uiState.update { it.copy(session = it.session.copy(draggingGrip = entity.id to idx), cursorWorld = g) }
                        return
                    }
                }
            }
            // Otherwise start Window/Crossing rectangle selection
            _uiState.update { it.copy(rectSelectionDrag = raw to raw) }
        } else if (state.activeTool is ActiveTool.ZoomWindow || state.activeTool is ActiveTool.RectSelect) {
            _uiState.update { it.copy(rectSelectionDrag = raw to raw) }
        }
    }

    fun onSingleFingerDrag(screenPos: Offset, delta: Offset) {
        val state = _uiState.value
        if (state.activeTool is ActiveTool.Pan) {
            _uiState.update { it.copy(viewport = it.viewport.panByScreenDelta(delta)) }
            return
        }
        val raw = state.viewport.screenToWorld(screenPos)
        val dragging = state.session.draggingGrip
        if (dragging != null) {
            val snap = computeSnap(raw, state)
            val updated = state.document.entities.map { e ->
                if (e.id == dragging.first) GeometryOperations.moveGrip(e, dragging.second, snap.point) else e
            }
            val synced = GeometryOperations.updateAssociativeDimensions(updated)
            _uiState.update { it.copy(document = it.document.copy(entities = synced, isDirty = true), cursorWorld = snap.point, snapResult = snap) }
            return
        }
        val rect = state.rectSelectionDrag
        if (rect != null) {
            _uiState.update { it.copy(rectSelectionDrag = rect.first to raw, cursorWorld = raw) }
        } else {
            onPointerHover(screenPos)
        }
    }

    fun onSingleFingerDragEnd() {
        val state = _uiState.value
        if (state.session.draggingGrip != null) {
            _uiState.update { it.copy(session = it.session.copy(draggingGrip = null), commandPrompt = "Grip edited") }
            scheduleSpatialIndexRebuild(state.document)
            return
        }
        val rect = state.rectSelectionDrag ?: return
        if (state.activeTool is ActiveTool.ZoomWindow) {
            val (a, b) = rect
            val bounds = com.example.cad.model.Bounds2D(
                kotlin.math.min(a.x, b.x), kotlin.math.min(a.y, b.y),
                kotlin.math.max(a.x, b.x), kotlin.math.max(a.y, b.y)
            )
            _uiState.update {
                it.copy(
                    viewport = if (bounds.width > 0.5 && bounds.height > 0.5) it.viewport.zoomToBounds(bounds, 0.92) else it.viewport,
                    rectSelectionDrag = null,
                    activeTool = ActiveTool.Select,
                    commandPrompt = "Zoomed to window"
                )
            }
            return
        }
        val visibleLayers = state.document.layers.filter { it.isOn && !it.isFrozen }.map { it.name }.toSet()
        val ids = GeometryEngine.selectByRect(rect.first, rect.second, state.document.entities, state.document.blocks, visibleLayers, state.spatialIndex)
        val updated = state.document.entities.map { e ->
            if (e.id in ids) e.copyCommon(isSelected = true) else e
        }
        val nextDoc = state.document.copy(entities = updated)
        val modeName = if (rect.second.x >= rect.first.x) "Window" else "Crossing"
        _uiState.update {
            it.copy(
                document = nextDoc,
                rectSelectionDrag = null,
                activeTool = ActiveTool.Select,
                commandPrompt = "$modeName select: ${ids.size} entity(ies) added"
            )
        }
        scheduleSpatialIndexRebuild(nextDoc)
    }

    fun selectTool(tool: ActiveTool) {
        if (tool is ActiveTool.Modify && tool.tool.isImmediateAction) {
            executeImmediateModify(tool.tool)
            return
        }
        _uiState.update {
            it.copy(
                activeTool = tool,
                session = ToolSession(),
                rectSelectionDrag = null,
                commandPrompt = "${tool.displayName}: Tap canvas or enter coordinates"
            )
        }
    }

    private fun executeImmediateModify(tool: ModifyTool) {
        val state = _uiState.value
        val doc = state.document
        val lockedLayers = doc.layers.filter { it.isLocked }.map { it.name }.toSet()
        val selected = doc.entities.filter { it.isSelected && it.layerName !in lockedLayers }

        when (tool) {
            ModifyTool.UNDO -> undo()
            ModifyTool.REDO -> redo()
            ModifyTool.ERASE, ModifyTool.DELETE -> {
                if (selected.isEmpty()) {
                    _uiState.update { it.copy(activeTool = ActiveTool.Modify(tool), session = ToolSession(), commandPrompt = "${tool.commandName}: Tap entity to delete") }
                } else {
                    pushUndoSnapshot(doc)
                    val selIds = selected.map { it.id }.toSet()
                    val remaining = doc.entities.filterNot { it.id in selIds }
                    commitDocChange(doc.copy(entities = remaining, isDirty = true), "Deleted ${selIds.size} entity(ies)")
                }
            }
            ModifyTool.EXPLODE -> {
                if (selected.isEmpty()) {
                    _uiState.update { it.copy(commandPrompt = "EXPLODE: Select polylines, blocks, or dimensions first") }
                    return
                }
                pushUndoSnapshot(doc)
                val selIds = selected.map { it.id }.toSet()
                val next = doc.entities.flatMap { e ->
                    if (e.id in selIds) GeometryOperations.explode(e, doc.blocks) else listOf(e)
                }
                commitDocChange(doc.copy(entities = next, isDirty = true), "Exploded ${selIds.size} object(s)")
            }
            ModifyTool.JOIN -> {
                if (selected.size < 2) {
                    _uiState.update { it.copy(commandPrompt = "JOIN: Select 2 or more connected lines/polylines") }
                    return
                }
                val joined = GeometryOperations.joinEntities(selected)
                if (joined == null) {
                    _uiState.update { it.copy(commandPrompt = "JOIN: Could not join selected geometry") }
                    return
                }
                pushUndoSnapshot(doc)
                val selIds = selected.map { it.id }.toSet()
                val next = doc.entities.filterNot { it.id in selIds } + joined
                commitDocChange(doc.copy(entities = next, isDirty = true), "Joined ${selected.size} entities into Polyline")
            }
            else -> Unit
        }
    }

    fun finishActiveTool(closeShape: Boolean = false) {
        val state = _uiState.value
        val tool = state.activeTool
        val pts = state.session.collectedPoints
        if (tool is ActiveTool.Draw) {
            when (tool.tool) {
                DrawTool.POLYLINE -> if (pts.size >= 2) {
                    pushUndoSnapshot(state.document)
                    val poly = CadPolyline(layerName = state.document.currentLayerName, points = pts, isClosed = closeShape)
                    commitDocChange(state.document.copy(entities = state.document.entities + poly, isDirty = true), "PLINE committed (${pts.size} vertices)")
                }
                DrawTool.SPLINE -> if (pts.size >= 2) {
                    pushUndoSnapshot(state.document)
                    val spline = CadSpline(layerName = state.document.currentLayerName, controlPoints = pts, isClosed = closeShape)
                    commitDocChange(state.document.copy(entities = state.document.entities + spline, isDirty = true), "SPLINE committed (${pts.size} control points)")
                }
                else -> Unit
            }
        }
        _uiState.update { it.copy(session = ToolSession(), activeTool = ActiveTool.Select, commandPrompt = "Ready") }
    }

    fun submitCommandInput(raw: String) {
        val state = _uiState.value
        val parsed = CommandProcessor.parse(raw, state.session.collectedPoints.lastOrNull())
        appendHistory("> $raw", isCommand = true)

        when (parsed) {
            is ParsedCliInput.SwitchTool -> {
                selectTool(parsed.tool)
                appendHistory(parsed.promptMessage)
            }
            is ParsedCliInput.PointCoordinate -> {
                if (state.activeTool is ActiveTool.Select) {
                    _uiState.update { it.copy(cursorWorld = parsed.point, commandPrompt = "Cursor moved to (${parsed.point.x}, ${parsed.point.y})") }
                } else {
                    val outcome = CadToolExecutor.handlePointTap(
                        activeTool = state.activeTool,
                        point = parsed.point,
                        doc = state.document,
                        session = state.session,
                        config = state.toolConfig,
                        hitToleranceWorld = 24.0 / state.viewport.zoom
                    )
                    applyToolOutcome(outcome, SnapResult(parsed.point))
                }
            }
            is ParsedCliInput.ScalarValue -> handleScalarInput(parsed.value)
            is ParsedCliInput.ConfirmFinish -> finishActiveTool(closeShape = false)
            is ParsedCliInput.ClosePolyline -> finishActiveTool(closeShape = true)
            is ParsedCliInput.CancelTool -> {
                _uiState.update { it.copy(activeTool = ActiveTool.Select, session = ToolSession(), rectSelectionDrag = null, commandPrompt = "Cancelled") }
            }
            is ParsedCliInput.ZoomCommand -> when (parsed.mode) {
                "EXTENTS" -> zoomExtents()
                "PREVIOUS" -> zoomPrevious()
                "ALL" -> zoomAll()
            }
            is ParsedCliInput.ToggleFlag -> when (parsed.flag) {
                "ORTHO" -> toggleOrtho()
                "GRID" -> toggleGrid()
                "OSNAP" -> toggleOsnap()
                "POLAR" -> togglePolar()
            }
            is ParsedCliInput.SelectAll -> selectAll()
            is ParsedCliInput.DeselectAll -> deselectAll()
            is ParsedCliInput.SaveDrawing -> saveDrawing()
            is ParsedCliInput.OpenLayers -> _uiState.update { it.copy(activePanelTab = WorkspacePanelTab.LAYERS) }
            is ParsedCliInput.Help -> appendHistory("Commands: LINE(L), PLINE(PL), REC, CIRCLE(C), ARC(A), ELLIPSE, SPLINE, MOVE(M), COPY(CO), ROTATE(RO), MIRROR(MI), OFFSET(O), TRIM(TR), EXTEND(EX), FILLET(F), CHAMFER(CHA), SCALE(SC), ARRAY(AR), DIMLINEAR, DIST(DI), AREA, ID, ZE, ZP, PAN, SAVE. Coords: x,y | @dx,dy | @dist<ang")
            is ParsedCliInput.Unknown -> appendHistory("Unknown command '${parsed.raw}'. Type HELP for list.", isError = true)
        }
    }

    private fun handleScalarInput(value: Double) {
        val state = _uiState.value
        val tool = state.activeTool
        val pts = state.session.collectedPoints
        if (tool is ActiveTool.Draw && tool.tool == DrawTool.CIRCLE && pts.size == 1 && value > 0) {
            val pt = Vec2(pts[0].x + value, pts[0].y)
            val outcome = CadToolExecutor.handlePointTap(tool, pt, state.document, state.session, state.toolConfig, 1.0)
            applyToolOutcome(outcome, SnapResult(pt))
            return
        }
        if (tool is ActiveTool.Draw && (tool.tool == DrawTool.LINE || tool.tool == DrawTool.POLYLINE) && pts.isNotEmpty()) {
            val last = pts.last()
            val dir = ((state.cursorWorld ?: Vec2(last.x + 1.0, last.y)) - last).normalized()
            val nextPt = last + dir * value
            val outcome = CadToolExecutor.handlePointTap(tool, nextPt, state.document, state.session, state.toolConfig, 1.0)
            applyToolOutcome(outcome, SnapResult(nextPt))
            return
        }
        if (tool is ActiveTool.Modify) {
            when (tool.tool) {
                ModifyTool.OFFSET -> _uiState.update { it.copy(toolConfig = it.toolConfig.copy(offsetDistance = value), commandPrompt = "OFFSET distance set to $value") }
                ModifyTool.FILLET -> _uiState.update { it.copy(toolConfig = it.toolConfig.copy(filletRadius = value), commandPrompt = "FILLET radius set to $value") }
                ModifyTool.ROTATE -> _uiState.update { it.copy(toolConfig = it.toolConfig.copy(rotateAngleDeg = value), commandPrompt = "ROTATE angle set to $value°") }
                ModifyTool.SCALE -> _uiState.update { it.copy(toolConfig = it.toolConfig.copy(scaleFactor = value), commandPrompt = "SCALE factor set to $value") }
                else -> appendHistory("Value $value acknowledged.")
            }
            return
        }
        appendHistory("Entered value: $value")
    }

    private fun applyToolOutcome(outcome: ToolStepOutcome, snap: SnapResult) {
        val current = _uiState.value
        if (outcome.shouldCommitHistory && outcome.updatedDocument != null) {
            pushUndoSnapshot(current.document)
        }
        val updatedDoc = outcome.updatedDocument
        _uiState.update {
            it.copy(
                document = updatedDoc ?: it.document,
                session = outcome.nextSession,
                cursorWorld = snap.point,
                snapResult = snap,
                measurement = outcome.measurement ?: it.measurement,
                commandPrompt = outcome.promptMessage,
                activeTool = if (outcome.resetToSelect) ActiveTool.Select else it.activeTool,
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty()
            )
        }
        if (updatedDoc != null) {
            scheduleSpatialIndexRebuild(updatedDoc)
        }
        appendHistory(outcome.promptMessage)
    }

    private fun pushUndoSnapshot(doc: CadDocument) {
        undoStack.addLast(doc)
        if (undoStack.size > 50) undoStack.removeFirst()
        redoStack.clear()
    }

    private fun commitDocChange(newDoc: CadDocument, message: String) {
        _uiState.update {
            it.copy(
                document = newDoc,
                commandPrompt = message,
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty()
            )
        }
        scheduleSpatialIndexRebuild(newDoc)
        appendHistory(message)
    }

    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(_uiState.value.document)
        _uiState.update {
            it.copy(
                document = prev,
                session = ToolSession(),
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty(),
                commandPrompt = "Undo"
            )
        }
        scheduleSpatialIndexRebuild(prev)
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(_uiState.value.document)
        _uiState.update {
            it.copy(
                document = next,
                session = ToolSession(),
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty(),
                commandPrompt = "Redo"
            )
        }
        scheduleSpatialIndexRebuild(next)
    }

    fun selectAll() {
        val state = _uiState.value
        val visible = state.document.layers.filter { it.isOn && !it.isFrozen }.map { it.name }.toSet()
        val updated = state.document.entities.map { if (it.layerName in visible) it.copyCommon(isSelected = true) else it }
        val nextDoc = state.document.copy(entities = updated)
        _uiState.update {
            it.copy(document = nextDoc, commandPrompt = "Selected ${updated.count { e -> e.isSelected }} objects")
        }
        scheduleSpatialIndexRebuild(nextDoc)
    }

    fun deselectAll() {
        val state = _uiState.value
        val updated = state.document.entities.map { it.copyCommon(isSelected = false) }
        val nextDoc = state.document.copy(entities = updated)
        _uiState.update {
            it.copy(document = nextDoc, commandPrompt = "Selection cleared")
        }
        scheduleSpatialIndexRebuild(nextDoc)
    }

    fun setActivePanelTab(tab: WorkspacePanelTab?) {
        _uiState.update { it.copy(activePanelTab = if (it.activePanelTab == tab) null else tab) }
    }

    fun toggleCommandBarExpanded() {
        _uiState.update { it.copy(commandBarExpanded = !it.commandBarExpanded) }
    }

    fun toggleGrid() = _uiState.update { it.copy(snapSettings = it.snapSettings.copy(gridVisible = !it.snapSettings.gridVisible)) }
    fun toggleGridSnap() = _uiState.update { it.copy(snapSettings = it.snapSettings.copy(gridSnapEnabled = !it.snapSettings.gridSnapEnabled)) }
    fun toggleOrtho() = _uiState.update { it.copy(snapSettings = it.snapSettings.copy(orthoMode = !it.snapSettings.orthoMode)) }
    fun togglePolar() = _uiState.update { it.copy(snapSettings = it.snapSettings.copy(polarTracking = !it.snapSettings.polarTracking)) }
    fun toggleOsnap() = _uiState.update { it.copy(snapSettings = it.snapSettings.copy(osnapEnabled = !it.snapSettings.osnapEnabled)) }
    fun toggleDynamicInput() = _uiState.update { it.copy(snapSettings = it.snapSettings.copy(dynamicInput = !it.snapSettings.dynamicInput)) }
    fun toggleOsnapMode(mode: OsnapType) = _uiState.update { state ->
        val cur = state.snapSettings.enabledOsnaps
        val next = if (mode in cur) cur - mode else cur + mode
        state.copy(snapSettings = state.snapSettings.copy(enabledOsnaps = next))
    }

    fun updateToolConfig(transform: (ToolNumericConfig) -> ToolNumericConfig) {
        _uiState.update { it.copy(toolConfig = transform(it.toolConfig)) }
    }

    fun updateDimensionStyle(style: DimensionStyle) {
        val doc = _uiState.value.document
        pushUndoSnapshot(doc)
        commitDocChange(doc.copy(dimensionStyle = style, units = style.units, isDirty = true), "Updated dimension style")
    }

    fun updateUnits(units: CadUnits) {
        val doc = _uiState.value.document
        pushUndoSnapshot(doc)
        commitDocChange(doc.copy(units = units, dimensionStyle = doc.dimensionStyle.copy(units = units), isDirty = true), "Units set to ${units.displayName}")
    }

    // Layer Manager operations
    fun createLayer(name: String, colorArgb: Int, aci: Int) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        val doc = _uiState.value.document
        if (doc.layers.any { it.name.equals(clean, ignoreCase = true) }) return
        pushUndoSnapshot(doc)
        val newLayer = CadLayer(name = clean, colorArgb = colorArgb, aciColor = aci)
        commitDocChange(doc.copy(layers = doc.layers + newLayer, currentLayerName = clean, isDirty = true), "Created layer '$clean'")
    }

    fun setCurrentLayer(name: String) {
        _uiState.update { it.copy(document = it.document.copy(currentLayerName = name), commandPrompt = "Active layer: $name") }
    }

    fun updateLayer(oldName: String, transform: (CadLayer) -> CadLayer) {
        val doc = _uiState.value.document
        pushUndoSnapshot(doc)
        val updatedLayers = doc.layers.map { if (it.name == oldName) transform(it) else it }
        val newLayerName = updatedLayers.firstOrNull { l -> doc.layers.none { it.name == l.name } }?.name
        val updatedEntities = if (newLayerName != null) {
            doc.entities.map { if (it.layerName == oldName) it.copyCommon(layerName = newLayerName) else it }
        } else doc.entities
        val nextCurrent = if (doc.currentLayerName == oldName && newLayerName != null) newLayerName else doc.currentLayerName
        commitDocChange(doc.copy(layers = updatedLayers, entities = updatedEntities, currentLayerName = nextCurrent, isDirty = true), "Updated layer '$oldName'")
    }

    fun deleteLayer(name: String) {
        if (name == "0") return
        val doc = _uiState.value.document
        pushUndoSnapshot(doc)
        val remaining = doc.layers.filterNot { it.name == name }
        val reassigned = doc.entities.map { if (it.layerName == name) it.copyCommon(layerName = "0") else it }
        val nextCurrent = if (doc.currentLayerName == name) "0" else doc.currentLayerName
        commitDocChange(doc.copy(layers = remaining, entities = reassigned, currentLayerName = nextCurrent, isDirty = true), "Deleted layer '$name'")
    }

    // Object Properties updates
    fun updateSelectedProperties(
        layerName: String? = null,
        colorArgb: Int? = null,
        clearCustomColor: Boolean = false,
        lineType: CadLineType? = null,
        clearCustomLineType: Boolean = false,
        lineWeightMm: Float? = null,
        transparency: Float? = null
    ) {
        val doc = _uiState.value.document
        if (doc.entities.none { it.isSelected }) return
        pushUndoSnapshot(doc)
        val updated = doc.entities.map { e ->
            if (!e.isSelected) e
            else e.copyCommon(
                layerName = layerName ?: e.layerName,
                colorArgb = if (clearCustomColor) null else (colorArgb ?: e.colorArgb),
                lineType = if (clearCustomLineType) null else (lineType ?: e.lineType),
                lineWeightMm = lineWeightMm ?: e.lineWeightMm,
                transparency = transparency ?: e.transparency
            )
        }
        commitDocChange(doc.copy(entities = updated, isDirty = true), "Updated selected properties")
    }

    fun updateSingleEntityGeometry(updatedEntity: CadEntity) {
        val doc = _uiState.value.document
        pushUndoSnapshot(doc)
        val updated = GeometryOperations.updateAssociativeDimensions(
            doc.entities.map { if (it.id == updatedEntity.id) updatedEntity else it }
        )
        commitDocChange(doc.copy(entities = updated, isDirty = true), "Updated entity geometry")
    }

    // Model & Layout Tabs
    fun switchLayout(layoutId: String) {
        _uiState.update { state ->
            state.copy(document = state.document.copy(activeLayoutId = layoutId))
        }
        zoomAll()
    }

    fun addLayout() {
        val doc = _uiState.value.document
        val idx = doc.layouts.count { !it.isModelSpace } + 1
        val newLayout = CadLayout(name = "Layout $idx", isModelSpace = false)
        _uiState.update {
            it.copy(document = doc.copy(layouts = doc.layouts + newLayout, activeLayoutId = newLayout.id, isDirty = true))
        }
        zoomAll()
    }

    fun renameLayout(layoutId: String, newName: String) {
        val clean = newName.trim()
        if (clean.isEmpty()) return
        val doc = _uiState.value.document
        val updated = doc.layouts.map { if (it.id == layoutId && !it.isModelSpace) it.copy(name = clean) else it }
        _uiState.update { it.copy(document = doc.copy(layouts = updated, isDirty = true)) }
    }

    fun deleteLayout(layoutId: String) {
        val doc = _uiState.value.document
        val target = doc.layouts.firstOrNull { it.id == layoutId } ?: return
        if (target.isModelSpace) return
        val remaining = doc.layouts.filterNot { it.id == layoutId }
        val nextActive = if (doc.activeLayoutId == layoutId) "model" else doc.activeLayoutId
        _uiState.update { it.copy(document = doc.copy(layouts = remaining, activeLayoutId = nextActive, isDirty = true)) }
    }

    // Saving, Save As, Importing, Exporting
    fun saveDrawing(newName: String? = null) {
        viewModelScope.launch {
            val doc = _uiState.value.document.let {
                if (!newName.isNullOrBlank()) it.copy(name = newName.trim()) else it
            }
            val saved = fileRepo.saveDrawingInternal(doc, clearRecovery = true)
            _uiState.update {
                it.copy(
                    document = saved,
                    statusBannerMessage = "Saved '${saved.name}.dxf' safely (backup created)"
                )
            }
        }
    }

    fun saveAsCopy(newName: String) {
        viewModelScope.launch {
            val clean = newName.trim().ifEmpty { "${_uiState.value.document.name}_Copy" }
            val copyDoc = _uiState.value.document.copy(
                id = java.util.UUID.randomUUID().toString(),
                name = clean
            )
            val saved = fileRepo.saveDrawingInternal(copyDoc, clearRecovery = true)
            _uiState.update {
                it.copy(
                    document = saved,
                    statusBannerMessage = "Saved copy as '${saved.name}.dxf'"
                )
            }
        }
    }

    fun importFromUri(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            when (val res = fileRepo.importFromUri(uri)) {
                is CadImportOutcome.Success -> {
                    setNewDocument(res.document)
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            statusBannerMessage = res.document.importWarnings.firstOrNull()
                                ?: "Imported '${res.document.name}' (${res.document.entities.size} entities)"
                        )
                    }
                }
                is CadImportOutcome.Error -> {
                    _uiState.update {
                        it.copy(isBusy = false, errorDialogPair = res.title to res.message)
                    }
                }
            }
        }
    }

    fun exportToUri(uri: Uri, format: ExportFormat) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            val res = fileRepo.exportToUri(_uiState.value.document, uri, format)
            res.fold(
                onSuccess = { msg -> _uiState.update { it.copy(isBusy = false, statusBannerMessage = msg) } },
                onFailure = { err -> _uiState.update { it.copy(isBusy = false, errorDialogPair = "Export Diagnostic" to (err.message ?: "Export failed")) } }
            )
        }
    }

    fun clearBanner() = _uiState.update { it.copy(statusBannerMessage = null) }
    fun dismissErrorDialog() = _uiState.update { it.copy(errorDialogPair = null) }
    fun clearMeasurement() = _uiState.update { it.copy(measurement = null) }

    private fun computeSnap(raw: Vec2, state: CadWorkspaceUiState): SnapResult {
        val visibleLayers = state.document.layers.filter { it.isOn && !it.isFrozen }.map { it.name }.toSet()
        val tolWorld = 24.0 / max(0.05, state.viewport.zoom)
        return SnapEngine.resolveSnap(
            rawWorldPoint = raw,
            anchorPoint = state.session.collectedPoints.lastOrNull(),
            entities = state.document.entities,
            blocks = state.document.blocks,
            visibleLayerNames = visibleLayers,
            snapSettings = state.snapSettings,
            snapToleranceWorld = tolWorld,
            spatialIndex = state.spatialIndex
        )
    }

    private fun appendHistory(msg: String, isError: Boolean = false, isCommand: Boolean = false) {
        _uiState.update {
            it.copy(
                commandHistory = (it.commandHistory + CommandHistoryEntry(msg, isError, isCommand)).takeLast(40)
            )
        }
    }

    override fun onCleared() {
        autoSaveJob?.cancel()
        spatialIndexJob?.cancel()
        super.onCleared()
    }
}
