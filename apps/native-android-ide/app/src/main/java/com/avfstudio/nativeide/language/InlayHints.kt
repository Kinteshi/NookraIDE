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
