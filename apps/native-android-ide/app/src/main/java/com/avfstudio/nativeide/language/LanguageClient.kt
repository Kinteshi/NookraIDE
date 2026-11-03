package com.avfstudio.nativeide.language

import com.avfstudio.nativeide.transport.GuestVfsClient
import com.avfstudio.nativeide.editor.LspPosition
import com.avfstudio.nativeide.editor.LspRange
import com.avfstudio.nativeide.editor.LspTextEdit
import com.avfstudio.nativeide.editor.EditorDecoration
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import java.io.Closeable
import java.io.IOException
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

private const val UNTITLED_PREFIX = "scratch:/"

/**
 * The path a server knows a document by. Untitled (`scratch:`) documents have no file, so they
 * appear under the workspace's `.untitled/` folder (never created on disk): every server accepts
 * `file://` URIs, and imports from the workspace still resolve, as for VS Code's untitled files.
 */
fun lspDocumentPath(path: String, workspaceRoot: String?): String =
    if (path.startsWith(UNTITLED_PREFIX) && workspaceRoot != null) {
        "${workspaceRoot.trimEnd('/')}/.untitled/${path.removePrefix(UNTITLED_PREFIX)}"
    } else {
        path
    }

/**
 * The URI a server knows a document by. Only guest files (absolute paths) become `file://`
 * URIs; an untitled one before any workspace, or a device file (`device:/…`), keeps its own name.
 */
fun lspDocumentUri(path: String, workspaceRoot: String?): String {
    val lspPath = lspDocumentPath(path, workspaceRoot)
    return if (!lspPath.startsWith("/")) lspPath else URI("file", "", lspPath, null).toASCIIString()
}

/** The untitled document [lspPath] stands for, or null for a real file. */
fun untitledPathFor(lspPath: String, workspaceRoot: String?): String? {
    val folder = workspaceRoot?.let { "${it.trimEnd('/')}/.untitled/" } ?: return null
    return lspPath.takeIf { it.startsWith(folder) }?.let { UNTITLED_PREFIX + it.removePrefix(folder) }
}

data class LanguageDiagnostic(
    val uri: String,
    val message: String,
    val severity: Int?,
    val range: JsonObject,
    val source: String?,
    val code: JsonElement?,
    /** LSP diagnostic tags: 1 unnecessary (shown faded), 2 deprecated (struck through). */
    val tags: List<Int> = emptyList(),
    /** Server data to hand back with code action requests (Ruff builds its fixes from it). */
    val data: JsonElement? = null,
)

