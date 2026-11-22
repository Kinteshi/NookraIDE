package io.github.kinteshi.nookraide.workbench

import com.google.gson.JsonObject
import io.github.kinteshi.nookraide.notebook.CellType
import io.github.kinteshi.nookraide.notebook.NotebookCell
import io.github.kinteshi.nookraide.notebook.NotebookDocument
import io.github.kinteshi.nookraide.notebook.OutputView
import io.github.kinteshi.nookraide.transport.FileEntry

internal fun gitStatusWord(letter: String?): String? = when (letter) {
    "M" -> "Modified"
    "A" -> "Added"
    "D" -> "Deleted"
    "R" -> "Renamed"
    "C" -> "Copied"
    "U" -> "Untracked"
    "!" -> "Conflicted"
    else -> null
}

internal fun explorerRowDescription(
    entry: FileEntry,
    label: String = entry.name,
    expanded: Boolean,
    tree: Boolean,
    selected: Boolean,
    active: Boolean,
    keptOffline: Boolean,
    mark: FileDecoration?,
): String = buildList {
    val kind = when {
        entry.isDirectory -> "Folder"
        entry.name.endsWith(".ipynb", ignoreCase = true) -> "Notebook"
        else -> "File"
    }
    add("$kind $label")
    if (entry.isDirectory && tree) add(if (expanded) "expanded" else "collapsed")
    if (keptOffline) add("kept offline")
    if (active) add("active")
    if (selected) add("selected")
    mark?.let { m ->
        if (m.problems > 0) add("${m.problems} ${if (m.problems == 1) "problem" else "problems"}")
        gitStatusWord(m.gitLetter)?.let(::add)
    }
}.joinToString(", ")

internal fun openEditorRowDescription(
    name: String,
    folder: String,
    active: Boolean,
    dirty: Boolean,
    deleted: Boolean,
): String = buildList {
    add(name)
    if (folder.isNotEmpty()) add("in $folder")
    if (active) add("active")
    if (dirty) add("unsaved changes")
    if (deleted) add("deleted")
}.joinToString(", ")

internal fun editorTabDescription(
    name: String,
    selected: Boolean,
    dirty: Boolean,
    preview: Boolean,
    pinned: Boolean,
    deleted: Boolean,
): String = buildList {
    add(name)
    add(if (selected) "active tab" else "tab")
    if (preview) add("preview")
    if (pinned) add("pinned")
    if (dirty) add("unsaved changes")
    if (deleted) add("deleted")
}.joinToString(", ")

internal fun editorTabActionDescription(name: String, dirty: Boolean, pinned: Boolean): String = when {
    pinned -> "Unpin $name"
    dirty -> "Close $name (unsaved changes)"
    else -> "Close $name"
}

internal fun keyBarKeyDescription(label: String, latch: Latch): Pair<String, String?> {
    val spoken = when (label) {
        "Esc" -> "Escape"
        "Ctrl" -> "Control"
        "Left" -> "Left arrow"
        "Up" -> "Up arrow"
        "Down" -> "Down arrow"
        "Right" -> "Right arrow"
        "PgUp" -> "Page Up"
        "PgDn" -> "Page Down"
        else -> label
    }
    val state = if (label in setOf("Ctrl", "Alt", "Shift")) {
        when (latch) {
            Latch.Off -> "Off"
            Latch.Once -> "Latched for next key"
            Latch.Locked -> "Locked on"
        }
    } else {
        null
    }
    return spoken to state
}

internal fun codeEditorDescription(
    path: String,
    language: String,
    lineCount: Int,
    cursorLine: Int,
    cursorCol: Int,
    problemCount: Int,
    readOnly: Boolean = false,
): String = buildList {
    add("Code editor")
    add(path.removePrefix("scratch:/").substringAfterLast('/').ifEmpty { "untitled" })
    if (language.isNotBlank()) add(language)
    add("line ${cursorLine.coerceAtLeast(1)} column ${cursorCol.coerceAtLeast(1)} of ${lineCount.coerceAtLeast(1)} ${if (lineCount == 1) "line" else "lines"}")
    if (problemCount > 0) add("$problemCount ${if (problemCount == 1) "problem" else "problems"}")
    if (readOnly) add("read-only")
}.joinToString(", ")

internal fun notebookCellDescription(
    index: Int,
    total: Int,
    cell: NotebookCell,
    selected: Boolean,
): String = buildList {
    val kind = when (cell.type) {
        CellType.Code -> "Code cell"
        CellType.Markdown -> "Markdown cell"
        CellType.Raw -> "Raw cell"
    }
    add("$kind ${index + 1} of ${total.coerceAtLeast(1)}")
    if (selected) add("selected")
    when {
        cell.running -> add("running")
        cell.queued -> add("queued")
        cell.executionCount != null -> add("executed [${cell.executionCount}]")
    }
    if (cell.outputs.isNotEmpty()) {
        add(if (cell.outputsHidden) "${cell.outputs.size} ${if (cell.outputs.size == 1) "output" else "outputs"} hidden" else "${cell.outputs.size} ${if (cell.outputs.size == 1) "output" else "outputs"}")
    }
}.joinToString(", ")

internal fun cellOutputDescription(
    index: Int,
    total: Int,
    output: JsonObject,
    view: OutputView?,
): String {
    val detail = when (output.get("output_type")?.asString) {
        "stream" -> "${output.get("name")?.asString ?: "stdout"} stream"
        "error" -> "error: ${output.get("ename")?.asString ?: "exception"}"
        else -> when (view) {
            OutputView.Image -> "plot image"
            OutputView.Svg -> "SVG image"
            OutputView.Table -> "table"
            OutputView.Plotly -> "Plotly chart"
            OutputView.Vega -> "Vega chart"
            OutputView.Latex -> "formula"
            OutputView.Html -> "HTML"
            OutputView.Json -> "JSON"
            OutputView.Markdown -> "Markdown"
            OutputView.Text, null -> NotebookDocument.multiline(output.getAsJsonObject("data")?.get("text/plain"))
                .lineSequence().firstOrNull { it.isNotBlank() }?.take(60)?.let { "text: $it" } ?: "output"
        }
    }
    return "Cell output ${index + 1} of ${total.coerceAtLeast(1)}, $detail"
}

internal fun statusBarProblemsDescription(errors: Int, warnings: Int): String =
    "Problems: $errors ${if (errors == 1) "error" else "errors"}, $warnings ${if (warnings == 1) "warning" else "warnings"}"
