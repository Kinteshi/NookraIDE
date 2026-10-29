package com.avfstudio.nativeide.workbench

import androidx.compose.foundation.background
import com.avfstudio.nativeide.ai.ChatReference
import androidx.compose.material3.InputChip
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ShortText
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@Composable
fun CopilotChatPane(controller: WorkbenchController, modifier: Modifier = Modifier, onClose: (() -> Unit)? = null) {
    var draft by remember { mutableStateOf("") }
    val entries = controller.chatEntries
    val listState = rememberLazyListState()
    val colors = MaterialTheme.colorScheme
    LaunchedEffect(entries.size, entries.lastOrNull()) {
        if (entries.isNotEmpty()) listState.animateScrollToItem(entries.lastIndex)
    }
    fun send() {
        val text = draft.trim()
        if (text.isNotEmpty() && !controller.chatBusy) {
            controller.sendChat(text)
            draft = ""
        }
    }
    Column(modifier.fillMaxSize().background(colors.surfaceContainerLow)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = colors.primary, modifier = Modifier.size(20.dp))
            Text(
                if (controller.showChatHistory) "Past chats" else "Copilot Chat",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 8.dp).weight(1f),
            )
            IconButton(onClick = controller::newChat) { Icon(Icons.Outlined.Add, contentDescription = "New chat") }
            IconButton(onClick = { if (controller.showChatHistory) controller.showChatHistory = false else controller.openChatHistory() }) {
                Icon(Icons.Outlined.History, contentDescription = "Past chats", tint = if (controller.showChatHistory) colors.primary else colors.onSurfaceVariant)
            }
            if (controller.chatAgentRunning) {
                IconButton(onClick = controller::endChatSession) { Icon(Icons.Outlined.Stop, contentDescription = "Stop the agent") }
            }
            onClose?.let { IconButton(onClick = it) { Icon(Icons.Outlined.Close, contentDescription = "Close chat") } }
        }
        if (controller.chatAuthMethods.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = colors.secondaryContainer),
                modifier = Modifier.fillMaxWidth().padding(12.dp),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Sign in to Copilot chat", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Chat signs in through the GitHub CLI, which Git then uses too. This runs `gh auth login` in a terminal and shows a one-time code here.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    FilledTonalButton(onClick = controller::signInGitHubInTerminal) { Text("Sign in") }
                }
            }
        }
        if (controller.showChatHistory) {
            ChatHistoryList(controller, Modifier.weight(1f).fillMaxWidth())
            return@Column
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (entries.isEmpty()) {
                item {
                    Text(
                        "Ask Copilot about this workspace. The agent runs in the guest and asks before it edits files or runs commands.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            }
            items(entries) { entry -> ChatEntryView(entry, controller) }
        }
        if (controller.chatAttachments.isNotEmpty()) {
            FlowRow(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                controller.chatAttachments.forEach { reference ->
                    InputChip(
                        selected = false,
                        onClick = { controller.removeChatAttachment(reference) },
                        label = { Text(reference.label, maxLines = 1) },
                        leadingIcon = { Icon(referenceIcon(reference), contentDescription = null, modifier = Modifier.size(16.dp)) },
                        trailingIcon = { Icon(Icons.Outlined.Close, contentDescription = "Remove ${reference.label}", modifier = Modifier.size(16.dp)) },
                    )
                }
            }
        }
        ChatOptionsRow(controller)
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            var attachMenu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { attachMenu = true }) { Icon(Icons.Outlined.AttachFile, contentDescription = "Add context") }
                DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Active file") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Outlined.InsertDriveFile, contentDescription = null) },
                        enabled = controller.activePath?.startsWith("scratch:") == false,
                        onClick = { attachMenu = false; controller.attachFileToChat() },
                    )
                    DropdownMenuItem(
                        text = { Text(if (controller.activeNotebook != null) "Current cell" else "Selection or current line") },
                        leadingIcon = { Icon(Icons.Outlined.ShortText, contentDescription = null) },
                        enabled = controller.activePath?.startsWith("scratch:") == false,
                        onClick = { attachMenu = false; controller.attachSelectionToChat() },
                    )
                    DropdownMenuItem(
                        text = { Text("Choose file…") },
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                        onClick = { attachMenu = false; controller.pickFileForChat() },
                    )
                }
            }
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(max = 180.dp)
                    .onFocusChanged { state ->
                        if (state.isFocused) controller.focusArea = FocusArea.Chat
                        else if (controller.focusArea == FocusArea.Chat) controller.focusArea = FocusArea.Other
                    }
                    .onPreviewKeyEvent { event ->
                        // Enter sends, Shift+Enter keeps typing on a new line.
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Enter && !event.isShiftPressed) {
                            send()
                            true
                        } else {
                            false
                        }
                    },
                placeholder = { Text("Ask Copilot") },
                shape = RoundedCornerShape(20.dp),
            )
            if (controller.chatBusy) {
                FilledIconButton(onClick = controller::cancelChat) { Icon(Icons.Outlined.Stop, contentDescription = "Stop") }
            } else {
                FilledIconButton(onClick = ::send, enabled = draft.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "Send")
                }
            }
        }
    }
}

