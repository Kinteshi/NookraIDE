package com.avfstudio.nativeide.editor

import io.github.rosemoe.sora.lang.styling.Span
import io.github.rosemoe.sora.lang.styling.TextStyle
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import android.graphics.Color as AndroidColor
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.Executors

data class BracketColorToken(val offset: Int, val depth: Int)

fun bracketColorTokens(text: String, languageId: String): List<BracketColorToken> {
    val tokens = mutableListOf<BracketColorToken>()
    val stack = mutableListOf<Char>()
    var quote: Char? = null
    var tripleQuote = false
    var escaped = false
    var lineComment = false
    var blockComment = false
    var index = 0
    val python = languageId == "py"
    val hashComments = python || languageId in setOf("sh", "yaml", "toml")

    while (index < text.length) {
        val char = text[index]
        val next = text.getOrNull(index + 1)
        if (lineComment) {
            if (char == '\n') lineComment = false
            index++
            continue
        }
        if (blockComment) {
            if (char == '*' && next == '/') {
                blockComment = false
                index += 2
            } else {
                index++
            }
            continue
        }
        if (quote != null) {
            if (escaped) {
                escaped = false
            } else if (char == '\\') {
                escaped = true
            } else if (char == quote) {
                if (tripleQuote && text.getOrNull(index + 1) == quote && text.getOrNull(index + 2) == quote) {
                    quote = null
                    tripleQuote = false
                    index += 3
                    continue
                } else if (!tripleQuote) {
                    quote = null
                }
            }
            index++
            continue
        }
        if (hashComments && char == '#') {
            lineComment = true
            index++
            continue
        }
        if (!python && char == '/' && next == '/') {
            lineComment = true
            index += 2
            continue
        }
        if (!python && char == '/' && next == '*') {
            blockComment = true
            index += 2
            continue
        }
        if (char == '\'' || char == '"' || (!python && char == '`')) {
            quote = char
            tripleQuote = python && text.getOrNull(index + 1) == char && text.getOrNull(index + 2) == char
            index += if (tripleQuote) 3 else 1
            continue
        }

        when (char) {
            '(', '[', '{' -> {
                tokens += BracketColorToken(index, stack.size % BRACKET_COLOR_COUNT)
                stack += char
            }
            ')', ']', '}' -> {
                val expectedOpen = when (char) {
                    ')' -> '('
                    ']' -> '['
                    else -> '{'
                }
                if (stack.lastOrNull() == expectedOpen) {
                    tokens += BracketColorToken(index, (stack.size - 1) % BRACKET_COLOR_COUNT)
                    stack.removeAt(stack.lastIndex)
                }
            }
        }
        index++
    }
    return tokens
}

private const val BRACKET_COLOR_COUNT = 3
private val BRACKET_COLOR_IDS = intArrayOf(500_001, 500_002, 500_003)
private val BRACKET_COLORS = intArrayOf(0xFF7FC8FF.toInt(), 0xFFFFC66D.toInt(), 0xFFC792EA.toInt())
private val SEMANTIC_TOKEN_COLORS = mapOf(
    "class" to (500_010 to 0xFF4EC9B0.toInt()),
    "enum" to (500_010 to 0xFF4EC9B0.toInt()),
    "interface" to (500_010 to 0xFF4EC9B0.toInt()),
    "namespace" to (500_010 to 0xFF4EC9B0.toInt()),
    "struct" to (500_010 to 0xFF4EC9B0.toInt()),
    "type" to (500_010 to 0xFF4EC9B0.toInt()),
    "function" to (500_011 to 0xFFDCDCAA.toInt()),
    "method" to (500_011 to 0xFFDCDCAA.toInt()),
    "parameter" to (500_012 to 0xFF9CDCFE.toInt()),
    "property" to (500_012 to 0xFF9CDCFE.toInt()),
    "variable" to (500_012 to 0xFF9CDCFE.toInt()),
    "enumMember" to (500_013 to 0xFF4FC1FF.toInt()),
    "keyword" to (500_014 to 0xFFC586C0.toInt()),
    "modifier" to (500_014 to 0xFFC586C0.toInt()),
    "string" to (500_015 to 0xFFCE9178.toInt()),
    "number" to (500_016 to 0xFFB5CEA8.toInt()),
    "comment" to (500_017 to 0xFF6A9955.toInt()),
    "operator" to (500_018 to 0xFFD4D4D4.toInt()),
)