class LanguageClient(
    transport: GuestVfsClient,
    private val settings: Map<String, JsonElement> = emptyMap(),
    // Keeps nulls like the connection does (`"processId": null` in initialize).
    private val gson: Gson = com.google.gson.GsonBuilder().serializeNulls().create(),
    private val initializationOptions: JsonObject? = null,
) : Closeable {
    @Volatile private var configuredSettings: Map<String, JsonElement> =
        settings.mapValues { (_, value) -> value.deepCopy() }
    private val connection = JsonRpcConnection(transport, "Lsp", gson)
    private val openedDocuments = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var semanticTokenTypes: List<String> = emptyList()
    @Volatile private var semanticTokenModifiers: List<String> = emptyList()
    @Volatile private var workspaceRoot: String? = null
    @Volatile var isReady: Boolean = false
        private set
    /** True from [start] until the server stops, so callers do not launch it twice. */
    @Volatile var isStarted: Boolean = false
        private set
    @Volatile var diagnosticsListener: ((String, List<LanguageDiagnostic>) -> Unit)? = null
    /**
     * Pull diagnostics (LSP 3.17 `textDocument/diagnostic`) for servers that only answer requests,
     * such as TypeScript 7; servers that push (ty, ruff) stop it at their first publish.
     */
    @Volatile private var pullDiagnostics = false
    /** What the server offers, from its initialize reply. */
    @Volatile var signatureTriggerCharacters: Set<String> = emptySet()
        private set
    @Volatile var signatureRetriggerCharacters: Set<String> = emptySet()
        private set
    @Volatile var supportsSignatureHelp = false
        private set
    /** Characters after which the server offers completions (`.` for Python; `.`, `"`, `/` for TypeScript). */
    @Volatile var completionTriggerCharacters: Set<String> = emptySet()
        private set
    @Volatile var supportsCompletionResolve = false
        private set
    @Volatile var supportsDocumentHighlight = false
        private set
    @Volatile var supportsRangeFormatting = false
        private set
    @Volatile var supportsInlayHints = false
    /** `textDocument/codeLens` (TypeScript's reference counts); others get counts from references. */
    @Volatile var supportsCodeLens = false
        private set
    @Volatile var supportsCodeLensResolve = false
        private set
    @Volatile var supportsReferences = false
        private set
    /** Go to Type Definition, Implementation and Declaration, and the call hierarchy. */
    @Volatile var supportsTypeDefinition = false
        private set
    @Volatile var supportsImplementation = false
        private set
    @Volatile var supportsDeclaration = false
        private set
    @Volatile var supportsCallHierarchy = false
        private set
    /** `textDocument/foldingRange`; without it, folding follows indentation. */
    @Volatile var supportsFoldingRange = false
        private set
    /** `textDocument/linkedEditingRange` (JSX/HTML tag pairs). */
    @Volatile var supportsLinkedEditing = false
        private set
    /** `$/progress` reports (begin, report, end) by token: indexing and loading the project. */
    @Volatile var progressListener: ((String, JsonObject) -> Unit)? = null
    /** `textDocument/selectionRange`, for Expand Selection. */
    @Volatile var supportsSelectionRange = false
        private set
    /** `workspace/symbol`, for Quick Open's `#`. */
    @Volatile var supportsWorkspaceSymbols = false
        private set
    /** Called when the server asks for fresh inlay hints (`workspace/inlayHint/refresh`). */
    @Volatile var inlayHintRefreshListener: (() -> Unit)? = null
    private val diagnosticPulls = ConcurrentHashMap<String, Boolean>()
    @Volatile var messageListener: ((String) -> Unit)? = null
    /** Receives notifications this client does not handle itself, such as Copilot status. */
    @Volatile var notificationListener: ((String, JsonObject) -> Unit)? = null
    /** Opens a `window/showDocument` URI outside the editor; returns whether it was shown. */
    @Volatile var showDocumentHandler: ((String) -> Boolean)? = null

    init {
        connection.notificationHandler = ::handleNotification
        connection.requestHandler = ::handleRequest
        connection.errorListener = { message -> messageListener?.invoke(message) }
        connection.exitListener = { code ->
            isReady = false
            isStarted = false
            openedDocuments.clear()
            messageListener?.invoke("Language server exited (${code ?: "unknown"})")
        }
    }

    fun start(
        rootPath: String,
        tool: String = "ty",
        env: List<Pair<String, String>> = emptyList(),
        options: JsonObject? = initializationOptions,
    ): CompletableFuture<Unit> {
        isStarted = true
        workspaceRoot = rootPath
        return connection.start(tool, rootPath, env = env).thenCompose {
            request("initialize", initializeParams(rootPath, options)).thenApply { initializeResult ->
                val capabilities = initializeResult.asJsonObject.getAsJsonObject("capabilities")
                val semanticProvider = capabilities?.getAsJsonObject("semanticTokensProvider")
                val legend = semanticProvider?.getAsJsonObject("legend")
                semanticTokenTypes = legend?.getAsJsonArray("tokenTypes")?.mapNotNull { it.asString }.orEmpty()
                semanticTokenModifiers =
                    legend?.getAsJsonArray("tokenModifiers")?.mapNotNull { it.asString }.orEmpty()
                pullDiagnostics = capabilities?.has("diagnosticProvider") == true
                val signatureProvider = capabilities?.get("signatureHelpProvider")?.takeIf { it.isJsonObject }?.asJsonObject
                fun characters(name: String) = signatureProvider?.getAsJsonArray(name)?.mapNotNull { it.takeIf { c -> c.isJsonPrimitive }?.asString }?.toSet().orEmpty()
                signatureTriggerCharacters = characters("triggerCharacters")
                signatureRetriggerCharacters = characters("retriggerCharacters")
                supportsSignatureHelp = signatureProvider != null
                val completionProvider = capabilities?.get("completionProvider")?.takeIf { it.isJsonObject }?.asJsonObject
                completionTriggerCharacters = completionProvider?.getAsJsonArray("triggerCharacters")
                    ?.mapNotNull { it.takeIf { c -> c.isJsonPrimitive }?.asString }?.toSet().orEmpty()
                supportsCompletionResolve = completionProvider?.get("resolveProvider")?.takeIf { it.isJsonPrimitive }?.asBoolean == true
                supportsDocumentHighlight = capabilities?.provides("documentHighlightProvider") == true
                supportsRangeFormatting = capabilities?.provides("documentRangeFormattingProvider") == true
                supportsInlayHints = capabilities?.provides("inlayHintProvider") == true
                supportsWorkspaceSymbols = capabilities?.provides("workspaceSymbolProvider") == true
                supportsSelectionRange = capabilities?.provides("selectionRangeProvider") == true
                supportsCodeLens = capabilities?.provides("codeLensProvider") == true
                supportsCodeLensResolve = capabilities?.get("codeLensProvider")?.takeIf { it.isJsonObject }?.asJsonObject
                    ?.get("resolveProvider")?.takeIf { it.isJsonPrimitive }?.asBoolean == true
                supportsReferences = capabilities?.provides("referencesProvider") == true
                supportsTypeDefinition = capabilities?.provides("typeDefinitionProvider") == true
                supportsImplementation = capabilities?.provides("implementationProvider") == true
                supportsDeclaration = capabilities?.provides("declarationProvider") == true
                supportsCallHierarchy = capabilities?.provides("callHierarchyProvider") == true
                supportsLinkedEditing = capabilities?.provides("linkedEditingRangeProvider") == true
                supportsFoldingRange = capabilities?.provides("foldingRangeProvider") == true
                notify("initialized", JsonObject())
                notify("workspace/didChangeConfiguration", JsonObject().apply {
                    add("settings", gson.toJsonTree(configuredSettings))
                })
                isReady = true
                Unit
            }
        }.whenComplete { _, error ->
            if (error != null) {
                isReady = false
                isStarted = false
                connection.stop()
            }
        }
    }

    fun didOpen(path: String, languageId: String, version: Long, text: String) {
        if (!isReady || !openedDocuments.add(path)) return
        notify("textDocument/didOpen", JsonObject().apply {
            add("textDocument", JsonObject().apply {
                addProperty("uri", fileUri(path))
                addProperty("languageId", languageId)
                addProperty("version", version)
                addProperty("text", text)
            })
        })
        pullDiagnosticsFor(path)
    }

    fun didChange(path: String, version: Long, text: String) {
        if (!isReady || path !in openedDocuments) return
        notify("textDocument/didChange", JsonObject().apply {
            add("textDocument", JsonObject().apply {
                addProperty("uri", fileUri(path))
                addProperty("version", version)
            })
            add("contentChanges", JsonArray().apply {
                add(JsonObject().apply { addProperty("text", text) })
            })
        })
        pullDiagnosticsFor(path)
    }

    /** One request per document at a time; a change while one is out asks again when it answers. */
    private fun pullDiagnosticsFor(path: String) {
        if (!pullDiagnostics) return
        // A request is out: mark it stale (false) so it asks again when it answers.
        if (diagnosticPulls.putIfAbsent(path, true) != null) {
            diagnosticPulls[path] = false
            return
        }
        val uri = fileUri(path)
        request("textDocument/diagnostic", JsonObject().apply {
            add("textDocument", JsonObject().apply { addProperty("uri", uri) })
        }).whenComplete { result, _ ->
            val items = result?.takeIf { it.isJsonObject }?.asJsonObject
                ?.takeIf { it.get("kind")?.asString == "full" }?.getAsJsonArray("items")
            if (items != null && pullDiagnostics) deliverDiagnostics(uri, items)
            // Changed again while waiting: pull once more for the latest text.
            if (diagnosticPulls.remove(path) == false && path in openedDocuments) pullDiagnosticsFor(path)
        }
    }

    fun didClose(path: String) {
        if (!openedDocuments.remove(path)) return
        notify("textDocument/didClose", JsonObject().apply {
            add("textDocument", JsonObject().apply { addProperty("uri", fileUri(path)) })
        })
    }

    fun updateSettings(settings: Map<String, JsonElement>) {
        configuredSettings = settings.mapValues { (_, value) -> value.deepCopy() }
        if (isReady) {
            notify("workspace/didChangeConfiguration", JsonObject().apply {
                add("settings", gson.toJsonTree(configuredSettings))
            })
        }
    }

    /** Parameter hints at [position]; [context] is LSP's SignatureHelpContext (trigger kind, retrigger, the hints shown). */
    fun signatureHelp(path: String, position: LspPosition, context: JsonObject): CompletableFuture<JsonElement> =
        request("textDocument/signatureHelp", positionParams(path, position).apply { add("context", context) })

    /** The symbol's other uses in the file (`textDocument/documentHighlight`). */
    fun documentHighlights(path: String, position: LspPosition): CompletableFuture<JsonElement> =
        request("textDocument/documentHighlight", positionParams(path, position))

    fun rangeFormatting(path: String, range: LspRange, tabSize: Int, insertSpaces: Boolean): CompletableFuture<List<LspTextEdit>> =
        request("textDocument/rangeFormatting", JsonObject().apply {
            add("textDocument", JsonObject().apply { addProperty("uri", fileUri(path)) })
            add("range", gson.toJsonTree(range))
            add("options", JsonObject().apply {
                addProperty("tabSize", tabSize)
                addProperty("insertSpaces", insertSpaces)
            })
        }).thenApply(::textEdits)

    /** Inferred types and parameter names in [range] (`textDocument/inlayHint`). */
    fun inlayHints(path: String, range: LspRange): CompletableFuture<JsonElement> =
        request("textDocument/inlayHint", JsonObject().apply {
            add("textDocument", JsonObject().apply { addProperty("uri", fileUri(path)) })
            add("range", gson.toJsonTree(range))
        })

    /**
     * Completions at [position]. [trigger] is the trigger character typed, if that is what asked;
     * [incomplete] re-asks for a list the server marked incomplete.
     */
    fun completion(path: String, position: LspPosition, trigger: String? = null, incomplete: Boolean = false): CompletableFuture<JsonElement> =
        request("textDocument/completion", positionParams(path, position).apply {
            add("context", JsonObject().apply {
                // LSP CompletionTriggerKind: 1 Invoked, 2 TriggerCharacter, 3 TriggerForIncompleteCompletions.
                addProperty("triggerKind", if (incomplete) 3 else if (trigger != null) 2 else 1)
                if (trigger != null) addProperty("triggerCharacter", trigger)
            })
        })

    /** The rest of an item (documentation, auto-import edits), for servers that send them on request. */
    fun resolveCompletion(item: JsonObject): CompletableFuture<JsonElement> = request("completionItem/resolve", item)

    fun selectionRanges(path: String, position: LspPosition): CompletableFuture<JsonElement> =
        request("textDocument/selectionRange", JsonObject().apply {
            add("textDocument", JsonObject().apply { addProperty("uri", fileUri(path)) })
            add("positions", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("line", position.line)
                    addProperty("character", position.character)
                })
            })
        })

    fun hover(path: String, position: LspPosition): CompletableFuture<JsonElement> =
        request("textDocument/hover", positionParams(path, position))

    fun definition(path: String, position: LspPosition): CompletableFuture<JsonElement> =
        request("textDocument/definition", positionParams(path, position))

    /** `textDocument/typeDefinition`, `implementation` or `declaration` ([method]'s last part). */
    fun locations(method: String, path: String, position: LspPosition): CompletableFuture<JsonElement> =
        request("textDocument/$method", positionParams(path, position))

    fun prepareCallHierarchy(path: String, position: LspPosition): CompletableFuture<JsonElement> =
        request("textDocument/prepareCallHierarchy", positionParams(path, position))

    /** `callHierarchy/incomingCalls` or `outgoingCalls` for an item from [prepareCallHierarchy]. */
    fun calls(item: JsonObject, incoming: Boolean): CompletableFuture<JsonElement> =
        request(if (incoming) "callHierarchy/incomingCalls" else "callHierarchy/outgoingCalls", JsonObject().apply { add("item", item) })

    fun references(path: String, position: LspPosition, includeDeclaration: Boolean = true): CompletableFuture<JsonElement> =
        request("textDocument/references", positionParams(path, position).apply {
            add("context", JsonObject().apply { addProperty("includeDeclaration", includeDeclaration) })
        })

    fun codeLens(path: String): CompletableFuture<JsonElement> =
        request("textDocument/codeLens", JsonObject().apply {
            add("textDocument", JsonObject().apply { addProperty("uri", fileUri(path)) })
        })

    fun resolveCodeLens(lens: JsonObject): CompletableFuture<JsonElement> = request("codeLens/resolve", lens)

    fun foldingRanges(path: String): CompletableFuture<JsonElement> =
        request("textDocument/foldingRange", JsonObject().apply {
            add("textDocument", JsonObject().apply { addProperty("uri", fileUri(path)) })
        })

    fun linkedEditingRanges(path: String, position: LspPosition): CompletableFuture<JsonElement> =
        request("textDocument/linkedEditingRange", positionParams(path, position))

    fun codeActions(path: String, range: LspRange, diagnostics: JsonArray): CompletableFuture<JsonElement> =
        request("textDocument/codeAction", JsonObject().apply {
            add("textDocument", JsonObject().apply { addProperty("uri", fileUri(path)) })
            add("range", gson.toJsonTree(range))
            add("context", JsonObject().apply { add("diagnostics", diagnostics) })
        })

    fun documentSymbols(path: String): CompletableFuture<JsonElement> =
        request("textDocument/documentSymbol", JsonObject().apply {
            add("textDocument", JsonObject().apply { addProperty("uri", fileUri(path)) })
        })

    fun workspaceSymbols(query: String): CompletableFuture<JsonElement> =
        request("workspace/symbol", JsonObject().apply { addProperty("query", query) })

    fun semanticTokens(path: String): CompletableFuture<List<EditorDecoration>> {
        val tokenTypes = semanticTokenTypes
        if (tokenTypes.isEmpty()) return CompletableFuture.completedFuture(emptyList())
        return request("textDocument/semanticTokens/full", JsonObject().apply {
            add("textDocument", JsonObject().apply { addProperty("uri", fileUri(path)) })
        }).thenApply { result ->
            decodeSemanticTokens(result, tokenTypes, semanticTokenModifiers)
        }
    }

    fun executeCommand(command: String, arguments: JsonArray): CompletableFuture<JsonElement> =
        request("workspace/executeCommand", JsonObject().apply {
            addProperty("command", command)
            add("arguments", arguments)
        })

    fun rename(path: String, position: LspPosition, newName: String): CompletableFuture<JsonElement> =
        request("textDocument/rename", positionParams(path, position).apply { addProperty("newName", newName) })

    fun formatting(path: String, tabSize: Int = 4, insertSpaces: Boolean = true): CompletableFuture<List<LspTextEdit>> =
        request("textDocument/formatting", JsonObject().apply {
            add("textDocument", JsonObject().apply { addProperty("uri", fileUri(path)) })
            add("options", JsonObject().apply {
                addProperty("tabSize", tabSize)
                addProperty("insertSpaces", insertSpaces)
            })
        }).thenApply(::textEdits)

    fun onDisconnected() {
        isReady = false
        isStarted = false
        openedDocuments.clear()
        connection.detach(IOException("Guest connection was lost"))
    }

    fun request(method: String, params: JsonElement): CompletableFuture<JsonElement> =
        connection.request(method, params)

    fun notify(method: String, params: JsonElement) {
        connection.notify(method, params).whenComplete { _, error ->
            if (error != null) messageListener?.invoke("LSP notification $method failed: ${error.message}")
        }
    }

    private fun handleNotification(method: String, params: JsonObject) {
        when (method) {
            "textDocument/publishDiagnostics" -> publishDiagnostics(params)
            "window/showMessage" ->
                messageListener?.invoke(params.get("message")?.asString ?: "Language server message")
            "\$/progress" -> {
                val token = params.get("token")?.takeIf { it.isJsonPrimitive }?.asString
                val value = params.get("value")?.takeIf { it.isJsonObject }?.asJsonObject
                if (token != null && value != null) progressListener?.invoke(token, value)
            }
            "window/logMessage", "\$/logTrace" -> Unit
            else -> notificationListener?.invoke(method, params)
        }
    }

    private fun handleRequest(method: String, params: JsonObject): CompletableFuture<JsonElement>? =
        when (method) {
            "workspace/configuration" -> CompletableFuture.completedFuture(configurationResult(params))
            "workspace/workspaceFolders" -> CompletableFuture.completedFuture(
                workspaceRoot?.let { root -> workspaceFolders(root) } ?: JsonNull.INSTANCE,
            )
            "window/showMessageRequest" -> {
                messageListener?.invoke(params.get("message")?.asString ?: "Language server message")
                CompletableFuture.completedFuture(JsonNull.INSTANCE)
            }
            "window/showDocument" -> {
                val uri = params.get("uri")?.asString
                val shown = uri != null && showDocumentHandler?.invoke(uri) == true
                CompletableFuture.completedFuture(JsonObject().apply { addProperty("success", shown) })
            }
            "workspace/inlayHint/refresh" -> {
                inlayHintRefreshListener?.invoke()
                CompletableFuture.completedFuture(JsonNull.INSTANCE)
            }
            "window/workDoneProgress/create", "client/registerCapability", "client/unregisterCapability" ->
                CompletableFuture.completedFuture(JsonNull.INSTANCE)
            else -> null
        }

    private fun publishDiagnostics(params: JsonObject) {
        val uri = params.get("uri")?.asString ?: return
        pullDiagnostics = false
        deliverDiagnostics(uri, params.getAsJsonArray("diagnostics") ?: JsonArray())
    }

    private fun deliverDiagnostics(uri: String, items: JsonArray) {
        val diagnostics = items.mapNotNull { item ->
            if (!item.isJsonObject) return@mapNotNull null
            val diagnostic = item.asJsonObject
            val range = diagnostic.getAsJsonObject("range") ?: return@mapNotNull null
            LanguageDiagnostic(
                uri = uri,
                message = diagnostic.get("message")?.asString ?: "",
                severity = diagnostic.get("severity")?.takeUnless { it.isJsonNull }?.asInt,
                range = range,
                source = diagnostic.get("source")?.takeUnless { it.isJsonNull }?.asString,
                code = diagnostic.get("code"),
                tags = diagnostic.getAsJsonArray("tags")?.mapNotNull { it.takeIf { tag -> tag.isJsonPrimitive }?.asInt }.orEmpty(),
                data = diagnostic.get("data")?.takeUnless { it.isJsonNull },
            )
        }
        diagnosticsListener?.invoke(uri, diagnostics)
    }

    private fun configurationResult(params: JsonObject): JsonArray {
        val sections = params.getAsJsonArray("items") ?: JsonArray()
        val result = JsonArray()
        sections.forEach { section ->
            val name = section.asJsonObject.get("section")?.asString
            result.add(name?.let(configuredSettings::get) ?: JsonObject())
        }
        return result
    }

    private fun workspaceFolders(rootPath: String) = JsonArray().apply {
        add(JsonObject().apply {
            addProperty("uri", fileUri(rootPath))
            addProperty("name", rootPath.substringAfterLast('/').ifEmpty { "workspace" })
        })
    }

    private fun initializeParams(rootPath: String, options: JsonObject?) = JsonObject().apply {
        add("processId", JsonNull.INSTANCE)
        addProperty("rootUri", fileUri(rootPath))
        add("workspaceFolders", workspaceFolders(rootPath))
        addProperty("trace", "off")
        options?.let { add("initializationOptions", it.deepCopy()) }
        add("capabilities", JsonObject().apply {
            add("window", JsonObject().apply {
                addProperty("workDoneProgress", true)
                add("showDocument", JsonObject().apply { addProperty("support", true) })
                add("showMessage", JsonObject())
            })
            add("workspace", JsonObject().apply {
                addProperty("configuration", true)
                addProperty("workspaceFolders", true)
                add("workspaceEdit", JsonObject().apply { addProperty("documentChanges", true) })
                add("inlayHint", JsonObject().apply { addProperty("refreshSupport", true) })
                add("symbol", JsonObject().apply { addProperty("dynamicRegistration", false) })
            })
            add("textDocument", JsonObject().apply {
                add("publishDiagnostics", JsonObject().apply {
                    addProperty("relatedInformation", true)
                    add("tagSupport", JsonObject().apply { add("valueSet", JsonArray().apply { add(1); add(2) }) })
                })
                add("synchronization", JsonObject().apply {
                    addProperty("dynamicRegistration", false)
                    addProperty("willSave", false)
                    addProperty("didSave", true)
                })
                add("completion", JsonObject().apply {
                    addProperty("dynamicRegistration", false)
                    addProperty("contextSupport", true)
                    add("completionItem", JsonObject().apply {
                        // Tab stops and placeholders (NativeCodeEditor.insertSnippet).
                        addProperty("snippetSupport", true)
                        addProperty("labelDetailsSupport", true)
                        addProperty("insertReplaceSupport", true)
                        addProperty("preselectSupport", true)
                        add("documentationFormat", JsonArray().apply { add("plaintext"); add("markdown") })
                        add("resolveSupport", JsonObject().apply {
                            add("properties", JsonArray().apply { add("documentation"); add("detail"); add("additionalTextEdits") })
                        })
                    })
                    add("completionItemKind", JsonObject().apply { add("valueSet", JsonArray().apply { (1..25).forEach(::add) }) })
                    add("completionList", JsonObject().apply {
                        add("itemDefaults", JsonArray().apply { add("editRange"); add("insertTextFormat"); add("data") })
                    })
                })
                add("hover", JsonObject().apply {
                    addProperty("dynamicRegistration", false)
                    // The hover card draws Markdown; without this ty sends plain text.
                    add("contentFormat", JsonArray().apply {
                        add("markdown")
                        add("plaintext")
                    })
                })
                add("signatureHelp", JsonObject().apply {
                    addProperty("dynamicRegistration", false)
                    addProperty("contextSupport", true)
                    add("signatureInformation", JsonObject().apply {
                        add("documentationFormat", JsonArray().apply { add("plaintext"); add("markdown") })
                        add("parameterInformation", JsonObject().apply { addProperty("labelOffsetSupport", true) })
                        addProperty("activeParameterSupport", true)
                    })
                })
                add("documentHighlight", JsonObject().apply { addProperty("dynamicRegistration", false) })
                add("selectionRange", JsonObject().apply { addProperty("dynamicRegistration", false) })
                add("codeLens", JsonObject().apply { addProperty("dynamicRegistration", false) })
                add("linkedEditingRange", JsonObject().apply { addProperty("dynamicRegistration", false) })
                add("foldingRange", JsonObject().apply {
                    addProperty("dynamicRegistration", false)
                    addProperty("lineFoldingOnly", true)
                })
                add("rangeFormatting", JsonObject().apply { addProperty("dynamicRegistration", false) })
                add("inlayHint", JsonObject().apply { addProperty("dynamicRegistration", false) })
                add("definition", JsonObject().apply { addProperty("dynamicRegistration", false); addProperty("linkSupport", true) })
                add("typeDefinition", JsonObject().apply { addProperty("dynamicRegistration", false); addProperty("linkSupport", true) })
                add("implementation", JsonObject().apply { addProperty("dynamicRegistration", false); addProperty("linkSupport", true) })
                add("declaration", JsonObject().apply { addProperty("dynamicRegistration", false); addProperty("linkSupport", true) })
                add("callHierarchy", JsonObject().apply { addProperty("dynamicRegistration", false) })
                add("references", JsonObject().apply { addProperty("dynamicRegistration", false) })
                add("rename", JsonObject().apply { addProperty("dynamicRegistration", false) })
                add("formatting", JsonObject().apply { addProperty("dynamicRegistration", false) })
                add("codeAction", JsonObject().apply {
                    addProperty("dynamicRegistration", false)
                    add("codeActionLiteralSupport", JsonObject().apply {
                        add("codeActionKind", JsonObject().apply {
                            add("valueSet", JsonArray().apply {
                                add("quickfix")
                                add("refactor")
                                add("source")
                            })
                        })
                    })
                })
                add("semanticTokens", JsonObject().apply {
                    addProperty("dynamicRegistration", false)
                    add("requests", JsonObject().apply {
                        addProperty("full", true)
                        addProperty("range", true)
                    })
                    add("tokenTypes", JsonArray().apply {
                        listOf("namespace", "type", "class", "enum", "interface", "struct", "typeParameter",
                            "parameter", "variable", "property", "enumMember", "event", "function", "method",
                            "macro", "keyword", "modifier", "comment", "string", "number", "regexp", "operator",
                        ).forEach(::add)
                    })
                    add("tokenModifiers", JsonArray().apply {
                        listOf("declaration", "definition", "readonly", "static", "deprecated", "abstract",
                            "async", "modification", "documentation", "defaultLibrary",
                        ).forEach(::add)
                    })
                    add("formats", JsonArray().apply { add("relative") })
                })
                add("documentSymbol", JsonObject().apply { addProperty("dynamicRegistration", false) })
            })
        })
    }

    private fun positionParams(path: String, position: LspPosition) = JsonObject().apply {
        add("textDocument", JsonObject().apply { addProperty("uri", fileUri(path)) })
        add("position", JsonObject().apply {
            addProperty("line", position.line)
            addProperty("character", position.character)
        })
    }

    private fun textEdits(value: JsonElement): List<LspTextEdit> {
        if (!value.isJsonArray) return emptyList()
        return value.asJsonArray.mapNotNull { element ->
            if (!element.isJsonObject) return@mapNotNull null
            val edit = element.asJsonObject
            val range = edit.getAsJsonObject("range") ?: return@mapNotNull null
            val start = range.getAsJsonObject("start") ?: return@mapNotNull null
            val end = range.getAsJsonObject("end") ?: return@mapNotNull null
            val newText = edit.get("newText")?.takeUnless { it.isJsonNull }?.asString ?: return@mapNotNull null
            LspTextEdit(
                LspRange(
                    LspPosition(start.get("line").asInt, start.get("character").asInt),
                    LspPosition(end.get("line").asInt, end.get("character").asInt),
                ),
                newText,
            )
        }
    }

    private fun fileUri(path: String): String = lspDocumentUri(path, workspaceRoot)

    override fun close() {
        stop()
    }

    /** Shuts the server down; completes once it is gone, so it can be started again. */
    fun stop(): CompletableFuture<Unit> {
        if (connection.bridgeId == null) {
            isStarted = false
            return CompletableFuture.completedFuture(Unit)
        }
        isReady = false
        openedDocuments.clear()
        val stopped = CompletableFuture<Unit>()
        request("shutdown", JsonNull.INSTANCE).whenComplete { _, _ ->
            connection.notify("exit", JsonNull.INSTANCE).whenComplete { _, _ ->
                connection.stop()
                isStarted = false
                stopped.complete(Unit)
            }
        }
        return stopped
    }

}

/** A capability given as `true` or an options object (`false`, null or absent means no). */
private fun JsonObject.provides(name: String): Boolean {
    val value = get(name) ?: return false
    return when {
        value.isJsonObject -> true
        value.isJsonPrimitive && value.asJsonPrimitive.isBoolean -> value.asBoolean
        else -> false
    }
}
