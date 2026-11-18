package io.github.kinteshi.quirecode.workbench

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.aardarch.aardink.core.ChromeColors
import com.aardarch.aardink.core.CommentSyntax
import com.aardarch.aardink.core.EditorTheme
import com.aardarch.aardink.core.IncrementalTokenizer
import com.aardarch.aardink.core.NamedTokenType
import com.aardarch.aardink.core.TokenType
import com.aardarch.aardink.languages.BuiltInLanguages
import com.aardarch.aardink.languages.LanguageDefinition
import com.aardarch.aardink.languages.LanguageRegistry
import com.aardarch.aardink.ui.EditorThemeParser
import com.aardarch.aardink.ui.EditorThemes
import com.aardarch.aardink.ui.fromColorScheme
import io.github.kinteshi.quirecode.DARK_SYNTAX_THEME
import io.github.kinteshi.quirecode.LIGHT_SYNTAX_THEME
import io.github.kinteshi.quirecode.assets.AssetPackGrammarLoader
import io.github.kinteshi.quirecode.assets.LanguagePack
import io.github.kinteshi.quirecode.document.DocumentSession
import io.github.kinteshi.quirecode.editor.BRACKET_COLORS
import io.github.kinteshi.quirecode.editor.BuiltInSemanticTokenizer
import io.github.kinteshi.quirecode.editor.LanguageRules
import io.github.kinteshi.quirecode.editor.LIGHT_BRACKET_COLORS
import io.github.kinteshi.quirecode.editor.SemanticTokenizer
import io.github.kinteshi.quirecode.editor.TextMateTokenizer
import io.github.kinteshi.quirecode.editor.semanticColorsByKind
import io.github.kinteshi.quirecode.editor.textMateTokenColors
import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import org.eclipse.tm4e.core.registry.IGrammarSource
import org.eclipse.tm4e.core.registry.IRegistryOptions
import org.eclipse.tm4e.core.registry.IThemeSource
import org.eclipse.tm4e.core.registry.Registry

/** The syntax colours in effect: an imported VS Code theme, Material You, High contrast, or the built-in Classic light/dark theme. */
internal class SyntaxTheme(
    /** A file name: tm4e tells the theme's format by its extension. */
    val name: String,
    val json: String,
    val semanticColors: Map<String, String>,
    val light: Boolean,
    val imported: Boolean,
    val workbenchColors: Map<String, String> = emptyMap(),
    val highContrast: Boolean = false,
    val palette: SyntaxPalette? = null,
) {
    val key: String = "$name/${json.hashCode()}/${semanticColors.hashCode()}/$light/${workbenchColors.hashCode()}/$highContrast/${palette?.javaClass?.simpleName}"

    override fun equals(other: Any?): Boolean = other is SyntaxTheme && key == other.key
    override fun hashCode(): Int = key.hashCode()
}

private val assetTextCache = java.util.concurrent.ConcurrentHashMap<String, String>()

private fun readCachedAsset(context: Context, assetPath: String): String =
    assetTextCache.getOrPut(assetPath) {
        context.applicationContext.assets.open(assetPath).use { it.reader().readText() }
    }

internal fun syntaxTheme(context: Context, colors: ColorScheme, dark: Boolean, syntax: SyntaxColors): SyntaxTheme {
    val prefs = context.getSharedPreferences("native-ide-assets", Context.MODE_PRIVATE)
    val importedJson = prefs.getString("theme-json", null)
    if (importedJson != null) {
        return SyntaxTheme(
            IMPORTED_THEME_FILE,
            importedJson,
            loadSavedSemanticColors(context),
            light = false,
            imported = true,
            workbenchColors = loadSavedWorkbenchColors(context),
        )
    }
    if (syntax == SyntaxColors.MaterialYou) {
        val palette = SyntaxPalette.materialYou(colors, dark)
        return SyntaxTheme(
            "material-you.tmTheme.json",
            palette.textMateTheme("Material You", colors.surface),
            palette.semanticColors(),
            !dark,
            imported = false,
            palette = palette,
        )
    }
    val file = if (dark) DARK_SYNTAX_THEME else LIGHT_SYNTAX_THEME
    val json = readCachedAsset(context, "textmate/$file")
    val highContrast = syntax == SyntaxColors.HighContrast
    return SyntaxTheme(
        file,
        json,
        loadSavedSemanticColors(context),
        !dark,
        imported = false,
        highContrast = highContrast,
        palette = if (highContrast) null else SyntaxPalette.classic(dark),
    )
}

