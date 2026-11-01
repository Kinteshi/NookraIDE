package com.avfstudio.nativeide.notebook

import com.avfstudio.nativeide.language.JsonRpcConnection
import com.avfstudio.nativeide.transport.GuestVfsClient
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import java.util.concurrent.CompletableFuture

const val JUPYTER_BRIDGE_TOOL = "jupyter"

data class KernelSpec(val name: String, val displayName: String, val language: String, val python: String? = null)
data class ExecuteReply(val status: String, val executionCount: Int?)

/** A Python interpreter the bridge found that could run notebooks once ipykernel is installed. */
data class InstallableKernel(val python: String, val label: String)
data class KernelDiscovery(val specs: List<KernelSpec>, val installable: List<InstallableKernel>)

/** The kernelspec name the bridge generates for an environment labelled [label] (see `avf-jupyter-bridge.py`). */
fun generatedKernelName(label: String): String =
    "avf-" + label.lowercase().map { if (it.isLetterOrDigit()) it else '-' }.joinToString("")

/**
 * Client for the guest's `avf-jupyter-bridge` (guest/tools/avf-jupyter-bridge.py): one bridge
 * process and one kernel per open notebook, spoken to as newline-delimited JSON-RPC.
 */
data class KernelCompletions(val matches: List<String>, val start: Int, val end: Int)

class KernelClient(transport: GuestVfsClient) {
    private val connection = JsonRpcConnection(transport, "Kernel")
    /** Cell id, output, and the display id when the output can be updated later. */
    @Volatile var outputListener: ((String, JsonObject, String?) -> Unit)? = null
    /** Display id and the output that replaces what was shown with it. */
    @Volatile var updateDisplayListener: ((String, JsonObject) -> Unit)? = null
    @Volatile var clearListener: ((String, Boolean) -> Unit)? = null
    @Volatile var busyListener: ((Boolean) -> Unit)? = null
    @Volatile var exitListener: ((String) -> Unit)? = null
    val isStarted: Boolean get() = connection.bridgeId != null

    init {
        connection.notificationHandler = { method, params ->
            when (method) {
                "output" -> params.getAsJsonObject("output")?.let { output ->
                    outputListener?.invoke(
                        params.get("cell_id")?.asString.orEmpty(),
                        output,
                        params.get("display_id")?.takeIf { it.isJsonPrimitive }?.asString,
                    )
                }
                "update_display" -> {
                    val displayId = params.get("display_id")?.takeIf { it.isJsonPrimitive }?.asString
                    val output = params.getAsJsonObject("output")
                    if (displayId != null && output != null) updateDisplayListener?.invoke(displayId, output)
                }
                "clear_output" -> clearListener?.invoke(
                    params.get("cell_id")?.asString.orEmpty(),
                    params.get("wait")?.asBoolean ?: false,
                )
                "status" -> busyListener?.invoke(params.get("state")?.asString == "busy")
            }
        }
        connection.exitListener = { code -> exitListener?.invoke("Kernel bridge exited (${code ?: "unknown"})") }
        connection.errorListener = { message -> exitListener?.invoke(message) }
    }

    fun launch(cwd: String): CompletableFuture<Unit> = connection.start(JUPYTER_BRIDGE_TOOL, cwd)

    /** The first call can take a while: uv fetches jupyter_client into its cache once. */
    fun kernelSpecs(): CompletableFuture<KernelDiscovery> =
        connection.request("kernelspecs", JsonObject(), timeoutSeconds = 300).thenApply { result ->
            val body = result.asJsonObject
            KernelDiscovery(
                specs = body.getAsJsonArray("kernelspecs")?.map { element ->
                    val spec = element.asJsonObject
                    KernelSpec(
                        spec.get("name").asString,
                        spec.get("display_name")?.asString ?: spec.get("name").asString,
                        spec.get("language")?.asString.orEmpty(),
                        spec.get("python")?.takeUnless { it.isJsonNull }?.asString,
                    )
                }.orEmpty(),
                installable = body.getAsJsonArray("installable")?.map { element ->
                    val candidate = element.asJsonObject
                    InstallableKernel(candidate.get("python").asString, candidate.get("label")?.asString ?: "python")
                }.orEmpty(),
            )
        }

    /** [plotlyDisplayIds]: let `display(fig, display_id=True)` update Plotly figures in place. */
    fun startKernel(name: String, cwd: String, plotlyDisplayIds: Boolean = false): CompletableFuture<Unit> =
        connection.request("start", JsonObject().apply {
            addProperty("name", name)
            addProperty("cwd", cwd)
            addProperty("plotly_display_ids", plotlyDisplayIds)
        }, timeoutSeconds = 300).thenApply { }

    fun execute(cellId: String, code: String): CompletableFuture<ExecuteReply> =
        connection.request("execute", JsonObject().apply {
            addProperty("cell_id", cellId)
            addProperty("code", code)
        }, timeoutSeconds = null).thenApply { result ->
            val reply = result.asJsonObject
            ExecuteReply(
                reply.get("status")?.asString ?: "ok",
                reply.get("execution_count")?.takeIf { it.isJsonPrimitive }?.asInt,
            )
        }

    /** Kernel completions at [cursor]; [KernelCompletions.start]..[KernelCompletions.end] is the text they replace. */
    fun complete(code: String, cursor: Int): CompletableFuture<KernelCompletions> =
        connection.request("complete", JsonObject().apply {
            addProperty("code", code)
            addProperty("cursor_pos", cursor)
        }).thenApply { result ->
            val reply = result.asJsonObject
            fun position(name: String) = reply.get(name)?.takeIf { it.isJsonPrimitive }?.asInt
            KernelCompletions(
                matches = reply.getAsJsonArray("matches")?.map { it.asString }.orEmpty(),
                start = position("cursor_start") ?: cursor,
                end = position("cursor_end") ?: cursor,
            )
        }

    /** The kernel's variables (Python kernels; others answer `unsupported`), after the cells queued before. */
    fun variables(): CompletableFuture<KernelVariables> =
        connection.request("variables", JsonObject(), timeoutSeconds = null).thenApply(::parseKernelVariables)

    /**
     * [limit] rows from [offset] of the DataFrame, Series or array called [name], for the data
     * viewer: those matching [query] (a pandas query or text), sorted by the shown [sort] column
     * (0 is the index). Older bridges ignore all but [limit].
     */
    fun table(name: String, limit: Int, offset: Int = 0, query: String = "", sort: Int? = null, ascending: Boolean = true): CompletableFuture<VariableTable?> =
        connection.request("table", JsonObject().apply {
            addProperty("name", name)
            addProperty("limit", limit)
            addProperty("offset", offset)
            addProperty("query", query)
            sort?.let { addProperty("sort", it) }
            addProperty("ascending", ascending)
        }, timeoutSeconds = null).thenApply(::parseVariableTable)

    fun interrupt(): CompletableFuture<Unit> = connection.request("interrupt", JsonNull.INSTANCE).thenApply { }

    fun restart(): CompletableFuture<Unit> =
        connection.request("restart", JsonNull.INSTANCE, timeoutSeconds = 180).thenApply { }

    /** Shuts the kernel down cleanly, then ends the bridge process. */
    fun stop() {
        if (!isStarted) return
        connection.request("shutdown", JsonNull.INSTANCE, timeoutSeconds = 20).whenComplete { _, _ -> connection.stop() }
    }

    fun onDisconnected() = connection.detach(java.io.IOException("Guest connection was lost"))
}
