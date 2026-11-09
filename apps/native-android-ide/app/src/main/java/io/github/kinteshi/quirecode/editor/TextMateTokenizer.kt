package io.github.kinteshi.quirecode.editor

import androidx.compose.ui.graphics.Color
import com.aardarch.aardink.core.CommentSyntax
import com.aardarch.aardink.core.IncrementalTokenizer
import com.aardarch.aardink.core.NamedTokenType
import com.aardarch.aardink.core.Token
import com.aardarch.aardink.core.TokenFontStyle
import com.aardarch.aardink.core.TokenType
import org.eclipse.tm4e.core.grammar.IGrammar
import org.eclipse.tm4e.core.grammar.IStateStack
import org.eclipse.tm4e.core.internal.grammar.tokenattrs.EncodedTokenAttributes

private const val COMMENT_KIND = 1
private const val STRING_KIND = 2
private const val REGEX_KIND = 3
private const val ITALIC = 1
private const val BOLD = 2
private const val UNDERLINE = 4
private const val STRIKETHROUGH = 8

/** A language server's colour for part of one line, drawn over the grammar's. */
class SemanticSpan(val line: Int, val start: Int, val end: Int, val type: TokenType)

/**
 * A TextMate grammar behind Aardink's tokenizer: VS Code grammar packs and themes in the Aardink
 * editor. tm4e resolves the theme by scope stack as VS Code does, so a token's type here is what
 * it looks like: its colour, its style, and whether it's a comment or a string, which Aardink's
 * bracket colours skip. Semantic colours go over the grammar's ([semantic]). One tokenizer serves
 * one document. Proven in the lab first (batch 42).
 */
class TextMateTokenizer(
    private val grammar: IGrammar,
    override val commentSyntax: CommentSyntax? = null,
) : IncrementalTokenizer {
    private var text: String? = null
    private var lines: List<String> = emptyList()
    private var offsets = IntArray(0)
    private var lineTokens: MutableList<List<Token>> = ArrayList()
    private var endStates: MutableList<IStateStack?> = ArrayList()
    private val types = HashMap<Int, TokenType>()

    /**
     * The language server's colours by line. Set it, then have the editor tokenize again: the same
     * text isn't scanned again, only coloured.
     */
    @Volatile
    var semantic: Map<Int, List<SemanticSpan>> = emptyMap()

    @Synchronized
    override fun tokenizeFull(text: String): List<Token> {
        if (text == this.text) return coloured(0 until lines.size)
        val split = splitLines(text)
        val tokens = ArrayList<List<Token>>(split.size)
        val states = ArrayList<IStateStack?>(split.size)
        val starts = lineStarts(split)
        var state: IStateStack? = null
        for ((index, line) in split.withIndex()) {
            val scanned = scanLine(line, state, starts[index])
            tokens += scanned.first
            state = scanned.second
            states += state
        }
        this.text = text
        lines = split
        offsets = starts
        lineTokens = tokens
        endStates = states
        return coloured(0 until split.size)
    }

    @Synchronized
    override fun tokenizeLines(text: String, dirtyRange: IntRange, previousTokens: List<Token>): List<Token> {
        // Asked with nothing before: a snippet, or a quick look at some lines. Answer for them and keep nothing.
        if (previousTokens.isEmpty()) return standalone(text, dirtyRange)
        if (lines.isEmpty()) return tokenizeFull(text)
        val split = splitLines(text)
        val delta = split.size - lines.size
        // Lines before the first that differs keep everything; the dirty range only narrows the search.
        var first = 0
        val limit = minOf(split.size, lines.size, dirtyRange.first.coerceAtLeast(0))
        while (first < limit && split[first] == lines[first]) first++
        val lastDirty = dirtyRange.last.coerceAtMost(split.size - 1).coerceAtLeast(first)
        val starts = lineStarts(split)

        val newTokens = ArrayList<List<Token>>()
        val newStates = ArrayList<IStateStack?>()
        var state = if (first == 0) null else endStates[first - 1]
        var line = first
        var resume = -1
        while (line < split.size) {
            val scanned = scanLine(split[line], state, starts[line])
            newTokens += scanned.first
            state = scanned.second
            newStates += state
            val old = line - delta
            // Past the edit, the same text from the same state gives the same tokens from here on.
            if (line >= lastDirty && old >= 0 && old < endStates.size && lines[old] == split[line] && endStates[old] == state) {
                resume = line + 1
                break
            }
            line++
        }

        val tokens = ArrayList<List<Token>>(split.size)
        val states = ArrayList<IStateStack?>(split.size)
        for (i in 0 until first) {
            tokens += lineTokens[i]
            states += endStates[i]
        }
        tokens += newTokens
        states += newStates
        if (resume >= 0) {
            for (i in resume until split.size) {
                val old = i - delta
                val move = starts[i] - offsets[old]
                tokens += if (move == 0) lineTokens[old] else lineTokens[old].map { Token(it.start + move, it.end + move, it.type) }
                states += endStates[old]
            }
        }
        this.text = text
        lines = split
        offsets = starts
        lineTokens = tokens
        endStates = states
        return coloured(first until first + newTokens.size)
    }

    override fun canSpanLines(lineIndex: Int, tokens: List<Token>): Boolean = false

    /** Tokens for [range] from a scan of [text] that leaves this tokenizer's own state alone. */
    private fun standalone(text: String, range: IntRange): List<Token> {
        val split = splitLines(text)
        val starts = lineStarts(split)
        val last = range.last.coerceAtMost(split.size - 1)
        val out = ArrayList<Token>()
        var state: IStateStack? = null
        for (line in 0..last) {
            val scanned = scanLine(split[line], state, starts[line])
            if (line >= range.first) out += scanned.first
            state = scanned.second
        }
        return out
    }

    /** The grammar's tokens on every line, with no semantic colours: for checking passes against each other. */
    @Synchronized
    internal fun grammarTokens(): List<Token> = lineTokens.flatten()

    private fun coloured(range: IntRange): List<Token> {
        val spans = semantic
        val out = ArrayList<Token>()
        for (line in range) {
            val tokens = lineTokens[line]
            val lineSpans = spans[line]
            if (lineSpans == null) out += tokens else out += overlay(tokens, lineSpans, offsets[line])
        }
        return out
    }

    private fun overlay(tokens: List<Token>, spans: List<SemanticSpan>, offset: Int): List<Token> {
        val out = ArrayList<Token>(tokens.size + spans.size * 2)
        for (token in tokens) {
            if (token.isEmpty) {
                out += token
                continue
            }
            var at = token.start
            for (span in spans) {
                val from = maxOf(offset + span.start, at)
                val to = minOf(offset + span.end, token.end)
                if (from >= to) continue
                if (from > at) out += Token(at, from, token.type)
                out += Token(from, to, span.type)
                at = to
            }
            if (at < token.end) out += Token(at, token.end, token.type)
        }
        return out
    }

    private fun scanLine(line: String, state: IStateStack?, offset: Int): Pair<List<Token>, IStateStack?> {
        // One grammar is shared by every tokenizer of its language (the registry caches it): the file, notebook cells, code
        // in hover cards. tm4e's grammar keeps caches that aren't safe across threads (a HashMap's computeIfAbsent threw
        // ConcurrentModificationException with several cells tokenizing at once, 11-09), so one line at a time per grammar.
        val result = synchronized(grammar) { grammar.tokenizeLine2(line, state, null) }
        val raw = result.tokens
        val tokens = ArrayList<Token>(raw.size / 2)
        var i = 0
        while (i < raw.size) {
            val start = raw[i]
            val end = if (i + 2 < raw.size) raw[i + 2] else line.length
            if (end > start) {
                val type = typeOf(raw[i + 1])
                val last = tokens.lastOrNull()
                if (last != null && last.type == type && last.end == offset + start) {
                    tokens[tokens.size - 1] = Token(last.start, offset + end, type)
                } else {
                    tokens += Token(offset + start, offset + end, type)
                }
            }
            i += 2
        }
        // An empty line still needs a token, so a pass that ends here says it covered it.
        if (tokens.isEmpty()) tokens += Token(offset, offset, typeOf(if (raw.size > 1) raw[1] else 0))
        return tokens to result.ruleStack
    }

    private fun typeOf(metadata: Int): TokenType {
        val kind = EncodedTokenAttributes.getTokenType(metadata)
        val color = EncodedTokenAttributes.getForeground(metadata)
        val style = EncodedTokenAttributes.getFontStyle(metadata).coerceAtLeast(0)
        return types.getOrPut(kind or (color shl 4) or (style shl 20)) { NamedTokenType(typeName(kind, color, style)) }
    }

    private fun splitLines(text: String): List<String> = text.split('\n').map { it.removeSuffix("\r") }

    private fun lineStarts(lines: List<String>): IntArray {
        val starts = IntArray(lines.size)
        for (i in 1 until lines.size) starts[i] = starts[i - 1] + lines[i - 1].length + 1
        return starts
    }
}

