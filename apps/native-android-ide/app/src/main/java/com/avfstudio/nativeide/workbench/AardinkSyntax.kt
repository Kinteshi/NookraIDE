package com.avfstudio.nativeide.workbench

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.aardarch.aardink.core.CommentSyntax
import com.aardarch.aardink.core.EditorTheme
import com.aardarch.aardink.core.NamedTokenType
import com.aardarch.aardink.core.TokenType
import com.aardarch.aardink.ui.fromColorScheme
import com.avfstudio.nativeide.DARK_SYNTAX_THEME
import com.avfstudio.nativeide.LIGHT_SYNTAX_THEME
import com.avfstudio.nativeide.PythonTextMate
import com.avfstudio.nativeide.assets.AssetPackGrammarLoader
import com.avfstudio.nativeide.document.DocumentSession
import com.avfstudio.nativeide.editor.BRACKET_COLORS
import com.avfstudio.nativeide.editor.LIGHT_BRACKET_COLORS
import com.avfstudio.nativeide.editor.TextMateTokenizer
import com.avfstudio.nativeide.editor.semanticColorsByKind
import com.avfstudio.nativeide.editor.textMateTokenColors
import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import org.eclipse.tm4e.core.registry.IGrammarSource
import org.eclipse.tm4e.core.registry.IRegistryOptions
import org.eclipse.tm4e.core.registry.IThemeSource
import org.eclipse.tm4e.core.registry.Registry

/**
 * The syntax colours in effect, as the sora editor gets them (`applyEditorTheme`): an imported VS Code
 * theme, else the Material You palette, else the built-in light or dark one.
 */
internal class SyntaxTheme(
    /** A file name: tm4e tells the theme's format by its extension. */
    val name: String,
    val json: String,
    val semanticColors: Map<String, String>,
    val light: Boolean,
    val imported: Boolean,
) {
    val key: String = "$name/${json.hashCode()}/${semanticColors.hashCode()}/$light"
}

internal fun syntaxTheme(context: Context, colors: ColorScheme, dark: Boolean, syntax: SyntaxColors): SyntaxTheme {
    val importedJson = context.getSharedPreferences("native-ide-assets", Context.MODE_PRIVATE).getString("theme-json", null)
    if (importedJson != null) return SyntaxTheme(IMPORTED_THEME_FILE, importedJson, loadSavedSemanticColors(context), light = false, imported = true)
    if (syntax == SyntaxColors.MaterialYou) {
        val palette = SyntaxPalette.materialYou(colors, dark)
        return SyntaxTheme("material-you.tmTheme.json", palette.textMateTheme("Material You", colors.surface), palette.semanticColors(), !dark, imported = false)
    }
    val file = if (dark) DARK_SYNTAX_THEME else LIGHT_SYNTAX_THEME
    val json = context.assets.open("textmate/$file").use { it.reader().readText() }
    return SyntaxTheme(file, json, loadSavedSemanticColors(context), !dark, imported = false)
}

/** Grammars and colours for the Aardink editor from the same files as sora's, through a tm4e registry of its own. */
internal object AardinkSyntax {
    private const val PYTHON_SCOPE = "source.python"
    private var registry: Pair<String, Registry>? = null

    /**
     * One registry for the theme in effect: tm4e colours a grammar's tokens with its registry's theme.
     * A theme it can't read leaves the registry without one, so the editor still opens.
     */
    @Synchronized
    fun registry(context: Context, theme: SyntaxTheme): Registry {
        registry?.takeIf { it.first == theme.key }?.let { return it.second }
        val app = context.applicationContext
        // Grammar packs are read in when sora's registry is first set up.
        PythonTextMate.initialize(app)
        val made = Registry(
            object : IRegistryOptions {
                override fun getGrammarSource(scopeName: String): IGrammarSource? = grammarSource(app, scopeName)
            },
        )
        runCatching {
            made.setTheme(IThemeSource.fromInputStream(ByteArrayInputStream(theme.json.toByteArray()), theme.name, Charsets.UTF_8))
        }.onFailure { android.util.Log.e("AardinkSyntax", "Could not read syntax theme ${theme.name}", it) }
        registry = theme.key to made
        return made
    }

    /** The document's grammar as a tokenizer; null when no grammar knows the file (it stays plain). */
    fun tokenizerFor(registry: Registry, document: DocumentSession): TextMateTokenizer? {
        val scope = if (document.languageId in setOf("py", "pyi")) {
            PYTHON_SCOPE
        } else {
            AssetPackGrammarLoader.scopeNameFor(document.path.substringAfterLast('/'))
        } ?: return null
        val grammar = runCatching { registry.loadGrammar(scope) }.getOrNull() ?: return null
        return TextMateTokenizer(grammar, if (scope == PYTHON_SCOPE) CommentSyntax(line = "#") else null)
    }

    fun editorTheme(theme: SyntaxTheme, registry: Registry, colors: ColorScheme, dark: Boolean): EditorTheme {
        val (tokenColors, tokenStyles) = textMateTokenColors(registry.colorMap)
        val semantic = semanticColorsByKind(theme.light, theme.semanticColors)
            .map { (kind, color) -> semanticType(kind) to Color(color) }
            .toMap()
        val base = EditorTheme.fromColorScheme(colors, dark)
        return base.copy(
            // An imported theme brings its own background, as it does in the sora editor.
            background = if (theme.imported) themeBackground(theme.json) ?: base.background else base.background,
            tokenColors = tokenColors + semantic,
            tokenFontStyles = tokenStyles,
            bracketPairColors = (if (theme.light) LIGHT_BRACKET_COLORS else BRACKET_COLORS).map(::Color),
        )
    }

    /** What a language server's token type is drawn as. */
    fun semanticType(kind: String): TokenType = NamedTokenType("semantic.${kind.substringBefore('.')}")

    private fun grammarSource(context: Context, scopeName: String): IGrammarSource? = when (scopeName) {
        PYTHON_SCOPE -> IGrammarSource.fromInputStream(
            context.assets.open("textmate/python.tmLanguage.json"),
            "python.tmLanguage.json",
            Charsets.UTF_8,
        )
        else -> AssetPackGrammarLoader.grammarFile(scopeName)?.let { IGrammarSource.fromFile(it) }
    }

    private fun themeBackground(json: String): Color? = runCatching {
        val root = JsonParser.parseString(json).asJsonObject
        val value = root.getAsJsonObject("colors")?.get("editor.background")?.asString
            ?: root.getAsJsonArray("settings")?.firstOrNull { !it.asJsonObject.has("scope") }
                ?.asJsonObject?.getAsJsonObject("settings")?.get("background")?.asString
        value?.let(::parseThemeColor)
    }.getOrNull()
}
