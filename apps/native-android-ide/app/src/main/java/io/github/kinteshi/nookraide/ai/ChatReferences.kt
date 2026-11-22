package io.github.kinteshi.nookraide.ai

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.net.URI

/**
 * Context attached to a chat message: a whole file, or part of one ([startLine]..[endLine],
 * 1-based and inclusive) with its [text] captured when it was attached.
 */
data class ChatReference(
    val path: String,
    val startLine: Int? = null,
    val endLine: Int? = null,
    val text: String? = null,
    /** Shown instead of the line range, e.g. "cell 3" for a notebook cell. */
    val detail: String? = null,
) {
    val isExcerpt: Boolean get() = text != null

    val label: String
        get() {
            val name = path.substringAfterLast('/')
            return when {
                detail != null -> "$name · $detail"
                startLine == null -> name
                startLine == endLine || endLine == null -> "$name:$startLine"
                else -> "$name:$startLine-$endLine"
            }
        }

    val uri: String
        get() {
            val base = URI("file", "", path, null).toASCIIString()
            return if (startLine == null) base else "$base#L$startLine${endLine?.takeIf { it != startLine }?.let { "-L$it" }.orEmpty()}"
        }
}

private val mimeTypes = mapOf(
    "py" to "text/x-python", "ipynb" to "application/x-ipynb+json", "md" to "text/markdown", "json" to "application/json",
    "js" to "text/javascript", "ts" to "text/typescript", "rs" to "text/rust", "sh" to "text/x-shellscript",
    "toml" to "application/toml", "yaml" to "text/yaml", "yml" to "text/yaml", "html" to "text/html", "css" to "text/css",
)

/**
 * ACP `session/prompt` content: the typed text, then each reference. Whole files go as
 * `resource_link` (the agent runs in the guest and can read them). Excerpts go as embedded
 * `resource` blocks when the agent supports `embeddedContext`, otherwise as fenced snippets.
 */
fun acpPromptBlocks(text: String, references: List<ChatReference>, embeddedContext: Boolean): JsonArray {
    val blocks = JsonArray()
    val inlineExcerpts = StringBuilder()
    references.filter { it.isExcerpt && !embeddedContext }.forEach { reference ->
        val language = reference.path.substringAfterLast('.', "")
        inlineExcerpts.append("\n\n`${reference.path}`${reference.startLine?.let { " lines $it-${reference.endLine ?: it}" }.orEmpty()}${reference.detail?.let { " ($it)" }.orEmpty()}:\n")
        inlineExcerpts.append("```$language\n${reference.text!!.trimEnd('\n')}\n```")
    }
    blocks.add(JsonObject().apply {
        addProperty("type", "text")
        addProperty("text", text + inlineExcerpts)
    })
    references.forEach { reference ->
        val mime = mimeTypes[reference.path.substringAfterLast('.', "").lowercase()] ?: "text/plain"
        when {
            !reference.isExcerpt -> blocks.add(JsonObject().apply {
                addProperty("type", "resource_link")
                addProperty("uri", reference.uri)
                addProperty("name", reference.path.substringAfterLast('/'))
                addProperty("mimeType", mime)
            })
            embeddedContext -> blocks.add(JsonObject().apply {
                addProperty("type", "resource")
                add("resource", JsonObject().apply {
                    addProperty("uri", reference.uri)
                    addProperty("mimeType", mime)
                    addProperty("text", reference.text)
                })
            })
        }
    }
    return blocks
}
