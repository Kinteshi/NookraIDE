package com.avfstudio.nativeide.ai

import com.avfstudio.nativeide.language.JsonRpcConnection
import com.avfstudio.nativeide.transport.GuestVfsClient
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.util.concurrent.CompletableFuture

const val COPILOT_AGENT_TOOL = "copilot"
private const val ACP_PROTOCOL_VERSION = 1

data class AcpAuthMethod(val id: String, val name: String, val description: String?)
data class AcpPermissionOption(val id: String, val name: String, val kind: String)

sealed interface AcpEvent {
    data class MessageChunk(val text: String) : AcpEvent
    data class ThoughtChunk(val text: String) : AcpEvent
    data class ToolCall(val id: String, val title: String?, val kind: String?, val status: String?) : AcpEvent
    data class Plan(val entries: List<String>) : AcpEvent
    data class Commands(val names: List<String>) : AcpEvent

    /** The agent waits until [respond] is called with an option id, or null to cancel. */
    data class PermissionRequest(
        val title: String,
        val options: List<AcpPermissionOption>,
        val respond: (String?) -> Unit,
    ) : AcpEvent
}

/**
 * Client side of the Agent Client Protocol (agentclientprotocol.com) for an agent running in
 * the guest, such as `copilot --acp`. The agent reads and edits workspace files with its own
 * tools, so this client advertises no file-system or terminal capabilities.
 */
class AcpClient(transport: GuestVfsClient, private val tool: String = COPILOT_AGENT_TOOL) {
    private val connection = JsonRpcConnection(transport, "Acp")
    @Volatile private var sessionId: String? = null
    @Volatile var authMethods: List<AcpAuthMethod> = emptyList()
        private set
    /** Whether the agent accepts embedded `resource` blocks (ACP `promptCapabilities.embeddedContext`). */
    @Volatile var embeddedContext: Boolean = false
        private set
    @Volatile var eventListener: ((AcpEvent) -> Unit)? = null
    @Volatile var exitListener: ((String) -> Unit)? = null
    val isStarted: Boolean get() = connection.bridgeId != null
    val hasSession: Boolean get() = sessionId != null

    init {
        connection.notificationHandler = ::handleNotification
        connection.requestHandler = ::handleRequest
        connection.exitListener = { code ->
            sessionId = null
            exitListener?.invoke("Agent exited (${code ?: "unknown"})")
        }
        connection.errorListener = { message -> exitListener?.invoke(message) }
    }

    /** Launches the agent and negotiates the protocol; [newSession] then opens a conversation. */
    fun start(cwd: String): CompletableFuture<Unit> =
        connection.start(tool, cwd).thenCompose {
            connection.request("initialize", JsonObject().apply {
                addProperty("protocolVersion", ACP_PROTOCOL_VERSION)
                add("clientCapabilities", JsonObject().apply {
                    add("fs", JsonObject().apply {
                        addProperty("readTextFile", false)
                        addProperty("writeTextFile", false)
                    })
                    addProperty("terminal", false)
                })
            }, timeoutSeconds = 60)
        }.thenApply { result ->
            authMethods = parseAuthMethods(result)
            embeddedContext = runCatching {
                result.asJsonObject.getAsJsonObject("agentCapabilities")
                    .getAsJsonObject("promptCapabilities").get("embeddedContext").asBoolean
            }.getOrDefault(false)
        }

    fun newSession(cwd: String): CompletableFuture<Unit> =
        connection.request("session/new", JsonObject().apply {
            addProperty("cwd", cwd)
            add("mcpServers", JsonArray())
        }, timeoutSeconds = 60).thenApply { result ->
            sessionId = result.asJsonObject.get("sessionId").asString
        }

    fun authenticate(methodId: String): CompletableFuture<JsonElement> =
        connection.request("authenticate", JsonObject().apply { addProperty("methodId", methodId) }, null)

