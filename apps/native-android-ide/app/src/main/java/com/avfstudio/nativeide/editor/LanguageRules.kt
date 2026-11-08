package com.avfstudio.nativeide.editor

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import java.io.StringReader

/**
 * What a VS Code `language-configuration.json` says about typing: comments, closing and surrounding
 * pairs, and the indentation Enter gives. Aardink 0.9 reads these files too, but its reader can't load on
 * Android (a regex Android's engine refuses, in a class initializer), so the Aardink editor reads them here.
 * Rules this can't compile are left out, not fatal.
 */
class LanguageRules(
    val lineComment: String?,
    val autoClosingPairs: List<AutoClosingPair>,
    val surroundingPairs: Map<Char, String>,
    val onEnterRules: List<OnEnterRule>,
    val increaseIndent: Regex?,
    val decreaseIndent: Regex?,
) {
    class AutoClosingPair(val open: String, val close: String, val notIn: Set<String>)
    class OnEnterRule(val beforeText: Regex, val afterText: Regex?, val indent: EnterIndent)
    enum class EnterIndent { None, Indent, Outdent, IndentOutdent }

    /**
     * The indentation of a new line after Enter, from the line before it ([before]) and what Enter carried
     * to the new line ([after]). [unit] is one level, spaces or a tab.
     */
    fun enterIndentation(before: String, after: String, unit: String): String {
        val base = before.takeWhile { it == ' ' || it == '\t' }
        val rule = onEnterRules.firstOrNull { it.beforeText.containsMatchIn(before) && (it.afterText?.containsMatchIn(after) ?: true) }
        var indentation = when (rule?.indent) {
            EnterIndent.Indent, EnterIndent.IndentOutdent -> base + unit
            EnterIndent.Outdent -> outdent(base, unit)
            EnterIndent.None -> base
            null -> if (increaseIndent?.containsMatchIn(before) == true) base + unit else base
        }
        if (decreaseIndent?.containsMatchIn(after) == true) indentation = outdent(indentation, unit)
        return indentation
    }

    /**
     * What closes [typed], typed at [offset] in [text] (which already holds it), or null. [tokenKindAt] says
     * whether the text at an offset is in a string or a comment, for a pair's `notIn`. As VS Code does by default, a pair closes only before
     * whitespace, the end of the line, or a closing character, and a quote not right after a word.
     */
    fun autoClose(text: CharSequence, offset: Int, typed: Char, tokenKindAt: (Int) -> String?): String? {
        val pair = autoClosingPairs.firstOrNull { it.open.length == 1 && it.open[0] == typed } ?: return null
        val next = text.getOrNull(offset + 1)
        if (next != null && !next.isWhitespace() && next !in CLOSE_BEFORE) return null
        if (typed in QUOTES) {
            val previous = text.getOrNull(offset - 1)
            if (previous == typed || (previous != null && previous.isLetterOrDigit() && !isStringPrefix(text, offset))) return null
        }
        if (pair.notIn.isNotEmpty()) {
            // The tokens are from before this key: what's just before it says whether it's in a string or a comment.
            val kind = tokenKindAt((offset - 1).coerceAtLeast(0))
            if (kind != null && pair.notIn.any { kind.startsWith(it) }) return null
        }
        return pair.close
    }

    private fun outdent(indentation: String, unit: String): String = when {
        indentation.endsWith(unit) -> indentation.dropLast(unit.length)
        indentation.endsWith('\t') -> indentation.dropLast(1)
        else -> indentation.dropLast(minOf(unit.length, indentation.length))
    }

    /** Python's `f"`, `rb'` and the like: a quote after them still opens a string. */
    private fun isStringPrefix(text: CharSequence, offset: Int): Boolean {
        var start = offset
        while (start > 0 && text[start - 1].isLetter()) start--
        if (start > 0 && (text[start - 1].isLetterOrDigit() || text[start - 1] == '_')) return false
        val prefix = text.subSequence(start, offset).toString().lowercase()
        return prefix.length <= 2 && prefix.all { it in "rbuf" }
    }

    companion object {
        private val CLOSE_BEFORE = setOf(';', ':', '.', ',', '=', '}', ']', ')', '>', '`')
        private val QUOTES = setOf('"', '\'', '`')

        fun parse(json: String): LanguageRules {
            val root = lenientJson(json).takeIf { it.isJsonObject }?.asJsonObject
                ?: throw IllegalArgumentException("A language configuration is a JSON object")
            val comments = root.getAsJsonObject("comments")
            val brackets = pairs(root.get("brackets"))
            val closing = root.get("autoClosingPairs")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull(::autoClosingPair)
                ?: brackets.map { AutoClosingPair(it.first, it.second, emptySet()) }
            val surrounding = pairs(root.get("surroundingPairs")).ifEmpty { brackets }
                .filter { it.first.length == 1 }.associate { it.first[0] to it.second }
            val enter = root.get("onEnterRules")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull(::onEnterRule).orEmpty()
            val indentation = root.getAsJsonObject("indentationRules")
            return LanguageRules(
                lineComment = comments?.get("lineComment")?.takeIf { it.isJsonPrimitive }?.asString,
                autoClosingPairs = closing,
                surroundingPairs = surrounding,
                onEnterRules = enter,
                increaseIndent = regex(indentation?.get("increaseIndentPattern")),
                decreaseIndent = regex(indentation?.get("decreaseIndentPattern")),
            )
        }

        /** The JSON these files are written in: comments and trailing commas allowed. */
        private fun lenientJson(text: String): JsonElement {
            val withoutTrailingCommas = text.replace(Regex(""",(\s*[}\]])"""), "$1")
            val reader = JsonReader(StringReader(withoutTrailingCommas)).apply { isLenient = true }
            return JsonParser.parseReader(reader)
        }

        private fun pairs(value: JsonElement?): List<Pair<String, String>> =
            (value as? JsonArray)?.mapNotNull { pair ->
                val parts = (pair as? JsonArray)?.takeIf { it.size() == 2 } ?: return@mapNotNull null
                parts[0].asString to parts[1].asString
            }.orEmpty()

        private fun autoClosingPair(value: JsonElement): AutoClosingPair? = when {
            value.isJsonArray && value.asJsonArray.size() == 2 -> AutoClosingPair(value.asJsonArray[0].asString, value.asJsonArray[1].asString, emptySet())
            value.isJsonObject -> {
                val pair = value.asJsonObject
                val open = pair.get("open")?.asString
                val close = pair.get("close")?.asString
                if (open == null || close == null) null
                else AutoClosingPair(open, close, (pair.get("notIn") as? JsonArray)?.map { it.asString }?.toSet().orEmpty())
            }
            else -> null
        }

        private fun onEnterRule(value: JsonElement): OnEnterRule? {
            val rule = value as? JsonObject ?: return null
            val before = regex(rule.get("beforeText")) ?: return null
            val action = rule.getAsJsonObject("action") ?: return null
            val indent = when (action.get("indent")?.asString) {
                "indent" -> EnterIndent.Indent
                "outdent" -> EnterIndent.Outdent
                "indentOutdent" -> EnterIndent.IndentOutdent
                else -> EnterIndent.None
            }
            return OnEnterRule(before, regex(rule.get("afterText")), indent)
        }

        /** A pattern as a string, or as `{ "pattern": …, "flags": … }`; null when it's absent or won't compile here. */
        private fun regex(value: JsonElement?): Regex? {
            val (pattern, flags) = when {
                value == null || value.isJsonNull -> return null
                value.isJsonPrimitive -> value.asString to ""
                value.isJsonObject -> (value.asJsonObject.get("pattern")?.asString ?: return null) to (value.asJsonObject.get("flags")?.asString ?: "")
                else -> return null
            }
            val options = if ('i' in flags) setOf(RegexOption.IGNORE_CASE) else emptySet()
            return runCatching { Regex(pattern, options) }.getOrNull()
        }
    }
}
