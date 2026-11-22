package io.github.kinteshi.nookraide.workbench

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

/** The dev app's palette ([DevBuild]): Material 3 tones from the icon's orange cursor, so it can't pass for the release app. */
private val DevDark = darkColorScheme(
    primary = Color(0xFFFFB68E), onPrimary = Color(0xFF532200),
    primaryContainer = Color(0xFF763300), onPrimaryContainer = Color(0xFFFFDBCA),
    inversePrimary = Color(0xFF9A4500),
    secondary = Color(0xFFE5BFA9), onSecondary = Color(0xFF432B1C),
    secondaryContainer = Color(0xFF5C4130), onSecondaryContainer = Color(0xFFFFDBCA),
    tertiary = Color(0xFFCDC88E), onTertiary = Color(0xFF343210),
    tertiaryContainer = Color(0xFF4B4825), onTertiaryContainer = Color(0xFFEAE4A8),
    background = Color(0xFF1A120D), onBackground = Color(0xFFF0DFD7),
    surface = Color(0xFF1A120D), onSurface = Color(0xFFF0DFD7),
    surfaceVariant = Color(0xFF52443C), onSurfaceVariant = Color(0xFFD7C2B8),
    surfaceTint = Color(0xFFFFB68E),
    inverseSurface = Color(0xFFF0DFD7), inverseOnSurface = Color(0xFF382E29),
    outline = Color(0xFFA08D84), outlineVariant = Color(0xFF52443C),
    surfaceBright = Color(0xFF42372F), surfaceDim = Color(0xFF1A120D),
    surfaceContainerLowest = Color(0xFF140D08), surfaceContainerLow = Color(0xFF221A15),
    surfaceContainer = Color(0xFF271E19), surfaceContainerHigh = Color(0xFF322823),
    surfaceContainerHighest = Color(0xFF3D332D),
)
private val DevLight = lightColorScheme(
    primary = Color(0xFF9A4500), onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBCA), onPrimaryContainer = Color(0xFF331200),
    inversePrimary = Color(0xFFFFB68E),
    secondary = Color(0xFF765848), onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDBCA), onSecondaryContainer = Color(0xFF2B160A),
    tertiary = Color(0xFF645F38), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEAE4A8), onTertiaryContainer = Color(0xFF1E1C00),
    background = Color(0xFFFFF8F5), onBackground = Color(0xFF221A15),
    surface = Color(0xFFFFF8F5), onSurface = Color(0xFF221A15),
    surfaceVariant = Color(0xFFF4DED4), onSurfaceVariant = Color(0xFF52443C),
    surfaceTint = Color(0xFF9A4500),
    inverseSurface = Color(0xFF382E29), inverseOnSurface = Color(0xFFFFEDE5),
    outline = Color(0xFF85736B), outlineVariant = Color(0xFFD7C2B8),
    surfaceBright = Color(0xFFFFF8F5), surfaceDim = Color(0xFFE7D7CF),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFFF1EB),
    surfaceContainer = Color(0xFFFCEBE3), surfaceContainerHigh = Color(0xFFF6E5DD),
    surfaceContainerHighest = Color(0xFFF0DFD7),
)

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
        DevBuild.enabled -> if (dark) DevDark else DevLight
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
