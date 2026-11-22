package io.github.kinteshi.nookraide.workbench

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

private val sgr = Regex("\u001B\\[([0-9;]*)m")
private val otherEscape = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]|\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)")

/** A place a traceback line points at: a notebook cell's run ([executionCount]) or a file. */
sealed interface TracebackTarget {
    val line: Int

    data class Cell(val executionCount: Int, override val line: Int) : TracebackTarget
    data class File(val path: String, override val line: Int) : TracebackTarget
}

/** IPython 8 (`Cell In[3], line 5`, `File ~/x.py:12, in f()`) and plain Python (`File "x.py", line 12`). */
private val cellReference = Regex("""Cell In\[(\d+)], line (\d+)""")
private val ipythonFile = Regex("""File (~?/[^\s:,]+):(\d+)""")
private val pythonFile = Regex("""File "([^"]+)", line (\d+)""")

/** The traceback references in a line of plain text, by their character range. */
fun tracebackTargets(text: String): List<Pair<IntRange, TracebackTarget>> = buildList {
    cellReference.findAll(text).forEach { add(it.range to TracebackTarget.Cell(it.groupValues[1].toInt(), it.groupValues[2].toInt())) }
    ipythonFile.findAll(text).forEach { add(it.range to TracebackTarget.File(it.groupValues[1], it.groupValues[2].toInt())) }
    pythonFile.findAll(text).forEach { match ->
        // A notebook cell shows up as a temporary file; Cell In[...] covers it.
        if (!match.groupValues[1].startsWith("<")) add(match.range to TracebackTarget.File(match.groupValues[1], match.groupValues[2].toInt()))
    }
}.sortedBy { it.first.first }

/**
 * Output text with its ANSI colours (SGR: the 16 colours in [palette], 256-colour and RGB, bold,
 * italic, underline, inverse) as an [AnnotatedString]; other escape sequences are dropped. With
 * [onTarget], traceback references become links.
 */
fun ansiAnnotated(
    text: String,
    palette: IntArray,
    foreground: Color,
    onTarget: ((TracebackTarget) -> Unit)? = null,
    linkColor: Color = foreground,
): AnnotatedString = buildAnnotatedString {
    val clean = text.replace(otherEscape) { if (it.value.endsWith("m") && it.value.startsWith("\u001B[")) it.value else "" }
    var fg: Color? = null
    var bg: Color? = null
    var bold = false
    var italic = false
    var underline = false
    var inverse = false
    var index = 0
    fun color(code: Int): Color = when (code) {
        in 0..15 -> palette.getOrNull(code)?.let(::Color) ?: foreground
        in 16..231 -> {
            val value = code - 16
            fun level(n: Int) = if (n == 0) 0 else 55 + n * 40
            Color(level(value / 36), level(value / 6 % 6), level(value % 6))
        }
        else -> (8 + (code - 232) * 10).coerceIn(0, 255).let { Color(it, it, it) }
    }
    fun emit(segment: String) {
        if (segment.isEmpty()) return
        val front = if (inverse) bg ?: Color.Unspecified else fg
        val back = if (inverse) fg ?: foreground else bg
        val style = SpanStyle(
            color = front ?: Color.Unspecified,
            background = back ?: Color.Unspecified,
            fontWeight = if (bold) FontWeight.Bold else null,
            fontStyle = if (italic) FontStyle.Italic else null,
            textDecoration = if (underline) TextDecoration.Underline else null,
        )
        if (style == SpanStyle(color = Color.Unspecified, background = Color.Unspecified)) append(segment) else withStyle(style) { append(segment) }
    }
    for (match in sgr.findAll(clean)) {
        emit(clean.substring(index, match.range.first))
        index = match.range.last + 1
        val codes = match.groupValues[1].split(';').map { it.toIntOrNull() ?: 0 }
        var i = 0
        while (i < codes.size) {
            when (val code = codes[i]) {
                0 -> { fg = null; bg = null; bold = false; italic = false; underline = false; inverse = false }
                1 -> bold = true
                3 -> italic = true
                4 -> underline = true
                7 -> inverse = true
                22 -> bold = false
                23 -> italic = false
                24 -> underline = false
                27 -> inverse = false
                in 30..37 -> fg = color(code - 30)
                39 -> fg = null
                in 40..47 -> bg = color(code - 40)
                49 -> bg = null
                in 90..97 -> fg = color(code - 90 + 8)
                in 100..107 -> bg = color(code - 100 + 8)
                38, 48 -> {
                    val target: Color? = when (codes.getOrNull(i + 1)) {
                        5 -> codes.getOrNull(i + 2)?.let(::color).also { i += 2 }
                        2 -> if (i + 4 < codes.size) Color(codes[i + 2], codes[i + 3], codes[i + 4]).also { i += 4 } else null
                        else -> null
                    }
                    if (code == 38) fg = target else bg = target
                }
            }
            i++
        }
    }
    emit(clean.substring(index))
    if (onTarget != null) {
        // The text as shown: the escapes removed (the builder's toString() isn't its text).
        tracebackTargets(clean.replace(sgr, "")).forEach { (range, target) ->
            addLink(
                LinkAnnotation.Clickable(
                    tag = "traceback",
                    styles = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                ) { onTarget(target) },
                range.first,
                range.last + 1,
            )
        }
    }
}
