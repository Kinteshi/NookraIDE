package io.github.kinteshi.quirecode.workbench

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.window.Dialog
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.FirstPage
import androidx.compose.material.icons.outlined.LastPage
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.background
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.FilledTonalButton
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Button
import androidx.compose.ui.graphics.vector.ImageVector

@Composable
fun WorkbenchDialogHost(controller: WorkbenchController) {
    when (val dialog = controller.dialog) {
        null -> Unit
        WorkbenchDialog.Connect -> ConnectionsDialog(controller)
        WorkbenchDialog.ConnectionWizard -> ConnectionWizardDialog(controller)
        WorkbenchDialog.ComputerPairing -> ComputerPairingDialog(controller)
        is WorkbenchDialog.SaveToDevice -> SaveToDeviceDialog(controller, dialog)
        is WorkbenchDialog.Import -> ImportDialog(controller, dialog)
        is WorkbenchDialog.ConnectionEditor -> ConnectionEditorDialog(controller, dialog)
        WorkbenchDialog.QuickOpen -> QuickOpenDialog(controller)
        WorkbenchDialog.GotoLine -> AlertDialog(
            onDismissRequest = { controller.dialog = null },
            title = { Text("Go to line") },
            text = {
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { focus.requestFocus() }
                OutlinedTextField(
                    value = controller.gotoLine,
                    onValueChange = { controller.gotoLine = it.filter(Char::isDigit) },
                    label = { Text("Line number") },
                    singleLine = true,
                    modifier = Modifier.focusRequester(focus),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { controller.goToLine() }),
                )
            },
            confirmButton = { TextButton(onClick = controller::goToLine) { Text("Go") } },
            dismissButton = { TextButton(onClick = { controller.dialog = null }) { Text("Cancel") } },
        )
        WorkbenchDialog.Encoding -> controller.activeDocument?.let { document ->
            ChoiceDialog("File encoding", document.encoding, listOf("UTF-8", "UTF-16LE", "UTF-16BE", "ISO-8859-1"), controller::setEncoding) {
                controller.dialog = null
            }
        }
        WorkbenchDialog.LineEnding -> controller.activeDocument?.let { document ->
            ChoiceDialog("Line ending", if (document.eol == "\r\n") "CRLF" else "LF", listOf("LF", "CRLF"), controller::setLineEnding) {
                controller.dialog = null
            }
        }
        WorkbenchDialog.IndentationPicker -> controller.activeDocument?.let { document -> IndentationDialog(controller, document) }
        WorkbenchDialog.UntitledLanguage -> controller.activeDocument?.let { document ->
            val current = UNTITLED_LANGUAGES.entries.firstOrNull { it.value == document.languageId }?.key ?: "Plain text"
            ChoiceDialog("Language", current, UNTITLED_LANGUAGES.keys.toList(), controller::setUntitledLanguage) {
                controller.dialog = null
            }
        }
        is WorkbenchDialog.PathPrompt -> PathPromptDialog(controller, dialog)
        is WorkbenchDialog.GuestSetup -> GuestSetupDialog(controller, dialog)
        is WorkbenchDialog.GitPrompt -> GitPromptDialog(controller, dialog)
        is WorkbenchDialog.GitDiff -> AlertDialog(
            onDismissRequest = { controller.dialog = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
            modifier = Modifier.padding(24.dp).fillMaxWidth(),
            title = { Text(dialog.title) },
            text = {
                // A file's change: each hunk can be staged, unstaged or discarded on its own.
                val actions: (@Composable (Int) -> Unit)? = dialog.entry?.let {
                    { index ->
                        @Composable
                        fun HunkButton(action: WorkbenchController.HunkAction, text: String) {
                            TextButton(
                                onClick = { controller.applyHunk(dialog, index, action) },
                                enabled = controller.gitBusy == null,
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                                modifier = Modifier.height(28.dp),
                            ) { Text(text, style = MaterialTheme.typography.labelMedium) }
                        }
                        if (dialog.staged) {
                            HunkButton(WorkbenchController.HunkAction.Unstage, "Unstage")
                        } else {
                            HunkButton(WorkbenchController.HunkAction.Discard, "Discard")
                            HunkButton(WorkbenchController.HunkAction.Stage, "Stage")
                        }
                    }
                }
                DiffView(dialog.patch, singleFile = dialog.entry != null, hunkActions = actions)
            },
            confirmButton = { TextButton(onClick = { controller.dialog = null }) { Text("Close") } },
        )
        is WorkbenchDialog.GitStashes -> AlertDialog(
            onDismissRequest = { controller.dialog = null },
            title = { Text("Stashes") },
            text = {
                LazyColumn(Modifier.heightIn(max = 440.dp)) {
                    items(dialog.stashes) { (index, description) ->
                        var dropping by remember { mutableStateOf(false) }
                        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text("stash@{$index}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(description, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { controller.stashAction(index, io.github.kinteshi.quirecode.transport.StashOperation.Apply(index)) }) { Text("Apply") }
                                TextButton(onClick = { controller.stashAction(index, io.github.kinteshi.quirecode.transport.StashOperation.Pop(index)) }) { Text("Pop") }
                                // Dropping loses the stash: a second tap confirms, as VS Code asks first.
                                TextButton(onClick = {
                                    if (dropping) controller.stashAction(index, io.github.kinteshi.quirecode.transport.StashOperation.Drop(index)) else dropping = true
                                }) { Text(if (dropping) "Drop it" else "Drop", color = if (dropping) MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified) }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { controller.dialog = null }) { Text("Close") } },
        )
        is WorkbenchDialog.GitFileHistory -> PaletteDialog(
            placeholder = "${dialog.path.substringAfterLast('/')}: pick a commit to see its change",
            query = controller.commandFilter,
            onQuery = { controller.commandFilter = it },
            items = dialog.commits.filter { fuzzyMatch(controller.commandFilter, it.subject) || it.hash.startsWith(controller.commandFilter) },
            onDismiss = { controller.dialog = null },
            onPick = { commit -> controller.showFileAtCommit(dialog.path, commit) },
        ) { commit -> PaletteRow(commit.subject, "${commit.author} · ${relativeTime(commit.timestamp)} · ${commit.hash.take(7)}") }
        is WorkbenchDialog.GitBranches -> GitBranchesDialog(controller, dialog)
        is WorkbenchDialog.ConfirmClose -> AlertDialog(
            onDismissRequest = controller::cancelClosing,
            title = { Text("Save changes to ${dialog.path.substringAfterLast('/')}?") },
            text = { Text("Your changes will be lost if you don't save them.") },
            confirmButton = { TextButton(onClick = { controller.saveAndClose(dialog.path) }) { Text("Save") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { controller.closeDiscarding(dialog.path) }) { Text("Don't save", color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = controller::cancelClosing) { Text("Cancel") }
                }
            },
        )
        is WorkbenchDialog.ConfirmConnectionSwitch -> AlertDialog(
            onDismissRequest = { controller.dialog = null },
            title = { Text("Switch connection?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val target = controller.profiles.firstOrNull { it.id == dialog.profileId }?.name ?: "that connection"
                    if (dialog.recent != null) {
                        Text("Open ${controller.displayName(dialog.recent.path)} from ${controller.recentWorkspaceOrigin(dialog.recent)}?")
                    } else {
                        Text("Switch to $target?")
                    }
                    if (dialog.hasUnsaved) {
                        Text("Unsaved edits are kept as drafts for this folder.")
                    }
                    if (dialog.hasRunning) {
                        Text("Running terminals, kernels or debugging may be interrupted.")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { controller.confirmConnectionSwitch(dialog.profileId, dialog.recent) }) {
                    Text(if (dialog.recent == null) "Switch" else "Switch and open")
                }
            },
            dismissButton = { TextButton(onClick = { controller.dialog = null }) { Text("Cancel") } },
        )
        is WorkbenchDialog.ConfirmOfflineWorkspace -> AlertDialog(
            onDismissRequest = { controller.dialog = null },
            title = { Text("Open offline copy?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Open ${controller.displayName(dialog.recent.path)} from this device? QuireCode will disconnect " +
                            "from ${controller.profileName.ifBlank { "the current guest" }}.",
                    )
                    if (dialog.hasUnsaved) Text("Unsaved edits are kept as drafts for this folder.")
                    if (dialog.hasRunning) Text("Running terminals, kernels or debugging may be interrupted.")
                }
            },
            confirmButton = {
                TextButton(onClick = { controller.confirmOfflineWorkspace(dialog.recent) }) { Text("Disconnect and open") }
            },
            dismissButton = { TextButton(onClick = { controller.dialog = null }) { Text("Cancel") } },
        )
        is WorkbenchDialog.ConfirmWorkspaceSwitch -> AlertDialog(
            onDismissRequest = { controller.dialog = null },
            title = { Text("Switch folder?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Open ${controller.displayName(dialog.path)}?")
                    if (dialog.hasUnsaved) Text("Unsaved edits are kept as drafts for this folder.")
                    if (dialog.hasRunning) Text("Running kernels, debugging or chat turns will stop.")
                }
            },
            confirmButton = {
                TextButton(onClick = { controller.confirmWorkspaceSwitch(dialog.path) }) { Text("Switch folder") }
            },
            dismissButton = { TextButton(onClick = { controller.dialog = null }) { Text("Cancel") } },
        )
        is WorkbenchDialog.ConfirmWorkOffline -> AlertDialog(
            onDismissRequest = { controller.dialog = null },
            title = { Text("Work offline?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Disconnect from ${controller.profileName.ifBlank { "the current guest" }} and keep " +
                            "${controller.workspaceRoot?.let(controller::displayName) ?: "this workspace"} open on this device?",
                    )
                    if (dialog.hasUnsaved) Text("Unsaved edits stay in your open tabs and sync when you reconnect.")
                    if (dialog.hasRunning) Text("Running terminals, kernels or debugging will be disconnected.")
                }
            },
            confirmButton = {
                TextButton(onClick = controller::confirmWorkOffline) { Text("Work offline") }
            },
            dismissButton = { TextButton(onClick = { controller.dialog = null }) { Text("Cancel") } },
        )
        is WorkbenchDialog.GitConfirmDiscard -> AlertDialog(
            onDismissRequest = { controller.dialog = null },
            title = { Text(if (dialog.paths.size == 1) "Discard changes?" else "Discard ${dialog.paths.size} files' changes?") },
            text = {
                Text(
                    buildString {
                        append(if (dialog.paths.size == 1) dialog.paths.first() else "${dialog.paths.size} files")
                        append(" will lose their uncommitted changes. This can't be undone.")
                        if (dialog.untracked > 0) append(" ${dialog.untracked} untracked file${if (dialog.untracked == 1) " is" else "s are"} deleted.")
                    },
                )
            },
            confirmButton = { TextButton(onClick = { controller.discardFiles(dialog.paths) }) { Text("Discard", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { controller.dialog = null }) { Text("Cancel") } },
        )
        WorkbenchDialog.GitClone -> GitCloneDialog(controller)
        WorkbenchDialog.GuestResources -> GuestResourcesDialog(controller)
        WorkbenchDialog.Licenses -> LicensesDialog(controller)
        WorkbenchDialog.GuestStopped -> AlertDialog(
            onDismissRequest = { controller.dialog = null },
            icon = { Icon(Icons.Outlined.CloudOff, contentDescription = null) },
            title = { Text("Why the guest stops") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("The Linux guest runs in Linux Terminal's virtual machine, which stops when:")
                    Text("• Linux Terminal is closed. Back, or swiping it away from Recents, shuts the guest down; leave it with Home instead.")
                    Text("• Android needs the memory. With the tablet low on memory, it can stop the virtual machine.")
                    Text(
                        "• Apps start too many background processes. Android allows 32 across all apps, and the virtual machine takes 6. " +
                            "Past that, switching apps makes Android stop some, which can include the guest. Build tools, data loaders and " +
                            "other terminal apps can start many. QuireCode starts none on the tablet; its work runs in the guest.",
                    )
                    Text(
                        "Developer options may have a switch that lifts that limit (\"Disable child process restrictions\" on Android 14 and " +
                            "later). It's a device setting for you to decide on; QuireCode doesn't change it.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "When the guest stops, its terminals and running kernels end with it. Files saved in the guest are kept, and edits " +
                            "made here meanwhile are kept on this device until it's back.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { controller.dialog = null; controller.openLinuxTerminal() }) { Text("Open Terminal") } },
            dismissButton = { TextButton(onClick = { controller.dialog = null }) { Text("Close") } },
        )
        is WorkbenchDialog.InstallDependency -> AlertDialog(
            onDismissRequest = { controller.dialog = null },
            icon = { Icon(Icons.Outlined.Download, contentDescription = null) },
            title = { Text("Install ${dialog.dependency.label}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(dialog.reason ?: "${dialog.dependency.label} isn't installed in the guest.")
                    Text("Used for: ${dialog.dependency.usedFor}.", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "From ${dialog.dependency.source}. It runs in a new terminal, where you can follow it" +
                            if ("sudo " in dialog.dependency.install) " and enter your password if sudo asks." else ".",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            dialog.dependency.install,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            },
            confirmButton = { Button(onClick = { controller.installDependency(dialog.dependency, dialog.then) }) { Text("Install") } },
            dismissButton = { TextButton(onClick = { controller.dialog = null }) { Text("Not now") } },
        )
        is WorkbenchDialog.OpenFolder -> OpenFolderDialog(controller, dialog)
        is WorkbenchDialog.GuestCommand -> AlertDialog(
            onDismissRequest = { controller.dialog = null },
            icon = { Icon(Icons.Outlined.Terminal, contentDescription = null) },
            title = { Text(dialog.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(dialog.text)
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            dialog.command,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            },
            confirmButton = { Button(onClick = { controller.runGuestCommand(dialog) }) { Text("Run") } },
            dismissButton = { TextButton(onClick = { controller.dialog = null }) { Text("Not now") } },
        )
        is WorkbenchDialog.InterpreterPicker -> {
            val choices: List<Any> = dialog.options + dialog.setup
            PaletteDialog(
                placeholder = "Select a Python interpreter",
                query = controller.commandFilter,
                onQuery = { controller.commandFilter = it },
                items = choices.filter { choice ->
                    @Suppress("UNCHECKED_CAST")
                    fuzzyMatch(controller.commandFilter, if (choice is EnvironmentSetup) setupLabel(choice) else (choice as Pair<String, String>).first)
                },
                onDismiss = { controller.dialog = null },
                onPick = { choice ->
                    @Suppress("UNCHECKED_CAST")
                    if (choice is EnvironmentSetup) controller.setUpEnvironment(choice, dialog.then)
                    else controller.selectInterpreter((choice as Pair<String, String>).second, dialog.then)
                },
            ) { choice ->
                @Suppress("UNCHECKED_CAST")
                if (choice is EnvironmentSetup) {
                    SetupRow(setupLabel(choice), setupDetail(choice), icon = if (choice == EnvironmentSetup.CreateVenv) Icons.Outlined.Add else Icons.Outlined.Download)
                }
                else (choice as Pair<String, String>).let { (label, path) -> PaletteRow(label.substringBeforeLast(" ("), path) }
            }
        }
        is WorkbenchDialog.ConfirmDelete -> AlertDialog(
            onDismissRequest = { controller.dialog = null },
            title = {
                Text(
                    when {
                        dialog.paths.size > 1 -> "Delete ${dialog.paths.size} items?"
                        dialog.isDirectory -> "Delete folder?"
                        else -> "Delete file?"
                    },
                )
            },
            text = {
                val names = dialog.paths.take(5).joinToString(", ") { it.substringAfterLast('/') } + if (dialog.paths.size > 5) " and ${dialog.paths.size - 5} more" else ""
                Text(
                    if (dialog.paths.any { io.github.kinteshi.quirecode.document.isTreePath(it) }) {
                        "$names will be deleted from this device. This can't be undone."
                    } else {
                        "$names will be moved to the guest's trash. Undo brings them back for 10 seconds."
                    },
                )
            },
            confirmButton = { TextButton(onClick = { controller.deleteEntries(dialog.paths) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { controller.dialog = null }) { Text("Cancel") } },
        )
        WorkbenchDialog.Rename -> AlertDialog(
            onDismissRequest = { controller.dialog = null },
            title = { Text("Rename symbol") },
            text = {
                OutlinedTextField(
                    value = controller.renameValue,
                    onValueChange = { controller.renameValue = it },
                    label = { Text("New name") },
                    singleLine = true,
                    keyboardActions = KeyboardActions(onDone = { controller.applyRename() }),
                )
            },
            confirmButton = { TextButton(onClick = controller::applyRename) { Text("Rename") } },
            dismissButton = { TextButton(onClick = { controller.dialog = null }) { Text("Cancel") } },
        )
        WorkbenchDialog.SettingsJson -> AlertDialog(
            onDismissRequest = { controller.dialog = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
            modifier = Modifier.padding(32.dp),
            title = { Text("settings.json") },
            text = {
                Column {
                    Text(
                        "User settings live on this device. Workspace settings are read from .vscode/settings.json in the guest.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = controller.settingsDraft,
                        onValueChange = { controller.settingsDraft = it },
                        modifier = Modifier.fillMaxWidth().height(420.dp).padding(top = 8.dp),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            },
            confirmButton = { TextButton(onClick = controller::saveSettingsJson) { Text("Save") } },
            dismissButton = { TextButton(onClick = { controller.dialog = null }) { Text("Cancel") } },
        )
        WorkbenchDialog.Keybindings -> KeybindingsDialog(controller)
        is WorkbenchDialog.TerminalRename -> {
            // The current name selected, so typing replaces it (VS Code's rename box).
            var name by remember(dialog.id) { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(dialog.name, androidx.compose.ui.text.TextRange(0, dialog.name.length))) }
            AlertDialog(
                onDismissRequest = { controller.dialog = null },
                title = { Text("Rename terminal") },
                text = {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name") },
                        singleLine = true,
                        keyboardActions = KeyboardActions(onDone = { controller.renameTerminal(dialog.id, name.text) }),
                    )
                },
                confirmButton = { TextButton(onClick = { controller.renameTerminal(dialog.id, name.text) }) { Text("Rename") } },
                dismissButton = { TextButton(onClick = { controller.dialog = null }) { Text("Cancel") } },
            )
        }
        is WorkbenchDialog.EditBreakpoint -> {
            // VS Code's breakpoint widget, as three fields: any of them may be set together.
            var condition by remember(dialog) { mutableStateOf(dialog.options.condition) }
            var hitCount by remember(dialog) { mutableStateOf(dialog.options.hitCondition) }
            var message by remember(dialog) { mutableStateOf(dialog.options.logMessage) }
            val focus = remember(dialog) { androidx.compose.ui.focus.FocusRequester() }
            LaunchedEffect(dialog) { focus.requestFocus() }
            fun save() = controller.saveBreakpoint(dialog.path, dialog.line, io.github.kinteshi.quirecode.debug.BreakpointOptions(condition, hitCount, message))
            fun Modifier.focusedFor(field: BreakpointField) = if (field == dialog.focus) focusRequester(focus) else this
            AlertDialog(
                onDismissRequest = { controller.dialog = null },
                title = { Text(if (dialog.exists) "Breakpoint on line ${dialog.line}" else "Add breakpoint on line ${dialog.line}") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = condition,
                            onValueChange = { condition = it },
                            label = { Text("Expression") },
                            placeholder = { Text("Break when it is true, e.g. i > 3") },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            keyboardActions = KeyboardActions(onDone = { save() }),
                            modifier = Modifier.fillMaxWidth().focusedFor(BreakpointField.Condition),
                        )
                        OutlinedTextField(
                            value = hitCount,
                            onValueChange = { hitCount = it },
                            label = { Text("Hit count") },
                            placeholder = { Text("Break after so many hits: 5, >5, %2") },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            keyboardActions = KeyboardActions(onDone = { save() }),
                            modifier = Modifier.fillMaxWidth().focusedFor(BreakpointField.HitCount),
                        )
                        OutlinedTextField(
                            value = message,
                            onValueChange = { message = it },
                            label = { Text("Log message") },
                            placeholder = { Text("Log instead of breaking: i is {i}") },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            keyboardActions = KeyboardActions(onDone = { save() }),
                            modifier = Modifier.fillMaxWidth().focusedFor(BreakpointField.LogMessage),
                        )
                        Text(
                            "A log message makes it a logpoint: it writes to the Debug console, {expressions} filled in, and doesn't stop.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                confirmButton = { TextButton(onClick = { save() }) { Text("Save") } },
                dismissButton = {
                    Row {
                        if (dialog.exists) TextButton(onClick = { controller.removeBreakpoint(dialog.path, dialog.line) }) { Text("Remove") }
                        TextButton(onClick = { controller.dialog = null }) { Text("Cancel") }
                    }
                },
            )
        }
        is WorkbenchDialog.SetVariable -> {
            val variable = dialog.variable
            var value by remember(dialog) {
                mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(variable.value, androidx.compose.ui.text.TextRange(0, variable.value.length)))
            }
            val focus = remember(dialog) { androidx.compose.ui.focus.FocusRequester() }
            LaunchedEffect(dialog) { focus.requestFocus() }
            AlertDialog(
                onDismissRequest = { controller.dialog = null },
                title = { Text("Set value of ${variable.name}") },
                text = {
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it },
                        label = { Text("New value (an expression)") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        keyboardActions = KeyboardActions(onDone = { controller.setVariableValue(variable, value.text) }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                },
                confirmButton = { TextButton(onClick = { controller.setVariableValue(variable, value.text) }) { Text("Set") } },
                dismissButton = { TextButton(onClick = { controller.dialog = null }) { Text("Cancel") } },
            )
        }
        WorkbenchDialog.TerminalProfiles -> PaletteDialog(
            placeholder = "Select the terminal profile to create",
            query = controller.commandFilter,
            onQuery = { controller.commandFilter = it },
            items = controller.terminalProfiles.filter { fuzzyMatch(controller.commandFilter, it.name) },
            onDismiss = { controller.dialog = null },
            onPick = { profile ->
                controller.dialog = null
                controller.newTerminal(profile = profile)
            },
        ) { profile ->
            PaletteRow(
                profile.name + if (profile.name == controller.settings.terminalDefaultProfile) "  (default)" else "",
                (listOf(profile.path) + profile.args).joinToString(" "),
            )
        }
        is WorkbenchDialog.InsertSnippet -> PaletteDialog(
            placeholder = "Select a snippet",
            query = controller.commandFilter,
            onQuery = { controller.commandFilter = it },
            items = dialog.snippets.filter { snippet -> fuzzyMatch(controller.commandFilter, snippet.name) || snippet.prefixes.any { fuzzyMatch(controller.commandFilter, it) } },
            onDismiss = { controller.dialog = null },
            onPick = controller::insertSnippet,
        ) { snippet ->
            PaletteRow(snippet.name, listOfNotNull(snippet.prefixes.firstOrNull(), snippet.description ?: snippet.source).joinToString("  ·  "))
        }
        is WorkbenchDialog.TerminalLinks -> PaletteDialog(
            placeholder = "Open a link from the terminal",
            query = controller.commandFilter,
            onQuery = { controller.commandFilter = it },
            items = dialog.links.filter { fuzzyMatch(controller.commandFilter, it.label) },
            onDismiss = { controller.dialog = null },
            onPick = controller::openTerminalLink,
        ) { link -> PaletteRow(link.label, if (link is TerminalLink.Web) "web" else "file") }
        is WorkbenchDialog.ConfirmReplaceAll -> AlertDialog(
            onDismissRequest = { controller.dialog = null },
            title = { Text("Replace all") },
            text = {
                Text(
                    "Replace ${dialog.matches} occurrence${if (dialog.matches == 1) "" else "s"} across ${dialog.files} file${if (dialog.files == 1) "" else "s"} " +
                        "with \"${controller.searchReplace}\"? Closed files are saved; open ones stay unsaved so you can check them.",
                )
            },
            confirmButton = { TextButton(onClick = controller::replaceAllSearchResults) { Text("Replace") } },
            dismissButton = { TextButton(onClick = { controller.dialog = null }) { Text("Cancel") } },
        )
        WorkbenchDialog.LanguagePacks -> {
            val installed = controller.installedLanguagePacks().map { it.first }.toSet()
            PaletteDialog(
                placeholder = "Language or file extension",
                query = controller.commandFilter,
                onQuery = { controller.commandFilter = it },
                items = io.github.kinteshi.quirecode.assets.LANGUAGE_PACKS.filter { pack ->
                    val query = controller.commandFilter.trim().removePrefix(".")
                    query.isEmpty() || pack.languageName.contains(query, true) || pack.extensions.any { it.equals(query, true) } ||
                        pack.filenames.any { it.contains(query, true) }
                },
                onDismiss = { controller.dialog = null },
                onPick = { pack ->
                    controller.dialog = null
                    if (pack.id !in installed) controller.installLanguagePack(pack)
                },
            ) { pack ->
                PaletteRow(
                    pack.languageName + when {
                        pack.id in installed -> "  ✓"
                        AardinkSyntax.builtInCovers(pack) -> "  (built in)"
                        else -> ""
                    },
                    (pack.extensions.take(4).map { ".$it" } + pack.filenames.take(2)).joinToString(" "),
                )
            }
        }
        is WorkbenchDialog.DataViewer -> DataViewerDialog(controller, dialog)
        is WorkbenchDialog.SharedFiles -> SharedFilesDialog(controller, dialog)
        is WorkbenchDialog.RecordKeybinding -> RecordKeybindingDialog(controller, dialog.commandId)
        WorkbenchDialog.KeybindingsJson -> AlertDialog(
            onDismissRequest = { controller.dialog = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
            modifier = Modifier.padding(32.dp),
            title = { Text("keybindings.json") },
            text = {
                Column {
                    Text(
                        "VS Code's format: [{\"key\": \"ctrl+k ctrl+r\", \"command\": \"…\"}]. A command starting with \"-\" removes " +
                            "that default shortcut. \"when\" clauses are kept but not used: a shortcut works wherever its command does.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = controller.settingsDraft,
                        onValueChange = { controller.settingsDraft = it },
                        modifier = Modifier.fillMaxWidth().height(420.dp).padding(top = 8.dp),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            },
            confirmButton = { TextButton(onClick = controller::saveKeybindingsJson) { Text("Save") } },
            dismissButton = { TextButton(onClick = { controller.dialog = null }) { Text("Cancel") } },
        )
        is WorkbenchDialog.CodeActions -> PaletteDialog(
            placeholder = "Quick fixes and refactorings",
            query = controller.commandFilter,
            onQuery = { controller.commandFilter = it },
            items = dialog.choices.filter { fuzzyMatch(controller.commandFilter, it.title) },
            onDismiss = { controller.dialog = null },
            onPick = controller::applyCodeAction,
        ) { choice -> PaletteRow(choice.title, null) }
        is WorkbenchDialog.Conflict -> {
            val onDevice = io.github.kinteshi.quirecode.document.isTreePath(dialog.document.path)
            AlertDialog(
            onDismissRequest = { controller.dialog = null },
            title = { Text(if (onDevice) "File changed on this device" else "File changed on the guest") },
            text = {
                Text(
                    if (onDevice) {
                        "Another app changed ${dialog.document.path.substringAfterLast('/')} since you opened it. Your edits are kept here. Load the changed file, or save your edits over it."
                    } else {
                        "Your local draft of ${dialog.document.path.substringAfterLast('/')} is kept. Load the guest copy, or save your draft over it."
                    },
                )
            },
            confirmButton = { TextButton(onClick = { controller.keepLocalAfterConflict(dialog.document) }) { Text("Keep my draft") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { controller.loadRemoteAfterConflict(dialog.document) }) { Text(if (onDevice) "Load the changed file" else "Load guest copy") }
                    TextButton(onClick = { controller.dialog = null }) { Text("Later") }
                }
            },
        )
        }
        is WorkbenchDialog.CopilotSignInCode -> AlertDialog(
            onDismissRequest = controller::abandonCopilotSignIn,
            title = { Text("Sign in to GitHub Copilot") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Enter this code on GitHub, then come back here:")
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp)) {
                        Text(
                            dialog.signIn.userCode,
                            style = MaterialTheme.typography.headlineMedium,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                        )
                    }
                    Text(dialog.signIn.verificationUri ?: "https://github.com/login/device", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { controller.continueCopilotSignIn(dialog.signIn) }) { Text("Copy code and open GitHub") } },
            dismissButton = { TextButton(onClick = controller::abandonCopilotSignIn) { Text("Cancel") } },
        )
        is WorkbenchDialog.KernelPicker -> {
            val session = controller.notebooks[dialog.path]
            val choices: List<Any> = dialog.specs + dialog.installable + dialog.setup
            PaletteDialog(
                placeholder = "Select a kernel",
                query = controller.commandFilter,
                onQuery = { controller.commandFilter = it },
                items = choices.filter { choice ->
                    fuzzyMatch(
                        controller.commandFilter,
                        when (choice) {
                            is io.github.kinteshi.quirecode.notebook.KernelSpec -> choice.displayName
                            is io.github.kinteshi.quirecode.notebook.InstallableKernel -> choice.label
                            is GuestDependency -> choice.label
                            else -> ""
                        },
                    )
                },
                onDismiss = {
                    controller.dialog = null
                    session?.let(controller::cancelPendingExecution)
                },
                onPick = { choice ->
                    when (choice) {
                        is io.github.kinteshi.quirecode.notebook.KernelSpec -> session?.let { controller.startKernel(it, choice) }
                        is io.github.kinteshi.quirecode.notebook.InstallableKernel -> session?.let { controller.installKernelPackage(it, choice) }
                        is GuestDependency -> session?.let { notebook -> controller.offerInstall(choice) { controller.chooseKernel(notebook) } }
                    }
                },
            ) { choice ->
                when (choice) {
                    is io.github.kinteshi.quirecode.notebook.KernelSpec -> PaletteRow(choice.displayName, choice.name)
                    is io.github.kinteshi.quirecode.notebook.InstallableKernel -> SetupRow(
                        if (choice.label == "system") "Create ~/.venv with ipykernel (system Python is managed by apt)"
                        else "Install ipykernel in Python (${choice.label})",
                        choice.python,
                        path = true,
                        icon = if (choice.label == "system") Icons.Outlined.Add else Icons.Outlined.Download,
                    )
                    is GuestDependency -> SetupRow("Install ${choice.label} (Miniforge)", choice.usedFor)
                }
            }
        }
    }
}

private fun setupLabel(setup: EnvironmentSetup): String = when (setup) {
    EnvironmentSetup.CreateVenv -> "Create ~/.venv with ipykernel"
    is EnvironmentSetup.Install -> "Install ${setup.dependency.label}"
}

private fun setupDetail(setup: EnvironmentSetup): String = when (setup) {
    EnvironmentSetup.CreateVenv -> "A virtual environment for scripts, debugging and notebooks"
    is EnvironmentSetup.Install -> setup.dependency.usedFor
}

/** A picker row that sets something up rather than choosing it, set apart by its background. */
@Composable
private fun SetupRow(headline: String, detail: String, path: Boolean = false, icon: ImageVector = Icons.Outlined.Download) {
    ListItem(
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        headlineContent = { Text(headline) },
        supportingContent = {
            Text(detail, fontFamily = if (path) FontFamily.Monospace else null, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    )
}

/** Command-palette style picker: a search field over a filtered list, Enter picks the first match. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> PaletteDialog(
    placeholder: String,
    query: String,
    onQuery: (String) -> Unit,
    items: List<T>,
    onDismiss: () -> Unit,
    onPick: (T) -> Unit,
    /** Start from [query] as it is (Quick Open's prefixes) rather than empty. */
    keepQuery: Boolean = false,
    header: @Composable () -> Unit = {},
    row: @Composable (T) -> Unit,
) {
    LaunchedEffect(Unit) { if (!keepQuery) onQuery("") }
    // The cursor goes after a prefix the caller set, and follows when the caller changes the text.
    var field by remember { mutableStateOf(TextFieldValue(query, TextRange(query.length))) }
    LaunchedEffect(query) { if (field.text != query) field = TextFieldValue(query, TextRange(query.length)) }
    // The row Enter picks; the arrow keys move it and a new query starts over.
    var selected by remember { mutableStateOf(0) }
    LaunchedEffect(query) { selected = 0 }
    val current = selected.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    val listState = rememberLazyListState()
    LaunchedEffect(current) {
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.none { it.index == current && it.offset >= 0 && it.offset + it.size <= listState.layoutInfo.viewportEndOffset }) {
            listState.scrollToItem(if (visible.firstOrNull()?.index ?: 0 < current) (current - visible.size + 2).coerceAtLeast(0) else current)
        }
    }
    // A window that fills the screen, with the box at a fixed place near the top as in VS Code: a
    // window sized to the results moved with each key and restarted the keyboard's input.
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss).imePadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
            modifier = Modifier.padding(horizontal = 16.dp).padding(top = 56.dp, bottom = 16.dp).widthIn(max = 680.dp).fillMaxWidth()
                // A tap on the box itself stays in it.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        ) {
            Column(Modifier.padding(12.dp)) {
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { focus.requestFocus() }
                OutlinedTextField(
                    value = field,
                    onValueChange = {
                        field = it
                        if (it.text != query) onQuery(it.text)
                    },
                    placeholder = { Text(placeholder) },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus)
                        .onPreviewKeyEvent { event ->
                            when {
                                event.type != KeyEventType.KeyDown -> false
                                event.key == Key.Enter -> {
                                    items.getOrNull(current)?.let(onPick)
                                    true
                                }
                                event.key == Key.Escape -> {
                                    onDismiss()
                                    true
                                }
                                event.key == Key.DirectionDown -> {
                                    if (items.isNotEmpty()) selected = (current + 1) % items.size
                                    true
                                }
                                event.key == Key.DirectionUp -> {
                                    if (items.isNotEmpty()) selected = (current - 1 + items.size) % items.size
                                    true
                                }
                                else -> false
                            }
                        },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { items.getOrNull(current)?.let(onPick) }),
                )
                header()
                LazyColumn(Modifier.heightIn(max = 440.dp).padding(top = 8.dp), state = listState) {
                    itemsIndexed(items) { index, item ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (index == current) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                                .clickable { onPick(item) },
                        ) { row(item) }
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun PaletteRow(title: String, detail: String?, leading: (@Composable () -> Unit)? = null) {
    ListItem(
        headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = leading,
        trailingContent = detail?.let {
            {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.height(48.dp),
    )
}

@Composable
private fun ChoiceDialog(title: String, selected: String, options: List<String>, onSelected: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { option ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onSelected(option) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == selected, onClick = { onSelected(option) })
                        Text(option)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/**
 * The Variables view's data viewer: a filter (text, or a pandas query), the kernel's rows a page
 * at a time, sorted by the column tapped (all rows, by the kernel).
 */
@Composable
private fun DataViewerDialog(controller: WorkbenchController, dialog: WorkbenchDialog.DataViewer) {
    val colors = MaterialTheme.colorScheme
    var query by remember(dialog.name) { mutableStateOf(dialog.query) }
    // Typing filters once it pauses.
    LaunchedEffect(query) {
        if (query == dialog.query) return@LaunchedEffect
        kotlinx.coroutines.delay(500)
        controller.loadDataViewer(dialog.copy(query = query, offset = 0))
    }
    val table = dialog.table
    // As big as the whole table needs, up to most of the screen. From its unfiltered size, and only
    // ever wider: a dialog window that resizes while the filter is typed restarts the keyboard's
    // input, which garbled the word being typed.
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    var tableWidth by remember(dialog.name) { mutableStateOf(0.dp) }
    table?.let { tableWidth = maxOf(tableWidth, dataViewerTableWidth(it.table)) }
    val windowWidth = (tableWidth + 50.dp).coerceIn(DATA_VIEWER_MIN_WIDTH, (configuration.screenWidthDp.dp - 48.dp).coerceAtLeast(DATA_VIEWER_MIN_WIDTH))
    val height = if (table == null) {
        180.dp
    } else {
        val rows = minOf(table.total, DATA_VIEWER_PAGE) + table.table.head.size
        // The table, the filter and the pager or note.
        (TABLE_ROW_HEIGHT * maxOf(rows, 3) + 130.dp).coerceAtMost((configuration.screenHeightDp * 0.72f).dp)
    }
    AlertDialog(
        onDismissRequest = { controller.dialog = null },
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.padding(24.dp).width(windowWidth),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(dialog.name, fontFamily = FontFamily.Monospace)
                androidx.compose.material3.CircularProgressIndicator(
                    Modifier.size(18.dp).alpha(if (dialog.loading) 1f else 0f),
                    strokeWidth = 2.dp,
                )
            }
        },
        text = {
            Column(Modifier.fillMaxWidth().height(height), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (table == null || table.pages) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                        placeholder = { Text("Filter: text in any column, or a pandas query like price > 10") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
                        keyboardActions = KeyboardActions(onSearch = { controller.loadDataViewer(dialog.copy(query = query, offset = 0)) }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                table?.filterError?.let {
                    Text("Not a pandas query ($it); matched as text", style = MaterialTheme.typography.labelSmall, color = colors.error)
                }
                when {
                    dialog.error != null -> Text(dialog.error, color = colors.error)
                    table == null -> Text("Asking the kernel…", color = colors.onSurfaceVariant)
                    !table.pages -> {
                        DataViewerTable(table.table, maxBodyHeight = height - 60.dp)
                        val shown = table.table.body.size
                        Text(
                            (if (table.total > shown) "First $shown of ${table.total} rows · tap a column to sort them" else "${table.total} rows · tap a column to sort") +
                                " · update the guest tools to filter and page",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    else -> {
                        BoxWithConstraints(Modifier.weight(1f)) {
                            val body = maxHeight - TABLE_ROW_HEIGHT * table.table.head.size - 2.dp
                            DataViewerTable(table.table, dialog.sort, dialog.ascending, maxBodyHeight = body.coerceAtLeast(TABLE_ROW_HEIGHT)) { column, ascending ->
                                controller.loadDataViewer(dialog.copy(sort = column, ascending = ascending, offset = 0))
                            }
                        }
                        val pageSize = table.table.body.size
                        // The height of the buttons with or without them: it holds the table's place.
                        Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                            val first = table.offset + 1
                            val last = table.offset + pageSize
                            Text(
                                when {
                                    table.matched == 0 -> if (dialog.query.isBlank()) "No rows" else "No rows match"
                                    else -> "Rows ${"%,d".format(first)}–${"%,d".format(last)} of ${"%,d".format(table.matched)}"
                                } + if (table.matched != table.total) " (filtered from ${"%,d".format(table.total)})" else "",
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            val step = maxOf(pageSize, 1)
                            val lastPage = ((table.matched - 1).coerceAtLeast(0) / DATA_VIEWER_PAGE) * DATA_VIEWER_PAGE
                            // One page has nothing to go to.
                            if (lastPage > 0) {
                            Tooltip("First page") { IconButton(enabled = table.offset > 0, onClick = { controller.loadDataViewer(dialog.copy(offset = 0)) }) {
                                Icon(Icons.Outlined.FirstPage, contentDescription = "First page")
                            } }
                            Tooltip("Previous page") { IconButton(enabled = table.offset > 0, onClick = { controller.loadDataViewer(dialog.copy(offset = (table.offset - DATA_VIEWER_PAGE).coerceAtLeast(0))) }) {
                                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, contentDescription = "Previous page")
                            } }
                            Tooltip("Next page") { IconButton(enabled = table.offset + step < table.matched, onClick = { controller.loadDataViewer(dialog.copy(offset = table.offset + step)) }) {
                                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = "Next page")
                            } }
                            Tooltip("Last page") { IconButton(enabled = table.offset < lastPage, onClick = { controller.loadDataViewer(dialog.copy(offset = lastPage)) }) {
                                Icon(Icons.Outlined.LastPage, contentDescription = "Last page")
                            } }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { controller.dialog = null }) { Text("Close") } },
    )
}

/** Files from the share sheet: the folder to save them in, or (one file) open it from the device. */
@Composable
private fun SharedFilesDialog(controller: WorkbenchController, shared: WorkbenchDialog.SharedFiles) {
    val root = controller.workspaceRoot?.trimEnd('/')
    var folder by remember(shared.uris) {
        mutableStateOf(if (root != null && shared.folder.startsWith(root)) shared.folder.removePrefix(root).trimStart('/') else shared.folder)
    }
    fun target() = if (root != null && !folder.startsWith("/")) (root + "/" + folder.trim().trim('/')).trimEnd('/') else folder.trim()
    AlertDialog(
        onDismissRequest = { if (!shared.saving) controller.dialog = null },
        title = { Text(if (shared.names.size == 1) "Save ${shared.names.single()}" else "Save ${shared.names.size} shared files") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (shared.names.size > 1) {
                    Text(
                        shared.names.take(6).joinToString(", ") + if (shared.names.size > 6) " and ${shared.names.size - 6} more" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = folder,
                    onValueChange = { folder = it },
                    singleLine = true,
                    enabled = !shared.saving,
                    label = { Text("Folder in the workspace (created if new; empty is the top)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                shared.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (shared.saving) Text("Saving…", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = !shared.saving && controller.connected, onClick = { controller.saveSharedFiles(shared, target()) }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (shared.uris.size == 1) {
                    TextButton(enabled = !shared.saving, onClick = {
                        controller.dialog = null
                        controller.openDeviceFile(shared.uris.single())
                    }) { Text("Open without saving") }
                }
                TextButton(enabled = !shared.saving, onClick = { controller.dialog = null }) { Text("Cancel") }
            }
        },
    )
}

/** The data viewer's page size; the controller asks for as many rows. */
internal const val DATA_VIEWER_PAGE = 500

/** The filter's hint and the pager fit in this, however small the table. */
private val DATA_VIEWER_MIN_WIDTH = 560.dp

/** VS Code's indentation quick pick, in one dialog: spaces or tabs, the size, detect again, convert. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IndentationDialog(controller: WorkbenchController, document: io.github.kinteshi.quirecode.document.DocumentSession) {
    val current = controller.indentation(document)
    AlertDialog(
        onDismissRequest = { controller.dialog = null },
        title = { Text("Indentation") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (document.indentation == null && document.detectedIndentation != null) "Detected from the file's content" else if (document.indentation != null) "Chosen for this file" else "From the settings",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(true to "Spaces", false to "Tabs").forEachIndexed { index, (spaces, label) ->
                        SegmentedButton(
                            selected = current.insertSpaces == spaces,
                            onClick = { controller.setIndentation(current.copy(insertSpaces = spaces)) },
                            shape = SegmentedButtonDefaults.itemShape(index, 2),
                        ) { Text(label) }
                    }
                }
                Text(if (current.insertSpaces) "Spaces per level" else "Tab width", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..8).forEach { size ->
                        FilterChip(
                            selected = current.tabSize == size,
                            onClick = { controller.setIndentation(current.copy(tabSize = size)) },
                            label = { Text("$size") },
                        )
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = controller::redetectIndentation) { Text("Detect from content") }
                    OutlinedButton(onClick = { controller.convertIndentationTo(current.copy(insertSpaces = !current.insertSpaces)) }) {
                        Text(if (current.insertSpaces) "Convert to tabs" else "Convert to spaces")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { controller.dialog = null }) { Text("Close") } },
    )
}

@Composable
private fun GuestSetupDialog(controller: WorkbenchController, setup: WorkbenchDialog.GuestSetup) {
    var code by remember { mutableStateOf("") }
    val colors = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = { controller.dialog = null },
        title = { Text("Set up this tablet's Linux guest") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                setup.reason?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant) }
                if (!controller.linuxTerminalAvailable()) {
                    Text(
                        "Linux Terminal isn't available yet. Turn on Linux development environment in Developer options, open " +
                            "Linux Terminal once so it installs its Debian guest, then come back here.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    FilledTonalButton(onClick = controller::openDeveloperOptions) { Text("Developer options") }
                }
                Text("1. Run this in Linux Terminal. It installs the QuireCode daemon and its tools, then prints a pairing code.", style = MaterialTheme.typography.bodyMedium)
                when {
                    controller.setupError != null -> Text(controller.setupError!!, style = MaterialTheme.typography.bodySmall, color = colors.error)
                    controller.setupCommand == null -> Text("Preparing the setup files…", style = MaterialTheme.typography.bodySmall)
                    else -> Surface(color = colors.surfaceContainerHighest, shape = MaterialTheme.shapes.small) {
                        SelectionContainer {
                            Text(
                                controller.setupCommand!!,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(10.dp),
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = controller::copySetupCommand, enabled = controller.setupCommand != null) { Text("Copy command") }
                    OutlinedButton(onClick = controller::openLinuxTerminal) { Text("Open Linux Terminal") }
                }
                Text(
                    "If Android asks to open port 19870, accept it. Already installed? Run " +
                        "`quirecode-daemon pair` in Linux Terminal for a new code.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
                Text("2. Enter the pairing code.", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.uppercase().filter { ch -> ch.isLetterOrDigit() || ch == '-' }.take(9) },
                    label = { Text("Pairing code") },
                    placeholder = { Text("XXXX-XXXX") },
                    singleLine = true,
                    isError = controller.pairingError != null,
                    supportingText = controller.pairingError?.let { error -> { Text(error) } },
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (code.count(Char::isLetterOrDigit) == 8) controller.pairAndConnect(code) }),
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { controller.dialog = WorkbenchDialog.ComputerPairing }) { Text("Pairing a computer instead?") }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { controller.pairAndConnect(code) },
                enabled = !controller.busy && code.count(Char::isLetterOrDigit) == 8,
            ) { Text(if (controller.busy) "Pairing…" else "Pair and connect") }
        },
        dismissButton = { TextButton(onClick = { controller.dialog = WorkbenchDialog.Connect }) { Text("Back") } },
    )
}

/** Subsequence match, like VS Code's quick pick: "wbc" matches "WorkbenchController". */
/**
 * How well [candidate] answers [query], lower first: it starts with it, a word starts with it, it
 * holds it, or (what's left of [fuzzyMatch]) only its letters are there in order.
 */
internal fun matchRank(query: String, candidate: String): Int {
    // Called for every file of a large folder at each key: no regular expressions here.
    val text = query.trim()
    var at = candidate.indexOf(text, ignoreCase = true)
    if (at == 0) return 0
    if (at < 0) return 3
    while (at > 0) {
        if (!candidate[at - 1].isLetterOrDigit()) return 1
        at = candidate.indexOf(text, at + 1, ignoreCase = true)
    }
    return 2
}

internal fun fuzzyMatch(query: String, candidate: String): Boolean {
    if (query.isBlank()) return true
    var index = 0
    for (char in candidate) {
        if (char.equals(query[index], ignoreCase = true) && ++index == query.length) return true
    }
    return false
}

@Composable
private fun PathPromptDialog(controller: WorkbenchController, prompt: WorkbenchDialog.PathPrompt) {
    val (title, label, confirm) = when (prompt.action) {
        is PathAction.NewFile -> Triple("New file", "Name (folders allowed: src/main.c)", "Create")
        is PathAction.NewFolder -> Triple("New folder", "Name", "Create")
        is PathAction.Rename -> Triple("Rename", "New name", "Rename")
        is PathAction.SaveAs -> Triple("Save as", "Path", "Save")
        is PathAction.Move -> Triple(
            "Move ${if (prompt.action.paths.size == 1) prompt.action.paths.single().substringAfterLast('/') else "${prompt.action.paths.size} items"}",
            "Folder in the workspace (created if new; empty is the top)",
            "Move",
        )
    }
    var value by remember(prompt.action) {
        // Select the name but not its extension, like VS Code, so typing replaces just the name.
        val start = if (prompt.action is PathAction.Move) 0 else prompt.initial.lastIndexOf('/') + 1
        val dot = prompt.initial.lastIndexOf('.').takeIf { it > start && prompt.action !is PathAction.Move } ?: prompt.initial.length
        mutableStateOf(TextFieldValue(prompt.initial, TextRange(start, dot)))
    }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(prompt.action) { focusRequester.requestFocus() }
    val parent = when (val action = prompt.action) {
        is PathAction.NewFile -> action.parent
        is PathAction.NewFolder -> action.parent
        else -> null
    }
    AlertDialog(
        onDismissRequest = { controller.dialog = null },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                parent?.let { Text("In ${controller.displayPath(it)}", style = MaterialTheme.typography.bodySmall) }
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(label) },
                    singleLine = true,
                    isError = prompt.error != null,
                    supportingText = prompt.error?.let { error -> { Text(error) } },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onDone = { controller.submitPathPrompt(prompt, value.text) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                )
            }
        },
        confirmButton = { TextButton(onClick = { controller.submitPathPrompt(prompt, value.text) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = { controller.dialog = null }) { Text("Cancel") } },
    )
}

@Composable
private fun GitPromptDialog(controller: WorkbenchController, dialog: WorkbenchDialog.GitPrompt) {
    var answer by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = { controller.answerGitPrompt(dialog.prompt, null) },
        title = { Text("Git needs credentials") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(dialog.prompt.text.trim(), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = answer,
                    onValueChange = { answer = it },
                    singleLine = true,
                    visualTransformation = if (dialog.prompt.secret) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { controller.answerGitPrompt(dialog.prompt, answer) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                if (dialog.prompt.secret) {
                    Text(
                        "For GitHub, use a personal access token as the password. It goes to Git in the guest and isn't stored by the app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { controller.answerGitPrompt(dialog.prompt, answer) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = { controller.answerGitPrompt(dialog.prompt, null) }) { Text("Cancel") } },
    )
}

@Composable
private fun GitBranchesDialog(controller: WorkbenchController, dialog: WorkbenchDialog.GitBranches) {
    var newBranch by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { controller.dialog = null },
        title = { Text("Checkout to…") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = newBranch,
                    onValueChange = { newBranch = it.replace(' ', '-') },
                    label = { Text("New branch from current") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (newBranch.isNotBlank()) controller.checkoutBranch(newBranch.trim(), create = true) }),
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(dialog.branches) { branch ->
                        ListItem(
                            headlineContent = { Text(branch.name, fontWeight = if (branch.isCurrent) FontWeight.Bold else null) },
                            supportingContent = branch.upstream?.let { upstream -> { Text(upstream) } },
                            modifier = Modifier.clickable(enabled = !branch.isCurrent) { controller.checkoutBranch(branch.name, create = false) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { controller.checkoutBranch(newBranch.trim(), create = true) }, enabled = newBranch.isNotBlank()) {
                Text("Create branch")
            }
        },
        dismissButton = { TextButton(onClick = { controller.dialog = null }) { Text("Cancel") } },
    )
}

@Composable
private fun GitCloneDialog(controller: WorkbenchController) {
    var url by remember { mutableStateOf("") }
    var folder by remember { mutableStateOf("") }
    val suggested = url.trim().trimEnd('/').substringAfterLast('/').removeSuffix(".git")
    AlertDialog(
        onDismissRequest = { controller.dialog = null },
        title = { Text("Clone repository") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Repository URL") },
                    placeholder = { Text("https://github.com/owner/repo.git") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = folder,
                    onValueChange = { folder = it },
                    label = { Text("Folder in the workspace") },
                    placeholder = { Text(suggested.ifEmpty { "repo" }) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { controller.gitClone(url, folder.ifBlank { suggested }) },
                enabled = url.isNotBlank() && (folder.isNotBlank() || suggested.isNotEmpty()),
            ) { Text("Clone") }
        },
        dismissButton = { TextButton(onClick = { controller.dialog = null }) { Text("Cancel") } },
    )
}

/** A unified diff with added, removed and hunk lines coloured. */
/**
 * A file's diff side by side (old left, new right, line numbers, the changed part of a changed
 * line marked), as VS Code's diff editor; "Inline" shows the unified patch. Side by side is the
 * default where the dialog is wide enough.
 */
@Composable
private fun DiffView(patch: String, singleFile: Boolean = true, hunkActions: (@Composable (Int) -> Unit)? = null) {
    // Side by side reads one file's patch; a commit's several files read better inline.
    val wide = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 700 && singleFile
    run {
        var sideBySide by remember { mutableStateOf(wide) }
        Column {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                androidx.compose.material3.FilterChip(selected = sideBySide, onClick = { sideBySide = true }, label = { Text("Side by side") })
                androidx.compose.material3.FilterChip(selected = !sideBySide, onClick = { sideBySide = false }, label = { Text("Inline") })
            }
            if (sideBySide) SideBySideDiff(patch, hunkActions) else DiffText(patch, hunkActions)
        }
    }
}

@Composable
private fun SideBySideDiff(patch: String, hunkActions: (@Composable (Int) -> Unit)?) {
    val colors = MaterialTheme.colorScheme
    val rows = remember(patch) { sideBySideRows(patch) }
    val removedTint = colors.errorContainer.copy(alpha = 0.45f)
    val addedTint = androidx.compose.ui.graphics.Color(0x3348B04F)
    val removedStrong = colors.error.copy(alpha = 0.35f)
    val addedStrong = androidx.compose.ui.graphics.Color(0x6648B04F)
    val style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    if (rows.isEmpty()) {
        Text("No changes to show.", color = colors.onSurfaceVariant)
        return
    }
    @Composable
    fun Side(number: Int?, text: String?, tint: androidx.compose.ui.graphics.Color, strong: androidx.compose.ui.graphics.Color, span: IntRange?, modifier: Modifier) {
        Row(modifier.background(if (text == null) colors.surfaceContainerHighest.copy(alpha = 0.4f) else tint)) {
            Text(
                number?.toString().orEmpty(),
                style = style,
                color = colors.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                modifier = Modifier.width(40.dp).padding(end = 6.dp),
            )
            val line = text.orEmpty()
            Text(
                androidx.compose.ui.text.buildAnnotatedString {
                    if (span != null && !span.isEmpty() && span.last < line.length) {
                        append(line.substring(0, span.first))
                        pushStyle(androidx.compose.ui.text.SpanStyle(background = strong))
                        append(line.substring(span.first, span.last + 1))
                        pop()
                        append(line.substring(span.last + 1))
                    } else {
                        append(line.ifEmpty { " " })
                    }
                },
                style = style,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                modifier = Modifier.weight(1f),
            )
        }
    }
    // Each hunk row's number among the hunks, for its actions.
    val hunkIndex = remember(rows) {
        var next = 0
        rows.map { if (it.kind == DiffKind.Hunk) next++ else -1 }
    }
    LazyColumn(Modifier.heightIn(max = 560.dp)) {
        items(rows.size) { position ->
            val row = rows[position]
            if (row.kind == DiffKind.Hunk) {
                HunkHeader(row.left.orEmpty(), style, hunkIndex[position], hunkActions)
                return@items
            }
            val spans = if (row.kind == DiffKind.Changed) changedSpans(row.left.orEmpty(), row.right.orEmpty()) else null
            Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
                Side(
                    row.leftNumber, row.left,
                    if (row.kind == DiffKind.Removed || row.kind == DiffKind.Changed) removedTint else androidx.compose.ui.graphics.Color.Transparent,
                    removedStrong, spans?.first, Modifier.weight(1f).fillMaxHeight(),
                )
                Box(Modifier.width(1.dp).fillMaxHeight().background(colors.outlineVariant))
                Side(
                    row.rightNumber, row.right,
                    if (row.kind == DiffKind.Added || row.kind == DiffKind.Changed) addedTint else androidx.compose.ui.graphics.Color.Transparent,
                    addedStrong, spans?.second, Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
    }
}

/** A hunk's `@@` line, with its Stage / Unstage / Discard buttons when the diff is a file's change. */
@Composable
private fun HunkHeader(text: String, style: androidx.compose.ui.text.TextStyle, index: Int, actions: (@Composable (Int) -> Unit)?) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().background(colors.secondaryContainer).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = style, color = colors.onSecondaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (actions != null && index >= 0) actions(index)
    }
}

@Composable
private fun DiffText(patch: String, hunkActions: (@Composable (Int) -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    // A file's change starts at its first hunk; a commit keeps its message and file headers.
    val lines = remember(patch) { if (hunkActions != null) patch.lines().dropWhile { !it.startsWith("@@") }.ifEmpty { patch.lines() } else patch.lines() }
    val hunkIndex = remember(lines) {
        var next = 0
        lines.map { if (it.startsWith("@@")) next++ else -1 }
    }
    // Only lines inside a hunk are additions and removals (a commit's `---` and file headers aren't).
    val inHunk = remember(lines) {
        var inside = false
        lines.map { line ->
            if (line.startsWith("@@")) inside = true else if (line.startsWith("diff --git")) inside = false
            inside
        }
    }
    LazyColumn(Modifier.heightIn(max = 560.dp)) {
        items(lines.size) { position ->
            val line = lines[position]
            if (line.startsWith("@@") && hunkActions != null) {
                HunkHeader(line, MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), hunkIndex[position], hunkActions)
                return@items
            }
            val (background, foreground) = when {
                line.startsWith("@@") -> colors.secondaryContainer to colors.onSecondaryContainer
                inHunk[position] && line.startsWith("+") -> androidx.compose.ui.graphics.Color(0x3348B04F) to colors.onSurface
                inHunk[position] && line.startsWith("-") -> colors.errorContainer.copy(alpha = 0.5f) to colors.onSurface
                else -> androidx.compose.ui.graphics.Color.Transparent to colors.onSurfaceVariant
            }
            Text(
                line.ifEmpty { " " },
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = foreground,
                modifier = Modifier.fillMaxWidth().background(background).padding(horizontal = 6.dp),
            )
        }
    }
}

/** Guest memory and CPU, and what the app runs there. */
@Composable
private fun GuestResourcesDialog(controller: WorkbenchController) {
    val colors = MaterialTheme.colorScheme
    val stats = controller.guestStats
    AlertDialog(
        onDismissRequest = { controller.dialog = null },
        title = { Text("Guest resources") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (stats == null) {
                    Text("Waiting for the first sample…")
                } else {
                    val total = stats.totalMb.coerceAtLeast(1)
                    val balloon = stats.balloonMb.coerceAtMost(total)
                    val programs = stats.programsMb.coerceAtMost(total - balloon)
                    val cache = stats.cachedMb.coerceAtMost(total - balloon - programs)
                    Text(
                        "Costs the tablet %.1f of %.1f GB · CPU %d%%".format(stats.footprintMb / 1024f, total / 1024f, stats.cpuPercent.toInt()),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Row(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(colors.surfaceContainerHighest)) {
                        if (programs > 0) Box(Modifier.weight(programs.toFloat()).fillMaxHeight().background(colors.primary))
                        if (cache > 0) Box(Modifier.weight(cache.toFloat()).fillMaxHeight().background(colors.tertiary))
                        val rest = total - programs - cache - balloon
                        if (rest > 0) Box(Modifier.weight(rest.toFloat()).fillMaxHeight())
                        if (balloon > 0) Box(Modifier.weight(balloon.toFloat()).fillMaxHeight().background(colors.outlineVariant))
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Legend(colors.primary, "Programs ${programs} MB")
                        Legend(colors.tertiary, "Cache ${cache} MB")
                        Legend(colors.surfaceContainerHighest, "Free ${stats.freeMb} MB")
                        if (balloon > 0) Legend(colors.outlineVariant, "Returned to Android ${balloon} MB")
                    }
                    Text(
                        "The guest keeps file cache until it runs short, and the tablet pays for it meanwhile. " +
                            "Linux Terminal takes memory back (the balloon) while it is in the background. " +
                            "Stopping tools you don't need is the reliable way to give memory back.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                    FootprintGraph(controller.guestFootprintHistory, total, colors.primary)
                    if (stats.diskTotalMb > 0) {
                        Text(
                            "Storage: %.1f of %.1f GB used".format(stats.diskUsedMb / 1024f, stats.diskTotalMb / 1024f),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        val used = stats.diskUsedMb.coerceIn(0, stats.diskTotalMb).toFloat()
                        Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(colors.surfaceContainerHighest)) {
                            if (used > 0) Box(Modifier.weight(used).fillMaxHeight().background(colors.secondary))
                            val free = stats.diskTotalMb - used
                            if (free > 0) Box(Modifier.weight(free).fillMaxHeight())
                        }
                    }
                }
                Text("Running for the app", style = MaterialTheme.typography.titleSmall)
                val components = controller.guestComponents
                if (components.isEmpty()) {
                    Text("Nothing besides the daemon.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                } else {
                    components.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                }
                FilledTonalButton(onClick = controller::stopIdleGuestTools, enabled = components.isNotEmpty()) { Text("Stop idle tools") }
            }
        },
        confirmButton = { TextButton(onClick = { controller.dialog = null }) { Text("Close") } },
    )
}

@Composable
private fun Legend(color: androidx.compose.ui.graphics.Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Text(" $label", style = MaterialTheme.typography.labelSmall)
    }
}

/** The guest's footprint over the last ten minutes (5-second samples), scaled to its total memory. */
@Composable
private fun FootprintGraph(history: List<Long>, totalMb: Long, color: androidx.compose.ui.graphics.Color) {
    if (history.size < 2) return
    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(48.dp)) {
        val step = size.width / (history.size - 1)
        val path = androidx.compose.ui.graphics.Path()
        history.forEachIndexed { index, value ->
            val x = index * step
            val y = size.height * (1f - value.toFloat() / totalMb)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
    }
}

@Composable
private fun QuickOpenDialog(controller: WorkbenchController) {
    LaunchedEffect(Unit) { controller.refreshQuickOpenIndex() }
    val query = controller.quickOpenFilter
    val mode = quickOpenMode(query)
    LaunchedEffect(query) { if (mode == QuickOpenMode.WorkspaceSymbols) controller.searchWorkspaceSymbols(query.removePrefix(mode.prefix)) }
    val root = controller.workspaceRoot.orEmpty().trimEnd('/')
    PaletteDialog(
        placeholder = when {
            controller.quickOpenAttachesToChat -> "Add a file to chat"
            controller.quickOpenIndexing -> "Indexing workspace… (type ? for help)"
            else -> "Search files by name (type ? for help)"
        },
        query = query,
        onQuery = { controller.quickOpenFilter = it },
        items = controller.quickOpenItems(query),
        onDismiss = {
            controller.dialog = null
            controller.quickOpenAttachesToChat = false
        },
        onPick = controller::pickQuickOpen,
        keepQuery = true,
        header = {
            if (mode != QuickOpenMode.Files) {
                Text(
                    mode.title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp),
                )
            } else if (controller.quickOpenTruncated) {
                Text(
                    "This folder has many files: searching the first ${"%,d".format(controller.quickOpenIndex.size)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp),
                )
            }
        },
    ) { item ->
        when (item) {
            is QuickOpenItem.File -> PaletteRow(item.entry.name, item.entry.path.removePrefix(root).removePrefix("/").substringBeforeLast('/', "")) {
                FileIconView(item.entry.name, controller.fileIconTheme, LocalDarkTheme.current, Icons.AutoMirrored.Outlined.InsertDriveFile)
            }
            is QuickOpenItem.Command -> PaletteRow(item.command.title, item.keybinding.takeIf(String::isNotEmpty))
            is QuickOpenItem.Symbol -> PaletteRow(item.name, item.detail) {
                Icon(Icons.Outlined.Code, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is QuickOpenItem.Line -> PaletteRow(item.title, null)
            is QuickOpenItem.Prefix -> PaletteRow(item.mode.prefix.ifEmpty { "…" } + "  " + item.mode.title, null)
            is QuickOpenItem.Message -> Text(
                item.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

/** Keyboard Shortcuts: every command with its keys; a tap records new ones. */
@Composable
private fun KeybindingsDialog(controller: WorkbenchController) {
    val filter = controller.commandFilter
    val commands = controller.effectiveCommands
    val keys = commands.filter { it.keybinding.isNotEmpty() }.groupBy({ it.id }, { it.keybinding })
    PaletteDialog(
        placeholder = "Search commands or keys",
        query = filter,
        onQuery = { controller.commandFilter = it },
        items = EditorCommands.all.distinctBy { it.id }.filter { command ->
            fuzzyMatch(filter, command.title) || keys[command.id].orEmpty().any { it.contains(filter, true) }
        }.sortedBy { if (keys[it.id] == null) 1 else 0 },
        onDismiss = { controller.dialog = null },
        onPick = { controller.beginRecordingKeybinding(it.id) },
        keepQuery = true,
        header = {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Tap a command to change its keys.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(start = 16.dp),
                )
                TextButton(onClick = controller::openKeybindingsJson) { Text("Edit as JSON") }
            }
        },
    ) { command ->
        val changed = controller.keybindingChanged(command.id)
        ListItem(
            headlineContent = { Text(command.title, maxLines = 1) },
            supportingContent = if (changed) {
                { Text("Changed by you", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
            } else {
                null
            },
            trailingContent = {
                Text(
                    keys[command.id]?.joinToString("  ·  ") ?: "—",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        )
    }
}

/**
 * Waits for a key press for [commandId] on a focused box (no text field, so the on-screen
 * keyboard and Gboard stay out of it): one stroke or a two-stroke chord, Enter to save.
 */
@Composable
private fun RecordKeybindingDialog(controller: WorkbenchController, commandId: String) {
    val title = EditorCommands.all.firstOrNull { it.id == commandId }?.title ?: commandId
    val current = controller.effectiveCommands.filter { it.id == commandId && it.keybinding.isNotEmpty() }.map { it.keybinding }
    val recorded = controller.recordedKeys.joinToString(" ") { it.label() }
    val conflicts = controller.recordedKeyConflicts(commandId)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = { controller.dialog = WorkbenchDialog.Keybindings },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Press the keys on your keyboard (up to two in a row, like Ctrl+K Ctrl+S), then Enter. Escape cancels.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .focusRequester(focus)
                        .onPreviewKeyEvent { controller.recordKey(it.nativeKeyEvent) }
                        .focusable(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        recorded.ifEmpty { "Waiting for keys…" },
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (recorded.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (conflicts.isNotEmpty()) {
                    Text(
                        "Also runs: ${conflicts.joinToString(", ")}. Your shortcut wins.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    "Now: ${current.joinToString("  ·  ").ifEmpty { "no shortcut" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (current.isNotEmpty()) OutlinedButton(onClick = { controller.removeKeybinding(commandId) }) { Text("Remove shortcut") }
                    if (controller.keybindingChanged(commandId)) OutlinedButton(onClick = { controller.resetKeybinding(commandId) }) { Text("Reset to default") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { controller.saveRecordedKeybinding(commandId) }, enabled = recorded.isNotEmpty()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = { controller.dialog = WorkbenchDialog.Keybindings }) { Text("Cancel") } },
    )
}
