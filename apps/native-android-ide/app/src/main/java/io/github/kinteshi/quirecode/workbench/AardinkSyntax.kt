package io.github.kinteshi.quirecode.workbench

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.aardarch.aardink.core.CommentSyntax
import com.aardarch.aardink.core.EditorTheme
import com.aardarch.aardink.core.NamedTokenType
import com.aardarch.aardink.core.TokenType
import com.aardarch.aardink.ui.fromColorScheme
import io.github.kinteshi.quirecode.DARK_SYNTAX_THEME
import io.github.kinteshi.quirecode.LIGHT_SYNTAX_THEME
import io.github.kinteshi.quirecode.assets.AssetPackGrammarLoader
import io.github.kinteshi.quirecode.document.DocumentSession
import io.github.kinteshi.quirecode.editor.BRACKET_COLORS
import io.github.kinteshi.quirecode.editor.LanguageRules
import io.github.kinteshi.quirecode.editor.LIGHT_BRACKET_COLORS
import io.github.kinteshi.quirecode.editor.TextMateTokenizer
import io.github.kinteshi.quirecode.editor.semanticColorsByKind
import io.github.kinteshi.quirecode.editor.textMateTokenColors
import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import org.eclipse.tm4e.core.registry.IGrammarSource
import org.eclipse.tm4e.core.registry.IRegistryOptions
import org.eclipse.tm4e.core.registry.IThemeSource
import org.eclipse.tm4e.core.registry.Registry

/** The syntax colours in effect: an imported VS Code theme, else the Material You palette, else the built-in light or dark one. */
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

/** Grammars and colours for the editor, through a tm4e registry. */
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
        AssetPackGrammarLoader.loadInstalledOnce(app)
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
    fun tokenizerFor(registry: Registry, document: DocumentSession, rules: LanguageRules?): TextMateTokenizer? {
        val scope = scopeFor(document) ?: return null
        val grammar = runCatching { registry.loadGrammar(scope) }.getOrNull() ?: return null
        return TextMateTokenizer(grammar, rules?.lineComment?.let { CommentSyntax(line = it) })
    }

    /**
     * The grammar for a code fence in hover, signature help or a suggestion's details, by the language it's tagged with
     * (` ```python `, ` ```ts `); null for one no grammar knows, which Aardink shows plain. Without semantic colours: those
     * are the document's, by its lines.
     */
    fun codeFenceTokenizer(registry: Registry, language: String): TextMateTokenizer? {
        val id = language.trim().lowercase()
        val scope = when (id) {
            "python", "py", "python3", "pyi", "pycon" -> PYTHON_SCOPE
            else -> AssetPackGrammarLoader.scopeNameForExtension(FENCE_EXTENSIONS[id] ?: id)
        } ?: return null
        val grammar = runCatching { registry.loadGrammar(scope) }.getOrNull() ?: return null
        return TextMateTokenizer(grammar, null)
    }

    /** VS Code's language ids that aren't their files' extension. */
    private val FENCE_EXTENSIONS = mapOf(
        "typescript" to "ts", "javascript" to "js", "typescriptreact" to "tsx", "javascriptreact" to "jsx",
        "shellscript" to "sh", "bash" to "sh", "shell" to "sh", "zsh" to "sh", "rust" to "rs", "golang" to "go",
        "kotlin" to "kt", "csharp" to "cs", "ruby" to "rb", "markdown" to "md", "yml" to "yaml", "c++" to "cpp",
    )

    /**
     * The file's `language-configuration.json` (closing pairs, Enter, indentation, comments): the app's
     * own for Python, a grammar pack's for its languages. Null when there's none, or it can't be read.
     */
    fun rulesFor(context: Context, document: DocumentSession): LanguageRules? {
        val scope = scopeFor(document) ?: return null
        val json = runCatching {
            if (scope == PYTHON_SCOPE) {
                context.assets.open("textmate/python.language-configuration.json").use { it.reader().readText() }
            } else {
                AssetPackGrammarLoader.configurationFile(scope)?.readText()
            }
        }.getOrNull() ?: return null
        return runCatching { LanguageRules.parse(json) }
            .onFailure { android.util.Log.w("AardinkSyntax", "Could not read the language configuration for $scope", it) }
            .getOrNull()
    }

    private fun scopeFor(document: DocumentSession): String? =
        if (document.languageId in setOf("py", "pyi")) PYTHON_SCOPE else AssetPackGrammarLoader.scopeNameFor(document.path.substringAfterLast('/'))

    fun editorTheme(theme: SyntaxTheme, registry: Registry, colors: ColorScheme, dark: Boolean): EditorTheme {
        val (tokenColors, tokenStyles) = textMateTokenColors(registry.colorMap)
        val semantic = semanticColorsByKind(theme.light, theme.semanticColors)
            .map { (kind, color) -> semanticType(kind) to Color(color) }
            .toMap()
        val base = EditorTheme.fromColorScheme(colors, dark)
        return base.copy(
            // An imported theme brings its own background.
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
