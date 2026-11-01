package com.avfstudio.nativeide.workbench

data class EditorCommand(
    val id: String,
    val title: String,
    val keybinding: String,
)

object EditorCommands {
    val core: List<EditorCommand> = listOf(
        command("workbench.action.files.save", "Save", "Ctrl+S"),
        command("workbench.action.files.saveAs", "Save As", "Ctrl+Shift+S"),
        command("workbench.action.files.openFile", "Open File", "Ctrl+O"),
        command("workbench.action.files.openFolder", "File: Open Folder...", "Ctrl+K Ctrl+O"),
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
        command("editor.action.previousMatchFindAction", "Find Previous", "Shift+F3"),
        command("editor.action.replaceOne", "Replace", "Ctrl+Shift+1"),
        command("editor.action.replaceAll", "Replace All", "Ctrl+Alt+Enter"),
        command("toggleFindInSelection", "Find in Selection", "Alt+L"),
        command("workbench.action.gotoLine", "Go to Line", "Ctrl+G"),
        command("editor.action.formatDocument", "Format Document", "Shift+Alt+F"),
        command("editor.action.formatSelection", "Format Selection", "Ctrl+K Ctrl+F"),
        command("editor.action.smartSelect.expand", "Expand Selection", "Shift+Alt+Right"),
        command("editor.action.smartSelect.shrink", "Shrink Selection", "Shift+Alt+Left"),
        command("editor.action.joinLines", "Join Lines", ""),
        command("editor.action.transposeLetters", "Transpose Characters around the Cursor", ""),
        command("editor.action.sortLinesAscending", "Sort Lines Ascending", ""),
        command("editor.action.sortLinesDescending", "Sort Lines Descending", ""),
        command("editor.action.removeDuplicateLines", "Delete Duplicate Lines", ""),
        command("editor.action.trimTrailingWhitespace", "Trim Trailing Whitespace", "Ctrl+K Ctrl+X"),
        command("editor.action.transformToUppercase", "Transform to Uppercase", ""),
        command("editor.action.transformToLowercase", "Transform to Lowercase", ""),
        command("editor.action.transformToTitlecase", "Transform to Title Case", ""),
        command("editor.action.transformToSnakecase", "Transform to Snake Case", ""),
        command("editor.action.transformToKebabcase", "Transform to Kebab Case", ""),
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
        command("editor.action.toggleWordWrap", "Toggle Word Wrap", "Alt+Z"),
        command("editor.action.indentUsingSpaces", "Indent Using Spaces", ""),
        command("editor.action.indentUsingTabs", "Indent Using Tabs", ""),
        command("editor.action.detectIndentation", "Detect Indentation from Content", ""),
        command("editor.action.indentationToSpaces", "Convert Indentation to Spaces", ""),
        command("editor.action.indentationToTabs", "Convert Indentation to Tabs", ""),
        command("editor.action.jumpToBracket", "Go to Bracket", "Ctrl+Shift+\\"),
        command("workbench.action.closeOtherEditors", "Close Other Editors", "Ctrl+K W"),
        command("workbench.action.reopenClosedEditor", "Reopen Closed Editor", "Ctrl+Shift+T"),
        command("workbench.action.quickSwitchEditor", "Quick Switch Editor", "Ctrl+Tab"),
        command("workbench.action.keepEditor", "Keep Editor Open", "Ctrl+K Enter"),
        command("workbench.action.pinEditor", "Pin Editor", "Ctrl+K Shift+Enter"),
    )

    val language: List<EditorCommand> = listOf(
        command("editor.action.triggerSuggest", "Trigger Suggest", "Ctrl+Space"),
        command("editor.action.triggerParameterHints", "Trigger Parameter Hints", "Ctrl+Shift+Space"),
        command("editor.action.showHover", "Show Hover", "Ctrl+K Ctrl+I"),
        command("editor.action.quickFix", "Quick Fix...", "Ctrl+."),
        command("markdown.showPreview", "Markdown: Open Preview", "Ctrl+Shift+V"),
        command("markdown.showPreviewToSide", "Markdown: Open Preview to the Side", "Ctrl+K V"),
        command("workbench.action.openSettings", "Open Settings", "Ctrl+,"),
        command("merge-conflict.accept.current", "Merge Conflict: Accept Current", ""),
        command("merge-conflict.accept.incoming", "Merge Conflict: Accept Incoming", ""),
        command("merge-conflict.accept.both", "Merge Conflict: Accept Both", ""),
        command("merge-conflict.accept.all-current", "Merge Conflict: Accept All Current", ""),
        command("merge-conflict.accept.all-incoming", "Merge Conflict: Accept All Incoming", ""),
        command("merge-conflict.next", "Merge Conflict: Next Conflict", ""),
        command("merge-conflict.previous", "Merge Conflict: Previous Conflict", ""),
    )

