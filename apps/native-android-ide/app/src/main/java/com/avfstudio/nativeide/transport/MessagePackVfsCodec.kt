package com.avfstudio.nativeide.transport

import org.msgpack.core.MessagePack
import org.msgpack.value.Value
import java.util.UUID

internal object MessagePackVfsCodec {
    fun hello(token: String): ByteArray = packet("System", "Hello") {
        packMapHeader(3)
        stringField("protocol_version", 2)
        stringField("auth_token", token)
        stringField("client_name", "avf-native-android-ide")
    }

    fun ping(timestamp: Long): ByteArray = packet("System", "Ping") {
        packMapHeader(1)
        stringField("timestamp", timestamp)
    }

    /** Asks which of [names] the guest can run (daemons whose Welcome lists `probe_commands`). */
    fun probeCommands(requestId: Long, names: List<String>): ByteArray = packet("System", "ProbeCommands") {
        packMapHeader(2)
        stringField("request_id", requestId)
        packString("names")
        packArrayHeader(names.size)
        names.forEach(::packString)
    }

    /** First packet of a pairing connection: trades a one-time code for the session token. */
    fun pair(code: String): ByteArray = packet("System", "Pair") {
        packMapHeader(3)
        stringField("protocol_version", 2)
        stringField("code", code)
        stringField("client_name", "avf-native-android-ide")
    }

    fun searchStart(requestId: Long, search: SearchQuery): ByteArray = packet("Search", "StartReq") {
        packMapHeader(10)
        stringField("req_id", requestId)
        packString("roots")
        packStrings(search.roots)
        stringField("query", search.query)
        packString("regex")
        packBoolean(search.regex)
        packString("case_sensitive")
        packBoolean(search.caseSensitive)
        packString("whole_word")
        packBoolean(search.wholeWord)
        packString("include")
        packStrings(search.include)
        packString("exclude")
        packStrings(search.exclude)
        stringField("max_results", search.maxResults)
        stringField("context_lines", search.contextLines)
    }

    fun gitRequest(requestId: Long, repo: String, operation: GitOperation): ByteArray = packet("Git", "Req") {
        packMapHeader(3)
        stringField("req_id", requestId)
        stringField("repo", repo)
        packString("op")
        operation.pack(this)
    }

    fun gitPromptAnswer(requestId: Long, promptId: Long, answer: String): ByteArray = packet("Git", "PromptAnswer") {
        packMapHeader(3)
        stringField("req_id", requestId)
        stringField("prompt_id", promptId)
        stringField("answer", answer)
    }

    fun searchCancel(requestId: Long): ByteArray = packet("Search", "Cancel") {
        packMapHeader(1)
        stringField("req_id", requestId)
    }

    private fun org.msgpack.core.MessagePacker.packStrings(values: List<String>) {
        packArrayHeader(values.size)
        values.forEach(::packString)
    }

    fun request(kind: String, requestId: Long, fields: Map<String, Any?> = emptyMap()): ByteArray =
        packet("Vfs", kind) {
            packMapHeader(fields.size + 1)
            stringField("req_id", requestId)
            fields.forEach { (key, value) ->
                packString(key)
                when (value) {
                    null -> packNil()
                    is String -> packString(value)
                    is Long -> packLong(value)
                    is Int -> packInt(value)
                    is Boolean -> packBoolean(value)
                    is ByteArray -> {
                        packBinaryHeader(value.size)
                        writePayload(value)
                    }
                    else -> error("Unsupported VFS field type: ${value::class.java.name}")
                }
            }
        }

    fun rpcStart(bridgeId: UUID, tool: String, args: List<String>, cwd: String?, kind: String = "Lsp"): ByteArray =
        rpcPacket("Start") {
            require(kind in setOf("Lsp", "Dap", "Acp", "Kernel")) { "Unsupported RPC bridge kind: $kind" }
            packMapHeader(6)
            stringField("bridge_id", bridgeId.toString())
            stringField("tool", tool)
            stringField("kind", kind)
            packString("args")
            packArrayHeader(args.size)
            args.forEach(::packString)
            packString("cwd")
            cwd?.let(::packString) ?: packNil()
            packString("env")
            packArrayHeader(0)
        }

    fun rpcMessage(bridgeId: UUID, body: String): ByteArray =
        rpcPacket("Message") {
            packMapHeader(2)
            stringField("bridge_id", bridgeId.toString())
            stringField("body", body)
        }

    fun rpcStop(bridgeId: UUID): ByteArray =
        rpcPacket("Stop") {
            packMapHeader(1)
            stringField("bridge_id", bridgeId.toString())
        }