private fun typeName(kind: Int, color: Int, style: Int): String {
    val prefix = when (kind) {
        COMMENT_KIND -> "comment"
        STRING_KIND -> "string"
        REGEX_KIND -> "regex"
        else -> "text"
    }
    return if (style == 0) "$prefix.c$color" else "$prefix.c$color.f$style"
}

/** The tokens' colours and styles for a theme's colour map: every kind, colour and style this can name. */
fun textMateTokenColors(colorMap: List<String>): Pair<Map<TokenType, Color>, Map<TokenType, TokenFontStyle>> {
    val colors = HashMap<TokenType, Color>()
    val styles = HashMap<TokenType, TokenFontStyle>()
    colorMap.forEachIndexed { id, hex ->
        val color = parseHex(hex) ?: return@forEachIndexed
        for (kind in 0..REGEX_KIND) {
            colors[NamedTokenType(typeName(kind, id, 0))] = color
            for (style in 1..15) {
                styles[NamedTokenType(typeName(kind, id, style))] = TokenFontStyle(
                    bold = style and BOLD != 0,
                    italic = style and ITALIC != 0,
                    underline = style and UNDERLINE != 0,
                    strikethrough = style and STRIKETHROUGH != 0,
                )
            }
        }
    }
    return colors to styles
}

private fun parseHex(hex: String?): Color? {
    val digits = hex?.removePrefix("#") ?: return null
    return when (digits.length) {
        6 -> digits.toLongOrNull(16)?.let { Color(0xFF000000L or it) }
        8 -> digits.toLongOrNull(16)?.let { Color(((it and 0xFF) shl 24) or (it ushr 8)) }
        else -> null
    }
}
