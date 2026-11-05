package com.avfstudio.aardinklab

import androidx.test.platform.app.InstrumentationRegistry
import com.aardarch.aardink.core.NamedTokenType
import com.aardarch.aardink.core.Token
import kotlin.random.Random
import org.eclipse.tm4e.core.registry.IGrammarSource
import org.eclipse.tm4e.core.registry.IThemeSource
import org.eclipse.tm4e.core.registry.Registry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextMateTokenizerTest {
    private val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
    private val registry = Registry().apply {
        setTheme(IThemeSource.fromInputStream(assets.open("textmate/avf-dark.tmTheme.json"), "avf-dark.tmTheme.json", null))
    }
    private val grammar = registry.addGrammar(
        IGrammarSource.fromInputStream(assets.open("textmate/python.tmLanguage.json"), "python.tmLanguage.json", null),
    )

    private fun tokenizer() = TextMateTokenizer(grammar)
    private fun typeAt(tokens: List<Token>, text: String, needle: String): String {
        val at = text.indexOf(needle)
        return (tokens.first { it.start <= at && at < it.end }.type as NamedTokenType).name
    }

    @Test
    fun coloursFollowTheThemeAndKnowCommentsAndStrings() {
        val text = "def f(x):\n    return \"text\"  # note\n"
        val tokens = tokenizer().tokenizeFull(text)
        assertTrue(typeAt(tokens, text, "\"text\"").startsWith("string."))
        assertTrue(typeAt(tokens, text, "# note").startsWith("comment."))
        assertNotEquals(typeAt(tokens, text, "def"), typeAt(tokens, text, "return"))
        val colors = textMateTokenColors(registry.colorMap).first
        assertTrue(tokens.filter { !it.isEmpty }.all { it.type in colors })
    }

    @Test
    fun tokensAreSortedAndInsideTheDocument() {
        val text = "a = 1\n\n\nb = '''x\ny'''\n"
        val tokens = tokenizer().tokenizeFull(text)
        assertEquals(tokens.sortedBy { it.start }, tokens)
        assertTrue(tokens.all { it.end <= text.length })
        tokens.zipWithNext { a, b -> assertTrue(a.end <= b.start) }
    }

    @Test
    fun emptyLinesAreCovered() {
        val text = "a\n\nb"
        val tokens = tokenizer().tokenizeFull(text)
        assertTrue(tokens.any { it.isEmpty && it.start == 2 })
    }

    @Test
    fun openingAStringRecoloursLaterLinesAndClosingItRestoresThem() {
        val tokenizer = tokenizer()
        val before = "x = 1\ny = 2\nz = 3\n"
        val previous = tokenizer.tokenizeFull(before)
        val opened = "x = '''\ny = 2\nz = 3\n"
        val result = tokenizer.tokenizeLines(opened, 0..0, previous)
        assertTrue(typeAt(result, opened, "z = 3").startsWith("string."))
        val closed = "x = 1\ny = 2\nz = 3\n"
        val again = tokenizer.tokenizeLines(closed, 0..0, result)
        assertEquals(previous, tokenizer.allTokens())
        assertTrue(again.isNotEmpty())
    }

    @Test
    fun anEditThatChangesNoStateStopsEarly() {
        val tokenizer = tokenizer()
        val text = (0 until 200).joinToString("\n") { "value_$it = $it" }
        val previous = tokenizer.tokenizeFull(text)
        val edited = text.replace("value_5 = 5", "value_5 = 55")
        val lineFive = text.split('\n').take(5).sumOf { it.length + 1 }
        val result = tokenizer.tokenizeLines(edited, 5..5, previous)
        assertTrue(result.first().start >= lineFive)
        assertTrue("rescanned ${result.size} of ${previous.size}", result.size < previous.size / 50)
    }

    @Test
    fun randomEditsMatchAFullPass() {
        val random = Random(42)
        val pieces = listOf("x = 1", "'''", "\"\"\"", "# note", "def f():", "    return 2", "", "s = 'a", "print(s)", "\\")
        repeat(40) { round ->
            val tokenizer = tokenizer()
            var lines = MutableList(30) { pieces.random(random) }
            var previous = tokenizer.tokenizeFull(lines.joinToString("\n"))
            repeat(25) {
                val at = random.nextInt(lines.size)
                val dirty = when (random.nextInt(3)) {
                    0 -> { lines[at] = pieces.random(random); at..at }
                    1 -> { lines.add(at, pieces.random(random)); at..at + 1 }
                    else -> if (lines.size > 3) { lines.removeAt(at); at..at.coerceAtMost(lines.size - 1) } else at..at
                }
                val text = lines.joinToString("\n")
                previous = tokenizer.tokenizeLines(text, dirty, previous).ifEmpty { previous }
                assertEquals("round $round", tokenizer().tokenizeFull(text), tokenizer.allTokens())
            }
            lines = mutableListOf()
        }
    }

    @Test
    fun aDirtyRangeReturnsEveryTokenOnTheLinesItCovers() {
        val tokenizer = tokenizer()
        val text = "a = 1\nb = 2\nc = 3"
        val previous = tokenizer.tokenizeFull(text)
        val edited = "a = 1\nb = 22\nc = 3"
        val result = tokenizer.tokenizeLines(edited, 1..1, previous)
        val full = tokenizer().tokenizeFull(edited)
        assertEquals(full.filter { it.start >= result.first().start && it.end <= result.last().end }, result)
    }
}
