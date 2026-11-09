package com.avfstudio.nativeide.editor

import com.avfstudio.nativeide.language.LanguageDiagnostic
import com.google.gson.JsonElement

/** A diagnostic clamped to the editor's text, in line/column terms. */
data class EditorProblem(
    val startLine: Int,
    val startColumn: Int,
    val endLine: Int,
    val endColumn: Int,
    /** LSP severity; a missing one counts as an error, as in VS Code. */
    val severity: Int,
    val message: String,
    val detail: String,
    val unnecessary: Boolean,
    val deprecated: Boolean,
)

/**
 * Clamps [diagnostics] to lines of [lineLengths]. An empty range (a missing colon, end of file)
 * is widened to one character so it can be seen and tapped.
 */
fun editorProblems(diagnostics: List<LanguageDiagnostic>, lineLengths: List<Int>): List<EditorProblem> {
    if (lineLengths.isEmpty()) return emptyList()
    val lastLine = lineLengths.lastIndex
    return diagnostics.mapNotNull { diagnostic ->
        fun position(name: String): Pair<Int, Int>? {
            val point = diagnostic.range.getAsJsonObject(name) ?: return null
            val line = point.get("line")?.asInt ?: return null
            val character = point.get("character")?.asInt ?: return null
            if (line > lastLine) return lastLine to lineLengths[lastLine]
            val clampedLine = line.coerceAtLeast(0)
            return clampedLine to character.coerceIn(0, lineLengths[clampedLine])
        }
        var (startLine, startColumn) = position("start") ?: return@mapNotNull null
        var (endLine, endColumn) = position("end") ?: return@mapNotNull null
        if (endLine < startLine || (endLine == startLine && endColumn < startColumn)) {
            endLine = startLine
            endColumn = startColumn
        }
        if (startLine == endLine && startColumn == endColumn) {
            val length = lineLengths[startLine]
            when {
                endColumn < length -> endColumn++
                startColumn > 0 -> startColumn--
            }
        }
        EditorProblem(
            startLine, startColumn, endLine, endColumn,
            severity = diagnostic.severity ?: 1,
            message = diagnostic.message,
            detail = listOfNotNull(diagnostic.source, diagnostic.code?.codeText()).joinToString(" "),
            unnecessary = 1 in diagnostic.tags,
            deprecated = 2 in diagnostic.tags,
        )
    }
}

private fun JsonElement.codeText(): String? = when {
    isJsonPrimitive -> asString
    isJsonObject -> asJsonObject.get("value")?.takeIf { it.isJsonPrimitive }?.asString
    else -> null
}

/** What the line-number margin shows, all by 0-based line: breakpoints, the paused line, and each line's worst problem. */
data class LineMarks(
    val breakpoints: Set<Int> = emptySet(),
    /** Of [breakpoints], those with a condition or hit count, and the logpoints (drawn apart, as VS Code does). */
    val conditionalBreakpoints: Set<Int> = emptySet(),
    val logpoints: Set<Int> = emptySet(),
    val pausedLine: Int? = null,
    val problems: Map<Int, Int> = emptyMap(),
)

/** The worst severity per line, for the margin; hints (4) get no mark. */
fun problemLines(problems: List<EditorProblem>): Map<Int, Int> =
    problems.filter { it.severity <= 3 }.groupBy { it.startLine }.mapValues { (_, onLine) -> onLine.minOf { it.severity } }

/** The editor options from the user's settings, as any editor engine applies them. */
data class EditorConfig(
    val fontSizeSp: Float = 14f,
    val tabSize: Int = 4,
    val insertSpaces: Boolean = true,
    val autoIndent: Boolean = true,
    val useTabStops: Boolean = true,
    val wordWrap: Boolean = false,
    val lineNumbers: Boolean = true,
    val problemUnderlines: Boolean = true,
    val showUnused: Boolean = true,
    val showDeprecated: Boolean = true,
    /** Pinching changes the font size; the editor reports it as [EditorEvent.Zoomed]. */
    val pinchToZoom: Boolean = true,
    /** `editor.mouseWheelZoom`: Ctrl and the mouse wheel change the font size. */
    val wheelZoom: Boolean = true,
    /** `editor.hover.enabled` and `editor.hover.delay`: report a resting pointer after this many ms. */
    val hover: Boolean = true,
    val hoverDelayMs: Int = 300,
    /** `editor.folding`: fold controls beside the line numbers. */
    val folding: Boolean = true,
)
