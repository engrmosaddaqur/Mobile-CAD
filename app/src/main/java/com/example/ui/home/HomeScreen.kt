package com.example.ui.home

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Architecture
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.cad.model.CadUnits
import com.example.data.RecentDrawingEntity
import com.example.ui.theme.CadAmber
import com.example.ui.theme.CadCyan
import com.example.ui.theme.CadEmerald
import com.example.ui.theme.JetBrainsMonoFamily
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    recentDrawings: List<RecentDrawingEntity>,
    pendingRecovery: RecentDrawingEntity?,
    importError: Pair<String, String>?,
    defaultUnits: CadUnits,
    onNewDrawing: () -> Unit,
    onOpenDrawingPicker: () -> Unit,
    onCreateProject: (name: String, projectGroup: String, units: CadUnits) -> Unit,
    onOpenRecent: (id: String, recoverAutosave: Boolean) -> Unit,
    onDiscardRecovery: (id: String) -> Unit,
    onRenameDrawing: (id: String, newName: String) -> Unit,
    onDeleteDrawing: (id: String) -> Unit,
    onNavigateSettings: () -> Unit,
    onNavigateHelp: () -> Unit,
    onDismissImportError: () -> Unit
) {
    var showCreateProjectDialog by remember { mutableStateOf(false) }
    var showBrowseFilesDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<RecentDrawingEntity?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedProjectFilter by remember { mutableStateOf<String?>(null) }

    val projectGroups = remember(recentDrawings) {
        recentDrawings.map { it.projectGroup }.distinct()
    }

    val filteredDrawings = remember(recentDrawings, searchQuery, selectedProjectFilter) {
        recentDrawings.filter { item ->
            val matchesQuery = searchQuery.isBlank() ||
                item.name.contains(searchQuery, ignoreCase = true) ||
                item.projectGroup.contains(searchQuery, ignoreCase = true)
            val matchesProject = selectedProjectFilter == null || item.projectGroup == selectedProjectFilter
            matchesQuery && matchesProject
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .widthIn(max = 960.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Header with App Logo, Name, Tagline
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_cad_logo_foreground),
                            contentDescription = stringResource(R.string.cd_app_logo),
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.app_name),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = stringResource(R.string.tagline),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Row {
                        IconButton(
                            onClick = onNavigateHelp,
                            modifier = Modifier.testTag("home_help_icon_btn")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = stringResource(R.string.action_help))
                        }
                        IconButton(
                            onClick = onNavigateSettings,
                            modifier = Modifier.testTag("home_settings_icon_btn")
                        ) {
                            Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.action_settings))
                        }
                    }
                }
            }

            // Crash Recovery Banner if an autosave exists
            if (pendingRecovery != null) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, CadAmber.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Restore, contentDescription = "Drawing recovery", tint = CadAmber)
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(
                                        "Drawing Recovery Available",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                    Text(
                                        "Recover unsaved auto-save for '${pendingRecovery.name}'",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f)
                                    )
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                TextButton(
                                    onClick = { onDiscardRecovery(pendingRecovery.id) },
                                    modifier = Modifier.testTag("discard_recovery_btn")
                                ) {
                                    Text("Discard")
                                }
                                Button(
                                    onClick = { onOpenRecent(pendingRecovery.id, true) },
                                    modifier = Modifier.testTag("recover_drawing_btn")
                                ) {
                                    Text("Recover")
                                }
                            }
                        }
                    }
                }
            }

            // 2. Hero Engineering Blueprint Card
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(176.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.img_cad_hero),
                        contentDescription = stringResource(R.string.cd_hero_blueprint),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(
                                        Color(0xEE080C12),
                                        Color(0xBB0B1017),
                                        Color(0x440B1017)
                                    )
                                )
                            )
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(18.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Surface(
                                color = CadCyan.copy(alpha = 0.16f),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    "2D VECTOR CAD ENGINE • OFFLINE DXF/DWG",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = CadCyan,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Draft, Dimension & Export Anywhere",
                                style = MaterialTheme.typography.titleLarge,
                                color = Color.White
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = onNewDrawing,
                                colors = ButtonDefaults.buttonColors(containerColor = CadCyan, contentColor = Color(0xFF002025)),
                                modifier = Modifier.testTag("new_drawing_button")
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.action_new_drawing))
                            }
                            OutlinedButton(
                                onClick = onOpenDrawingPicker,
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                modifier = Modifier.testTag("open_drawing_button")
                            ) {
                                Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.action_open_drawing))
                            }
                        }
                    }
                }
            }

            // 3. Action Grid (Browse Files, Create New Project, Settings, Help & Tutorials)
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        HomeActionTile(
                            icon = Icons.Default.FolderOpen,
                            title = stringResource(R.string.action_browse_files),
                            subtitle = "${recentDrawings.size} local drawings",
                            accent = CadCyan,
                            testTag = "browse_files_button",
                            modifier = Modifier.weight(1f),
                            onClick = { showBrowseFilesDialog = true }
                        )
                        HomeActionTile(
                            icon = Icons.Default.CreateNewFolder,
                            title = stringResource(R.string.action_new_project),
                            subtitle = "Units & folder setup",
                            accent = CadAmber,
                            testTag = "new_project_button",
                            modifier = Modifier.weight(1f),
                            onClick = { showCreateProjectDialog = true }
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        HomeActionTile(
                            icon = Icons.Default.Settings,
                            title = stringResource(R.string.action_settings),
                            subtitle = "Theme, units, auto-save",
                            accent = CadEmerald,
                            testTag = "settings_button",
                            modifier = Modifier.weight(1f),
                            onClick = onNavigateSettings
                        )
                        HomeActionTile(
                            icon = Icons.AutoMirrored.Filled.HelpOutline,
                            title = stringResource(R.string.action_help),
                            subtitle = "Commands & gestures",
                            accent = MaterialTheme.colorScheme.primary,
                            testTag = "help_tutorials_button",
                            modifier = Modifier.weight(1f),
                            onClick = onNavigateHelp
                        )
                    }
                }
            }

            // 4. Recent Drawings Section + Project Filter
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.recent_drawings),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = "${filteredDrawings.size} files",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (projectGroups.size > 1) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item {
                                FilterChip(
                                    selected = selectedProjectFilter == null,
                                    onClick = { selectedProjectFilter = null },
                                    label = { Text("All Projects") }
                                )
                            }
                            items(projectGroups) { group ->
                                FilterChip(
                                    selected = selectedProjectFilter == group,
                                    onClick = {
                                        selectedProjectFilter = if (selectedProjectFilter == group) null else group
                                    },
                                    label = { Text(group) }
                                )
                            }
                        }
                    }
                }
            }

            if (filteredDrawings.isEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Default.Architecture,
                                contentDescription = null,
                                modifier = Modifier.size(44.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                stringResource(R.string.empty_recent_title),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                stringResource(R.string.empty_recent_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(filteredDrawings, key = { it.id }) { item ->
                    RecentDrawingCard(
                        item = item,
                        onOpen = { onOpenRecent(item.id, false) },
                        onRename = { renameTarget = item },
                        onDelete = { onDeleteDrawing(item.id) }
                    )
                }
            }
        }
    }

    if (showCreateProjectDialog) {
        CreateProjectDialog(
            defaultUnits = defaultUnits,
            onDismiss = { showCreateProjectDialog = false },
            onCreate = { name, group, units ->
                showCreateProjectDialog = false
                onCreateProject(name, group, units)
            }
        )
    }

    if (showBrowseFilesDialog) {
        BrowseFilesDialog(
            drawings = recentDrawings,
            initialQuery = searchQuery,
            onQueryChange = { searchQuery = it },
            onOpenDevicePicker = {
                showBrowseFilesDialog = false
                onOpenDrawingPicker()
            },
            onSelectDrawing = { id ->
                showBrowseFilesDialog = false
                onOpenRecent(id, false)
            },
            onDismiss = { showBrowseFilesDialog = false }
        )
    }

    renameTarget?.let { target ->
        var newName by remember(target.id) { mutableStateOf(target.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename Drawing") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Drawing Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    onRenameDrawing(target.id, newName)
                    renameTarget = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Cancel") }
            }
        )
    }

    importError?.let { (title, message) ->
        AlertDialog(
            onDismissRequest = onDismissImportError,
            title = { Text(title) },
            text = { Text(message, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                Button(onClick = onDismissImportError) { Text("OK") }
            }
        )
    }
}

