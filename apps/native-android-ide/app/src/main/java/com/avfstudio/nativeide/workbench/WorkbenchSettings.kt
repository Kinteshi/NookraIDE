package com.avfstudio.nativeide.workbench

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
    val wordWrap: Boolean = false,
    val lineNumbers: Boolean = true,
    val formatOnSave: Boolean = false,
    val autoSave: AutoSaveMode = AutoSaveMode.Off,
    val autoSaveDelayMs: Int = 1000,
    val semanticHighlighting: Boolean = true,
    val terminalFontSize: Float = 13f,
    val pythonLanguageServer: String = "ty",
    val languageServerIdleSeconds: Int = 60,
    val inlineSuggestDelayMs: Int = 350,
    val chatIdleMinutes: Int = 5,
    val jupyterDefaultKernel: String = "",
    val autoConnect: Boolean = true,
    val pythonInterpreter: String = "",
    val restoreSession: Boolean = true,
    /** Enter keeps the indentation (one level more after a block opener). */
    val autoIndent: Boolean = true,
    /** Backspace in leading spaces goes back to the previous tab stop (one indentation level). */
    val useTabStops: Boolean = true,
    val notebookCopilot: Boolean = true,
    /** Draw Plotly figures; needs plotly.js from cdn.plot.ly the first time. */
    val renderPlotly: Boolean = true,
    val statusBarGuestResources: Boolean = true,
    /** Script-free `text/html` outputs (pandas tables) as HTML rather than their text form. */
    val renderHtmlOutputs: Boolean = true,
    /** Jupyter-style Tab after a name or dot asks the kernel for completions (Ctrl+Space always does). */
    val notebookTabCompletion: Boolean = true,
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
        const val AUTO_SAVE = "files.autoSave"
        const val AUTO_SAVE_DELAY = "files.autoSaveDelay"
        const val SEMANTIC_HIGHLIGHTING = "editor.semanticHighlighting.enabled"
        const val TERMINAL_FONT_SIZE = "terminal.integrated.fontSize"
        const val PYTHON_LANGUAGE_SERVER = "avf.python.languageServer"
        const val LANGUAGE_SERVER_IDLE = "avf.languageServers.idleStopSeconds"
        const val INLINE_SUGGEST_DELAY = "avf.copilot.inlineSuggestDelayMs"
        const val CHAT_IDLE_MINUTES = "avf.copilot.chatIdleMinutes"
        const val JUPYTER_DEFAULT_KERNEL = "avf.jupyter.defaultKernel"
        const val AUTO_CONNECT = "avf.connection.autoConnect"
        const val PYTHON_INTERPRETER = "python.defaultInterpreterPath"
        const val RESTORE_SESSION = "avf.session.restore"
        /** VS Code's enum (none/keep/brackets/advanced/full); anything but "none" is on here. */
        const val AUTO_INDENT = "editor.autoIndent"
        const val USE_TAB_STOPS = "editor.useTabStops"
        const val NOTEBOOK_COPILOT = "avf.copilot.notebooks"
        const val RENDER_PLOTLY = "avf.notebook.plotly"
        const val STATUS_BAR_GUEST = "avf.statusBar.guestResources"
        const val RENDER_HTML = "avf.notebook.htmlOutputs"
        const val NOTEBOOK_TAB_COMPLETION = "avf.notebook.tabCompletion"

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
                autoSave = AutoSaveMode.entries.firstOrNull { it.key == string(AUTO_SAVE) } ?: defaults.autoSave,
                autoSaveDelayMs = number(AUTO_SAVE_DELAY)?.toInt()?.coerceIn(200, 60_000) ?: defaults.autoSaveDelayMs,
                semanticHighlighting = bool(SEMANTIC_HIGHLIGHTING, defaults.semanticHighlighting),
                terminalFontSize = number(TERMINAL_FONT_SIZE)?.toFloat()?.coerceIn(6f, 30f) ?: defaults.terminalFontSize,
                pythonLanguageServer = string(PYTHON_LANGUAGE_SERVER)?.takeIf { it in setOf("ty", "basedpyright") }
                    ?: defaults.pythonLanguageServer,
                languageServerIdleSeconds = number(LANGUAGE_SERVER_IDLE)?.toInt()?.coerceIn(10, 3600)
                    ?: defaults.languageServerIdleSeconds,
                inlineSuggestDelayMs = number(INLINE_SUGGEST_DELAY)?.toInt()?.coerceIn(0, 5000)
                    ?: defaults.inlineSuggestDelayMs,
                chatIdleMinutes = number(CHAT_IDLE_MINUTES)?.toInt()?.coerceIn(1, 120) ?: defaults.chatIdleMinutes,
                jupyterDefaultKernel = string(JUPYTER_DEFAULT_KERNEL) ?: defaults.jupyterDefaultKernel,
                autoConnect = bool(AUTO_CONNECT, defaults.autoConnect),
                pythonInterpreter = string(PYTHON_INTERPRETER)?.trim() ?: defaults.pythonInterpreter,
                restoreSession = bool(RESTORE_SESSION, defaults.restoreSession),
                autoIndent = string(AUTO_INDENT)?.let { it != "none" } ?: defaults.autoIndent,
                useTabStops = bool(USE_TAB_STOPS, defaults.useTabStops),
                notebookCopilot = bool(NOTEBOOK_COPILOT, defaults.notebookCopilot),
                renderPlotly = bool(RENDER_PLOTLY, defaults.renderPlotly),
                statusBarGuestResources = bool(STATUS_BAR_GUEST, defaults.statusBarGuestResources),
                renderHtmlOutputs = bool(RENDER_HTML, defaults.renderHtmlOutputs),
                notebookTabCompletion = bool(NOTEBOOK_TAB_COMPLETION, defaults.notebookTabCompletion),
            )
        }
    }
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
