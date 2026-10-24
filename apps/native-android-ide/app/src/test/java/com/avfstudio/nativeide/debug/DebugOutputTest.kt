package com.avfstudio.nativeide.debug

import org.junit.Assert.assertEquals
import org.junit.Test

class DebugOutputTest {
    private val output = DebugConsoleLine.Kind.Output

    @Test
    fun partialChunksJoinUntilANewline() {
        // debugpy sent print("answer", answer) as "answer", " ", "6\n" on the tablet.
        val lines = mutableListOf<DebugConsoleLine>()
        var open = false
        listOf("answer", " ", "6\n", "next\n").forEach { open = appendDebugOutput(lines, it, output, open) }
        assertEquals(listOf("answer 6", "next"), lines.map { it.text })
        assertEquals(false, open)
    }

    @Test
    fun multiLineChunksSplitAndOtherKindsStartNewLines() {
        val lines = mutableListOf<DebugConsoleLine>()
        var open = appendDebugOutput(lines, "a\nb", output, false)
        open = appendDebugOutput(lines, "warn\n", DebugConsoleLine.Kind.Error, open)
        appendDebugOutput(lines, "c\n", output, open)
        assertEquals(listOf("a", "b", "warn", "c"), lines.map { it.text })
    }
}
