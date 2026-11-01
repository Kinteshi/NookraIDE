package com.avfstudio.nativeide.workbench

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.connectbot.terminal.Terminal
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Surface
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/** Bottom panel (Ctrl+J): terminals, problems and the output log, resizable by its top edge. */
@Composable
fun PanelArea(controller: WorkbenchController, modifier: Modifier = Modifier) {
    var height by remember { mutableFloatStateOf(320f) }
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth().height(height.dp).background(colors.surfaceContainerLow)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(10.dp)
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { delta -> height = (height - delta / density.density).coerceIn(140f, 900f) },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.width(36.dp).height(4.dp).background(colors.outlineVariant, RoundedCornerShape(2.dp)))
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PanelTab.entries.forEach { tab ->
                FilterChip(
                    selected = controller.panelTab == tab,
                    onClick = { controller.panelTab = tab },
                    label = {
                        val count = when (tab) {
                            PanelTab.Problems -> controller.diagnostics.size
                            PanelTab.Ports -> controller.visiblePorts.size
                            else -> 0
                        }
                        Text(if (count > 0) "${tab.title} $count" else tab.title)
                    },
                )
            }
            Spacer(Modifier.weight(1f))
            if (controller.panelTab == PanelTab.Terminal) {
                IconButton(onClick = { controller.newTerminal() }) { Icon(Icons.Outlined.Add, contentDescription = "New terminal") }
            }
            IconButton(onClick = { controller.panelVisible = false }) {
                Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "Hide panel")
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (controller.panelTab) {
                PanelTab.Terminal -> TerminalView(controller)
                PanelTab.Problems -> ProblemsList(controller)
                PanelTab.Output -> OutputLog(controller)
                PanelTab.Debug -> DebugPanel(controller)
                PanelTab.Ports -> PortsView(controller)
                PanelTab.Variables -> VariablesView(controller)
            }
        }
    }
}

@Composable
fun TerminalView(controller: WorkbenchController, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val dark = LocalDarkTheme.current
    Column(modifier.fillMaxSize()) {
        if (controller.terminals.size > 1 || controller.terminals.any { it.exited }) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                controller.terminals.forEach { session ->
                    InputChip(
                        selected = session.id == controller.activeTerminalId,
                        onClick = { controller.activeTerminalId = session.id },
                        label = { Text(if (session.exited) "${session.title} (exited)" else session.title) },
                        leadingIcon = { Icon(Icons.Outlined.Terminal, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        trailingIcon = {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = "Close terminal",
                                modifier = Modifier.size(16.dp).clickable { controller.closeTerminal(session.id) },
                            )
                        },
                    )
                }
            }
        }
        val session = controller.activeTerminal
        if (session == null) {
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("No terminal is open", color = colors.onSurfaceVariant)
                TextButton(onClick = { controller.newTerminal() }) { Text("New terminal  Ctrl+Shift+`") }
            }
            return
        }
        val background = if (dark) colors.surfaceContainerLowest else colors.surface
        LaunchedEffect(session.id, dark, background) {
            session.emulator.applyColorScheme(terminalPalette(dark), colors.onSurface.toArgb(), background.toArgb())
        }
        // An ended terminal takes no keys, but tapping it must still take the keyboard away from
        // the editor: otherwise typing lands in the last focused cell or file.
        val endedFocus = remember(session.id) { FocusRequester() }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .focusRequester(endedFocus)
                .focusable(enabled = session.exited)
                .pointerInput(session.id, session.exited) {
                    if (!session.exited) return@pointerInput
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        endedFocus.requestFocus()
                    }
                },
        ) {
            key(session.id) {
                Terminal(
                    terminalEmulator = session.emulator,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 6.dp)
                        .onFocusChanged { state ->
                            if (state.hasFocus) controller.focusArea = FocusArea.Terminal
                            else if (controller.focusArea == FocusArea.Terminal) controller.focusArea = FocusArea.Other
                        },
                    initialFontSize = controller.settings.terminalFontSize.sp,
                    backgroundColor = background,
                    foregroundColor = colors.onSurface,
                    selectionBackgroundColor = colors.primary.copy(alpha = 0.35f),
                    keyboardEnabled = !session.exited,
                    onHyperlinkClick = controller::openLink,
                    onInterceptKey = { event ->
                        event.type == KeyEventType.KeyDown && controller.handleKeyDown(event.nativeKeyEvent)
                    },
                )
            }
        }
        if (session.exited) {
            Surface(
                color = colors.secondaryContainer,
                contentColor = colors.onSecondaryContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Row(Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (session.exitCode != null) "This terminal ended (exit code ${session.exitCode})" else "This terminal ended",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                    )
                    FilledTonalButton(onClick = { controller.newTerminal() }) { Text("New terminal") }
                }
            }
        }
    }
}

/**
 * Ports (VS Code's Ports view): what listens in the guest, the program holding it, and buttons
 * to open it in the browser or copy its address. Linux Terminal forwards a guest port to this
 * tablet's localhost once you allow it in its notification.
 */
