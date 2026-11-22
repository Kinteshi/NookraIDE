package io.github.kinteshi.quirecode.debug

import io.github.kinteshi.quirecode.transport.GuestVfsClient
import io.github.kinteshi.quirecode.transport.RpcEvent
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** The adapter answered a request with `success: false`. */
class DapException(val command: String, message: String) : IOException(message)

/**
 * A Debug Adapter Protocol peer behind the daemon's `Dap` bridge (Content-Length framing is done
 * by the daemon). DAP isn't JSON-RPC: requests carry `seq`, responses `request_seq`, and the
 * adapter also sends events and, rarely, reverse requests.
 */
class DapClient(private val transport: GuestVfsClient, val tool: String = DEBUGPY_TOOL) {
    private val gson = Gson()
    private val seq = AtomicInteger(0)
    private val pending = ConcurrentHashMap<Int, Pair<String, CompletableFuture<JsonObject>>>()
    @Volatile var bridgeId: UUID? = null
        private set
    @Volatile var eventListener: ((String, JsonObject) -> Unit)? = null
    @Volatile var exitListener: ((String) -> Unit)? = null

    val isStarted: Boolean get() = bridgeId != null

    /**
     * Reverse requests the app takes (js-debug's `startDebugging`, offered in `initialize`): true
     * answers success. Others, and any it declines, are answered as unsupported.
     */
    @Volatile var reverseRequestHandler: ((String, JsonObject) -> Boolean)? = null

    fun start(cwd: String, args: List<String> = emptyList()): CompletableFuture<Unit> =
        transport.startRpc(tool, cwd, "Dap", args).thenApply { id ->
            bridgeId = id
            transport.addRpcEventListener(id, ::onRpcEvent)
        }

    /** Sends a request; completes with the response body, or fails with [DapException]. */
    fun request(command: String, arguments: JsonObject = JsonObject(), timeoutSeconds: Long? = 30): CompletableFuture<JsonObject> {
        val id = bridgeId ?: return CompletableFuture.failedFuture(IOException("The debugger isn't running"))
        val requestSeq = seq.incrementAndGet()
        val result = CompletableFuture<JsonObject>()
        pending[requestSeq] = command to result
        val message = JsonObject().apply {
            addProperty("seq", requestSeq)
            addProperty("type", "request")
            addProperty("command", command)
            add("arguments", arguments)
        }
        transport.sendRpcMessage(id, gson.toJson(message)).whenComplete { _, error ->
            if (error != null) pending.remove(requestSeq)?.second?.completeExceptionally(error)
        }
        if (timeoutSeconds != null) {
            CompletableFuture.delayedExecutor(timeoutSeconds, TimeUnit.SECONDS).execute {
                pending.remove(requestSeq)?.second?.completeExceptionally(IOException("$command timed out"))
            }
        }
        return result
    }

    /** Stops the adapter process (it would otherwise wait for stdin after `disconnect`). */
    fun stop() {
        val id = bridgeId ?: return
        detach(IOException("Debug session ended"))
        transport.stopRpc(id)
    }

    fun detach(error: Throwable) {
        bridgeId?.let(transport::removeRpcEventListener)
        bridgeId = null
        pending.values.forEach { it.second.completeExceptionally(error) }
        pending.clear()
    }

    private fun onRpcEvent(event: RpcEvent) {
        if (event.bridgeId != bridgeId) return
        when (event) {
            is RpcEvent.Message -> handle(event.body)
            is RpcEvent.Error -> exitListener?.invoke(event.message)
            is RpcEvent.Exited -> {
                detach(IOException("The debug adapter exited"))
                exitListener?.invoke("The debug adapter exited (${event.code ?: "unknown"})")
            }
            is RpcEvent.Started -> Unit
        }
    }

    private fun handle(body: String) {
        val message = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return
        val payload = message.get("body")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        when (message.get("type")?.asString) {
            "response" -> {
                val (command, result) = pending.remove(message.get("request_seq")?.asInt ?: return) ?: return
                if (message.get("success")?.asBoolean == true) {
                    result.complete(payload)
                } else {
                    val detail = payload.getAsJsonObject("error")?.get("format")?.asString
                    result.completeExceptionally(DapException(command, detail ?: message.get("message")?.asString ?: "$command failed"))
                }
            }
            "event" -> eventListener?.invoke(message.get("event")?.asString ?: return, payload)
            "request" -> {
                val command = message.get("command")?.asString.orEmpty()
                val arguments = message.get("arguments")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
                answer(message, reverseRequestHandler?.invoke(command, arguments) == true)
            }
        }
    }

    /** Answers a reverse request; runInTerminal isn't offered in `initialize`, so refused ones are refused politely. */
    private fun answer(request: JsonObject, success: Boolean) {
        val id = bridgeId ?: return
        val response = JsonObject().apply {
            addProperty("seq", seq.incrementAndGet())
            addProperty("type", "response")
            addProperty("request_seq", request.get("seq")?.asInt ?: 0)
            addProperty("success", success)
            addProperty("command", request.get("command")?.asString ?: "")
            if (success) add("body", JsonObject()) else addProperty("message", "Not supported by Nookra IDE")
        }
        transport.sendRpcMessage(id, gson.toJson(response))
    }

    companion object {
        const val DEBUGPY_TOOL = "debugpy"
        /** VS Code's JavaScript debugger (Node.js), as its standalone DAP server. */
        const val JS_DEBUG_TOOL = "js-debug"
        /** CodeLLDB, for C, C++ and Rust. */
        const val CODELLDB_TOOL = "codelldb"
    }
}
