package com.avfstudio.nativeide.workbench

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalRepliesTest {
    private fun reply(text: String) = isTerminalReply(text.toByteArray(Charsets.ISO_8859_1))

    @Test
    fun recognizesAutomaticAnswers() {
        assertTrue(reply("\u001b[56;1R"))
        assertTrue(reply("\u001b[1;120R\u001b[56;1R"))
        assertTrue(reply("\u001b[0n"))
        assertTrue(reply("\u001b[?62;22c"))
        assertTrue(reply("\u001b]11;rgb:0000/0000/0000\u0007"))
        assertTrue(reply("\u001b]10;rgb:ffff/ffff/ffff\u001b\\"))
    }

    @Test
    fun keysAreNeverReplies() {
        listOf("a", "ls\r", "\u001b[A", "\u001b[1;5C", "\u001b", "\u0003", "\u001b[3~").forEach { assertFalse(it, reply(it)) }
    }
}
