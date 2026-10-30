package com.avfstudio.nativeide.language

import com.avfstudio.nativeide.editor.LspPosition
import com.google.gson.JsonElement

/** LSP InlayHintKind: 1 is a type, 2 a parameter name. */
data class InlayHint(val position: LspPosition, val label: String, val kind: Int?)

/** Reads a `textDocument/inlayHint` result; label parts are joined into one label. */
fun parseInlayHints(result: JsonElement?): List<InlayHint> {
    val hints = result?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
    return hints.mapNotNull { element ->
        val hint = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        val position = hint.getAsJsonObject("position") ?: return@mapNotNull null
        val label = hint.get("label") ?: return@mapNotNull null
        val text = when {
            label.isJsonPrimitive -> label.asString
            label.isJsonArray -> label.asJsonArray.joinToString("") { part ->
                part.takeIf { it.isJsonObject }?.asJsonObject?.get("value")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            }
            else -> return@mapNotNull null
        }.trim()
        if (text.isEmpty()) return@mapNotNull null
        InlayHint(
            LspPosition(position.get("line")?.asInt ?: return@mapNotNull null, position.get("character")?.asInt ?: 0),
            text,
            hint.get("kind")?.takeIf { it.isJsonPrimitive }?.asInt,
        )
    }
}

private const val ARGUMENT_CHARS = 16

/**
 * The editor can't make room inside a line (sora has no inline decorations), so a line's hints
 * are read at its end, each with the code it belongs to: a type after the name it describes
 * (`total: int`), a parameter name before its argument (`factor=2`). Others show as they are.
 */
fun endOfLineHints(line: String, hints: List<InlayHint>): List<String> =
    hints.sortedBy { it.position.character }.map { hint ->
        val at = hint.position.character.coerceIn(0, line.length)
        val label = hint.label
        when {
            label.startsWith(":") || hint.kind == 1 && !label.startsWith("->") -> {
                val name = line.substring(0, at).takeLastWhile { it.isLetterOrDigit() || it == '_' || it == '$' }
                if (name.isEmpty()) label else name + label
            }
            label.endsWith("=") || label.endsWith(":") || hint.kind == 2 -> label + argumentAt(line, at).let { if (label.endsWith(":")) " $it" else it }
            else -> label
        }
    }

/** The argument that starts at [start]: up to the next comma or closing bracket at its depth, shortened. */
private fun argumentAt(line: String, start: Int): String {
    var depth = 0
    var quote: Char? = null
    var end = start
    while (end < line.length) {
        val char = line[end]
        when {
            quote != null -> if (char == quote && line.getOrNull(end - 1) != '\\') quote = null
            char == '"' || char == '\'' || char == '`' -> quote = char
            char in "([{" -> depth++
            char in ")]}" -> if (depth == 0) break else depth--
            char == ',' && depth == 0 -> break
        }
        end++
    }
    val argument = line.substring(start, end).trim()
    return if (argument.length > ARGUMENT_CHARS) argument.take(ARGUMENT_CHARS - 1) + "…" else argument
}
