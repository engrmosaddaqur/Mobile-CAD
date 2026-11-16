package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.cad.io.DefaultDwgSdkBridge
import com.example.cad.model.CadUnits
import com.example.cad.model.CanvasBackgroundTheme
import com.example.data.UserPreferences
import com.example.data.UserPreferencesRepository
import com.example.ui.theme.AppThemeMode
import com.example.ui.theme.CadAmber
import com.example.ui.theme.CadEmerald
import com.example.ui.theme.JetBrainsMonoFamily
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    preferences: UserPreferences,
    prefsRepo: UserPreferencesRepository,
    onNavigateBack: () -> Unit
) {
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("CAD Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack, modifier = Modifier.testTag("settings_back_btn")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .widthIn(max = 820.dp),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                SettingSectionCard("Interface Theme") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppThemeMode.entries.forEach { mode ->
                            FilterChip(
                                selected = preferences.themeMode == mode,
                                onClick = { scope.launch { prefsRepo.updateThemeMode(mode) } },
                                label = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) },
                                modifier = Modifier.testTag("theme_chip_${mode.name.lowercase()}")
                            )
                        }
                    }
                }
            }

            item {
                SettingSectionCard("Drawing Canvas Background") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CanvasBackgroundTheme.entries.forEach { bg ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { scope.launch { prefsRepo.updateCanvasTheme(bg) } }
                                    .padding(vertical = 6.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .background(Color(bg.backgroundArgb))
                                        .border(2.dp, if (preferences.canvasTheme == bg) MaterialTheme.colorScheme.primary else Color.Gray, CircleShape)
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(bg.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                                if (preferences.canvasTheme == bg) {
                                    Text("Active", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }

            item {
                SettingSectionCard("Units, Grid & Precision") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Default Drawing Units", style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            CadUnits.entries.forEach { u ->
                                FilterChip(
                                    selected = preferences.defaultUnits == u,
                                    onClick = { scope.launch { prefsRepo.updateDefaultUnits(u) } },
                                    label = { Text(u.displayName) }
                                )
                            }
                        }

                        Text("Decimal Precision: ${preferences.decimalPrecision} places", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            (0..4).forEach { p ->
                                FilterChip(
                                    selected = preferences.decimalPrecision == p,
                                    onClick = { scope.launch { prefsRepo.updateDecimalPrecision(p) } },
                                    label = { Text(".$p") }
                                )
                            }
                        }

                        Text("Grid Base Spacing: ${preferences.gridSpacing.toInt()} units", style = MaterialTheme.typography.labelLarge)
                        Slider(
                            value = preferences.gridSpacing.toFloat(),
                            onValueChange = { v -> scope.launch { prefsRepo.updateGridSpacing(v.toDouble()) } },
                            valueRange = 1f..100f
                        )

                        Text("Polar Tracking Increment", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(15.0, 30.0, 45.0, 90.0).forEach { deg ->
                                FilterChip(
                                    selected = preferences.polarIncrementDeg == deg,
                                    onClick = { scope.launch { prefsRepo.updatePolarIncrement(deg) } },
                                    label = { Text("${deg.toInt()}°") }
                                )
                            }
                        }
                    }
                }
            }

            item {
                SettingSectionCard("Auto-Save & Crash Recovery") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Enable Background Auto-Save", style = MaterialTheme.typography.bodyLarge)
                                Text("Writes recovery DXF snapshot automatically while editing", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = preferences.autoSaveEnabled,
                                onCheckedChange = { enabled ->
                                    scope.launch { prefsRepo.updateAutoSave(enabled, preferences.autoSaveIntervalSec) }
                                }
                            )
                        }
                        if (preferences.autoSaveEnabled) {
                            Text("Interval: Every ${preferences.autoSaveIntervalSec} seconds", style = MaterialTheme.typography.labelMedium)
                            Slider(
                                value = preferences.autoSaveIntervalSec.toFloat(),
                                onValueChange = { v -> scope.launch { prefsRepo.updateAutoSave(true, v.toInt()) } },
                                valueRange = 10f..90f
                            )
                        }
                    }
                }
            }

            item {
                val sdkLoaded = DefaultDwgSdkBridge.isNativeLibraryLoaded()
                SettingSectionCard("DWG / DXF Engine Diagnostics") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("DXF Engine: Built-in AC1024 Streaming Parser & Writer", style = MaterialTheme.typography.bodyMedium, color = CadEmerald)
                        Text(
                            text = if (sdkLoaded) "DWG Native SDK: Linked (${DefaultDwgSdkBridge.sdkName})"
                            else "DWG Native SDK: Integration bridge ready (link liblibredwg_jni.so or ODA Drawings SDK in jniLibs for binary DWG). ASCII DXF-wrapped DWG & header version inspection active.",
                            fontFamily = JetBrainsMonoFamily,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (sdkLoaded) CadEmerald else CadAmber
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingSectionCard(title: String, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}
