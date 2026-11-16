package com.example.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.cad.model.AciColors
import com.example.cad.model.CadDocument
import com.example.cad.model.CadEntity
import com.example.cad.model.CadEntity.*
import com.example.cad.model.CadLayer
import com.example.cad.model.CadLineType
import com.example.cad.model.Vec2
import com.example.ui.theme.CadAmber
import com.example.ui.theme.CadCyan
import com.example.ui.theme.JetBrainsMonoFamily
import java.util.Locale

@Composable
fun LayerManagerPanel(
    doc: CadDocument,
    onCreateLayer: (String, Int, Int) -> Unit,
    onSetCurrentLayer: (String) -> Unit,
    onUpdateLayer: (String, (CadLayer) -> CadLayer) -> Unit,
    onDeleteLayer: (String) -> Unit
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<CadLayer?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 220.dp)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "Layers (${doc.layers.size}) • Active: ${doc.currentLayerName}",
                style = MaterialTheme.typography.titleSmall
            )
            Button(
                onClick = { showCreateDialog = true },
                modifier = Modifier
                    .height(34.dp)
                    .testTag("create_layer_btn")
            ) {
                Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("New Layer", style = MaterialTheme.typography.labelMedium)
            }
        }
        Spacer(Modifier.height(6.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(doc.layers, key = { it.name }) { layer ->
                val isCurrent = layer.name == doc.currentLayerName
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                    else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSetCurrentLayer(layer.name) }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(Color(layer.colorArgb))
                                .border(1.dp, Color.White.copy(alpha = 0.4f), CircleShape)
                                .clickable {
                                    // Cycle preset ACI colors
                                    val swatches = AciColors.presetSwatches
                                    val idx = swatches.indexOfFirst { it.first == layer.aciColor }
                                    val next = swatches[(idx + 1) % swatches.size]
                                    onUpdateLayer(layer.name) { it.copy(aciColor = next.first, colorArgb = next.second) }
                                }
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(layer.name, style = MaterialTheme.typography.titleSmall)
                                if (isCurrent) {
                                    Icon(Icons.Default.CheckCircle, "Current layer", tint = CadCyan, modifier = Modifier.size(14.dp))
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    layer.lineType.displayName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable {
                                        val types = CadLineType.entries
                                        val next = types[(types.indexOf(layer.lineType) + 1) % types.size]
                                        onUpdateLayer(layer.name) { it.copy(lineType = next) }
                                    }
                                )
                                Text(
                                    "${layer.lineWeightMm}mm",
                                    fontFamily = JetBrainsMonoFamily,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.clickable {
                                        val weights = listOf(0.13f, 0.18f, 0.25f, 0.35f, 0.50f, 0.70f)
                                        val idx = weights.indexOfFirst { it == layer.lineWeightMm }
                                        val next = weights[(idx + 1) % weights.size]
                                        onUpdateLayer(layer.name) { it.copy(lineWeightMm = next) }
                                    }
                                )
                            }
                        }

                        // ON/OFF, Freeze, Lock, Rename, Delete
                        IconButton(onClick = { onUpdateLayer(layer.name) { it.copy(isOn = !it.isOn) } }, modifier = Modifier.size(34.dp)) {
                            Icon(
                                if (layer.isOn) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = "Toggle layer visibility",
                                tint = if (layer.isOn) CadCyan else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(onClick = { onUpdateLayer(layer.name) { it.copy(isFrozen = !it.isFrozen) } }, modifier = Modifier.size(34.dp)) {
                            Icon(
                                Icons.Default.AcUnit,
                                contentDescription = "Freeze/Unfreeze layer",
                                tint = if (layer.isFrozen) Color(0xFF80D8FF) else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(onClick = { onUpdateLayer(layer.name) { it.copy(isLocked = !it.isLocked) } }, modifier = Modifier.size(34.dp)) {
                            Icon(
                                if (layer.isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                                contentDescription = "Lock/Unlock layer",
                                tint = if (layer.isLocked) CadAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        if (layer.name != "0") {
                            IconButton(onClick = { renameTarget = layer }, modifier = Modifier.size(34.dp)) {
                                Icon(Icons.Default.DriveFileRenameOutline, "Rename layer", modifier = Modifier.size(18.dp))
                            }
                            IconButton(onClick = { onDeleteLayer(layer.name) }, modifier = Modifier.size(34.dp)) {
                                Icon(Icons.Default.DeleteOutline, "Delete layer", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        var newName by remember { mutableStateOf("LAYER_${doc.layers.size}") }
        var selectedSwatch by remember { mutableStateOf(AciColors.presetSwatches[4]) }
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("New CAD Layer") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("Layer Name") },
                        singleLine = true
                    )
                    Text("Layer Color (ACI)", style = MaterialTheme.typography.labelLarge)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(AciColors.presetSwatches) { sw ->
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(Color(sw.second))
                                    .border(
                                        width = if (sw == selectedSwatch) 3.dp else 1.dp,
                                        color = if (sw == selectedSwatch) CadCyan else Color.Gray,
                                        shape = CircleShape
                                    )
                                    .clickable { selectedSwatch = sw }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    onCreateLayer(newName, selectedSwatch.second, selectedSwatch.first)
                    showCreateDialog = false
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showCreateDialog = false }) { Text("Cancel") } }
        )
    }

    renameTarget?.let { target ->
        var edited by remember(target.name) { mutableStateOf(target.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename Layer") },
            text = {
                OutlinedTextField(value = edited, onValueChange = { edited = it }, label = { Text("Layer Name") }, singleLine = true)
            },
            confirmButton = {
                Button(onClick = {
                    if (edited.isNotBlank()) onUpdateLayer(target.name) { it.copy(name = edited.trim()) }
                    renameTarget = null
                }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } }
        )
    }
}

@Composable
fun PropertiesPanel(
    doc: CadDocument,
    selectedEntities: List<CadEntity>,
    onUpdateProperties: (
        layerName: String?,
        colorArgb: Int?,
        clearCustomColor: Boolean,
        lineType: CadLineType?,
        clearCustomLineType: Boolean,
        lineWeightMm: Float?,
        transparency: Float?
    ) -> Unit,
    onUpdateGeometry: (CadEntity) -> Unit
) {
    if (selectedEntities.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "Tap an entity on canvas or drag a Window/Crossing box to inspect & edit properties.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val first = selectedEntities.first()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 220.dp)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = if (selectedEntities.size == 1) "${first::class.simpleName?.removePrefix("Cad")} • Layer: ${first.layerName}"
            else "${selectedEntities.size} entities selected",
            style = MaterialTheme.typography.titleSmall,
            color = CadCyan
        )

        // Layer assignment chips
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(doc.layers) { layer ->
                FilterChip(
                    selected = first.layerName == layer.name,
                    onClick = { onUpdateProperties(layer.name, null, false, null, false, null, null) },
                    label = { Text(layer.name) }
                )
            }
        }

        // Color swatches + ByLayer
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            item {
                FilterChip(
                    selected = first.colorArgb == null,
                    onClick = { onUpdateProperties(null, null, true, null, false, null, null) },
                    label = { Text("ByLayer") }
                )
            }
            items(AciColors.presetSwatches) { (_, argb) ->
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(Color(argb))
                        .border(if (first.colorArgb == argb) 2.5.dp else 1.dp, if (first.colorArgb == argb) CadCyan else Color.Gray, CircleShape)
                        .clickable { onUpdateProperties(null, argb, false, null, false, null, null) }
                )
            }
        }

        // LineType + LineWeight + Transparency
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            item {
                FilterChip(
                    selected = first.lineType == null,
                    onClick = { onUpdateProperties(null, null, false, null, true, null, null) },
                    label = { Text("LT: ByLayer") }
                )
            }
            items(CadLineType.entries) { lt ->
                FilterChip(
                    selected = first.lineType == lt,
                    onClick = { onUpdateProperties(null, null, false, lt, false, null, null) },
                    label = { Text(lt.displayName) }
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Opacity", style = MaterialTheme.typography.labelSmall)
            Slider(
                value = 1f - first.transparency,
                onValueChange = { v -> onUpdateProperties(null, null, false, null, false, null, (1f - v).coerceIn(0f, 0.9f)) },
                valueRange = 0.1f..1f,
                modifier = Modifier.weight(1f)
            )
            Text("${((1f - first.transparency) * 100).toInt()}%", fontFamily = JetBrainsMonoFamily, style = MaterialTheme.typography.labelSmall)
        }

        // Single-entity exact geometry inspection & editing
        if (selectedEntities.size == 1) {
            SingleEntityGeometryEditor(first, onUpdateGeometry)
        }
    }
}

@Composable
private fun SingleEntityGeometryEditor(entity: CadEntity, onUpdateGeometry: (CadEntity) -> Unit) {
    when (entity) {
        is CadCircle -> {
            var rText by remember(entity.id, entity.radius) { mutableStateOf(String.format(Locale.US, "%.2f", entity.radius)) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = rText,
                    onValueChange = { rText = it },
                    label = { Text("Radius") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = {
                    rText.toDoubleOrNull()?.let { r ->
                        if (r > 0.01) onUpdateGeometry(entity.copy(radius = r))
                    }
                }) { Text("Set R") }
            }
        }
        is CadLine -> {
            val len = entity.start.distanceTo(entity.end)
            val ang = (entity.end - entity.start).angleDeg()
            Text(
                String.format(Locale.US, "Start (%.2f, %.2f) → End (%.2f, %.2f) | Len %.2f ∠%.1f°", entity.start.x, entity.start.y, entity.end.x, entity.end.y, len, ang),
                fontFamily = JetBrainsMonoFamily,
                style = MaterialTheme.typography.labelSmall
            )
        }
        is CadText -> {
            var txt by remember(entity.id, entity.text) { mutableStateOf(entity.text) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = txt, onValueChange = { txt = it }, label = { Text("Text") }, singleLine = true, modifier = Modifier.weight(1f))
                Button(onClick = { onUpdateGeometry(entity.copy(text = txt)) }) { Text("Apply") }
            }
        }
        is CadDimension -> {
            var note by remember(entity.id, entity.leaderLabel, entity.textOverride) {
                mutableStateOf(entity.textOverride ?: entity.leaderLabel)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Dimension / Callout Override") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = {
                    onUpdateGeometry(entity.copy(textOverride = note.ifBlank { null }, leaderLabel = note))
                }) { Text("Set") }
            }
        }
        else -> Unit
    }
}
