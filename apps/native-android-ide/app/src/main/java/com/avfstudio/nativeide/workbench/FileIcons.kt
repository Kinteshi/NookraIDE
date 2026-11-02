package com.avfstudio.nativeide.workbench

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.avfstudio.nativeide.R
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** A file's icon from an icon theme: a glyph of the theme's font in its colour. */
data class FileIcon(val glyph: String, val color: Color)

/**
 * A VS Code file icon theme of the font kind (`vs-seti`, VS Code's default): icons by file name,
 * then by extension (longest first, `d.ts` before `ts`), then by the file's language, else the
 * default file icon. Light themes use the theme's `light` section.
 */
class FileIconTheme(json: JsonObject) {
    private class Icons(val definitions: Map<String, FileIcon>, section: JsonObject, fallback: String) {
        val file: FileIcon? = definitions[section.get("file")?.asString ?: fallback]
        val names: Map<String, String> = section.strings("fileNames")
        val extensions: Map<String, String> = section.strings("fileExtensions")
        val languages: Map<String, String> = section.strings("languageIds")
    }

    private val dark: Icons
    private val light: Icons

    init {
        val definitions = json.getAsJsonObject("iconDefinitions").entrySet().mapNotNull { (id, value) ->
            val definition = value.asJsonObject
            val character = definition.get("fontCharacter")?.asString ?: return@mapNotNull null
            val code = character.removePrefix("\\").toIntOrNull(16) ?: return@mapNotNull null
            val color = definition.get("fontColor")?.asString?.let(::parseHexColor) ?: Color.Unspecified
            id to FileIcon(String(Character.toChars(code)), color)
        }.toMap()
        val fallback = json.get("file")?.asString ?: "_default"
        dark = Icons(definitions, json, fallback)
        // The light section only lists what differs; the rest comes from the dark one.
        val lightSection = json.getAsJsonObject("light") ?: JsonObject()
        light = Icons(definitions, json.deepCopy().apply {
            listOf("fileNames", "fileExtensions", "languageIds").forEach { key ->
                val merged = getAsJsonObject(key) ?: JsonObject()
                lightSection.getAsJsonObject(key)?.entrySet()?.forEach { (name, id) -> merged.add(name, id) }
                add(key, merged)
            }
            lightSection.get("file")?.let { add("file", it) }
        }, fallback)
    }

    fun iconFor(name: String, dark: Boolean): FileIcon? {
        val icons = if (dark) this.dark else light
        val lower = name.lowercase()
        icons.names[lower]?.let { id -> icons.definitions[id]?.let { return it } }
        var dot = lower.indexOf('.')
        while (dot >= 0) {
            icons.extensions[lower.substring(dot + 1)]?.let { id -> icons.definitions[id]?.let { return it } }
            dot = lower.indexOf('.', dot + 1)
        }
        languageId(lower)?.let { language -> icons.languages[language]?.let { id -> icons.definitions[id]?.let { return it } } }
        return icons.file
    }

    companion object {
        fun load(context: android.content.Context): FileIconTheme? = runCatching {
            context.assets.open("icon-themes/vs-seti-icon-theme.json").bufferedReader().use { reader ->
                FileIconTheme(JsonParser.parseReader(reader).asJsonObject)
            }
        }.getOrNull()

        val font: FontFamily by lazy { FontFamily(Font(R.font.seti)) }

        private fun JsonObject.strings(key: String): Map<String, String> =
            getAsJsonObject(key)?.entrySet()?.associate { (name, value) -> name.lowercase() to value.asString }.orEmpty()

        private fun parseHexColor(value: String): Color? = runCatching {
            Color(android.graphics.Color.parseColor(value))
        }.getOrNull()
    }
}

/** VS Code's language id for a file name, as far as the icon theme needs it. */
internal fun languageId(name: String): String? {
    val lower = name.lowercase()
    when (lower) {
        "dockerfile", "containerfile" -> return "dockerfile"
        "makefile", "gnumakefile" -> return "makefile"
        "docker-compose.yml", "docker-compose.yaml", "compose.yml", "compose.yaml" -> return "dockercompose"
        ".gitignore", ".dockerignore", ".npmignore" -> return "ignore"
        ".env" -> return "dotenv"
    }
    if (lower.startsWith(".env.")) return "dotenv"
    return LANGUAGE_BY_EXTENSION[lower.substringAfterLast('.', "")]
}

private val LANGUAGE_BY_EXTENSION: Map<String, String> = buildMap {
    fun language(language: String, vararg extensions: String) {
        extensions.forEach { put(it, language) }
    }
    language("python", "py", "pyi", "pyw", "pyx", "ipy")
    language("javascript", "js", "mjs", "cjs")
    language("javascriptreact", "jsx")
    language("typescript", "ts", "mts", "cts")
    language("typescriptreact", "tsx")
    language("json", "json", "webmanifest")
    language("jsonc", "jsonc")
    language("jsonl", "jsonl", "ndjson")
    language("markdown", "md", "markdown", "mdx")
    language("html", "html", "htm", "xhtml")
    language("css", "css")
    language("scss", "scss")
    language("sass", "sass")
    language("less", "less")
    language("yaml", "yml", "yaml")
    language("xml", "xml", "xsd", "xsl", "plist", "svg")
    language("shellscript", "sh", "bash", "zsh", "fish", "ksh")
    language("powershell", "ps1", "psm1")
    language("bat", "bat", "cmd")
    language("c", "c", "h")
    language("cpp", "cpp", "cc", "cxx", "hpp", "hh", "hxx", "ino")
    language("cuda-cpp", "cu")
    language("csharp", "cs")
    language("fsharp", "fs", "fsx")
    language("java", "java")
    language("kotlin", "kt", "kts")
    language("groovy", "groovy")
    language("go", "go")
    language("rust", "rs")
    language("ruby", "rb")
    language("php", "php")
    language("perl", "pl", "pm")
    language("lua", "lua")
    language("r", "r")
    language("julia", "jl")
    language("swift", "swift")
    language("dart", "dart")
    language("sql", "sql")
    language("haskell", "hs")
    language("elixir", "ex", "exs")
    language("clojure", "clj", "cljs", "edn")
    language("ocaml", "ml", "mli")
    language("objective-c", "m")
    language("objective-cpp", "mm")
    language("properties", "properties", "ini", "cfg", "conf", "toml")
    language("terraform", "tf", "tfvars")
    language("latex", "tex", "ltx")
    language("vue", "vue")
    language("jinja", "jinja", "j2")
    language("makefile", "mk", "mak")
}

/**
 * A file's icon in Explorer, tabs and Open Editors: the icon theme's glyph when one is on
 * (`workbench.iconTheme`), else [fallback] in the theme's colour.
 */
@Composable
fun FileIconView(name: String, theme: FileIconTheme?, dark: Boolean, fallback: ImageVector, size: Dp = 18.dp, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    val icon = theme?.iconFor(name, dark)
    if (icon == null) {
        Icon(fallback, contentDescription = null, modifier = Modifier.size(size), tint = tint)
        return
    }
    // The theme draws its glyphs at 150% of the label's size; this fills the same box as the other icons.
    val fontSize = with(LocalDensity.current) { (size * 1.25f).toSp() }
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Text(
            icon.glyph,
            style = TextStyle(fontFamily = FileIconTheme.font, fontSize = fontSize, color = icon.color, textAlign = TextAlign.Center),
            maxLines = 1,
            softWrap = false,
        )
    }
}