/** Grammars and colours for the editor: Aardink built-in languages first, falling back to a tm4e registry. */
internal object AardinkSyntax {
    private const val PYTHON_SCOPE = "source.python"
    private var registry: Pair<String, Registry>? = null
    private var tokenColorsCache: Pair<List<String>, Pair<Map<TokenType, Color>, Map<TokenType, com.aardarch.aardink.core.TokenFontStyle>>>? = null
    private val editorThemeCache = object : android.util.LruCache<String, EditorTheme>(6) {}
    private var fenceTokenizerRegistry: Registry? = null
    private val fenceTokenizerCache = java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<IncrementalTokenizer>>()

    /**
     * All 20 built-in syntax languages from `aardink-languages`, excluding PlainText so `.txt` files
     * continue to count as unhighlighted text rather than claiming an active grammar.
     */
    private val builtInRegistry: LanguageRegistry by lazy {
        LanguageRegistry.withBuiltIns().apply {
            unregister(BuiltInLanguages.PlainText.id)
        }
    }

    /** Built-in Aardink language matching [path] or [languageId], or null to fall back to tm4e. */
    fun builtInLanguageFor(path: String, languageId: String = ""): LanguageDefinition? {
        val fileName = path.substringAfterLast('/')
        if (fileName.isNotBlank()) builtInRegistry.byFileName(fileName)?.let { return it }
        val normalized = languageId.trim().removePrefix(".").lowercase()
        if (normalized.isEmpty()) return null
        return builtInRegistry.byExtension(normalized) ?: builtInRegistry.byId(normalized)
    }

    /** Built-in Aardink language for a code-fence or cell language identifier (such as `"kt"` or `"typescript"`). */
    fun builtInLanguageForId(language: String): LanguageDefinition? {
        val normalized = language.trim().lowercase()
        if (normalized.isEmpty() || normalized == BuiltInLanguages.PlainText.id) return null
        return builtInRegistry.byId(normalized)
            ?: builtInRegistry.byExtension(FENCE_EXTENSIONS[normalized] ?: normalized)
    }

    fun hasBuiltInLanguage(path: String): Boolean = builtInLanguageFor(path) != null

    /** True when Aardink's built-in languages already cover core files of [pack]. */
    fun builtInCovers(pack: LanguagePack): Boolean =
        pack.extensions.any { builtInRegistry.byExtension(it) != null } ||
            pack.filenames.any { builtInRegistry.byFileName(it) != null }

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

    /** The document's tokenizer (Aardink built-in language first, else tm4e grammar); null stays plain. */
    fun tokenizerFor(registry: Registry, document: DocumentSession, rules: LanguageRules?): SemanticTokenizer? {
        builtInLanguageFor(document.path, document.languageId)?.let { def ->
            return BuiltInSemanticTokenizer(def.tokenizer)
        }
        val scope = scopeFor(document) ?: return null
        val grammar = runCatching { registry.loadGrammar(scope) }.getOrNull() ?: return null
        return TextMateTokenizer(grammar, rules?.lineComment?.let { CommentSyntax(line = it) })
    }

    /**
     * The tokenizer for a code fence or notebook cell (` ```python `, ` ```ts `, ` ```kt `): Aardink
     * built-in language first, else tm4e; null when unknown (shown plain).
     */
    fun codeFenceTokenizer(registry: Registry, language: String): IncrementalTokenizer? {
        builtInLanguageForId(language)?.let { return it.tokenizer }
        val id = language.trim().lowercase()
        val scope = when (id) {
            "python", "py", "python3", "pyi", "pycon" -> PYTHON_SCOPE
            else -> AssetPackGrammarLoader.scopeNameForExtension(FENCE_EXTENSIONS[id] ?: id)
        } ?: return null
        val grammar = runCatching { registry.loadGrammar(scope) }.getOrNull() ?: return null
        return TextMateTokenizer(grammar, null)
    }

    /** Shared code-fence / cell tokenizer lookup cached per [registry]. */
    @Synchronized
    fun fenceTokenizerProvider(registry: Registry): (String) -> IncrementalTokenizer? {
        if (fenceTokenizerRegistry !== registry) {
            fenceTokenizerRegistry = registry
            fenceTokenizerCache.clear()
        }
        return { language ->
            fenceTokenizerCache.getOrPut(language) {
                java.util.Optional.ofNullable(codeFenceTokenizer(registry, language))
            }.orElse(null)
        }
    }
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
                readCachedAsset(context, "textmate/python.language-configuration.json")
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

