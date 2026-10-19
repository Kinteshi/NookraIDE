package com.avfstudio.nativeide.workbench

data class EditorCommand(
    val id: String,
    val title: String,
    val keybinding: String,
)

object EditorCommands {
    val top50: List<EditorCommand> = listOf(
        command("workbench.action.files.save", "Save", "Ctrl+S"),
        command("workbench.action.files.saveAs", "Save As", "Ctrl+Shift+S"),
        command("workbench.action.files.openFile", "Open File", "Ctrl+O"),
        command("workbench.action.quickOpen", "Quick Open", "Ctrl+P"),
        command("workbench.action.showCommands", "Show All Commands", "Ctrl+Shift+P"),
        command("workbench.action.closeActiveEditor", "Close Editor", "Ctrl+W"),
        command("workbench.action.files.newUntitledFile", "New Untitled File", "Ctrl+N"),
        command("undo", "Undo", "Ctrl+Z"),
        command("redo", "Redo", "Ctrl+Shift+Z"),
        command("editor.action.clipboardCutAction", "Cut", "Ctrl+X"),
        command("editor.action.clipboardCopyAction", "Copy", "Ctrl+C"),
        command("editor.action.clipboardPasteAction", "Paste", "Ctrl+V"),
        command("editor.action.selectAll", "Select All", "Ctrl+A"),
        command("actions.find", "Find", "Ctrl+F"),
        command("editor.action.startFindReplaceAction", "Replace", "Ctrl+H"),
        command("editor.action.nextMatchFindAction", "Find Next", "F3"),
        command("workbench.action.gotoLine", "Go to Line", "Ctrl+G"),
        command("editor.action.formatDocument", "Format Document", "Shift+Alt+F"),
        command("editor.action.commentLine", "Toggle Line Comment", "Ctrl+/"),
        command("editor.action.blockComment", "Toggle Block Comment", "Shift+Alt+A"),
        command("editor.action.indentLines", "Indent Line", "Tab"),
        command("editor.action.outdentLines", "Outdent Line", "Shift+Tab"),
        command("editor.action.moveLinesUpAction", "Move Line Up", "Alt+Up"),
        command("editor.action.moveLinesDownAction", "Move Line Down", "Alt+Down"),
        command("editor.action.copyLinesUpAction", "Copy Line Up", "Shift+Alt+Up"),
        command("editor.action.copyLinesDownAction", "Copy Line Down", "Shift+Alt+Down"),
        command("editor.action.deleteLines", "Delete Line", "Ctrl+Shift+K"),
        command("editor.action.insertLineAfter", "Insert Line Below", "Ctrl+Enter"),
        command("editor.action.insertLineBefore", "Insert Line Above", "Ctrl+Shift+Enter"),
        command("cursorUndo", "Undo Cursor Move", "Ctrl+U"),
        command("editor.action.insertCursorAbove", "Add Cursor Above", "Ctrl+Alt+Up"),
        command("editor.action.insertCursorBelow", "Add Cursor Below", "Ctrl+Alt+Down"),
        command("editor.action.addSelectionToNextFindMatch", "Add Selection to Next Match", "Ctrl+D"),
        command("editor.action.selectHighlights", "Select All Occurrences", "Ctrl+Shift+L"),
        command("editor.action.goToDefinition", "Go to Definition", "F12"),
        command("editor.action.peekDefinition", "Peek Definition", "Alt+F12"),
        command("editor.action.goToReferences", "Find All References", "Shift+F12"),
        command("editor.action.rename", "Rename Symbol", "F2"),
        command("editor.action.marker.next", "Next Problem", "F8"),
        command("editor.action.marker.prev", "Previous Problem", "Shift+F8"),
        command("editor.debug.action.toggleBreakpoint", "Toggle Breakpoint", "F9"),
        command("editor.action.toggleWordWrap", "Toggle Word Wrap", "Alt+Z"),
        command("editor.action.jumpToBracket", "Go to Bracket", "Ctrl+Shift+\\"),
        command("editor.fold", "Fold", "Ctrl+Shift+["),
        command("editor.unfold", "Unfold", "Ctrl+Shift+]"),
        command("editor.foldAll", "Fold All", "Ctrl+K Ctrl+0"),
        command("editor.unfoldAll", "Unfold All", "Ctrl+K Ctrl+J"),
        command("workbench.action.closeOtherEditors", "Close Other Editors", "Ctrl+K W"),
        command("workbench.action.reopenClosedEditor", "Reopen Closed Editor", "Ctrl+Shift+T"),
        command("workbench.action.quickSwitchEditor", "Quick Switch Editor", "Ctrl+Tab"),
    )

    val language: List<EditorCommand> = listOf(
        command("editor.action.triggerSuggest", "Trigger Suggest", "Ctrl+Space"),
        command("editor.action.showHover", "Show Hover", "Ctrl+K Ctrl+I"),
        command("editor.action.quickFix", "Quick Fix...", "Ctrl+."),
        command("workbench.action.openSettings", "Open Settings", "Ctrl+,"),
    )

    private fun command(id: String, title: String, keybinding: String) =
        EditorCommand(id, title, keybinding)
}

data class KeyStroke(
    val key: String,
    val ctrl: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false,
) {
    companion object {
        fun parse(value: String): KeyStroke {
            val parts = value.split('+')
            val key = parts.last().uppercase()
            val modifiers = parts.dropLast(1).map(String::uppercase).toSet()
            return KeyStroke(
                key = key,
                ctrl = "CTRL" in modifiers,
                shift = "SHIFT" in modifiers,
                alt = "ALT" in modifiers,
            )
        }
    }
}

class EditorKeybindingDispatcher(commands: List<EditorCommand> = EditorCommands.top50 + EditorCommands.language) {
    private val bindings = commands.map { command ->
        command.keybinding.split(' ').map(KeyStroke::parse) to command.id
    }
    private var pendingChord: KeyStroke? = null
    val isAwaitingChord: Boolean get() = pendingChord != null

    fun dispatch(stroke: KeyStroke): String? {
        val pending = pendingChord
        if (pending != null) {
            pendingChord = null
            return bindings.firstOrNull { (keys, _) -> keys == listOf(pending, stroke) }?.second
        }
        val oneStroke = bindings.firstOrNull { (keys, _) -> keys == listOf(stroke) }?.second
        if (oneStroke != null) return oneStroke
        if (bindings.any { (keys, _) -> keys.size == 2 && keys.first() == stroke }) {
            pendingChord = stroke
        }
        return null
    }
}
