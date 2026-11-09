package io.github.kinteshi.quirecode.workbench

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
import io.github.kinteshi.quirecode.R
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asImageBitmap

/** A file's or folder's icon from an icon theme: a glyph of one of its fonts, or a picture (SVG or PNG). */
sealed interface FileIcon {
    data class Glyph(val glyph: String, val color: Color, val fontId: String?) : FileIcon
    data class Picture(val file: File) : FileIcon
}

/** An icon theme the app can show: Seti (built in) or one from an imported VSIX. */
data class IconThemeChoice(val id: String, val label: String, val pack: String?)

/**
 * A VS Code file icon theme (`contributes.iconThemes`): icons by file name, then by extension
 * (longest first, `d.ts` before `ts`), then by the file's language, else the default file icon;
 * folders by name, else the theme's folder icon. Light themes use the theme's `light` section.
 * Icons are font glyphs (Seti) or pictures ([root] holds them; Material Icon Theme's SVGs).
 */
class FileIconTheme(
    json: JsonObject,
    /** Where the theme file is: picture paths are relative to it. */
    private val folder: File? = null,
    /** The pack's folder; pictures outside it aren't used. */
    private val root: File? = folder,
    /** The theme's fonts by id; the first one is the default for glyphs without a `fontId`. */
    val fonts: Map<String, FontFamily> = emptyMap(),
) {
    private class Icons(val definitions: Map<String, FileIcon>, section: JsonObject, fallback: String) {
        val file: FileIcon? = definitions[section.get("file")?.asString ?: fallback]
        val folder: FileIcon? = section.get("folder")?.asString?.let(definitions::get)
        val folderExpanded: FileIcon? = section.get("folderExpanded")?.asString?.let(definitions::get) ?: folder
        val names: Map<String, String> = section.strings("fileNames")
        val extensions: Map<String, String> = section.strings("fileExtensions")
        val languages: Map<String, String> = section.strings("languageIds")
        val folderNames: Map<String, String> = section.strings("folderNames")
        val folderNamesExpanded: Map<String, String> = section.strings("folderNamesExpanded")
    }

    private val dark: Icons
    private val light: Icons
    /** The theme draws its own folder icons (VS Code then hides the tree's arrows if it asks to). */
    val hasFolderIcons: Boolean
    val hidesExplorerArrows: Boolean = json.get("hidesExplorerArrows")?.takeIf { it.isJsonPrimitive }?.asBoolean == true
    private val defaultFontId: String? = fonts.keys.firstOrNull()

    init {
        val base = root?.canonicalFile
        val definitions = json.getAsJsonObject("iconDefinitions")?.entrySet()?.mapNotNull { (id, value) ->
            val definition = value.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            definition.get("iconPath")?.takeIf { it.isJsonPrimitive }?.asString?.let { iconPath ->
                // Pictures must stay inside the theme's pack.
                val file = folder?.let { File(it, iconPath.removePrefix("./")).canonicalFile }
                    ?.takeIf { base != null && it.path.startsWith(base.path + File.separator) && it.isFile }
                    ?: return@mapNotNull null
                return@mapNotNull id to FileIcon.Picture(file)
            }
            val character = definition.get("fontCharacter")?.asString ?: return@mapNotNull null
            val code = character.removePrefix("\\").toIntOrNull(16) ?: return@mapNotNull null
            val color = definition.get("fontColor")?.asString?.let(::parseHexColor) ?: Color.Unspecified
            id to FileIcon.Glyph(String(Character.toChars(code)), color, definition.get("fontId")?.asString)
        }?.toMap().orEmpty()
        val fallback = json.get("file")?.asString ?: "_default"
        dark = Icons(definitions, json, fallback)
        // The light section only lists what differs; the rest comes from the dark one.
        val lightSection = json.getAsJsonObject("light") ?: JsonObject()
        light = Icons(definitions, json.deepCopy().apply {
            listOf("fileNames", "fileExtensions", "languageIds", "folderNames", "folderNamesExpanded").forEach { key ->
                val merged = getAsJsonObject(key) ?: JsonObject()
                lightSection.getAsJsonObject(key)?.entrySet()?.forEach { (name, id) -> merged.add(name, id) }
                add(key, merged)
            }
            listOf("file", "folder", "folderExpanded").forEach { key -> lightSection.get(key)?.let { add(key, it) } }
        }, fallback)
        hasFolderIcons = dark.folder != null
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

    /** A folder's icon (open or closed), or null when the theme has none (Seti). */
    fun folderIconFor(name: String, expanded: Boolean, dark: Boolean): FileIcon? {
        val icons = if (dark) this.dark else light
        val lower = name.lowercase()
        val byName = if (expanded) icons.folderNamesExpanded[lower] ?: icons.folderNames[lower] else icons.folderNames[lower]
        byName?.let { id -> icons.definitions[id]?.let { return it } }
        return if (expanded) icons.folderExpanded else icons.folder
    }

    /** The font a glyph is drawn with: its own, else the theme's default, else Seti's. */
    fun fontFor(icon: FileIcon.Glyph): FontFamily = icon.fontId?.let(fonts::get) ?: defaultFontId?.let(fonts::get) ?: setiFont

    companion object {
        const val SETI = "vs-seti"

        fun load(context: android.content.Context): FileIconTheme? = runCatching {
            context.assets.open("icon-themes/vs-seti-icon-theme.json").bufferedReader().use { reader ->
                FileIconTheme(JsonParser.parseReader(reader).asJsonObject)
            }
        }.getOrNull()

        val setiFont: FontFamily by lazy { FontFamily(Font(R.font.seti)) }

        /** The icon themes imported VSIX packs contribute (`contributes.iconThemes`). */
        fun installed(packsDirectory: File): List<IconThemeChoice> =
            packsDirectory.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith(".") }.sortedBy { it.name }.flatMap { pack ->
                iconThemeContributions(pack).map { (id, label, _) -> IconThemeChoice(id, label, pack.name) }
            }

        /** (id, label, path) of a pack's icon themes. */
        private fun iconThemeContributions(pack: File): List<Triple<String, String, String>> = runCatching {
            val manifest = JsonParser.parseString(File(pack, "extension/package.json").readText()).asJsonObject
            manifest.getAsJsonObject("contributes")?.getAsJsonArray("iconThemes")?.mapNotNull { entry ->
                val theme = entry.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val id = theme.get("id")?.asString ?: return@mapNotNull null
                val path = theme.get("path")?.asString ?: return@mapNotNull null
                Triple(id, theme.get("label")?.asString ?: id, path)
            }.orEmpty()
        }.getOrDefault(emptyList())

        /**
         * Loads icon theme [id] from the imported packs, with its fonts (TTF or OTF as they are;
         * WOFF converted to TTF once, beside it, since Android can't read WOFF).
         */
        fun loadInstalled(packsDirectory: File, id: String): FileIconTheme? {
            for (pack in packsDirectory.listFiles().orEmpty().filter { it.isDirectory }) {
                val (_, _, path) = iconThemeContributions(pack).firstOrNull { it.first == id } ?: continue
                return runCatching {
                    val extension = File(pack, "extension").canonicalFile
                    val file = File(extension, path.removePrefix("./")).canonicalFile
                    require(file.path.startsWith(extension.path + File.separator)) { "Icon theme outside its pack" }
                    val json = file.bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
                    val folder = file.parentFile ?: extension
                    val fonts = json.getAsJsonArray("fonts")?.mapNotNull { value ->
                        val font = value.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                        val fontId = font.get("id")?.asString ?: return@mapNotNull null
                        val source = font.getAsJsonArray("src")?.mapNotNull { it.takeIf { s -> s.isJsonObject }?.asJsonObject?.get("path")?.asString }
                            ?.map { File(folder, it.removePrefix("./")).canonicalFile }
                            ?.firstOrNull { it.path.startsWith(extension.path + File.separator) && it.isFile }
                            ?: return@mapNotNull null
                        val loadable = when (source.extension.lowercase()) {
                            "ttf", "otf" -> source
                            "woff" -> File(source.parentFile, source.nameWithoutExtension + ".avf.ttf").also { converted ->
                                if (!converted.isFile) converted.writeBytes(woffToSfnt(source.readBytes()))
                            }
                            else -> return@mapNotNull null
                        }
                        fontId to FontFamily(Font(loadable))
                    }?.toMap().orEmpty()
                    FileIconTheme(json, folder, extension, fonts)
                }.getOrNull()
            }
            return null
        }

        private fun JsonObject.strings(key: String): Map<String, String> =
            getAsJsonObject(key)?.entrySet()?.mapNotNull { (name, value) -> value.takeIf { it.isJsonPrimitive }?.let { name.lowercase() to it.asString } }?.toMap().orEmpty()
    }
}

