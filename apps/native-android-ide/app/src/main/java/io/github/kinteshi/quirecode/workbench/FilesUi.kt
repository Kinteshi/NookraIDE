package io.github.kinteshi.quirecode.workbench

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.NoteAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** Save to the device (G27): what's selected and its size, then as itself, a zip, or into a device folder. */
@Composable
internal fun SaveToDeviceDialog(controller: WorkbenchController, save: WorkbenchDialog.SaveToDevice) {
    val plan = save.plan
    val names = transferTopNames(save.paths)
    val single = plan != null && !plan.hasFolders && plan.files.size == 1
    val zipFirst = controller.settings.saveFoldersAs != "folder"
    AlertDialog(
        onDismissRequest = { controller.dialog = null },
        title = { Text("Save to the device") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (names.size == 1) names.single() else "${names.size} items: ${names.take(3).joinToString(", ")}${if (names.size > 3) "…" else ""}",
                    style = MaterialTheme.typography.titleSmall,
                )
                when {
                    save.error != null -> Text(save.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    plan == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Counting what's in it…", style = MaterialTheme.typography.bodyMedium)
                    }
                    else -> Text(
                        "${plan.files.size} ${if (plan.files.size == 1) "file" else "files"}, ${formatBytes(plan.totalBytes)}" +
                            if (single) "." else ". As one zip archive, or as files in a folder you pick on the device.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            if (plan != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        single -> {
                            OutlinedButton(onClick = { controller.chooseSaveTarget(save, SaveHow.Zip) }) { Text("As zip…") }
                            Button(onClick = { controller.chooseSaveTarget(save, SaveHow.File) }) { Text("Save…") }
                        }
                        zipFirst -> {
                            OutlinedButton(onClick = { controller.chooseSaveTarget(save, SaveHow.Folder) }) { Text("Into a folder…") }
                            Button(onClick = { controller.chooseSaveTarget(save, SaveHow.Zip) }) { Text("As zip…") }
                        }
                        else -> {
                            OutlinedButton(onClick = { controller.chooseSaveTarget(save, SaveHow.Zip) }) { Text("As zip…") }
                            Button(onClick = { controller.chooseSaveTarget(save, SaveHow.Folder) }) { Text("Into a folder…") }
                        }
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = { controller.dialog = null }) { Text("Cancel") } },
    )
}

/** Import (G28): the picked items, the guest folder they go into, and what a taken name does. */
@Composable
internal fun ImportDialog(controller: WorkbenchController, import: WorkbenchDialog.Import) {
    var folder by remember(import) { mutableStateOf(import.folder) }
    var replace by remember(import) { mutableStateOf(false) }
    val names = import.items.map { if (it.folder) "${it.name}/" else it.name }
    AlertDialog(
        onDismissRequest = { controller.dialog = null },
        title = { Text("Import into the workspace") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (names.size == 1) names.single() else "${names.size} files: ${names.take(3).joinToString(", ")}${if (names.size > 3) "…" else ""}",
                    style = MaterialTheme.typography.titleSmall,
                )
                if (io.github.kinteshi.quirecode.document.isTreePath(folder)) {
                    // A device folder's path isn't one to type (G29): where it goes, by name.
                    Text("Into ${controller.displayPath(folder)}", style = MaterialTheme.typography.bodyMedium)
                } else {
                    OutlinedTextField(
                        value = folder,
                        onValueChange = { folder = it },
                        label = { Text("Into the folder") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text("When a name is taken", style = MaterialTheme.typography.labelLarge)
                listOf(false to "Keep both (the new one gets \" (2)\")", true to "Replace what's there").forEach { (value, label) ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = replace == value, role = Role.RadioButton) { replace = value },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = replace == value, onClick = null)
                        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { controller.runImport(import, folder, replace) }, enabled = folder.isNotBlank()) { Text("Import") } },
        dismissButton = { TextButton(onClick = { controller.dialog = null }) { Text("Cancel") } },
    )
}

/** A running transfer's progress under the top bar, with Cancel (group 9). */
@Composable
internal fun TransferBanner(controller: WorkbenchController) {
    val progress = controller.transfer ?: return
    val colors = MaterialTheme.colorScheme
    Surface(
        color = colors.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    progress.title + if (progress.totalFiles > 0) {
                        " · ${progress.files} of ${progress.totalFiles} ${if (progress.totalFiles == 1) "file" else "files"}" +
                            if (progress.totalBytes > 0) " · ${formatBytes(progress.bytes)} of ${formatBytes(progress.totalBytes)}" else ""
                    } else {
                        "…"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = controller::cancelTransfer) { Text("Cancel") }
            }
            if (progress.totalFiles > 0) {
                LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** Explorer's header button: import files or a folder from the device into the folder shown (G28). */
@Composable
internal fun ImportButton(controller: WorkbenchController) {
    var open by remember { mutableStateOf(false) }
    Box {
        Tooltip("Import from the device") {
            IconButton(onClick = { open = true }) { Icon(Icons.Outlined.FileUpload, contentDescription = "Import from the device") }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Import files…") },
                leadingIcon = { Icon(Icons.Outlined.NoteAdd, contentDescription = null) },
                onClick = { open = false; controller.beginImportFiles() },
            )
            DropdownMenuItem(
                text = { Text("Import a folder…") },
                leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, contentDescription = null) },
                onClick = { open = false; controller.beginImportFolder() },
            )
        }
    }
}
