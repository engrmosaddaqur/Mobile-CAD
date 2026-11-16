package com.example.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.ui.theme.JetBrainsMonoFamily

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(onNavigateBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Help & CAD Tutorials") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack, modifier = Modifier.testTag("help_back_btn")) {
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                HelpCard(
                    title = "1. Touch & Mouse Navigation",
                    body = """
                        • Two-finger pinch: Smoothly zoom anchored under your fingers.
                        • Two-finger drag: Pan the drawing canvas at any time without switching tools.
                        • Single-finger Pan: Tap the Hand (Pan) icon in the top-right floating pill.
                        • Double-tap canvas: Instantly Zoom to Drawing Extents.
                        • Selection Box: Drag Left-to-Right (Blue) for Window selection (strictly inside); drag Right-to-Left (Green) for Crossing selection.
                        • Mouse support: Scroll wheel zooms at cursor; Middle-mouse drag pans immediately.
                    """.trimIndent()
                )
            }
            item {
                HelpCard(
                    title = "2. Object Snap (OSNAP) & Precision",
                    body = """
                        • END (Square): Snaps to line, arc, or polyline endpoints.
                        • MID (Triangle): Snaps to segment or arc midpoints.
                        • CEN (Circle): Snaps to circle, arc, or ellipse centers.
                        • QUA (Diamond): Snaps to 0°, 90°, 180°, 270° circle quadrants.
                        • INT (X): Snaps to intersections between nearby lines and circles.
                        • PER / TAN: Snaps perpendicular or tangent relative to your last anchor point.
                        • ORTHO / POLAR: Locks cursor to 90° or polar angle increments (15°/30°/45°).
                    """.trimIndent()
                )
            }
            item {
                HelpCard(
                    title = "3. Command Line & Coordinate Input",
                    body = """
                        Enter commands or exact coordinates in the bottom CLI bar:
                        • Absolute: 120,80
                        • Relative Cartesian: @50,0
                        • Relative Polar: @75<45
                        • Direct distance: Tap direction, type 25 and press Send.
                        • Draw: LINE (L), PLINE (PL), TRIANGLE, RECTANGLE (REC), CIRCLE (C), POLYGON (POL), ARC (A), ELLIPSE (EL), SPLINE (SPL), POINT (PO), RAY, XLINE (XL)
                        • Modify: MOVE (M), COPY (CO), ROTATE (RO), MIRROR (MI), OFFSET (O), TRIM (TR), EXTEND (EX), STRETCH (S), FILLET (F), CHAMFER (CHA), SCALE (SC), ARRAY (AR), EXPLODE (X), JOIN (J), BREAK (BR), ERASE (E), UNDO (U), REDO
                        • Measure & Dim: DIST (DI), AREA, ID, DIMLINEAR, DIMALIGNED, DIMANGULAR, DIMRADIUS, DIMDIAMETER, DIMARC, LEADER
                    """.trimIndent()
                )
            }
            item {
                HelpCard(
                    title = "4. File Compatibility & Export",
                    body = """
                        • DXF (AC1024): Full read/write support preserving layers, ACI colors, line types, blocks, units, and dimensions.
                        • DWG: Inspects binary DWG version headers (AC1012–AC1032) and connects to LibreDWG / ODA Drawings SDK via JNI (`liblibredwg_jni.so`). Also reads DXF-compatible DWG files directly.
                        • Export: Export vector PDF (with Paper Space title block), layered SVG, or high-resolution PNG / JPG via Android Storage Access Framework.
                    """.trimIndent()
                )
            }
        }
    }
}

@Composable
private fun HelpCard(title: String, body: String) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text(body, fontFamily = JetBrainsMonoFamily, style = MaterialTheme.typography.bodySmall)
        }
    }
}
