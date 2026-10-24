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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

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
                placeholder = if (controller.quickOpenIndexing) "Indexing workspace…" else "Search files by name",
                query = controller.quickOpenFilter,
                onQuery = { controller.quickOpenFilter = it },
                items = controller.quickOpenIndex
                    .filter { fuzzyMatch(controller.quickOpenFilter, it.path.removePrefix(root)) }
                    .sortedBy { if (it.name.startsWith(controller.quickOpenFilter, true)) 0 else 1 }
                    .take(200),
                onDismiss = { controller.dialog = null },
                onPick = { entry ->
                    controller.dialog = null
                    controller.openPath(entry.path)
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
            val choices: List<Any> = dialog.specs + dialog.installable
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
                            else -> ""
                        },
                    )
                },
                onDismiss = {
                    controller.dialog = null
                    session?.notebook?.cells?.forEach { it.queued = false }
                },
                onPick = { choice ->
                    when (choice) {
                        is com.avfstudio.nativeide.notebook.KernelSpec -> session?.let { controller.startKernel(it, choice) }
                        is com.avfstudio.nativeide.notebook.InstallableKernel -> session?.let { controller.installKernelPackage(it, choice) }
                    }
                },
            ) { choice ->
                when (choice) {
                    is com.avfstudio.nativeide.notebook.KernelSpec -> PaletteRow(choice.displayName, choice.name)
                    is com.avfstudio.nativeide.notebook.InstallableKernel -> ListItem(
                        headlineContent = { Text("Install ipykernel in Python (${choice.label})") },
                        supportingContent = { Text(choice.python, fontFamily = FontFamily.Monospace, maxLines = 1) },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    )
                }
            }
        }
    }
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
        dismissButton = { TextButton(onClick = { controller.dialog = null }) { Text("Cancel") } },
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
