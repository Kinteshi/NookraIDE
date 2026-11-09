package com.avfstudio.nativeide.workbench

import com.avfstudio.nativeide.ai.ChatAgentKind
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

enum class ThemeMode(val key: String, val label: String) {
    System("system", "Follow system"),
    Light("light", "Light"),
    Dark("dark", "Dark"),
}

/**
 * Typed view of the user's `settings.json`. Keys follow VS Code where an equivalent exists
 * (`editor.fontSize`, `files.autoSave`, `terminal.integrated.fontSize`) and use `avf.*` for
 * behaviour specific to this app, so a VS Code settings file mostly carries over.
 */
data class WorkbenchSettings(
    val themeMode: ThemeMode = ThemeMode.System,
    val dynamicColor: Boolean = true,
    val pureBlack: Boolean = false,
    val syntaxColors: SyntaxColors = SyntaxColors.Classic,
    val editorFontSize: Float = 14f,
    val tabSize: Int = 4,
    val insertSpaces: Boolean = true,
    /** Tab size and spaces or tabs follow each file's own indentation (`editor.detectIndentation`). */
    val detectIndentation: Boolean = true,
    val wordWrap: Boolean = false,
    val lineNumbers: Boolean = true,
    val formatOnSave: Boolean = false,
    /** Ruff/TypeScript format what is pasted (range formatting), like `editor.formatOnPaste`. */
    val formatOnPaste: Boolean = false,
    /** Save-time clean-ups (`files.trimTrailingWhitespace`, `files.insertFinalNewline`, `files.trimFinalNewlines`). */
    val trimTrailingWhitespace: Boolean = false,
    val insertFinalNewline: Boolean = false,
    val trimFinalNewlines: Boolean = false,
    /** Parameter hints while typing a call (`editor.parameterHints.enabled`). */
    val parameterHints: Boolean = true,
    /** The language server's suggestions pop up while typing (`editor.quickSuggestions`); Ctrl+Space always shows them. */
    val quickSuggestions: Boolean = true,
    /**
     * With the Aardink engine and a hardware keyboard, suggestions in our own list, chosen with the arrows, Enter and Tab:
     * Aardink shows only its touch strip on Android for now. Off, the strip.
     */
    val keyboardSuggestionList: Boolean = true,
    /** Who last changed the cursor's line, at its end (`git.blame.editorDecoration.enabled`). */
    val gitBlame: Boolean = false,
    /** Language servers for Go, C/C++, Rust, Bash, YAML, JSON, HTML, CSS, Dockerfile, SQL, TOML and Markdown (`avf.languageServers.others`). */
    val otherLanguageServers: Boolean = true,
    /** How the SQL server's layout rules (sqlint: a line break after SELECT, upper-case keywords…) report (`avf.sql.styleRules`). */
    val sqlStyleRules: SqlStyleRules = SqlStyleRules.Warning,
    /** The paused function's variables at the end of its lines (`debug.inlineValues`). */
    val debugInlineValues: Boolean = true,
    /** Where snippets go among the suggestions (`editor.snippetSuggestions`). */
    val snippetSuggestions: SnippetSuggestions = SnippetSuggestions.Inline,
    /** What Find starts with (`editor.find.seedSearchStringFromSelection`): "always" (the word at the cursor), "selection" or "never". */
    val findSeed: String = "always",
    /** The other uses of the symbol under the cursor (`editor.occurrencesHighlight`). */
    val occurrencesHighlight: Boolean = true,
    /** Inferred types and parameter names from the language server (`editor.inlayHints.enabled`). */
    val inlayHints: Boolean = true,
    /** Pinching the editor changes `editor.fontSize`. */
    val pinchToZoom: Boolean = true,
    /** `editor.mouseWheelZoom`: Ctrl and the mouse wheel (or touchpad scroll) change the font size. */
    val mouseWheelZoom: Boolean = true,
    /** `avf.workbench.tooltips`: a button's name (and key) when the pointer rests on it or on a long press. */
    val tooltips: Boolean = true,
    /** `editor.hover.enabled`: the language server's hover card when the pointer rests on code. */
    val editorHover: Boolean = true,
    /** `editor.hover.delay`: how long the pointer rests before the hover card shows, in ms. */
    val editorHoverDelay: Int = 300,
    /** `editor.hover.hidingDelay`: how long the card stays once the pointer leaves its word, to reach it, in ms. */
    val editorHoverHidingDelay: Int = 300,
    /** The Markdown preview follows the editor's scrolling (`markdown.preview.scrollPreviewWithEditor`). */
    val markdownScrollSync: Boolean = true,
    val autoSave: AutoSaveMode = AutoSaveMode.Off,
    val autoSaveDelayMs: Int = 1000,
    val semanticHighlighting: Boolean = true,
    val terminalFontSize: Float = 13f,
    /** `terminal.integrated.defaultProfile.linux`: the shell a new terminal starts. */
    val terminalDefaultProfile: String = "bash",
    /** `terminal.integrated.profiles.linux`: the user's own shells, over the built-in ones (null hides one). */
    val terminalProfiles: List<TerminalProfile> = BUILT_IN_TERMINAL_PROFILES,
    /** `terminal.integrated.shellIntegration.enabled`: prompt and exit-status marks in bash and zsh. */
    val shellIntegration: Boolean = true,
    /** `terminal.integrated.copyOnSelection`: selected terminal text goes to the clipboard. */
    val terminalCopyOnSelection: Boolean = false,
    val pythonLanguageServer: String = "ty",
    /** TypeScript 7's native server ("typescript") or vtsls (Node + tsserver). */
    val typescriptLanguageServer: String = "typescript",
    val languageServerIdleSeconds: Int = 60,
    val inlineSuggestDelayMs: Int = 350,
    val chatIdleMinutes: Int = 5,
    /** Which agent the Chat view talks to (see [ChatAgentKind]). */
    val chatAgent: ChatAgentKind = ChatAgentKind.Copilot,
    /** Copilot CLI's `--reasoning-effort`; empty leaves the CLI's default. */
    val chatReasoningEffort: String = "",
    /** After the agent stops (idle, reconnect, effort change) the next message reloads the chat shown. */
    val chatResume: Boolean = true,
    val jupyterDefaultKernel: String = "",
    val autoConnect: Boolean = true,
    val pythonInterpreter: String = "",
    val restoreSession: Boolean = true,
    /** Without a guest, reopen the last folder's tabs from this device's copies. */
    val offlineWorkspace: Boolean = true,
    /** Enter keeps the indentation (one level more after a block opener). */
    val autoIndent: Boolean = true,
    /** Backspace in leading spaces goes back to the previous tab stop (one indentation level). */
    val useTabStops: Boolean = true,
    val notebookCopilot: Boolean = true,
    /** `avf.notebook.languageServer`: problems, hover and go to definition in code cells, from the Python language server. */
    val notebookLanguageServer: Boolean = true,
    /** Draw Plotly figures; needs plotly.js from cdn.plot.ly the first time. */
    val renderPlotly: Boolean = true,
    /** ipywidgets drawn live (sliders, dropdowns, interact()); off shows their text form. */
    val liveWidgets: Boolean = true,
    /** `text/latex` outputs (SymPy, IPython's Math) drawn with KaTeX; needs it from jsDelivr the first time. */
    val renderLatex: Boolean = true,
    /** Python kernels let `display(fig, display_id=True)` update Plotly figures in place. */
    val plotlyDisplayIds: Boolean = true,
    val statusBarGuestResources: Boolean = true,
    /** Script-free `text/html` outputs (pandas tables) as HTML rather than their text form. */
    val renderHtmlOutputs: Boolean = true,
    /** Jupyter-style Tab after a name or dot asks the kernel for completions (Ctrl+Space always does). */
    val notebookTabCompletion: Boolean = true,
    /** A tall cell keeps its run button, first line and actions at the top while it scrolls. */
    val stickyCellHeader: Boolean = true,
    /** Each code cell's run time and how it ended (done, running, queued, failed) in its gutter. */
    val cellStatistics: Boolean = true,
    /** Save each run's start and end in the cell's metadata (JupyterLab's `execution`), so they show on reopen. */
    val recordTiming: Boolean = false,
    /** Outputs that need JavaScript (Altair/Vega, Bokeh, folium) in an isolated WebView, as VS Code runs them. */
    val scriptOutputs: Boolean = true,
    /** DataFrame outputs as native, sortable tables rather than their HTML. */
    val nativeTables: Boolean = true,
    /** Text outputs longer than this scroll in a box until expanded (VS Code's default is 30). */
    val outputLineLimit: Int = 30,
    /** Copilot proposes the next change anywhere in the file after an edit (VS Code's NES). */
    val nextEditSuggestions: Boolean = true,
    /** A tap on a plot (image or SVG output) opens it in the plot viewer; its menu always can. */
    val plotViewerOnTap: Boolean = true,
    /** Run Cell, Run Above and Run Below on the `# %%` lines of Python files. */
    val interactiveCodeLens: Boolean = true,
    /** Esc, Tab, Ctrl, Alt, Shift and arrow keys in a bar above the on-screen keyboard. */
    val keyBar: Boolean = true,
    /** Problem counts beside Outline entries and its "Problems only" filter. */
    val outlineProblems: Boolean = true,
    /** Squiggles under problems in the editor and their marks beside the scrollbar. */
    val problemUnderlines: Boolean = true,
    /** Code the server tags unnecessary (unused imports, variables) is drawn faded. */
    val showUnused: Boolean = true,
    /** Code the server tags deprecated is struck through. */
    val showDeprecated: Boolean = true,
    /** Extra Ruff rule sets on top of the project's own configuration. */
    val pythonLintRules: PythonLintRules = PythonLintRules.Default,
    /** How strict ty or basedpyright check types; Default leaves the server's (or project's) level. */
    val pythonTypeChecking: PythonTypeChecking = PythonTypeChecking.Default,
    /** Explorer folders open in place as a tree; off goes into a folder one level at a time. */
    val explorerTree: Boolean = true,
    /** A single tap opens a file in a preview tab that the next one replaces (`workbench.editor.enablePreview`). */
    val enablePreview: Boolean = true,
    /** SVG files open drawn, with their source a tap away; off opens them as text. */
    val svgAsImage: Boolean = true,
    /** A server starting in the guest is announced in the status bar (`remote.autoForwardPortsNotify`). */
    val portNotifications: Boolean = true,
    /** Files without colours: offer their VS Code grammar from Open VSX, fetch it at once, or leave them plain. */
    val languageDownloads: LanguageDownloads = LanguageDownloads.Ask,
    /** Explorer colours and letters for changed files (`git.decorations.enabled`). */
    val gitDecorations: Boolean = true,
    /** Explorer colours and counts for files with problems (`problems.decorations.enabled`). */
    val problemDecorations: Boolean = true,
    /** The launcher's long-press menu lists the recent folders. */
    val launcherShortcuts: Boolean = true,
    /** Reference counts above functions and classes, from the language server (`editor.codeLens`). */
    val codeLens: Boolean = true,
    /** Renaming an HTML tag renames its closing tag too (`editor.linkedEditing`). */
    val linkedEditing: Boolean = true,
    /** Ask before deleting from Explorer (`explorer.confirmDelete`); Undo is offered either way. */
    val confirmDelete: Boolean = true,
    /** A folder holding only one folder shows as one row, `src/main/java` (`explorer.compactFolders`). */
    val compactFolders: Boolean = true,
    /** The open tabs listed above the files in Explorer (`explorer.openEditors.visible`). */
    val openEditors: Boolean = true,
    /** Long-press and drag files onto folders to move them (`explorer.enableDragAndDrop`). */
    val explorerDragAndDrop: Boolean = true,
    /** Names Explorer and Quick Open hide (`files.exclude`, glob → true). */
    val filesExclude: List<String> = DEFAULT_FILES_EXCLUDE,
    /** File icons in Explorer, tabs and Open Editors (`workbench.iconTheme`): VS Code's Seti, an imported theme's id, or "none" for plain ones. */
    val iconTheme: String = "vs-seti",
    /** Fold arrows beside the line numbers and the fold commands (`editor.folding`). */
    val folding: Boolean = true,
    /** A short buzz for a breakpoint, a latched modifier, a long-press drag (`avf.haptics`). */
    val haptics: Boolean = true,
) {
    companion object {
        const val THEME_MODE = "avf.theme.mode"
        const val DYNAMIC_COLOR = "avf.theme.dynamicColor"
        const val PURE_BLACK = "avf.theme.pureBlack"
        const val SYNTAX_COLORS = "avf.theme.syntaxColors"
        const val EDITOR_FONT_SIZE = "editor.fontSize"
        const val TAB_SIZE = "editor.tabSize"
        const val INSERT_SPACES = "editor.insertSpaces"
        const val WORD_WRAP = "editor.wordWrap"
        const val LINE_NUMBERS = "editor.lineNumbers"
        const val FORMAT_ON_SAVE = "editor.formatOnSave"
        const val FORMAT_ON_PASTE = "editor.formatOnPaste"
        const val TRIM_TRAILING_WHITESPACE = "files.trimTrailingWhitespace"
        const val INSERT_FINAL_NEWLINE = "files.insertFinalNewline"
        const val TRIM_FINAL_NEWLINES = "files.trimFinalNewlines"
        const val PARAMETER_HINTS = "editor.parameterHints.enabled"
        /** VS Code's enum (off/singleFile/multiFile, a boolean before 1.84); only off/false turns it off. */
        const val OCCURRENCES_HIGHLIGHT = "editor.occurrencesHighlight"
        const val QUICK_SUGGESTIONS = "editor.quickSuggestions"
        const val KEYBOARD_SUGGESTION_LIST = "avf.aardink.keyboardSuggestionList"
        const val GIT_BLAME = "git.blame.editorDecoration.enabled"
        const val OTHER_LANGUAGE_SERVERS = "avf.languageServers.others"
        const val SNIPPET_SUGGESTIONS = "editor.snippetSuggestions"
        const val SQL_STYLE_RULES = "avf.sql.styleRules"
        const val DEBUG_INLINE_VALUES = "debug.inlineValues"
        const val FIND_SEED = "editor.find.seedSearchStringFromSelection"
        const val PLOT_VIEWER_ON_TAP = "avf.notebook.plotViewerOnTap"
        /** VS Code's enum (on/off/onUnlessPressed/offUnlessPressed); there is no key to press here, so off* is off. */
        const val INLAY_HINTS = "editor.inlayHints.enabled"
        const val PINCH_TO_ZOOM = "avf.editor.pinchToZoom"
        const val MOUSE_WHEEL_ZOOM = "editor.mouseWheelZoom"
        const val NOTEBOOK_LANGUAGE_SERVER = "avf.notebook.languageServer"
        const val TOOLTIPS = "avf.workbench.tooltips"
        const val EDITOR_HOVER = "editor.hover.enabled"
        const val EDITOR_HOVER_DELAY = "editor.hover.delay"
        const val EDITOR_HOVER_HIDING_DELAY = "editor.hover.hidingDelay"
        const val MARKDOWN_SCROLL_SYNC = "markdown.preview.scrollPreviewWithEditor"
        const val AUTO_SAVE = "files.autoSave"
        const val AUTO_SAVE_DELAY = "files.autoSaveDelay"
        const val SEMANTIC_HIGHLIGHTING = "editor.semanticHighlighting.enabled"
        const val TERMINAL_FONT_SIZE = "terminal.integrated.fontSize"
        const val TERMINAL_DEFAULT_PROFILE = "terminal.integrated.defaultProfile.linux"
        const val TERMINAL_PROFILES = "terminal.integrated.profiles.linux"
        const val SHELL_INTEGRATION = "terminal.integrated.shellIntegration.enabled"
        const val TERMINAL_COPY_ON_SELECTION = "terminal.integrated.copyOnSelection"
        const val PYTHON_LANGUAGE_SERVER = "avf.python.languageServer"
        const val TYPESCRIPT_LANGUAGE_SERVER = "avf.typescript.languageServer"
        const val LANGUAGE_SERVER_IDLE = "avf.languageServers.idleStopSeconds"
        const val INLINE_SUGGEST_DELAY = "avf.copilot.inlineSuggestDelayMs"
        const val CHAT_IDLE_MINUTES = "avf.copilot.chatIdleMinutes"
        const val CHAT_AGENT = "avf.chat.agent"
        const val CHAT_REASONING_EFFORT = "avf.copilot.chatReasoningEffort"
        const val CHAT_RESUME = "avf.copilot.chatResume"
        /** The levels Copilot CLI 1.0.105 accepts for `--reasoning-effort`. */
        val REASONING_EFFORTS = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")
        const val JUPYTER_DEFAULT_KERNEL = "avf.jupyter.defaultKernel"
        const val AUTO_CONNECT = "avf.connection.autoConnect"
        const val PYTHON_INTERPRETER = "python.defaultInterpreterPath"
        const val RESTORE_SESSION = "avf.session.restore"
        const val OFFLINE_WORKSPACE = "avf.offline.openLastFolder"
        /** VS Code's enum (none/keep/brackets/advanced/full); anything but "none" is on here. */
        const val AUTO_INDENT = "editor.autoIndent"
        const val USE_TAB_STOPS = "editor.useTabStops"
        const val NOTEBOOK_COPILOT = "avf.copilot.notebooks"
        const val RENDER_PLOTLY = "avf.notebook.plotly"
        const val LIVE_WIDGETS = "avf.notebook.widgets"
        const val RENDER_LATEX = "avf.notebook.latex"
        const val KEY_BAR = "avf.keyBar.enabled"
        const val NEXT_EDIT_SUGGESTIONS = "github.copilot.nextEditSuggestions.enabled"
        const val INTERACTIVE_CODE_LENS = "jupyter.interactiveWindow.codeLens.enable"
        const val PLOTLY_DISPLAY_IDS = "avf.notebook.plotlyDisplayIds"
        const val STATUS_BAR_GUEST = "avf.statusBar.guestResources"
        const val RENDER_HTML = "avf.notebook.htmlOutputs"
        const val NOTEBOOK_TAB_COMPLETION = "avf.notebook.tabCompletion"
        const val STICKY_CELL_HEADER = "avf.notebook.stickyCellHeader"
        const val CELL_STATISTICS = "avf.notebook.cellStatistics"
        const val RECORD_TIMING = "avf.notebook.recordTiming"
        const val NATIVE_TABLES = "avf.notebook.nativeTables"
        const val SCRIPT_OUTPUTS = "avf.notebook.scriptOutputs"
        const val OUTPUT_LINE_LIMIT = "notebook.output.textLineLimit"
        const val OUTLINE_PROBLEMS = "outline.problems.enabled"
        /** VS Code's enum (editable/on/off); only "off" hides them. */
        const val PROBLEM_UNDERLINES = "editor.renderValidationDecorations"
        const val SHOW_UNUSED = "editor.showUnused"
        const val SHOW_DEPRECATED = "editor.showDeprecated"
        const val PYTHON_LINT_RULES = "avf.python.lintRules"
        const val PYTHON_TYPE_CHECKING = "avf.python.typeChecking"
        const val EXPLORER_TREE = "avf.explorer.tree"
        const val ENABLE_PREVIEW = "workbench.editor.enablePreview"
        const val SVG_AS_IMAGE = "avf.svg.openAsImage"
        const val PORT_NOTIFICATIONS = "remote.autoForwardPortsNotify"
        const val LANGUAGE_DOWNLOADS = "avf.languages.download"
        const val GIT_DECORATIONS = "git.decorations.enabled"
        const val PROBLEM_DECORATIONS = "problems.decorations.enabled"
        const val LAUNCHER_SHORTCUTS = "avf.launcher.recentFolders"
        const val DETECT_INDENTATION = "editor.detectIndentation"
        const val CODE_LENS = "editor.codeLens"
        const val LINKED_EDITING = "editor.linkedEditing"
        const val CONFIRM_DELETE = "explorer.confirmDelete"
        const val COMPACT_FOLDERS = "explorer.compactFolders"
        /** VS Code takes a count (0 hides the section); a boolean works here too. */
        const val OPEN_EDITORS = "explorer.openEditors.visible"
        const val EXPLORER_DRAG_AND_DROP = "explorer.enableDragAndDrop"
        const val FILES_EXCLUDE = "files.exclude"
        const val HAPTICS = "avf.haptics"
        const val FOLDING = "editor.folding"
        const val ICON_THEME = "workbench.iconTheme"
        /** VS Code's defaults for `files.exclude`. */
        val DEFAULT_FILES_EXCLUDE = listOf("**/.git", "**/.svn", "**/.hg", "**/CVS", "**/.DS_Store", "**/Thumbs.db")

        fun from(settings: JsonObject): WorkbenchSettings {
            val defaults = WorkbenchSettings()
            fun string(key: String) = settings.getPath(key)?.takeIf { it.isJsonPrimitive }?.asString
            fun bool(key: String, fallback: Boolean) =
                settings.getPath(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asBoolean }.getOrNull() }
                    ?: fallback
            fun number(key: String) =
                settings.getPath(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asNumber
            return WorkbenchSettings(
                themeMode = ThemeMode.entries.firstOrNull { it.key == string(THEME_MODE) } ?: defaults.themeMode,
                dynamicColor = bool(DYNAMIC_COLOR, defaults.dynamicColor),
                pureBlack = bool(PURE_BLACK, defaults.pureBlack),
                syntaxColors = SyntaxColors.entries.firstOrNull { it.key == string(SYNTAX_COLORS) } ?: defaults.syntaxColors,
                editorFontSize = number(EDITOR_FONT_SIZE)?.toFloat()?.coerceIn(8f, 40f) ?: defaults.editorFontSize,
                tabSize = number(TAB_SIZE)?.toInt()?.coerceIn(1, 8) ?: defaults.tabSize,
                insertSpaces = bool(INSERT_SPACES, defaults.insertSpaces),
                wordWrap = string(WORD_WRAP)?.let { it == "on" } ?: defaults.wordWrap,
                lineNumbers = string(LINE_NUMBERS)?.let { it != "off" } ?: defaults.lineNumbers,
                formatOnSave = bool(FORMAT_ON_SAVE, defaults.formatOnSave),
                formatOnPaste = bool(FORMAT_ON_PASTE, defaults.formatOnPaste),
                trimTrailingWhitespace = bool(TRIM_TRAILING_WHITESPACE, defaults.trimTrailingWhitespace),
                insertFinalNewline = bool(INSERT_FINAL_NEWLINE, defaults.insertFinalNewline),
                trimFinalNewlines = bool(TRIM_FINAL_NEWLINES, defaults.trimFinalNewlines),
                parameterHints = bool(PARAMETER_HINTS, defaults.parameterHints),
                quickSuggestions = bool(QUICK_SUGGESTIONS, defaults.quickSuggestions),
                keyboardSuggestionList = bool(KEYBOARD_SUGGESTION_LIST, defaults.keyboardSuggestionList),
                gitBlame = bool(GIT_BLAME, defaults.gitBlame),
                otherLanguageServers = bool(OTHER_LANGUAGE_SERVERS, defaults.otherLanguageServers),
                snippetSuggestions = SnippetSuggestions.entries.firstOrNull { it.key == string(SNIPPET_SUGGESTIONS) } ?: defaults.snippetSuggestions,
                sqlStyleRules = SqlStyleRules.entries.firstOrNull { it.key == string(SQL_STYLE_RULES) } ?: defaults.sqlStyleRules,
                // VS Code's values are "on", "off" and "auto"; the app has a provider for every language, so auto is on.
                debugInlineValues = settings.getPath(DEBUG_INLINE_VALUES)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                    ?.asString?.let { it != "off" } ?: bool(DEBUG_INLINE_VALUES, defaults.debugInlineValues),
                findSeed = string(FIND_SEED)?.takeIf { it in setOf("always", "selection", "never") } ?: defaults.findSeed,
                plotViewerOnTap = bool(PLOT_VIEWER_ON_TAP, defaults.plotViewerOnTap),
                occurrencesHighlight = string(OCCURRENCES_HIGHLIGHT)?.let { it != "off" && it != "false" } ?: defaults.occurrencesHighlight,
                inlayHints = string(INLAY_HINTS)?.let { !it.startsWith("off") && it != "false" } ?: defaults.inlayHints,
                pinchToZoom = bool(PINCH_TO_ZOOM, defaults.pinchToZoom),
                mouseWheelZoom = bool(MOUSE_WHEEL_ZOOM, defaults.mouseWheelZoom),
                tooltips = bool(TOOLTIPS, defaults.tooltips),
                editorHover = bool(EDITOR_HOVER, defaults.editorHover),
                editorHoverDelay = number(EDITOR_HOVER_DELAY)?.toInt()?.coerceIn(0, 5000) ?: defaults.editorHoverDelay,
                editorHoverHidingDelay = number(EDITOR_HOVER_HIDING_DELAY)?.toInt()?.coerceIn(0, 5000) ?: defaults.editorHoverHidingDelay,
                markdownScrollSync = bool(MARKDOWN_SCROLL_SYNC, defaults.markdownScrollSync),
                autoSave = AutoSaveMode.entries.firstOrNull { it.key == string(AUTO_SAVE) } ?: defaults.autoSave,
                autoSaveDelayMs = number(AUTO_SAVE_DELAY)?.toInt()?.coerceIn(200, 60_000) ?: defaults.autoSaveDelayMs,
                semanticHighlighting = bool(SEMANTIC_HIGHLIGHTING, defaults.semanticHighlighting),
                terminalFontSize = number(TERMINAL_FONT_SIZE)?.toFloat()?.coerceIn(6f, 30f) ?: defaults.terminalFontSize,
                terminalDefaultProfile = string(TERMINAL_DEFAULT_PROFILE)?.takeIf { it.isNotBlank() } ?: defaults.terminalDefaultProfile,
                terminalProfiles = terminalProfiles(settings.getPath(TERMINAL_PROFILES)?.takeIf { it.isJsonObject }?.asJsonObject),
                shellIntegration = bool(SHELL_INTEGRATION, defaults.shellIntegration),
                terminalCopyOnSelection = bool(TERMINAL_COPY_ON_SELECTION, defaults.terminalCopyOnSelection),
                pythonLanguageServer = string(PYTHON_LANGUAGE_SERVER)?.takeIf { it in setOf("ty", "basedpyright") }
                    ?: defaults.pythonLanguageServer,
                typescriptLanguageServer = string(TYPESCRIPT_LANGUAGE_SERVER)?.takeIf { it in setOf("typescript", "vtsls") }
                    ?: defaults.typescriptLanguageServer,
                languageServerIdleSeconds = number(LANGUAGE_SERVER_IDLE)?.toInt()?.coerceIn(10, 3600)
                    ?: defaults.languageServerIdleSeconds,
                inlineSuggestDelayMs = number(INLINE_SUGGEST_DELAY)?.toInt()?.coerceIn(0, 5000)
                    ?: defaults.inlineSuggestDelayMs,
                chatIdleMinutes = number(CHAT_IDLE_MINUTES)?.toInt()?.coerceIn(1, 120) ?: defaults.chatIdleMinutes,
                chatAgent = ChatAgentKind.entries.firstOrNull { it.key == string(CHAT_AGENT) } ?: defaults.chatAgent,
                chatReasoningEffort = string(CHAT_REASONING_EFFORT)?.takeIf { it in REASONING_EFFORTS } ?: defaults.chatReasoningEffort,
                chatResume = bool(CHAT_RESUME, defaults.chatResume),
                jupyterDefaultKernel = string(JUPYTER_DEFAULT_KERNEL) ?: defaults.jupyterDefaultKernel,
                autoConnect = bool(AUTO_CONNECT, defaults.autoConnect),
                pythonInterpreter = string(PYTHON_INTERPRETER)?.trim() ?: defaults.pythonInterpreter,
                restoreSession = bool(RESTORE_SESSION, defaults.restoreSession),
                offlineWorkspace = bool(OFFLINE_WORKSPACE, defaults.offlineWorkspace),
                autoIndent = string(AUTO_INDENT)?.let { it != "none" } ?: defaults.autoIndent,
                useTabStops = bool(USE_TAB_STOPS, defaults.useTabStops),
                notebookCopilot = bool(NOTEBOOK_COPILOT, defaults.notebookCopilot),
                notebookLanguageServer = bool(NOTEBOOK_LANGUAGE_SERVER, defaults.notebookLanguageServer),
                renderPlotly = bool(RENDER_PLOTLY, defaults.renderPlotly),
                liveWidgets = bool(LIVE_WIDGETS, defaults.liveWidgets),
                renderLatex = bool(RENDER_LATEX, defaults.renderLatex),
                keyBar = bool(KEY_BAR, defaults.keyBar),
                nextEditSuggestions = bool(NEXT_EDIT_SUGGESTIONS, defaults.nextEditSuggestions),
                interactiveCodeLens = bool(INTERACTIVE_CODE_LENS, defaults.interactiveCodeLens),
                plotlyDisplayIds = bool(PLOTLY_DISPLAY_IDS, defaults.plotlyDisplayIds),
                statusBarGuestResources = bool(STATUS_BAR_GUEST, defaults.statusBarGuestResources),
                renderHtmlOutputs = bool(RENDER_HTML, defaults.renderHtmlOutputs),
                notebookTabCompletion = bool(NOTEBOOK_TAB_COMPLETION, defaults.notebookTabCompletion),
                stickyCellHeader = bool(STICKY_CELL_HEADER, defaults.stickyCellHeader),
                cellStatistics = bool(CELL_STATISTICS, defaults.cellStatistics),
                recordTiming = bool(RECORD_TIMING, defaults.recordTiming),
                nativeTables = bool(NATIVE_TABLES, defaults.nativeTables),
                scriptOutputs = bool(SCRIPT_OUTPUTS, defaults.scriptOutputs),
                outputLineLimit = number(OUTPUT_LINE_LIMIT)?.toInt()?.coerceIn(5, 10_000) ?: defaults.outputLineLimit,
                outlineProblems = bool(OUTLINE_PROBLEMS, defaults.outlineProblems),
                problemUnderlines = string(PROBLEM_UNDERLINES)?.let { it != "off" } ?: defaults.problemUnderlines,
                showUnused = bool(SHOW_UNUSED, defaults.showUnused),
                showDeprecated = bool(SHOW_DEPRECATED, defaults.showDeprecated),
                pythonLintRules = PythonLintRules.entries.firstOrNull { it.key == string(PYTHON_LINT_RULES) }
                    ?: defaults.pythonLintRules,
                pythonTypeChecking = PythonTypeChecking.entries.firstOrNull { it.key == string(PYTHON_TYPE_CHECKING) }
                    ?: defaults.pythonTypeChecking,
                explorerTree = bool(EXPLORER_TREE, defaults.explorerTree),
                enablePreview = bool(ENABLE_PREVIEW, defaults.enablePreview),
                svgAsImage = bool(SVG_AS_IMAGE, defaults.svgAsImage),
                portNotifications = bool(PORT_NOTIFICATIONS, defaults.portNotifications),
                languageDownloads = LanguageDownloads.entries.firstOrNull { it.key == string(LANGUAGE_DOWNLOADS) } ?: defaults.languageDownloads,
                gitDecorations = bool(GIT_DECORATIONS, defaults.gitDecorations),
                problemDecorations = bool(PROBLEM_DECORATIONS, defaults.problemDecorations),
                launcherShortcuts = bool(LAUNCHER_SHORTCUTS, defaults.launcherShortcuts),
                detectIndentation = bool(DETECT_INDENTATION, defaults.detectIndentation),
                codeLens = bool(CODE_LENS, defaults.codeLens),
                linkedEditing = bool(LINKED_EDITING, defaults.linkedEditing),
                confirmDelete = bool(CONFIRM_DELETE, defaults.confirmDelete),
                compactFolders = bool(COMPACT_FOLDERS, defaults.compactFolders),
                openEditors = settings.getPath(OPEN_EDITORS)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive?.let { value ->
                    if (value.isNumber) value.asInt > 0 else runCatching { value.asBoolean }.getOrNull()
                } ?: defaults.openEditors,
                explorerDragAndDrop = bool(EXPLORER_DRAG_AND_DROP, defaults.explorerDragAndDrop),
                filesExclude = settings.getPath(FILES_EXCLUDE)?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()
                    ?.filter { (_, value) -> value.isJsonPrimitive && runCatching { value.asBoolean }.getOrDefault(false) }
                    ?.map { it.key } ?: defaults.filesExclude,
                haptics = bool(HAPTICS, defaults.haptics),
                folding = bool(FOLDING, defaults.folding),
                // VS Code writes null for no file icons.
                iconTheme = settings.getPath(ICON_THEME)?.let { if (it.isJsonNull) "none" else it.takeIf { v -> v.isJsonPrimitive }?.asString }
                    ?.takeIf { it.isNotBlank() } ?: defaults.iconTheme,
            )
        }
    }
}

