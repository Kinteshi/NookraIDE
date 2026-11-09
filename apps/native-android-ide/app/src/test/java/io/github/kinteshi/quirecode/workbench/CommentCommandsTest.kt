package io.github.kinteshi.quirecode.workbench

import org.junit.Assert.assertEquals
import org.junit.Test

class CommentCommandsTest {
    @Test
    fun commentsEveryLineAtTheShallowestIndentAndTogglesBack() {
        val lines = listOf("def f():", "    return 1", "", "  x = 2")
        val commented = toggleLineComments(lines, "#")
        assertEquals(listOf("# def f():", "#     return 1", "", "#   x = 2"), commented)
        assertEquals(lines, toggleLineComments(commented, "#"))
    }

    @Test
    fun mixedSelectionGetsCommentedRatherThanUncommented() {
        assertEquals(listOf("# # a", "# b"), toggleLineComments(listOf("# a", "b"), "#"))
    }

    @Test
    fun wrapsAndUnwrapsBlockComments() {
        val wrapped = toggleBlockComment("a = b + c", 4, 9, "/*", "*/")
        assertEquals(TextEdit("a = /* b + c */", 7, 12), wrapped)
        assertEquals(TextEdit("a = b + c", 4, 9), toggleBlockComment(wrapped.text, wrapped.selectionStart, wrapped.selectionEnd, "/*", "*/"))
        assertEquals(TextEdit("a = b + c", 4, 9), toggleBlockComment("a = /* b + c */", 4, 15, "/*", "*/"))
    }

    @Test
    fun emptySelectionInsertsAnEmptyBlockWithTheCursorInside() {
        assertEquals(TextEdit("x<!--  -->", 6, 6), toggleBlockComment("x", 1, 1, "<!--", "-->"))
    }

    @Test
    fun findsTheChangedSpan() {
        assertEquals(ChangedSpan(2, 3, 5), changedSpan("abcde", "abXYZde"))
        assertEquals(ChangedSpan(3, 3, 5), changedSpan("abc", "abc!!"))
    }
}
