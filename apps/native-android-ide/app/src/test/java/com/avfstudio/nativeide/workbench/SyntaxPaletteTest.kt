package com.avfstudio.nativeide.workbench

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SyntaxPaletteTest {
    private val dark = darkColorScheme(primary = Color(0xFF9ECAFF), tertiary = Color(0xFFD6BEE4))
    private val light = lightColorScheme(primary = Color(0xFF0061A4), tertiary = Color(0xFF6B5778))

    @Test
    fun hslOfPrimaries() {
        val red = hsl(Color.Red)
        assertEquals(0f, red[0], 0.01f)
        assertEquals(1f, red[1], 0.01f)
        assertEquals(0.5f, red[2], 0.01f)
        assertEquals(240f, hsl(Color.Blue)[0], 0.01f)
    }

    @Test
    fun materialYouKindsComeFromThePaletteAndStayDistinct() {
        val palette = SyntaxPalette.materialYou(dark, dark = true)
        assertEquals(dark.primary, palette.keyword)
        assertEquals(dark.tertiary, palette.string)
        val accents = listOf(palette.keyword, palette.number, palette.function, palette.type, palette.constant)
        accents.forEachIndexed { i, a ->
            accents.drop(i + 1).forEach { b ->
                val gap = abs(hsl(a)[0] - hsl(b)[0]).let { minOf(it, 360f - it) }
                assertTrue("hues too close: $a $b", gap >= 30f)
            }
        }
    }

    @Test
    fun accentsAreReadableOnTheSurface() {
        listOf(true to dark, false to light).forEach { (isDark, scheme) ->
            val palette = SyntaxPalette.materialYou(scheme, isDark)
            listOf(palette.number, palette.function, palette.type, palette.constant, palette.comment).forEach { color ->
                assertTrue("contrast of $color in ${if (isDark) "dark" else "light"}", contrast(color, scheme.surface) >= 3.0)
            }
        }
    }

    @Test
    fun textMateThemeAndSemanticColoursUseThePalette() {
        val palette = SyntaxPalette.materialYou(dark, dark = true)
        val theme = JsonParser.parseString(palette.textMateTheme("Material You", dark.surface)).asJsonObject
        val rules = theme.getAsJsonArray("settings").map { it.asJsonObject }
        assertEquals(palette.foreground.hex(), rules[0].getAsJsonObject("settings").get("foreground").asString)
        val keyword = rules.first { it.get("scope")?.asString?.startsWith("keyword,") == true }
        assertEquals(palette.keyword.hex(), keyword.getAsJsonObject("settings").get("foreground").asString)
        assertEquals(palette.function.hex(), palette.semanticColors()["method"])
    }

    private fun contrast(a: Color, b: Color): Double {
        fun luminance(c: Color): Double {
            fun channel(v: Float) = if (v <= 0.03928f) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
            return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
        }
        val (high, low) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (high + 0.05) / (low + 0.05)
    }
}
