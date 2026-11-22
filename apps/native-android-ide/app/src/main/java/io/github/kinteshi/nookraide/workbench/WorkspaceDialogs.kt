package io.github.kinteshi.nookraide.workbench

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.TabletAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/**
 * File > Open Workspace: unified `(host, folder)` picker across All recent, This device, and every
 * paired host. Works connected or offline so jumping between hosts or folders never hits a dead end.
 */
@Composable
internal fun OpenFolderDialog(controller: WorkbenchController, dialog: WorkbenchDialog.OpenFolder) {
    val colors = MaterialTheme.colorScheme
    val animate = controller.settings.animations
    var newName by remember(dialog.source, dialog.path) { mutableStateOf<String?>(null) }
    var lastNewName by remember(dialog.source, dialog.path) { mutableStateOf("") }
    newName?.let { lastNewName = it }
    val source = dialog.source
    val remoteProfile = (source as? WorkbenchDialog.WorkspacePickerSource.RemoteHost)?.let { target ->
        controller.profiles.firstOrNull { it.id == target.profileId }
    }
    val activeRemote = remoteProfile != null && controller.connected && controller.profileId == remoteProfile.id
    val parent = dialog.path.substringBeforeLast('/').ifEmpty { "/" }
    val canGoUp = activeRemote && dialog.path.isNotBlank() && parent != dialog.path && controller.roots.any { isPathWithinRoot(parent, it) }

    AlertDialog(
        onDismissRequest = { controller.dialog = null },
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.widthIn(max = 680.dp).padding(24.dp),
        title = { Text("Open workspace") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = source == WorkbenchDialog.WorkspacePickerSource.RecentAll,
                        onClick = { controller.showOpenFolder(source = WorkbenchDialog.WorkspacePickerSource.RecentAll) },
                        label = { Text("All recent") },
                        leadingIcon = { Icon(Icons.Outlined.History, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    )
                    FilterChip(
                        selected = source == WorkbenchDialog.WorkspacePickerSource.ThisDevice,
                        onClick = { controller.showOpenFolder(source = WorkbenchDialog.WorkspacePickerSource.ThisDevice) },
                        label = { Text("This device") },
                        leadingIcon = { Icon(Icons.Outlined.PhoneAndroid, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    )
                    controller.profiles.forEach { profile ->
                        val chipSource = WorkbenchDialog.WorkspacePickerSource.RemoteHost(profile.id)
                        val isConnected = controller.connected && controller.profileId == profile.id
                        FilterChip(
                            selected = source == chipSource,
                            onClick = { controller.showOpenFolder(source = chipSource) },
                            label = { Text(if (isConnected) "${profile.name} · on" else profile.name) },
                            leadingIcon = {
                                Icon(
                                    if (isTabletGuestEndpoint(profile.endpoint)) Icons.Outlined.TabletAndroid else Icons.Outlined.Computer,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                        )
                    }
                    AssistChip(
                        onClick = { controller.openConnect() },
                        label = { Text("Connections") },
                        leadingIcon = { Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    )
                }

                Box(Modifier.height(340.dp).fillMaxWidth()) {
                    AnimatedContent(
                        targetState = source,
                        transitionSpec = {
                            val ms = if (animate) 140 else 0
                            fadeIn(tween(ms)) togetherWith fadeOut(tween(ms))
                        },
                        label = "workspaceSource",
                    ) { currentSource ->
                        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            when (currentSource) {
                                WorkbenchDialog.WorkspacePickerSource.RecentAll -> {
                                    val recent = controller.recentWorkspaceChoices
                                    Box(Modifier.weight(1f).fillMaxWidth()) {
                                        if (recent.isEmpty()) {
                                            Surface(color = colors.surfaceContainerLow, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    Text("No recent workspaces yet", style = MaterialTheme.typography.titleSmall)
                                                    Text(
                                                        "Every folder you open remembers its host (This device, Linux guest, or a paired computer) here for quick switching.",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = colors.onSurfaceVariant,
                                                    )
                                                }
                                            }
                                        } else {
                                            LazyColumn(Modifier.fillMaxSize()) {
                                                items(recent, key = { "${it.connectionId ?: "device"}:${it.path}" }) { item ->
                                                    RecentWorkspaceRow(controller, item)
                                                }
                                            }
                                        }
                                    }
                                }

                                WorkbenchDialog.WorkspacePickerSource.ThisDevice -> {
                                    Surface(color = colors.surfaceContainerLow, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                Text("Device storage", style = MaterialTheme.typography.titleSmall)
                                                Text(
                                                    "Pick a folder or standalone file on this tablet without needing a Linux machine.",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = colors.onSurfaceVariant,
                                                )
                                            }
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                FilledTonalButton(onClick = controller::beginOpenDeviceFolder) {
                                                    Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.padding(end = 6.dp).size(18.dp))
                                                    Text("Pick folder…")
                                                }
                                                OutlinedButton(onClick = {
                                                    controller.dialog = null
                                                    controller.pickLocalFile?.invoke()
                                                }) {
                                                    Icon(Icons.Outlined.Description, contentDescription = null, modifier = Modifier.padding(end = 6.dp).size(18.dp))
                                                    Text("Pick file…")
                                                }
                                            }
                                        }
                                    }
                                    val deviceRecent = controller.recentWorkspaceChoices.filter { it.isDeviceFolder }
                                    Box(Modifier.weight(1f).fillMaxWidth()) {
                                        if (deviceRecent.isEmpty()) {
                                            Text(
                                                "Recent device folders will appear here once opened.",
                                                color = colors.onSurfaceVariant,
                                                style = MaterialTheme.typography.bodyMedium,
                                                modifier = Modifier.padding(12.dp),
                                            )
                                        } else {
                                            LazyColumn(Modifier.fillMaxSize()) {
                                                items(deviceRecent, key = { it.path }) { item ->
                                                    RecentWorkspaceRow(controller, item)
                                                }
                                            }
                                        }
                                    }
                                }

                                is WorkbenchDialog.WorkspacePickerSource.RemoteHost -> {
                                    val hostRecents = controller.recentWorkspaceChoices.filter { it.connectionId == currentSource.profileId }
                                    if (!activeRemote) {
                                        val connectingHere = controller.busy && controller.profileId == currentSource.profileId
                                        Surface(color = colors.surfaceContainerLow, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                                            Row(
                                                Modifier.padding(14.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                            ) {
                                                if (connectingHere) {
                                                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                                } else {
                                                    Icon(Icons.Outlined.Cloud, contentDescription = null, tint = colors.primary)
                                                }
                                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                    Text(
                                                        when {
                                                            connectingHere -> "Connecting to ${remoteProfile?.name ?: "host"}…"
                                                            remoteProfile != null -> "Connect to browse ${remoteProfile.name}"
                                                            else -> "Host is unavailable"
                                                        },
                                                        style = MaterialTheme.typography.titleSmall,
                                                    )
                                                    Text(
                                                        if (connectingHere) "Folders will appear as soon as the host answers."
                                                        else "Tap Connect & browse to list folders on this host, or open a saved workspace below.",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = colors.onSurfaceVariant,
                                                    )
                                                }
                                                if (!connectingHere && remoteProfile != null) {
                                                    FilledTonalButton(onClick = { controller.openHostWorkspacePicker(remoteProfile) }) {
                                                        Text("Connect & browse")
                                                    }
                                                }
                                            }
                                        }
                                        Box(Modifier.weight(1f).fillMaxWidth()) {
                                            if (hostRecents.isEmpty()) {
                                                Text(
                                                    "No saved recent folders for this host yet.",
                                                    color = colors.onSurfaceVariant,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    modifier = Modifier.padding(12.dp),
                                                )
                                            } else {
                                                LazyColumn(Modifier.fillMaxSize()) {
                                                    items(hostRecents, key = { it.path }) { item ->
                                                        RecentWorkspaceRow(controller, item)
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        if (hostRecents.isNotEmpty()) {
                                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text("Recent on ${remoteProfile.name}", style = MaterialTheme.typography.labelMedium, color = colors.primary)
                                                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    hostRecents.take(5).forEach { item ->
                                                        AssistChip(
                                                            onClick = { controller.openRecentWorkspace(item) },
                                                            label = { Text(controller.displayName(item.path), maxLines = 1) },
                                                            leadingIcon = { Icon(Icons.Outlined.History, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                        if (controller.roots.size > 1) {
                                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                controller.roots.forEach { root ->
                                                    AssistChip(
                                                        onClick = { controller.showOpenFolder(root, currentSource) },
                                                        label = { Text(root.trimEnd('/').substringAfterLast('/').ifEmpty { root }) },
                                                        leadingIcon = { Icon(Icons.Outlined.Folder, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                                    )
                                                }
                                            }
                                        }
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Tooltip("Up one folder") {
                                                IconButton(onClick = { controller.showOpenFolder(parent, currentSource) }, enabled = canGoUp) {
                                                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Up one folder")
                                                }
                                            }
                                            Text(
                                                relativeHostDisplayPath(dialog.path, controller.roots),
                                                style = MaterialTheme.typography.bodyMedium,
                                                maxLines = 1,
                                                overflow = TextOverflow.StartEllipsis,
                                                modifier = Modifier.weight(1f),
                                            )
                                            Tooltip("New folder") {
                                                IconButton(onClick = { newName = if (newName == null) "" else null }) {
                                                    Icon(Icons.Outlined.CreateNewFolder, contentDescription = "New folder")
                                                }
                                            }
                                        }
                                        VerticalReveal(visible = newName != null, animate = animate) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                OutlinedTextField(
                                                    value = newName ?: lastNewName,
                                                    onValueChange = { newName = it },
                                                    singleLine = true,
                                                    label = { Text("New folder name") },
                                                    modifier = Modifier.weight(1f),
                                                )
                                                TextButton(onClick = { controller.createFolderToOpen(dialog.path, newName ?: lastNewName) }) { Text("Create") }
                                            }
                                        }
                                        dialog.error?.let { Text(it, color = colors.error, style = MaterialTheme.typography.bodySmall) }
                                        val folders = dialog.folders
                                        Box(Modifier.weight(1f).fillMaxWidth()) {
                                            when {
                                                folders == null && dialog.error == null -> CircularProgressIndicator(Modifier.padding(16.dp).size(24.dp))
                                                folders.isNullOrEmpty() -> Text(
                                                    "No subfolders here",
                                                    color = colors.onSurfaceVariant,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    modifier = Modifier.padding(12.dp),
                                                )
                                                else -> LazyColumn(Modifier.fillMaxSize()) {
                                                    items(folders.sortedBy { it.name.startsWith('.') }, key = { it.path }) { folder ->
                                                        FolderRow(
                                                            name = folder.name,
                                                            detail = null,
                                                            icon = { Icon(Icons.Outlined.Folder, contentDescription = null) },
                                                            trailing = {
                                                                Tooltip("Open ${folder.name}") {
                                                                    FilledTonalButton(
                                                                        onClick = { controller.requestOpenWorkspace(folder.path) },
                                                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                                                        modifier = Modifier.height(32.dp),
                                                                    ) {
                                                                        Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = "Open ${folder.name}", modifier = Modifier.padding(end = 4.dp).size(15.dp))
                                                                        Text("Open", style = MaterialTheme.typography.labelMedium)
                                                                    }
                                                                }
                                                            },
                                                        ) { controller.showOpenFolder(folder.path, currentSource) }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (activeRemote && dialog.path.isNotBlank()) {
                TextButton(
                    onClick = { controller.requestOpenWorkspace(dialog.path) },
                    enabled = dialog.path != controller.workspaceRoot,
                ) {
                    Text("Open current (${dialog.path.substringAfterLast('/').ifEmpty { dialog.path }})", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } else {
                TextButton(onClick = { controller.dialog = null }) { Text("Close") }
            }
        },
        dismissButton = {
            if (activeRemote && dialog.path.isNotBlank()) {
                TextButton(onClick = { controller.dialog = null }) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun RecentWorkspaceRow(controller: WorkbenchController, item: RecentWorkspace) {
    FolderRow(
        name = controller.displayName(item.path),
        detail = "${controller.displayPath(item.path)} · ${controller.recentWorkspaceOrigin(item)}",
        icon = { Icon(Icons.Outlined.History, contentDescription = null) },
        trailing = {
            Tooltip("Remove from recent") {
                IconButton(onClick = { controller.forgetRecentWorkspace(item) }) {
                    Icon(Icons.Outlined.Close, contentDescription = "Remove from recent")
                }
            }
        },
    ) { controller.openRecentWorkspace(item) }
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
