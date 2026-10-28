package com.avfstudio.nativeide.workbench

import com.avfstudio.nativeide.editor.LspPosition
import com.avfstudio.nativeide.editor.TagDecoration
import com.avfstudio.nativeide.language.LanguageDiagnostic
import com.google.gson.JsonElement
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticDetail
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticRegion
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticsContainer
import io.github.rosemoe.sora.lang.diagnostic.Quickfix
import io.github.rosemoe.sora.widget.style.DiagnosticIndicatorStyle

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

/** sora's squiggle for a severity; hints get none (they only fade or strike code through). */
fun soraSeverity(severity: Int): Short? = when (severity) {
    1 -> DiagnosticRegion.SEVERITY_ERROR
    2 -> DiagnosticRegion.SEVERITY_WARNING
    3 -> DiagnosticRegion.SEVERITY_TYPO
    else -> null
}

/** Per-line fade/strike-through ranges for tagged problems; a multi-line range covers each line. */
fun tagDecorations(
    problems: List<EditorProblem>,
    lineLengths: List<Int>,
    showUnused: Boolean,
    showDeprecated: Boolean,
): List<TagDecoration> = problems.flatMap { problem ->
    val faded = showUnused && problem.unnecessary
    val struck = showDeprecated && problem.deprecated
    if (!faded && !struck) return@flatMap emptyList()
    (problem.startLine..problem.endLine).map { line ->
        TagDecoration(
            line,
            if (line == problem.startLine) problem.startColumn else 0,
            if (line == problem.endLine) problem.endColumn else lineLengths[line],
            faded,
            struck,
        )
    }
}

/**
 * Shows [problems] in [editor]: wavy underlines, the tap/hover tooltip (message, source and
 * code, plus "Quick fix…" which asks the server for code actions there) and the faded or
 * struck-through tagged code.
 */
internal fun showEditorProblems(
    editor: NativeCodeEditor,
    problems: List<EditorProblem>,
    settings: WorkbenchSettings,
    onQuickFix: (LspPosition) -> Unit,
) {
    val content = editor.text
    val lineLengths = (0 until content.lineCount).map(content::getColumnCount)
    val container = DiagnosticsContainer()
    if (settings.problemUnderlines) {
        problems.forEachIndexed { index, problem ->
            val severity = soraSeverity(problem.severity) ?: return@forEachIndexed
            val start = content.getCharIndex(problem.startLine, problem.startColumn)
            val end = content.getCharIndex(problem.endLine, problem.endColumn)
            val at = LspPosition(problem.startLine, problem.startColumn)
            val detail = DiagnosticDetail(
                problem.message,
                problem.detail.takeIf { it.isNotEmpty() },
                listOf(Quickfix("Quick fix…", 0L) { onQuickFix(at) }),
                null,
            )
            container.addDiagnostic(DiagnosticRegion(start, end, severity, index.toLong(), detail))
        }
    }
    editor.diagnosticIndicatorStyle = DiagnosticIndicatorStyle.WAVY_LINE
    editor.diagnostics = container
    editor.overlay?.setTagDecorations(tagDecorations(problems, lineLengths, settings.showUnused, settings.showDeprecated))
}

/** Where a problem sits along the scrollbar (0 top, 1 bottom) and its severity, worst first per line. */
data class ProblemMark(val fraction: Float, val line: Int, val column: Int, val severity: Int)

fun problemMarks(problems: List<EditorProblem>, lineCount: Int): List<ProblemMark> =
    problems.filter { it.severity <= 3 }
        .groupBy { it.startLine }
        .map { (line, onLine) ->
            val worst = onLine.minBy { it.severity }
            ProblemMark(line.toFloat() / lineCount.coerceAtLeast(1), line, worst.startColumn, worst.severity)
        }
        .sortedBy { it.line }
