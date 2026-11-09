package io.github.kinteshi.quirecode.workbench

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/**
 * File > Open Folder: recent folders first, then a browser over the guest's roots (only
 * folders). "Open" makes the shown folder the workspace; a new folder can be made in place.
 */
@Composable
internal fun OpenFolderDialog(controller: WorkbenchController, dialog: WorkbenchDialog.OpenFolder) {
    val colors = MaterialTheme.colorScheme
    var newName by remember(dialog.path) { mutableStateOf<String?>(null) }
    val parent = dialog.path.substringBeforeLast('/').ifEmpty { "/" }
    // Up stays inside what the daemon serves (from ~/Projects up to home, not above it).
    val canGoUp = parent != dialog.path && controller.roots.any { isPathWithinRoot(parent, it) }
    AlertDialog(
        onDismissRequest = { controller.dialog = null },
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.widthIn(max = 640.dp).padding(24.dp),
        title = { Text("Open folder") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val recent = controller.recentWorkspaceChoices
                if (recent.isNotEmpty()) {
                    Text("Recent", style = MaterialTheme.typography.labelLarge, color = colors.primary)
                    recent.take(5).forEach { path ->
                        FolderRow(
                            name = path.substringAfterLast('/').ifEmpty { path },
                            detail = path,
                            icon = { Icon(Icons.Outlined.History, contentDescription = null) },
                            trailing = {
                                Tooltip("Remove from recent") { IconButton(onClick = { controller.forgetRecentWorkspace(path) }) {
                                    Icon(Icons.Outlined.Close, contentDescription = "Remove from recent")
                                } }
                            },
                        ) { controller.openWorkspace(path) }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                }
                // The guest's roots as shortcuts (~/Projects and home on a tablet guest).
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    controller.roots.forEach { root ->
                        AssistChip(
                            onClick = { controller.showOpenFolder(root) },
                            label = { Text(root.trimEnd('/').substringAfterLast('/').ifEmpty { root }) },
                            leadingIcon = { Icon(Icons.Outlined.Folder, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Tooltip("Up one folder") { IconButton(onClick = { controller.showOpenFolder(parent) }, enabled = canGoUp) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Up one folder")
                    } }
                    Text(
                        dialog.path,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.StartEllipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Tooltip("New folder") { IconButton(onClick = { newName = "" }) {
                        Icon(Icons.Outlined.CreateNewFolder, contentDescription = "New folder")
                    } }
                }
                newName?.let { name ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { newName = it },
                            singleLine = true,
                            label = { Text("New folder name") },
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { controller.createFolderToOpen(dialog.path, name) }) { Text("Create") }
                    }
                }
                dialog.error?.let { Text(it, color = colors.error, style = MaterialTheme.typography.bodySmall) }
                val folders = dialog.folders
                // One height for every folder: the dialog and its buttons stay put while browsing.
                Box(Modifier.height(320.dp)) {
                    when {
                        folders == null && dialog.error == null -> CircularProgressIndicator(Modifier.padding(16.dp).size(24.dp))
                        folders.isNullOrEmpty() -> Text(
                            "No folders here",
                            color = colors.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(12.dp),
                        )
                        else -> LazyColumn(Modifier.fillMaxSize()) {
                            // Hidden folders (.cache, .config) after the user's own.
                            items(folders.sortedBy { it.name.startsWith('.') }, key = { it.path }) { folder ->
                                FolderRow(folder.name, null, icon = { Icon(Icons.Outlined.Folder, contentDescription = null) }) {
                                    controller.showOpenFolder(folder.path)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { controller.openWorkspace(dialog.path) }, enabled = dialog.path != controller.workspaceRoot) {
                Text("Open ${dialog.path.substringAfterLast('/').ifEmpty { dialog.path }}", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        dismissButton = { TextButton(onClick = { controller.dialog = null }) { Text("Cancel") } },
    )
}

@Composable
private fun FolderRow(
    name: String,
    detail: String?,
    icon: @Composable () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = detail?.let { { Text(it, maxLines = 1, overflow = TextOverflow.StartEllipsis) } },
        leadingContent = icon,
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    )
}
