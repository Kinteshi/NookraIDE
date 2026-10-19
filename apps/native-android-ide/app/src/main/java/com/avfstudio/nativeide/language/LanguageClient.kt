package com.avfstudio.nativeide.language

import com.avfstudio.nativeide.transport.GuestVfsClient
import com.avfstudio.nativeide.transport.RpcEvent
import com.avfstudio.nativeide.editor.LspPosition
import com.avfstudio.nativeide.editor.LspRange
import com.avfstudio.nativeide.editor.LspTextEdit
import com.avfstudio.nativeide.editor.EditorDecoration
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.Closeable
import java.io.IOException
import java.net.URI
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit

data class LanguageDiagnostic(
    val uri: String,
    val message: String,
    val severity: Int?,
    val range: JsonObject,
    val source: String?,
    val code: JsonElement?,
)

class LanguageClient(
    private val transport: GuestVfsClient,
    private val settings: Map<String, JsonElement> = emptyMap(),
    private val gson: Gson = Gson(),
) : Closeable {
    @Volatile private var configuredSettings: Map<String, JsonElement> =
        settings.mapValues { (_, value) -> value.deepCopy() }
    private val requestIds = AtomicLong(0)
    private val pendingRequests = ConcurrentHashMap<Long, CompletableFuture<JsonElement>>()
    private val openedDocuments = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var semanticTokenTypes: List<String> = emptyList()
    @Volatile private var semanticTokenModifiers: List<String> = emptyList()
    @Volatile private var bridgeId: UUID? = null
    @Volatile var isReady: Boolean = false
        private set
    @Volatile var diagnosticsListener: ((String, List<LanguageDiagnostic>) -> Unit)? = null
    @Volatile var messageListener: ((String) -> Unit)? = null

    fun start(rootPath: String, tool: String = "basedpyright"): CompletableFuture<Unit> =
        transport.startLanguageServer(tool, rootPath).thenCompose { id ->
            bridgeId = id
            transport.addRpcEventListener(id, ::onRpcEvent)
            request("initialize", initializeParams(rootPath)).thenApply { initializeResult ->
                val capabilities = initializeResult.asJsonObject.getAsJsonObject("capabilities")
                val semanticProvider = capabilities?.getAsJsonObject("semanticTokensProvider")
                val legend = semanticProvider?.getAsJsonObject("legend")
                semanticTokenTypes = legend?.getAsJsonArray("tokenTypes")?.mapNotNull { it.asString }.orEmpty()
                semanticTokenModifiers =
                    legend?.getAsJsonArray("tokenModifiers")?.mapNotNull { it.asString }.orEmpty()
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
                bridgeId?.let { id ->
                    transport.removeRpcEventListener(id)
                    transport.stopRpc(id)
                }
                bridgeId = null
                failRequests(error)
            }
        }

    fun didOpen(path: String, languageId: String, version: Long, text: String) {
        if (!isReady || path.startsWith("scratch:") || !openedDocuments.add(path)) return
        notify("textDocument/didOpen", JsonObject().apply {
            add("textDocument", JsonObject().apply {
                addProperty("uri", fileUri(path))
                addProperty("languageId", languageId)
                addProperty("version", version)
                addProperty("text", text)
            })
        })
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

    fun completion(path: String, position: LspPosition): CompletableFuture<JsonElement> =
        request("textDocument/completion", positionParams(path, position))

    fun hover(path: String, position: LspPosition): CompletableFuture<JsonElement> =
        request("textDocument/hover", positionParams(path, position))

    fun definition(path: String, position: LspPosition): CompletableFuture<JsonElement> =
        request("textDocument/definition", positionParams(path, position))

    fun references(path: String, position: LspPosition): CompletableFuture<JsonElement> =
        request("textDocument/references", positionParams(path, position).apply {
            add("context", JsonObject().apply { addProperty("includeDeclaration", true) })
        })

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

    fun formatting(path: String): CompletableFuture<List<LspTextEdit>> =
        request("textDocument/formatting", JsonObject().apply {
            add("textDocument", JsonObject().apply { addProperty("uri", fileUri(path)) })
            add("options", JsonObject().apply {
                addProperty("tabSize", 4)
                addProperty("insertSpaces", true)
            })
        }).thenApply(::textEdits)

    fun onDisconnected() {
        isReady = false
        bridgeId?.let(transport::removeRpcEventListener)
        bridgeId = null
        openedDocuments.clear()
        failRequests(IOException("Guest connection was lost"))
    }

    fun request(method: String, params: JsonElement): CompletableFuture<JsonElement> {
        val id = requestIds.incrementAndGet()
        val result = CompletableFuture<JsonElement>()
        pendingRequests[id] = result
        val message = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("id", id)
            addProperty("method", method)
            add("params", params)
        }
        send(message).whenComplete { _, error ->
            if (error != null) pendingRequests.remove(id)?.completeExceptionally(error)
        }
        CompletableFuture.delayedExecutor(30, TimeUnit.SECONDS).execute {
            pendingRequests.remove(id)?.completeExceptionally(IOException("LSP request $method timed out"))
        }
        return result
    }

    fun notify(method: String, params: JsonElement) {
        val message = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("method", method)
            add("params", params)
        }
        send(message).whenComplete { _, error ->
            if (error != null) messageListener?.invoke("LSP notification $method failed: ${error.message}")
        }
    }

    private fun send(message: JsonObject): CompletableFuture<Unit> {
        val id = bridgeId ?: return CompletableFuture.failedFuture(IOException("Language server is not started"))
        return transport.sendRpcMessage(id, gson.toJson(message))
    }

    private fun onRpcEvent(event: RpcEvent) {
        if (event.bridgeId != bridgeId) return
        when (event) {
            is RpcEvent.Message -> handleMessage(event.body)
            is RpcEvent.Error -> failRequests(IOException(event.message))
            is RpcEvent.Exited -> {
                isReady = false
                transport.removeRpcEventListener(event.bridgeId)
                bridgeId = null
                openedDocuments.clear()
                failRequests(IOException("Language server exited with code ${event.code ?: "unknown"}"))
                messageListener?.invoke("Language server exited (${event.code ?: "unknown"})")
            }
            is RpcEvent.Started -> Unit
        }
    }

    private fun handleMessage(body: String) {
        val message = try {
            JsonParser.parseString(body).asJsonObject
        } catch (error: Exception) {
            messageListener?.invoke("Language server sent invalid JSON-RPC: ${error.message}")
            return
        }
        if (message.has("method")) {
            val method = message.get("method").asString
            val params = message.getAsJsonObject("params") ?: JsonObject()
            when (method) {
                "textDocument/publishDiagnostics" -> publishDiagnostics(params)
                "workspace/configuration" -> respondConfiguration(message.get("id"), params)
                "window/showMessage", "window/logMessage" ->
                    messageListener?.invoke(params.get("message")?.asString ?: "Language server message")
            }
            return
        }
        val id = message.get("id")?.asLong ?: return
        val pending = pendingRequests.remove(id) ?: return
        if (message.has("error")) {
            pending.completeExceptionally(IOException("LSP request failed: ${message.get("error")}"))
        } else {
            pending.complete(message.get("result") ?: com.google.gson.JsonNull.INSTANCE)
        }
    }

    private fun publishDiagnostics(params: JsonObject) {
        val uri = params.get("uri")?.asString ?: return
        val items = params.getAsJsonArray("diagnostics") ?: JsonArray()
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
            )
        }
        diagnosticsListener?.invoke(uri, diagnostics)
    }

    private fun respondConfiguration(id: JsonElement?, params: JsonObject) {
        if (id == null) return
        val sections = params.getAsJsonArray("items") ?: JsonArray()
        val result = JsonArray()
        sections.forEach { section ->
            val name = section.asJsonObject.get("section")?.asString
            result.add(name?.let(configuredSettings::get) ?: JsonObject())
        }
        val response = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            add("id", id)
            add("result", result)
        }
        send(response).whenComplete { _, error ->
            if (error != null) messageListener?.invoke("workspace/configuration failed: ${error.message}")
        }
    }

    private fun failRequests(error: Throwable) {
        pendingRequests.values.forEach { it.completeExceptionally(error) }
        pendingRequests.clear()
    }

    private fun initializeParams(rootPath: String) = JsonObject().apply {
        add("processId", com.google.gson.JsonNull.INSTANCE)
        addProperty("rootUri", fileUri(rootPath))
        add("workspaceFolders", JsonArray().apply {
            add(JsonObject().apply {
                addProperty("uri", fileUri(rootPath))
                addProperty("name", rootPath.substringAfterLast('/').ifEmpty { "workspace" })
            })
        })
        addProperty("trace", "off")
        add("capabilities", JsonObject().apply {
            add("workspace", JsonObject().apply {
                addProperty("configuration", true)
                addProperty("workspaceFolders", true)
                add("workspaceEdit", JsonObject().apply { addProperty("documentChanges", true) })
            })
            add("textDocument", JsonObject().apply {
                add("publishDiagnostics", JsonObject().apply { addProperty("relatedInformation", true) })
                add("synchronization", JsonObject().apply {
                    addProperty("dynamicRegistration", false)
                    addProperty("willSave", false)
                    addProperty("didSave", true)
                })
                add("completion", JsonObject().apply {
                    addProperty("dynamicRegistration", false)
                    add("completionItem", JsonObject().apply { addProperty("snippetSupport", false) })
                })
                add("hover", JsonObject().apply { addProperty("dynamicRegistration", false) })
                add("definition", JsonObject().apply { addProperty("dynamicRegistration", false) })
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

    private fun fileUri(path: String): String = URI("file", null, path, null).toASCIIString()

    override fun close() {
        val id = bridgeId ?: return
        isReady = false
        request("shutdown", JsonObject()).whenComplete { _, _ ->
            val exit = JsonObject().apply {
                addProperty("jsonrpc", "2.0")
                addProperty("method", "exit")
                add("params", JsonObject())
                }
            transport.sendRpcMessage(id, gson.toJson(exit)).whenComplete { _, _ ->
                transport.stopRpc(id)
                transport.removeRpcEventListener(id)
                bridgeId = null
            }
        }
    }

}