    val copilot: List<EditorCommand> = listOf(
        command("editor.action.inlineSuggest.trigger", "Trigger Inline Suggestion", "Alt+\\"),
        command("editor.action.inlineSuggest.acceptNextEdit", "Copilot: Accept Next Edit Suggestion", ""),
        command("editor.action.inlineSuggest.triggerNextEdit", "Copilot: Suggest the Next Edit", ""),
        command("workbench.action.chat.open", "Open Copilot Chat", "Ctrl+Alt+I"),
        command("github.copilot.chat.attachSelection", "Copilot: Add Selection to Chat", "Ctrl+Alt+L"),
        command("github.copilot.chat.attachFile", "Copilot: Add File to Chat", ""),
        command("github.copilot.signIn", "GitHub Copilot: Sign In", ""),
        command("github.copilot.signOut", "GitHub Copilot: Sign Out", ""),
        command("github.copilot.toggleInlineSuggestions", "GitHub Copilot: Toggle Inline Suggestions", ""),
    )

    /** Workbench-level commands (VS Code defaults) that also work while the terminal has focus. */
    val workbench: List<EditorCommand> = listOf(
        command("workbench.action.terminal.toggleTerminal", "Toggle Terminal", "Ctrl+`"),
        command("workbench.action.terminal.new", "New Terminal", "Ctrl+Shift+`"),
        command("workbench.action.terminal.openDetectedLink", "Terminal: Open Detected Link...", ""),
        command("workbench.action.toggleSidebarVisibility", "Toggle Primary Side Bar", "Ctrl+B"),
        command("workbench.action.togglePanel", "Toggle Panel", "Ctrl+J"),
        command("workbench.view.explorer", "Show Explorer", "Ctrl+Shift+E"),
        command("workbench.view.search", "Search: Find in Files", "Ctrl+Shift+F"),
        command("workbench.view.scm", "Show Source Control", "Ctrl+Shift+G"),
        command("git.refresh", "Git: Refresh", ""),
        command("git.commit", "Git: Commit", ""),
        command("git.checkout", "Git: Checkout to…", ""),
        command("git.pull", "Git: Pull", ""),
        command("git.push", "Git: Push", ""),
        command("git.sync", "Git: Sync", ""),
        command("git.fetch", "Git: Fetch", ""),
        command("git.clone", "Git: Clone…", ""),
        command("git.init", "Git: Initialize Repository", ""),
        command("workbench.actions.view.problems", "Show Problems", "Ctrl+Shift+M"),
        command("workbench.action.gotoSymbol", "Go to Symbol in Editor", "Ctrl+Shift+O"),
        command("workbench.action.showAllSymbols", "Go to Symbol in Workspace", "Ctrl+T"),
        command("outline.focus", "Show Outline", ""),
        command("workbench.action.unpinEditor", "Unpin Editor", ""),
        command("ports.focus", "Show Ports", ""),
        command("workbench.action.output.toggleOutput", "Toggle Output", "Ctrl+Shift+U"),
        command("avf.guest.showResources", "Guest: Show Memory and CPU", ""),
        command("workbench.action.nextEditor", "Open Next Editor", "Ctrl+PageDown"),
        command("workbench.action.previousEditor", "Open Previous Editor", "Ctrl+PageUp"),
        command("workbench.action.openGlobalKeybindings", "Keyboard Shortcuts", "Ctrl+K Ctrl+S"),
        command("workbench.action.openGlobalKeybindingsFile", "Open Keyboard Shortcuts (JSON)", ""),
        command("workbench.action.debug.run", "Run Active File in Terminal", "Ctrl+F5"),
        command("workbench.action.debug.start", "Debug: Start or Continue", "F5"),
        command("workbench.action.debug.stop", "Debug: Stop", "Shift+F5"),
        command("workbench.action.debug.restart", "Debug: Restart", "Ctrl+Shift+F5"),
        command("workbench.action.debug.pause", "Debug: Pause", "F6"),
        command("workbench.action.debug.stepOver", "Debug: Step Over", "F10"),
        command("workbench.action.debug.stepInto", "Debug: Step Into", "F11"),
        command("workbench.action.debug.stepOut", "Debug: Step Out", "Shift+F11"),
        command("editor.debug.action.toggleBreakpoint", "Debug: Toggle Breakpoint", "F9"),
        command("python.setInterpreter", "Python: Select Interpreter", ""),
        command("editor.action.fontZoomIn", "Editor Font Zoom In", "Ctrl+="),
        command("editor.action.fontZoomOut", "Editor Font Zoom Out", "Ctrl+-"),
        command("workbench.action.openSettingsJson", "Open User Settings (JSON)", ""),
        command("workbench.action.connect", "Connect to Guest", ""),
        command("workbench.action.openRecent", "File: Open Recent...", ""),
        command("explorer.newFile", "File: New File...", ""),
        command("explorer.newFolder", "File: New Folder...", ""),
        command("renameFile", "File: Rename...", ""),
        command("deleteFile", "File: Delete", ""),
        command("jupyter.selectKernel", "Notebook: Select Kernel", ""),
        command("jupyter.runAll", "Notebook: Run All Cells", ""),
        command("jupyter.interruptKernel", "Notebook: Interrupt Kernel", ""),
        command("jupyter.restartKernel", "Notebook: Restart Kernel", ""),
        command("jupyter.showVariables", "Jupyter: Show Variables", ""),
        command("github.signIn", "GitHub: Sign In (Copilot Chat and Git)", ""),
        command("github.copilot.chat.signIn", "GitHub Copilot: Sign In to Chat", ""),
        command("github.copilot.chat.signInCli", "Copilot: Sign In to Chat with copilot login", ""),
        command("avf.chat.selectCopilot", "Chat: Use Copilot", ""),
        command("avf.chat.selectClaudeCode", "Chat: Use Claude Code", ""),
        command("avf.chat.selectGemini", "Chat: Use Gemini", ""),
        command("avf.chat.signIn", "Chat: Sign In to the Chat Agent", ""),
        command("workbench.action.files.openLocalFile", "File: Open File from Device...", ""),
        command("jupyter.runallcells", "Jupyter: Run All Cells", ""),
        command("jupyter.runallcellsabove", "Jupyter: Run Cells Above Current Cell", ""),
        command("jupyter.runcurrentcellandallbelow", "Jupyter: Run Current Cell and Below", ""),
        command("jupyter.execSelectionInteractive", "Jupyter: Run Selection/Line in Interactive Window", ""),
        command("jupyter.createnewinteractive", "Jupyter: Create Interactive Window", ""),
        command("jupyter.showPlotViewer", "Jupyter: Show Plot Viewer", ""),
    )

