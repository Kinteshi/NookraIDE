package com.avfstudio.nativeide.language

import com.avfstudio.nativeide.editor.LspPosition
import com.avfstudio.nativeide.editor.LspRange
import com.avfstudio.nativeide.transport.GuestVfsClient
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.util.concurrent.CompletableFuture

const val COPILOT_LANGUAGE_SERVER_TOOL = "copilot-language-server"

/** Device-flow code the user enters at [verificationUri]; [command] finishes the flow. */
data class CopilotSignIn(val userCode: String, val verificationUri: String?, val command: JsonObject?)

/** Copilot's `didChangeStatus` state; kind is Normal, Error, Warning or Inactive. */
data class CopilotStatus(val kind: String, val message: String, val busy: Boolean)

data class CopilotSuggestion(
    val insertText: String,
    val range: LspRange,
    val command: JsonObject?,
    val item: JsonObject,
)

/** A next edit suggestion: [text] replaces [range] of the document at [version], anywhere in the file. */
data class CopilotNextEdit(
    val text: String,
    val range: LspRange,
    val version: Long?,
    val command: JsonObject?,
    val item: JsonObject,
)

/**
 * GitHub Copilot inline completions over `@github/copilot-language-server`, following the
 * protocol documented in github/copilot-language-server-release. Copilot keeps its own
 * document state, so the workbench mirrors open documents into it like any language server.
 */
class CopilotClient(
    transport: GuestVfsClient,
    editorVersion: String,
) {
    val language = LanguageClient(
        transport,
        initializationOptions = JsonObject().apply {
            add("editorInfo", JsonObject().apply {
                addProperty("name", "MaterialCode")
                addProperty("version", editorVersion)
            })
            add("editorPluginInfo", JsonObject().apply {
                addProperty("name", "avf-studio-copilot")
                addProperty("version", editorVersion)
            })
        },
    )
    @Volatile var statusListener: ((CopilotStatus) -> Unit)? = null

    init {
        language.notificationListener = { method, params ->
            if (method == "didChangeStatus") {
                statusListener?.invoke(
                    CopilotStatus(
                        kind = params.get("kind")?.asString ?: "Normal",
                        message = params.get("message")?.asString.orEmpty(),
                        busy = params.get("busy")?.asBoolean ?: false,
                    ),
                )
            }
        }
    }

    @Volatile private var workspaceRoot: String? = null

    fun start(rootPath: String): CompletableFuture<Unit> {
        workspaceRoot = rootPath
        return language.start(rootPath, COPILOT_LANGUAGE_SERVER_TOOL)
    }

    /** Starts the device flow; completes with null when the guest is already signed in. */
    fun signIn(): CompletableFuture<CopilotSignIn?> =
        language.request("signIn", JsonObject()).thenApply(::parseSignIn)

    fun finishSignIn(signIn: CopilotSignIn): CompletableFuture<JsonElement> {
        val command = signIn.command
            ?: return CompletableFuture.failedFuture(IllegalStateException("Copilot sent no sign-in command"))
        return executeCommand(command)
    }

    fun signOut(): CompletableFuture<JsonElement> = language.request("signOut", JsonObject())

    fun didFocus(path: String?) {
        language.notify("textDocument/didFocus", JsonObject().apply {
            if (path != null) add("textDocument", JsonObject().apply { addProperty("uri", fileUri(path)) })
        })
    }

    fun inlineCompletion(
        path: String,
        version: Long,
        position: LspPosition,
        tabSize: Int,
        insertSpaces: Boolean,
        invoked: Boolean,
    ): CompletableFuture<List<CopilotSuggestion>> =
        language.request("textDocument/inlineCompletion", JsonObject().apply {
            add("textDocument", JsonObject().apply {
                addProperty("uri", fileUri(path))
                addProperty("version", version)
            })
            add("position", JsonObject().apply {
                addProperty("line", position.line)
                addProperty("character", position.character)
            })
            // LSP InlineCompletionTriggerKind: 1 = Invoked, 2 = Automatic.
            add("context", JsonObject().apply { addProperty("triggerKind", if (invoked) 1 else 2) })
            add("formattingOptions", JsonObject().apply {
                addProperty("tabSize", tabSize)
                addProperty("insertSpaces", insertSpaces)
            })
        }).thenApply(::parseSuggestions)

    /** Next edit suggestions (`textDocument/copilotInlineEdit`): edits that may change text away from the cursor. */
    fun inlineEdit(path: String, version: Long, position: LspPosition): CompletableFuture<List<CopilotNextEdit>> =
        language.request("textDocument/copilotInlineEdit", JsonObject().apply {
            add("textDocument", JsonObject().apply {
                addProperty("uri", fileUri(path))
                addProperty("version", version)
            })
            add("position", JsonObject().apply {
                addProperty("line", position.line)
                addProperty("character", position.character)
            })
        }).thenApply(::parseNextEdits)

    fun didShowNextEdit(edit: CopilotNextEdit) {
        language.notify("textDocument/didShowInlineEdit", JsonObject().apply { add("item", edit.item) })
    }

    fun acceptedNextEdit(edit: CopilotNextEdit) {
        edit.command?.let(::executeCommand)
    }

    fun didShow(suggestion: CopilotSuggestion) {
        language.notify("textDocument/didShowCompletion", JsonObject().apply { add("item", suggestion.item) })
    }

    /** Reports acceptance; the edit itself is applied by the editor. */
    fun accepted(suggestion: CopilotSuggestion) {
        suggestion.command?.let(::executeCommand)
    }

    private fun executeCommand(command: JsonObject): CompletableFuture<JsonElement> =
        language.executeCommand(
            command.get("command")?.asString.orEmpty(),
            command.getAsJsonArray("arguments") ?: JsonArray(),
        )

    /** Untitled files go under the workspace's `.untitled/`, as for the language servers. */
    private fun fileUri(path: String): String = lspDocumentUri(path, workspaceRoot)
}