    fun ptySpawn(sessionId: UUID, cwd: String?, cols: Int, rows: Int): ByteArray =
        ptyPacket(sessionId) {
            packMapHeader(1)
            packString("Spawn")
            packMapHeader(4)
            packString("shell")
            packNil()
            packString("cwd")
            cwd?.let(::packString) ?: packNil()
            stringField("cols", cols)
            stringField("rows", rows)
        }

    fun ptyInput(sessionId: UUID, data: ByteArray): ByteArray =
        ptyPacket(sessionId) {
            packMapHeader(1)
            packString("Input")
            packBinaryHeader(data.size)
            writePayload(data)
        }

    fun ptyResize(sessionId: UUID, cols: Int, rows: Int): ByteArray =
        ptyPacket(sessionId) {
            packMapHeader(1)
            packString("Resize")
            packMapHeader(2)
            stringField("cols", cols)
            stringField("rows", rows)
        }

    /** Unit variants (`Reattach`, `Kill`) are encoded as bare strings. */
    fun ptyCommand(sessionId: UUID, command: String): ByteArray {
        require(command == "Reattach" || command == "Kill") { "Unsupported PTY command: $command" }
        return ptyPacket(sessionId) { packString(command) }
    }

    fun decode(bytes: ByteArray): IncomingPacket {
        val packet = MessagePack.newDefaultUnpacker(bytes).use { it.unpackValue() }
        packet.mapStringValue("Git")?.let { group ->
            val variant = group.singleEntry()
            val fields = variant.value.asMapValue().map().mapKeys { it.key.asString() }
            val requestId = fields["req_id"]?.asLong() ?: -1L
            return when (variant.key.asString()) {
                "Res" -> IncomingPacket.Git(GitEvent.Result(requestId, decodeGitResult(fields.getValue("result"))))
                "Progress" -> IncomingPacket.Git(
                    GitEvent.Progress(
                        requestId,
                        fields.getValue("phase").asString(),
                        fields["percent"]?.takeUnless { it.isNilValue }?.asLong()?.toInt(),
                        fields.getValue("detail").asString(),
                    ),
                )
                "Prompt" -> IncomingPacket.Git(
                    GitEvent.Prompt(
                        requestId,
                        fields.getValue("prompt_id").asLong(),
                        fields.getValue("text").asString(),
                        fields.getValue("secret").asBoolean(),
                    ),
                )
                "Error" -> IncomingPacket.Git(GitEvent.Error(requestId, fields.getValue("message").asString()))
                else -> IncomingPacket.Ignored
            }
        }
        packet.mapStringValue("Search")?.let { group ->
            val variant = group.singleEntry()
            val fields = variant.value.asMapValue().map().mapKeys { it.key.asString() }
            val requestId = fields["req_id"]?.asLong() ?: -1L
            return when (variant.key.asString()) {
                "Match" -> {
                    // The daemon reports byte offsets into the UTF-8 line; convert them to chars.
                    val preview = fields.getValue("preview").asString()
                    val bytes = preview.toByteArray(Charsets.UTF_8)
                    val startByte = fields.getValue("col").asLong().toInt().coerceIn(0, bytes.size)
                    val endByte = (startByte + fields.getValue("len").asLong().toInt()).coerceIn(startByte, bytes.size)
                    val column = String(bytes, 0, startByte, Charsets.UTF_8).length
                    IncomingPacket.Search(
                        SearchEvent.Match(
                            requestId = requestId,
                            path = fields.getValue("path").asString(),
                            line = fields.getValue("line").asLong().toInt(),
                            column = column,
                            length = String(bytes, startByte, endByte - startByte, Charsets.UTF_8).length,
                            preview = preview,
                        ),
                    )
                }
                "Done" -> IncomingPacket.Search(
                    SearchEvent.Done(
                        requestId,
                        fields.getValue("total").asLong().toInt(),
                        fields.getValue("truncated").asBoolean(),
                        fields.getValue("elapsed_ms").asLong().toInt(),
                    ),
                )
                "Error" -> IncomingPacket.Search(SearchEvent.Error(requestId, fields.getValue("message").asString()))
                else -> IncomingPacket.Ignored
            }
        }
        val group = packet.mapStringValue("Vfs")
        if (group != null) {
            val variant = group.singleEntry()
            val payload = variant.value
            val fields = payload.asMapValue().map().mapKeys { it.key.asString() }
            val requestId = fields["req_id"]?.asLong() ?: -1L
            return when (variant.key.asString()) {
                "ReadFileRes" -> IncomingPacket.ReadFile(
                    requestId = requestId,
                    content = fields.getValue("content").asBinaryValue().asByteArray(),
                    eof = fields.getValue("eof").asBoolean(),
                    modifiedTs = fields.getValue("modified_ts").asLong(),
                    size = fields.getValue("size").asLong(),
                )
                "Ack" -> IncomingPacket.Ack(
                    requestId = requestId,
                    modifiedTs = fields["modified_ts"]?.takeUnless { it.isNilValue }?.asLong(),
                )
                "ListDirRes" -> IncomingPacket.ListDir(
                    requestId = requestId,
                    entries = fields.getValue("entries").asArrayValue().list().map { entry ->
                        val values = entry.asMapValue().map().mapKeys { it.key.asString() }
                        FileEntry(
                            name = values.getValue("name").asString(),
                            path = values.getValue("path").asString(),
                            isDirectory = values.getValue("is_dir").asBoolean(),
                            size = values.getValue("size").asLong(),
                            modifiedTs = values.getValue("modified_ts").asLong(),
                        )
                    },
                )
                "RootsRes" -> IncomingPacket.Roots(
                    requestId = requestId,
                    roots = fields.getValue("roots").asArrayValue().list().map { it.asString() },
                )
                "StatRes" -> {
                    val values = fields.getValue("entry").asMapValue().map().mapKeys { it.key.asString() }
                    IncomingPacket.Stat(
                        requestId,
                        FileEntry(
                            name = values.getValue("name").asString(),
                            path = values.getValue("path").asString(),
                            isDirectory = values.getValue("is_dir").asBoolean(),
                            size = values.getValue("size").asLong(),
                            modifiedTs = values.getValue("modified_ts").asLong(),
                        ),
                    )
                }
                "VfsError" -> IncomingPacket.Error(
                    requestId = requestId,
                    code = fields.getValue("code").asString(),
                    message = fields.getValue("message").asString(),
                )
                "FsEvent" -> IncomingPacket.Ignored
                else -> IncomingPacket.Unexpected("unexpected VFS response: ${variant.key.asString()}")
            }
        }

        val rpc = packet.mapStringValue("Rpc")
        if (rpc != null) {
            val variant = rpc.singleEntry()
            val fields = variant.value.asMapValue().map().mapKeys { it.key.asString() }
            val bridgeId = UUID.fromString(fields.getValue("bridge_id").asString())
            return when (variant.key.asString()) {
                "Started" -> IncomingPacket.Rpc(RpcEvent.Started(bridgeId, fields.getValue("pid").asLong()))
                "Message" -> IncomingPacket.Rpc(RpcEvent.Message(bridgeId, fields.getValue("body").asString()))
                "Exited" -> IncomingPacket.Rpc(
                    RpcEvent.Exited(bridgeId, fields["code"]?.takeUnless { it.isNilValue }?.asLong()?.toInt()),
                )
                "Error" -> IncomingPacket.Rpc(RpcEvent.Error(bridgeId, fields.getValue("message").asString()))
                else -> IncomingPacket.Unexpected("unexpected RPC response: ${variant.key.asString()}")
            }
        }

        val pty = packet.mapStringValue("Pty")
        if (pty != null) {
            val fields = pty.asMapValue().map().mapKeys { it.key.asString() }
            val sessionId = UUID.fromString(fields.getValue("session_id").asString())
            val data = fields.getValue("data")
            if (data.isStringValue) return IncomingPacket.Ignored
            val variant = data.singleEntry()
            val event = when (variant.key.asString()) {
                "Spawned" -> PtyEvent.Spawned(
                    sessionId,
                    variant.value.asMapValue().map().mapKeys { it.key.asString() }.getValue("pid").asLong(),
                )
                "Output" -> PtyEvent.Output(sessionId, variant.value.asBinaryValue().asByteArray())
                "Exit" -> PtyEvent.Exit(
                    sessionId,
                    variant.value.asMapValue().map().mapKeys { it.key.asString() }["code"]
                        ?.takeUnless { it.isNilValue }?.asLong()?.toInt(),
                )
                "Error" -> PtyEvent.Error(
                    sessionId,
                    variant.value.asMapValue().map().mapKeys { it.key.asString() }.getValue("message").asString(),
                )
                else -> return IncomingPacket.Ignored
            }
            return IncomingPacket.Pty(event)
        }

        val system = packet.mapStringValue("System")
            ?: return IncomingPacket.Unexpected("packet did not contain a System or Vfs variant")
        val variant = system.singleEntry()
        return when (variant.key.asString()) {
            "Welcome" -> {
                val fields = variant.value.asMapValue().map().mapKeys { it.key.asString() }
                IncomingPacket.Welcome(
                    protocolVersion = fields.getValue("protocol_version").asLong().toInt(),
                    ptySessions = fields["pty_sessions"]?.takeUnless { it.isNilValue }?.asArrayValue()?.list()
                        ?.map { session ->
                            val values = session.asMapValue().map().mapKeys { it.key.asString() }
                            PtySessionInfo(
                                sessionId = UUID.fromString(values.getValue("session_id").asString()),
                                cwd = values["cwd"]?.takeUnless { it.isNilValue }?.asString().orEmpty(),
                                cols = values["cols"]?.asLong()?.toInt() ?: 80,
                                rows = values["rows"]?.asLong()?.toInt() ?: 24,
                            )
                        }.orEmpty(),
                    features = fields["features"]?.takeUnless { it.isNilValue }?.asArrayValue()?.list()
                        ?.map { it.asStringValue().asString() }?.toSet().orEmpty(),
                )
            }
            "CommandsProbed" -> {
                val fields = variant.value.asMapValue().map().mapKeys { it.key.asString() }
                IncomingPacket.CommandsProbed(
                    requestId = fields.getValue("request_id").asLong(),
                    paths = fields["commands"]?.asArrayValue()?.list().orEmpty().associate { command ->
                        val values = command.asMapValue().map().mapKeys { it.key.asString() }
                        values.getValue("name").asString() to values["path"]?.takeUnless { it.isNilValue }?.asString()
                    },
                )
            }
            "Rejected" -> {
                // Unit reasons arrive as a bare string, struct reasons as { Name: { fields } }.
                val reason = variant.value.asMapValue().map().entries.first { it.key.asString() == "reason" }.value
                if (reason.isStringValue) {
                    IncomingPacket.Rejected(reason.asString())
                } else {
                    val entry = reason.singleEntry()
                    val fields = entry.value.asMapValue().map().mapKeys { it.key.asString() }
                    IncomingPacket.Rejected(entry.key.asString(), fields["attempts_left"]?.asLong()?.toInt())
                }
            }
            "Pong" -> {
                val fields = variant.value.asMapValue().map().mapKeys { it.key.asString() }
                fun mb(name: String) = fields[name]?.takeUnless { it.isNilValue }?.asLong() ?: 0L
                IncomingPacket.Stats(
                    GuestStats(
                        timestamp = mb("timestamp"),
                        cpuPercent = fields["cpu_usage"]?.asFloatValue()?.toFloat() ?: 0f,
                        usedMb = mb("mem_used_mb"),
                        totalMb = mb("mem_total_mb"),
                        freeMb = mb("mem_free_mb"),
                        cachedMb = mb("mem_cached_mb"),
                        balloonMb = mb("mem_balloon_mb"),
                        diskProblem = fields["disk_problem"]?.takeUnless { it.isNilValue }?.asStringValue()?.asString(),
                        diskUsedMb = mb("disk_used_mb"),
                        diskTotalMb = mb("disk_total_mb"),
                    ),
                )
            }
            "Paired" -> IncomingPacket.Paired(
                variant.value.asMapValue().map().entries.first { it.key.asString() == "token" }.value.asString(),
            )
            "ProtocolError" -> IncomingPacket.Unexpected(variant.value.toString())
            else -> IncomingPacket.Ignored
        }
    }

