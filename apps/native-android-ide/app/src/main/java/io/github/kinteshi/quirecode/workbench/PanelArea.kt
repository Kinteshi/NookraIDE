package io.github.kinteshi.quirecode.workbench

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
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
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Surface
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.outlined.VerticalSplit
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.fillMaxHeight

internal val PanelTab.icon: ImageVector
    get() = when (this) {
        PanelTab.Terminal -> Icons.Outlined.Terminal
        PanelTab.Debug -> Icons.Outlined.BugReport
        PanelTab.Problems -> Icons.Outlined.ErrorOutline
        PanelTab.Output -> Icons.AutoMirrored.Outlined.Article
        PanelTab.Ports -> Icons.Outlined.Lan
        PanelTab.Variables -> Icons.Outlined.DataObject
        PanelTab.Interactive -> Icons.Outlined.PlayCircleOutline
        PanelTab.Docs -> Icons.AutoMirrored.Outlined.MenuBook
    }

/** A panel tab; the wide and the compact layout draw the same one. */
@Composable
internal fun PanelTabChip(controller: WorkbenchController, tab: PanelTab) {
    FilterChip(
        selected = controller.panelTab == tab,
        onClick = { controller.panelTab = tab },
        leadingIcon = { Icon(tab.icon, contentDescription = null, modifier = Modifier.size(16.dp)) },
        label = {
            val count = when (tab) {
                PanelTab.Problems -> controller.diagnostics.size
                PanelTab.Ports -> controller.visiblePorts.size
                else -> 0
            }
            Text(if (count > 0) "${tab.title} $count" else tab.title, maxLines = 1)
        },
    )
}

/** The terminal toolbar's buttons, slimmer than the default so its row stays low. */
private val TERMINAL_ACTION = Modifier.size(36.dp)
private val TERMINAL_ACTION_ICON = Modifier.size(20.dp)

/** Bottom panel (Ctrl+J): terminals, problems and the output log, resizable by its top edge up to [maxHeight] dp. */
@Composable
fun PanelArea(controller: WorkbenchController, modifier: Modifier = Modifier, maxHeight: Float = 900f) {
    val height = (controller.panelHeight ?: 320f).coerceIn(140f, maxHeight.coerceAtLeast(140f))
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth().height(height.dp).background(colors.surfaceContainerLow)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(10.dp)
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { delta ->
                        controller.panelHeight = ((controller.panelHeight ?: height) - delta / density.density).coerceIn(140f, maxHeight.coerceAtLeast(140f))
                    },
                    onDragStopped = { controller.savePaneSizes() },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.width(36.dp).height(4.dp).background(colors.outlineVariant, RoundedCornerShape(2.dp)))
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // The tabs scroll when the panel is narrow; the actions beside them stay.
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                controller.shownPanelTabs.forEach { tab -> PanelTabChip(controller, tab) }
            }
            Tooltip("Hide panel") { IconButton(onClick = { controller.panelVisible = false }) {
                Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "Hide panel")
            } }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Crossfade(
                targetState = controller.panelTab,
                animationSpec = tween(if (controller.settings.animations) 120 else 0),
                label = "panelTab",
            ) { tab ->
                when (tab) {
                    PanelTab.Terminal -> TerminalView(controller)
                    PanelTab.Problems -> ProblemsList(controller)
                    PanelTab.Output -> OutputLog(controller)
                    PanelTab.Debug -> DebugPanel(controller)
                    PanelTab.Ports -> PortsView(controller)
                    PanelTab.Variables -> VariablesView(controller)
                    PanelTab.Interactive -> InteractiveView(controller)
                    PanelTab.Docs -> DocsView(controller)
                }
            }
        }
    }
}

