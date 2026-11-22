package io.github.kinteshi.nookraide.assets

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.IOException

data class ImportedTheme(
    val name: String,
    val textMateThemeJson: String,
    val workbenchColors: Map<String, String>,
    val semanticTokenColors: Map<String, String>,
)

class VsCodeThemeImporter {
    fun import(themeFile: File, extensionRoot: File): ImportedTheme {
        val root = extensionRoot.canonicalFile
        val theme = readObject(themeFile)
        val themeDirectory = themeFile.parentFile ?: root
        return convertTheme(theme, themeFile.nameWithoutExtension, themeDirectory, root)
    }

    fun importJson(json: String, fallbackName: String): ImportedTheme {
        val element = try {
            JsonParser.parseString(json)
        } catch (error: RuntimeException) {
            throw IOException("Theme file is invalid JSON", error)
        }
        if (!element.isJsonObject) throw IOException("Theme file must contain a JSON object")
        return convertTheme(element.asJsonObject, fallbackName, null, null)
    }

    private fun convertTheme(
        theme: JsonObject,
        fallbackName: String,
        themeDirectory: File?,
        extensionRoot: File?,
    ): ImportedTheme {
        val workbenchColors = stringMap(theme.getAsJsonObject("colors"))
        val semanticColors = semanticColors(theme.getAsJsonObject("semanticTokenColors"))
        val settings = JsonArray()
        val defaults = JsonObject()
        val editorForeground = workbenchColors["editor.foreground"]
        val editorBackground = workbenchColors["editor.background"]
        if (editorForeground != null) defaults.addProperty("foreground", editorForeground)
        if (editorBackground != null) defaults.addProperty("background", editorBackground)
        if (defaults.size() > 0) {
            settings.add(JsonObject().apply { add("settings", defaults) })
        }
        val tokenRules = collectTokenRules(
            theme.get("include"),
            themeDirectory,
            extensionRoot,
            mutableSetOf(),
            0,
        ) + collectTokenRules(
            theme.get("tokenColors"),
            themeDirectory,
            extensionRoot,
            mutableSetOf(),
            0,
        )
        tokenRules.forEach { settings.add(it) }
        val textMateTheme = JsonObject().apply {
            addProperty("name", theme.get("name")?.asString ?: fallbackName)
            add("settings", settings)
        }
        return ImportedTheme(
            name = textMateTheme.get("name").asString,
            textMateThemeJson = Gson().toJson(textMateTheme),
            workbenchColors = workbenchColors,
            semanticTokenColors = semanticColors,
        )
    }

    private fun collectTokenRules(
        tokenColors: JsonElement?,
        baseDirectory: File?,
        extensionRoot: File?,
        visited: MutableSet<String>,
        depth: Int,
    ): List<JsonObject> {
        if (tokenColors == null || tokenColors.isJsonNull) return emptyList()
        if (depth > MAX_INCLUDE_DEPTH) throw IOException("Theme token include nesting is too deep")
        if (tokenColors.isJsonPrimitive && tokenColors.asJsonPrimitive.isString) {
            val base = baseDirectory ?: throw IOException("Theme JSON include requires a VSIX asset pack")
            val root = extensionRoot ?: throw IOException("Theme JSON include requires a VSIX asset pack")
            val include = resolveThemeFile(base, tokenColors.asString, root)
            val canonical = include.canonicalPath
            if (!visited.add(canonical)) throw IOException("Theme token include cycle: ${include.name}")
            val includedTheme = readJson(include)
            val includedDirectory = include.parentFile ?: root
            val rules = if (includedTheme.isJsonArray) {
                includedTheme.asJsonArray.mapNotNull(::tokenRule)
            } else if (includedTheme.isJsonObject) {
                val objectTheme = includedTheme.asJsonObject
                collectTokenRules(
                    objectTheme.get("include"),
                    includedDirectory,
                    root,
                    visited,
                    depth + 1,
                ) + collectTokenRules(
                    objectTheme.get("tokenColors"),
                    includedDirectory,
                    root,
                    visited,
                    depth + 1,
                )
            } else {
                throw IOException("Theme token include must contain an array or object")
            }
            visited.remove(canonical)
            return rules
        }
        if (!tokenColors.isJsonArray) throw IOException("Theme tokenColors must be an array or a relative path")
        return tokenColors.asJsonArray.flatMap { entry ->
            if (!entry.isJsonObject) return@flatMap emptyList()
            val objectEntry = entry.asJsonObject
            val included = objectEntry.get("include")
            val inherited = if (included != null) {
                collectTokenRules(included, baseDirectory, extensionRoot, visited, depth + 1)
            } else {
                emptyList()
            }
            inherited + listOfNotNull(tokenRule(entry))
        }
    }

    private fun tokenRule(element: JsonElement): JsonObject? {
        if (!element.isJsonObject) return null
        val input = element.asJsonObject
        val scopes = input.get("scope") ?: return null
        val sourceSettings = input.getAsJsonObject("settings") ?: return null
        val filteredSettings = JsonObject()
        listOf("foreground", "background", "fontStyle").forEach { key ->
            sourceSettings.get(key)?.let { filteredSettings.add(key, it.deepCopy()) }
        }
        if (filteredSettings.size() == 0) return null
        return JsonObject().apply {
            add("scope", scopes.deepCopy())
            add("settings", filteredSettings)
        }
    }

    private fun resolveThemeFile(baseDirectory: File, path: String, extensionRoot: File): File {
        if (path.isBlank() || path.startsWith('/') || '\\' in path) {
            throw IOException("Theme include path is invalid")
        }
        val candidate = File(baseDirectory, path).canonicalFile
        if (!candidate.path.startsWith(extensionRoot.canonicalPath + File.separator) || !candidate.isFile) {
            throw IOException("Theme include is missing or outside the extension: $path")
        }
        return candidate
    }

    private fun readObject(file: File): JsonObject {
        val element = readJson(file)
        if (!element.isJsonObject) throw IOException("Theme file must contain a JSON object")
        return element.asJsonObject
    }

    private fun readJson(file: File): JsonElement = try {
        JsonParser.parseString(file.readText(Charsets.UTF_8))
    } catch (error: RuntimeException) {
        throw IOException("Theme file is invalid JSON: ${file.name}", error)
    }

    private fun stringMap(objectValue: JsonObject?): Map<String, String> =
        objectValue?.entrySet()?.mapNotNull { (key, value) ->
            if (value.isJsonPrimitive && value.asJsonPrimitive.isString) key to value.asString else null
        }?.toMap().orEmpty()

    private fun semanticColors(objectValue: JsonObject?): Map<String, String> =
        objectValue?.entrySet()?.mapNotNull { (key, value) ->
            when {
                value.isJsonPrimitive && value.asJsonPrimitive.isString -> key to value.asString
                value.isJsonObject -> value.asJsonObject.get("foreground")
                    ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                    ?.let { key to it.asString }
                else -> null
            }
        }?.toMap().orEmpty()

    private companion object {
        const val MAX_INCLUDE_DEPTH = 8
    }
}
