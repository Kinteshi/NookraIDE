package com.avfstudio.nativeide.workbench

import android.content.Context
import androidx.compose.ui.graphics.Color
import com.avfstudio.nativeide.assets.ImportedTheme
import com.google.gson.JsonObject
import com.google.gson.JsonParser

internal fun saveImportedTheme(context: Context, theme: ImportedTheme) {
    val workbenchColors = JsonObject().apply {
        theme.workbenchColors.forEach { (key, value) -> addProperty(key, value) }
    }
    val semanticColors = JsonObject().apply {
        theme.semanticTokenColors.forEach { (key, value) -> addProperty(key, value) }
    }
    context.getSharedPreferences("native-ide-assets", Context.MODE_PRIVATE).edit()
        .putString("theme-json", theme.textMateThemeJson)
        .putString("workbench-colors", workbenchColors.toString())
        .putString("semantic-colors", semanticColors.toString())
        .apply()
}

internal fun loadSavedSemanticColors(context: Context): Map<String, String> {
    val json = context.getSharedPreferences("native-ide-assets", Context.MODE_PRIVATE)
        .getString("semantic-colors", "{}") ?: "{}"
    return JsonParser.parseString(json).asJsonObject.entrySet().mapNotNull { (name, value) ->
        if (value.isJsonPrimitive && value.asJsonPrimitive.isString) name to value.asString else null
    }.toMap()
}

internal fun parseThemeColor(value: String): Color {
    val normalized = when {
        value.matches(Regex("#[0-9a-fA-F]{8}")) ->
            "#${value.substring(7, 9)}${value.substring(1, 7)}"
        value.matches(Regex("#[0-9a-fA-F]{4}")) ->
            "#${value[4]}${value[1]}${value[2]}${value[3]}"
        else -> value
    }
    val rgb = Regex("""rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)(?:\s*,\s*([\d.]+))?\s*\)""")
        .matchEntire(normalized)
    val color = if (rgb != null) {
        val red = rgb.groupValues[1].toInt().coerceIn(0, 255)
        val green = rgb.groupValues[2].toInt().coerceIn(0, 255)
        val blue = rgb.groupValues[3].toInt().coerceIn(0, 255)
        val alpha = rgb.groupValues[4].takeIf(String::isNotEmpty)?.toFloatOrNull()
            ?.let { (it.coerceIn(0f, 1f) * 255).toInt() } ?: 255
        android.graphics.Color.argb(alpha, red, green, blue)
    } else {
        android.graphics.Color.parseColor(normalized)
    }
    return Color(color)
}


internal const val IMPORTED_THEME_FILE = "imported-theme.tmTheme.json"

internal fun hasImportedTheme(context: Context): Boolean =
    context.getSharedPreferences("native-ide-assets", Context.MODE_PRIVATE).contains("theme-json")

internal fun clearSavedImportedTheme(context: Context) {
    context.getSharedPreferences("native-ide-assets", Context.MODE_PRIVATE).edit()
        .remove("theme-json")
        .remove("workbench-colors")
        .remove("semantic-colors")
        .apply()
}
