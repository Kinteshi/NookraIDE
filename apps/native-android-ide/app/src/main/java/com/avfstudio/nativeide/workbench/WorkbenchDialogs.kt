package com.avfstudio.nativeide.workbench

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.background
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.material3.Button
import androidx.compose.ui.graphics.vector.ImageVector

@Composable
fun WorkbenchDialogHost(controller: WorkbenchController) {
    when (val dialog = controller.dialog) {
        null -> Unit
        WorkbenchDialog.Connect -> ConnectDialog(controller)
        WorkbenchDialog.Commands -> PaletteDialog(
            placeholder = "Type a command",
            query = controller.commandFilter,
            onQuery = { controller.commandFilter = it },
            items = EditorCommands.all.distinctBy { it.id }.filter { fuzzyMatch(controller.commandFilter, it.title) || it.id.contains(controller.commandFilter, true) },
            onDismiss = { controller.dialog = null },
            onPick = { command ->
                controller.dialog = null
                controller.runCommand(command.id)
            },
        ) { command ->
            PaletteRow(command.title, command.keybinding.takeIf(String::isNotEmpty))
        }
        WorkbenchDialog.QuickOpen -> {
            LaunchedEffect(Unit) { controller.refreshQuickOpenIndex() }
            val root = controller.workspaceRoot.orEmpty().trimEnd('/')
            PaletteDialog(
                placeholder = when {
                    controller.quickOpenIndexing -> "Indexing workspace…"
                    controller.quickOpenAttachesToChat -> "Add a file to Copilot chat"
                    else -> "Search files by name"
                },
                query = controller.quickOpenFilter,
                onQuery = { controller.quickOpenFilter = it },
                items = controller.quickOpenIndex
                    .filter { fuzzyMatch(controller.quickOpenFilter, it.path.removePrefix(root)) }
                    .sortedBy { if (it.name.startsWith(controller.quickOpenFilter, true)) 0 else 1 }
                    .take(200),
                onDismiss = {
                    controller.dialog = null
                    controller.quickOpenAttachesToChat = false
                },
                onPick = { entry ->
                    controller.dialog = null
                    if (controller.quickOpenAttachesToChat) controller.attachFileToChat(entry.path) else controller.openPath(entry.path)
                    controller.quickOpenAttachesToChat = false
                },
            ) { entry ->
                PaletteRow(entry.name, entry.path.removePrefix(root).removePrefix("/").substringBeforeLast('/', ""))
            }
        }
        WorkbenchDialog.Find -> AlertDialog(
            onDismissRequest = { controller.dialog = null },
            title = { Text("Find and replace") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val focus = remember { FocusRequester() }
                    LaunchedEffect(Unit) { focus.requestFocus() }
                    OutlinedTextField(
                        value = controller.findQuery,
                        onValueChange = { controller.findQuery = it },
                        label = { Text("Find") },
                        singleLine = true,
                        modifier = Modifier.focusRequester(focus),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { controller.findInEditor(true) }),
                    )
                    OutlinedTextField(
                        value = controller.replaceQuery,
                        onValueChange = { controller.replaceQuery = it },
                        label = { Text("Replace") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { controller.findInEditor(true) }) { Text("Find next") } },
            dismissButton = {
                Row {
                    TextButton(onClick = controller::replaceAll) { Text("Replace all") }
                    TextButton(onClick = { controller.dialog = null }) { Text("Close") }
                }
            },
        )
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
        is WorkbenchDialog.PathPrompt -> PathPromptDialog(controller, dialog)
        is WorkbenchDialog.GuestSetup -> GuestSetupDialog(controller, dialog)
        is WorkbenchDialog.GitPrompt -> GitPromptDialog(controller, dialog)
        is WorkbenchDialog.GitDiff -> AlertDialog(
            onDismissRequest = { controller.dialog = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
            modifier = Modifier.padding(24.dp),
            title = { Text(dialog.title) },
            text = { DiffText(dialog.patch) },
            confirmButton = { TextButton(onClick = { controller.dialog = null }) { Text("Close") } },
        )
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
            title = { Text(if (dialog.isDirectory) "Delete folder?" else "Delete file?") },
            text = { Text("${dialog.path.substringAfterLast('/')} will be moved to the guest's trash.") },
            confirmButton = { TextButton(onClick = { controller.deleteEntry(dialog.path) }) { Text("Delete") } },
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
        WorkbenchDialog.Keybindings -> PaletteDialog(
            placeholder = "Search keyboard shortcuts",
            query = controller.commandFilter,
            onQuery = { controller.commandFilter = it },
            items = EditorCommands.all.distinctBy { it.id }.filter { it.keybinding.isNotEmpty() }
                .filter { fuzzyMatch(controller.commandFilter, it.title) || it.keybinding.contains(controller.commandFilter, true) },
            onDismiss = { controller.dialog = null },
            onPick = { command ->
                controller.dialog = null
                controller.runCommand(command.id)
            },
        ) { command -> PaletteRow(command.title, command.keybinding) }
        is WorkbenchDialog.Completion -> PaletteDialog(
            placeholder = "Filter completions",
            query = controller.commandFilter,
            onQuery = { controller.commandFilter = it },
            items = dialog.choices.filter { fuzzyMatch(controller.commandFilter, it.label) },
            onDismiss = { controller.dialog = null },
            onPick = controller::applyCompletion,
        ) { choice -> PaletteRow(choice.label, choice.detail) }
        is WorkbenchDialog.CodeActions -> PaletteDialog(
            placeholder = "Quick fixes and refactorings",
            query = controller.commandFilter,
            onQuery = { controller.commandFilter = it },
            items = dialog.choices.filter { fuzzyMatch(controller.commandFilter, it.title) },
            onDismiss = { controller.dialog = null },
            onPick = controller::applyCodeAction,
        ) { choice -> PaletteRow(choice.title, null) }
        is WorkbenchDialog.Hover -> AlertDialog(
            onDismissRequest = { controller.dialog = null },
            text = {
                SelectionContainer {
                    Text(
                        dialog.text,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { controller.dialog = null }) { Text("Close") } },
        )
        is WorkbenchDialog.Conflict -> AlertDialog(
            onDismissRequest = { controller.dialog = null },
            title = { Text("File changed on the guest") },
            text = { Text("Your local draft of ${dialog.document.path.substringAfterLast('/')} is kept. Load the guest copy, or save your draft over it.") },
            confirmButton = { TextButton(onClick = { controller.keepLocalAfterConflict(dialog.document) }) { Text("Keep my draft") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { controller.loadRemoteAfterConflict(dialog.document) }) { Text("Load guest copy") }
                    TextButton(onClick = { controller.dialog = null }) { Text("Later") }
                }
            },
        )
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
                            is com.avfstudio.nativeide.notebook.KernelSpec -> choice.displayName
                            is com.avfstudio.nativeide.notebook.InstallableKernel -> choice.label
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
                        is com.avfstudio.nativeide.notebook.KernelSpec -> session?.let { controller.startKernel(it, choice) }
                        is com.avfstudio.nativeide.notebook.InstallableKernel -> session?.let { controller.installKernelPackage(it, choice) }
                        is GuestDependency -> session?.let { notebook -> controller.offerInstall(choice) { controller.chooseKernel(notebook) } }
                    }
                },
            ) { choice ->
                when (choice) {
                    is com.avfstudio.nativeide.notebook.KernelSpec -> PaletteRow(choice.displayName, choice.name)
                    is com.avfstudio.nativeide.notebook.InstallableKernel -> SetupRow(
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
    row: @Composable (T) -> Unit,
) {
    LaunchedEffect(Unit) { onQuery("") }
    BasicAlertDialog(onDismissRequest = onDismiss, modifier = Modifier.padding(horizontal = 24.dp)) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(16.dp)) {
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { focus.requestFocus() }
                OutlinedTextField(
                    value = query,
                    onValueChange = onQuery,
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
                                    items.firstOrNull()?.let(onPick)
                                    true
                                }
                                event.key == Key.Escape -> {
                                    onDismiss()
                                    true
                                }
                                else -> false
                            }
                        },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { items.firstOrNull()?.let(onPick) }),
                )
                LazyColumn(Modifier.heightIn(max = 440.dp).padding(top = 8.dp)) {
                    items(items) { item ->
                        Row(Modifier.fillMaxWidth().clickable { onPick(item) }) { row(item) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PaletteRow(title: String, detail: String?) {
    ListItem(
        headlineContent = { Text(title, maxLines = 1) },
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
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
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

@Composable
private fun ConnectDialog(controller: WorkbenchController) {
    AlertDialog(
        onDismissRequest = { controller.dialog = null },
        title = { Text("Connect to guest") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Profiles are saved on this device; tokens are encrypted with the Android Keystore.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (controller.profiles.isNotEmpty()) {
                    LazyColumn(Modifier.heightIn(max = 160.dp)) {
                        items(controller.profiles) { profile ->
                            ListItem(
                                headlineContent = { Text(profile.name) },
                                supportingContent = { Text(profile.endpoint) },
                                leadingContent = { RadioButton(selected = profile.id == controller.profileId, onClick = { controller.selectProfile(profile) }) },
                                trailingContent = {
                                    IconButton(onClick = { controller.deleteProfile(profile.id) }) {
                                        Icon(Icons.Outlined.Delete, contentDescription = "Delete profile")
                                    }
                                },
                                modifier = Modifier.clickable { controller.selectProfile(profile) },
                            )
                        }
                    }
                    TextButton(onClick = controller::newProfile) { Text("New profile") }
                }
                OutlinedTextField(
                    value = controller.profileName,
                    onValueChange = { controller.profileName = it },
                    label = { Text("Profile name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = controller.endpoint,
                    onValueChange = { controller.endpoint = it },
                    label = { Text("WebSocket URL") },
                    placeholder = { Text("ws://127.0.0.1:19870/ws") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = controller.token,
                    onValueChange = { controller.token = it },
                    label = { Text("Session token") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
        },
        confirmButton = { TextButton(onClick = controller::requestGuestConnection, enabled = !controller.busy) { Text(if (controller.busy) "Connecting…" else "Connect") } },
        dismissButton = {
            Row {
                TextButton(onClick = { controller.beginGuestSetup() }) { Text("Set up or pair…") }
                TextButton(onClick = { controller.dialog = null }) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun GuestSetupDialog(controller: WorkbenchController, setup: WorkbenchDialog.GuestSetup) {
    var code by remember { mutableStateOf("") }
    var connectionString by remember { mutableStateOf("") }
    val colors = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = { controller.dialog = null },
        title = { Text("Set up the Linux guest") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                setup.reason?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.error) }
                Text("1. Run this in Linux Terminal. It installs the AVF Studio daemon and its tools, then prints a pairing code.", style = MaterialTheme.typography.bodyMedium)
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
                    "If Android asks to open port 19870 (avf-native), accept it. Already installed? Run " +
                        "`avf-native-daemon pair` in Linux Terminal for a new code.",
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
                HorizontalDivider()
                Text("Or pair with a computer (Linux, or Windows through WSL)", style = MaterialTheme.typography.titleSmall)
                Text(
                    "On the computer, run scripts/install-native-daemon.sh --remote (on Windows, " +
                        "install-host-daemon-wsl.ps1); it prints a connection string starting with avf://. Paste it here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = connectionString,
                    onValueChange = { connectionString = it.trim() },
                    label = { Text("Connection string") },
                    placeholder = { Text("avf://192.168.1.10:19870?fp=…&code=…") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { controller.pairWithConnectionString(connectionString) }),
                    modifier = Modifier.fillMaxWidth(),
                )
                FilledTonalButton(
                    onClick = { controller.pairWithConnectionString(connectionString) },
                    enabled = !controller.busy && connectionString.startsWith("avf://"),
                ) { Text("Pair with computer") }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { controller.pairAndConnect(code) },
                enabled = !controller.busy && code.count(Char::isLetterOrDigit) == 8,
            ) { Text(if (controller.busy) "Pairing…" else "Pair and connect") }
        },
        dismissButton = { TextButton(onClick = { controller.dialog = null }) { Text("Close") } },
    )
}

/** Subsequence match, like VS Code's quick pick: "wbc" matches "WorkbenchController". */
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
    }
    var value by remember(prompt.action) {
        // Select the name but not its extension, like VS Code, so typing replaces just the name.
        val start = prompt.initial.lastIndexOf('/') + 1
        val dot = prompt.initial.lastIndexOf('.').takeIf { it > start } ?: prompt.initial.length
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
                parent?.let { Text("In $it", style = MaterialTheme.typography.bodySmall) }
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
@Composable
private fun DiffText(patch: String) {
    val colors = MaterialTheme.colorScheme
    val lines = remember(patch) { patch.lines().dropWhile { !it.startsWith("@@") }.ifEmpty { patch.lines() } }
    LazyColumn(Modifier.heightIn(max = 560.dp)) {
        items(lines) { line ->
            val (background, foreground) = when {
                line.startsWith("@@") -> colors.secondaryContainer to colors.onSecondaryContainer
                line.startsWith("+") -> androidx.compose.ui.graphics.Color(0x3348B04F) to colors.onSurface
                line.startsWith("-") -> colors.errorContainer.copy(alpha = 0.5f) to colors.onSurface
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