@Composable
private fun ChatEntryView(entry: ChatEntry, controller: WorkbenchController) {
    val colors = MaterialTheme.colorScheme
    when (entry) {
        is ChatEntry.User -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            Surface(
                color = colors.primaryContainer,
                contentColor = colors.onPrimaryContainer,
                shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
                modifier = Modifier.widthIn(max = 520.dp),
            ) {
                SelectionContainer { Text(entry.text, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) }
            }
            if (entry.references.isNotEmpty()) {
                FlowRow(
                    Modifier.widthIn(max = 520.dp).padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                ) {
                    entry.references.forEach { reference ->
                        AssistChip(
                            onClick = { controller.openPath(reference.path) },
                            label = { Text(reference.label, maxLines = 1, style = MaterialTheme.typography.labelSmall) },
                            leadingIcon = { Icon(referenceIcon(reference), contentDescription = null, modifier = Modifier.size(14.dp)) },
                        )
                    }
                }
            }
        }
        is ChatEntry.Agent -> Surface(
            color = colors.surfaceContainerHigh,
            shape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            SelectionContainer { ChatMarkdown(entry.text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) }
        }
        is ChatEntry.Thought -> Text(
            entry.text,
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        is ChatEntry.Tool -> AssistChip(
            onClick = {},
            label = { Text(entry.title + (entry.status?.let { " · $it" }.orEmpty()), maxLines = 1) },
            leadingIcon = { Icon(Icons.Outlined.Build, contentDescription = null, modifier = Modifier.size(16.dp)) },
        )
        is ChatEntry.Plan -> Card(colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer)) {
            Column(Modifier.padding(12.dp)) {
                Text("Plan", style = MaterialTheme.typography.labelLarge)
                entry.entries.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            }
        }
        is ChatEntry.Notice -> Text(
            entry.text,
            style = MaterialTheme.typography.bodySmall,
            color = colors.tertiary,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        is ChatEntry.DeviceCode -> Card(colors = CardDefaults.cardColors(containerColor = colors.secondaryContainer)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (entry.done) "Signed in to Copilot chat" else "Enter this code on GitHub",
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.onSecondaryContainer,
                )
                if (!entry.done) {
                    SelectionContainer {
                        Text(
                            entry.code,
                            style = MaterialTheme.typography.headlineSmall,
                            fontFamily = FontFamily.Monospace,
                            color = colors.onSecondaryContainer,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { controller.openDeviceLogin(entry) }) { Text("Copy code and open GitHub") }
                        OutlinedButton(onClick = controller::cancelChatSignIn) { Text("Cancel") }
                    }
                }
            }
        }
        is ChatEntry.Permission -> Card(colors = CardDefaults.cardColors(containerColor = colors.tertiaryContainer)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(entry.title, style = MaterialTheme.typography.titleSmall, color = colors.onTertiaryContainer)
                if (entry.answer != null) {
                    Text(entry.answer, style = MaterialTheme.typography.bodySmall, color = colors.onTertiaryContainer)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        entry.options.forEach { option ->
                            if (option.kind.startsWith("allow")) {
                                FilledTonalButton(onClick = { controller.answerPermission(entry, option.id) }) { Text(option.name) }
                            } else {
                                OutlinedButton(onClick = { controller.answerPermission(entry, option.id) }) { Text(option.name) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Minimal Markdown: fenced code blocks in monospace, everything else as paragraphs. */
@Composable
private fun ChatMarkdown(text: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        text.split("```").forEachIndexed { index, part ->
            if (index % 2 == 1) {
                Surface(color = colors.surfaceContainerLowest, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Text(
                        part.substringAfter('\n', part).trimEnd(),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            } else if (part.isNotBlank()) {
                Text(part.trim(), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private fun referenceIcon(reference: ChatReference) =
    if (reference.isExcerpt) Icons.Outlined.ShortText else Icons.AutoMirrored.Outlined.InsertDriveFile

/**
 * The agent's session options (model, mode, tool approvals) as chips above the input. Before a
 * session exists they show the remembered choice; the first tap opens a session to list them.
 */
@Composable
private fun ChatOptionsRow(controller: WorkbenchController) {
    val options = controller.chatConfigOptions
    var confirmAllowAll by remember { mutableStateOf<Pair<com.avfstudio.nativeide.ai.AcpConfigOption, String>?>(null) }
    FlowRow(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (options.isEmpty()) {
            listOf("model" to "Model", "mode" to "Mode").forEach { (id, name) ->
                AssistChip(
                    onClick = controller::loadChatOptions,
                    label = { Text("$name: ${controller.rememberedChatChoice(id) ?: "Default"}", maxLines = 1) },
                    trailingIcon = {
                        if (controller.chatOptionsLoading) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Outlined.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
                    },
                )
            }
        }
        ReasoningEffortChip(controller)
        options.forEach { option ->
            var open by remember(option.id) { mutableStateOf(false) }
            Box {
                AssistChip(
                    onClick = { open = true },
                    label = { Text("${option.name}: ${option.choices.firstOrNull { it.value == option.current }?.name ?: option.current}", maxLines = 1) },
                    trailingIcon = { Icon(Icons.Outlined.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    option.choices.forEach { choice ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(choice.name)
                                    choice.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                }
                            },
                            // Every item keeps the check's space so names line up.
                            leadingIcon = {
                                if (choice.value == option.current) Icon(Icons.Outlined.Check, contentDescription = "Selected")
                                else Spacer(Modifier.size(24.dp))
                            },
                            onClick = {
                                open = false
                                if (option.category == "permissions" && choice.value == "on") {
                                    confirmAllowAll = option to choice.value
                                } else {
                                    controller.setChatOption(option, choice.value, choice.name)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
    confirmAllowAll?.let { (option, value) ->
        AlertDialog(
            onDismissRequest = { confirmAllowAll = null },
            title = { Text("Let Copilot act without asking?") },
            text = { Text("Copilot will run commands, edit files and fetch URLs in the guest without asking first, for this session.") },
            confirmButton = { TextButton(onClick = { confirmAllowAll = null; controller.setChatOption(option, value) }) { Text("Allow all") } },
            dismissButton = { TextButton(onClick = { confirmAllowAll = null }) { Text("Cancel") } },
        )
    }
}

/** Copilot CLI's `--reasoning-effort`, which it reads at launch; changing it starts a new session. */
@Composable
private fun ReasoningEffortChip(controller: WorkbenchController) {
    var open by remember { mutableStateOf(false) }
    val current = controller.settings.chatReasoningEffort
    Box {
        AssistChip(
            onClick = { open = true },
            label = { Text("Effort: ${reasoningEffortLabel(current)}", maxLines = 1) },
            trailingIcon = { Icon(Icons.Outlined.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp)) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            (listOf("") + WorkbenchSettings.REASONING_EFFORTS).forEach { effort ->
                DropdownMenuItem(
                    text = { Text(reasoningEffortLabel(effort)) },
                    leadingIcon = {
                        if (effort == current) Icon(Icons.Outlined.Check, contentDescription = "Selected")
                        else Spacer(Modifier.size(24.dp))
                    },
                    onClick = {
                        open = false
                        controller.setChatReasoningEffort(effort)
                    },
                )
            }
        }
    }
}

/** The agent's past chats in this workspace: tap to continue one; its menu renames or removes it. */
@Composable
private fun ChatHistoryList(controller: WorkbenchController, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    var renaming by remember { mutableStateOf<ChatSessionRow?>(null) }
    when {
        controller.chatSessionsLoading -> Box(modifier.padding(24.dp), contentAlignment = Alignment.TopCenter) {
            CircularProgressIndicator(Modifier.size(24.dp))
        }
        controller.chatSessions.isEmpty() -> Text(
            "No past chats in this workspace yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            modifier = modifier.padding(16.dp),
        )
        else -> LazyColumn(modifier.padding(horizontal = 6.dp)) {
            items(controller.chatSessions, key = { it.id }) { row ->
                var menu by remember { mutableStateOf(false) }
                Box {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(if (row.current) colors.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent, MaterialTheme.shapes.small)
                            .combinedClickable(onLongClick = { menu = true }) { controller.openChatSession(row) }
                            .padding(start = 10.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(row.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(chatSessionAge(row.updatedAt), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                        }
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "More for ${row.title}") }
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, contentDescription = null) },
                            onClick = { menu = false; renaming = row },
                        )
                        DropdownMenuItem(
                            text = { Text("Remove from list") },
                            leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                            onClick = { menu = false; controller.removeChatSession(row) },
                        )
                    }
                }
            }
        }
    }
    renaming?.let { row ->
        var name by remember(row.id) { mutableStateOf(row.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename chat") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    supportingText = { Text("Empty goes back to Copilot's title") },
                )
            },
            confirmButton = { TextButton(onClick = { controller.renameChatSession(row, name); renaming = null }) { Text("Rename") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
}