    /** Keys a Python file with `# %%` cells takes before the editor (the Jupyter extension's). */
    val interactive: List<EditorCommand> = listOf(
        command("jupyter.runcurrentcell", "Jupyter: Run Current Cell", "Ctrl+Enter"),
        command("jupyter.runcurrentcelladvance", "Jupyter: Run Current Cell and Advance", "Shift+Enter"),
    )

    /** Keys a terminal takes before the workbench (VS Code's terminal find); Ctrl+F reaches the shell elsewhere. */
    val terminal: List<EditorCommand> = listOf(
        command("workbench.action.terminal.focusFind", "Terminal: Find", "Ctrl+F"),
    )

    /** Jupyter-style cell keys; honoured while a notebook is the active editor. */
    val notebook: List<EditorCommand> = listOf(
        command("notebook.cell.executeAndSelectBelow", "Notebook: Run Cell and Select Below", "Shift+Enter"),
        command("notebook.cell.execute", "Notebook: Run Cell", "Ctrl+Enter"),
        command("notebook.cell.executeAndInsertBelow", "Notebook: Run Cell and Insert Below", "Alt+Enter"),
        command("notebook.cell.insertCodeCellBelow", "Notebook: Insert Code Cell Below", "Ctrl+Shift+Enter"),
        command("notebook.focusPreviousEditor", "Notebook: Focus Previous Cell", "Ctrl+Up"),
        command("notebook.focusNextEditor", "Notebook: Focus Next Cell", "Ctrl+Down"),
        command("notebook.cell.moveUp", "Notebook: Move Cell Up", "Alt+Up"),
        command("notebook.cell.moveDown", "Notebook: Move Cell Down", "Alt+Down"),
        command("notebook.cell.delete", "Notebook: Delete Cell", ""),
        command("notebook.undoDeleteCell", "Notebook: Undo Delete Cell", ""),
        command("notebook.outline", "Notebook: Show Outline", ""),
    )

    /** The editor's find keys, which a notebook takes too (its widget searches every cell). */
    val notebookFind: List<EditorCommand>
        get() = core.filter { it.id in setOf("actions.find", "editor.action.startFindReplaceAction", "editor.action.nextMatchFindAction", "editor.action.previousMatchFindAction", "toggleFindInSelection", "editor.action.replaceAll", "editor.action.replaceOne") }

    val all: List<EditorCommand> get() = core + language + copilot + workbench + notebook + terminal + interactive

    /** What the code editor's keys reach: not the notebook's cell keys, and the cell keys only in a file with cells. */
    val editor: List<EditorCommand> get() = core + language + copilot + workbench

