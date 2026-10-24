package com.avfstudio.nativeide.workbench

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Stop
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
                DebugState.Idle -> TextButton(onClick = controller::startOrContinueDebugging) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(" Start debugging  F5")
                }
                DebugState.Starting -> Text("Starting…", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(8.dp))
                DebugState.Running -> IconButton(onClick = controller::pauseDebugging) { Icon(Icons.Outlined.Pause, "Pause (F6)") }
                is DebugState.Stopped -> {
                    IconButton(onClick = controller::startOrContinueDebugging) { Icon(Icons.Outlined.PlayArrow, "Continue (F5)") }
                    TextButton(onClick = { controller.debugStep("next") }) { Text("Over F10") }
                    TextButton(onClick = { controller.debugStep("stepIn") }) { Text("Into F11") }
                    TextButton(onClick = { controller.debugStep("stepOut") }) { Text("Out ⇧F11") }
                }
            }
            if (state != DebugState.Idle) {
                IconButton(onClick = controller::restartDebugging) { Icon(Icons.Outlined.RestartAlt, "Restart (Ctrl+Shift+F5)") }
                IconButton(onClick = controller::stopDebugging) { Icon(Icons.Outlined.Stop, "Stop (Shift+F5)", tint = MaterialTheme.colorScheme.error) }
            }
            (state as? DebugState.Stopped)?.let {
                Text(
                    "Paused on ${it.description ?: it.reason}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(Modifier.weight(1f))
            TextButton(onClick = { controller.chooseInterpreter() }) {
                Text(controller.settings.pythonInterpreter.ifEmpty { "Select interpreter" }.substringAfterLast("/bin/").let { "Python: $it" }, maxLines = 1)
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
                PaneTitle("Variables")
                LazyColumn(Modifier.fillMaxSize()) {
                    fun flatten(nodes: List<DebugVariable>): List<DebugVariable> =
                        nodes.flatMap { node -> listOf(node) + if (node.expanded) flatten(node.children) else emptyList() }
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

@Composable
private fun VariableRow(controller: WorkbenchController, variable: DebugVariable) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = variable.reference > 0) { controller.toggleVariable(variable) }
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
            Text(variable.value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
