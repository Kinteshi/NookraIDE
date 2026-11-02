package com.avfstudio.nativeide.workbench

import com.avfstudio.nativeide.notebook.CellType
import com.avfstudio.nativeide.notebook.NotebookCell
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotebookIntelligenceTest {
    @Test
    fun magicsBecomeCommentsOfTheSameLength() {
        val source = "%matplotlib inline\nimport os\n  !ls -la\nx = 1  # %not a magic"
        val cleaned = withoutMagics(source)
        assertEquals("#matplotlib inline\nimport os\n  #ls -la\nx = 1  # %not a magic", cleaned)
        assertEquals(source.length, cleaned.length)
    }

    @Test
    fun languageServerMirrorKeepsLinesAndComments() {
        val cells = listOf(
            NotebookCell("a", CellType.Code, "%%time\nx = 1"),
            NotebookCell("b", CellType.Markdown, "# Title"),
            NotebookCell("c", CellType.Code, "print(x)"),
        )
        val mirror = notebookMirror(cells, forLanguageServer = true)
        assertEquals("# %%\n#%time\nx = 1\n\n# %% [markdown]\n# # Title\n\n# %%\nprint(x)\n\n", mirror.text)
        assertEquals(mapOf("a" to 1, "b" to 5, "c" to 8), mirror.startLines)
    }

    @Test
    fun ipythonNamesAndTopLevelAwaitAreNotProblems() {
        assertTrue(isNotebookOnlyProblem("Name `display` used when not defined"))
        assertTrue(isNotebookOnlyProblem("\"await\" allowed only within async function"))
        assertFalse(isNotebookOnlyProblem("Name `pandas` used when not defined"))
    }

    @Test
    fun hoverStaysWithinAWord() {
        val source = "total = price_total + 1"
        assertTrue(sameWord(source, 8, 15))
        assertFalse(sameWord(source, 8, 2))
        assertFalse(sameWord(source, 8, 20))
    }

    @Test
    fun terminalProfilesFromSettings() {
        val profiles = terminalProfiles(
            JsonParser.parseString(
                """{"zsh": null, "py": {"path": "python3", "args": ["-q"]}, "tmux": {"args": ["attach"]}, "broken": {}}""",
            ).asJsonObject,
        )
        val byName = profiles.associateBy { it.name }
        assertFalse("zsh" in byName)
        assertEquals(TerminalProfile("py", "python3", listOf("-q")), byName["py"])
        assertEquals(TerminalProfile("tmux", "tmux", listOf("attach")), byName["tmux"])
        assertFalse("broken" in byName)
        assertEquals(BUILT_IN_TERMINAL_PROFILES, terminalProfiles(null))
    }
}
