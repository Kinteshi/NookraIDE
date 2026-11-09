package io.github.kinteshi.quirecode.workbench

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.snapshotFlow
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import io.github.kinteshi.quirecode.ai.ChatAgentKind
import io.github.kinteshi.quirecode.document.isGuestPath
import io.github.kinteshi.quirecode.ai.ChatReference
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

/** Far past any reply's height: the list stops at its end. */
private const val CHAT_LIST_END = 10_000_000

internal enum class ChatToolState { Running, Done, Failed, Other }

/** An ACP tool call's status (pending, in_progress, completed, failed). */
internal fun chatToolState(status: String?): ChatToolState = when (status?.lowercase()) {
    "pending", "in_progress" -> ChatToolState.Running
    "completed" -> ChatToolState.Done
    "failed" -> ChatToolState.Failed
    else -> ChatToolState.Other
}

/** A thought as agents send it, a bold title and a paragraph: plain, without its marks or an empty end. */
internal fun chatThoughtText(text: String): String = text.replace("**", "").trim().trimEnd('…').trim()

@Composable
fun CopilotChatPane(controller: WorkbenchController, modifier: Modifier = Modifier, onClose: (() -> Unit)? = null) {
    val draft = controller.chatDraft
    val entries = controller.chatEntries
    val listState = rememberLazyListState()
    val colors = MaterialTheme.colorScheme
    // The list stays at the newest text until scrolled up, and comes back when scrolled to the end.
    var following by remember { mutableStateOf(true) }
    val userScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0f) following = false
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.canScrollForward to following }.collect { (more, follow) ->
            if (!more) following = true
            // Directly, not animated: a reply grows many times a second, and each animation would restart the last.
            else if (follow && controller.chatEntries.isNotEmpty()) listState.scrollToItem(controller.chatEntries.lastIndex, CHAT_LIST_END)
        }
    }
    fun send() {
        val text = draft.trim()
        if (text.isNotEmpty() && !controller.chatBusy) {
            controller.sendChat(text)
            controller.chatDraft = ""
            following = true
        }
    }
    Column(modifier.fillMaxSize().background(colors.surfaceContainerLow)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = colors.primary, modifier = Modifier.size(20.dp))
            if (controller.showChatHistory) {
                Text("Past chats", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 8.dp).weight(1f))
            } else {
                Box(Modifier.weight(1f)) { ChatAgentPicker(controller) }
            }
            // Left of the others, so they don't move when the agent starts or stops.
            if (controller.chatAgentRunning) {
                Tooltip("Stop the agent") { IconButton(onClick = controller::endChatSession) { Icon(Icons.Outlined.PowerSettingsNew, contentDescription = "Stop the agent") } }
            }
            Tooltip("New chat") { IconButton(onClick = controller::newChat) { Icon(Icons.Outlined.Add, contentDescription = "New chat") } }
            Tooltip("Past chats") { IconButton(onClick = { if (controller.showChatHistory) controller.showChatHistory = false else controller.openChatHistory() }) {
                Icon(Icons.Outlined.History, contentDescription = "Past chats", tint = if (controller.showChatHistory) colors.primary else colors.onSurfaceVariant)
            } }
            onClose?.let { Tooltip("Close chat") { IconButton(onClick = it) { Icon(Icons.Outlined.Close, contentDescription = "Close chat") } } }
        }
        if (controller.chatAuthMethods.isNotEmpty() || controller.chatSignInNeeded) {
            val kind = controller.chatAgentKind
            Card(
                colors = CardDefaults.cardColors(containerColor = colors.secondaryContainer),
                modifier = Modifier.fillMaxWidth().padding(12.dp),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Sign in to ${kind.label}", style = MaterialTheme.typography.titleSmall)
                    Text(
                        when (kind) {
                            ChatAgentKind.Copilot -> "Chat signs in through the GitHub CLI, which Git then uses too. This runs `gh auth login` in a terminal and shows a one-time code here."
                            ChatAgentKind.ClaudeCode -> "Uses your Claude subscription. This runs Claude Code's sign-in in a terminal; open the page it gives here and paste the code back."
                            ChatAgentKind.Gemini -> "Uses your Google account. This runs Gemini CLI's sign-in in a terminal; open the page it gives here and paste the code back."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    FilledTonalButton(onClick = controller::signInChatAgent) { Text("Sign in") }
                }
            }
        }
        if (controller.showChatHistory) {
            ChatHistoryList(controller, Modifier.weight(1f).fillMaxWidth())
            return@Column
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (entries.isEmpty()) {
                EmptyState(
                    Icons.Outlined.AutoAwesome,
                    "Ask ${controller.chatAgentKind.label} about this workspace",
                    "The agent runs in the guest and asks before it edits files or runs commands.",
                )
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().nestedScroll(userScroll).padding(horizontal = 12.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(entries, contentType = { it::class }) { entry -> ChatEntryView(entry, controller) }
            }
            if (!following && listState.canScrollForward) {
                Box(Modifier.align(Alignment.BottomEnd).padding(12.dp)) {
                    Tooltip("Go to the newest message") {
                        SmallFloatingActionButton(onClick = { following = true }, containerColor = colors.secondaryContainer) {
                            Icon(Icons.Outlined.ArrowDownward, contentDescription = "Go to the newest message")
                        }
                    }
                }
            }
        }
        // Always there, so the controls below don't move when a reply starts or ends.
        Box(Modifier.fillMaxWidth().height(2.dp)) {
            if (controller.chatBusy) LinearProgressIndicator(Modifier.fillMaxSize())
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
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 12.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            var attachMenu by remember { mutableStateOf(false) }
            Box {
                Tooltip("Add context") { IconButton(onClick = { attachMenu = true }) { Icon(Icons.Outlined.AttachFile, contentDescription = "Add context") } }
                DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Active file") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Outlined.InsertDriveFile, contentDescription = null) },
                        enabled = controller.activePath?.let(::isGuestPath) == true,
                        onClick = { attachMenu = false; controller.attachFileToChat() },
                    )
                    DropdownMenuItem(
                        text = { Text(if (controller.activeNotebook != null) "Current cell" else "Selection or current line") },
                        leadingIcon = { Icon(Icons.Outlined.ShortText, contentDescription = null) },
                        enabled = controller.activePath?.let(::isGuestPath) == true,
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
                onValueChange = { controller.chatDraft = it },
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
                placeholder = { Text("Ask ${controller.chatAgentKind.label}") },
                shape = RoundedCornerShape(20.dp),
            )
            if (controller.chatBusy) {
                Tooltip("Stop") { FilledIconButton(onClick = controller::cancelChat) { Icon(Icons.Outlined.Stop, contentDescription = "Stop") } }
            } else {
                Tooltip("Send") { FilledIconButton(onClick = ::send, enabled = draft.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "Send")
                } }
            }
        }
    }
}

