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
                        val count = if (tab == PanelTab.Problems) controller.diagnostics.size else 0
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
}
