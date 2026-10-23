package com.avfstudio.nativeide.workbench

import android.view.KeyEvent
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkbenchSettingsTest {
    @Test
    fun settingsReadNestedAndDottedKeysAndWriteNested() {
        val json = JsonParser.parseString("""{"editor":{"fontSize":18},"editor.wordWrap":"on","avf.theme.mode":"dark"}""").asJsonObject
        val settings = WorkbenchSettings.from(json)
        assertEquals(18f, settings.editorFontSize)
        assertTrue(settings.wordWrap)
        assertEquals(ThemeMode.Dark, settings.themeMode)
        assertEquals("ty", settings.pythonLanguageServer)

        val updated = json.withPath("editor.wordWrap", "off").withPath("avf.python.languageServer", "basedpyright")
        assertFalse(updated.has("editor.wordWrap"))
        assertEquals("off", updated.getAsJsonObject("editor").get("wordWrap").asString)
        assertEquals(18, updated.getAsJsonObject("editor").get("fontSize").asInt)
        assertEquals("basedpyright", WorkbenchSettings.from(updated).pythonLanguageServer)
    }

    @Test
    fun workbenchShortcutsFollowVsCodeDefaults() {
        val stroke = editorKeyStroke(KeyEvent.KEYCODE_GRAVE, ctrl = true, shift = false, alt = false)
        assertEquals("workbench.action.terminal.toggleTerminal", EditorKeybindingDispatcher(EditorCommands.global).dispatch(stroke!!))
        val dispatcher = EditorKeybindingDispatcher(EditorCommands.all)
        assertEquals(null, dispatcher.dispatch(KeyStroke.parse("Ctrl+K")))
        assertEquals("workbench.action.openGlobalKeybindings", dispatcher.dispatch(KeyStroke.parse("Ctrl+S")))
        // Plain letters never reach the dispatcher outside a chord, so typing is unaffected.
        assertEquals(null, editorKeyStroke(KeyEvent.KEYCODE_Q, ctrl = false, shift = false, alt = false))
        assertTrue(EditorCommands.global.none { it.id == "editor.action.clipboardCopyAction" })
    }

    @Test
    fun fuzzyMatchingIsAnInOrderSubsequence() {
        assertTrue(fuzzyMatch("wbc", "WorkbenchController.kt"))
        assertTrue(fuzzyMatch("", "anything"))
        assertFalse(fuzzyMatch("cbw", "WorkbenchController.kt"))
    }
}
