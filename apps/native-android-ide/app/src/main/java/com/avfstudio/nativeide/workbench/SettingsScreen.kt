package com.avfstudio.nativeide.workbench

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.KeyboardTab
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.automirrored.outlined.WrapText
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Cable
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderCopy
import androidx.compose.material.icons.outlined.FormatIndentIncrease
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.FormatPaint
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.FormatStrikethrough
import androidx.compose.material.icons.outlined.Functions
import androidx.compose.material.icons.outlined.Highlight
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.InsertChart
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.KeyboardReturn
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Numbers
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Rule
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SettingsEthernet
import androidx.compose.material.icons.outlined.SpaceBar
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.UnfoldLess
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Web
import androidx.compose.material.icons.outlined.Mouse
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.avfstudio.nativeide.ai.ChatAgentKind
import kotlin.math.roundToInt

/** The Settings screen's pages, each with its icon in the list on the left (or the first screen on a phone). */
enum class SettingsCategory(val title: String, val summary: String, val icon: ImageVector) {
    Appearance("Appearance", "Theme, colours, file icons", Icons.Outlined.Palette),
    Editor("Editor", "Font, indentation, wrapping, folding", Icons.Outlined.Edit),
    Intelligence("Code intelligence", "Suggestions, hints, problems, references", Icons.Outlined.Lightbulb),
    Saving("Saving and formatting", "Auto save, format on save, clean-ups", Icons.Outlined.Save),
    Files("Files and Explorer", "Explorer, tabs, hidden files, launcher", Icons.Outlined.Folder),
    Languages("Languages", "Grammars and language servers", Icons.Outlined.Translate),
    Notebooks("Notebooks", "Kernels, outputs, cells", Icons.Outlined.Science),
    Terminal("Terminal", "Font size and ports", Icons.Outlined.Terminal),
    Ai("Copilot and Chat", "Inline suggestions, next edits, chat agent", Icons.Outlined.AutoAwesome),
    Guest("Guest", "Linux tools, fixes, connections", Icons.Outlined.Dns),
    Keyboard("Keyboard and device", "Shortcuts, key bar, haptics, settings.json", Icons.Outlined.Keyboard),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(controller: WorkbenchController) {
    LaunchedEffect(controller.connected) { if (controller.connected) controller.checkGuestTools() }
    LaunchedEffect(Unit) { controller.refreshIconThemeChoices() }
    var picked by rememberSaveable { mutableStateOf<SettingsCategory?>(null) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // A tablet shows the pages beside their list; a narrow window, the list and then a page.
            val wide = maxWidth >= 720.dp
            val shown = picked ?: if (wide) SettingsCategory.Appearance else null
            BackHandler(enabled = !wide && picked != null) { picked = null }
            Column(Modifier.fillMaxSize()) {
                TopAppBar(
                    title = { Text(if (!wide && shown != null) shown.title else "Settings") },
                    navigationIcon = {
                        Tooltip("Back") { IconButton(onClick = { if (!wide && picked != null) picked = null else controller.settingsVisible = false }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                        } }
                    },
                )
                if (wide) {
                    Row(Modifier.fillMaxSize()) {
                        CategoryList(shown, Modifier.width(300.dp).fillMaxHeight()) { picked = it }
                        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        CategoryPage(controller, shown ?: SettingsCategory.Appearance, heading = true, Modifier.weight(1f))
                    }
                } else if (shown == null) {
                    CategoryList(null, Modifier.fillMaxSize()) { picked = it }
                } else {
                    CategoryPage(controller, shown, heading = false, Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun CategoryList(selected: SettingsCategory?, modifier: Modifier, onPick: (SettingsCategory) -> Unit) {
    LazyColumn(modifier, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
        items(SettingsCategory.entries) { category ->
            NavigationDrawerItem(
                icon = { Icon(category.icon, contentDescription = null) },
                label = {
                    Column {
                        Text(category.title)
                        if (selected == null) {
                            Text(category.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                selected = category == selected,
                onClick = { onPick(category) },
                colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun CategoryPage(controller: WorkbenchController, category: SettingsCategory, heading: Boolean, modifier: Modifier) {
    val settings = controller.settings
    fun set(key: String, value: Any?) = controller.updateSetting(key, value)
    LazyColumn(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (heading) {
            item {
                Row(
                    Modifier.widthIn(max = 840.dp).fillMaxWidth().padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(category.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column {
                        Text(category.title, style = MaterialTheme.typography.titleLarge)
                        Text(category.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        when (category) {
            SettingsCategory.Appearance -> appearance(controller, settings, ::set)
            SettingsCategory.Editor -> editor(settings, ::set)
            SettingsCategory.Intelligence -> intelligence(settings, ::set)
            SettingsCategory.Saving -> saving(settings, ::set)
            SettingsCategory.Files -> files(controller, settings, ::set)
            SettingsCategory.Languages -> languages(controller, settings, ::set)
            SettingsCategory.Notebooks -> notebooks(settings, ::set)
            SettingsCategory.Terminal -> terminal(settings, ::set)
            SettingsCategory.Ai -> ai(controller, settings, ::set)
            SettingsCategory.Guest -> guest(controller, settings, ::set)
            SettingsCategory.Keyboard -> keyboard(controller, settings, ::set)
        }
        item { Spacer(Modifier.padding(bottom = 48.dp)) }
    }
}

private typealias SetSetting = (String, Any?) -> Unit

private fun LazyListScope.appearance(controller: WorkbenchController, settings: WorkbenchSettings, set: SetSetting) {
    item { Section("Theme") }
    item {
        ChoiceRow(Icons.Outlined.DarkMode, "Theme", "Light, dark, or follow the system", ThemeMode.entries.map { it.key to it.label }, settings.themeMode.key) {
            set(WorkbenchSettings.THEME_MODE, it)
        }
    }
    item {
        SwitchRow(
            Icons.Outlined.ColorLens,
            "Dynamic colour",
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "Material You palette from your wallpaper" else "Needs Android 12 or later",
            settings.dynamicColor,
        ) { set(WorkbenchSettings.DYNAMIC_COLOR, it) }
    }
    item { SwitchRow(Icons.Outlined.Contrast, "Pure black", "True black surfaces in dark mode, for OLED screens", settings.pureBlack) { set(WorkbenchSettings.PURE_BLACK, it) } }
    item { Section("Code colours") }
    item {
        ChoiceRow(
            Icons.Outlined.FormatPaint,
            "Syntax colours",
            "VS Code's Dark+ and Light+, or colours from the Material You palette",
            SyntaxColors.entries.map { it.key to it.label },
            settings.syntaxColors.key,
        ) { set(WorkbenchSettings.SYNTAX_COLORS, it) }
    }
    item { ActionRow(Icons.Outlined.FileOpen, "Import VS Code theme", "Syntax colours from a .vsix or theme JSON") { controller.pickAssetPack?.invoke("vsix") } }
    item { ActionRow(Icons.Outlined.Restore, "Use app syntax colours", "Drop an imported theme and follow light and dark mode") { controller.clearImportedTheme() } }
    item { Section("Icons") }
    item {
        ChoiceRow(
            Icons.Outlined.InsertDriveFile,
            "File icons",
            "Icons by file type in Explorer, tabs and Open Editors: VS Code's default Seti, an imported icon theme, or plain ones",
            listOf("vs-seti" to "Seti") + controller.iconThemeChoices.map { it.id to it.label } + listOf("none" to "Plain"),
            settings.iconTheme,
            below = controller.iconThemeChoices.isNotEmpty(),
        ) { set(WorkbenchSettings.ICON_THEME, it) }
    }
    item { ActionRow(Icons.Outlined.FileOpen, "Import icon theme", "File icons from a .vsix (Material Icon Theme, vscode-icons…; Open VSX has them)") { controller.pickAssetPack?.invoke("vsix") } }
}

private fun LazyListScope.editor(settings: WorkbenchSettings, set: SetSetting) {
    item { Section("Text") }
    item { SliderRow(Icons.Outlined.FormatSize, "Font size", settings.editorFontSize, 8f..32f, "%.0f sp") { set(WorkbenchSettings.EDITOR_FONT_SIZE, it.roundToInt()) } }
    item { SwitchRow(Icons.Outlined.ZoomIn, "Pinch to zoom", "Two fingers on the screen or the touchpad change the font size of the editor and notebooks, kept as the font size above", settings.pinchToZoom) { set(WorkbenchSettings.PINCH_TO_ZOOM, it) } }
    item { SwitchRow(Icons.Outlined.Mouse, "Ctrl + wheel zooms", "Ctrl and the mouse wheel change the font size (editor.mouseWheelZoom)", settings.mouseWheelZoom) { set(WorkbenchSettings.MOUSE_WHEEL_ZOOM, it) } }
    item { SwitchRow(Icons.AutoMirrored.Outlined.WrapText, "Word wrap", "Wrap long lines at the edge of the editor (folding needs it off)", settings.wordWrap) { set(WorkbenchSettings.WORD_WRAP, if (it) "on" else "off") } }
    item { SwitchRow(Icons.Outlined.FormatListNumbered, "Line numbers", null, settings.lineNumbers) { set(WorkbenchSettings.LINE_NUMBERS, if (it) "on" else "off") } }
    item { SwitchRow(Icons.Outlined.UnfoldLess, "Folding", "Arrows beside the line numbers fold functions, classes and blocks; Ctrl+Shift+[ and ] at the cursor", settings.folding) { set(WorkbenchSettings.FOLDING, it) } }
    item { SwitchRow(Icons.Outlined.ColorLens, "Semantic highlighting", "Colours from the language server, on top of the grammar", settings.semanticHighlighting) { set(WorkbenchSettings.SEMANTIC_HIGHLIGHTING, it) } }
    item { Section("Indentation") }
    item { SliderRow(Icons.AutoMirrored.Outlined.KeyboardTab, "Tab size", settings.tabSize.toFloat(), 1f..8f, "%.0f", steps = 6) { set(WorkbenchSettings.TAB_SIZE, it.roundToInt()) } }
    item { SwitchRow(Icons.Outlined.SpaceBar, "Insert spaces", "Indent with spaces instead of tabs", settings.insertSpaces) { set(WorkbenchSettings.INSERT_SPACES, it) } }
    item {
        SwitchRow(Icons.Outlined.Search, "Detect indentation", "Each file keeps its own indentation (spaces or tabs, and how many), found when it opens; the status bar shows and changes it", settings.detectIndentation) {
            set(WorkbenchSettings.DETECT_INDENTATION, it)
        }
    }
    item {
        SwitchRow(Icons.Outlined.FormatIndentIncrease, "Auto-indent", "Enter keeps the indentation, one level more after : or an open bracket", settings.autoIndent) {
            set(WorkbenchSettings.AUTO_INDENT, if (it) "full" else "none")
        }
    }
    item {
        SwitchRow(Icons.Outlined.KeyboardReturn, "Backspace by indentation level", "In leading spaces, Backspace goes back to the previous tab stop", settings.useTabStops) {
            set(WorkbenchSettings.USE_TAB_STOPS, it)
        }
    }
    item { Section("Editing") }
    item {
        ChoiceRow(
            Icons.Outlined.Search,
            "Find starts with",
            "What Ctrl+F puts in the find box",
            listOf("always" to "Word at cursor", "selection" to "Selection", "never" to "Last search"),
            settings.findSeed,
        ) { set(WorkbenchSettings.FIND_SEED, it) }
    }
    item { SwitchRow(Icons.Outlined.Code, "Linked editing", "Renaming an HTML or XML tag renames its closing tag as you type", settings.linkedEditing) { set(WorkbenchSettings.LINKED_EDITING, it) } }
    item { SwitchRow(Icons.AutoMirrored.Outlined.Notes, "Markdown preview follows the editor", "Scrolling a Markdown file scrolls its preview to the same place", settings.markdownScrollSync) { set(WorkbenchSettings.MARKDOWN_SCROLL_SYNC, it) } }
}

private fun LazyListScope.intelligence(settings: WorkbenchSettings, set: SetSetting) {
    item { Section("While typing") }
    item { SwitchRow(Icons.Outlined.AutoFixHigh, "Suggestions while typing", "The language server's completions pop up as you type, with auto-imports (Ctrl+Space always shows them)", settings.quickSuggestions) { set(WorkbenchSettings.QUICK_SUGGESTIONS, it) } }
    item {
        ChoiceRow(
            Icons.Outlined.DataObject,
            "Snippets in suggestions",
            "Where snippets (from imported packs and .vscode/*.code-snippets) go among the suggestions; Tab moves through a snippet's fields",
            SnippetSuggestions.entries.map { it.key to it.label },
            settings.snippetSuggestions.key,
        ) { set(WorkbenchSettings.SNIPPET_SUGGESTIONS, it) }
    }
    item { SwitchRow(Icons.Outlined.Functions, "Parameter hints", "The signature and the current parameter while typing a call (Ctrl+Shift+Space shows them)", settings.parameterHints) { set(WorkbenchSettings.PARAMETER_HINTS, it) } }
    item { Section("In the code") }
    item { SwitchRow(Icons.Outlined.Mouse, "Hover", "Resting a mouse or touchpad pointer on code shows its type, docs and problems (Ctrl+K Ctrl+I at the cursor)", settings.editorHover) { set(WorkbenchSettings.EDITOR_HOVER, it) } }
    if (settings.editorHover) {
        item { SliderRow(Icons.Outlined.Timer, "Hover delay", settings.editorHoverDelay.toFloat(), 0f..2000f, "%.0f ms", steps = 19) { set(WorkbenchSettings.EDITOR_HOVER_DELAY, it.roundToInt()) } }
        item { SliderRow(Icons.Outlined.Timer, "Hover hiding delay", settings.editorHoverHidingDelay.toFloat(), 0f..2000f, "%.0f ms", steps = 19) { set(WorkbenchSettings.EDITOR_HOVER_HIDING_DELAY, it.roundToInt()) } }
    }
    item { SwitchRow(Icons.Outlined.DataObject, "Inlay hints", "Inferred types and parameter names at the end of the line, from the language server", settings.inlayHints) { set(WorkbenchSettings.INLAY_HINTS, if (it) "on" else "off") } }
    item { SwitchRow(Icons.Outlined.Numbers, "Reference counts", "\"3 references\" after functions and classes (code lens); tap one to list them", settings.codeLens) { set(WorkbenchSettings.CODE_LENS, it) } }
    item { SwitchRow(Icons.Outlined.Highlight, "Highlight symbol uses", "Marks the other uses of the symbol under the cursor; assignments stronger", settings.occurrencesHighlight) { set(WorkbenchSettings.OCCURRENCES_HIGHLIGHT, if (it) "singleFile" else "off") } }
    item { Section("Problems") }
    item {
        SwitchRow(Icons.Outlined.ErrorOutline, "Problem underlines", "Wavy lines under problems, their message on tap or hover, and marks beside the line numbers and the scrollbar", settings.problemUnderlines) {
            set(WorkbenchSettings.PROBLEM_UNDERLINES, if (it) "on" else "off")
        }
    }
    item { SwitchRow(Icons.Outlined.VisibilityOff, "Fade unused code", "Imports and variables the language server reports as unused", settings.showUnused) { set(WorkbenchSettings.SHOW_UNUSED, it) } }
    item { SwitchRow(Icons.Outlined.FormatStrikethrough, "Strike through deprecated code", null, settings.showDeprecated) { set(WorkbenchSettings.SHOW_DEPRECATED, it) } }
    item {
        SwitchRow(Icons.Outlined.AccountTree, "Problems in the outline", "Problem counts beside Outline entries, and a filter for symbols with problems", settings.outlineProblems) {
            set(WorkbenchSettings.OUTLINE_PROBLEMS, it)
        }
    }
}

private fun LazyListScope.saving(settings: WorkbenchSettings, set: SetSetting) {
    item { Section("Saving") }
    item {
        ChoiceRow(
            Icons.Outlined.Save,
            "Auto save",
            "Save to the guest after a pause in typing, when you leave the editor, or when you leave the app",
            AutoSaveMode.entries.map { it.key to it.label },
            settings.autoSave.key,
            below = true,
        ) { set(WorkbenchSettings.AUTO_SAVE, it) }
    }
    item { Section("Formatting") }
    item { SwitchRow(Icons.Outlined.AutoFixHigh, "Format on save", "Ruff for Python, the language server otherwise", settings.formatOnSave) { set(WorkbenchSettings.FORMAT_ON_SAVE, it) } }
    item { SwitchRow(Icons.Outlined.ContentPaste, "Format on paste", "Formats what you paste, where the formatter can format a range (Ruff, TypeScript)", settings.formatOnPaste) { set(WorkbenchSettings.FORMAT_ON_PASTE, it) } }
    item { Section("Clean-ups on save") }
    item { SwitchRow(Icons.Outlined.CleaningServices, "Trim trailing whitespace", "Not on the cursor's line when saving automatically; Markdown files are left alone", settings.trimTrailingWhitespace) { set(WorkbenchSettings.TRIM_TRAILING_WHITESPACE, it) } }
    item { SwitchRow(Icons.Outlined.KeyboardReturn, "Insert final newline", "Ends the file with a line break", settings.insertFinalNewline) { set(WorkbenchSettings.INSERT_FINAL_NEWLINE, it) } }
    item { SwitchRow(Icons.Outlined.CleaningServices, "Trim final newlines", "Keeps a single line break at the end of the file", settings.trimFinalNewlines) { set(WorkbenchSettings.TRIM_FINAL_NEWLINES, it) } }
}

private fun LazyListScope.files(controller: WorkbenchController, settings: WorkbenchSettings, set: SetSetting) {
    item { Section("Explorer") }
    item { SwitchRow(Icons.Outlined.AccountTree, "Explorer tree", "Folders open in place below themselves; off opens one folder at a time", settings.explorerTree) { set(WorkbenchSettings.EXPLORER_TREE, it) } }
    item { SwitchRow(Icons.Outlined.FolderCopy, "Compact folders", "A folder holding only one folder shows as one row, like src/main/java", settings.compactFolders) { set(WorkbenchSettings.COMPACT_FOLDERS, it) } }
    item { SwitchRow(Icons.Outlined.ViewAgenda, "Open editors in Explorer", "The open tabs above the files, with their unsaved marks and close buttons", settings.openEditors) { set(WorkbenchSettings.OPEN_EDITORS, it) } }
    item { SwitchRow(Icons.Outlined.DragIndicator, "Drag to move", "Long-press a file or folder and drag it onto a folder; a long press without moving opens its menu", settings.explorerDragAndDrop) { set(WorkbenchSettings.EXPLORER_DRAG_AND_DROP, it) } }
    item { SwitchRow(Icons.Outlined.Delete, "Confirm before deleting", "Undo brings a deleted file back for 10 seconds either way", settings.confirmDelete) { set(WorkbenchSettings.CONFIRM_DELETE, it) } }
    item {
        ActionRow(Icons.Outlined.VisibilityOff, "Hidden files", "files.exclude in settings.json: ${settings.filesExclude.joinToString(", ").ifEmpty { "none" }}") {
            controller.runCommand("workbench.action.openSettingsJson")
        }
    }
    item { SwitchRow(Icons.Outlined.Rule, "Git marks", "Changed files in colour with their letter (M, U, A, D, ! for a conflict); their folders in colour", settings.gitDecorations) { set(WorkbenchSettings.GIT_DECORATIONS, it) } }
    item { SwitchRow(Icons.Outlined.History, "Git blame on the cursor's line", "Who last changed the line and when, faintly at its end (saved files; Git: Toggle Blame Editor Decoration)", settings.gitBlame) { set(WorkbenchSettings.GIT_BLAME, it) } }
    item { SwitchRow(Icons.Outlined.ErrorOutline, "Problem marks", "Files with errors or warnings in red or yellow with their count", settings.problemDecorations) { set(WorkbenchSettings.PROBLEM_DECORATIONS, it) } }
    item { Section("Tabs and files") }
    item {
        SwitchRow(Icons.Outlined.PushPin, "Preview tabs", "A tap in Explorer or Search opens the file in an italic tab that the next one replaces; edit it or tap twice to keep it", settings.enablePreview) {
            set(WorkbenchSettings.ENABLE_PREVIEW, it)
        }
    }
    item { SwitchRow(Icons.Outlined.Image, "Open SVG files as images", "Drawn in the image viewer; the buttons by the file path show the source", settings.svgAsImage) { set(WorkbenchSettings.SVG_AS_IMAGE, it) } }
    item { Section("Folders") }
    item { SwitchRow(Icons.Outlined.History, "Recent folders in the launcher", "Long-press MaterialCode's icon to open one of the last four folders", settings.launcherShortcuts) { set(WorkbenchSettings.LAUNCHER_SHORTCUTS, it) } }
    item {
        SwitchRow(Icons.AutoMirrored.Outlined.DriveFileMove, "Open the last folder offline", "Without a guest, its tabs come back from this device's copies; saves wait for the guest", settings.offlineWorkspace) {
            set(WorkbenchSettings.OFFLINE_WORKSPACE, it)
        }
    }
}

private fun LazyListScope.languages(controller: WorkbenchController, settings: WorkbenchSettings, set: SetSetting) {
    item { Section("Grammars") }
    item {
        ChoiceRow(
            Icons.Outlined.ColorLens,
            "Colours for other languages",
            "Files Python's built-in grammar doesn't cover can get VS Code's own grammar from Open VSX (10–60 KB each)",
            LanguageDownloads.entries.map { it.key to it.label },
            settings.languageDownloads.key,
        ) { set(WorkbenchSettings.LANGUAGE_DOWNLOADS, it) }
    }
    item {
        ActionRow(Icons.Outlined.Extension, "Get colours for a language…", "SQL, Rust, Go, YAML, shell, C/C++ and 40 more") {
            controller.commandFilter = ""
            controller.dialog = WorkbenchDialog.LanguagePacks
        }
    }
    controller.installedLanguagePacks().forEach { (id, name) ->
        item(key = "language-pack-$id") {
            SettingRow(Icons.Outlined.Extension, name, id) {
                TextButton(onClick = { controller.removeLanguagePack(id) }) { Text("Remove") }
            }
        }
    }
    item { Section("Language servers") }
    item {
        ChoiceRow(Icons.Outlined.Psychology, "Python language server", "ty uses about a fifth of basedpyright's memory", listOf("ty" to "ty", "basedpyright" to "basedpyright"), settings.pythonLanguageServer) {
            set(WorkbenchSettings.PYTHON_LANGUAGE_SERVER, it)
        }
    }
    item {
        ChoiceRow(
            Icons.Outlined.Psychology,
            "TypeScript language server",
            "TypeScript 7 is native (about 50 MB); vtsls runs tsserver on Node",
            listOf("typescript" to "TypeScript 7", "vtsls" to "vtsls"),
            settings.typescriptLanguageServer,
        ) { set(WorkbenchSettings.TYPESCRIPT_LANGUAGE_SERVER, it) }
    }
    item {
        ChoiceRow(
            Icons.Outlined.Rule,
            "Python lint rules",
            "Ruff rules added to the project's own: Extended adds bugbear, pyupgrade, simplify, import order and Ruff's rules",
            PythonLintRules.entries.map { it.key to it.label },
            settings.pythonLintRules.key,
        ) { set(WorkbenchSettings.PYTHON_LINT_RULES, it) }
    }
    item {
        ChoiceRow(
            Icons.Outlined.BugReport,
            "Python type checking",
            "Strict makes every ty rule an error (basedpyright: strict mode); Off keeps syntax errors only",
            PythonTypeChecking.entries.map { it.key to it.label },
            settings.pythonTypeChecking.key,
        ) { set(WorkbenchSettings.PYTHON_TYPE_CHECKING, it) }
    }
    item {
        SliderRow(Icons.Outlined.Timer, "Stop idle servers after", settings.languageServerIdleSeconds.toFloat(), 10f..600f, "%.0f s") {
            set(WorkbenchSettings.LANGUAGE_SERVER_IDLE, it.roundToInt())
        }
    }
    item { Section("Debugging") }
    item {
        SwitchRow(
            Icons.Outlined.BugReport,
            "Inline values",
            "While paused, the paused function's variables at the end of its lines, as VS Code shows them",
            settings.debugInlineValues,
        ) { set(WorkbenchSettings.DEBUG_INLINE_VALUES, it) }
    }
}

private fun LazyListScope.notebooks(settings: WorkbenchSettings, set: SetSetting) {
    item { Section("Kernels and cells") }
    item {
        ActionRow(Icons.Outlined.Memory, "Default notebook kernel", settings.jupyterDefaultKernel.ifEmpty { "Ask when a notebook first runs" }) {
            set(WorkbenchSettings.JUPYTER_DEFAULT_KERNEL, null)
        }
    }
    item {
        SwitchRow(Icons.Outlined.Lightbulb, "Language intelligence in cells", "Problems under code cells, hover (Ctrl+K Ctrl+I) and go to definition (F12), from the Python language server", settings.notebookLanguageServer) {
            set(WorkbenchSettings.NOTEBOOK_LANGUAGE_SERVER, it)
        }
    }
    item { SwitchRow(Icons.Outlined.AutoFixHigh, "Tab completes in notebooks", "After a name or a dot, Tab asks the kernel (as in Jupyter); Ctrl+Space always does", settings.notebookTabCompletion) { set(WorkbenchSettings.NOTEBOOK_TAB_COMPLETION, it) } }
    item { SwitchRow(Icons.Outlined.PushPin, "Sticky cell header", "A tall cell keeps its run button and actions at the top while it scrolls", settings.stickyCellHeader) { set(WorkbenchSettings.STICKY_CELL_HEADER, it) } }
    item { SwitchRow(Icons.Outlined.Speed, "Cell run statistics", "Run time and how the run ended (done, queued, running, failed) beside each code cell", settings.cellStatistics) { set(WorkbenchSettings.CELL_STATISTICS, it) } }
    item {
        SwitchRow(Icons.Outlined.Schedule, "Save run times in notebooks", "Writes each run's start and end into the cell's metadata, as JupyterLab does, so they show after reopening; adds to Git diffs", settings.recordTiming) {
            set(WorkbenchSettings.RECORD_TIMING, it)
        }
    }
    item {
        SwitchRow(
            Icons.Outlined.Code,
            "Run buttons on # %% lines",
            "Python files with # %% cells: Run Cell, Run Above and Run Below; Ctrl+Enter and Shift+Enter run cells in the Interactive window either way",
            settings.interactiveCodeLens,
        ) { set(WorkbenchSettings.INTERACTIVE_CODE_LENS, it) }
    }
    item { Section("Outputs") }
    item { SwitchRow(Icons.Outlined.TableChart, "Native tables", "pandas and polars DataFrames as sortable tables in the app's colours; off shows their HTML", settings.nativeTables) { set(WorkbenchSettings.NATIVE_TABLES, it) } }
    item {
        SwitchRow(Icons.Outlined.Tune, "Interactive widgets", "ipywidgets (sliders, dropdowns, buttons, interact()) drawn live and connected to the kernel; off shows their text", settings.liveWidgets) {
            set(WorkbenchSettings.LIVE_WIDGETS, it)
        }
    }
    item { SwitchRow(Icons.Outlined.InsertChart, "Draw Plotly figures", "Interactive charts in cell outputs; loads plotly.js from cdn.plot.ly the first time", settings.renderPlotly) { set(WorkbenchSettings.RENDER_PLOTLY, it) } }
    item {
        SwitchRow(Icons.Outlined.Refresh, "Plotly figures update in place", "display(fig, display_id=True) and its update() redraw one figure; applies to kernels started from now on", settings.plotlyDisplayIds) {
            set(WorkbenchSettings.PLOTLY_DISPLAY_IDS, it)
        }
    }
    item { SwitchRow(Icons.Outlined.Image, "Open plots with a tap", "A tap on a plot opens it full screen to zoom, browse the notebook's plots, save or share; its output menu always can", settings.plotViewerOnTap) { set(WorkbenchSettings.PLOT_VIEWER_ON_TAP, it) } }
    item { SwitchRow(Icons.Outlined.Functions, "Draw LaTeX", "Formulas from SymPy and IPython's Math() in outputs and Markdown cells; loads KaTeX from jsDelivr the first time", settings.renderLatex) { set(WorkbenchSettings.RENDER_LATEX, it) } }
    item { SwitchRow(Icons.Outlined.Web, "HTML outputs", "Rich output as HTML (and DataFrames when native tables are off); scripts never run", settings.renderHtmlOutputs) { set(WorkbenchSettings.RENDER_HTML, it) } }
    item {
        SwitchRow(
            Icons.Outlined.Code,
            "Outputs with scripts",
            "Altair/Vega charts, Bokeh, folium and other output that runs JavaScript, in an isolated web view (no access to the app or its files); loads their libraries from the internet",
            settings.scriptOutputs,
        ) { set(WorkbenchSettings.SCRIPT_OUTPUTS, it) }
    }
    item {
        SliderRow(Icons.Outlined.TextFields, "Long text outputs scroll after", settings.outputLineLimit.toFloat().coerceAtMost(200f), 10f..200f, "%.0f lines", steps = 18) {
            set(WorkbenchSettings.OUTPUT_LINE_LIMIT, it.roundToInt())
        }
    }
}

private fun LazyListScope.terminal(settings: WorkbenchSettings, set: SetSetting) {
    item { Section("Terminal") }
    item { SliderRow(Icons.Outlined.FormatSize, "Terminal font size", settings.terminalFontSize, 8f..24f, "%.0f sp") { set(WorkbenchSettings.TERMINAL_FONT_SIZE, it.roundToInt()) } }
    item {
        ChoiceRow(
            Icons.Outlined.Terminal,
            "Default shell",
            "What + starts; the arrow beside it offers the others. Add your own in settings.json (terminal.integrated.profiles.linux)",
            settings.terminalProfiles.map { it.name to it.name },
            settings.terminalDefaultProfile,
            below = true,
        ) { set(WorkbenchSettings.TERMINAL_DEFAULT_PROFILE, it) }
    }
    item {
        SwitchRow(Icons.Outlined.History, "Shell integration", "bash and zsh mark each prompt and command: the tab shows the last exit status, Commands lists them, Ctrl+Up and Ctrl+Down move between them", settings.shellIntegration) {
            set(WorkbenchSettings.SHELL_INTEGRATION, it)
        }
    }
    item { SwitchRow(Icons.Outlined.ContentCopy, "Copy on selection", "Text selected in the terminal goes to the clipboard", settings.terminalCopyOnSelection) { set(WorkbenchSettings.TERMINAL_COPY_ON_SELECTION, it) } }
    item { Section("Ports") }
    item {
        SwitchRow(Icons.Outlined.Lan, "Announce new servers", "When a program in the guest starts listening on a port, the status bar says so (Ports lists them all)", settings.portNotifications) {
            set(WorkbenchSettings.PORT_NOTIFICATIONS, it)
        }
    }
}

private fun LazyListScope.ai(controller: WorkbenchController, settings: WorkbenchSettings, set: SetSetting) {
    item { Section("GitHub Copilot") }
    item {
        SwitchRow(
            Icons.Outlined.AutoAwesome,
            "Inline suggestions",
            "Status: ${controller.copilotStatus}. Turning this on starts Copilot's ~280 MB server in the guest.",
            controller.copilotEnabled,
        ) { enabled -> if (enabled) controller.beginCopilotSignIn() else controller.enableCopilot(false) }
    }
    item {
        SwitchRow(
            Icons.Outlined.Bolt,
            "Next edit suggestions",
            "After an edit, Copilot proposes the next change anywhere in the file; Tab goes to it, Tab again accepts, Esc dismisses",
            settings.nextEditSuggestions,
        ) { set(WorkbenchSettings.NEXT_EDIT_SUGGESTIONS, it) }
    }
    item { SliderRow(Icons.Outlined.Timer, "Suggestion delay", settings.inlineSuggestDelayMs.toFloat(), 0f..2000f, "%.0f ms") { set(WorkbenchSettings.INLINE_SUGGEST_DELAY, it.roundToInt()) } }
    item { SwitchRow(Icons.Outlined.Science, "Suggestions in notebooks", "Copilot sees all cells of the notebook as context", settings.notebookCopilot) { set(WorkbenchSettings.NOTEBOOK_COPILOT, it) } }
    item {
        ActionRow(Icons.AutoMirrored.Outlined.Login, "Sign in to GitHub", "One login for Copilot chat and Git (GitHub CLI); inline suggestions sign in separately") {
            controller.signInGitHubInTerminal(); controller.settingsVisible = false
        }
    }
    item { ActionRow(Icons.AutoMirrored.Outlined.Logout, "Sign out of inline suggestions", null) { controller.runCommand("github.copilot.signOut") } }
    item { Section("Chat") }
    item {
        ChoiceRow(
            Icons.Outlined.Psychology,
            "Chat agent",
            "Who answers in the Chat view; each runs in the guest and signs in once",
            ChatAgentKind.entries.map { it.key to it.label },
            settings.chatAgent.key,
        ) { key -> ChatAgentKind.entries.firstOrNull { it.key == key }?.let(controller::selectChatAgent) }
    }
    item {
        ActionRow(Icons.AutoMirrored.Outlined.Login, "Sign in to ${settings.chatAgent.label}", "Runs the agent's own sign-in in a terminal") {
            controller.signInChatAgent(); controller.settingsVisible = false
        }
    }
    item {
        // The common levels as buttons; settings.json takes any level the CLI accepts.
        ChoiceRow(
            Icons.Outlined.Tune,
            "Chat reasoning effort",
            "How much Copilot chat thinks before answering; Default leaves it to Copilot",
            listOf("", "low", "medium", "high").map { it to reasoningEffortLabel(it) },
            settings.chatReasoningEffort,
        ) { controller.setChatReasoningEffort(it) }
    }
    item { SliderRow(Icons.Outlined.Timer, "End idle chat agent after", settings.chatIdleMinutes.toFloat(), 1f..60f, "%.0f min") { set(WorkbenchSettings.CHAT_IDLE_MINUTES, it.roundToInt()) } }
    item {
        SwitchRow(Icons.Outlined.History, "Continue chats after the agent stops", "When the agent ends (idle, reconnect, reasoning effort), the next message picks up the chat shown", settings.chatResume) {
            set(WorkbenchSettings.CHAT_RESUME, it)
        }
    }
}

private fun LazyListScope.guest(controller: WorkbenchController, settings: WorkbenchSettings, set: SetSetting) {
    item { Section("Connection") }
    item { ActionRow(Icons.Outlined.Cable, "Connections", controller.profileName.ifEmpty { "Manage guest connection profiles" }) { controller.dialog = WorkbenchDialog.Connect } }
    item {
        SwitchRow(Icons.Outlined.Memory, "Guest resources in the status bar", "Memory the Linux guest costs the tablet, its CPU and its storage", settings.statusBarGuestResources) {
            set(WorkbenchSettings.STATUS_BAR_GUEST, it)
        }
    }
    item { Section("Guest tools") }
    items(GuestDependency.entries.filter { dependency -> OtherLanguageServer.entries.none { it.dependency == dependency } }) { dependency ->
        GuestToolRow(controller, dependency)
    }
    item { Section("Language servers for other languages") }
    item {
        SwitchRow(
            Icons.Outlined.Translate,
            "Start them for their files",
            "Go, C/C++, Rust, Bash, YAML, JSON, HTML, CSS, Dockerfile, SQL, TOML and Markdown get completion, problems and navigation; each is offered for install the first time one of its files opens",
            settings.otherLanguageServers,
        ) { set(WorkbenchSettings.OTHER_LANGUAGE_SERVERS, it) }
    }
    if (settings.otherLanguageServers) {
        items(OtherLanguageServer.entries) { server -> GuestToolRow(controller, server.dependency) }
        item {
            ChoiceRow(
                Icons.Outlined.Rule,
                "SQL style rules",
                "How sqlint's layout rules (a line break after SELECT, upper-case keywords, aligned columns) show in Problems; sqlLanguageServer.lint in settings.json wins",
                SqlStyleRules.entries.map { it.key to it.label },
                settings.sqlStyleRules.key,
            ) { set(WorkbenchSettings.SQL_STYLE_RULES, it) }
        }
    }
    controller.forwarderFix?.let { fix ->
        item {
            SettingRow(Icons.Outlined.SettingsEthernet, "Port forwarding fix", fix.summary) {
                when (fix) {
                    ForwarderFix.Missing -> FilledTonalButton(onClick = {
                        controller.settingsVisible = false
                        controller.offerForwarderFix(install = true)
                    }) { Text("Apply") }
                    ForwarderFix.AfterRestart, ForwarderFix.Active -> TextButton(onClick = {
                        controller.settingsVisible = false
                        controller.offerForwarderFix(install = false)
                    }) { Text("Undo") }
                }
            }
        }
    }
    controller.tmpPlacement?.let { placement ->
        item {
            SettingRow(Icons.Outlined.Storage, "Temp files (/tmp)", placement.summary) {
                when (placement) {
                    TmpPlacement.Memory -> FilledTonalButton(onClick = {
                        controller.settingsVisible = false
                        controller.offerTmpPlacement(toDisk = true)
                    }) { Text("Move to disk") }
                    TmpPlacement.DiskAfterRestart, TmpPlacement.MovedToDisk -> TextButton(onClick = {
                        controller.settingsVisible = false
                        controller.offerTmpPlacement(toDisk = false)
                    }) { Text(if (placement == TmpPlacement.MovedToDisk) "Back to memory" else "Undo") }
                    TmpPlacement.Disk -> Unit
                }
            }
        }
    }
    item {
        ActionRow(Icons.Outlined.Refresh, "Check guest tools again", if (controller.connected) "After installing something yourself" else "Connect to a guest to check") {
            controller.checkGuestTools()
        }
    }
}

private fun LazyListScope.keyboard(controller: WorkbenchController, settings: WorkbenchSettings, set: SetSetting) {
    item { Section("Keyboard") }
    item { ActionRow(Icons.Outlined.Keyboard, "Keyboard shortcuts", "VS Code defaults · Ctrl+K Ctrl+S") { controller.dialog = WorkbenchDialog.Keybindings } }
    item {
        SwitchRow(Icons.AutoMirrored.Outlined.KeyboardTab, "Key bar", "Esc, Tab, Ctrl, Alt, Shift and arrows above the on-screen keyboard; tap Ctrl twice to keep it on", settings.keyBar) {
            set(WorkbenchSettings.KEY_BAR, it)
        }
    }
    item { Section("Device") }
    item { SwitchRow(Icons.Outlined.ChatBubbleOutline, "Tooltips", "A button's name and shortcut when a mouse or touchpad pointer rests on it, or on a long press", settings.tooltips) { set(WorkbenchSettings.TOOLTIPS, it) } }
    item { SwitchRow(Icons.Outlined.Vibration, "Haptic feedback", "A short buzz for breakpoints, latched Ctrl or Alt, picking up and dropping in Explorer, and Undo", settings.haptics) { set(WorkbenchSettings.HAPTICS, it) } }
    item { Section("Advanced") }
    item { ActionRow(Icons.Outlined.Settings, "Edit settings.json", "Every setting, in VS Code's format (editor.*, files.* as in VS Code; avf.* for this app's own)") { controller.openSettingsJson() } }
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
private fun RowIcon(icon: ImageVector) {
    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SettingRow(icon: ImageVector, title: String, summary: String?, trailing: @Composable () -> Unit) {
    ListItem(
        leadingContent = { RowIcon(icon) },
        headlineContent = { Text(title) },
        supportingContent = summary?.let { { Text(it) } },
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.widthIn(max = 840.dp).fillMaxWidth(),
    )
}

@Composable
private fun SwitchRow(icon: ImageVector, title: String, summary: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        leadingContent = { RowIcon(icon) },
        headlineContent = { Text(title) },
        supportingContent = summary?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.widthIn(max = 840.dp).fillMaxWidth().clickable { onChange(!checked) },
    )
}

@Composable
private fun ActionRow(icon: ImageVector, title: String, summary: String?, onClick: () -> Unit) {
    ListItem(
        leadingContent = { RowIcon(icon) },
        headlineContent = { Text(title) },
        supportingContent = summary?.let { { Text(it) } },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.widthIn(max = 840.dp).fillMaxWidth().clickable(onClick = onClick),
    )
}

/**
 * A setting with a few values as segmented buttons: beside the text, or across the row's width
 * below it when [below] (four choices don't fit beside the text at tablet width).
 */
@Composable
private fun ChoiceRow(
    icon: ImageVector,
    title: String,
    summary: String?,
    options: List<Pair<String, String>>,
    selected: String,
    below: Boolean = false,
    onSelect: (String) -> Unit,
) {
    @Composable
    fun Buttons(modifier: Modifier) {
        SingleChoiceSegmentedButtonRow(modifier) {
            options.forEachIndexed { index, (value, label) ->
                SegmentedButton(
                    selected = selected == value,
                    onClick = { onSelect(value) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                ) { Text(label, maxLines = 2, textAlign = TextAlign.Center) }
            }
        }
    }
    ListItem(
        leadingContent = { RowIcon(icon) },
        headlineContent = { Text(title) },
        supportingContent = if (below) {
            {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    summary?.let { Text(it) }
                    Buttons(Modifier.fillMaxWidth())
                }
            }
        } else {
            summary?.let { { Text(it) } }
        },
        trailingContent = if (below) null else ({ Buttons(Modifier) }),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.widthIn(max = 840.dp).fillMaxWidth(),
    )
}

/** A slider that writes its setting once the thumb is released, not on every frame of the drag. */
@Composable
private fun SliderRow(
    icon: ImageVector,
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: String,
    steps: Int = 0,
    onCommit: (Float) -> Unit,
) {
    var current by remember(value) { mutableFloatStateOf(value) }
    Row(
        Modifier.widthIn(max = 840.dp).fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        RowIcon(icon)
        Column(Modifier.weight(1f)) {
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
}

@Composable
private fun GuestToolRow(controller: WorkbenchController, dependency: GuestDependency) {
    val state = controller.guestTool(dependency)
    SettingRow(
        Icons.Outlined.Extension,
        dependency.label,
        when (state) {
            is DependencyState.Found -> "${dependency.usedFor} · ${state.path}"
            DependencyState.Missing -> "${dependency.usedFor} · not installed"
            DependencyState.Unknown -> dependency.usedFor
        },
    ) {
        when (state) {
            DependencyState.Missing -> FilledTonalButton(onClick = {
                controller.settingsVisible = false
                controller.offerInstall(dependency)
            }) { Text("Install") }
            is DependencyState.Found -> Row(verticalAlignment = Alignment.CenterVertically) {
                if (dependency.remove != null) {
                    TextButton(onClick = {
                        controller.settingsVisible = false
                        controller.offerRemove(dependency)
                    }) { Text("Remove") }
                }
                Icon(Icons.Outlined.CheckCircle, contentDescription = "Installed", tint = MaterialTheme.colorScheme.primary)
            }
            DependencyState.Unknown -> Unit
        }
    }
}
