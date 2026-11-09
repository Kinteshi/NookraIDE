package io.github.kinteshi.quirecode.transport

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID

interface GuestVfs {
    fun connect(url: String, token: String): CompletableFuture<Unit>
    fun roots(): CompletableFuture<List<String>>
    fun listDir(path: String): CompletableFuture<List<FileEntry>>
    fun stat(path: String): CompletableFuture<FileEntry>
    fun read(path: String, offset: Long, length: Int): CompletableFuture<IncomingPacket.ReadFile>
    fun write(path: String, offset: Long, content: ByteArray, truncate: Boolean, expectedModifiedTs: Long?): CompletableFuture<IncomingPacket.Ack>
    fun flush(path: String): CompletableFuture<IncomingPacket.Ack>
    fun isConnected(): Boolean
}

class GuestVfsException(val code: String, message: String) : IOException(message)

/** The guest refused the handshake: `InvalidToken` usually means it was reset or re-installed. */
class GuestRejectedException(val reason: String, val attemptsLeft: Int? = null) : IOException(
    when (reason) {
        "InvalidToken" -> "The guest rejected the saved token"
        "PairingUnavailable" -> "No pairing code is active on the guest; run the installer or `avf-native-daemon pair` again"
        "PairingFailed" -> "Wrong pairing code" + (attemptsLeft?.let { "; $it attempts left" } ?: "")
        else -> "Guest rejected connection: $reason"
    },
)

private const val NATIVE_APP_ORIGIN = "avf-native-android://localhost"
/** Welcome feature of daemons that answer `ProbeCommands`. */
const val PROBE_COMMANDS_FEATURE = "probe_commands"

class GuestVfsClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        // The daemon drops a client that pinged and went silent (a crashed app behind Linux
        // Terminal's forwarder); a missing pong fails the socket here, and the app reconnects.
        .pingInterval(20, TimeUnit.SECONDS)
        .build(),
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(),
) : GuestVfs, Closeable {
    private val requestIds = AtomicLong(0)
    private val pending = ConcurrentHashMap<Long, CompletableFuture<IncomingPacket>>()
    private val pendingRpcStarts = ConcurrentHashMap<UUID, CompletableFuture<RpcEvent.Started>>()
    private val rpcEventListeners = ConcurrentHashMap<UUID, (RpcEvent) -> Unit>()
    private val ptyListeners = ConcurrentHashMap<UUID, (PtyEvent) -> Unit>()
    private val searchListeners = ConcurrentHashMap<Long, (SearchEvent) -> Unit>()
    private val gitListeners = ConcurrentHashMap<Long, (GitEvent) -> Unit>()
    private val pendingPings = ConcurrentHashMap<Long, CompletableFuture<GuestStats>>()
    private val pendingProbes = ConcurrentHashMap<Long, CompletableFuture<Map<String, String?>>>()
    /** Optional requests the connected daemon understands (its Welcome `features`). */
    @Volatile var guestFeatures: Set<String> = emptySet()
        private set
    /** Terminal sessions the guest still runs, from the latest handshake; reattach to keep them. */
    @Volatile var guestPtySessions: List<PtySessionInfo> = emptyList()
        private set
    @Volatile private var socket: WebSocket? = null
    @Volatile private var connected = CompletableFuture<Unit>()
    @Volatile var connectionStateListener: ((Boolean, String?) -> Unit)? = null
    @Volatile var rpcEventListener: ((RpcEvent) -> Unit)? = null
    /** Guest ports opening and closing; the daemon sends the open ones again after each handshake. */
    @Volatile var portListener: ((GuestPort, Boolean) -> Unit)? = null

    fun addRpcEventListener(bridgeId: UUID, listener: (RpcEvent) -> Unit) {
        rpcEventListeners[bridgeId] = listener
    }

    fun removeRpcEventListener(bridgeId: UUID) {
        rpcEventListeners.remove(bridgeId)
    }

    /**
     * Exchanges a one-time pairing code for the guest's session token over a short-lived
     * connection; the caller then connects normally with the token.
     */
    fun pair(url: String, code: String, fingerprint: String? = null): CompletableFuture<String> {
        validateUrl(url, fingerprint)
        val result = CompletableFuture<String>()
        val request = Request.Builder().url(url).header("Origin", NATIVE_APP_ORIGIN).build()
        clientFor(fingerprint).newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (!webSocket.send(ByteString.of(*MessagePackVfsCodec.pair(code.trim())))) {
                        result.completeExceptionally(IOException("Could not send the pairing request"))
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    when (val packet = runCatching { MessagePackVfsCodec.decode(bytes.toByteArray()) }.getOrNull()) {
                        is IncomingPacket.Paired -> result.complete(packet.token)
                        is IncomingPacket.Rejected -> result.completeExceptionally(GuestRejectedException(packet.reason, packet.attemptsLeft))
                        else -> result.completeExceptionally(IOException("Unexpected reply to the pairing request"))
                    }
                    webSocket.close(1000, null)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    result.completeExceptionally(IOException("Could not reach the guest", t))
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                    result.completeExceptionally(IOException("The guest closed the pairing connection"))
                }
            },
        )
        scheduler.schedule({ result.completeExceptionally(IOException("Pairing timed out")) }, 10, TimeUnit.SECONDS)
        return result
    }

    /** The shared client, or one that trusts only [fingerprint]'s certificate (paired hosts). */
    private fun clientFor(fingerprint: String?): OkHttpClient = fingerprint?.let(http::pinnedTo) ?: http

    private fun validateUrl(url: String, fingerprint: String? = null) {
        require(url.startsWith("ws://") || url.startsWith("wss://")) { "Guest URL must use ws:// or wss://" }
        require(fingerprint == null || url.startsWith("wss://")) { "A pinned certificate needs a wss:// URL" }
        if (url.startsWith("ws://")) {
            val host = java.net.URI(url).host?.lowercase()
            require(host in setOf("localhost", "127.0.0.1", "::1")) {
                "Unencrypted WebSocket connections are limited to loopback; use wss:// for remote guests"
            }
        }
    }

    override fun connect(url: String, token: String): CompletableFuture<Unit> = connect(url, token, null)

    /** Connects; with [fingerprint] the host's self-signed certificate must match it exactly. */
    fun connect(url: String, token: String, fingerprint: String?): CompletableFuture<Unit> {
        validateUrl(url, fingerprint)
        require(token.isNotBlank()) { "Guest session token is required" }
        socket?.close(1000, "reconnecting")
        failPending(IOException("Guest connection replaced"))
        val handshake = CompletableFuture<Unit>()
        connected = handshake
        val request = Request.Builder()
            .url(url)
            .header("Origin", NATIVE_APP_ORIGIN)
            .build()
        socket = clientFor(fingerprint).newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (!webSocket.send(ByteString.of(*MessagePackVfsCodec.hello(token)))) {
                        handshake.completeExceptionally(IOException("Could not send guest protocol handshake"))
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    val packet = try {
                        MessagePackVfsCodec.decode(bytes.toByteArray())
                    } catch (error: Exception) {
                        handshake.completeExceptionally(IOException("Invalid guest MessagePack packet", error))
                        webSocket.close(1002, "invalid protocol packet")
                        return
                    }
                    when (packet) {
                        is IncomingPacket.Welcome -> {
                            if (packet.protocolVersion != 2) {
                                handshake.completeExceptionally(
                                    IOException("Guest protocol version ${packet.protocolVersion} is unsupported"),
                                )
                            } else {
                                guestPtySessions = packet.ptySessions
                                guestFeatures = packet.features
                                handshake.complete(Unit)
                                connectionStateListener?.invoke(true, null)
                            }
                        }
                        is IncomingPacket.Rejected ->
                            handshake.completeExceptionally(GuestRejectedException(packet.reason, packet.attemptsLeft))
                        is IncomingPacket.Paired -> Unit
                        is IncomingPacket.ReadFile -> complete(packet.requestId, packet)
                        is IncomingPacket.Ack -> complete(packet.requestId, packet)
                        is IncomingPacket.ListDir -> complete(packet.requestId, packet)
                        is IncomingPacket.Stat -> complete(packet.requestId, packet)
                        is IncomingPacket.Roots -> complete(packet.requestId, packet)
                        is IncomingPacket.Error ->
                            completeExceptionally(packet.requestId, GuestVfsException(packet.code, packet.message))
                        is IncomingPacket.Rpc -> handleRpcEvent(packet.event)
                        is IncomingPacket.Search -> searchListeners[packet.event.requestId]?.invoke(packet.event)
                        is IncomingPacket.Git -> gitListeners[packet.event.requestId]?.invoke(packet.event)
                        is IncomingPacket.CommandsProbed -> pendingProbes.remove(packet.requestId)?.complete(packet.paths)
                        is IncomingPacket.Stats -> pendingPings.remove(packet.stats.timestamp)?.complete(packet.stats)
                        is IncomingPacket.Pty -> ptyListeners[packet.event.sessionId]?.invoke(packet.event)
                        is IncomingPacket.Port -> portListener?.invoke(packet.port, packet.open)
                        is IncomingPacket.Unexpected ->
                            handshake.completeExceptionally(IOException(packet.message))
                        IncomingPacket.Ignored -> Unit
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    handshake.completeExceptionally(IOException("Guest connection failed", t))
                    failPending(IOException("Guest connection lost", t))
                    if (socket === webSocket) socket = null
                    connectionStateListener?.invoke(false, t.message)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    // 1005, 1006 and 1015 describe a close without a status and may not be sent back.
                    webSocket.close(if (code in setOf(1005, 1006, 1015)) 1000 else code, null)
                    handshake.completeExceptionally(IOException("Guest closed the connection ($code) $reason".trim()))
                    failPending(IOException("Guest connection closing: $reason"))
                    if (socket === webSocket) socket = null
                    connectionStateListener?.invoke(false, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    handshake.completeExceptionally(IOException("Guest connection closed: $reason"))
                    failPending(IOException("Guest connection closed: $reason"))
                    if (socket === webSocket) socket = null
                    connectionStateListener?.invoke(false, reason)
                }
            },
        )
        scheduler.schedule(
            { handshake.completeExceptionally(IOException("Guest handshake timed out")) },
            10,
            TimeUnit.SECONDS,
        )
        return handshake
    }

    override fun roots(): CompletableFuture<List<String>> =
        request("RootsReq").thenApply {
            (it as IncomingPacket.Roots).roots
        }

    override fun listDir(path: String): CompletableFuture<List<FileEntry>> =
        request("ListDirReq", mapOf("path" to path)).thenApply {
            (it as IncomingPacket.ListDir).entries
        }

    override fun stat(path: String): CompletableFuture<FileEntry> =
        request("StatReq", mapOf("path" to path)).thenApply {
            (it as IncomingPacket.Stat).entry
        }

    override fun read(path: String, offset: Long, length: Int): CompletableFuture<IncomingPacket.ReadFile> {
        require(length in 1..4 * 1024 * 1024) { "VFS read length must be between 1 byte and 4 MiB" }
        return request("ReadFileReq", mapOf("path" to path, "offset" to offset, "len" to length))
            .thenApply { it as IncomingPacket.ReadFile }
    }

    override fun write(
        path: String,
        offset: Long,
        content: ByteArray,
        truncate: Boolean,
        expectedModifiedTs: Long?,
    ): CompletableFuture<IncomingPacket.Ack> {
        require(content.size <= 4 * 1024 * 1024) { "VFS write chunk must not exceed 4 MiB" }
        return request(
            "WriteFileReq",
            mapOf(
                "path" to path,
                "content" to content,
                "expected_modified_ts" to expectedModifiedTs,
                "truncate" to truncate,
                "offset" to offset,
            ),
        ).thenApply { it as IncomingPacket.Ack }
    }

    override fun flush(path: String): CompletableFuture<IncomingPacket.Ack> =
        request("FlushReq", mapOf("path" to path)).thenApply { it as IncomingPacket.Ack }

    /** Creates an empty file (failing if it exists) or a directory with its parents. */
    fun create(path: String, directory: Boolean): CompletableFuture<IncomingPacket.Ack> =
        request("CreateReq", mapOf("path" to path, "kind" to if (directory) "Dir" else "File"))
            .thenApply { it as IncomingPacket.Ack }

    fun rename(from: String, to: String): CompletableFuture<IncomingPacket.Ack> =
        request("RenameReq", mapOf("from" to from, "to" to to, "overwrite" to false))
            .thenApply { it as IncomingPacket.Ack }

    /** Moves [path] to the daemon's trash (restorable), recursively for directories. */
    fun delete(path: String): CompletableFuture<IncomingPacket.Ack> =
        request("DeleteReq", mapOf("path" to path, "recursive" to true, "to_trash" to true))
            .thenApply { it as IncomingPacket.Ack }

    /** Puts a deleted entry back where it was; the daemon keeps it for 10 s after the delete. */
    fun restore(trashId: String): CompletableFuture<IncomingPacket.Ack> =
        request("RestoreReq", mapOf("trash_id" to trashId))
            .thenApply { it as IncomingPacket.Ack }

    fun startLanguageServer(tool: String, cwd: String, args: List<String> = emptyList()): CompletableFuture<UUID> =
        startRpc(tool, cwd, "Lsp", args)

    /** [env] must be names the tool's catalog entry allows (`env_allowlist`). */
    fun startRpc(
        tool: String,
        cwd: String,
        kind: String,
        args: List<String> = emptyList(),
        env: List<Pair<String, String>> = emptyList(),
    ): CompletableFuture<UUID> {
        val activeSocket = connectedSocket()
            ?: return CompletableFuture.failedFuture(IOException("Guest is not connected"))
        val bridgeId = UUID.randomUUID()
        val started = CompletableFuture<RpcEvent.Started>()
        pendingRpcStarts[bridgeId] = started
        if (!activeSocket.send(ByteString.of(*MessagePackVfsCodec.rpcStart(bridgeId, tool, args, cwd, kind, env)))) {
            pendingRpcStarts.remove(bridgeId)
            return CompletableFuture.failedFuture(IOException("Guest WebSocket is not accepting RPC messages"))
        }
        scheduler.schedule(
            {
                pendingRpcStarts.remove(bridgeId)?.completeExceptionally(
                    IOException("Guest tool $tool did not start before the timeout"),
                )
            },
            30,
            TimeUnit.SECONDS,
        )
        return started.thenApply { bridgeId }
    }

    fun sendRpcMessage(bridgeId: UUID, body: String): CompletableFuture<Unit> {
        val activeSocket = connectedSocket()
            ?: return CompletableFuture.failedFuture(IOException("Guest is not connected"))
        if (!activeSocket.send(ByteString.of(*MessagePackVfsCodec.rpcMessage(bridgeId, body)))) {
            return CompletableFuture.failedFuture(IOException("Guest WebSocket is not accepting RPC messages"))
        }
        return CompletableFuture.completedFuture(Unit)
    }

    fun stopRpc(bridgeId: UUID): CompletableFuture<Unit> {
        val activeSocket = connectedSocket()
            ?: return CompletableFuture.failedFuture(IOException("Guest is not connected"))
        if (!activeSocket.send(ByteString.of(*MessagePackVfsCodec.rpcStop(bridgeId)))) {
            return CompletableFuture.failedFuture(IOException("Guest WebSocket is not accepting RPC messages"))
        }
        return CompletableFuture.completedFuture(Unit)
    }

    fun addPtyListener(sessionId: UUID, listener: (PtyEvent) -> Unit) {
        ptyListeners[sessionId] = listener
    }

    fun removePtyListener(sessionId: UUID) {
        ptyListeners.remove(sessionId)
    }

    fun ptySpawn(
        sessionId: UUID,
        cwd: String?,
        cols: Int,
        rows: Int,
        shell: String? = null,
        args: List<String> = emptyList(),
        integration: Boolean = false,
    ): Boolean = sendFrame(MessagePackVfsCodec.ptySpawn(sessionId, cwd, cols, rows, shell, args, integration))

    fun ptyInput(sessionId: UUID, data: ByteArray): Boolean = sendFrame(MessagePackVfsCodec.ptyInput(sessionId, data))

    fun ptyResize(sessionId: UUID, cols: Int, rows: Int): Boolean =
        sendFrame(MessagePackVfsCodec.ptyResize(sessionId, cols, rows))

    fun ptyReattach(sessionId: UUID): Boolean = sendFrame(MessagePackVfsCodec.ptyCommand(sessionId, "Reattach"))

    fun ptyKill(sessionId: UUID): Boolean = sendFrame(MessagePackVfsCodec.ptyCommand(sessionId, "Kill"))

    /**
     * Starts a ripgrep search in the guest; [listener] gets matches as they stream, then Done or
     * Error (after which it is removed). Returns the request id for [cancelSearch], or null offline.
     */
    fun search(query: SearchQuery, listener: (SearchEvent) -> Unit): Long? {
        val requestId = requestIds.incrementAndGet()
        searchListeners[requestId] = { event ->
            if (event !is SearchEvent.Match) searchListeners.remove(requestId)
            listener(event)
        }
        if (!sendFrame(MessagePackVfsCodec.searchStart(requestId, query))) {
            searchListeners.remove(requestId)
            return null
        }
        return requestId
    }

    /**
     * Runs a Git operation in [repo]. [onEvent] sees progress and credential prompts (answer with
     * [answerGitPrompt]); the future completes with the result or fails with the Git error.
     */
    fun git(repo: String, operation: GitOperation, onEvent: (GitEvent) -> Unit = {}): CompletableFuture<GitResult> {
        val requestId = requestIds.incrementAndGet()
        val result = CompletableFuture<GitResult>()
        gitListeners[requestId] = { event ->
            when (event) {
                is GitEvent.Result -> {
                    gitListeners.remove(requestId)
                    result.complete(event.result)
                }
                is GitEvent.Error -> {
                    gitListeners.remove(requestId)
                    result.completeExceptionally(IOException(event.message))
                }
                else -> onEvent(event)
            }
        }
        if (!sendFrame(MessagePackVfsCodec.gitRequest(requestId, repo, operation))) {
            gitListeners.remove(requestId)
            result.completeExceptionally(IOException("Guest is not connected"))
        }
        return result
    }

    /** Guest CPU and memory, sampled by the daemon. */
    fun ping(): CompletableFuture<GuestStats> {
        val stamp = System.nanoTime()
        val result = CompletableFuture<GuestStats>()
        pendingPings[stamp] = result
        if (!sendFrame(MessagePackVfsCodec.ping(stamp))) {
            pendingPings.remove(stamp)
            result.completeExceptionally(IOException("Guest is not connected"))
        } else {
            scheduler.schedule({ pendingPings.remove(stamp)?.completeExceptionally(IOException("Ping timed out")) }, 10, TimeUnit.SECONDS)
        }
        return result
    }

    /**
     * Each of [names] mapped to where the guest has it, or null when missing. Fails with
     * [UnsupportedOperationException] on a daemon too old to answer (it would drop the connection).
     */
    fun probeCommands(names: List<String>): CompletableFuture<Map<String, String?>> {
        if (PROBE_COMMANDS_FEATURE !in guestFeatures) {
            return CompletableFuture.failedFuture(UnsupportedOperationException("The guest daemon can't check for commands; update it from Settings → Set up guest"))
        }
        val requestId = requestIds.incrementAndGet()
        val result = CompletableFuture<Map<String, String?>>()
        pendingProbes[requestId] = result
        if (!sendFrame(MessagePackVfsCodec.probeCommands(requestId, names))) {
            pendingProbes.remove(requestId)
            result.completeExceptionally(IOException("Guest is not connected"))
        } else {
            scheduler.schedule({ pendingProbes.remove(requestId)?.completeExceptionally(IOException("Command check timed out")) }, 10, TimeUnit.SECONDS)
        }
        return result
    }

    fun answerGitPrompt(requestId: Long, promptId: Long, answer: String) {
        sendFrame(MessagePackVfsCodec.gitPromptAnswer(requestId, promptId, answer))
    }

    fun cancelSearch(requestId: Long) {
        searchListeners.remove(requestId)
        sendFrame(MessagePackVfsCodec.searchCancel(requestId))
    }

    private fun sendFrame(frame: ByteArray): Boolean = connectedSocket()?.send(ByteString.of(*frame)) == true

    override fun isConnected(): Boolean = connected.isDone && !connected.isCompletedExceptionally && socket != null

    private fun connectedSocket(): WebSocket? =
        socket?.takeIf { isConnected() }

    private fun handleRpcEvent(event: RpcEvent) {
        when (event) {
            is RpcEvent.Started -> pendingRpcStarts.remove(event.bridgeId)?.complete(event)
            is RpcEvent.Error -> {
                val pending = pendingRpcStarts.remove(event.bridgeId)
                if (pending != null) pending.completeExceptionally(IOException(event.message))
                else rpcEventListeners[event.bridgeId]?.invoke(event) ?: rpcEventListener?.invoke(event)
            }
            is RpcEvent.Message, is RpcEvent.Exited ->
                rpcEventListeners[event.bridgeId]?.invoke(event) ?: rpcEventListener?.invoke(event)
        }
    }

    private fun request(kind: String, fields: Map<String, Any?> = emptyMap()): CompletableFuture<IncomingPacket> {
        val activeSocket = socket
            ?: return CompletableFuture.failedFuture(IOException("Guest is disconnected"))
        if (!isConnected()) {
            return CompletableFuture.failedFuture(IOException("Guest is not authenticated"))
        }
        val requestId = requestIds.incrementAndGet()
        val result = CompletableFuture<IncomingPacket>()
        pending[requestId] = result
        val frame = MessagePackVfsCodec.request(kind, requestId, fields)
        if (!activeSocket.send(ByteString.of(*frame))) {
            pending.remove(requestId)
            return CompletableFuture.failedFuture(IOException("Guest WebSocket is not accepting messages"))
        }
        scheduler.schedule(
            {
                pending.remove(requestId)?.completeExceptionally(
                    IOException("Guest request $requestId timed out"),
                )
            },
            30,
            TimeUnit.SECONDS,
        )
        return result
    }

    private fun complete(requestId: Long, packet: IncomingPacket) {
        pending.remove(requestId)?.complete(packet)
    }

    private fun completeExceptionally(requestId: Long, error: Throwable) {
        pending.remove(requestId)?.completeExceptionally(error)
    }

    private fun failPending(error: Throwable) {
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
        pendingRpcStarts.values.forEach { it.completeExceptionally(error) }
        pendingRpcStarts.clear()
        pendingProbes.values.forEach { it.completeExceptionally(error) }
        pendingProbes.clear()
        searchListeners.entries.toList().forEach { (requestId, listener) ->
            listener(SearchEvent.Error(requestId, error.message ?: "Guest connection lost"))
        }
        searchListeners.clear()
        gitListeners.entries.toList().forEach { (requestId, listener) ->
            listener(GitEvent.Error(requestId, error.message ?: "Guest connection lost"))
        }
        gitListeners.clear()
    }

    override fun close() {
        socket?.close(1000, "app closed")
        socket = null
        failPending(IOException("Guest client closed"))
        rpcEventListeners.clear()
        ptyListeners.clear()
        scheduler.shutdownNow()
        http.dispatcher.executorService.shutdown()
    }
}