    /** Completes with the agent's stop reason once the whole turn has streamed. */
    fun prompt(text: String, references: List<ChatReference> = emptyList()): CompletableFuture<String> {
        val session = sessionId
            ?: return CompletableFuture.failedFuture(IllegalStateException("No agent session is open"))
        return connection.request("session/prompt", JsonObject().apply {
            addProperty("sessionId", session)
            add("prompt", acpPromptBlocks(text, references, embeddedContext))
        }, timeoutSeconds = null).thenApply { result ->
            result.takeIf { it.isJsonObject }?.asJsonObject?.get("stopReason")?.asString ?: "end_turn"
        }
    }

    fun cancel() {
        val session = sessionId ?: return
        connection.notify("session/cancel", JsonObject().apply { addProperty("sessionId", session) })
    }

    fun stop() {
        sessionId = null
        connection.stop()
    }

    fun onDisconnected() {
        sessionId = null
        connection.detach(java.io.IOException("Guest connection was lost"))
    }

    private fun handleNotification(method: String, params: JsonObject) {
        if (method != "session/update") return
        val update = params.getAsJsonObject("update") ?: return
        parseSessionUpdate(update)?.let { event -> eventListener?.invoke(event) }
    }

    private fun handleRequest(method: String, params: JsonObject): CompletableFuture<JsonElement>? {
        if (method != "session/request_permission") return null
        val answer = CompletableFuture<JsonElement>()
        val toolCall = params.getAsJsonObject("toolCall")
        val options = params.getAsJsonArray("options")?.mapNotNull { element ->
            val option = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            AcpPermissionOption(
                id = option.get("optionId")?.asString ?: return@mapNotNull null,
                name = option.get("name")?.asString ?: option.get("optionId").asString,
                kind = option.get("kind")?.asString.orEmpty(),
            )
        }.orEmpty()
        val listener = eventListener
        if (listener == null) {
            answer.complete(permissionOutcome(null))
        } else {
            listener(
                AcpEvent.PermissionRequest(
                    title = toolCall?.get("title")?.asString ?: "The agent requests permission",
                    options = options,
                    respond = { optionId -> answer.complete(permissionOutcome(optionId)) },
                ),
            )
        }
        return answer
    }
}

internal fun permissionOutcome(optionId: String?): JsonObject = JsonObject().apply {
    add("outcome", JsonObject().apply {
        if (optionId == null) {
            addProperty("outcome", "cancelled")
        } else {
            addProperty("outcome", "selected")
            addProperty("optionId", optionId)
        }
    })
}

internal fun parseAuthMethods(result: JsonElement): List<AcpAuthMethod> {
    val methods = result.takeIf { it.isJsonObject }?.asJsonObject?.getAsJsonArray("authMethods") ?: return emptyList()
    return methods.mapNotNull { element ->
        val method = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        val id = method.get("id")?.asString ?: return@mapNotNull null
        AcpAuthMethod(id, method.get("name")?.asString ?: id, method.get("description")?.takeUnless { it.isJsonNull }?.asString)
    }
}

internal fun parseSessionUpdate(update: JsonObject): AcpEvent? =
    when (update.get("sessionUpdate")?.asString) {
        "agent_message_chunk" -> contentText(update)?.let(AcpEvent::MessageChunk)
        "agent_thought_chunk" -> contentText(update)?.let(AcpEvent::ThoughtChunk)
        "tool_call", "tool_call_update" -> update.get("toolCallId")?.asString?.let { id ->
            AcpEvent.ToolCall(
                id = id,
                title = update.get("title")?.takeUnless { it.isJsonNull }?.asString,
                kind = update.get("kind")?.takeUnless { it.isJsonNull }?.asString,
                status = update.get("status")?.takeUnless { it.isJsonNull }?.asString,
            )
        }
        "plan" -> AcpEvent.Plan(
            update.getAsJsonArray("entries")?.mapNotNull { entry ->
                entry.takeIf { it.isJsonObject }?.asJsonObject?.get("content")?.asString
            }.orEmpty(),
        )
        "available_commands_update" -> AcpEvent.Commands(
            update.getAsJsonArray("availableCommands")?.mapNotNull { command ->
                command.takeIf { it.isJsonObject }?.asJsonObject?.get("name")?.asString
            }.orEmpty(),
        )
        else -> null
    }

private fun contentText(update: JsonObject): String? {
    val content = update.get("content")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
    return if (content.get("type")?.asString == "text") content.get("text")?.asString else null
}