/** A readable name for a chat option value without one, e.g. a mode URL ending in `#agent`. */
internal fun chatValueLabel(value: String): String =
    value.substringAfterLast('#').substringAfterLast('/').replaceFirstChar { it.uppercase() }

/** How a `--reasoning-effort` level reads in the UI; empty is the CLI's own default. */
fun reasoningEffortLabel(effort: String): String = when (effort) {
    "" -> "Default"
    "xhigh" -> "Extra high"
    else -> effort.replaceFirstChar { it.uppercase() }
}

/** Ruff rule sets added with `lint.extendSelect`; the project's `ruff.toml`/`pyproject.toml` still applies. */
enum class PythonLintRules(val key: String, val label: String, val extendSelect: List<String>) {
    Default("default", "Default", emptyList()),
    /** Likely bugs, outdated syntax, simplifications, import order, comprehensions, Ruff's own. */
    Extended("extended", "Extended", listOf("B", "UP", "SIM", "I", "C4", "RUF")),
    All("all", "All rules", listOf("ALL")),
}

/** One strictness picker for both Python servers. */
enum class PythonTypeChecking(val key: String, val label: String, val basedpyrightMode: String?, val tyRulesLevel: String?) {
    Default("default", "Default", null, null),
    Off("off", "Off", "off", "ignore"),
    Strict("strict", "Strict", "strict", "error"),
}

