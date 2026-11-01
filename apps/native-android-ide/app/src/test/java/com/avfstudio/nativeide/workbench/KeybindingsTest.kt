package com.avfstudio.nativeide.workbench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeybindingsTest {
    @Test
    fun normalizesVsCodeKeysToTheAppsForm() {
        assertEquals("Ctrl+Shift+K", normalizeKeybinding("shift+ctrl+k"))
        assertEquals("Ctrl+K Ctrl+S", normalizeKeybinding("ctrl+k  ctrl+s"))
        assertEquals("Shift+Alt+Down", normalizeKeybinding("alt+shift+down"))
        assertEquals("Ctrl+PageUp", normalizeKeybinding("ctrl+pageup"))
        assertEquals("F5", normalizeKeybinding("f5"))
        assertEquals("Ctrl+`", normalizeKeybinding("ctrl+`"))
        assertNull(normalizeKeybinding("cmd+s"))
        assertNull(normalizeKeybinding("ctrl+a ctrl+b ctrl+c"))
        assertNull(normalizeKeybinding("ctrl+numpad_add"))
    }

    @Test
    fun parsesVsCodeFilesWithCommentsAndReportsWhatCannotApply() {
        val parsed = parseKeybindings(
            """
            // Place your key bindings in this file
            [
              { "key": "ctrl+shift+d", "command": "editor.action.copyLinesDownAction", "when": "editorTextFocus" },
              { "key": "ctrl+d", "command": "-editor.action.addSelectionToNextFindMatch" },
              { "key": "cmd+k", "command": "workbench.action.quickOpen" },
              { "key": "ctrl+e", "command": "extension.unknown" },
            ]
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                KeybindingRule("Ctrl+Shift+D", "editor.action.copyLinesDownAction", "editorTextFocus"),
                KeybindingRule("Ctrl+D", "-editor.action.addSelectionToNextFindMatch"),
            ),
            parsed.rules,
        )
        assertEquals(listOf("can't press \"cmd+k\" here", "unknown command extension.unknown"), parsed.problems)
        assertEquals(ParsedKeybindings(emptyList(), emptyList()), parseKeybindings("  "))
    }

    @Test
    fun userRulesWinAndRemovalsDropDefaults() {
        val rules = listOf(
            KeybindingRule("Ctrl+S", "workbench.action.showCommands"),
            KeybindingRule("Ctrl+Shift+P", "-workbench.action.showCommands"),
            KeybindingRule("", "-workbench.action.files.save"),
        )
        val dispatcher = EditorKeybindingDispatcher(applyKeybindings(EditorCommands.all, rules))
        assertEquals("workbench.action.showCommands", dispatcher.dispatch(KeyStroke.parse("Ctrl+S")))
        assertNull(dispatcher.dispatch(KeyStroke.parse("Ctrl+Shift+P")))
        // Untouched defaults keep working.
        assertEquals("workbench.action.quickOpen", dispatcher.dispatch(KeyStroke.parse("Ctrl+P")))
    }

    @Test
    fun aRuleOnlyJoinsScopesWhereItsCommandWorks() {
        val rules = listOf(KeybindingRule("Ctrl+Alt+M", "editor.action.formatDocument"), KeybindingRule("Ctrl+Alt+N", "workbench.action.terminal.new"))
        val global = applyKeybindings(EditorCommands.global, rules)
        assertTrue(global.none { it.id == "editor.action.formatDocument" && it.keybinding == "Ctrl+Alt+M" })
        assertTrue(global.any { it.id == "workbench.action.terminal.new" && it.keybinding == "Ctrl+Alt+N" })
    }

    @Test
    fun rebindingReplacesTheDefaultsAsVsCodeWritesIt() {
        val rules = rebind(listOf(KeybindingRule("Ctrl+Alt+X", "workbench.action.files.save")), "workbench.action.files.save", "Ctrl+K Ctrl+W")
        assertEquals(
            listOf(KeybindingRule("Ctrl+S", "-workbench.action.files.save"), KeybindingRule("Ctrl+K Ctrl+W", "workbench.action.files.save")),
            rules,
        )
        assertEquals(listOf(KeybindingRule("Ctrl+S", "-workbench.action.files.save")), rebind(emptyList(), "workbench.action.files.save", null))
        assertEquals(emptyList<KeybindingRule>(), resetKeybinding(rules, "workbench.action.files.save"))
        val text = formatKeybindings(rules)
        assertEquals(rules, parseKeybindings(text).rules)
        assertTrue("\"key\": \"ctrl+k ctrl+w\"" in text)
    }

    @Test
    fun recordedStrokesReadLikeTheDefaults() {
        assertEquals("Ctrl+Shift+Enter", KeyStroke("ENTER", ctrl = true, shift = true).label())
        assertEquals("Alt+F12", KeyStroke("F12", alt = true).label())
        assertEquals("Ctrl+PageDown", KeyStroke("PAGEDOWN", ctrl = true).label())
    }
}
