package io.github.kinteshi.quirecode.language

import io.github.kinteshi.quirecode.transport.GuestVfsClient
import io.github.kinteshi.quirecode.transport.RpcEvent
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** JSON-RPC error returned by the peer; [code] follows the JSON-RPC 2.0 numbering. */
class JsonRpcException(val code: Int, message: String, val data: JsonElement? = null) : IOException(message)

/**
 * One JSON-RPC 2.0 peer behind a guest RPC bridge. Language servers (`Lsp`) and agents (`Acp`)
 * share this: the daemon handles framing, so each body is a single JSON message.
 */
class JsonRpcConnection(
    private val transport: GuestVfsClient,
    private val kind: String = "Lsp",
    // Nulls are kept: JSON-RPC replies need `"result": null`, and LSP's initialize needs
    // `"processId": null` (TypeScript 7 rejects it missing; Gson drops nulls by default).
    private val gson: Gson = GsonBuilder().serializeNulls().create(),
) {
    private val requestIds = AtomicLong(0)
    private val pendingRequests = ConcurrentHashMap<Long, CompletableFuture<JsonElement>>()
    @Volatile var bridgeId: UUID? = null
        private set

    /** Handles requests the peer sends; return null to answer "method not found". */
    @Volatile var requestHandler: ((String, JsonObject) -> CompletableFuture<JsonElement>?)? = null
    @Volatile var notificationHandler: ((String, JsonObject) -> Unit)? = null
    @Volatile var exitListener: ((Int?) -> Unit)? = null
    @Volatile var errorListener: ((String) -> Unit)? = null

    fun start(
        tool: String,
        cwd: String,
        args: List<String> = emptyList(),
        env: List<Pair<String, String>> = emptyList(),
    ): CompletableFuture<Unit> =
        transport.startRpc(tool, cwd, kind, args, env).thenApply { id ->
            bridgeId = id
            transport.addRpcEventListener(id, ::onRpcEvent)
        }

    /** Sends a request; a null [timeoutSeconds] waits until the peer answers or exits. */
    fun request(method: String, params: JsonElement, timeoutSeconds: Long? = 30): CompletableFuture<JsonElement> {
        val id = requestIds.incrementAndGet()
        val result = CompletableFuture<JsonElement>()
        pendingRequests[id] = result
        val message = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("id", id)
            addProperty("method", method)
            // LSP `shutdown` and `exit` take no params; servers such as ty reject `{}`.
            if (!params.isJsonNull) add("params", params)
        }
        send(message).whenComplete { _, error ->
            if (error != null) pendingRequests.remove(id)?.completeExceptionally(error)
        }
        if (timeoutSeconds != null) {
            CompletableFuture.delayedExecutor(timeoutSeconds, TimeUnit.SECONDS).execute {
                pendingRequests.remove(id)?.completeExceptionally(IOException("$method timed out"))
            }
        }
        return result
    }

    fun notify(method: String, params: JsonElement): CompletableFuture<Unit> =
        send(JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("method", method)
            if (!params.isJsonNull) add("params", params)
        })

    /** Stops the guest process and fails every request that is still waiting. */
    fun stop() {
        val id = bridgeId ?: return
        detach(IOException("Connection stopped"))
        transport.stopRpc(id)
    }

    /** Forgets the bridge without contacting the guest, for example after a lost connection. */
    fun detach(error: Throwable) {
        bridgeId?.let(transport::removeRpcEventListener)
        bridgeId = null
        failRequests(error)
    }

    private fun send(message: JsonObject): CompletableFuture<Unit> {
        val id = bridgeId ?: return CompletableFuture.failedFuture(IOException("Guest tool is not started"))
        return transport.sendRpcMessage(id, gson.toJson(message))
    }

    private fun onRpcEvent(event: RpcEvent) {
        if (event.bridgeId != bridgeId) return
        when (event) {
            is RpcEvent.Message -> handleMessage(event.body)
            is RpcEvent.Error -> {
                failRequests(IOException(event.message))
                errorListener?.invoke(event.message)
            }
            is RpcEvent.Exited -> {
                detach(IOException("Guest tool exited with code ${event.code ?: "unknown"}"))
                exitListener?.invoke(event.code)
            }
            is RpcEvent.Started -> Unit
        }
    }

    private fun handleMessage(body: String) {
        val message = try {
            JsonParser.parseString(body).asJsonObject
        } catch (error: Exception) {
            errorListener?.invoke("Guest tool sent invalid JSON-RPC: ${error.message}")
            return
        }
        val method = message.get("method")?.takeIf { it.isJsonPrimitive }?.asString
        val id = message.get("id")?.takeUnless { it.isJsonNull }
        val params = message.get("params")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        when {
            method != null && id != null -> answer(id, method, params)
            method != null -> notificationHandler?.invoke(method, params)
            id != null -> {
                val pending = id.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                    ?.let { pendingRequests.remove(it.asLong) } ?: return
                val error = message.get("error")?.takeIf { it.isJsonObject }?.asJsonObject
                if (error != null) {
                    pending.completeExceptionally(
                        JsonRpcException(
                            error.get("code")?.asInt ?: -32603,
                            error.get("message")?.asString ?: "Request failed",
                            error.get("data"),
                        ),
                    )
                } else {
                    pending.complete(message.get("result") ?: JsonNull.INSTANCE)
                }
            }
        }
    }

    private fun answer(id: JsonElement, method: String, params: JsonObject) {
        val handled = try {
            requestHandler?.invoke(method, params)
        } catch (error: Exception) {
            CompletableFuture.failedFuture(error)
        }
        if (handled == null) {
            respondError(id, -32601, "Method not found: $method")
            return
        }
        handled.whenComplete { result, error ->
            if (error != null) {
                val cause = (error as? java.util.concurrent.CompletionException)?.cause ?: error
                respondError(id, (cause as? JsonRpcException)?.code ?: -32603, cause.message ?: "Request failed")
            } else {
                send(JsonObject().apply {
                    addProperty("jsonrpc", "2.0")
                    add("id", id)
                    add("result", result ?: JsonNull.INSTANCE)
                })
            }
        }
    }

    private fun respondError(id: JsonElement, code: Int, message: String) {
        send(JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            add("id", id)
            add("error", JsonObject().apply {
                addProperty("code", code)
                addProperty("message", message)
            })
        })
    }

    private fun failRequests(error: Throwable) {
        pendingRequests.values.forEach { it.completeExceptionally(error) }
        pendingRequests.clear()
    }
}