internal fun parseSignIn(result: JsonElement): CopilotSignIn? {
    if (!result.isJsonObject) return null
    val value = result.asJsonObject
    val userCode = value.get("userCode")?.takeUnless { it.isJsonNull }?.asString ?: return null
    return CopilotSignIn(
        userCode = userCode,
        verificationUri = value.get("verificationUri")?.takeUnless { it.isJsonNull }?.asString,
        command = value.get("command")?.takeIf { it.isJsonObject }?.asJsonObject,
    )
}

internal fun parseSuggestions(result: JsonElement): List<CopilotSuggestion> {
    val items = when {
        result.isJsonArray -> result.asJsonArray
        result.isJsonObject -> result.asJsonObject.getAsJsonArray("items") ?: JsonArray()
        else -> JsonArray()
    }
    return items.mapNotNull { element ->
        if (!element.isJsonObject) return@mapNotNull null
        val item = element.asJsonObject
        val insertText = item.get("insertText")?.takeIf { it.isJsonPrimitive }?.asString ?: return@mapNotNull null
        val range = item.getAsJsonObject("range") ?: return@mapNotNull null
        val start = range.getAsJsonObject("start") ?: return@mapNotNull null
        val end = range.getAsJsonObject("end") ?: return@mapNotNull null
        CopilotSuggestion(
            insertText = insertText.replace("\r\n", "\n"),
            range = LspRange(
                LspPosition(start.get("line").asInt, start.get("character").asInt),
                LspPosition(end.get("line").asInt, end.get("character").asInt),
            ),
            command = item.get("command")?.takeIf { it.isJsonObject }?.asJsonObject,
            item = item,
        )
    }
}

internal fun parseNextEdits(result: JsonElement): List<CopilotNextEdit> {
    val edits = result.takeIf { it.isJsonObject }?.asJsonObject?.getAsJsonArray("edits") ?: return emptyList()
    return edits.mapNotNull { element ->
        val edit = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        val text = edit.get("text")?.takeIf { it.isJsonPrimitive }?.asString ?: return@mapNotNull null
        val range = edit.getAsJsonObject("range") ?: return@mapNotNull null
        val start = range.getAsJsonObject("start") ?: return@mapNotNull null
        val end = range.getAsJsonObject("end") ?: return@mapNotNull null
        CopilotNextEdit(
            text = text.replace("\r\n", "\n"),
            range = LspRange(
                LspPosition(start.get("line").asInt, start.get("character").asInt),
                LspPosition(end.get("line").asInt, end.get("character").asInt),
            ),
            version = edit.getAsJsonObject("textDocument")?.get("version")?.takeIf { it.isJsonPrimitive }?.asLong,
            command = edit.get("command")?.takeIf { it.isJsonObject }?.asJsonObject,
            item = edit,
        )
    }
}
