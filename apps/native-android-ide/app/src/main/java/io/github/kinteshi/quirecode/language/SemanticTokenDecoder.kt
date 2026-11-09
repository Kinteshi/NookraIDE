package io.github.kinteshi.quirecode.language

import io.github.kinteshi.quirecode.editor.EditorDecoration
import io.github.kinteshi.quirecode.editor.LspPosition
import io.github.kinteshi.quirecode.editor.LspRange
import com.google.gson.JsonElement

internal fun decodeSemanticTokens(
    result: JsonElement,
    tokenTypes: List<String>,
    tokenModifiers: List<String>,
): List<EditorDecoration> {
    if (result.isJsonNull) return emptyList()
    require(result.isJsonObject) { "Semantic-token response must be an object" }
    val data = result.asJsonObject.getAsJsonArray("data")
        ?: throw IllegalArgumentException("Semantic-token response has no data array")
    require(data.size() % 5 == 0) { "Semantic-token data length must be a multiple of five" }
    var line = 0L
    var start = 0L
    return data.toList().chunked(5).map { encoded ->
        val deltaLine = encoded[0].asLong
        val deltaStart = encoded[1].asLong
        val length = encoded[2].asLong
        val tokenType = encoded[3].asInt
        val modifierBits = encoded[4].asLong
        require(deltaLine >= 0 && deltaStart >= 0) { "Semantic-token deltas cannot be negative" }
        require(length > 0) { "Semantic-token length must be positive" }
        require(modifierBits >= 0) { "Semantic-token modifier bits cannot be negative" }
        line += deltaLine
        start = if (deltaLine == 0L) start + deltaStart else deltaStart
        require(line <= Int.MAX_VALUE && start <= Int.MAX_VALUE - length) {
            "Semantic-token position exceeds supported document bounds"
        }
        val kind = tokenTypes.getOrNull(tokenType)
            ?: throw IllegalArgumentException("Semantic-token type index $tokenType is outside the server legend")
        val modifiers = tokenModifiers.indices
            .filter { it < Long.SIZE_BITS && modifierBits and (1L shl it) != 0L }
            .map(tokenModifiers::get)
        EditorDecoration(
            range = LspRange(
                LspPosition(line.toInt(), start.toInt()),
                LspPosition(line.toInt(), (start + length).toInt()),
            ),
            kind = kind,
            value = modifiers.joinToString(","),
        )
    }
}
