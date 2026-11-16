package com.example.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.Architecture
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.ChangeHistory
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ControlCamera
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Hexagon
import androidx.compose.material.icons.filled.JoinFull
import androidx.compose.material.icons.filled.LinearScale
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Polyline
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.RoundedCorner
import androidx.compose.material.icons.filled.SquareFoot
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.cad.engine.ActiveTool
import com.example.cad.engine.CadWorkspaceUiState
import com.example.cad.engine.DrawTool
import com.example.cad.engine.MeasureTool
import com.example.cad.engine.ModifyTool
import com.example.cad.engine.ToolNumericConfig
import com.example.cad.model.DimensionType
import com.example.ui.theme.CadAmber
import com.example.ui.theme.CadCyan
import com.example.ui.theme.JetBrainsMonoFamily

@Composable
fun DrawToolPanel(
    activeTool: ActiveTool,
    sessionPointsCount: Int,
    config: ToolNumericConfig,
    onSelectTool: (ActiveTool) -> Unit,
    onUpdateConfig: ((ToolNumericConfig) -> ToolNumericConfig) -> Unit,
    onFinishShape: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(DrawTool.entries) { tool ->
                val isSelected = activeTool == ActiveTool.Draw(tool)
                ToolGridChip(
                    icon = drawToolIcon(tool),
                    label = tool.label,
                    selected = isSelected,
                    tag = "draw_tool_${tool.commandName.lowercase()}",
                    onClick = { onSelectTool(ActiveTool.Draw(tool)) }
                )
            }
        }

        // Contextual controls for Polyline/Spline/Line or Polygon sides
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (activeTool is ActiveTool.Draw && activeTool.tool == DrawTool.POLYGON) {
                Text("Sides: ${config.polygonSides}", style = MaterialTheme.typography.labelMedium, fontFamily = JetBrainsMonoFamily)
                TextButton(onClick = { onUpdateConfig { it.copy(polygonSides = (it.polygonSides - 1).coerceAtLeast(3)) } }) { Text("-1") }
                TextButton(onClick = { onUpdateConfig { it.copy(polygonSides = (it.polygonSides + 1).coerceAtMost(32)) } }) { Text("+1") }
            }
            if (sessionPointsCount > 0 && activeTool is ActiveTool.Draw) {
                if (activeTool.tool == DrawTool.POLYLINE || activeTool.tool == DrawTool.SPLINE) {
                    if (sessionPointsCount >= 3) {
                        OutlinedButton(onClick = { onFinishShape(true) }, modifier = Modifier.height(34.dp)) {
                            Text("Close", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                Button(onClick = { onFinishShape(false) }, modifier = Modifier.height(34.dp)) {
                    Text("Done", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
fun ModifyToolPanel(
    activeTool: ActiveTool,
    selectedCount: Int,
    config: ToolNumericConfig,
    onSelectTool: (ActiveTool) -> Unit,
    onUpdateConfig: ((ToolNumericConfig) -> ToolNumericConfig) -> Unit,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(ModifyTool.entries) { tool ->
                val isSelected = activeTool == ActiveTool.Modify(tool)
                ToolGridChip(
                    icon = modifyToolIcon(tool),
                    label = tool.label,
                    selected = isSelected,
                    tag = "modify_tool_${tool.commandName.lowercase()}",
                    onClick = { onSelectTool(ActiveTool.Modify(tool)) }
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "$selectedCount selected",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            TextButton(onClick = onSelectAll) { Text("Select All") }
            if (selectedCount > 0) {
                TextButton(onClick = onDeselectAll) { Text("Clear") }
            }

            if (activeTool is ActiveTool.Modify) {
                Spacer(Modifier.weight(1f))
                ModifyParamBadge(activeTool.tool, config, onUpdateConfig)
            }
        }
    }
}

@Composable
private fun ModifyParamBadge(
    tool: ModifyTool,
    config: ToolNumericConfig,
    onUpdateConfig: ((ToolNumericConfig) -> ToolNumericConfig) -> Unit
) {
    var editing by remember(tool) { mutableStateOf(false) }
    val label = when (tool) {
        ModifyTool.OFFSET -> "Dist: ${config.offsetDistance}"
        ModifyTool.FILLET -> "Radius: ${config.filletRadius}"
        ModifyTool.CHAMFER -> "D: ${config.chamferDist1}×${config.chamferDist2}"
        ModifyTool.ROTATE -> "Angle: ${config.rotateAngleDeg}°"
        ModifyTool.SCALE -> "Factor: ${config.scaleFactor}x"
        ModifyTool.ARRAY -> if (config.arrayPolarMode) "Polar ${config.arrayPolarCount}" else "${config.arrayRows}×${config.arrayCols}"
        else -> null
    } ?: return

    FilterChip(
        selected = editing,
        onClick = { editing = !editing },
        label = { Text(label, fontFamily = JetBrainsMonoFamily) },
        leadingIcon = { Icon(Icons.Default.Tune, null, modifier = Modifier.size(16.dp)) }
    )

    if (editing) {
        var val1 by remember(tool) {
            mutableStateOf(
                when (tool) {
                    ModifyTool.OFFSET -> config.offsetDistance.toString()
                    ModifyTool.FILLET -> config.filletRadius.toString()
                    ModifyTool.CHAMFER -> config.chamferDist1.toString()
                    ModifyTool.ROTATE -> config.rotateAngleDeg.toString()
                    ModifyTool.SCALE -> config.scaleFactor.toString()
                    ModifyTool.ARRAY -> if (config.arrayPolarMode) config.arrayPolarCount.toString() else config.arrayCols.toString()
                    else -> "10"
                }
            )
        }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("${tool.label} Parameter") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (tool == ModifyTool.ARRAY) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = !config.arrayPolarMode,
                                onClick = { onUpdateConfig { it.copy(arrayPolarMode = false) } },
                                label = { Text("Rectangular") }
                            )
                            FilterChip(
                                selected = config.arrayPolarMode,
                                onClick = { onUpdateConfig { it.copy(arrayPolarMode = true) } },
                                label = { Text("Polar") }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = val1,
                        onValueChange = { val1 = it },
                        label = {
                            Text(
                                when (tool) {
                                    ModifyTool.OFFSET -> "Offset Distance"
                                    ModifyTool.FILLET -> "Fillet Radius (0 = sharp corner)"
                                    ModifyTool.CHAMFER -> "Chamfer Distance"
                                    ModifyTool.ROTATE -> "Rotation Angle (deg)"
                                    ModifyTool.SCALE -> "Scale Factor"
                                    ModifyTool.ARRAY -> if (config.arrayPolarMode) "Item Count" else "Columns"
                                    else -> "Value"
                                }
                            )
                        },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val num = val1.toDoubleOrNull()
                    if (num != null) {
                        onUpdateConfig { c ->
                            when (tool) {
                                ModifyTool.OFFSET -> c.copy(offsetDistance = num.coerceAtLeast(0.1))
                                ModifyTool.FILLET -> c.copy(filletRadius = num.coerceAtLeast(0.0))
                                ModifyTool.CHAMFER -> c.copy(chamferDist1 = num.coerceAtLeast(0.1), chamferDist2 = num.coerceAtLeast(0.1))
                                ModifyTool.ROTATE -> c.copy(rotateAngleDeg = num)
                                ModifyTool.SCALE -> c.copy(scaleFactor = num.coerceAtLeast(0.05))
                                ModifyTool.ARRAY -> if (c.arrayPolarMode) c.copy(arrayPolarCount = num.toInt().coerceIn(2, 36)) else c.copy(arrayCols = num.toInt().coerceIn(1, 20))
                                else -> c
                            }
                        }
                    }
                    editing = false
                }) { Text("Apply") }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } }
        )
    }
}

@Composable
fun DimensionToolPanel(
    activeTool: ActiveTool,
    onSelectDimension: (DimensionType) -> Unit,
    onOpenDimensionSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(DimensionType.entries) { type ->
                ToolGridChip(
                    icon = Icons.Default.Straighten,
                    label = type.label,
                    selected = activeTool == ActiveTool.Dimension(type),
                    tag = "dim_tool_${type.commandName.lowercase()}",
                    onClick = { onSelectDimension(type) }
                )
            }
            item {
                ToolGridChip(
                    icon = Icons.Default.Tune,
                    label = "Dim Style",
                    selected = false,
                    tag = "dim_settings_btn",
                    onClick = onOpenDimensionSettings
                )
            }
        }
    }
}

