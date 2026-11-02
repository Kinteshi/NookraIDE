package com.avfstudio.nativeide.workbench

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalTextTest {
    @Test
    fun keepsPlainLinesWithoutEscapes() {
        val log = TerminalTextLog()
        log.append("\u001b]0;title\u0007\u001b[1;32mok\u001b[0m line\r\n50%\r100%\nab\bc\n".toByteArray())
        // A multi-byte character split across two reads still decodes.
        val bytes = "café\n".toByteArray()
        log.append(bytes.copyOfRange(0, 4))
        log.append(bytes.copyOfRange(4, bytes.size))
        assertEquals(listOf("ok line", "100%", "ac", "café"), log.snapshot())
    }

    @Test
    fun detectsLinksNewestFirst() {
        val links = detectTerminalLinks(
            listOf(
                "see https://docs.python.org/3/library/re.html.",
                "  File \"/home/u/app/main.py\", line 12, in <module>",
                "src/util.ts:4:9 - error TS2304",
                "version 1.2.3 and file.txt",
            ),
        )
        assertEquals(
            listOf(
                TerminalLink.File("src/util.ts", 4, 9),
                TerminalLink.File("/home/u/app/main.py", 12, null),
                TerminalLink.Web("https://docs.python.org/3/library/re.html"),
            ),
            links,
        )
    }

    @Test
    fun findsLinesWithEveryMatch() {
        val matches = findInTerminal(listOf("Error one", "ok", "error error"), "error", caseSensitive = false)
        assertEquals(listOf(0L, 2L), matches.map { it.line })
        assertEquals(listOf(0..4, 6..10), matches[1].ranges)
        assertEquals(listOf(2L), findInTerminal(listOf("Error one", "ok", "error error"), "error", caseSensitive = true).map { it.line })
        assertEquals(listOf(12L), findInTerminal(listOf("a", "b", "c"), "c", caseSensitive = false, firstLine = 10).map { it.line })
    }

    @Test
    fun shellIntegrationMarksBecomeCommands() {
        val log = TerminalTextLog()
        var changes = 0
        log.onCommandsChanged = { changes++ }
        val esc = "\u001b"
        val bel = "\u0007"
        // A prompt, `ls` (two lines of output, exit 0), a prompt, `false` (exit 1), a prompt.
        log.append("$esc]133;A${bel}$ ls$esc]133;B$bel\r\n$esc]133;C$bel$esc]633;E;ls$bel".toByteArray())
        log.append("a.txt\r\nb.txt\r\n$esc]133;D;0$esc\\$esc]133;A$bel$ false\r\n".toByteArray())
        log.append("$esc]133;C$bel$esc]633;E;false$bel$esc]133;D;1$bel$esc]133;A$bel$ ".toByteArray())
        val commands = log.commands()
        assertEquals(listOf("ls", "false"), commands.map { it.command })
        assertEquals(listOf(0, 1), commands.map { it.exitCode })
        assertEquals("a.txt\nb.txt", log.output(commands[0]))
        assertEquals(0L, commands[0].startLine)
        assertEquals(3L, commands[1].startLine)
        // The marks don't show up as text.
        assertEquals(listOf("$ ls", "a.txt", "b.txt", "$ false", "$ "), log.snapshot())
        assertEquals(9, changes)
        // From `false`'s prompt to the end: its line and the current prompt.
        assertEquals(2, log.rowsSince(commands[1].startLine, columns = 80))
        assertEquals(5, log.rowsSince(0, columns = 80))
        assertEquals(10, log.rowsSince(0, columns = 3))
    }
}
