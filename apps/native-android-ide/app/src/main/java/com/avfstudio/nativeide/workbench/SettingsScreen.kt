package com.avfstudio.nativeide.workbench

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(controller: WorkbenchController) {
    val settings = controller.settings
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    fun set(key: String, value: Any?) = controller.updateSetting(key, value)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection)) {
            LargeTopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = { controller.settingsVisible = false }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
            LazyColumn(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                item { Section("Appearance") }
                item {
                    SettingRow("Theme", "Light, dark, or follow the system") {
                        SingleChoiceSegmentedButtonRow {
                            ThemeMode.entries.forEachIndexed { index, mode ->
                                SegmentedButton(
                                    selected = settings.themeMode == mode,
                                    onClick = { set(WorkbenchSettings.THEME_MODE, mode.key) },
                                    shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size),
                                ) { Text(mode.label) }
                            }
                        }
                    }
                }
                item {
                    SwitchRow(
                        "Dynamic colour",
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "Material You palette from your wallpaper" else "Needs Android 12 or later",
                        settings.dynamicColor,
                    ) { set(WorkbenchSettings.DYNAMIC_COLOR, it) }
                }
                item {
                    SwitchRow("Pure black", "True black surfaces in dark mode, for OLED screens", settings.pureBlack) {
                        set(WorkbenchSettings.PURE_BLACK, it)
                    }
                }
                item {
                    ActionRow("Import VS Code theme", "Syntax colours from a .vsix or theme JSON") { controller.pickAssetPack?.invoke("vsix") }
                }
                item {
                    ActionRow("Use app syntax colours", "Drop an imported theme and follow light and dark mode") { controller.clearImportedTheme() }
                }

                item { Section("Editor") }
                item {
                    SliderRow("Font size", settings.editorFontSize, 8f..32f, "%.0f sp") { set(WorkbenchSettings.EDITOR_FONT_SIZE, it.roundToInt()) }
                }
                item {
                    SliderRow("Tab size", settings.tabSize.toFloat(), 1f..8f, "%.0f", steps = 6) { set(WorkbenchSettings.TAB_SIZE, it.roundToInt()) }
                }
                item { SwitchRow("Insert spaces", "Indent with spaces instead of tabs", settings.insertSpaces) { set(WorkbenchSettings.INSERT_SPACES, it) } }
                item {
                    SwitchRow("Auto-indent", "Enter keeps the indentation, one level more after : or an open bracket", settings.autoIndent) {
                        set(WorkbenchSettings.AUTO_INDENT, if (it) "full" else "none")
                    }
                }
                item { SwitchRow("Word wrap", "Wrap long lines at the edge of the editor", settings.wordWrap) { set(WorkbenchSettings.WORD_WRAP, if (it) "on" else "off") } }
                item { SwitchRow("Line numbers", null, settings.lineNumbers) { set(WorkbenchSettings.LINE_NUMBERS, if (it) "on" else "off") } }
                item {
                    SwitchRow("Semantic highlighting", "Colours from the language server, on top of the grammar", settings.semanticHighlighting) {
                        set(WorkbenchSettings.SEMANTIC_HIGHLIGHTING, it)
                    }
                }
                item { SwitchRow("Format on save", "Ruff for Python, the language server otherwise", settings.formatOnSave) { set(WorkbenchSettings.FORMAT_ON_SAVE, it) } }
                item {
                    SettingRow("Auto save", "Save to the guest after a pause in typing, when you leave the editor, or when you leave the app") {
                        SingleChoiceSegmentedButtonRow {
                            AutoSaveMode.entries.forEachIndexed { index, mode ->
                                SegmentedButton(
                                    selected = settings.autoSave == mode,
                                    onClick = { set(WorkbenchSettings.AUTO_SAVE, mode.key) },
                                    shape = SegmentedButtonDefaults.itemShape(index, AutoSaveMode.entries.size),
                                ) { Text(mode.label, maxLines = 1) }
                            }
                        }
                    }
                }

                item {
                    SwitchRow("Guest resources in the status bar", "Memory the Linux guest costs the tablet, its CPU and its storage", settings.statusBarGuestResources) {
                        set(WorkbenchSettings.STATUS_BAR_GUEST, it)
                    }
                }
                item { Section("Language servers") }
                item {
                    SettingRow("Python language server", "ty uses about a fifth of basedpyright's memory") {
                        SingleChoiceSegmentedButtonRow {
                            listOf("ty", "basedpyright").forEachIndexed { index, server ->
                                SegmentedButton(
                                    selected = settings.pythonLanguageServer == server,
                                    onClick = { set(WorkbenchSettings.PYTHON_LANGUAGE_SERVER, server) },
                                    shape = SegmentedButtonDefaults.itemShape(index, 2),
                                ) { Text(server) }
                            }
                        }
                    }
                }
                item {
                    SliderRow("Stop idle servers after", settings.languageServerIdleSeconds.toFloat(), 10f..600f, "%.0f s") {
                        set(WorkbenchSettings.LANGUAGE_SERVER_IDLE, it.roundToInt())
                    }
                }

                item { Section("GitHub Copilot") }
                item {
                    SwitchRow(
                        "Inline suggestions",
                        "Status: ${controller.copilotStatus}. Turning this on starts Copilot's ~280 MB server in the guest.",
                        controller.copilotEnabled,
                    ) { enabled -> if (enabled) controller.beginCopilotSignIn() else controller.enableCopilot(false) }
                }
                item {
                    SliderRow("Suggestion delay", settings.inlineSuggestDelayMs.toFloat(), 0f..2000f, "%.0f ms") {
                        set(WorkbenchSettings.INLINE_SUGGEST_DELAY, it.roundToInt())
                    }
                }
                item {
                    SliderRow("End idle chat agent after", settings.chatIdleMinutes.toFloat(), 1f..60f, "%.0f min") {
                        set(WorkbenchSettings.CHAT_IDLE_MINUTES, it.roundToInt())
                    }
                }
                item {
                    SwitchRow("Suggestions in notebooks", "Copilot sees all cells of the notebook as context", settings.notebookCopilot) {
                        set(WorkbenchSettings.NOTEBOOK_COPILOT, it)
                    }
                }
                item {
                    ActionRow("Sign in to GitHub", "One login for Copilot chat and Git (GitHub CLI); inline suggestions sign in separately") {
                        controller.signInGitHubInTerminal(); controller.settingsVisible = false
                    }
                }
                item { ActionRow("Sign out of inline suggestions", null) { controller.runCommand("github.copilot.signOut") } }

                item { Section("Terminal and Jupyter") }
                item {
                    SliderRow("Terminal font size", settings.terminalFontSize, 8f..24f, "%.0f sp") {
                        set(WorkbenchSettings.TERMINAL_FONT_SIZE, it.roundToInt())
                    }
                }
                item {
                    ActionRow(
                        "Default notebook kernel",
                        settings.jupyterDefaultKernel.ifEmpty { "Ask when a notebook first runs" },
                    ) { set(WorkbenchSettings.JUPYTER_DEFAULT_KERNEL, null) }
                }

                item {
                    SwitchRow("Draw Plotly figures", "Interactive charts in cell outputs; loads plotly.js from cdn.plot.ly the first time", settings.renderPlotly) {
                        set(WorkbenchSettings.RENDER_PLOTLY, it)
                    }
                }
                item { Section("Keyboard and advanced") }
                item { ActionRow("Keyboard shortcuts", "VS Code defaults · Ctrl+K Ctrl+S") { controller.dialog = WorkbenchDialog.Keybindings } }
                item { ActionRow("Edit settings.json", "Every setting, in VS Code's format") { controller.openSettingsJson() } }
                item { ActionRow("Connections", controller.profileName.ifEmpty { "Manage guest connection profiles" }) { controller.dialog = WorkbenchDialog.Connect } }
                item { androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = 48.dp)) }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Column(Modifier.widthIn(max = 840.dp).fillMaxWidth()) {
        HorizontalDivider(Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
        )
    }
}

@Composable
private fun SettingRow(title: String, summary: String?, trailing: @Composable () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = summary?.let { { Text(it) } },
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.widthIn(max = 840.dp).fillMaxWidth(),
    )
}

@Composable
private fun SwitchRow(title: String, summary: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = summary?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.widthIn(max = 840.dp).fillMaxWidth().clickable { onChange(!checked) },
    )
}

@Composable
private fun ActionRow(title: String, summary: String?, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = summary?.let { { Text(it) } },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.widthIn(max = 840.dp).fillMaxWidth().clickable(onClick = onClick),
    )
}

/** A slider that writes its setting once the thumb is released, not on every frame of the drag. */
@Composable
private fun SliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: String,
    steps: Int = 0,
    onCommit: (Float) -> Unit,
) {
    var current by remember(value) { mutableFloatStateOf(value) }
    Column(Modifier.widthIn(max = 840.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(format.format(current), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = current,
            onValueChange = { current = it },
            onValueChangeFinished = { onCommit(current) },
            valueRange = range,
            steps = steps,
        )
    }
}