@Composable
fun TerminalView(controller: WorkbenchController, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val dark = LocalDarkTheme.current
    Column(modifier.fillMaxSize()) {
        // Below the panel's tabs: the terminals, then what acts on them, which stays put while the tabs scroll.
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                controller.terminals.forEach { session ->
                    InputChip(
                        selected = session.id == controller.activeTerminalId,
                        onClick = { controller.activeTerminalId = session.id },
                        label = { Text(if (session.exited) "${session.title} (exited)" else session.title, maxLines = 1) },
                        leadingIcon = {
                            // Shell integration's last exit status, as VS Code's command decorations colour it.
                            val code = session.lastExitCode
                            if (code == null) {
                                Icon(Icons.Outlined.Terminal, contentDescription = null, modifier = Modifier.size(16.dp))
                            } else {
                                Box(Modifier.size(10.dp).background(if (code == 0) colors.primary else colors.error, androidx.compose.foundation.shape.CircleShape))
                            }
                        },
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
            if (controller.terminals.isNotEmpty()) {
                androidx.compose.material3.VerticalDivider(Modifier.padding(horizontal = 6.dp).height(20.dp), color = colors.outlineVariant)
            }
            TerminalActions(controller)
        }
        val session = controller.activeTerminal
        if (session == null && !controller.connected) {
            // Terminals run in the guest: offline, the button would only say so.
            EmptyState(Icons.Outlined.Terminal, "Terminals run in the guest", "Connect to the guest to open one.")
            return
        }
        if (session == null) {
            EmptyState(Icons.Outlined.Terminal, "No terminal is open", "Ctrl+Shift+` opens one too.") {
                FilledTonalButton(onClick = { controller.newTerminal() }) {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp).padding(end = 2.dp))
                    Text("New terminal")
                }
            }
            return
        }
        if (controller.terminalFindVisible) TerminalFindBar(controller, session)
        val pair = controller.terminalSplit?.takeIf { session.id == it.first || session.id == it.second }
            ?.let { (left, right) -> controller.terminals.firstOrNull { it.id == left } to controller.terminals.firstOrNull { it.id == right } }
        val left = pair?.first
        val right = pair?.second
        if (left == null || right == null) {
            TerminalPane(controller, session, Modifier.weight(1f).fillMaxWidth())
        } else {
            // VS Code's split terminal: the two side by side; a tap makes one active.
            Row(Modifier.weight(1f).fillMaxWidth()) {
                TerminalPane(controller, left, Modifier.weight(1f).fillMaxHeight())
                androidx.compose.material3.VerticalDivider(color = colors.outlineVariant)
                TerminalPane(controller, right, Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

/** One terminal: the emulator's view, and a note with New terminal once it ended. */
@Composable
private fun TerminalPane(controller: WorkbenchController, session: TerminalSession, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val dark = LocalDarkTheme.current
    Column(modifier) {
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
                // The I-beam over the terminal's text, as VS Code's terminal shows it.
                .pointerHoverIcon(androidx.compose.ui.input.pointer.PointerIcon.Text)
                .pointerInput(session.id, session.exited) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        if (controller.terminalSplit != null) controller.activeTerminalId = session.id
                        if (session.exited) {
                            endedFocus.requestFocus()
                            return@awaitEachGesture
                        }
                        // Copy on selection: once the finger or mouse lifts from making one.
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Final)
                        } while (event.changes.any { it.pressed })
                        controller.copyTerminalSelection(session)
                    }
                },
        ) {
            key(session.id) {
                ScrollableTerminal(
                    terminalEmulator = session.emulator,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 6.dp)
                        .onFocusChanged { state ->
                            if (state.hasFocus) {
                                controller.focusArea = FocusArea.Terminal
                                if (controller.terminalSplit != null) controller.activeTerminalId = session.id
                            } else if (controller.focusArea == FocusArea.Terminal) {
                                controller.focusArea = FocusArea.Other
                            }
                        },
                    initialFontSize = controller.settings.terminalFontSize.sp,
                    backgroundColor = background,
                    foregroundColor = colors.onSurface,
                    selectionBackgroundColor = colors.primary.copy(alpha = 0.35f),
                    keyboardEnabled = !session.exited,
                    onHyperlinkClick = controller::openLink,
                    onSelectionControllerAvailable = { session.selection = it },
                    onScrollAvailable = { session.scroll = it },
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
                    FilledTonalButton(onClick = { controller.newTerminal(profile = session.profile) }) { Text("New terminal") }
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
            EmptyState(Icons.Outlined.CloudOff, "Not connected", "Connect to the guest to see its ports.")
            return@Column
        }
        if (shown.isEmpty()) {
            EmptyState(Icons.Outlined.Lan, "No ports yet", "Start a server in the guest (python3 -m http.server, npm run dev) and it shows up here.", Modifier.weight(1f))
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
                    Tooltip("Copy address") { IconButton(onClick = { controller.copyPortAddress(port) }) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy address", modifier = Modifier.size(18.dp))
                    } }
                    Tooltip("Open in browser") { IconButton(onClick = { controller.openPort(port) }) {
                        Icon(Icons.Outlined.OpenInBrowser, contentDescription = "Open in browser", modifier = Modifier.size(18.dp))
                    } }
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
                EmptyState(Icons.Outlined.DataObject, "No notebook is open", "Open a notebook to see its kernel's variables.")
                return@Column
            }
            session.kernelName == null -> {
                EmptyState(Icons.Outlined.DataObject, "The kernel isn't running", "Run a cell of ${session.document.path.substringAfterLast('/')} to see its variables.")
                return@Column
            }
            result?.unsupported == true -> {
                EmptyState(Icons.Outlined.DataObject, "Python kernels only", "Variables are listed for Python kernels.")
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
            Tooltip("Refresh variables") { IconButton(onClick = { controller.refreshVariables(session) }, enabled = !session.variablesLoading) {
                Icon(Icons.Outlined.Refresh, contentDescription = "Refresh variables", modifier = Modifier.size(18.dp))
            } }
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

/** The terminal's own buttons: find in its output, links in it, a new terminal. */
@Composable
fun TerminalActions(controller: WorkbenchController) {
    Tooltip("Find in terminal") { IconButton(onClick = { if (controller.terminalFindVisible) controller.terminalFindVisible = false else controller.showTerminalFind() }, modifier = TERMINAL_ACTION) {
        Icon(Icons.Outlined.Search, contentDescription = "Find in terminal", modifier = TERMINAL_ACTION_ICON)
    } }
    Tooltip("Open a detected link") { IconButton(onClick = controller::showTerminalLinks, modifier = TERMINAL_ACTION) { Icon(Icons.Outlined.Link, contentDescription = "Open a detected link", modifier = TERMINAL_ACTION_ICON) } }
    TerminalCommandsButton(controller)
    Tooltip("Split terminal", "workbench.action.terminal.split") {
        IconButton(onClick = controller::splitTerminal, modifier = TERMINAL_ACTION) { Icon(Icons.Outlined.VerticalSplit, contentDescription = "Split terminal", modifier = TERMINAL_ACTION_ICON) }
    }
    Tooltip("New terminal", "workbench.action.terminal.new") { IconButton(onClick = { controller.newTerminal() }, modifier = TERMINAL_ACTION) { Icon(Icons.Outlined.Add, contentDescription = "New terminal", modifier = TERMINAL_ACTION_ICON) } }
    var more by remember { mutableStateOf(false) }
    Box {
        Tooltip("More terminal actions") { IconButton(onClick = { more = true }, modifier = TERMINAL_ACTION) { Icon(Icons.Outlined.MoreVert, contentDescription = "More terminal actions", modifier = TERMINAL_ACTION_ICON) } }
        DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
            // VS Code's launch profile list under +, then the terminal's own actions.
            controller.terminalProfiles.forEach { profile ->
                DropdownMenuItem(
                    text = { Text(profile.name + if (profile.name == controller.settings.terminalDefaultProfile) " (default)" else "") },
                    leadingIcon = { Icon(Icons.Outlined.Terminal, contentDescription = null) },
                    onClick = {
                        more = false
                        controller.newTerminal(profile = profile)
                    },
                )
            }
            androidx.compose.material3.HorizontalDivider()
            controller.activeTerminal?.let { session ->
                DropdownMenuItem(text = { Text("Rename…") }, onClick = {
                    more = false
                    controller.runCommand("workbench.action.terminal.rename")
                })
                if (controller.terminalSplit != null) {
                    DropdownMenuItem(text = { Text("Unsplit") }, onClick = {
                        more = false
                        controller.unsplitTerminal()
                    })
                }
                DropdownMenuItem(text = { Text("Copy last command output") }, onClick = {
                    more = false
                    controller.runCommand("workbench.action.terminal.copyLastCommandOutput")
                })
                DropdownMenuItem(text = { Text("Kill terminal") }, onClick = {
                    more = false
                    controller.closeTerminal(session.id)
                })
            }
        }
    }
}

/**
 * The commands shell integration marked in the active terminal, newest first, each with how it
 * ended: a tap scrolls to it (Ctrl+Up / Ctrl+Down step through them); copy its output or run it again.
 */
@Composable
private fun TerminalCommandsButton(controller: WorkbenchController) {
    val session = controller.activeTerminal ?: return
    val colors = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    Box {
        Tooltip("Commands") { IconButton(onClick = { open = true }, modifier = TERMINAL_ACTION) { Icon(Icons.Outlined.History, contentDescription = "Commands", modifier = TERMINAL_ACTION_ICON) } }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val commands = controller.terminalCommands(session).asReversed().take(20)
            if (commands.isEmpty()) {
                DropdownMenuItem(
                    text = { Text(if (controller.settings.shellIntegration) "No commands yet (bash and zsh mark them)" else "Shell integration is off (Settings → Terminal)") },
                    enabled = false,
                    onClick = {},
                )
            }
            commands.forEach { command ->
                DropdownMenuItem(
                    text = { Text(command.command.ifBlank { "(command)" }, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, maxLines = 1) },
                    leadingIcon = {
                        when (val code = command.exitCode) {
                            null -> androidx.compose.material3.CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
                            0 -> Icon(Icons.Outlined.CheckCircle, contentDescription = "Succeeded", tint = colors.primary)
                            else -> Text("$code", color = colors.error, style = MaterialTheme.typography.labelLarge)
                        }
                    },
                    trailingIcon = {
                        Row {
                            Tooltip("Copy output") {
                                IconButton(onClick = {
                                    open = false
                                    controller.copyTerminalText(session.textLog.output(command), "Copied the output of ${command.command}")
                                }) { Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy output", modifier = Modifier.size(18.dp)) }
                            }
                            Tooltip("Run again") {
                                IconButton(onClick = {
                                    open = false
                                    controller.rerunTerminalCommand(session, command)
                                }, enabled = !session.exited) { Icon(Icons.Outlined.Replay, contentDescription = "Run again", modifier = Modifier.size(18.dp)) }
                            }
                        }
                    },
                    onClick = {
                        open = false
                        controller.revealTerminalCommand(session, command)
                    },
                )
            }
        }
    }
}

/**
 * Find in the terminal's output (Ctrl+F there): the matching lines, newest last, with the match
 * marked. Enter and Shift+Enter (or a tap on a line) scroll the terminal to a match; a long press
 * on a line with a file:line or web address opens it, otherwise copies the line.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TerminalFindBar(controller: WorkbenchController, session: TerminalSession) {
    val colors = MaterialTheme.colorScheme
    val focus = remember { FocusRequester() }
    LaunchedEffect(controller.terminalFindFocus) { runCatching { focus.requestFocus() } }
    var matches by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(emptyList<TerminalMatch>()) }
    // Output keeps arriving; the list follows it while the bar is open.
    LaunchedEffect(session.id, controller.terminalFindQuery, controller.terminalFindCaseSensitive) {
        var seen = -1L
        while (true) {
            if (session.textLog.revision != seen) {
                seen = session.textLog.revision
                matches = controller.terminalMatches()
            }
            kotlinx.coroutines.delay(500)
        }
    }
    Column(Modifier.fillMaxWidth().background(colors.surfaceContainer).padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.foundation.text.BasicTextField(
                value = controller.terminalFindQuery,
                onValueChange = { controller.terminalFindQuery = it },
                singleLine = true,
                // Enter from a soft keyboard (or one that goes through the input method) comes as the action.
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { controller.stepTerminalMatch(matches, previous = true) }),
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.primary),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focus)
                    .background(colors.surfaceContainerHighest, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 7.dp)
                    .onPreviewKeyEvent { event ->
                        when {
                            event.type != KeyEventType.KeyDown -> false
                            event.key == androidx.compose.ui.input.key.Key.Escape -> {
                                controller.terminalFindVisible = false
                                true
                            }
                            event.key == androidx.compose.ui.input.key.Key.Enter || event.key == androidx.compose.ui.input.key.Key.NumPadEnter -> {
                                controller.stepTerminalMatch(matches, previous = !event.isShiftPressed)
                                true
                            }
                            else -> false
                        }
                    },
                decorationBox = { field ->
                    if (controller.terminalFindQuery.isEmpty()) Text("Find in terminal", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    field()
                },
            )
            Text(
                when {
                    controller.terminalFindQuery.isEmpty() -> ""
                    matches.isEmpty() -> "No results"
                    else -> "${matches.size} line${if (matches.size == 1) "" else "s"}"
                },
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
            )
            FilterChip(
                selected = controller.terminalFindCaseSensitive,
                onClick = { controller.terminalFindCaseSensitive = !controller.terminalFindCaseSensitive },
                label = { Text("Aa", fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) },
            )
            Tooltip("Close find") { IconButton(onClick = { controller.terminalFindVisible = false }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Outlined.Close, contentDescription = "Close find", modifier = Modifier.size(18.dp))
            } }
        }
        if (matches.isNotEmpty()) {
            val listState = androidx.compose.foundation.lazy.rememberLazyListState()
            val selected = matches.indexOfFirst { it.line == controller.terminalFindLine }
            LaunchedEffect(matches.size, selected) { listState.scrollToItem((if (selected >= 0) selected else matches.size - 1).coerceAtLeast(0)) }
            androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 150.dp).padding(top = 4.dp), state = listState) {
                items(matches.size) { index ->
                    val match = matches[index]
                    Text(
                        androidx.compose.ui.text.buildAnnotatedString {
                            append(match.text)
                            match.ranges.forEach { range ->
                                if (range.last < match.text.length) addStyle(androidx.compose.ui.text.SpanStyle(background = colors.primary.copy(alpha = 0.35f)), range.first, range.last + 1)
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (index == selected) colors.secondaryContainer else colors.surfaceContainer)
                            .combinedClickable(
                                onClick = { controller.revealTerminalLine(session, match.line) },
                                onLongClick = { controller.openTerminalLine(match.text) },
                            )
                            .padding(vertical = 2.dp),
                    )
                }
            }
        }
    }
}