    private val globalIds = setOf(
        "workbench.action.files.save",
        "workbench.action.files.saveAs",
        "workbench.action.files.openFile",
        "workbench.action.files.openFolder",
        "workbench.action.quickOpen",
        "workbench.action.showCommands",
        "workbench.action.closeActiveEditor",
        "workbench.action.files.newUntitledFile",
        "workbench.action.closeOtherEditors",
        "workbench.action.keepEditor",
        "workbench.action.pinEditor",
        "workbench.action.reopenClosedEditor",
        "workbench.action.quickSwitchEditor",
        "workbench.action.openSettings",
        "workbench.action.chat.open",
        "github.copilot.chat.attachSelection",
    )

    /** Bindings honoured when focus is outside the code editor (terminal, chat, notebooks, dialogs). */
    val global: List<EditorCommand> get() = all.filter { it.id in globalIds } + workbench

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

class EditorKeybindingDispatcher(commands: List<EditorCommand> = EditorCommands.all) {
    private val bindings = commands.filter { it.keybinding.isNotEmpty() }.map { command ->
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

/** Maps an Android hardware key to the key names used in VS Code keybindings. */
internal fun editorKeyStroke(
    keyCode: Int,
    ctrl: Boolean,
    shift: Boolean,
    alt: Boolean,
    allowUnmodified: Boolean = false,
): KeyStroke? {
    val key = when (keyCode) {
        in android.view.KeyEvent.KEYCODE_A..android.view.KeyEvent.KEYCODE_Z ->
            ('A' + (keyCode - android.view.KeyEvent.KEYCODE_A)).toString()
        in android.view.KeyEvent.KEYCODE_0..android.view.KeyEvent.KEYCODE_9 ->
            ('0' + (keyCode - android.view.KeyEvent.KEYCODE_0)).toString()
        in android.view.KeyEvent.KEYCODE_F1..android.view.KeyEvent.KEYCODE_F12 ->
            "F${keyCode - android.view.KeyEvent.KEYCODE_F1 + 1}"
        else -> namedKeys[keyCode] ?: return null
    }
    if (!ctrl && !alt && !shift && !allowUnmodified && key != "TAB" && !key.matches(Regex("F\\d+"))) return null
    return KeyStroke(key, ctrl, shift, alt)
}

private val namedKeys = mapOf(
    android.view.KeyEvent.KEYCODE_DPAD_UP to "UP",
    android.view.KeyEvent.KEYCODE_DPAD_DOWN to "DOWN",
    android.view.KeyEvent.KEYCODE_DPAD_LEFT to "LEFT",
    android.view.KeyEvent.KEYCODE_DPAD_RIGHT to "RIGHT",
    android.view.KeyEvent.KEYCODE_PAGE_UP to "PAGEUP",
    android.view.KeyEvent.KEYCODE_PAGE_DOWN to "PAGEDOWN",
    android.view.KeyEvent.KEYCODE_TAB to "TAB",
    android.view.KeyEvent.KEYCODE_ENTER to "ENTER",
    android.view.KeyEvent.KEYCODE_SPACE to "SPACE",
    android.view.KeyEvent.KEYCODE_ESCAPE to "ESCAPE",
    android.view.KeyEvent.KEYCODE_MOVE_HOME to "HOME",
    android.view.KeyEvent.KEYCODE_MOVE_END to "END",
    android.view.KeyEvent.KEYCODE_FORWARD_DEL to "DELETE",
    android.view.KeyEvent.KEYCODE_DEL to "BACKSPACE",
    android.view.KeyEvent.KEYCODE_INSERT to "INSERT",
    android.view.KeyEvent.KEYCODE_GRAVE to "`",
    android.view.KeyEvent.KEYCODE_SLASH to "/",
    // ABNT2 (Brazilian) keyboards print "/" on the extra key beside right Shift; accept it too.
    android.view.KeyEvent.KEYCODE_RO to "/",
    android.view.KeyEvent.KEYCODE_BACKSLASH to "\\",
    android.view.KeyEvent.KEYCODE_LEFT_BRACKET to "[",
    android.view.KeyEvent.KEYCODE_RIGHT_BRACKET to "]",
    android.view.KeyEvent.KEYCODE_COMMA to ",",
    android.view.KeyEvent.KEYCODE_PERIOD to ".",
    android.view.KeyEvent.KEYCODE_EQUALS to "=",
    android.view.KeyEvent.KEYCODE_MINUS to "-",
    android.view.KeyEvent.KEYCODE_SEMICOLON to ";",
    android.view.KeyEvent.KEYCODE_APOSTROPHE to "'",
)
