package com.avfstudio.nativeide.workbench

import androidx.compose.foundation.background
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
            Text("Copilot Chat", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 8.dp).weight(1f))
            if (controller.chatAgent.isStarted) {
                IconButton(onClick = controller::endChatSession) { Icon(Icons.Outlined.Stop, contentDescription = "End session") }
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
                        "Chat uses the Copilot CLI, which keeps its own GitHub login. This runs `copilot login` in a terminal and shows a one-time code here.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    FilledTonalButton(onClick = controller::signInChatInTerminal) { Text("Sign in") }
                }
            }
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
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(max = 180.dp)
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
        is ChatEntry.User -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                color = colors.primaryContainer,
                contentColor = colors.onPrimaryContainer,
                shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
                modifier = Modifier.widthIn(max = 520.dp),
            ) {
                SelectionContainer { Text(entry.text, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) }
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