@Composable
private fun HomeActionTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accent: Color,
    testTag: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = modifier
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun RecentDrawingCard(
    item: RecentDrawingEntity,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val thumbBitmap = remember(item.thumbnailPath, item.lastModifiedMs) {
        val f = File(item.thumbnailPath)
        if (f.exists()) BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap() else null
    }
    val dateStr = remember(item.lastModifiedMs) {
        SimpleDateFormat("MMM dd, yyyy • HH:mm", Locale.getDefault()).format(Date(item.lastModifiedMs))
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onOpen)
            .testTag("recent_drawing_card_${item.id}")
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(width = 86.dp, height = 60.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF080C12))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (thumbBitmap != null) {
                    Image(
                        bitmap = thumbBitmap,
                        contentDescription = "Thumbnail for ${item.name}",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(Icons.Default.Architecture, contentDescription = null, tint = CadCyan)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = item.format,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${item.projectGroup} • ${item.entityCount} entities",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                Text(
                    text = dateStr,
                    fontFamily = JetBrainsMonoFamily,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Drawing options")
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, null) },
                        onClick = {
                            menuExpanded = false
                            onRename()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        leadingIcon = { Icon(Icons.Default.DeleteOutline, null) },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun CreateProjectDialog(
    defaultUnits: CadUnits,
    onDismiss: () -> Unit,
    onCreate: (String, String, CadUnits) -> Unit
) {
    var name by remember { mutableStateOf("Project_Plan_01") }
    var group by remember { mutableStateOf("Engineering Projects") }
    var units by remember { mutableStateOf(defaultUnits) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_new_project)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Drawing Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = group,
                    onValueChange = { group = it },
                    label = { Text("Project Folder / Group") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Drawing Units", style = MaterialTheme.typography.labelLarge)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(CadUnits.entries) { u ->
                        FilterChip(
                            selected = units == u,
                            onClick = { units = u },
                            label = { Text(u.shortLabel) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(name, group, units) },
                modifier = Modifier.testTag("confirm_create_project_btn")
            ) { Text("Create & Open") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun BrowseFilesDialog(
    drawings: List<RecentDrawingEntity>,
    initialQuery: String,
    onQueryChange: (String) -> Unit,
    onOpenDevicePicker: () -> Unit,
    onSelectDrawing: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Browse CAD Files") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = initialQuery,
                    onValueChange = onQueryChange,
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    placeholder = { Text("Search local drawings...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = onOpenDevicePicker,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.UploadFile, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Import DXF / DWG from Device Storage")
                }
                val matches = drawings.filter {
                    initialQuery.isBlank() || it.name.contains(initialQuery, ignoreCase = true)
                }.take(6)
                matches.forEach { d ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectDrawing(d.id) }
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(d.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                                Text("${d.projectGroup} • ${d.entityCount} entities", style = MaterialTheme.typography.bodySmall)
                            }
                            Text(d.format, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
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
