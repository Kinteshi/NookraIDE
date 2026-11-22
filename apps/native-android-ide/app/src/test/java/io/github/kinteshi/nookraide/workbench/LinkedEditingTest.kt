package io.github.kinteshi.nookraide.workbench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkedEditingTest {
    @Test
    fun aTagsNameAndItsPartnersAreLinked() {
        val html = "<div class=\"a\"><div>x</div></div>"
        // The outer opening tag's name pairs with the last closing tag, not the inner one.
        assertEquals(listOf(1 until 4, 29 until 32), linkedTagRanges(html, 2))
        // From the closing tag, too.
        assertEquals(listOf(1 until 4, 29 until 32), linkedTagRanges(html, 30))
    }

    @Test
    fun selfClosingUnpairedAndNonTagsAreNotLinked() {
        assertNull(linkedTagRanges("<br/><p></p>", 2))
        assertNull(linkedTagRanges("<div>a", 2))
        assertNull(linkedTagRanges("a < b", 3))
        assertNull(linkedTagRanges("<div>a</div>", 5))
    }
}