    private fun packet(group: String, variant: String, fields: org.msgpack.core.MessagePacker.() -> Unit): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packMapHeader(1)
        packer.packString(group)
        packer.packMapHeader(1)
        packer.packString(variant)
        packer.fields()
        packer.close()
        return packer.toByteArray()
    }

    private fun rpcPacket(variant: String, fields: org.msgpack.core.MessagePacker.() -> Unit): ByteArray =
        packet("Rpc", variant, fields)

    private fun ptyPacket(sessionId: UUID, data: org.msgpack.core.MessagePacker.() -> Unit): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packMapHeader(1)
        packer.packString("Pty")
        packer.packMapHeader(2)
        packer.stringField("session_id", sessionId.toString())
        packer.packString("data")
        packer.data()
        packer.close()
        return packer.toByteArray()
    }

    private fun org.msgpack.core.MessagePacker.stringField(key: String, value: String) {
        packString(key)
        packString(value)
    }

    private fun org.msgpack.core.MessagePacker.stringField(key: String, value: Int) {
        packString(key)
        packInt(value)
    }

    private fun org.msgpack.core.MessagePacker.stringField(key: String, value: Long) {
        packString(key)
        packLong(value)
    }

    private fun Value.mapStringValue(key: String): Value? =
        asMapValue().map().entries.firstOrNull { it.key.asString() == key }?.value

    private fun Value.singleEntry(): Map.Entry<Value, Value> =
        asMapValue().map().entries.singleOrNull() ?: error("expected a single tagged enum variant")

    private fun Value.asLong(): Long = asIntegerValue().asLong()
    private fun Value.asString(): String = asStringValue().asString()
    private fun Value.asBoolean(): Boolean = asBooleanValue().boolean
}

