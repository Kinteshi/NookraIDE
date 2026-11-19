package io.github.kinteshi.quirecode.workbench

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
    fun newFeatureSettingsDefaultOnAndFollowVsCodeValues() {
        val defaults = WorkbenchSettings.from(JsonParser.parseString("{}").asJsonObject)
        assertEquals(listOf(true, true, true, true), listOf(defaults.autoIndent, defaults.notebookCopilot, defaults.renderPlotly, defaults.statusBarGuestResources))
        val off = WorkbenchSettings.from(
            JsonParser.parseString(
                """{"editor.autoIndent":"none","avf.copilot.notebooks":false,"avf":{"notebook":{"plotly":false}},"avf.statusBar.guestResources":false}""",
            ).asJsonObject,
        )
        assertEquals(listOf(false, false, false, false), listOf(off.autoIndent, off.notebookCopilot, off.renderPlotly, off.statusBarGuestResources))
        assertEquals(true, WorkbenchSettings.from(JsonParser.parseString("""{"editor.autoIndent":"advanced"}""").asJsonObject).autoIndent)
        assertEquals(listOf(true, false), listOf(defaults.useTabStops, WorkbenchSettings.from(JsonParser.parseString("""{"editor.useTabStops":false}""").asJsonObject).useTabStops))
        assertEquals(SyntaxColors.Classic, defaults.syntaxColors)
        assertEquals("", defaults.chatReasoningEffort)
        assertEquals("high", WorkbenchSettings.from(JsonParser.parseString("""{"avf.copilot.chatReasoningEffort":"high"}""").asJsonObject).chatReasoningEffort)
        assertEquals("", WorkbenchSettings.from(JsonParser.parseString("""{"avf.copilot.chatReasoningEffort":"turbo"}""").asJsonObject).chatReasoningEffort)
        assertEquals(listOf("Default", "Extra high", "Low"), listOf("", "xhigh", "low").map(::reasoningEffortLabel))
        assertEquals("Agent", chatValueLabel("https://agentclientprotocol.com/protocol/session-modes#agent"))
        assertEquals("Auto", chatValueLabel("auto"))
        assertEquals(SyntaxColors.MaterialYou, WorkbenchSettings.from(JsonParser.parseString("""{"avf":{"theme":{"syntaxColors":"materialYou"}}}""").asJsonObject).syntaxColors)
    }

    @Test
    fun problemSettingsFollowVsCodeKeysAndLintPickersReadTheirKeys() {
        fun from(json: String) = WorkbenchSettings.from(JsonParser.parseString(json).asJsonObject)
        val defaults = from("{}")
        assertEquals(listOf(true, true, true), listOf(defaults.problemUnderlines, defaults.showUnused, defaults.showDeprecated))
        assertEquals(false, from("""{"editor.renderValidationDecorations":"off"}""").problemUnderlines)
        assertEquals(true, from("""{"editor.renderValidationDecorations":"editable"}""").problemUnderlines)
        assertEquals(false, from("""{"editor.showUnused":false}""").showUnused)
        assertEquals(PythonLintRules.Extended, from("""{"avf.python.lintRules":"extended"}""").pythonLintRules)
        assertEquals(PythonTypeChecking.Strict, from("""{"avf.python.typeChecking":"strict"}""").pythonTypeChecking)
        assertEquals(PythonTypeChecking.Default, from("""{"avf.python.typeChecking":"bogus"}""").pythonTypeChecking)
    }

    @Test
    fun autoSaveReadsVsCodeModes() {
        fun mode(value: String) = WorkbenchSettings.from(JsonParser.parseString("""{"files.autoSave":"$value"}""").asJsonObject).autoSave
        assertEquals(AutoSaveMode.AfterDelay, mode("afterDelay"))
        assertEquals(AutoSaveMode.OnFocusChange, mode("onFocusChange"))
        assertEquals(AutoSaveMode.OnWindowChange, mode("onWindowChange"))
        assertEquals(AutoSaveMode.Off, mode("bogus"))
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

    @Test
    fun panelAutoHideIsPerTabWithDefaults() {
        assertEquals(setOf(PanelTab.Ports, PanelTab.Variables, PanelTab.Interactive), WorkbenchSettings().panelAutoHide)
        assertEquals("avf.panel.autoHide.ports", WorkbenchSettings.panelAutoHideKey(PanelTab.Ports))
        val nested = JsonParser.parseString("""{"avf":{"panel":{"autoHide":{"ports":false,"output":true}}}}""").asJsonObject
        assertEquals(setOf(PanelTab.Output, PanelTab.Variables, PanelTab.Interactive), WorkbenchSettings.from(nested).panelAutoHide)
        val written = JsonParser.parseString("{}").asJsonObject.withPath(WorkbenchSettings.panelAutoHideKey(PanelTab.Debug), true)
        assertTrue(PanelTab.Debug in WorkbenchSettings.from(written).panelAutoHide)
        assertFalse(PanelTab.Docs in WorkbenchSettings.AUTO_HIDING_PANEL_TABS)
    }

    @Test
    fun saveFoldersAsZipOrFolder() {
        assertEquals("zip", WorkbenchSettings().saveFoldersAs)
        assertEquals("folder", WorkbenchSettings.from(JsonParser.parseString("""{"avf.files.saveFoldersAs":"folder"}""").asJsonObject).saveFoldersAs)
        assertEquals("zip", WorkbenchSettings.from(JsonParser.parseString("""{"avf.files.saveFoldersAs":"tarball"}""").asJsonObject).saveFoldersAs)
    }

    @Test
    fun panelHideWhileTypingAndConnectionBanner() {
        val defaults = WorkbenchSettings()
        assertTrue(defaults.panelHideWhileTyping)
        assertTrue(defaults.connectionBanner)
        val off = WorkbenchSettings.from(JsonParser.parseString("""{"avf.panel.hideWhileTyping":false,"avf":{"connection":{"banner":false}}}""").asJsonObject)
        assertFalse(off.panelHideWhileTyping)
        assertFalse(off.connectionBanner)
    }

    @Test
    fun notebookCellEditorAndLineNumbers() {
        val defaults = WorkbenchSettings()
        assertEquals("aardink", defaults.cellEditor)
        assertFalse(defaults.notebookLineNumbers)
        val set = JsonParser.parseString("""{"avf.notebook.cellEditor":"text","notebook.lineNumbers":"on"}""").asJsonObject
        assertEquals("text", WorkbenchSettings.from(set).cellEditor)
        assertTrue(WorkbenchSettings.from(set).notebookLineNumbers)
        val unknown = JsonParser.parseString("""{"avf.notebook.cellEditor":"monaco"}""").asJsonObject
        assertEquals("aardink", WorkbenchSettings.from(unknown).cellEditor)
    }

    @Test
    fun aardinkEditorOptionsAndAnimationsParseAndValidate() {
        val defaults = WorkbenchSettings()
        assertEquals("on", defaults.lineNumbersStyle)
        assertTrue(defaults.lineNumbers)
        assertEquals("line", defaults.cursorStyle)
        assertEquals("smooth", defaults.cursorBlinking)
        assertTrue(defaults.smoothScrolling)
        assertTrue(defaults.indentGuides)
        assertTrue(defaults.bracketPairGuides)
        assertEquals("same", defaults.wrappingIndent)
        assertFalse(defaults.scrollBeyondLastLine)
        assertFalse(defaults.formatOnType)
        assertTrue(defaults.animations)

        val configured = WorkbenchSettings.from(
            JsonParser.parseString(
                """{
                  "editor.lineNumbers":"relative",
                  "editor.cursorStyle":"block-outline",
                  "editor.cursorBlinking":"solid",
                  "editor.smoothScrolling":false,
                  "editor.guides.indentation":false,
                  "editor.guides.bracketPairs":false,
                  "editor.wrappingIndent":"deepIndent",
                  "editor.scrollBeyondLastLine":true,
                  "editor.formatOnType":true,
                  "avf.workbench.animations":false,
                  "avf.theme.syntaxColors":"highContrast"
                }""",
            ).asJsonObject,
        )
        assertEquals("relative", configured.lineNumbersStyle)
        assertTrue(configured.lineNumbers)
        assertEquals("block-outline", configured.cursorStyle)
        assertEquals("solid", configured.cursorBlinking)
        assertFalse(configured.smoothScrolling)
        assertFalse(configured.indentGuides)
        assertFalse(configured.bracketPairGuides)
        assertEquals("deepIndent", configured.wrappingIndent)
        assertTrue(configured.scrollBeyondLastLine)
        assertTrue(configured.formatOnType)
        assertFalse(configured.animations)
        assertEquals(SyntaxColors.HighContrast, configured.syntaxColors)

        val interval = WorkbenchSettings.from(JsonParser.parseString("""{"editor.lineNumbers":"interval"}""").asJsonObject)
        assertEquals("interval", interval.lineNumbersStyle)
        assertTrue(interval.lineNumbers)

        val off = WorkbenchSettings.from(JsonParser.parseString("""{"editor.lineNumbers":"off"}""").asJsonObject)
        assertEquals("off", off.lineNumbersStyle)
        assertFalse(off.lineNumbers)

        val invalid = WorkbenchSettings.from(
            JsonParser.parseString(
                """{"editor.lineNumbers":"bogus","editor.cursorStyle":"beam","editor.cursorBlinking":"pulse","editor.wrappingIndent":"hanging"}""",
            ).asJsonObject,
        )
        assertEquals("on", invalid.lineNumbersStyle)
        assertEquals("line", invalid.cursorStyle)
        assertEquals("smooth", invalid.cursorBlinking)
        assertEquals("same", invalid.wrappingIndent)
    }

    @Test
    fun legacyGeminiChatAgentMigratesToAntigravity() {
        val legacy = WorkbenchSettings.from(JsonParser.parseString("""{"avf.chat.agent":"gemini"}""").asJsonObject)
        assertEquals(io.github.kinteshi.quirecode.ai.ChatAgentKind.Antigravity, legacy.chatAgent)
        val explicit = WorkbenchSettings.from(JsonParser.parseString("""{"avf.chat.agent":"antigravity"}""").asJsonObject)
        assertEquals(io.github.kinteshi.quirecode.ai.ChatAgentKind.Antigravity, explicit.chatAgent)
    }
}
