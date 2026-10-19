package com.avfstudio.nativeide.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class BracketColorizerTest {
    @Test
    fun assignsNestedColorsAndIgnoresBracketCharactersInPythonStringsAndComments() {
        val text = "call({\"key\": [(value)]}) # ]"

        val tokens = bracketColorTokens(text, "py")

        assertEquals(
            listOf(
                BracketColorToken(4, 0),
                BracketColorToken(5, 1),
                BracketColorToken(13, 2),
                BracketColorToken(14, 0),
                BracketColorToken(20, 0),
                BracketColorToken(21, 2),
                BracketColorToken(22, 1),
                BracketColorToken(23, 0),
            ),
            tokens,
        )
    }

    @Test
    fun ignoresDelimitersInsideMultilineCommentsAndStrings() {
        val text = "items = [1, 2]\n# (\nvalue = ''' } '''"

        assertEquals(
            listOf(BracketColorToken(8, 0), BracketColorToken(13, 0)),
            bracketColorTokens(text, "py"),
        )
    }

    @Test
    fun supportsCommonSlashCommentsAndQuotedStrings() {
        val text = "call(\"[\") // }\nnext({})"

        assertEquals(
            listOf(
                BracketColorToken(4, 0),
                BracketColorToken(8, 0),
                BracketColorToken(19, 0),
                BracketColorToken(20, 1),
                BracketColorToken(21, 1),
                BracketColorToken(22, 0),
            ),
            bracketColorTokens(text, "js"),
        )
    }
}