@Composable
private fun ChatEntryView(entry: ChatEntry, controller: WorkbenchController) {
    val colors = MaterialTheme.colorScheme
    when (entry) {
        is ChatEntry.User -> Column(Modifier.fillMaxWidth().padding(start = 32.dp), horizontalAlignment = Alignment.End) {
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
        // Flat on the pane, as wide as it is: the room goes to the text, not to a bubble's margins.
        is ChatEntry.Agent -> ChatMarkdown(controller, entry.text, Modifier.padding(horizontal = 2.dp))
        is ChatEntry.Thought -> ChatThought(entry)
        is ChatEntry.Tool -> ChatTool(entry)
        is ChatEntry.Plan -> Card(colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer)) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.Checklist, contentDescription = null, tint = colors.primary, modifier = Modifier.size(16.dp))
                    Text("Plan", style = MaterialTheme.typography.labelLarge)
                }
                entry.entries.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            }
        }
        is ChatEntry.Notice -> Row(
            Modifier.padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Outlined.Info, contentDescription = null, tint = colors.tertiary, modifier = Modifier.padding(top = 1.dp).size(16.dp))
            SelectionContainer { Text(entry.text, style = MaterialTheme.typography.bodySmall, color = colors.tertiary) }
        }
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
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { controller.openDeviceLogin(entry) }) { Text("Copy code and open GitHub") }
                        OutlinedButton(onClick = controller::cancelChatSignIn) { Text("Cancel") }
                    }
                }
            }
        }
        is ChatEntry.SignInLink -> SignInLinkCard(entry, controller)
        // Answered, it's a line of the history; only a waiting question stands out.
        is ChatEntry.Permission -> if (entry.answer != null) {
            Row(
                Modifier.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Outlined.Shield, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(14.dp))
                Text(
                    "${entry.answer}: ${entry.title}",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            Card(colors = CardDefaults.cardColors(containerColor = colors.tertiaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.Shield, contentDescription = null, tint = colors.onTertiaryContainer, modifier = Modifier.padding(top = 1.dp).size(18.dp))
                        Text(entry.title, style = MaterialTheme.typography.titleSmall, color = colors.onTertiaryContainer)
                    }
                    // A narrow chat wraps them rather than cutting the last one off.
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        entry.options.forEach { option ->
                            if (option.kind.startsWith("allow")) {
                                FilledTonalButton(onClick = { controller.answerPermission(entry, option.id) }) { Text(option.name) }
                            } else {
                                OutlinedButton(
                                    onClick = { controller.answerPermission(entry, option.id) },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.onTertiaryContainer),
                                    border = BorderStroke(1.dp, colors.onTertiaryContainer.copy(alpha = 0.6f)),
                                ) { Text(option.name) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The chat's title, which picks the agent: Copilot, Claude Code or Gemini. */
@Composable
private fun ChatAgentPicker(controller: WorkbenchController) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.clip(RoundedCornerShape(8.dp)).clickable { open = true }.padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(controller.chatAgentKind.label, style = MaterialTheme.typography.titleSmall)
        Icon(Icons.Outlined.ArrowDropDown, contentDescription = "Choose the chat agent", modifier = Modifier.size(20.dp))
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        ChatAgentKind.entries.forEach { kind ->
            DropdownMenuItem(
                text = { Text(kind.label) },
                leadingIcon = {
                    if (kind == controller.chatAgentKind) Icon(Icons.Outlined.Check, contentDescription = "Selected")
                    else Spacer(Modifier.size(24.dp))
                },
                enabled = !controller.chatBusy,
                onClick = {
                    open = false
                    controller.selectChatAgent(kind)
                },
            )
        }
    }
}

/** A Claude Code or Gemini sign-in: the page to open, then the code it shows, typed into the waiting login. */
@Composable
private fun SignInLinkCard(entry: ChatEntry.SignInLink, controller: WorkbenchController) {
    val colors = MaterialTheme.colorScheme
    var code by remember(entry.url) { mutableStateOf("") }
    Card(colors = CardDefaults.cardColors(containerColor = colors.secondaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                when {
                    entry.done -> "Signed in to ${entry.agent}"
                    entry.codeSent && entry.quitAfterCode -> "When the terminal shows Gemini's prompt, you're signed in"
                    entry.codeSent -> "Checking the code…"
                    else -> "Sign in to ${entry.agent}"
                },
                style = MaterialTheme.typography.titleSmall,
                color = colors.onSecondaryContainer,
            )
            if (!entry.done && !entry.codeSent) {
                Text(
                    "Open the page, sign in, and paste the code it shows here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSecondaryContainer,
                )
                FilledTonalButton(onClick = { controller.openLink(entry.url) }) { Text("Open sign-in page") }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it },
                        singleLine = true,
                        placeholder = { Text("Code") },
                        modifier = Modifier.weight(1f),
                    )
                    FilledTonalButton(onClick = { controller.sendSignInCode(entry, code) }, enabled = code.isNotBlank()) { Text("Send") }
                }
            }
            if (!entry.done) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (entry.codeSent && entry.quitAfterCode) {
                        FilledTonalButton(onClick = { controller.closeSignInAgent(entry) }) { Text("Done") }
                    }
                    OutlinedButton(onClick = controller::cancelChatSignIn) { Text("Cancel") }
                }
            }
        }
    }
}

