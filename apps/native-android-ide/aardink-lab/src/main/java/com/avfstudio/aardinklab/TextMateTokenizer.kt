package com.avfstudio.aardinklab

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

/**
 * A TextMate grammar behind Aardink's tokenizer, for the switch's phase 0: VS Code grammar packs and
 * themes without declarative grammars. tm4e resolves the theme by scope stack as VS Code does, so a
 * token's type here is what it looks like: its colour, its style, and whether it's a comment or
 * a string, which Aardink's bracket colours skip. One tokenizer serves one document.
 */
class TextMateTokenizer(
    private val grammar: IGrammar,
    override val commentSyntax: CommentSyntax? = null,
) : IncrementalTokenizer {
    private var lines: List<String> = emptyList()
    private var lineTokens: MutableList<List<Token>> = ArrayList()
    private var endStates: MutableList<IStateStack?> = ArrayList()
    private val types = HashMap<Int, TokenType>()

    @Synchronized
    override fun tokenizeFull(text: String): List<Token> {
        val split = splitLines(text)
        val tokens = ArrayList<List<Token>>(split.size)
        val states = ArrayList<IStateStack?>(split.size)
        var state: IStateStack? = null
        var offset = 0
        for (line in split) {
            val scanned = scanLine(line, state, offset)
            tokens += scanned.first
            state = scanned.second
            states += state
            offset += line.length + 1
        }
        lines = split
        lineTokens = tokens
        endStates = states
        return tokens.flatten()
    }

    @Synchronized
    override fun tokenizeLines(text: String, dirtyRange: IntRange, previousTokens: List<Token>): List<Token> {
        if (lines.isEmpty() || previousTokens.isEmpty()) return tokenizeFull(text)
        val split = splitLines(text)
        val delta = split.size - lines.size
        // Lines before the first that differs keep everything; the dirty range only narrows the search.
        var first = 0
        val limit = minOf(split.size, lines.size, dirtyRange.first.coerceAtLeast(0))
        while (first < limit && split[first] == lines[first]) first++
        val lastDirty = dirtyRange.last.coerceAtMost(split.size - 1).coerceAtLeast(first)

        val offsets = IntArray(split.size)
        for (i in 1 until split.size) offsets[i] = offsets[i - 1] + split[i - 1].length + 1

        val newTokens = ArrayList<List<Token>>()
        val newStates = ArrayList<IStateStack?>()
        var state = if (first == 0) null else endStates[first - 1]
        var line = first
        var resume = -1
        while (line < split.size) {
            val scanned = scanLine(split[line], state, offsets[line])
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
                val move = offsets[i] - (lineTokens[old].firstOrNull()?.start ?: offsets[i])
                tokens += if (move == 0) lineTokens[old] else lineTokens[old].map { Token(it.start + move, it.end + move, it.type) }
                states += endStates[old]
            }
        }
        lines = split
        lineTokens = tokens
        endStates = states
        return newTokens.flatten()
    }

    override fun canSpanLines(lineIndex: Int, tokens: List<Token>): Boolean = false

    /** What every line holds now, for checking incremental passes against a full one. */
    @Synchronized
    internal fun allTokens(): List<Token> = lineTokens.flatten()

    private fun scanLine(line: String, state: IStateStack?, offset: Int): Pair<List<Token>, IStateStack?> {
        val result = grammar.tokenizeLine2(line, state, null)
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
