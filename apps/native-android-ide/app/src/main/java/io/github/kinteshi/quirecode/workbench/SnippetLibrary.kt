package io.github.kinteshi.quirecode.workbench

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import java.io.File
import java.io.StringReader

/**
 * A snippet from a VS Code snippet file: typing one of its [prefixes] offers it in the suggest
 * popup. [languages] are VS Code language ids; null means every language (a `.code-snippets`
 * file without `scope`).
 */
data class Snippet(
    val name: String,
    val prefixes: List<String>,
    val body: String,
    val description: String?,
    val languages: Set<String>?,
    /** Where it comes from, shown beside it: a pack's name or the workspace file. */
    val source: String,
)

/**
 * Snippets from a VS Code snippet file (JSON with comments): `{ "Name": { "prefix", "body",
 * "description", "scope" } }`. A language-specific file (a pack's `contributes.snippets` entry)
 * passes its [language]; a `.code-snippets` file takes each snippet's `scope`.
 */
fun parseSnippetFile(text: String, language: String?, source: String): List<Snippet> {
    val root = runCatching {
        JsonParser.parseReader(JsonReader(StringReader(text)).apply { isLenient = true })
    }.getOrNull()?.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return emptyList()
    return root.entrySet().mapNotNull { (name, value) ->
        val entry = value.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@mapNotNull null
        val body = entry.strings("body")?.joinToString("\n") ?: return@mapNotNull null
        val prefixes = entry.strings("prefix").orEmpty().filter(String::isNotBlank)
        val scope = entry.get("scope")?.takeIf { it.isJsonPrimitive }?.asString
            ?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)?.toSet()
        Snippet(
            name = name,
            prefixes = prefixes,
            body = body,
            description = entry.get("description")?.takeIf { it.isJsonPrimitive }?.asString,
            languages = language?.let(::setOf) ?: scope,
            source = source,
        )
    }
}

/** A string or an array of strings. */
private fun JsonObject.strings(name: String): List<String>? {
    val value = get(name) ?: return null
    return when {
        value.isJsonPrimitive -> listOf(value.asString)
        value.isJsonArray -> value.asJsonArray.mapNotNull { it.takeIf(JsonElement::isJsonPrimitive)?.asString }
        else -> null
    }
}

/**
 * The snippets in the installed packs (imported or downloaded VSIX files), from each pack's
 * `contributes.snippets`, and the file extensions its `contributes.languages` declares, so a
 * file the app has no language id for still finds its pack's snippets.
 */
fun loadPackSnippets(packsDirectory: File): Pair<List<Snippet>, Map<String, String>> {
    val snippets = mutableListOf<Snippet>()
    val extensions = mutableMapOf<String, String>()
    packsDirectory.listFiles()?.filter(File::isDirectory)?.forEach { pack ->
        val root = File(pack, "extension")
        val manifest = runCatching { JsonParser.parseString(File(root, "package.json").readText()).asJsonObject }.getOrNull() ?: return@forEach
        val name = manifest.get("displayName")?.asString?.takeUnless { it.startsWith("%") } ?: pack.name
        val contributes = manifest.getAsJsonObject("contributes") ?: return@forEach
        contributes.getAsJsonArray("languages")?.forEach { language ->
            val entry = language.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@forEach
            val id = entry.get("id")?.asString ?: return@forEach
            entry.getAsJsonArray("extensions")?.forEach { extension ->
                extensions.putIfAbsent(extension.asString.removePrefix(".").lowercase(), id)
            }
        }
        contributes.getAsJsonArray("snippets")?.forEach { contribution ->
            val entry = contribution.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@forEach
            val path = entry.get("path")?.asString ?: return@forEach
            val file = File(root, path).canonicalFile
            // Only files inside the pack (the installer checked too).
            if (!file.path.startsWith(root.canonicalPath + File.separator) || !file.isFile) return@forEach
            val language = entry.get("language")?.asString
            snippets += parseSnippetFile(file.readText(), language, name)
        }
    }
    return snippets to extensions
}

/** The snippets that apply to [languageId], by name. */
fun List<Snippet>.forLanguage(languageId: String): List<Snippet> =
    filter { snippet -> snippet.languages == null || languageId in snippet.languages }.sortedBy { it.name.lowercase() }

/** `editor.snippetSuggestions`: where snippets go in the suggest popup. */
enum class SnippetSuggestions(val key: String, val label: String) {
    Top("top", "Above other suggestions"),
    Bottom("bottom", "Below other suggestions"),
    Inline("inline", "Mixed with other suggestions"),
    None("none", "Not in suggestions"),
}

/** A starter `.code-snippets` file for Snippets: Configure Workspace Snippets. */
const val WORKSPACE_SNIPPETS_TEMPLATE = """{
	// Workspace snippets: each has a prefix that offers it in suggestions, a body and a description.
	// "scope" names the languages it is for (python, typescript, …); without it, every file gets it.
	// $1, $2 … are tab stops, ${'$'}{1:label} a stop with a default, $0 where the cursor ends.
	// "Print to console": {
	// 	"scope": "python",
	// 	"prefix": "pr",
	// 	"body": ["print(${'$'}{1:value})", "$0"],
	// 	"description": "Print a value"
	// }
}
"""