/** What the agent is thinking, muted and short (a tap shows it all), so it doesn't push the reply around. */
@Composable
private fun ChatThought(entry: ChatEntry.Thought) {
    val colors = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    val text = remember(entry.text) { chatThoughtText(entry.text) }
    val summary = text.substringBefore('\n')
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { open = !open }.padding(horizontal = 2.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Outlined.Lightbulb, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.padding(top = 1.dp).size(16.dp))
        Text(
            if (open) text else summary,
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            maxLines = if (open) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (summary != text) {
            Icon(
                if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = if (open) "Show less" else "Show all",
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** A tool the agent ran: its state and title on one line (a tap shows a long title whole). */
@Composable
private fun ChatTool(entry: ChatEntry.Tool) {
    val colors = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    val state = chatToolState(entry.status)
    Surface(color = colors.surfaceContainer, shape = RoundedCornerShape(10.dp), onClick = { open = !open }, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (state) {
                ChatToolState.Running -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                ChatToolState.Done -> Icon(Icons.Outlined.Check, contentDescription = "Done", tint = colors.primary, modifier = Modifier.size(16.dp))
                ChatToolState.Failed -> Icon(Icons.Outlined.ErrorOutline, contentDescription = "Failed", tint = colors.error, modifier = Modifier.size(16.dp))
                ChatToolState.Other -> Icon(Icons.Outlined.Build, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(16.dp))
            }
            Text(
                entry.title,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurface,
                fontFamily = FontFamily.Monospace,
                maxLines = if (open) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Ellipsis,
            )
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
    var confirmAllowAll by remember { mutableStateOf<Pair<io.github.kinteshi.quirecode.ai.AcpConfigOption, String>?>(null) }
    // One line that scrolls sideways: wrapped, the box below it jumped as the choices changed.
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (options.isEmpty()) {
            listOf("model" to "Model", "mode" to "Mode").forEach { (id, name) ->
                AssistChip(
                    onClick = controller::loadChatOptions,
                    label = { ChatChipLabel(name, controller.rememberedChatChoice(id) ?: "Default") },
                    leadingIcon = { Icon(chatOptionIcon(id), contentDescription = null, modifier = Modifier.size(16.dp)) },
                    trailingIcon = {
                        if (controller.chatOptionsLoading) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Outlined.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
                    },
                )
            }
        }
        // The same order before and after a session lists them, so the chips don't change places.
        @Composable
        fun optionChip(option: io.github.kinteshi.quirecode.ai.AcpConfigOption) {
            var open by remember(option.id) { mutableStateOf(false) }
            Box {
                AssistChip(
                    onClick = { open = true },
                    label = { ChatChipLabel(option.name, option.choices.firstOrNull { it.value == option.current }?.name ?: option.current) },
                    leadingIcon = { Icon(chatOptionIcon(option.id), contentDescription = null, modifier = Modifier.size(16.dp)) },
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
        val (first, rest) = options.sortedBy { chatOptionRank(it.id) }.partition { chatOptionRank(it.id) < 2 }
        first.forEach { optionChip(it) }
        if (controller.chatAgentKind == ChatAgentKind.Copilot) ReasoningEffortChip(controller)
        rest.forEach { optionChip(it) }
    }
    confirmAllowAll?.let { (option, value) ->
        AlertDialog(
            onDismissRequest = { confirmAllowAll = null },
            title = { Text("Let ${controller.chatAgentKind.label} act without asking?") },
            text = { Text("${controller.chatAgentKind.label} will run commands, edit files and fetch URLs in the guest without asking first, for this session.") },
            confirmButton = { TextButton(onClick = { confirmAllowAll = null; controller.setChatOption(option, value) }) { Text("Allow all") } },
            dismissButton = { TextButton(onClick = { confirmAllowAll = null }) { Text("Cancel") } },
        )
    }
}

/** An option chip's choice, with the option's name for tooltips and TalkBack; a long model name is cut short. */
@Composable
private fun ChatChipLabel(name: String, choice: String) {
    Tooltip(name) {
        Text(
            choice,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 170.dp).semantics { contentDescription = "$name: $choice" },
        )
    }
}

/** The chips' order: model, mode, then the rest. */
internal fun chatOptionRank(id: String): Int = when {
    "model" in id -> 0
    "mode" in id -> 1
    else -> 2
}

private fun chatOptionIcon(id: String) = when {
    "model" in id -> Icons.Outlined.Memory
    "mode" in id -> Icons.Outlined.Tune
    "effort" in id || "thought" in id || "reason" in id -> Icons.Outlined.Speed
    else -> Icons.Outlined.Settings
}

/** Copilot CLI's `--reasoning-effort`, which it reads at launch; changing it starts a new session. */
@Composable
private fun ReasoningEffortChip(controller: WorkbenchController) {
    var open by remember { mutableStateOf(false) }
    val current = controller.settings.chatReasoningEffort
    Box {
        AssistChip(
            onClick = { open = true },
            label = { ChatChipLabel("Effort", reasoningEffortLabel(current)) },
            leadingIcon = { Icon(chatOptionIcon("effort"), contentDescription = null, modifier = Modifier.size(16.dp)) },
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
        controller.chatSessions.isEmpty() -> EmptyState(Icons.Outlined.History, "No past chats yet", "Chats in this workspace show up here.", modifier)
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
                        Tooltip("More for ${row.title}") { IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "More for ${row.title}") } }
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
                    supportingText = { Text("Empty goes back to ${controller.chatAgentKind.label}'s title") },
                )
            },
            confirmButton = { TextButton(onClick = { controller.renameChatSession(row, name); renaming = null }) { Text("Rename") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
}
