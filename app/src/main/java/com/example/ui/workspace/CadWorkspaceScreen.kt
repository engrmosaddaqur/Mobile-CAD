package com.example.ui.workspace

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SaveAs
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.SquareFoot
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.cad.engine.ActiveTool
import com.example.cad.engine.CadWorkspaceUiState
import com.example.cad.engine.CadWorkspaceViewModel
import com.example.cad.engine.CommandProcessor
import com.example.cad.engine.WorkspacePanelTab
import com.example.cad.model.CadUnits
import com.example.data.ExportFormat
import com.example.ui.theme.CadAmber
import com.example.ui.theme.CadCoral
import com.example.ui.theme.CadCyan
import com.example.ui.theme.CadEmerald
import com.example.ui.theme.JetBrainsMonoFamily

@Composable
fun CadWorkspaceScreen(
    viewModel: CadWorkspaceViewModel,
    onNavigateBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val prefs by viewModel.userPreferences.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var showMoreMenu by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showSaveAsDialog by remember { mutableStateOf(false) }
    var showDimSettingsDialog by remember { mutableStateOf(false) }
    var showOsnapDialog by remember { mutableStateOf(false) }
    var showUnitsDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showHistoryDialog by remember { mutableStateOf(false) }
    var pendingExportFormat by remember { mutableStateOf<ExportFormat?>(null) }
    var commandText by remember { mutableStateOf("") }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.importFromUri(it) }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val fmt = pendingExportFormat
        if (uri != null && fmt != null) {
            viewModel.exportToUri(uri, fmt)
        }
        pendingExportFormat = null
    }

    LaunchedEffect(state.statusBannerMessage) {
        state.statusBannerMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearBanner()
        }
    }

    BackHandler {
        if (state.activeTool !is ActiveTool.Select) {
            viewModel.selectTool(ActiveTool.Select)
        } else {
            onNavigateBack()
        }
    }

    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { _ ->
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
            // 1. TOP BAR: Back | Drawing Name | Save | Undo | Redo | More Options
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("workspace_back_btn")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { showRenameDialog = true }
                            .padding(horizontal = 6.dp)
                    ) {
                        Text(
                            text = state.document.name + if (state.document.isDirty) " *" else "",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${state.document.sourceFormat.label} • ${state.document.entities.size} entities • Layer: ${state.document.currentLayerName}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    IconButton(
                        onClick = { viewModel.saveDrawing() },
                        modifier = Modifier.testTag("workspace_save_btn")
                    ) {
                        Icon(
                            Icons.Default.Save,
                            contentDescription = stringResource(R.string.cd_save),
                            tint = if (state.document.isDirty) CadAmber else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(
                        onClick = { viewModel.undo() },
                        enabled = state.canUndo,
                        modifier = Modifier.testTag("workspace_undo_btn")
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Undo,
                            contentDescription = state.undoDescription?.let { "Undo $it" }
                                ?: stringResource(R.string.cd_undo)
                        )
                    }
                    IconButton(
                        onClick = { viewModel.redo() },
                        enabled = state.canRedo,
                        modifier = Modifier.testTag("workspace_redo_btn")
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Redo,
                            contentDescription = state.redoDescription?.let { "Redo $it" }
                                ?: stringResource(R.string.cd_redo)
                        )
                    }
                    Box {
                        IconButton(
                            onClick = { showMoreMenu = true },
                            modifier = Modifier.testTag("workspace_more_btn")
                        ) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.cd_more_options))
                        }
                        DropdownMenu(expanded = showMoreMenu, onDismissRequest = { showMoreMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Save As...") },
                                leadingIcon = { Icon(Icons.Default.SaveAs, null) },
                                onClick = { showMoreMenu = false; showSaveAsDialog = true }
                            )
                            DropdownMenuItem(
                                text = { Text("Import DXF / DWG...") },
                                leadingIcon = { Icon(Icons.Default.UploadFile, null) },
                                onClick = { showMoreMenu = false; importLauncher.launch(arrayOf("*/*")) }
                            )
                            DropdownMenuItem(
                                text = { Text("Export (DXF / PDF / SVG / PNG / JPG)...") },
                                leadingIcon = { Icon(Icons.Default.FileDownload, null) },
                                onClick = { showMoreMenu = false; showExportDialog = true }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Select All") },
                                leadingIcon = { Icon(Icons.Default.SelectAll, null) },
                                onClick = { showMoreMenu = false; viewModel.selectAll() }
                            )
                            DropdownMenuItem(
                                text = { Text("Deselect All") },
                                leadingIcon = { Icon(Icons.Default.Close, null) },
                                onClick = { showMoreMenu = false; viewModel.deselectAll() }
                            )
                            DropdownMenuItem(
                                text = { Text("Undo / Redo History (${state.undoCount} / ${state.redoCount})...") },
                                leadingIcon = { Icon(Icons.Default.History, null) },
                                onClick = { showMoreMenu = false; showHistoryDialog = true }
                            )
                            DropdownMenuItem(
                                text = { Text("Drawing Units (${state.document.units.shortLabel})") },
                                leadingIcon = { Icon(Icons.Default.Straighten, null) },
                                onClick = { showMoreMenu = false; showUnitsDialog = true }
                            )
                        }
                    }
                }
            }

            if (state.isBusy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            // 2. MAIN WORKSPACE (Adaptive Portrait vs Landscape)
            if (isLandscape) {
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        CadCanvasView(
                            state = state,
                            canvasTheme = prefs.canvasTheme,
                            showCrosshairCoords = prefs.showCrosshairCoords,
                            onViewportSizeChanged = viewModel::onViewportSizeChanged,
                            onPinchOrPan = viewModel::onPinchOrPan,
                            onMouseWheelZoom = viewModel::onMouseWheelZoom,
                            onPointerHover = viewModel::onPointerHover,
                            onTap = viewModel::onCanvasTap,
                            onDoubleTap = viewModel::zoomExtents,
                            onSingleDragStart = viewModel::onSingleFingerDragStart,
                            onSingleDrag = viewModel::onSingleFingerDrag,
                            onSingleDragEnd = viewModel::onSingleFingerDragEnd,
                            onZoomExtents = viewModel::zoomExtents,
                            onZoomAll = viewModel::zoomAll,
                            onZoomWindow = { viewModel.selectTool(ActiveTool.ZoomWindow) },
                            onZoomPrevious = viewModel::zoomPrevious,
                            onTogglePanTool = {
                                val next = if (state.activeTool is ActiveTool.Pan) ActiveTool.Select else ActiveTool.Pan
                                viewModel.selectTool(next)
                            }
                        )
                    }
                    // Side tool strip in landscape for spacious drafting
                    if (state.activePanelTab != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier
                                .width(270.dp)
                                .fillMaxHeight()
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            ActivePanelHost(
                                state = state,
                                viewModel = viewModel,
                                onOpenDimSettings = { showDimSettingsDialog = true }
                            )
                        }
                    }
                }
            } else {
                // Portrait: Maximize canvas, expandable bottom panel
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    CadCanvasView(
                        state = state,
                        canvasTheme = prefs.canvasTheme,
                        showCrosshairCoords = prefs.showCrosshairCoords,
                        onViewportSizeChanged = viewModel::onViewportSizeChanged,
                        onPinchOrPan = viewModel::onPinchOrPan,
                        onMouseWheelZoom = viewModel::onMouseWheelZoom,
                        onPointerHover = viewModel::onPointerHover,
                        onTap = viewModel::onCanvasTap,
                        onDoubleTap = viewModel::zoomExtents,
                        onSingleDragStart = viewModel::onSingleFingerDragStart,
                        onSingleDrag = viewModel::onSingleFingerDrag,
                        onSingleDragEnd = viewModel::onSingleFingerDragEnd,
                        onZoomExtents = viewModel::zoomExtents,
                        onZoomAll = viewModel::zoomAll,
                        onZoomWindow = { viewModel.selectTool(ActiveTool.ZoomWindow) },
                        onZoomPrevious = viewModel::zoomPrevious,
                        onTogglePanTool = {
                            val next = if (state.activeTool is ActiveTool.Pan) ActiveTool.Select else ActiveTool.Pan
                            viewModel.selectTool(next)
                        }
                    )
                }

                AnimatedVisibility(visible = state.activePanelTab != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 2.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        ActivePanelHost(
                            state = state,
                            viewModel = viewModel,
                            onOpenDimSettings = { showDimSettingsDialog = true }
                        )
                    }
                }
            }

            // 3. BOTTOM TOOLBAR: Draw | Modify | Dimension | Layers | Measure | Properties
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    WorkspaceTabButton(Icons.Default.Edit, "Draw", state.activePanelTab == WorkspacePanelTab.DRAW, "tab_draw") {
                        viewModel.setActivePanelTab(WorkspacePanelTab.DRAW)
                    }
                    WorkspaceTabButton(Icons.Default.Tune, "Modify", state.activePanelTab == WorkspacePanelTab.MODIFY, "tab_modify") {
                        viewModel.setActivePanelTab(WorkspacePanelTab.MODIFY)
                    }
                    WorkspaceTabButton(Icons.Default.Straighten, "Dimension", state.activePanelTab == WorkspacePanelTab.DIMENSION, "tab_dimension") {
                        viewModel.setActivePanelTab(WorkspacePanelTab.DIMENSION)
                    }
                    WorkspaceTabButton(Icons.Default.Layers, "Layers", state.activePanelTab == WorkspacePanelTab.LAYERS, "tab_layers") {
                        viewModel.setActivePanelTab(WorkspacePanelTab.LAYERS)
                    }
                    WorkspaceTabButton(Icons.Default.SquareFoot, "Measure", state.activePanelTab == WorkspacePanelTab.MEASURE, "tab_measure") {
                        viewModel.setActivePanelTab(WorkspacePanelTab.MEASURE)
                    }
                    WorkspaceTabButton(Icons.Default.Tune, "Properties", state.activePanelTab == WorkspacePanelTab.PROPERTIES, "tab_properties") {
                        viewModel.setActivePanelTab(WorkspacePanelTab.PROPERTIES)
                    }
                }
            }

            // 4. BOTTOM SECTION: Model / Layout 1 / Layout 2 Tabs + Command Line + Status Bar
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Model & Layout Tabs
                    ModelLayoutTabsRow(
                        state = state,
                        onSwitchLayout = viewModel::switchLayout,
                        onAddLayout = viewModel::addLayout,
                        onRenameLayout = viewModel::renameLayout,
                        onDeleteLayout = viewModel::deleteLayout
                    )

                    // Command Line Interface
                    CommandLineBar(
                        state = state,
                        commandText = commandText,
                        onCommandTextChange = { commandText = it },
                        onSubmit = {
                            viewModel.submitCommandInput(commandText)
                            commandText = ""
                        },
                        onToggleExpand = viewModel::toggleCommandBarExpanded
                    )

                    // CAD Precision Status Bar (OSNAP, GRID, GSNAP, ORTHO, POLAR, DYN)
                    CadStatusBar(
                        state = state,
                        onToggleOsnap = viewModel::toggleOsnap,
                        onOpenOsnapMenu = { showOsnapDialog = true },
                        onToggleGrid = viewModel::toggleGrid,
                        onToggleGridSnap = viewModel::toggleGridSnap,
                        onToggleOrtho = viewModel::toggleOrtho,
                        onTogglePolar = viewModel::togglePolar,
                        onToggleDynamicInput = viewModel::toggleDynamicInput
                    )
                }
            }
        }

            AnimatedVisibility(visible = state.isBusy) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.48f))
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) {}
                        .testTag("workspace_loading_overlay"),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        tonalElevation = 6.dp,
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier
                            .padding(24.dp)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator(color = CadCyan)
                            Text(
                                text = state.loadingMessage,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Preparing CAD geometry and spatial index...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    // Dialogs
    if (showDimSettingsDialog) {
        DimensionSettingsDialog(
            style = state.document.dimensionStyle,
            onDismiss = { showDimSettingsDialog = false },
            onSave = {
                viewModel.updateDimensionStyle(it)
                showDimSettingsDialog = false
            }
        )
    }

    if (showOsnapDialog) {
        OsnapSettingsDialog(
            enabledModes = state.snapSettings.enabledOsnaps,
            onToggleMode = viewModel::toggleOsnapMode,
            onDismiss = { showOsnapDialog = false }
        )
    }

    if (showExportDialog) {
        ExportFormatDialog(
            drawingName = state.document.name,
            onPickFormat = { fmt ->
                showExportDialog = false
                pendingExportFormat = fmt
                exportLauncher.launch("${state.document.name}.${fmt.extension}")
            },
            onDismiss = { showExportDialog = false }
        )
    }

    if (showSaveAsDialog) {
        SaveAsDialog(
            currentName = state.document.name,
            onConfirm = { newName ->
                viewModel.saveAsCopy(newName)
                showSaveAsDialog = false
            },
            onDismiss = { showSaveAsDialog = false }
        )
    }

    if (showUnitsDialog) {
        AlertDialog(
            onDismissRequest = { showUnitsDialog = false },
            title = { Text("Drawing Units") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    CadUnits.entries.forEach { u ->
                        FilterChip(
                            selected = state.document.units == u,
                            onClick = { viewModel.updateUnits(u); showUnitsDialog = false },
                            label = { Text(u.displayName) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showUnitsDialog = false }) { Text("Close") } }
        )
    }

    if (showRenameDialog) {
        var editedName by remember(state.document.name) { mutableStateOf(state.document.name) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename Current Drawing") },
            text = {
                OutlinedTextField(value = editedName, onValueChange = { editedName = it }, label = { Text("Name") }, singleLine = true)
            },
            confirmButton = {
                Button(onClick = { viewModel.saveDrawing(editedName); showRenameDialog = false }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") } }
        )
    }

    if (showHistoryDialog) {
        UndoRedoHistoryDialog(
            state = state,
            onUndoSteps = { steps ->
                viewModel.undo(steps)
                showHistoryDialog = false
            },
            onRedoSteps = { steps ->
                viewModel.redo(steps)
                showHistoryDialog = false
            },
            onDismiss = { showHistoryDialog = false }
        )
    }

    state.errorDialogPair?.let { (title, message) ->
        AlertDialog(
            onDismissRequest = viewModel::dismissErrorDialog,
            title = { Text(title) },
            text = { Text(message) },
            confirmButton = { Button(onClick = viewModel::dismissErrorDialog) { Text("OK") } }
        )
    }
}

@Composable
private fun UndoRedoHistoryDialog(
    state: CadWorkspaceUiState,
    onUndoSteps: (Int) -> Unit,
    onRedoSteps: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Undo / Redo History") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { onUndoSteps(1) },
                        enabled = state.canUndo,
                        modifier = Modifier.weight(1f).testTag("history_undo_one_btn")
                    ) {
                        Text("Undo (${state.undoCount})")
                    }
                    OutlinedButton(
                        onClick = { onRedoSteps(1) },
                        enabled = state.canRedo,
                        modifier = Modifier.weight(1f).testTag("history_redo_one_btn")
                    ) {
                        Text("Redo (${state.redoCount})")
                    }
                }
                if (state.undoHistoryLabels.isEmpty() && state.redoHistoryLabels.isEmpty()) {
                    Text(
                        text = "No drafting history recorded yet in this session.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (state.redoHistoryLabels.isNotEmpty()) {
                            item {
                                Text(
                                    text = "REDO STACK (Tap to reapply)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = CadAmber
                                )
                            }
                            items(state.redoHistoryLabels.size) { index ->
                                val label = state.redoHistoryLabels[index]
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { onRedoSteps(index + 1) }
                                ) {
                                    Text(
                                        text = "+${index + 1}  $label",
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                                    )
                                }
                            }
                        }
                        if (state.undoHistoryLabels.isNotEmpty()) {
                            item {
                                Text(
                                    text = "UNDO STACK (Tap to revert)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = CadCyan,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                            items(state.undoHistoryLabels.size) { index ->
                                val label = state.undoHistoryLabels[index]
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { onUndoSteps(index + 1) }
                                ) {
                                    Text(
                                        text = "-${index + 1}  $label",
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun ActivePanelHost(
    state: CadWorkspaceUiState,
    viewModel: CadWorkspaceViewModel,
    onOpenDimSettings: () -> Unit
) {
    when (state.activePanelTab) {
        WorkspacePanelTab.DRAW -> DrawToolPanel(
            activeTool = state.activeTool,
            sessionPointsCount = state.session.collectedPoints.size,
            config = state.toolConfig,
            onSelectTool = viewModel::selectTool,
            onUpdateConfig = viewModel::updateToolConfig,
            onFinishShape = viewModel::finishActiveTool
        )
        WorkspacePanelTab.MODIFY -> ModifyToolPanel(
            activeTool = state.activeTool,
            selectedCount = state.selectedEntities.size,
            config = state.toolConfig,
            onSelectTool = viewModel::selectTool,
            onUpdateConfig = viewModel::updateToolConfig,
            onSelectAll = viewModel::selectAll,
            onDeselectAll = viewModel::deselectAll
        )
        WorkspacePanelTab.DIMENSION -> DimensionToolPanel(
            activeTool = state.activeTool,
            onSelectDimension = { viewModel.selectTool(ActiveTool.Dimension(it)) },
            onOpenDimensionSettings = onOpenDimSettings
        )
        WorkspacePanelTab.LAYERS -> LayerManagerPanel(
            doc = state.document,
            onCreateLayer = viewModel::createLayer,
            onSetCurrentLayer = viewModel::setCurrentLayer,
            onUpdateLayer = viewModel::updateLayer,
            onDeleteLayer = viewModel::deleteLayer
        )
        WorkspacePanelTab.MEASURE -> MeasureToolPanel(
            state = state,
            onSelectMeasure = { viewModel.selectTool(ActiveTool.Measure(it)) },
            onClearMeasurement = viewModel::clearMeasurement
        )
        WorkspacePanelTab.PROPERTIES -> PropertiesPanel(
            doc = state.document,
            selectedEntities = state.selectedEntities,
            onUpdateProperties = viewModel::updateSelectedProperties,
            onUpdateGeometry = viewModel::updateSingleEntityGeometry
        )
        null -> Unit
    }
}

@Composable
private fun WorkspaceTabButton(
    icon: ImageVector,
    label: String,
    active: Boolean,
    tag: String,
    onClick: () -> Unit
) {
    val bg = if (active) CadCyan.copy(alpha = 0.2f) else Color.Transparent
    val tint = if (active) CadCyan else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(16.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = tint)
    }
}

@Composable
private fun ModelLayoutTabsRow(
    state: CadWorkspaceUiState,
    onSwitchLayout: (String) -> Unit,
    onAddLayout: () -> Unit,
    onRenameLayout: (String, String) -> Unit,
    onDeleteLayout: (String) -> Unit
) {
    var manageTargetId by remember { mutableStateOf<String?>(null) }

    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items(state.document.layouts, key = { it.id }) { layout ->
            val selected = layout.id == state.document.activeLayoutId
            Surface(
                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier
                    .border(1.dp, if (selected) CadCyan else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
                    .clip(RoundedCornerShape(6.dp))
                    .clickable {
                        if (selected && !layout.isModelSpace) manageTargetId = layout.id
                        else onSwitchLayout(layout.id)
                    }
                    .testTag("layout_tab_${layout.name.lowercase().replace(" ", "_")}")
            ) {
                Text(
                    text = layout.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) CadCyan else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
        }
        item {
            IconButton(
                onClick = onAddLayout,
                modifier = Modifier.size(28.dp).testTag("add_layout_btn")
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Layout", modifier = Modifier.size(16.dp))
            }
        }
    }

    manageTargetId?.let { lid ->
        val layout = state.document.layouts.firstOrNull { it.id == lid }
        if (layout != null) {
            var newName by remember(lid) { mutableStateOf(layout.name) }
            AlertDialog(
                onDismissRequest = { manageTargetId = null },
                title = { Text("Manage ${layout.name}") },
                text = {
                    OutlinedTextField(value = newName, onValueChange = { newName = it }, label = { Text("Layout Name") }, singleLine = true)
                },
                confirmButton = {
                    Button(onClick = { onRenameLayout(lid, newName); manageTargetId = null }) { Text("Rename") }
                },
                dismissButton = {
                    TextButton(onClick = { onDeleteLayout(lid); manageTargetId = null }) { Text("Delete", color = CadCoral) }
                }
            )
        }
    }
}

@Composable
private fun CommandLineBar(
    state: CadWorkspaceUiState,
    commandText: String,
    onCommandTextChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onToggleExpand: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        AnimatedVisibility(visible = state.commandBarExpanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0B1017), RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                LazyColumn(modifier = Modifier.heightIn(max = 110.dp)) {
                    items(state.commandHistory) { entry ->
                        Text(
                            text = entry.text,
                            fontFamily = JetBrainsMonoFamily,
                            style = MaterialTheme.typography.labelSmall,
                            color = when {
                                entry.isError -> CadCoral
                                entry.isCommand -> CadCyan
                                else -> Color(0xFFECF2F8)
                            }
                        )
                    }
                }
                if (commandText.isNotBlank()) {
                    val suggestions = CommandProcessor.allKnownCommands.filter {
                        it.startsWith(commandText.trim().uppercase())
                    }.take(6)
                    if (suggestions.isNotEmpty()) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            items(suggestions) { cmd ->
                                FilterChip(
                                    selected = false,
                                    onClick = { onCommandTextChange(cmd) },
                                    label = { Text(cmd, fontFamily = JetBrainsMonoFamily) }
                                )
                            }
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            IconButton(
                onClick = onToggleExpand,
                modifier = Modifier.size(34.dp).testTag("toggle_cmd_history_btn")
            ) {
                Icon(
                    if (state.commandBarExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                    contentDescription = "Expand command history",
                    tint = CadCyan,
                    modifier = Modifier.size(18.dp)
                )
            }
            OutlinedTextField(
                value = commandText,
                onValueChange = onCommandTextChange,
                placeholder = {
                    Text(
                        state.commandPrompt,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                leadingIcon = { Icon(Icons.Default.Terminal, null, modifier = Modifier.size(16.dp), tint = CadCyan) },
                singleLine = true,
                textStyle = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSubmit() }),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("command_line_input")
            )
            IconButton(
                onClick = onSubmit,
                modifier = Modifier.size(40.dp).testTag("command_line_send_btn")
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Execute command", tint = CadCyan, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun CadStatusBar(
    state: CadWorkspaceUiState,
    onToggleOsnap: () -> Unit,
    onOpenOsnapMenu: () -> Unit,
    onToggleGrid: () -> Unit,
    onToggleGridSnap: () -> Unit,
    onToggleOrtho: () -> Unit,
    onTogglePolar: () -> Unit,
    onToggleDynamicInput: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatusPill("OSNAP", state.snapSettings.osnapEnabled, "status_osnap", onToggleOsnap)
        StatusPill("SNAPS…", false, "status_osnap_cfg", onOpenOsnapMenu)
        StatusPill("GRID", state.snapSettings.gridVisible, "status_grid", onToggleGrid)
        StatusPill("GSNAP", state.snapSettings.gridSnapEnabled, "status_gsnap", onToggleGridSnap)
        StatusPill("ORTHO", state.snapSettings.orthoMode, "status_ortho", onToggleOrtho)
        StatusPill("POLAR", state.snapSettings.polarTracking, "status_polar", onTogglePolar)
        StatusPill("DYN", state.snapSettings.dynamicInput, "status_dyn", onToggleDynamicInput)
        Spacer(Modifier.width(4.dp))
        Text(
            text = "${state.document.units.shortLabel.uppercase()} • ${state.document.currentLayerName}",
            fontFamily = JetBrainsMonoFamily,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StatusPill(label: String, active: Boolean, tag: String, onClick: () -> Unit) {
    val bg = if (active) CadEmerald.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surface
    val fg = if (active) CadEmerald else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        color = bg,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier
            .border(1.dp, if (active) CadEmerald else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .testTag(tag)
    ) {
        Text(
            text = label,
            fontFamily = JetBrainsMonoFamily,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