/** `avf.languages.download`: what happens when a file's language has no colours yet. */
enum class LanguageDownloads(val key: String, val label: String) {
    Ask("ask", "Ask"),
    Always("always", "Always"),
    Never("never", "Never"),
}

/** VS Code's `files.autoSave` values. */
enum class AutoSaveMode(val key: String, val label: String) {
    Off("off", "Off"),
    AfterDelay("afterDelay", "After delay"),
    /** When the editor loses focus: another tab, a panel, or leaving the app. */
    OnFocusChange("onFocusChange", "Focus change"),
    /** When the app goes to the background. */
    OnWindowChange("onWindowChange", "Leaving app"),
}

/** Reads `a.b.c` from nested objects, or from a literal dotted key as VS Code allows. */
fun JsonObject.getPath(path: String): JsonElement? {
    get(path)?.let { return it }
    var current: JsonObject = this
    val parts = path.split('.')
    parts.forEachIndexed { index, part ->
        val value = current.get(part) ?: return null
        if (index == parts.lastIndex) return value
        current = value.takeIf { it.isJsonObject }?.asJsonObject ?: return null
    }
    return null
}

/** Writes `a.b.c` as nested objects, replacing any literal dotted key for the same setting. */
fun JsonObject.withPath(path: String, value: Any?): JsonObject {
    val copy = deepCopy()
    copy.remove(path)
    val parts = path.split('.')
    var current = copy
    parts.dropLast(1).forEach { part ->
        val child = current.get(part)?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject().also { current.add(part, it) }
        current = child
    }
    when (value) {
        null -> current.remove(parts.last())
        is Boolean -> current.add(parts.last(), JsonPrimitive(value))
        is Number -> current.add(parts.last(), JsonPrimitive(value))
        is String -> current.add(parts.last(), JsonPrimitive(value))
        is JsonElement -> current.add(parts.last(), value)
        else -> error("Unsupported settings value: ${value::class.java.name}")
    }
    return copy
}