    /**
     * Resolves Aardink token colours, font styles, and optional imported background for [theme]
     * so built-in languages use Classic, Material You, or imported VS Code JSON themes too.
     */
    internal fun resolveBuiltInTheme(theme: SyntaxTheme): Triple<Map<TokenType, Color>, Map<TokenType, com.aardarch.aardink.core.TokenFontStyle>, Color?> {
        if (theme.imported) {
            val parsed = EditorThemeParser.fromJson(theme.json)
            return Triple(
                parsed?.tokenColors.orEmpty(),
                parsed?.tokenFontStyles.orEmpty(),
                parsed?.background,
            )
        }
        val palette = theme.palette ?: return Triple(emptyMap(), emptyMap(), null)
        return Triple(palette.aardinkTokenColors(), palette.aardinkTokenStyles(), null)
    }

    @Synchronized
    private fun cachedTokenColors(registry: Registry): Pair<Map<TokenType, Color>, Map<TokenType, com.aardarch.aardink.core.TokenFontStyle>> {
        val map = registry.colorMap
        tokenColorsCache?.takeIf { it.first == map }?.let { return it.second }
        return textMateTokenColors(map).also { tokenColorsCache = map to it }
    }

    fun editorTheme(theme: SyntaxTheme, registry: Registry, colors: ColorScheme, dark: Boolean): EditorTheme {
        val cacheKey = "${theme.key}/$dark/${colors.surface.value}/${colors.primary.value}/${colors.onSurface.value}/${colors.outlineVariant.value}/${colors.surfaceContainerHigh.value}/${colors.surfaceContainerHighest.value}"
        editorThemeCache.get(cacheKey)?.let { return it }
        val (tokenColors, tokenStyles) = cachedTokenColors(registry)
        val semantic = semanticColorsByKind(theme.light, theme.semanticColors)
            .map { (kind, color) -> semanticType(kind) to Color(color) }
            .toMap()
        val built = if (theme.highContrast) {
            val hc = if (dark) EditorThemes.HighContrastDark else EditorThemes.HighContrastLight
            hc.copy(
                tokenColors = hc.tokenColors + tokenColors + semantic,
                tokenFontStyles = hc.tokenFontStyles + tokenStyles,
            )
        } else {
            val base = EditorTheme.fromColorScheme(colors, dark)
            val (builtInColors, builtInStyles, importedBg) = resolveBuiltInTheme(theme)
            fun chromeColor(vararg keys: String): Color? =
                keys.firstNotNullOfOrNull { key -> theme.workbenchColors[key]?.let { runCatching { parseThemeColor(it) }.getOrNull() } }
            base.copy(
                // An imported theme brings its own background.
                background = if (theme.imported) importedBg ?: themeBackground(theme.json) ?: base.background else base.background,
                tokenColors = base.tokenColors + builtInColors + tokenColors + semantic,
                tokenFontStyles = base.tokenFontStyles + builtInStyles + tokenStyles,
                bracketPairColors = (if (theme.light) LIGHT_BRACKET_COLORS else BRACKET_COLORS).map(::Color),
                chromeColors = ChromeColors(
                    indentGuide = chromeColor("editorIndentGuide.background1", "editorIndentGuide.background")
                        ?: colors.outlineVariant.copy(alpha = 0.35f),
                    activeIndentGuide = chromeColor("editorIndentGuide.activeBackground1", "editorIndentGuide.activeBackground")
                        ?: colors.primary.copy(alpha = 0.65f),
                    bracketPairGuide = chromeColor("editorBracketPairGuide.background1") ?: Color.Unspecified,
                    ruler = chromeColor("editorRuler.foreground") ?: colors.outlineVariant.copy(alpha = 0.4f),
                    widgetBackground = chromeColor("editorWidget.background") ?: colors.surfaceContainerHigh,
                    widgetForeground = chromeColor("editorWidget.foreground") ?: colors.onSurface,
                    widgetBorder = chromeColor("editorWidget.border") ?: colors.outlineVariant,
                    inlayHint = chromeColor("editorInlayHint.foreground") ?: colors.onSurfaceVariant,
                    inlayHintBackground = chromeColor("editorInlayHint.background") ?: colors.surfaceContainerHighest.copy(alpha = 0.6f),
                    codeLens = chromeColor("editorCodeLens.foreground") ?: colors.onSurfaceVariant,
                    ghostText = chromeColor("editorGhostText.foreground") ?: colors.onSurfaceVariant.copy(alpha = 0.55f),
                ),
            )
        }
        editorThemeCache.put(cacheKey, built)
        return built
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
