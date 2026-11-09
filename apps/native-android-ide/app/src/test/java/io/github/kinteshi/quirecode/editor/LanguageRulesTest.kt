package io.github.kinteshi.quirecode.editor

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanguageRulesTest {
    private val python = LanguageRules.parse(File("src/main/assets/textmate/python.language-configuration.json").readText())
    private val noTokens: (Int) -> String? = { null }

    @Test
    fun enterIndentsAfterAColonAndKeepsTheLineOtherwise() {
        assertEquals("    ", python.enterIndentation("def f(x):", "", "    "))
        assertEquals("        ", python.enterIndentation("    if x:  # yes", "", "    "))
        assertEquals("    ", python.enterIndentation("    x = 1", "", "    "))
        assertEquals("\t", python.enterIndentation("for i in y:", "", "\t"))
    }

    @Test
    fun aClosingKeywordComesBackOutALevel() {
        assertEquals("    ", python.enterIndentation("        return 1", "else:", "    "))
    }

    @Test
    fun pairsCloseBeforeSpaceOrAClosingCharacterOnly() {
        assertEquals(")", python.autoClose("f(", 1, '(', noTokens))
        assertEquals("]", python.autoClose("x = [)", 4, '[', noTokens))
        assertNull(python.autoClose("(abc", 0, '(', noTokens))
    }

    @Test
    fun quotesCloseUnlessAfterAWordOrInAString() {
        assertEquals("'", python.autoClose("x = '", 4, '\'', noTokens))
        assertEquals("\"", python.autoClose("f\"", 1, '"', noTokens))
        assertNull(python.autoClose("don'", 3, '\'', noTokens))
        assertNull(python.autoClose("x = 'a'", 6, '\'') { "string.c4" })
        assertNull(python.autoClose("# it'", 4, '\'') { "comment.c5" })
    }

    @Test
    fun surroundingAndCommentsComeFromTheFile() {
        assertEquals(")", python.surroundingPairs['('])
        assertEquals("#", python.lineComment)
    }

    @Test
    fun commentsTrailingCommasAndUnreadablePatternsAreTolerated() {
        val rules = LanguageRules.parse(
            """
            // VS Code's files have comments
            { "comments": { "lineComment": "--", },
              "brackets": [["(", ")"],],
              "onEnterRules": [ { "beforeText": "(unclosed", "action": { "indent": "indent" } } ],
            }
            """.trimIndent(),
        )
        assertEquals("--", rules.lineComment)
        assertEquals(")", rules.autoClose("(", 0, '(', noTokens))
        assertEquals(emptyList<LanguageRules.OnEnterRule>(), rules.onEnterRules)
    }

    @Test
    fun elseMovesBackALevelAsItsColonIsTyped() {
        val unit = "    "
        // After a block's last line, and right under its header.
        assertEquals("", python.outdentAsTyped("    else", "    else:", "    x = 1", unit))
        assertEquals("    ", python.outdentAsTyped("        except ValueError", "        except ValueError:", "        pass", unit))
        assertEquals("", python.outdentAsTyped("    else", "    else:", "if x:", unit))
        // Placed by hand, already matching, not the rule, or nothing above: left alone.
        assertNull(python.outdentAsTyped("else", "else:", "    x = 1", unit))
        assertNull(python.outdentAsTyped("    else:", "    else: ", "    x = 1", unit))
        assertNull(python.outdentAsTyped("    elsewhere = {a", "    elsewhere = {a:", "    x = 1", unit))
        assertNull(python.outdentAsTyped("    else", "    else:", null, unit))
    }
}
