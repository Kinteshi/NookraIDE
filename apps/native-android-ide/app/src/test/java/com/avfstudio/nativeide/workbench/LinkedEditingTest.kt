package com.avfstudio.nativeide.workbench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkedEditingTest {
    private fun apply(text: String, offset: Int, removed: Int, inserted: String): String {
        val edited = text.substring(0, offset) + inserted + text.substring(offset + removed)
        val linked = linkedTagEdit(text, offset, removed, inserted) ?: return edited
        return edited.substring(0, linked.start) + linked.name + edited.substring(linked.end)
    }

    @Test
    fun renamesTheClosingTagWhileTypingInTheOpeningOne() {
        val html = "<div class=\"a\"><div>x</div></div>"
        assertEquals("<divs class=\"a\"><div>x</div></divs>", apply(html, 4, 0, "s"))
        // In the closing tag, the opening one follows.
        assertEquals("<span><b>x</b></span>", apply("<spa><b>x</b></spa>", 18, 0, "n"))
    }

    @Test
    fun aRetypedNameStaysLinked() {
        val emptied = apply("<div>x</div>", 1, 3, "")
        assertEquals("<>x</>", emptied)
        assertEquals("<p>x</p>", apply(emptied, 1, 0, "p"))
    }

    @Test
    fun leavesTagsBeingTypedSelfClosingAndUnpairedAlone() {
        assertNull(linkedTagEdit("<di\n<p>a</p>", 3, 0, "v"))
        assertNull(linkedTagEdit("<br/><p></p>", 3, 0, "x"))
        assertNull(linkedTagEdit("<div>a</div>", 4, 0, " "))
        assertNull(linkedTagEdit("a < b", 3, 0, "x"))
    }
}
