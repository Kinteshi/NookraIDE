package com.avfstudio.nativeide.workbench

import com.avfstudio.nativeide.editor.LspPosition
import com.avfstudio.nativeide.editor.LspRange
import com.avfstudio.nativeide.editor.LspTextEdit
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

internal fun InputStream.readUtf8Limited(maxBytes: Int): String {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        total += count
        if (total > maxBytes) throw IOException("Theme JSON exceeds the 8 MiB limit")
        output.write(buffer, 0, count)
    }
    return String(output.toByteArray(), Charsets.UTF_8)
}

data class LanguageLocation(val uri: String, val range: JsonObject)
data class EditorNavigationTarget(val path: String, val line: Int, val character: Int)
data class CompletionChoice(val label: String, val detail: String?, val edit: LspTextEdit?)
data class CodeActionChoice(val title: String, val action: JsonObject)
data class LanguageServerIssue(val server: String, val message: String)
data class OutlineSymbol(
    val name: String,
    val kind: Int,
    val range: JsonObject,
    val children: List<OutlineSymbol>,
)

internal fun JsonObject.toLspRange(): LspRange? {
    val start = getAsJsonObject("start") ?: return null
    val end = getAsJsonObject("end") ?: return null
    return LspRange(
        LspPosition(start.get("line").asInt, start.get("character").asInt),
        LspPosition(end.get("line").asInt, end.get("character").asInt),
    )
}

internal fun JsonElement.toLocations(): List<LanguageLocation> {
    val values = when {
        isJsonArray -> asJsonArray.toList()
        isJsonObject -> listOf(this)
        else -> emptyList()
    }
    return values.mapNotNull { value ->
        if (!value.isJsonObject) return@mapNotNull null
        val location = value.asJsonObject
        val uri = location.get("uri")?.takeUnless { it.isJsonNull }?.asString
            ?: location.get("targetUri")?.takeUnless { it.isJsonNull }?.asString
            ?: return@mapNotNull null
        val range = location.getAsJsonObject("range")
            ?: location.getAsJsonObject("targetSelectionRange")
            ?: location.getAsJsonObject("targetRange")
            ?: return@mapNotNull null
        LanguageLocation(uri, range)
    }
}

internal fun JsonElement.toOutlineSymbols(): List<OutlineSymbol> =
    if (!isJsonArray) emptyList() else asJsonArray.mapNotNull { value ->
        if (!value.isJsonObject) return@mapNotNull null
        val symbol = value.asJsonObject
        val name = symbol.get("name")?.takeUnless { it.isJsonNull }?.asString ?: return@mapNotNull null
        val range = symbol.getAsJsonObject("range")
            ?: symbol.getAsJsonObject("location")?.getAsJsonObject("range")
            ?: return@mapNotNull null
        OutlineSymbol(
            name = name,
            kind = symbol.get("kind")?.takeUnless { it.isJsonNull }?.asInt ?: 0,
            range = range,
            children = symbol.getAsJsonArray("children")?.toOutlineSymbols().orEmpty(),
        )
    }

internal fun JsonElement.workspaceTextEdits(): Map<String, List<LspTextEdit>> {
    if (!isJsonObject) return emptyMap()
    val result = linkedMapOf<String, MutableList<LspTextEdit>>()
    val edit = asJsonObject
    edit.getAsJsonObject("changes")?.entrySet()?.forEach { (uri, values) ->
        result.getOrPut(java.net.URI(uri).path) { mutableListOf() }.addAll(values.textEdits())
    }
    edit.getAsJsonArray("documentChanges")?.forEach { change ->
        val document = change.asJsonObject.getAsJsonObject("textDocument") ?: return@forEach
        val uri = document.get("uri")?.asString ?: return@forEach
        val values = change.asJsonObject.getAsJsonArray("edits") ?: return@forEach
        result.getOrPut(java.net.URI(uri).path) { mutableListOf() }.addAll(values.textEdits())
    }
    return result
}

internal fun isPathWithinRoot(path: String, root: String): Boolean {
    val normalizedPath = normalizeGuestPath(path) ?: return false
    val normalizedRoot = normalizeGuestPath(root) ?: return false
    return normalizedPath == normalizedRoot || normalizedPath.startsWith("$normalizedRoot/")
}

internal fun normalizeGuestPath(path: String): String? {
    if (!path.startsWith('/')) return null
    val segments = mutableListOf<String>()
    path.split('/').forEach { segment ->
        when (segment) {
            "", "." -> Unit
            ".." -> if (segments.isEmpty()) return null else segments.removeAt(segments.lastIndex)
            else -> segments.add(segment)
        }
    }
    return "/" + segments.joinToString("/")
}

internal fun JsonElement.textEdits(): List<LspTextEdit> =
    if (!isJsonArray) emptyList() else asJsonArray.mapNotNull { value ->
        if (!value.isJsonObject) return@mapNotNull null
        val item = value.asJsonObject
        val range = item.getAsJsonObject("range")?.toLspRange() ?: return@mapNotNull null
        val newText = item.get("newText")?.takeUnless { it.isJsonNull }?.asString ?: return@mapNotNull null
        LspTextEdit(range, newText)
    }

internal fun hoverContents(value: JsonElement): String? = when {
    value.isJsonPrimitive -> value.asString
    value.isJsonObject -> value.asJsonObject.get("value")?.asString
        ?: value.asJsonObject.get("language")?.asString?.let { language ->
            value.asJsonObject.get("value")?.asString?.let { "```$language\n$it\n```" }
        }
    value.isJsonArray -> value.asJsonArray.mapNotNull(::hoverContents).joinToString("\n\n").ifEmpty { null }
    else -> null
}

internal fun completionWordRange(text: String, position: LspPosition): LspRange {
    val line = text.lines().getOrNull(position.line).orEmpty()
    val cursor = position.character.coerceIn(0, line.length)
    var start = cursor
    while (start > 0 && isIdentifierCharacter(line[start - 1])) start--
    var end = cursor
    while (end < line.length && isIdentifierCharacter(line[end])) end++
    return LspRange(LspPosition(position.line, start), LspPosition(position.line, end))
}

internal fun wordAtPosition(text: String, position: LspPosition): String {
    val line = text.lines().getOrNull(position.line).orEmpty()
    val range = completionWordRange(text, position)
    return line.substring(range.start.character, range.end.character)
}

internal fun isIdentifierCharacter(char: Char): Boolean = char == '_' || char.isLetterOrDigit()