class SoraBracketColorizer(
    private val editor: CodeEditor,
    private val languageId: String,
) {
    private val injected = Collections.newSetFromMap(IdentityHashMap<Span, Boolean>())
    @Volatile private var semanticDecorations: List<EditorDecoration> = emptyList()

    fun setSemanticDecorations(decorations: List<EditorDecoration>) {
        semanticDecorations = decorations
        updateAsync()
    }

    fun updateAsync() {
        colorizerExecutor.execute(::update)
    }

    fun update() {
        val styles = editor.styles ?: return
        val spans = styles.spans ?: return
        if (!spans.supportsModify()) return

        val text = editor.text.toString()
        val lineStarts = IntArray(editor.lineCount)
        var line = 1
        for (offset in text.indices) {
            if (text[offset] == '\n' && line < lineStarts.size) {
                lineStarts[line++] = offset + 1
            }
        }
        val tokensByLine = mutableMapOf<Int, MutableList<BracketColorToken>>()
        var tokenLine = 0
        var nextLineStart = lineStarts.getOrNull(1) ?: Int.MAX_VALUE
        bracketColorTokens(text, languageId).forEach { token ->
            while (token.offset >= nextLineStart && tokenLine + 1 < lineStarts.size) {
                tokenLine++
                nextLineStart = lineStarts.getOrNull(tokenLine + 1) ?: Int.MAX_VALUE
            }
            tokensByLine.getOrPut(tokenLine) { mutableListOf() }.add(token)
        }
        val semanticByLine = semanticDecorations.mapNotNull { decoration ->
            val range = decoration.range
            val colorId = SEMANTIC_TOKEN_COLORS[decoration.kind]?.first ?: return@mapNotNull null
            if (range.start.line != range.end.line ||
                range.start.line !in 0 until editor.lineCount ||
                range.start.character < 0 ||
                range.end.character <= range.start.character
            ) {
                return@mapNotNull null
            }
            Triple(range.start.line, range.start.character, range.end.character to colorId)
        }.groupBy({ it.first }, { it.second to it.third })

        val reader = spans.read()
        val modifier = spans.modify()
        val previousInjected = injected.toSet()
        injected.clear()
        for (line in 0 until editor.lineCount) {
            reader.moveToLine(line)
            val base = reader.getSpansOnLine(line)
                .filterNot(previousInjected::contains)
                .sortedBy(Span::getColumn)
            val lineTokens = tokensByLine[line].orEmpty()
            val semanticTokens = semanticByLine[line].orEmpty()
            if ((lineTokens.isEmpty() && semanticTokens.isEmpty()) || base.isEmpty()) {
                if (base.size != reader.getSpanCount()) modifier.setSpansOnLine(line, base)
                continue
            }

            val output = base.toMutableList()
            val lineLength = editor.text.getColumnCount(line)
            val tokenColumns = lineTokens.mapTo(HashSet()) { it.offset - lineStarts[line] }
            tokenColumns += semanticTokens.map { it.first }
            for ((startColumn, endAndColor) in semanticTokens) {
                val (endColumn, colorId) = endAndColor
                if (startColumn !in 0 until lineLength || endColumn > lineLength) continue
                val sourceSpan = base.lastOrNull { it.column <= startColumn } ?: base.first()
                output += coloredSpan(startColumn, sourceSpan, colorId)
                if (endColumn !in tokenColumns && base.none { it.column == endColumn }) {
                    val restoreSource = base.lastOrNull { it.column <= endColumn } ?: sourceSpan
                    output += restoredSpan(endColumn, restoreSource)
                }
            }
            for (token in lineTokens) {
                val column = token.offset - lineStarts[line]
                if (column !in 0 until lineLength) continue
                val sourceSpan = base.lastOrNull { it.column <= column } ?: base.first()
                output += coloredSpan(column, sourceSpan, BRACKET_COLOR_IDS[token.depth])
                val nextColumn = column + 1
                if (nextColumn <= lineLength &&
                    nextColumn !in tokenColumns &&
                    base.none { it.column == nextColumn }
                ) {
                    output += restoredSpan(nextColumn, sourceSpan)
                }
            }
            output.sortBy(Span::getColumn)
            modifier.setSpansOnLine(line, output)
        }
        editor.postInvalidate()
    }

    private fun coloredSpan(column: Int, source: Span, colorId: Int): Span =
        source.copy().apply {
            this.column = column
            style = withForeground(source.style, colorId)
        }.also(injected::add)

    private fun restoredSpan(column: Int, source: Span): Span =
        source.copy().apply {
            this.column = column
        }.also(injected::add)

    private fun withForeground(style: Long, colorId: Int): Long =
        (style and TextStyle.FOREGROUND_BITS.inv()) or colorId.toLong()

    companion object {
        fun configureColors(editor: CodeEditor, semanticColors: Map<String, String> = emptyMap()) {
            BRACKET_COLOR_IDS.forEachIndexed { index, colorId ->
                editor.colorScheme.setColor(colorId, BRACKET_COLORS[index])
            }
            SEMANTIC_TOKEN_COLORS.values.distinct().forEach { (colorId, color) ->
                editor.colorScheme.setColor(colorId, color)
            }
            semanticColors.forEach { (tokenType, value) ->
                val colorId = SEMANTIC_TOKEN_COLORS[tokenType.substringBefore('.')]?.first ?: return@forEach
                val normalizedColor = if (value.matches(Regex("#[0-9a-fA-F]{8}"))) {
                    "#${value.substring(7, 9)}${value.substring(1, 7)}"
                } else {
                    value
                }
                val color = try {
                    AndroidColor.parseColor(normalizedColor)
                } catch (error: IllegalArgumentException) {
                    throw IllegalArgumentException("Invalid semantic token color for $tokenType: $value", error)
                }
                editor.colorScheme.setColor(colorId, color)
            }
            editor.setHighlightBracketPair(true)
            editor.colorScheme.setColor(EditorColorScheme.HIGHLIGHTED_DELIMITERS_FOREGROUND, 0xFFFFFFFF.toInt())
        }

        private val colorizerExecutor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "sora-bracket-colorizer").apply { isDaemon = true }
        }
    }
}
