package com.avfstudio.nativeide.debug

import org.junit.Assert.assertEquals
import org.junit.Test

class DebugOutputTest {
    private val output = DebugConsoleLine.Kind.Output

    @Test
    fun partialChunksJoinUntilANewline() {
        // debugpy sent print("answer", answer) as "answer", " ", "6\n" on the tablet.
        val lines = mutableListOf<DebugConsoleLine>()
        var open: String? = null
        listOf("answer", " ", "6\n", "next\n").forEach { open = appendDebugOutput(lines, it, output, "stdout", open) }
        assertEquals(listOf("answer 6", "next"), lines.map { it.text })
        assertEquals(null, open)
    }

    @Test
    fun multiLineChunksSplitAndOtherKindsStartNewLines() {
        val lines = mutableListOf<DebugConsoleLine>()
        var open = appendDebugOutput(lines, "a\nb", output, "stdout", null)
        open = appendDebugOutput(lines, "warn\n", DebugConsoleLine.Kind.Error, "stderr", open)
        appendDebugOutput(lines, "c\n", output, "stdout", open)
        assertEquals(listOf("a", "b", "warn", "c"), lines.map { it.text })
    }

    @Test
    fun logpointsNeverJoinAHalfPrintedLine() {
        // Seen on the tablet: a logpoint's "n is 7" and print()'s " 4 total = 104" on one line.
        val lines = mutableListOf<DebugConsoleLine>()
        var open = appendDebugOutput(lines, "4", output, "stdout", null)
        open = appendDebugOutput(lines, "n is 7", output, "console", open)
        open = appendDebugOutput(lines, " total = 104\n", output, "stdout", open)
        assertEquals(listOf("4", "n is 7", " total = 104"), lines.map { it.text })
        assertEquals(null, open)
    }
}
