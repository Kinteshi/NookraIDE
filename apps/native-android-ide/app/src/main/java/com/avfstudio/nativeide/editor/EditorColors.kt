package com.avfstudio.nativeide.editor

import android.graphics.Color as AndroidColor

/** Bracket pairs by depth: VS Code's Dark+ colours. */
internal val BRACKET_COLORS = intArrayOf(0xFF7FC8FF.toInt(), 0xFFFFC66D.toInt(), 0xFFC792EA.toInt())
/** VS Code's Light+ bracket-pair colours, used when the app theme is light. */
internal val LIGHT_BRACKET_COLORS = intArrayOf(0xFF0431FA.toInt(), 0xFF319331.toInt(), 0xFF7B3814.toInt())

/**
 * Each semantic token type's colour: VS Code's, Light+ ones on a light theme, then a theme's own by type (which colours
 * every type sharing its slot, as types share a colour in VS Code's themes).
 */
internal fun semanticColorsByKind(light: Boolean, overrides: Map<String, String>): Map<String, Int> {
    val slots = HashMap<Int, Int>()
    SEMANTIC_TOKEN_COLORS.values.forEach { (slot, color) -> slots[slot] = if (light) LIGHT_SEMANTIC_COLORS[slot] ?: color else color }
    overrides.forEach { (tokenType, value) ->
        val slot = SEMANTIC_TOKEN_COLORS[tokenType.substringBefore('.')]?.first ?: return@forEach
        val normalized = if (value.matches(Regex("#[0-9a-fA-F]{8}"))) "#${value.substring(7, 9)}${value.substring(1, 7)}" else value
        runCatching { AndroidColor.parseColor(normalized) }.getOrNull()?.let { slots[slot] = it }
    }
    return SEMANTIC_TOKEN_COLORS.mapValues { (_, slotAndColor) -> slots.getValue(slotAndColor.first) }
}

/** Token type → its colour slot and VS Code's Dark+ colour. */
private val SEMANTIC_TOKEN_COLORS = mapOf(
    "class" to (0 to 0xFF4EC9B0.toInt()),
    "enum" to (0 to 0xFF4EC9B0.toInt()),
    "interface" to (0 to 0xFF4EC9B0.toInt()),
    "namespace" to (0 to 0xFF4EC9B0.toInt()),
    "struct" to (0 to 0xFF4EC9B0.toInt()),
    "type" to (0 to 0xFF4EC9B0.toInt()),
    "function" to (1 to 0xFFDCDCAA.toInt()),
    "method" to (1 to 0xFFDCDCAA.toInt()),
    "parameter" to (2 to 0xFF9CDCFE.toInt()),
    "property" to (2 to 0xFF9CDCFE.toInt()),
    "variable" to (2 to 0xFF9CDCFE.toInt()),
    "enumMember" to (3 to 0xFF4FC1FF.toInt()),
    "keyword" to (4 to 0xFFC586C0.toInt()),
    "modifier" to (4 to 0xFFC586C0.toInt()),
    "string" to (5 to 0xFFCE9178.toInt()),
    "number" to (6 to 0xFFB5CEA8.toInt()),
    "comment" to (7 to 0xFF6A9955.toInt()),
    "operator" to (8 to 0xFFD4D4D4.toInt()),
)

/** Light+ colours by slot. */
private val LIGHT_SEMANTIC_COLORS = mapOf(
    0 to 0xFF267F99.toInt(),
    1 to 0xFF795E26.toInt(),
    2 to 0xFF001080.toInt(),
    3 to 0xFF0070C1.toInt(),
    4 to 0xFFAF00DB.toInt(),
    5 to 0xFFA31515.toInt(),
    6 to 0xFF098658.toInt(),
    7 to 0xFF008000.toInt(),
    8 to 0xFF000000.toInt(),
)