/** "#rgb", "#rrggbb" or "#rrggbbaa" (VS Code's order) as a colour. */
internal fun parseHexColor(value: String): Color? {
    val hex = value.removePrefix("#")
    val full = when (hex.length) {
        3 -> hex.map { "$it$it" }.joinToString("") + "ff"
        6 -> hex + "ff"
        8 -> hex
        else -> return null
    }
    val rgba = full.toLongOrNull(16) ?: return null
    return Color(
        red = ((rgba shr 24) and 0xFF).toInt(),
        green = ((rgba shr 16) and 0xFF).toInt(),
        blue = ((rgba shr 8) and 0xFF).toInt(),
        alpha = (rgba and 0xFF).toInt(),
    )
}

/**
 * A WOFF 1.0 font as the TrueType/OpenType file it wraps: each table inflated (zlib) and laid
 * out again with an sfnt header. WOFF2 (Brotli) isn't read.
 */
internal fun woffToSfnt(woff: ByteArray): ByteArray {
    val input = java.nio.ByteBuffer.wrap(woff)
    require(woff.size >= 44 && input.getInt(0) == 0x774F4646) { "Not a WOFF font" }
    val flavor = input.getInt(4)
    val tableCount = input.getShort(12).toInt() and 0xFFFF
    data class Table(val tag: Int, val checksum: Int, val data: ByteArray)
    val tables = (0 until tableCount).map { index ->
        val entry = 44 + index * 20
        val tag = input.getInt(entry)
        val offset = input.getInt(entry + 4)
        val compressed = input.getInt(entry + 8)
        val original = input.getInt(entry + 12)
        val checksum = input.getInt(entry + 16)
        val raw = woff.copyOfRange(offset, offset + compressed)
        val data = if (compressed < original) {
            java.util.zip.Inflater().run {
                setInput(raw)
                val out = ByteArray(original)
                var read = 0
                while (read < original && !finished()) read += inflate(out, read, original - read)
                end()
                out
            }
        } else {
            raw
        }
        Table(tag, checksum, data)
    }
    var searchRange = 1
    var entrySelector = 0
    while (searchRange * 2 <= tableCount) {
        searchRange *= 2
        entrySelector++
    }
    searchRange *= 16
    val headerSize = 12 + 16 * tableCount
    val total = headerSize + tables.sumOf { (it.data.size + 3) / 4 * 4 }
    val out = java.nio.ByteBuffer.allocate(total)
    out.putInt(flavor).putShort(tableCount.toShort()).putShort(searchRange.toShort())
        .putShort(entrySelector.toShort()).putShort((tableCount * 16 - searchRange).toShort())
    var offset = headerSize
    tables.forEach { table ->
        out.putInt(table.tag).putInt(table.checksum).putInt(offset).putInt(table.data.size)
        offset += (table.data.size + 3) / 4 * 4
    }
    tables.forEach { table ->
        out.put(table.data)
        repeat((4 - table.data.size % 4) % 4) { out.put(0) }
    }
    return out.array()
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
 * A file's icon in Explorer, tabs and Open Editors: the icon theme's (`workbench.iconTheme`),
 * else [fallback] in the theme's colour. [folder] asks for a folder's icon (open when [expanded]);
 * a theme without folder icons shows [fallback].
 */
@Composable
fun FileIconView(
    name: String,
    theme: FileIconTheme?,
    dark: Boolean,
    fallback: ImageVector,
    size: Dp = 18.dp,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    folder: Boolean = false,
    expanded: Boolean = false,
) {
    val icon = if (folder) theme?.folderIconFor(name, expanded, dark) else theme?.iconFor(name, dark)
    when (icon) {
        null -> Icon(fallback, contentDescription = null, modifier = Modifier.size(size), tint = tint)
        is FileIcon.Glyph -> {
            // The theme draws its glyphs at 150% of the label's size; this fills the same box as the other icons.
            val fontSize = with(LocalDensity.current) { (size * 1.25f).toSp() }
            Box(Modifier.size(size), contentAlignment = Alignment.Center) {
                Text(
                    icon.glyph,
                    style = TextStyle(fontFamily = theme!!.fontFor(icon), fontSize = fontSize, color = icon.color.takeIf { it != Color.Unspecified } ?: tint, textAlign = TextAlign.Center),
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
        is FileIcon.Picture -> {
            val px = with(LocalDensity.current) { size.roundToPx() }
            val bitmap = remember(icon.file, px) { iconBitmap(icon.file, px) }
            if (bitmap != null) {
                androidx.compose.foundation.Image(bitmap, contentDescription = null, modifier = Modifier.size(size))
            } else {
                Icon(fallback, contentDescription = null, modifier = Modifier.size(size), tint = tint)
            }
        }
    }
}

/** Pictures drawn once per size: icon themes reuse a few hundred files across every row. */
private val iconBitmaps = object : android.util.LruCache<String, androidx.compose.ui.graphics.ImageBitmap>(512) {}

private fun iconBitmap(file: File, px: Int): androidx.compose.ui.graphics.ImageBitmap? {
    val key = "${file.path}@$px"
    iconBitmaps.get(key)?.let { return it }
    val bitmap = runCatching {
        if (file.extension.equals("svg", ignoreCase = true)) {
            val svg = com.caverock.androidsvg.SVG.getFromString(file.readText())
            svg.setDocumentWidth("100%")
            svg.setDocumentHeight("100%")
            android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888).also { target ->
                svg.renderToCanvas(android.graphics.Canvas(target), android.graphics.RectF(0f, 0f, px.toFloat(), px.toFloat()))
            }
        } else {
            android.graphics.BitmapFactory.decodeFile(file.path)?.let { android.graphics.Bitmap.createScaledBitmap(it, px, px, true) }
        }
    }.getOrNull()?.asImageBitmap() ?: return null
    iconBitmaps.put(key, bitmap)
    return bitmap
}
