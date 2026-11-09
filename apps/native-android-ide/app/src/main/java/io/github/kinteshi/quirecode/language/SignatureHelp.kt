package io.github.kinteshi.quirecode.language

import io.github.kinteshi.quirecode.editor.LspPosition
import io.github.kinteshi.quirecode.editor.LspRange
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * The signature to show while typing a call: its label with the active parameter at
 * [activeStart] until [activeEnd] (both -1 when none), its documentation and the parameter's,
 * and which of [count] overloads it is. [raw] goes back to the server on a retrigger.
 */
data class SignatureHint(
    val label: String,
    val activeStart: Int,
    val activeEnd: Int,
    val documentation: String?,
    val parameterDocumentation: String?,
    val index: Int,
    val count: Int,
    val raw: JsonObject,
)

/** A `SignatureHelp` reply as the signature to show; null when there is none. */
fun parseSignatureHelp(result: JsonElement?): SignatureHint? {
    val help = result?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
    val signatures = help.getAsJsonArray("signatures")?.mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject }.orEmpty()
    if (signatures.isEmpty()) return null
    val index = help.int("activeSignature")?.takeIf { it in signatures.indices } ?: 0
    val signature = signatures[index]
    val label = signature.get("label")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
    val parameters = signature.getAsJsonArray("parameters")?.mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject }.orEmpty()
    // The signature's own active parameter wins over the reply's (LSP 3.16).
    val active = (signature.int("activeParameter") ?: help.int("activeParameter") ?: 0).takeIf { it in parameters.indices }
    val (start, end) = active?.let { parameterRange(label, parameters, it) } ?: (-1 to -1)
    return SignatureHint(
        label = label,
        activeStart = start,
        activeEnd = end,
        documentation = markupText(signature.get("documentation")),
        parameterDocumentation = active?.let { markupText(parameters[it].get("documentation")) },
        index = index,
        count = signatures.size,
        raw = help,
    )
}

/**
 * Where parameter [index] sits in [label]: given as offsets, or as its text, found after the
 * previous parameters so `f(a, a2)` marks the right one.
 */
private fun parameterRange(label: String, parameters: List<JsonObject>, index: Int): Pair<Int, Int>? {
    var from = label.indexOf('(').let { if (it < 0) 0 else it + 1 }
    for (i in 0..index) {
        val name = parameters[i].get("label") ?: return null
        val range = if (name.isJsonArray) {
            val offsets = name.asJsonArray
            val start = offsets.getOrNull(0)?.asInt ?: return null
            val end = offsets.getOrNull(1)?.asInt ?: return null
            start.coerceIn(0, label.length) to end.coerceIn(0, label.length)
        } else {
            val text = name.asString
            val start = label.indexOf(text, from).takeIf { it >= 0 } ?: return null
            start to start + text.length
        }
        if (i == index) return range
        from = range.second
    }
    return null
}

private fun com.google.gson.JsonArray.getOrNull(index: Int): JsonElement? = if (index in 0 until size()) get(index) else null

private fun JsonObject.int(name: String): Int? =
    get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt

/** A string or MarkupContent as plain text (Markdown code fences dropped). */
internal fun markupText(value: JsonElement?): String? {
    val text = when {
        value == null || value.isJsonNull -> null
        value.isJsonPrimitive -> value.asString
        value.isJsonObject -> value.asJsonObject.get("value")?.takeIf { it.isJsonPrimitive }?.asString
        else -> null
    } ?: return null
    return text.lines().filterNot { it.trimStart().startsWith("```") }.joinToString("\n").trim().takeIf { it.isNotEmpty() }
}

/** One use of a symbol; [write] for assignments (DocumentHighlightKind.Write). */
data class SymbolHighlight(val range: LspRange, val write: Boolean)

fun parseDocumentHighlights(result: JsonElement?): List<SymbolHighlight> {
    val items = result?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
    return items.mapNotNull { item ->
        val highlight = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        val range = highlight.getAsJsonObject("range") ?: return@mapNotNull null
        fun position(name: String) = range.getAsJsonObject(name)?.let { LspPosition(it.get("line").asInt, it.get("character").asInt) }
        val start = position("start") ?: return@mapNotNull null
        val end = position("end") ?: return@mapNotNull null
        SymbolHighlight(LspRange(start, end), write = highlight.get("kind")?.takeIf { it.isJsonPrimitive }?.asInt == 3)
    }
}

/** The SignatureHelpContext for a request: typed [trigger] character, or a retrigger of [shown]. */
fun signatureContext(trigger: String?, shown: SignatureHint?, invoked: Boolean = false): JsonObject = JsonObject().apply {
    addProperty("triggerKind", if (invoked) 1 else if (trigger != null) 2 else 3)
    trigger?.let { addProperty("triggerCharacter", it) }
    addProperty("isRetrigger", shown != null)
    shown?.let { add("activeSignatureHelp", it.raw) }
}
