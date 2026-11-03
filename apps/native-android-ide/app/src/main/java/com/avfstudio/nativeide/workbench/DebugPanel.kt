package com.avfstudio.nativeide.workbench

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.avfstudio.nativeide.debug.DebugConsoleLine
import com.avfstudio.nativeide.debug.DebugState
import com.avfstudio.nativeide.debug.DebugVariable

/** Debug panel: controls, call stack, variables and the debug console side by side. */
@Composable
fun DebugPanel(controller: WorkbenchController) {
    val state = controller.debugState
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            when (state) {
                DebugState.Idle -> {
                    TextButton(onClick = controller::startOrContinueDebugging) {
                        Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(" Start debugging  F5")
                    }
                    ConfigurationPicker(controller)
                }
                DebugState.Starting -> Text("Starting…", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(8.dp))
                DebugState.Running -> Tooltip("Pause (F6)") { IconButton(onClick = controller::pauseDebugging) { Icon(Icons.Outlined.Pause, "Pause (F6)") } }
                is DebugState.Stopped -> {
                    Tooltip("Continue (F5)") { IconButton(onClick = controller::startOrContinueDebugging) { Icon(Icons.Outlined.PlayArrow, "Continue (F5)") } }
                    TextButton(onClick = { controller.debugStep("next") }) { Text("Over F10") }
                    TextButton(onClick = { controller.debugStep("stepIn") }) { Text("Into F11") }
                    TextButton(onClick = { controller.debugStep("stepOut") }) { Text("Out ⇧F11") }
                }
            }
            if (state != DebugState.Idle) {
                Tooltip("Restart (Ctrl+Shift+F5)") { IconButton(onClick = controller::restartDebugging) { Icon(Icons.Outlined.RestartAlt, "Restart (Ctrl+Shift+F5)") } }
                Tooltip("Stop (Shift+F5)") { IconButton(onClick = controller::stopDebugging) { Icon(Icons.Outlined.Stop, "Stop (Shift+F5)", tint = MaterialTheme.colorScheme.error) } }
            }
            (state as? DebugState.Stopped)?.let {
                Text(
                    // debugpy describes the reason ("breakpoint"), js-debug the whole state ("Paused on breakpoint").
                    (it.description ?: it.reason).let { reason -> if (reason.startsWith("Paused")) reason else "Paused on $reason" },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(Modifier.weight(1f))
            if (controller.selectedDebugConfiguration.type !in com.avfstudio.nativeide.debug.NODE_DEBUG_TYPES) {
                TextButton(onClick = { controller.chooseInterpreter() }) {
                    Text(controller.settings.pythonInterpreter.ifEmpty { "Select interpreter" }.substringAfterLast("/bin/").let { "Python: $it" }, maxLines = 1)
                }
            }
        }
        HorizontalDivider()
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.weight(0.8f).fillMaxHeight()) {
                PaneTitle("Call stack")
                LazyColumn(Modifier.fillMaxSize()) {
                    items(controller.debugFrames) { frame ->
                        val selected = frame.id == controller.selectedFrameId
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                                .clickable { controller.selectFrame(frame) }
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text(frame.name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text(
                                "${frame.path?.substringAfterLast('/') ?: "?"}:${frame.line}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            VerticalDivider()
            Column(Modifier.weight(1.2f).fillMaxHeight()) {
                var adding by remember { mutableStateOf(false) }
                fun flatten(nodes: List<DebugVariable>): List<DebugVariable> =
                    nodes.flatMap { node -> listOf(node) + if (node.expanded) flatten(node.children) else emptyList() }
                LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PaneTitle("Watch")
                            Box(Modifier.weight(1f))
                            Tooltip("Add Expression") {
                                IconButton(onClick = { adding = true }, modifier = Modifier.size(28.dp)) {
                                    Icon(Icons.Outlined.Add, "Add Expression", modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                    if (adding) item { WatchInput(onDone = { expression -> controller.addWatch(expression); adding = false }) }
                    if (controller.watchResults.isEmpty() && !adding) {
                        item {
                            Text(
                                "Expressions to follow while paused (+, or Debug: Add to Watch on a selection)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                            )
                        }
                    }
                    items(flatten(controller.watchResults)) { variable ->
                        VariableRow(controller, variable, onRemove = if (variable.depth == 1) ({ controller.removeWatch(variable.name) }) else null)
                    }
                    item { PaneTitle("Variables") }
                    items(flatten(controller.debugVariables)) { variable -> VariableRow(controller, variable) }
                }
            }
            VerticalDivider()
            DebugConsole(controller, Modifier.weight(1.5f).fillMaxHeight())
        }
    }
}

@Composable
private fun PaneTitle(title: String) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** The Debug panel's configuration: launch.json's and the built-in ones, plus Add Configuration. */
@Composable
private fun ConfigurationPicker(controller: WorkbenchController) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text(controller.selectedDebugConfiguration.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 260.dp))
            Icon(Icons.Outlined.ArrowDropDown, contentDescription = "Choose a configuration")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            controller.debugConfigurations.forEach { configuration ->
                DropdownMenuItem(
                    text = { Text(configuration.name) },
                    onClick = {
                        open = false
                        controller.selectDebugConfiguration(configuration.name)
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(if (controller.launchConfigurations.isEmpty()) "Add Configuration… (create launch.json)" else "Open launch.json") },
                onClick = {
                    open = false
                    controller.openLaunchJson()
                },
            )
        }
    }
}

@Composable
private fun WatchInput(onDone: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        placeholder = { Text("Expression to watch") },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone(text) }),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp).focusRequester(focus),
    )
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun VariableRow(controller: WorkbenchController, variable: DebugVariable, onRemove: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            // A tap opens it; a long press or a double click sets its value (VS Code's Set Value).
            .combinedClickable(
                onClick = { controller.toggleVariable(variable) },
                onLongClick = { controller.editVariable(variable) }.takeIf { variable.parentReference > 0 },
                onDoubleClick = { controller.editVariable(variable) }.takeIf { variable.parentReference > 0 },
            )
            .padding(start = (6 + variable.depth * 12).dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (variable.reference > 0) {
            Icon(
                if (variable.expanded) Icons.Outlined.ExpandMore else Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
        } else {
            Box(Modifier.width(16.dp))
        }
        val isScope = variable.depth == 0
        Text(
            if (isScope) variable.name else "${variable.name}: ",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (isScope) FontWeight.SemiBold else null,
            color = if (isScope) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
            fontFamily = if (isScope) null else FontFamily.Monospace,
        )
        if (!isScope) {
            Text(
                variable.value,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = if (variable.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        onRemove?.let { remove ->
            Box(Modifier.weight(1f))
            Tooltip("Remove Expression") {
                IconButton(onClick = remove, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Outlined.Close, "Remove Expression", modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

@Composable
private fun DebugConsole(controller: WorkbenchController, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    LaunchedEffect(controller.debugConsole.size) {
        if (controller.debugConsole.isNotEmpty()) listState.scrollToItem(controller.debugConsole.lastIndex)
    }
    Column(modifier) {
        PaneTitle("Debug console")
        SelectionContainer(Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                items(controller.debugConsole) { line ->
                    Text(
                        line.text,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = when (line.kind) {
                            DebugConsoleLine.Kind.Error -> colors.error
                            DebugConsoleLine.Kind.Input -> colors.primary
                            DebugConsoleLine.Kind.Info -> colors.onSurfaceVariant
                            else -> colors.onSurface
                        },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }
        }
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            placeholder = { Text("Evaluate in the paused frame") },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = {
                controller.evaluateInDebugConsole(input)
                input = ""
            }),
            modifier = Modifier.fillMaxWidth().padding(6.dp),
        )
    }
}
