package io.github.kinteshi.quirecode.workbench

import com.aardarch.aardink.core.CodeDocument
import com.aardarch.aardink.core.CodeEditorState
import com.aardarch.aardink.core.CompletionKind
import com.aardarch.aardink.core.DiagnosticSeverity
import com.aardarch.aardink.core.DiagnosticTag
import com.aardarch.aardink.core.PlainTextTokenizer
import com.aardarch.aardink.core.TextEdit
import io.github.kinteshi.quirecode.editor.EditorConfig
import io.github.kinteshi.quirecode.editor.EditorProblem
import io.github.kinteshi.quirecode.editor.LspPosition
import io.github.kinteshi.quirecode.editor.LspRange
import io.github.kinteshi.quirecode.editor.LspTextEdit
import io.github.kinteshi.quirecode.editor.parseSnippet
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AardinkLanguageServiceTest {
    @Test
    fun aSnippetGoesBackToVsCodeSyntaxWithItsStopsAndMirrors() {
        val parsed = parseSnippet("for \${1:item} in \${2:items}:\n\tprint(\$1)\$0")
        assertEquals("for \${1:item} in \${2:items}:\n\tprint(\${1})\${0}", snippetSyntax(parsed))
        assertEquals("cost \\$5 \\}\${0}", snippetSyntax(parseSnippet("cost \\$5 }")))
        assertEquals("\${1|a,b|}\${0}", snippetSyntax(parseSnippet("\${1|a,b|}")))
    }

    @Test
    fun aServerItemKeepsItsRangeEditsKindAndDetail() {
        val document = CodeDocument("import os\nos.pa")
        val item = LspCompletionItem(
            label = "path", labelDetail = "(module)", description = "os", kind = 9, sortText = "a", filterText = "path",
            insertText = "path", snippet = false,
            insertRange = LspRange(LspPosition(1, 3), LspPosition(1, 5)),
            additionalEdits = listOf(LspTextEdit(LspRange(LspPosition(0, 0), LspPosition(0, 0)), "import sys\n")),
            preselect = false, raw = JsonObject(),
        )
        val shown = completionItem(item, document)
        assertEquals(CompletionKind.Module, shown.kind)
        assertEquals(13 until 15, shown.replaceRange)
        assertEquals(listOf(TextEdit(0 until 0, "import sys\n")), shown.additionalEdits)
        assertEquals("(module) os", shown.detail)
        assertEquals(CompletionKind.Text, completionKind(99))
    }

    @Test
    fun signatureHelpKeepsParametersByNameOrByOffsets() {
        val reply = JsonParser.parseString(
            """{"signatures":[{"label":"f(a, b)","documentation":{"kind":"markdown","value":"Does **f**."},
            "parameters":[{"label":[2,3]},{"label":"b","documentation":"second"}]}],"activeSignature":0,"activeParameter":1}""",
        )
        val help = parseAardinkSignatureHelp(reply)!!
        assertEquals("f(a, b)", help.signatures.single().label)
        assertEquals("Does **f**.", help.signatures.single().documentation)
        assertEquals(listOf("a", "b"), help.signatures.single().parameters.map { it.label })
        assertEquals(2 until 3, help.signatures.single().parameters[0].labelRange)
        assertEquals(1, help.activeParameter)
        assertNull(parseAardinkSignatureHelp(JsonParser.parseString("""{"signatures":[]}""")))
    }

    @Test
    fun problemsBecomeDiagnosticsWithTheirTagsAndAtLeastACharacter() {
        val surface = AardinkEditorSurface(CodeEditorState("x = 1\ny = z\n", PlainTextTokenizer, 0L, CoroutineScope(Dispatchers.Unconfined)))
        surface.setProblems(
            listOf(
                EditorProblem(1, 4, 1, 5, 1, "z is not defined", "ty", unnecessary = false, deprecated = false),
                EditorProblem(0, 0, 0, 0, 2, "unused", "", unnecessary = true, deprecated = true),
            ),
            EditorConfig(),
        ) {}
        val (undefined, unused) = surface.problems
        assertEquals(10 until 11, undefined.range)
        assertEquals(DiagnosticSeverity.Error, undefined.severity)
        assertEquals("ty", undefined.source)
        assertEquals(0 until 1, unused.range)
        assertEquals(setOf(DiagnosticTag.Unnecessary, DiagnosticTag.Deprecated), unused.tags)
        surface.setProblems(emptyList(), EditorConfig(problemUnderlines = false)) {}
        assertTrue(surface.problems.isEmpty())
    }

    @Test
    fun cellsAndConflictsGetLensesOnTheirFirstLine() {
        val text = "# %%\nx = 1\n# %% [markdown]\n# text\n<<<<<<< HEAD\na\n=======\nb\n>>>>>>> other\n"
        val document = com.aardarch.aardink.core.CodeDocument(text)
        val lenses = textLenses(text, document, cells = true, controller = null)
        assertEquals(listOf("Run Cell", "Run Above", "Run Below", "Accept Current", "Accept Incoming", "Accept Both"), lenses.map { it.title })
        // The markdown cell runs nothing; the conflict's row is above its <<<<<<< line.
        assertEquals(listOf(0, 0, 0, 34, 34, 34), lenses.map { it.offset })
        assertEquals(listOf("Accept Current", "Accept Incoming", "Accept Both"), textLenses(text, document, cells = false, controller = null).map { it.title })
    }

    @Test
    fun inlayHintsLandAtTheirOffsetsClampedToTheLine() {
        val document = com.aardarch.aardink.core.CodeDocument("total = f(2)\nx\n")
        val hints = aardinkInlayHints(
            listOf(
                io.github.kinteshi.quirecode.language.InlayHint(LspPosition(0, 5), ": int", 1, "Return type"),
                io.github.kinteshi.quirecode.language.InlayHint(LspPosition(0, 10), "factor=", 2, "Multiplier"),
                io.github.kinteshi.quirecode.language.InlayHint(LspPosition(1, 40), ": str", 1),
            ),
            document,
        )
        assertEquals(listOf(5, 10, 14), hints.map { it.offset })
        assertEquals(listOf(": int", "factor=", ": str"), hints.map { it.label })
        assertEquals(listOf("Return type", "Multiplier", null), hints.map { it.tooltip })
    }
}
