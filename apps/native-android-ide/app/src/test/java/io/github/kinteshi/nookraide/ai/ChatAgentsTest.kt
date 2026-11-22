package io.github.kinteshi.nookraide.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatAgentsTest {
    @Test
    fun claudeCodeLinkIsReadOnceItAsksForTheCode() {
        val url = "https://claude.com/cai/oauth/authorize?code=true&client_id=9d1c&response_type=code&state=YHjJ"
        val opening = "Opening browser to sign in…\r\nIf the browser didn't open, visit: $url\r\n"
        assertNull(parseSignInLink(opening))
        assertEquals(SignInLink(url), parseSignInLink(opening + "\u001B[1mPaste code here if prompted > \u001B[0m"))
    }

    @Test
    fun antigravityLinkComesBeforeItsCodePrompt() {
        val url = "https://accounts.google.com/o/oauth2/v2/auth?redirect_uri=https%3A%2F%2Fcodeassist.google.com%2Fauthcode&state=23c9"
        val output = "Please visit the following URL to authorize the application:\r\n\r\n$url\r\n\r\nEnter the authorization code: "
        assertEquals(SignInLink(url), parseSignInLink(output))
    }

    @Test
    fun otherLinksAreNotSignInPages() {
        assertNull(parseSignInLink("See https://example.com/docs then paste code here"))
    }

    @Test
    fun agentsLaunchTheirCatalogTools() {
        assertEquals(listOf("copilot", "claude-code", "antigravity"), ChatAgentKind.entries.map { it.tool })
        assertEquals("antigravity acp --login && exit", ChatAgentKind.Antigravity.loginCommand)
    }
}
