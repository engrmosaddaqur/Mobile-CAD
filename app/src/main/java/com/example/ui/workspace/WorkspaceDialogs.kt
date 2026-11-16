package com.example.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.example.cad.model.AciColors
import com.example.cad.model.CadUnits
import com.example.cad.model.DimensionStyle
import com.example.cad.model.DimensionTextPosition
import com.example.cad.model.OsnapType
import com.example.data.ExportFormat
import com.example.ui.theme.CadCyan

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DimensionSettingsDialog(
    style: DimensionStyle,
    onDismiss: () -> Unit,
    onSave: (DimensionStyle) -> Unit
) {
    var textHeight by remember { mutableStateOf(style.textHeight.toString()) }
    var arrowSize by remember { mutableStateOf(style.arrowSize.toString()) }
    var scaleFactor by remember { mutableStateOf(style.scaleFactor.toString()) }
    var precision by remember { mutableStateOf(style.decimalPrecision) }
    var units by remember { mutableStateOf(style.units) }
    var textPos by remember { mutableStateOf(style.textPosition) }
    var lineColor by remember { mutableStateOf(style.lineColorArgb) }
    var textColor by remember { mutableStateOf(style.textColorArgb) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Dimension Settings (${style.name})") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = textHeight,
                        onValueChange = { textHeight = it },
                        label = { Text("Text Height") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = arrowSize,
                        onValueChange = { arrowSize = it },
                        label = { Text("Arrow Size") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                OutlinedTextField(
                    value = scaleFactor,
                    onValueChange = { scaleFactor = it },
                    label = { Text("Scale Factor") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Decimal Precision", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (0..4).forEach { p ->
                        FilterChip(selected = precision == p, onClick = { precision = p }, label = { Text(".$p") })
                    }
                }
                Text("Measurement Units", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CadUnits.entries.forEach { u ->
                        FilterChip(selected = units == u, onClick = { units = u }, label = { Text(u.shortLabel) })
                    }
                }
                Text("Text Position", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DimensionTextPosition.entries.forEach { tp ->
                        FilterChip(selected = textPos == tp, onClick = { textPos = tp }, label = { Text(tp.label) })
                    }
                }
                Text("Line Color", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AciColors.presetSwatches.take(7).forEach { (_, argb) ->
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(Color(argb))
                                .border(if (lineColor == argb) 2.5.dp else 1.dp, if (lineColor == argb) CadCyan else Color.Gray, CircleShape)
                                .clickable { lineColor = argb }
                        )
                    }
                }
                Text("Text Color", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AciColors.presetSwatches.take(7).forEach { (_, argb) ->
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(Color(argb))
                                .border(if (textColor == argb) 2.5.dp else 1.dp, if (textColor == argb) CadCyan else Color.Gray, CircleShape)
                                .clickable { textColor = argb }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(
                    style.copy(
                        textHeight = textHeight.toDoubleOrNull()?.coerceIn(0.5, 50.0) ?: style.textHeight,
                        arrowSize = arrowSize.toDoubleOrNull()?.coerceIn(0.5, 40.0) ?: style.arrowSize,
                        scaleFactor = scaleFactor.toDoubleOrNull()?.coerceIn(0.001, 1000.0) ?: style.scaleFactor,
                        decimalPrecision = precision,
                        units = units,
                        textPosition = textPos,
                        lineColorArgb = lineColor,
                        textColorArgb = textColor
                    )
                )
            }) { Text("Save Style") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun OsnapSettingsDialog(
    enabledModes: Set<OsnapType>,
    onToggleMode: (OsnapType) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Object Snap (OSNAP) Modes") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OsnapType.entries.forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggleMode(mode) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = mode in enabledModes, onCheckedChange = { onToggleMode(mode) })
                        Spacer(Modifier.width(8.dp))
                        Text("${mode.label} (${mode.shortCode})", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("Done") } }
    )
}

@Composable
fun ExportFormatDialog(
    drawingName: String,
    onPickFormat: (ExportFormat) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export '$drawingName'") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ExportFormat.entries.forEach { fmt ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPickFormat(fmt) }
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(fmt.label, style = MaterialTheme.typography.titleSmall)
                                Text(".${fmt.extension.uppercase()} • ${fmt.mimeType}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(Icons.Default.Download, contentDescription = null, tint = CadCyan)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
fun SaveAsDialog(
    currentName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("${currentName}_v2") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save Drawing As") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("New Drawing Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = { Button(onClick = { onConfirm(name) }) { Text("Save Copy") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
