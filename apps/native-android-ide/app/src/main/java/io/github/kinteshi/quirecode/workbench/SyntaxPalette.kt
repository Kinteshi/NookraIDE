package io.github.kinteshi.quirecode.workbench

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** `avf.theme.syntaxColors`: where code colours come from. An imported VS Code theme wins over both. */
enum class SyntaxColors(val key: String, val label: String) {
    /** VS Code's Dark+ and Light+. */
    Classic("classic", "Classic"),
    /** Generated from the app's Material You palette. */
    MaterialYou("materialYou", "Material You"),
    /** Aardink's WCAG-7:1 high-contrast palette. */
    HighContrast("highContrast", "High contrast"),
}

/** The colour of each kind of token, shared by the code editor and notebook cells. */
data class SyntaxPalette(
    val foreground: Color,
    val keyword: Color,
    val string: Color,
    val comment: Color,
    val number: Color,
    val function: Color,
    val type: Color,
    val variable: Color,
    val constant: Color,
    val operator: Color,
    val invalid: Color,
) {
    companion object {
        fun classic(dark: Boolean) = if (dark) {
            SyntaxPalette(
                foreground = Color(0xFFD4D4D4), keyword = Color(0xFFC586C0), string = Color(0xFFCE9178),
                comment = Color(0xFF6A9955), number = Color(0xFFB5CEA8), function = Color(0xFFDCDCAA),
                type = Color(0xFF4EC9B0), variable = Color(0xFF9CDCFE), constant = Color(0xFF4FC1FF),
                operator = Color(0xFFD4D4D4), invalid = Color(0xFFF44747),
            )
        } else {
            SyntaxPalette(
                foreground = Color(0xFF000000), keyword = Color(0xFFAF00DB), string = Color(0xFFA31515),
                comment = Color(0xFF008000), number = Color(0xFF098658), function = Color(0xFF795E26),
                type = Color(0xFF267F99), variable = Color(0xFF001080), constant = Color(0xFF0070C1),
                operator = Color(0xFF000000), invalid = Color(0xFFCD3131),
            )
        }

        /**
         * Colours from the Material scheme: keywords take the primary colour and strings the
         * tertiary; the other kinds are the primary hue turned around the colour wheel, at the
         * scheme's accent lightness, so every kind stays distinct whatever the wallpaper is.
         */
        fun materialYou(colors: ColorScheme, dark: Boolean): SyntaxPalette {
            val hue = hsl(colors.primary)[0]
            val lightness = if (dark) 0.78f else 0.36f
            val saturation = if (dark) 0.6f else 0.7f
            fun turned(degrees: Float) = Color.hsl((hue + degrees).mod(360f), saturation, lightness)
            return SyntaxPalette(
                foreground = colors.onSurface,
                keyword = colors.primary,
                string = colors.tertiary,
                comment = lerp(colors.onSurfaceVariant, colors.surface, 0.3f),
                number = turned(300f),
                function = turned(120f),
                type = turned(180f),
                variable = lerp(colors.onSurface, colors.primary, 0.25f),
                constant = turned(240f),
                operator = colors.onSurfaceVariant,
                invalid = colors.error,
            )
        }
    }
}

/** Hue (degrees), saturation and lightness of [color]. */
internal fun hsl(color: Color): FloatArray {
    val r = color.red
    val g = color.green
    val b = color.blue
    val high = max(r, max(g, b))
    val low = min(r, min(g, b))
    val lightness = (high + low) / 2f
    val delta = high - low
    if (delta == 0f) return floatArrayOf(0f, 0f, lightness)
    val saturation = delta / (1f - abs(2f * lightness - 1f))
    val hue = when (high) {
        r -> 60f * ((g - b) / delta).mod(6f)
        g -> 60f * ((b - r) / delta + 2f)
        else -> 60f * ((r - g) / delta + 4f)
    }
    return floatArrayOf(hue, saturation, lightness)
}

internal fun Color.hex(): String = "#%06X".format(toArgb() and 0xFFFFFF)

/** TextMate scopes for each kind, a short version of the scopes Dark+ colours. */
private fun SyntaxPalette.scopeRules(): List<Triple<String, Color, String?>> = listOf(
    Triple("comment, punctuation.definition.comment", comment, "italic"),
    Triple("keyword, keyword.control, storage, storage.type, storage.modifier, keyword.operator.logical.python, keyword.operator.wordlike, keyword.operator.new, keyword.operator.expression, entity.name.tag, markup.heading, markup.bold", keyword, null),
    Triple("variable.language, constant.language", keyword, "italic"),
    Triple("keyword.operator, punctuation, meta.brace", operator, null),
    Triple("string, markup.inline.raw, meta.preprocessor.string", string, null),
    Triple("constant.character.escape, string.regexp, constant.other.placeholder, variable.other.constant, variable.other.enummember, support.constant", constant, null),
    Triple("constant.numeric, keyword.other.unit", number, null),
    Triple("entity.name.function, support.function, meta.function-call.generic, entity.name.function.decorator, punctuation.definition.decorator", function, null),
    Triple("entity.name.type, entity.name.class, entity.name.namespace, support.class, support.type, entity.other.inherited-class, entity.other.attribute-name", type, null),
    Triple("variable, variable.parameter, meta.definition.variable.name, support.variable, entity.name.variable, meta.structure.dictionary.key.python, support.type.property-name, meta.object-literal.key", variable, null),
    Triple("markup.italic", foreground, "italic"),
    Triple("invalid", invalid, null),
)

/** A TextMate theme (VS Code's tmTheme JSON form) that colours code with this palette. */
internal fun SyntaxPalette.textMateTheme(name: String, background: Color): String {
    val settings = JsonArray()
    settings.add(JsonObject().apply {
        add("settings", JsonObject().apply {
            addProperty("foreground", foreground.hex())
            addProperty("background", background.hex())
        })
    })
    scopeRules().forEach { (scope, color, fontStyle) ->
        settings.add(JsonObject().apply {
            addProperty("scope", scope)
            add("settings", JsonObject().apply {
                addProperty("foreground", color.hex())
                fontStyle?.let { addProperty("fontStyle", it) }
            })
        })
    }
    return JsonObject().apply {
        addProperty("name", name)
        add("settings", settings)
    }.toString()
}

/** Semantic token colours (token type to `#RRGGBB`) for the language server's highlighting. */
internal fun SyntaxPalette.semanticColors(): Map<String, String> = mapOf(
    "class" to type, "enum" to type, "interface" to type, "namespace" to type, "struct" to type, "type" to type,
    "function" to function, "method" to function,
    "parameter" to variable, "property" to variable, "variable" to variable,
    "enumMember" to constant,
    "keyword" to keyword, "modifier" to keyword,
    "string" to string, "number" to number, "comment" to comment, "operator" to operator,
).mapValues { it.value.hex() }
