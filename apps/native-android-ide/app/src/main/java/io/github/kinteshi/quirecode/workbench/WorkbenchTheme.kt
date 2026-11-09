package io.github.kinteshi.quirecode.workbench

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
import androidx.compose.ui.platform.LocalContext

/** True when the workbench is drawn dark; editors and terminals match it. */
val LocalDarkTheme = staticCompositionLocalOf { true }

private val FallbackDark = darkColorScheme(primary = Color(0xFF9ECAFF), secondary = Color(0xFFBBC7DB))
private val FallbackLight = lightColorScheme(primary = Color(0xFF0061A4), secondary = Color(0xFF535F70))

/**
 * Material You theme: dynamic colour from the wallpaper, an optional pure-black
 * dark variant for OLED panels, and a static baseline palette otherwise.
 */
@Composable
fun AvfTheme(settings: WorkbenchSettings, tooltips: Tooltips = Tooltips(settings.tooltips) { "" }, content: @Composable () -> Unit) {
    val dark = when (settings.themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val context = LocalContext.current
    val base = when {
        settings.dynamicColor ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> FallbackDark
        else -> FallbackLight
    }
    val scheme = if (dark && settings.pureBlack) base.pureBlack() else base
    CompositionLocalProvider(LocalDarkTheme provides dark, LocalTooltips provides tooltips) {
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

/** A problem's colour by LSP severity (1 error, 2 warning, else information), as in Problems. */
fun ColorScheme.problemColor(severity: Int?): Color = when (severity) {
    1, null -> error
    2 -> tertiary
    else -> primary
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