/** A shell a terminal can start (VS Code's terminal profile): [path] with [args]. */
data class TerminalProfile(val name: String, val path: String, val args: List<String> = emptyList())

/** The shells offered without settings; `terminal.integrated.profiles.linux` adds to them. */
val BUILT_IN_TERMINAL_PROFILES = listOf(
    TerminalProfile("bash", "bash"),
    TerminalProfile("zsh", "zsh"),
    TerminalProfile("fish", "fish"),
    TerminalProfile("sh", "sh"),
    TerminalProfile("tmux", "tmux", listOf("new-session", "-A", "-s", "main")),
)

/** VS Code's `terminal.integrated.profiles.linux`: `{"name": {"path": …, "args": [...]}}`, `null` hides a built-in. */
internal fun terminalProfiles(settings: JsonObject?): List<TerminalProfile> {
    if (settings == null) return BUILT_IN_TERMINAL_PROFILES
    val profiles = BUILT_IN_TERMINAL_PROFILES.associateBy { it.name }.toMutableMap()
    settings.entrySet().forEach { (name, value) ->
        if (value.isJsonNull) {
            profiles.remove(name)
            return@forEach
        }
        val profile = value.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
        val path = profile.get("path")?.let { path ->
            when {
                path.isJsonPrimitive -> path.asString
                path.isJsonArray -> path.asJsonArray.firstOrNull()?.takeIf { it.isJsonPrimitive }?.asString
                else -> null
            }
        } ?: profiles[name]?.path ?: return@forEach
        val args = profile.get("args")?.let { args ->
            when {
                args.isJsonArray -> args.asJsonArray.mapNotNull { it.takeIf { a -> a.isJsonPrimitive }?.asString }
                args.isJsonPrimitive -> listOf(args.asString)
                else -> null
            }
        } ?: emptyList()
        profiles[name] = TerminalProfile(name, path, args)
    }
    return profiles.values.toList()
}
