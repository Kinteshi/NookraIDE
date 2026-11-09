package io.github.kinteshi.quirecode.editor

import com.aardarch.aardink.core.NamedTokenType
import com.aardarch.aardink.core.Token
import java.io.File
import kotlin.random.Random
import org.eclipse.tm4e.core.registry.IGrammarSource
import org.eclipse.tm4e.core.registry.IThemeSource
import org.eclipse.tm4e.core.registry.Registry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TextMateTokenizerTest {
    private val assets = File("src/main/assets/textmate")
    private val registry = Registry().apply { setTheme(IThemeSource.fromFile(File(assets, "avf-dark.tmTheme.json"))) }
    private val grammar = registry.addGrammar(IGrammarSource.fromFile(File(assets, "python.tmLanguage.json")))
    private val semantic = NamedTokenType("semantic.function")

    private fun tokenizer() = TextMateTokenizer(grammar)
    private fun typeAt(tokens: List<Token>, text: String, needle: String, nth: Int = 0): String {
        var at = -1
        repeat(nth + 1) { at = text.indexOf(needle, at + 1) }
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
    fun tokensAreSortedApartAndInsideTheDocument() {
        val text = "a = 1\n\n\nb = '''x\ny'''\n"
        val tokens = tokenizer().tokenizeFull(text)
        assertTrue(tokens.all { it.end <= text.length })
        tokens.zipWithNext { a, b -> assertTrue(a.end <= b.start) }
        assertTrue(tokens.any { it.isEmpty && it.start == 6 })
    }

    @Test
    fun randomEditsMatchAFullPass() {
        val random = Random(42)
        val pieces = listOf("x = 1", "'''", "\"\"\"", "# note", "def f():", "    return 2", "", "s = 'a", "print(s)", "\\")
        repeat(40) { round ->
            val tokenizer = tokenizer()
            val lines = MutableList(30) { pieces.random(random) }
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
                assertEquals("round $round", tokenizer().tokenizeFull(text), tokenizer.grammarTokens())
            }
        }
    }

    @Test
    fun aSemanticColourGoesOverTheGrammarsOnItsCharactersOnly() {
        val tokenizer = tokenizer()
        val text = "x = 1\nresult = compute(x)\n"
        tokenizer.tokenizeFull(text)
        tokenizer.semantic = mapOf(1 to listOf(SemanticSpan(1, 9, 16, semantic)))
        val tokens = tokenizer.tokenizeFull(text)
        val compute = tokens.filter { it.type == semantic }
        assertEquals(listOf(text.indexOf("compute") until text.indexOf("compute") + 7), compute.map { it.start until it.end })
        assertNotEquals(semantic, tokens.first { it.start == text.indexOf("result") }.type)
        tokens.zipWithNext { a, b -> assertTrue(a.end <= b.start) }
        assertEquals(text.length - text.count { it == '\n' }, tokens.sumOf { it.length })
    }

    @Test
    fun theSameTextIsColouredAgainWithoutBeingScanned() {
        val tokenizer = tokenizer()
        val text = "a = 1\n"
        tokenizer.tokenizeFull(text)
        val scanned = tokenizer.grammarTokens()
        tokenizer.semantic = mapOf(0 to listOf(SemanticSpan(0, 0, 1, semantic)))
        tokenizer.tokenizeFull(text)
        assertEquals(scanned, tokenizer.grammarTokens())
        assertSame(scanned[0].type, tokenizer.grammarTokens()[0].type)
    }

    @Test
    fun askedWithNothingBeforeItAnswersForTheLinesAndKeepsItsOwnState() {
        val tokenizer = tokenizer()
        tokenizer.tokenizeFull("a = 1\nb = 2\n")
        val kept = tokenizer.grammarTokens()
        val snippet = "x = '''\ny\nz = 3"
        val lines = tokenizer.tokenizeLines(snippet, 1..2, emptyList())
        assertEquals(tokenizer().tokenizeFull(snippet).filter { it.start >= snippet.indexOf("y") }, lines)
        assertTrue(lines.all { (it.type as NamedTokenType).name.startsWith("string.") || it.isEmpty })
        assertEquals(kept, tokenizer.grammarTokens())
    }

    @Test
    fun anIncrementalPassReturnsItsLinesWithSemanticColours() {
        val tokenizer = tokenizer()
        val text = "a = 1\nb = f(a)\nc = 3"
        val previous = tokenizer.tokenizeFull(text)
        tokenizer.semantic = mapOf(1 to listOf(SemanticSpan(1, 4, 5, semantic)))
        val edited = "a = 1\nb = f(a) \nc = 3"
        val result = tokenizer.tokenizeLines(edited, 1..1, previous)
        assertTrue(result.any { it.type == semantic && it.start == edited.indexOf("f(") })
    }
}