/**
 * One `Pong`. Inside the guest, [usedMb] (total − available) includes the virtio balloon, which
 * Android already took back ([balloonMb]); cached pages stay resident on the host until the guest
 * drops them. So the tablet pays for programs plus cache: total − free − balloon.
 */
data class GuestStats(
    val timestamp: Long,
    val cpuPercent: Float,
    val usedMb: Long,
    val totalMb: Long,
    val freeMb: Long,
    val cachedMb: Long,
    val balloonMb: Long = 0,
    /** Set by the daemon when the guest's disk went read-only or logged an error since boot. */
    val diskProblem: String? = null,
    /** Storage on the filesystem holding the guest's home; 0 from daemons that don't report it. */
    val diskUsedMb: Long = 0,
    val diskTotalMb: Long = 0,
) {
    val footprintMb: Long get() = if (freeMb > 0) (totalMb - freeMb - balloonMb).coerceAtLeast(0) else usedMb
    /** The footprint without cache, so programs + cache + free + balloon add up to the total. */
    val programsMb: Long get() = if (freeMb > 0) (footprintMb - cachedMb).coerceAtLeast(0) else (usedMb - balloonMb).coerceAtLeast(0)
}

sealed interface IncomingPacket {
    data class Welcome(
        val protocolVersion: Int,
        val ptySessions: List<PtySessionInfo> = emptyList(),
        val features: Set<String> = emptySet(),
    ) : IncomingPacket
    /** Each probed command's path in the guest, or null when it isn't installed. */
    data class CommandsProbed(val requestId: Long, val paths: Map<String, String?>) : IncomingPacket
    data class Rejected(val reason: String, val attemptsLeft: Int? = null) : IncomingPacket
    data class Paired(val token: String) : IncomingPacket
    data class Stats(val stats: GuestStats) : IncomingPacket
    data class Search(val event: SearchEvent) : IncomingPacket
    data class Git(val event: GitEvent) : IncomingPacket
    data class ReadFile(
        val requestId: Long,
        val content: ByteArray,
        val eof: Boolean,
        val modifiedTs: Long,
        val size: Long,
    ) : IncomingPacket
    data class Ack(val requestId: Long, val modifiedTs: Long?) : IncomingPacket
    data class ListDir(val requestId: Long, val entries: List<FileEntry>) : IncomingPacket
    data class Stat(val requestId: Long, val entry: FileEntry) : IncomingPacket
    data class Roots(val requestId: Long, val roots: List<String>) : IncomingPacket
    data class Error(val requestId: Long, val code: String, val message: String) : IncomingPacket
    data class Rpc(val event: RpcEvent) : IncomingPacket
    data class Pty(val event: PtyEvent) : IncomingPacket
    data class Unexpected(val message: String) : IncomingPacket
    data object Ignored : IncomingPacket
}

sealed interface RpcEvent {
    val bridgeId: UUID

    data class Started(override val bridgeId: UUID, val pid: Long) : RpcEvent
    data class Message(override val bridgeId: UUID, val body: String) : RpcEvent
    data class Exited(override val bridgeId: UUID, val code: Int?) : RpcEvent
    data class Error(override val bridgeId: UUID, val message: String) : RpcEvent
}

data class PtySessionInfo(val sessionId: UUID, val cwd: String, val cols: Int, val rows: Int)

sealed interface PtyEvent {
    val sessionId: UUID

    data class Spawned(override val sessionId: UUID, val pid: Long) : PtyEvent
    data class Output(override val sessionId: UUID, val data: ByteArray) : PtyEvent
    data class Exit(override val sessionId: UUID, val code: Int?) : PtyEvent
    data class Error(override val sessionId: UUID, val message: String) : PtyEvent
}

data class FileEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val modifiedTs: Long,
)
