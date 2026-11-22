package io.github.kinteshi.nookraide.workbench

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import java.io.StringReader

/**
 * One rule of `keybindings.json`, in VS Code's format: `{"key": "ctrl+k ctrl+s", "command": "…"}`.
 * A command starting with "-" removes that command's default binding for [key] (all of its
 * defaults when [key] is empty). [whenClause] is kept in the file but not evaluated: a rule
 * applies where its command works (the editor, or everywhere for workbench commands).
 */
data class KeybindingRule(val key: String, val command: String, val whenClause: String? = null) {
    val removes: Boolean get() = command.startsWith("-")
    val commandId: String get() = command.removePrefix("-")
}

/** The user's rules and what in them was skipped (unknown commands, keys this app can't press). */
data class ParsedKeybindings(val rules: List<KeybindingRule>, val problems: List<String>)

/** Reads `keybindings.json` as VS Code writes it: comments and trailing commas are fine. */
fun parseKeybindings(text: String): ParsedKeybindings {
    if (text.isBlank()) return ParsedKeybindings(emptyList(), emptyList())
    val array = JsonReader(StringReader(text)).apply { isLenient = true }
        .let { JsonParser.parseReader(it) }
        .takeIf { it.isJsonArray }?.asJsonArray
        ?: throw IllegalArgumentException("keybindings.json must be an array of rules")
    val rules = mutableListOf<KeybindingRule>()
    val problems = mutableListOf<String>()
    array.forEach { element ->
        val rule = element?.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
        val command = rule.get("command")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
        val key = rule.get("key")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
        when {
            command.isEmpty() || command == "-" -> problems += "a rule without a command"
            EditorCommands.all.none { it.id == command.removePrefix("-") } -> problems += "unknown command ${command.removePrefix("-")}"
            key.isEmpty() && !command.startsWith("-") -> problems += "no key for $command"
            key.isNotEmpty() && normalizeKeybinding(key) == null -> problems += "can't press \"$key\" here"
            else -> rules += KeybindingRule(
                normalizeKeybinding(key).orEmpty(),
                command,
                rule.get("when")?.takeIf { it.isJsonPrimitive }?.asString,
            )
        }
    }
    return ParsedKeybindings(rules, problems)
}

/** Writes rules back the way VS Code formats `keybindings.json`. */
fun formatKeybindings(rules: List<KeybindingRule>): String {
    val array = JsonArray()
    rules.forEach { rule ->
        array.add(
            JsonObject().apply {
                addProperty("key", rule.key.lowercase())
                addProperty("command", rule.command)
                rule.whenClause?.let { addProperty("when", it) }
            },
        )
    }
    return com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(array)
}

private val modifierOrder = listOf("CTRL", "SHIFT", "ALT")

private val keyNames = mapOf(
    "UP" to "Up", "DOWN" to "Down", "LEFT" to "Left", "RIGHT" to "Right",
    "PAGEUP" to "PageUp", "PAGEDOWN" to "PageDown", "TAB" to "Tab", "ENTER" to "Enter",
    "SPACE" to "Space", "ESCAPE" to "Escape", "HOME" to "Home", "END" to "End",
    "DELETE" to "Delete", "BACKSPACE" to "Backspace", "INSERT" to "Insert",
)

private val singleKeys = setOf("`", "/", "\\", "[", "]", ",", ".", "=", "-", ";", "'")

/**
 * `ctrl+shift+k ctrl+s` → `Ctrl+Shift+K Ctrl+S`, the form [EditorCommands] uses; null for keys
 * no keyboard here sends (cmd, meta, win) or more than two chords.
 */
fun normalizeKeybinding(key: String): String? {
    val chords = key.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
    if (chords.isEmpty() || chords.size > 2) return null
    return chords.map { chord ->
        val parts = chord.split('+')
        val modifiers = parts.dropLast(1).map { it.uppercase() }
        if (modifiers.any { it !in modifierOrder }) return null
        val name = parts.last().uppercase()
        val keyName = when {
            name.length == 1 && (name[0].isLetterOrDigit() || name in singleKeys) -> name
            name.matches(Regex("F([1-9]|1[0-2])")) -> name
            name in keyNames -> keyNames.getValue(name)
            else -> return null
        }
        (modifierOrder.filter { it in modifiers }.map { it.lowercase().replaceFirstChar(Char::uppercase) } + keyName).joinToString("+")
    }.joinToString(" ")
}

/** How a recorded key press reads, in the same form. */
fun KeyStroke.label(): String =
    listOfNotNull("Ctrl".takeIf { ctrl }, "Shift".takeIf { shift }, "Alt".takeIf { alt }, keyNames[key] ?: key).joinToString("+")

/**
 * The default bindings with the user's rules applied: their own bindings first (they win over
 * a default on the same key), then the defaults they didn't remove. [scope] is the list a
 * dispatcher honours; a user rule only joins it when its command is in that list.
 */
fun applyKeybindings(scope: List<EditorCommand>, rules: List<KeybindingRule>): List<EditorCommand> {
    val ids = scope.mapTo(HashSet()) { it.id }
    val titles = EditorCommands.all.associate { it.id to it.title }
    val added = rules.filter { !it.removes && it.commandId in ids }
        .map { EditorCommand(it.commandId, titles[it.commandId] ?: it.commandId, it.key) }
    val removals = rules.filter { it.removes }
    val defaults = scope.filterNot { command ->
        removals.any { it.commandId == command.id && (it.key.isEmpty() || sameKeys(it.key, command.keybinding)) }
    }
    return added + defaults
}

private fun sameKeys(a: String, b: String): Boolean =
    a.split(' ').map(KeyStroke::parse) == b.split(' ').map(KeyStroke::parse)

/**
 * The rules after the user gives [commandId] the binding [key] in Keyboard Shortcuts (null
 * removes its binding), as VS Code writes them: the command's earlier rules go, and its defaults
 * are switched off with "-" rules.
 */
fun rebind(rules: List<KeybindingRule>, commandId: String, key: String?): List<KeybindingRule> {
    val kept = rules.filterNot { it.commandId == commandId }
    val defaults = EditorCommands.all.filter { it.id == commandId && it.keybinding.isNotEmpty() }
        .map { KeybindingRule(it.keybinding, "-$commandId") }
    return kept + defaults + listOfNotNull(key?.let { KeybindingRule(it, commandId) })
}

/** The rules without anything the user set for [commandId]: its defaults apply again. */
fun resetKeybinding(rules: List<KeybindingRule>, commandId: String): List<KeybindingRule> =
    rules.filterNot { it.commandId == commandId }
