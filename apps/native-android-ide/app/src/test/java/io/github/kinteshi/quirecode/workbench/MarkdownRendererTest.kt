package io.github.kinteshi.quirecode.workbench

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownRendererTest {
    @Test
    fun blocksCarryTheirSourceLine() {
        val html = MarkdownRenderer.render("# Title\n\nFirst paragraph.\n\n- one\n- two\n")
        assertTrue(html, html.contains("""<h1 data-line="0">Title</h1>"""))
        assertTrue(html, html.contains("""<p data-line="2">First paragraph.</p>"""))
        assertTrue(html, html.contains("""<li data-line="5">two</li>"""))
    }

    @Test
    fun githubTablesStrikethroughTaskListsAndAutolinks() {
        val html = MarkdownRenderer.render("| a | b |\n|---|---|\n| 1 | 2 |\n\n~~old~~ https://example.com\n\n- [x] done\n")
        assertTrue(html, html.contains("<table"))
        assertTrue(html, html.contains("<del>old</del>"))
        assertTrue(html, html.contains("""href="https://example.com">https://example.com</a>"""))
        assertTrue(html, html.contains("""type="checkbox""""))
    }

    @Test
    fun scriptLinksAreDropped() {
        val html = MarkdownRenderer.render("[x](javascript:alert(1))")
        assertFalse(html, html.contains("javascript:"))
    }

    @Test
    fun markdownFilesAreRecognised() {
        assertTrue(isMarkdownPath("/w/README.md"))
        assertTrue(isMarkdownPath("/w/notes.MARKDOWN"))
        assertFalse(isMarkdownPath("/w/main.py"))
    }

    @Test
    fun markdownBaseUrlPreservesGuestAndDeviceTreePaths() {
        val guestBase = markdownBaseUrl("/home/droid/My Project/docs")
        val resolvedGuest = java.net.URI(guestBase).resolve("../images/chart%201.svg").toString()
        org.junit.Assert.assertEquals("/home/droid/My Project/images/chart 1.svg", markdownUrlPath(resolvedGuest))

        val treeRoot = "tree:/content%3A%2F%2Fcom.android.externalstorage.documents%2Ftree%2Fprimary%253ANotes"
        val treeBase = markdownBaseUrl("$treeRoot/sub")
        val resolvedTree = java.net.URI(treeBase).resolve("../diagram.svg").toString()
        org.junit.Assert.assertEquals("$treeRoot/diagram.svg", markdownUrlPath(resolvedTree))
    }
}