@Composable
fun MeasureToolPanel(
    state: CadWorkspaceUiState,
    onSelectMeasure: (MeasureTool) -> Unit,
    onClearMeasurement: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(MeasureTool.entries) { tool ->
                ToolGridChip(
                    icon = Icons.Default.SquareFoot,
                    label = tool.label,
                    selected = state.activeTool == ActiveTool.Measure(tool),
                    tag = "measure_tool_${tool.commandName.lowercase()}",
                    onClick = { onSelectMeasure(tool) }
                )
            }
            if (state.measurement != null) {
                item {
                    OutlinedButton(onClick = onClearMeasurement) { Text("Clear Readout") }
                }
            }
        }
    }
}

@Composable
fun ToolGridChip(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit
) {
    val bg = if (selected) CadCyan.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant
    val border = if (selected) CadCyan else MaterialTheme.colorScheme.outlineVariant
    val tint = if (selected) CadCyan else MaterialTheme.colorScheme.onSurface

    Column(
        modifier = Modifier
            .width(68.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp)
            .testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun drawToolIcon(tool: DrawTool): ImageVector = when (tool) {
    DrawTool.LINE -> Icons.Default.LinearScale
    DrawTool.POLYLINE -> Icons.Default.Polyline
    DrawTool.TRIANGLE -> Icons.Default.ChangeHistory
    DrawTool.RECTANGLE -> Icons.Default.CropSquare
    DrawTool.CIRCLE -> Icons.Default.RadioButtonUnchecked
    DrawTool.POLYGON -> Icons.Default.Hexagon
    DrawTool.ARC -> Icons.Default.RoundedCorner
    DrawTool.ELLIPSE -> Icons.Default.AddCircleOutline
    DrawTool.SPLINE -> Icons.Default.Gesture
    DrawTool.POINT -> Icons.Default.MyLocation
    DrawTool.RAY -> Icons.AutoMirrored.Filled.CallMade
    DrawTool.XLINE -> Icons.Default.Timeline
}

private fun modifyToolIcon(tool: ModifyTool): ImageVector = when (tool) {
    ModifyTool.MOVE -> Icons.Default.ControlCamera
    ModifyTool.COPY -> Icons.Default.ContentCopy
    ModifyTool.ROTATE -> Icons.AutoMirrored.Filled.RotateRight
    ModifyTool.MIRROR -> Icons.Default.Flip
    ModifyTool.OFFSET -> Icons.Default.OpenInFull
    ModifyTool.TRIM -> Icons.Default.ContentCut
    ModifyTool.EXTEND -> Icons.AutoMirrored.Filled.CallMade
    ModifyTool.STRETCH -> Icons.Default.ZoomOutMap
    ModifyTool.FILLET -> Icons.Default.RoundedCorner
    ModifyTool.CHAMFER -> Icons.Default.Architecture
    ModifyTool.SCALE -> Icons.Default.ZoomOutMap
    ModifyTool.ARRAY -> Icons.Default.GridView
    ModifyTool.EXPLODE -> Icons.Default.AutoFixHigh
    ModifyTool.JOIN -> Icons.Default.JoinFull
    ModifyTool.BREAK -> Icons.AutoMirrored.Filled.MergeType
    ModifyTool.ERASE -> Icons.Default.DeleteSweep
    ModifyTool.DELETE -> Icons.Default.Delete
    ModifyTool.UNDO -> Icons.AutoMirrored.Filled.Undo
    ModifyTool.REDO -> Icons.AutoMirrored.Filled.Redo
}
