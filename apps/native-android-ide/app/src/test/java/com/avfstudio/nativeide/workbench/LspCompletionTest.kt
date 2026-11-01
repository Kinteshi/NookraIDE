package com.avfstudio.nativeide.workbench

import com.avfstudio.nativeide.editor.LspPosition
import com.avfstudio.nativeide.editor.LspRange
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LspCompletionTest {
    @Test
    fun parsesItemsWithLabelDetailsImportsAndDefaults() {
        val list = parseCompletionList(
            JsonParser.parseString(
                """
                {"isIncomplete": true,
                 "itemDefaults": {"editRange": {"insert": {"start": {"line": 3, "character": 4}, "end": {"line": 3, "character": 6}},
                                                "replace": {"start": {"line": 3, "character": 4}, "end": {"line": 3, "character": 9}}},
                                  "data": {"id": 7}},
                 "items": [
                   {"label": "defaultdict", "kind": 7, "sortText": "b", "labelDetails": {"description": "collections"},
                    "additionalTextEdits": [{"range": {"start": {"line": 0, "character": 0}, "end": {"line": 0, "character": 0}},
                                             "newText": "from collections import defaultdict\n"}]},
                   {"label": "deque", "kind": 3, "detail": "class deque(...)\nmore", "labelDetails": {"detail": "(iterable)"},
                    "textEdit": {"range": {"start": {"line": 3, "character": 4}, "end": {"line": 3, "character": 6}}, "newText": "deque"}}
                 ]}
                """,
            ),
        )
        assertTrue(list.incomplete)
        val (dict, deque) = list.items
        assertEquals("collections", dict.description)
        assertEquals(LspRange(LspPosition(3, 4), LspPosition(3, 6)), dict.insertRange)
        assertEquals("from collections import defaultdict\n", dict.additionalEdits.single().newText)
        assertEquals(7, dict.raw.getAsJsonObject("data").get("id").asInt)
        assertEquals("(iterable)", deque.labelDetail)
        assertEquals("class deque(...)", deque.description)
        assertFalse(parseCompletionList(JsonParser.parseString("[]")).incomplete)
    }

    @Test
    fun fuzzyScoresPreferPrefixesAndBoundaries() {
        assertNull(completionScore("xyz", "append"))
        // The first letter must start the word or a part of it.
        assertNull(completionScore("p", "append"))
        val prefix = completionScore("app", "append")!!
        val boundary = completionScore("ap", "add_path")!!
        val camel = completionScore("gC", "getChildren")!!
        assertTrue(prefix > boundary)
        assertTrue(camel > 0)
        assertTrue(completionScore("App", "Append")!! > completionScore("App", "append")!!)
    }

    @Test
    fun filteringKeepsTheServersOrderForEqualScores() {
        fun item(label: String, sort: String) = parseCompletionItem(JsonParser.parseString("""{"label": "$label", "sortText": "$sort"}""").asJsonObject)!!
        val items = listOf(item("print", "b"), item("property", "a"), item("pow", "c"), item("len", "d"))
        assertEquals(listOf("property", "print", "pow"), filterCompletions(items, "p").map { it.label })
        assertEquals(listOf("property", "print"), filterCompletions(items, "pr").map { it.label })
        assertEquals(listOf("print"), filterCompletions(items, "pri").map { it.label })
        assertEquals(listOf("property", "print", "pow", "len"), filterCompletions(items, "").map { it.label })
    }

    @Test
    fun snippetsLoseTheirPlaceholders() {
        assertEquals("print(value)" to 6, snippetToPlain("print(\${1:value})"))
        assertEquals("foo()" to 4, snippetToPlain("foo(\$1)\$0"))
        assertEquals("a\$b" to 3, snippetToPlain("a\\\$b"))
        assertEquals("x = 1" to 0, snippetToPlain("\${1|x,y|} = 1"))
    }

    @Test
    fun anEditRunsFromTheItemsStartToTheCursor() {
        val item = parseCompletionItem(
            JsonParser.parseString("""{"label": "path", "textEdit": {"insert": {"start": {"line": 2, "character": 3}, "end": {"line": 2, "character": 5}}, "replace": {"start": {"line": 2, "character": 3}, "end": {"line": 2, "character": 8}}, "newText": "path"}}""").asJsonObject,
        )!!
        assertEquals(LspRange(LspPosition(2, 3), LspPosition(2, 6)) to "path", completionEdit(item, 2, 3, 6))
        assertEquals(3, completionWordStart("os.pa", 5))
    }

    @Test
    fun lineCommentsAreFoundOutsideStrings() {
        assertTrue(inLineComment("x = 1  # note ", "py"))
        assertFalse(inLineComment("s = '# not a comment' + ", "py"))
        assertTrue(inLineComment("const a = 1 // ", "ts"))
        assertFalse(inLineComment("url = \"http://x\" + ", "ts"))
        assertFalse(inLineComment("# heading", "md"))
    }
}
