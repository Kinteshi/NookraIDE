package io.github.kinteshi.quirecode.workbench

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorCommandsTest {
    @Test
    fun exposesDistinctVsCodeEditorCommands() {
        assertEquals(EditorCommands.all.size, EditorCommands.all.map(EditorCommand::id).distinct().size)
        // Each dispatcher context (code editor; notebooks and other panes) needs unambiguous keys.
        val editorContext = EditorCommands.core + EditorCommands.language + EditorCommands.copilot + EditorCommands.workbench
        val notebookContext = EditorCommands.global + EditorCommands.notebook
        listOf(editorContext, notebookContext).forEach { context ->
            val keys = context.distinctBy(EditorCommand::id).map(EditorCommand::keybinding).filter(String::isNotEmpty)
            assertEquals(keys.size, keys.distinct().size)
        }
        assertTrue(EditorCommands.core.any { it.id == "editor.action.goToDefinition" && it.keybinding == "F12" })
    }

    @Test
    fun dispatchesSingleStrokeAndMultiStrokeBindings() {
        val dispatcher = EditorKeybindingDispatcher()

        assertEquals("workbench.action.files.save", dispatcher.dispatch(KeyStroke.parse("Ctrl+S")))
        assertNull(dispatcher.dispatch(KeyStroke.parse("Ctrl+K")))
        assertTrue(dispatcher.isAwaitingChord)
        assertEquals("editor.action.showHover", dispatcher.dispatch(KeyStroke.parse("Ctrl+I")))
        assertFalse(dispatcher.isAwaitingChord)
        assertNull(dispatcher.dispatch(KeyStroke.parse("Ctrl+K")))
        assertEquals("workbench.action.closeOtherEditors", dispatcher.dispatch(KeyStroke.parse("W")))
    }

    @Test
    fun everyCoreBindingResolvesToItsCommand() {
        // Palette-only commands (Join Lines, Sort Lines…) have no keys.
        EditorCommands.core.filter { it.keybinding.isNotEmpty() }.forEach { command ->
            val dispatcher = EditorKeybindingDispatcher()
            val result = command.keybinding.split(' ')
                .map(KeyStroke::parse)
                .map(dispatcher::dispatch)
                .last()
            assertEquals(command.id, result)
        }
    }

    @Test
    fun everyCoreKeystrokeMapsFromAndroidHardwareKeyCodes() {
        val keyCodes = mapOf(
            "UP" to KeyEvent.KEYCODE_DPAD_UP,
            "DOWN" to KeyEvent.KEYCODE_DPAD_DOWN,
            "LEFT" to KeyEvent.KEYCODE_DPAD_LEFT,
            "RIGHT" to KeyEvent.KEYCODE_DPAD_RIGHT,
            "TAB" to KeyEvent.KEYCODE_TAB,
            "ENTER" to KeyEvent.KEYCODE_ENTER,
            "/" to KeyEvent.KEYCODE_SLASH,
            "\\" to KeyEvent.KEYCODE_BACKSLASH,
            "[" to KeyEvent.KEYCODE_LEFT_BRACKET,
            "]" to KeyEvent.KEYCODE_RIGHT_BRACKET,
            "0" to KeyEvent.KEYCODE_0,
            "W" to KeyEvent.KEYCODE_W,
            "J" to KeyEvent.KEYCODE_J,
            "K" to KeyEvent.KEYCODE_K,
            "S" to KeyEvent.KEYCODE_S,
            "O" to KeyEvent.KEYCODE_O,
            "P" to KeyEvent.KEYCODE_P,
            "N" to KeyEvent.KEYCODE_N,
            "Z" to KeyEvent.KEYCODE_Z,
            "X" to KeyEvent.KEYCODE_X,
            "C" to KeyEvent.KEYCODE_C,
            "V" to KeyEvent.KEYCODE_V,
            "A" to KeyEvent.KEYCODE_A,
            "F" to KeyEvent.KEYCODE_F,
            "H" to KeyEvent.KEYCODE_H,
            "G" to KeyEvent.KEYCODE_G,
            "D" to KeyEvent.KEYCODE_D,
            "L" to KeyEvent.KEYCODE_L,
            "T" to KeyEvent.KEYCODE_T,
            "U" to KeyEvent.KEYCODE_U,
            "1" to KeyEvent.KEYCODE_1,
            "F2" to KeyEvent.KEYCODE_F2,
            "F3" to KeyEvent.KEYCODE_F3,
            "F8" to KeyEvent.KEYCODE_F8,
            "F9" to KeyEvent.KEYCODE_F9,
            "F12" to KeyEvent.KEYCODE_F12,
        )

        EditorCommands.core.filter { it.keybinding.isNotEmpty() }.forEach { command ->
            command.keybinding.split(' ').forEachIndexed { index, binding ->
                val expected = KeyStroke.parse(binding)
                val keyCode = requireNotNull(keyCodes[expected.key]) { "No Android key code for ${expected.key}" }
                assertEquals(
                    expected,
                    editorKeyStroke(
                        keyCode,
                        expected.ctrl,
                        expected.shift,
                        expected.alt,
                        allowUnmodified = index > 0,
                    ),
                )
            }
        }
    }
}
