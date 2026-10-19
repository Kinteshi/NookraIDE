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

    fun rpcStart(bridgeId: UUID, tool: String, args: List<String>, cwd: String?): ByteArray =
        rpcPacket("Start") {
            packMapHeader(6)
            stringField("bridge_id", bridgeId.toString())
            stringField("tool", tool)
            stringField("kind", "Lsp")
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

    fun decode(bytes: ByteArray): IncomingPacket {
        val packet = MessagePack.newDefaultUnpacker(bytes).use { it.unpackValue() }
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

        val system = packet.mapStringValue("System")
            ?: return IncomingPacket.Unexpected("packet did not contain a System or Vfs variant")
        val variant = system.singleEntry()
        return when (variant.key.asString()) {
            "Welcome" -> IncomingPacket.Welcome(
                protocolVersion = variant.value.asMapValue().map().mapKeys { it.key.asString() }
                    .getValue("protocol_version").asLong().toInt(),
            )
            "Rejected" -> IncomingPacket.Rejected(variant.value.toString())
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

sealed interface IncomingPacket {
    data class Welcome(val protocolVersion: Int) : IncomingPacket
    data class Rejected(val reason: String) : IncomingPacket
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

data class FileEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val modifiedTs: Long,
)
