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
        assertEquals(listOf(0, 2), matches.map { it.line })
        assertEquals(listOf(0..4, 6..10), matches[1].ranges)
        assertEquals(listOf(2), findInTerminal(listOf("Error one", "ok", "error error"), "error", caseSensitive = true).map { it.line })
    }
}
