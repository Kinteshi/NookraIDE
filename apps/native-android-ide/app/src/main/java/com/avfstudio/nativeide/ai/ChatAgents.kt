package com.avfstudio.nativeide.ai

/**
 * The agents the Chat view talks to over ACP, each a tool from the guest catalog. Copilot CLI is
 * a download; Claude Code (its ACP adapter, which carries the Claude Code CLI) and Gemini CLI run
 * on a pinned Node.js that `avf-node-agent` installs under ~/.local/share/materialcode.
 */
enum class ChatAgentKind(
    val key: String,
    val label: String,
    /** The catalog tool the daemon launches. */
    val tool: String,
    /** Signs in interactively in a terminal; the shell exits only when that worked. */
    val loginCommand: String,
    /** What the running agent holds in the guest (measured on the tablet, 10-29), for the resources panel. */
    val memoryNote: String,
) {
    Copilot("copilot", "Copilot", COPILOT_AGENT_TOOL, COPILOT_LOGIN_COMMAND, "~290 MB"),
    ClaudeCode("claude-code", "Claude Code", "claude-code", "avf-node-agent login claude-code && exit", "~310 MB"),
    Gemini("gemini", "Gemini", "gemini", "avf-node-agent login gemini && exit", "~380 MB"),
}

/** A sign-in page an agent's login printed, and that it then waits for the page's code. */
data class SignInLink(val url: String)

private val CLAUDE_SIGN_IN = Regex("""https://claude\.(?:com|ai)/\S*oauth/authorize\?\S+""")
private val GOOGLE_SIGN_IN = Regex("""https://accounts\.google\.com/o/oauth2/\S+""")

/**
 * The sign-in link in a login's terminal output: Claude Code prints "If the browser didn't
 * open, visit: …" then "Paste code here", Gemini "Please visit the following URL…" then "Enter
 * the authorization code". Only once the prompt for the code is there, so the link is complete.
 */
fun parseSignInLink(output: String): SignInLink? {
    val text = stripAnsi(output)
    if ("Paste code" !in text && "authorization code" !in text) return null
    val match = CLAUDE_SIGN_IN.find(text) ?: GOOGLE_SIGN_IN.find(text) ?: return null
    return SignInLink(match.value)
}
