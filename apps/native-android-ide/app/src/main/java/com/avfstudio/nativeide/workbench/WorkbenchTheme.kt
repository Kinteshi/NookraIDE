package com.avfstudio.nativeide.workbench

import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import com.avfstudio.nativeide.DARK_SYNTAX_THEME
import com.avfstudio.nativeide.LIGHT_SYNTAX_THEME
import com.avfstudio.nativeide.PythonTextMate
import com.avfstudio.nativeide.editor.SoraBracketColorizer
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import java.io.ByteArrayInputStream
import org.eclipse.tm4e.core.registry.IThemeSource

/** True when the workbench is drawn dark; editors and terminals match it. */
val LocalDarkTheme = staticCompositionLocalOf { true }

private val FallbackDark = darkColorScheme(primary = Color(0xFF9ECAFF), secondary = Color(0xFFBBC7DB))
private val FallbackLight = lightColorScheme(primary = Color(0xFF0061A4), secondary = Color(0xFF535F70))

/**
 * Material You theme: dynamic colour from the wallpaper on Android 12+, an optional pure-black
 * dark variant for OLED panels, and a static baseline palette otherwise.
 */
@Composable
fun AvfTheme(settings: WorkbenchSettings, content: @Composable () -> Unit) {
    val dark = when (settings.themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val context = LocalContext.current
    val base = when {
        settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> FallbackDark
        else -> FallbackLight
    }
    val scheme = if (dark && settings.pureBlack) base.pureBlack() else base
    CompositionLocalProvider(LocalDarkTheme provides dark) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

private fun ColorScheme.pureBlack(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0B0B0C),
    surfaceContainer = Color(0xFF111113),
    surfaceContainerHigh = Color(0xFF18181B),
    surfaceContainerHighest = Color(0xFF202024),
)

/**
 * Applies the syntax theme and Material chrome colours to a code editor. Imported VS Code themes
 * keep their own colours; otherwise code is coloured with Dark+/Light+ ([SyntaxColors.Classic])
 * or a theme generated from the Material You palette, blended into the app surfaces.
 */
fun applyEditorTheme(
    editor: CodeEditor,
    context: Context,
    colors: ColorScheme,
    dark: Boolean,
    syntax: SyntaxColors = SyntaxColors.Classic,
) {
    PythonTextMate.initialize(context)
    val registry = ThemeRegistry.getInstance()
    val imported = hasImportedTheme(context)
    if (imported) restoreImportedTheme(context)
    val material = !imported && syntax == SyntaxColors.MaterialYou
    val palette = if (material) SyntaxPalette.materialYou(colors, dark) else null
    val fileName = when {
        imported -> IMPORTED_THEME_FILE
        palette != null -> materialSyntaxTheme(palette, colors.surface)
        dark -> DARK_SYNTAX_THEME
        else -> LIGHT_SYNTAX_THEME
    }
    registry.findThemeByFileName(fileName)?.let(registry::setTheme)
    editor.colorScheme = TextMateColorScheme.create(registry)
    SoraBracketColorizer.configureColors(
        editor,
        palette?.semanticColors() ?: loadSavedSemanticColors(context),
        light = !dark && !imported,
    )
    if (imported) return
    val scheme = editor.colorScheme
    fun set(id: Int, color: Color) = scheme.setColor(id, color.toArgb())
    set(EditorColorScheme.WHOLE_BACKGROUND, colors.surface)
    set(EditorColorScheme.LINE_NUMBER_BACKGROUND, colors.surface)
    set(EditorColorScheme.LINE_NUMBER, colors.onSurfaceVariant.copy(alpha = 0.55f))
    set(EditorColorScheme.LINE_NUMBER_CURRENT, colors.onSurface)
    set(EditorColorScheme.LINE_DIVIDER, Color.Transparent)
    set(EditorColorScheme.CURRENT_LINE, colors.surfaceContainerLow)
    set(EditorColorScheme.SELECTION_INSERT, colors.primary)
    set(EditorColorScheme.SELECTION_HANDLE, colors.primary)
    set(EditorColorScheme.SELECTED_TEXT_BACKGROUND, colors.primary.copy(alpha = 0.28f))
    set(EditorColorScheme.MATCHED_TEXT_BACKGROUND, colors.tertiary.copy(alpha = 0.3f))
    set(EditorColorScheme.BLOCK_LINE, colors.outlineVariant.copy(alpha = 0.6f))
    set(EditorColorScheme.BLOCK_LINE_CURRENT, colors.outline)
    set(EditorColorScheme.SCROLL_BAR_THUMB, colors.outlineVariant)
    set(EditorColorScheme.SCROLL_BAR_THUMB_PRESSED, colors.outline)
    set(EditorColorScheme.HIGHLIGHTED_DELIMITERS_BACKGROUND, colors.primary.copy(alpha = 0.18f))
    set(EditorColorScheme.COMPLETION_WND_BACKGROUND, colors.surfaceContainerHigh)
    set(EditorColorScheme.COMPLETION_WND_CORNER, colors.outlineVariant)
    set(EditorColorScheme.COMPLETION_WND_TEXT_PRIMARY, colors.onSurface)
    set(EditorColorScheme.COMPLETION_WND_TEXT_SECONDARY, colors.onSurfaceVariant)
    set(EditorColorScheme.COMPLETION_WND_ITEM_CURRENT, colors.secondaryContainer)
}

/**
 * Registers the TextMate theme for [palette] once and returns its file name. The registry keeps
 * every theme it loads, so each palette (wallpaper, light or dark) gets its own name.
 */
private fun materialSyntaxTheme(palette: SyntaxPalette, background: Color): String {
    val fileName = "material-you-%08x.tmTheme.json".format(palette.hashCode() xor background.hashCode())
    val registry = ThemeRegistry.getInstance()
    if (registry.findThemeByFileName(fileName) == null) {
        val json = palette.textMateTheme("Material You", background)
        registry.loadTheme(IThemeSource.fromInputStream(ByteArrayInputStream(json.toByteArray()), fileName, Charsets.UTF_8))
    }
    return fileName
}

/** The colours notebook cells use for [syntax], matching the code editor. */
fun syntaxPalette(syntax: SyntaxColors, colors: ColorScheme, dark: Boolean): SyntaxPalette =
    if (syntax == SyntaxColors.MaterialYou) SyntaxPalette.materialYou(colors, dark) else SyntaxPalette.classic(dark)

/** The 16 ANSI colours for terminals, tuned for the current light or dark surface. */
fun terminalPalette(dark: Boolean): IntArray =
    if (dark) {
        longArrayOf(
            0xFF000000, 0xFFCD3131, 0xFF0DBC79, 0xFFE5E510, 0xFF2472C8, 0xFFBC3FBC, 0xFF11A8CD, 0xFFE5E5E5,
            0xFF666666, 0xFFF14C4C, 0xFF23D18B, 0xFFF5F543, 0xFF3B8EEA, 0xFFD670D6, 0xFF29B8DB, 0xFFFFFFFF,
        ).map { it.toInt() }.toIntArray()
    } else {
        longArrayOf(
            0xFF000000, 0xFFCD3131, 0xFF00BC00, 0xFF949800, 0xFF0451A5, 0xFFBC05BC, 0xFF0598BC, 0xFF555555,
            0xFF666666, 0xFFCD3131, 0xFF14CE14, 0xFFB5BA00, 0xFF0451A5, 0xFFBC05BC, 0xFF0598BC, 0xFFA5A5A5,
        ).map { it.toInt() }.toIntArray()
    }