@Composable
fun PortsView(controller: WorkbenchController) {
    val colors = MaterialTheme.colorScheme
    val ports = controller.listedPorts
    val shown = if (controller.showInternalPorts) ports else ports.filterNot(controller::isInternalPort)
    val hidden = ports.size - ports.count { !controller.isInternalPort(it) }
    Column(Modifier.fillMaxSize()) {
        if (!controller.connected) {
            EmptyMessage("Connect to the guest to see its ports.")
            return@Column
        }
        if (shown.isEmpty()) {
            EmptyMessage("Nothing listens in the guest yet. Start a server (python3 -m http.server, npm run dev) and it shows up here.")
        }
        androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f, fill = shown.isNotEmpty())) {
            items(shown.size) { index ->
                val port = shown[index]
                val internal = controller.isInternalPort(port)
                Row(
                    Modifier.fillMaxWidth().clickable { controller.openPort(port) }.padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        port.port.toString(),
                        style = MaterialTheme.typography.titleSmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = if (internal) colors.onSurfaceVariant else colors.primary,
                        modifier = Modifier.width(64.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            port.command ?: "A system service (another user's process)",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = if (port.command != null) androidx.compose.ui.text.font.FontFamily.Monospace else null,
                            color = if (port.command != null) colors.onSurface else colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        Text(
                            "localhost:${port.port}${if (port.ipv6) " · IPv6" else ""}",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { controller.copyPortAddress(port) }) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy address", modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = { controller.openPort(port) }) {
                        Icon(Icons.Outlined.OpenInBrowser, contentDescription = "Open in browser", modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "The first time, Linux Terminal asks in a notification to open the port.",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (hidden > 0) {
                TextButton(onClick = { controller.showInternalPorts = !controller.showInternalPorts }) {
                    Text(if (controller.showInternalPorts) "Hide system and app ports" else "Show system and app ports ($hidden)")
                }
            }
        }
    }
}

/**
 * Variables (VS Code's Jupyter Variables): the names the notebook's kernel holds, with type,
 * size and a short value, refreshed after each run while this tab is open. DataFrames, Series
 * and arrays open in the data viewer; a tap on another row shows its whole value line.
 */
@Composable
fun VariablesView(controller: WorkbenchController) {
    val colors = MaterialTheme.colorScheme
    val session = controller.variablesSession
    LaunchedEffect(session, session?.kernelName) { controller.refreshVariables(session) }
    var filter by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var expanded by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        val result = session?.variables
        when {
            session == null -> {
                EmptyMessage("Open a notebook to see its kernel's variables.")
                return@Column
            }
            session.kernelName == null -> {
                EmptyMessage("Start ${session.document.path.substringAfterLast('/')}'s kernel (run a cell) to see its variables.")
                return@Column
            }
            result?.unsupported == true -> {
                EmptyMessage("Variables are listed for Python kernels.")
                return@Column
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "${session!!.document.path.substringAfterLast('/')} · ${session.kernelName}",
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            androidx.compose.foundation.text.BasicTextField(
                value = filter,
                onValueChange = { filter = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(color = colors.onSurface),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.primary),
                modifier = Modifier.width(180.dp).background(colors.surfaceContainerHigh, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
                decorationBox = { field ->
                    if (filter.isEmpty()) Text("Filter", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    field()
                },
            )
            IconButton(onClick = { controller.refreshVariables(session) }, enabled = !session.variablesLoading) {
                Icon(Icons.Outlined.Refresh, contentDescription = "Refresh variables", modifier = Modifier.size(18.dp))
            }
        }
        session.variablesError?.let {
            Text("Couldn't list the variables: $it", style = MaterialTheme.typography.bodySmall, color = colors.error, modifier = Modifier.padding(horizontal = 16.dp))
        }
        val variables = result?.variables.orEmpty().filter { filter.isBlank() || it.name.contains(filter, true) || it.type.contains(filter, true) }
        if (result != null && variables.isEmpty()) {
            EmptyMessage(if (filter.isBlank()) "No variables yet." else "No variable matches \"$filter\".")
        } else if (result == null) {
            EmptyMessage(if (session.variablesLoading) "Asking the kernel…" else "")
        }
        if (variables.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)) {
                listOf("Name" to 0.22f, "Type" to 0.14f, "Size" to 0.12f, "Value" to 0.52f).forEach { (label, weight) ->
                    Text(label, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, modifier = Modifier.weight(weight))
                }
            }
        }
        androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f)) {
            items(variables.size) { index ->
                val variable = variables[index]
                val open = expanded == variable.name
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (variable.viewable) controller.openDataViewer(session, variable) else expanded = if (open) null else variable.name
                        }
                        .background(if (index % 2 == 0) colors.surfaceContainerLow else colors.surfaceContainer)
                        .padding(horizontal = 16.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    val mono = androidx.compose.ui.text.font.FontFamily.Monospace
                    Text(variable.name, style = MaterialTheme.typography.bodySmall, fontFamily = mono, color = colors.primary, maxLines = 1, modifier = Modifier.weight(0.22f))
                    Text(variable.type, style = MaterialTheme.typography.bodySmall, maxLines = 1, modifier = Modifier.weight(0.14f))
                    Text(variable.size, style = MaterialTheme.typography.bodySmall, maxLines = 1, modifier = Modifier.weight(0.12f))
                    Row(Modifier.weight(0.52f), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            variable.value,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = mono,
                            color = colors.onSurfaceVariant,
                            maxLines = if (open) 12 else 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (variable.viewable) {
                            Icon(Icons.Outlined.TableChart, contentDescription = "Open in the data viewer", tint = colors.primary, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
            if (result?.truncated == true) {
                item { EmptyMessage("Only the first 500 names are listed.") }
            }
        }
    }
}
