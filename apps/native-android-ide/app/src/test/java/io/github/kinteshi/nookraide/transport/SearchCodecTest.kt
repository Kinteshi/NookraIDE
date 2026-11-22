package io.github.kinteshi.nookraide.transport

import org.junit.Assert.assertEquals
import org.junit.Test
import org.msgpack.core.MessagePack

class SearchCodecTest {
    private fun match(preview: String, col: Int, len: Int): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packMapHeader(1)
        packer.packString("Search")
        packer.packMapHeader(1)
        packer.packString("Match")
        packer.packMapHeader(8)
        packer.packString("req_id"); packer.packLong(7)
        packer.packString("path"); packer.packString("/w/a.py")
        packer.packString("line"); packer.packInt(3)
        packer.packString("col"); packer.packInt(col)
        packer.packString("len"); packer.packInt(len)
        packer.packString("preview"); packer.packString(preview)
        packer.packString("context_before"); packer.packArrayHeader(0)
        packer.packString("context_after"); packer.packArrayHeader(0)
        return packer.toByteArray()
    }

    @Test
    fun convertsUtf8ByteColumnsToCharacters() {
        // "ação = 1": "ação" is 6 UTF-8 bytes but 4 chars; the match "= 1" starts at byte 7
        // (column 8: the daemon passes on ripgrep's 1-based column).
        val event = (MessagePackVfsCodec.decode(match("ação = 1", 8, 3)) as IncomingPacket.Search).event
        assertEquals(SearchEvent.Match(7, "/w/a.py", 3, 5, 3, "ação = 1"), event)
        val accented = (MessagePackVfsCodec.decode(match("x = ação", 5, 6)) as IncomingPacket.Search).event as SearchEvent.Match
        assertEquals(4, accented.column)
        assertEquals(4, accented.length)
    }

    @Test
    fun encodesTheStartRequestWithGlobs() {
        val bytes = MessagePackVfsCodec.searchStart(9, SearchQuery("todo", listOf("/w"), include = listOf("*.py")))
        val fields = MessagePack.newDefaultUnpacker(bytes).unpackValue().asMapValue().map().values.first()
            .asMapValue().map().values.first().asMapValue().map().entries
            .associate { (key, value) -> key.asStringValue().asString() to value }
        assertEquals("todo", fields.getValue("query").asStringValue().asString())
        assertEquals(listOf("*.py"), fields.getValue("include").asArrayValue().list().map { it.asStringValue().asString() })
        assertEquals(10, fields.size)
    }

    @Test
    fun encodesFindFilesAndDecodesFilesFound() {
        val bytes = MessagePackVfsCodec.findFiles(11, "/w", "lib.rs", listOf("target"), maxResults = 200)
        val variant = MessagePack.newDefaultUnpacker(bytes).unpackValue().asMapValue().map().values.first()
            .asMapValue().map().entries.first()
        assertEquals("FindFilesReq", variant.key.asStringValue().asString())
        val fields = variant.value.asMapValue().map().entries
            .associate { (key, value) -> key.asStringValue().asString() to value }
        assertEquals("/w", fields.getValue("root").asStringValue().asString())
        assertEquals("lib.rs", fields.getValue("query").asStringValue().asString())
        assertEquals(listOf("target"), fields.getValue("exclude").asArrayValue().list().map { it.asStringValue().asString() })
        assertEquals(200, fields.getValue("max_results").asIntegerValue().asInt())

        val packer = MessagePack.newDefaultBufferPacker()
        packer.packMapHeader(1)
        packer.packString("Search")
        packer.packMapHeader(1)
        packer.packString("FilesFound")
        packer.packMapHeader(4)
        packer.packString("req_id"); packer.packLong(11)
        packer.packString("paths"); packer.packArrayHeader(1); packer.packString("/w/src/lib.rs")
        packer.packString("truncated"); packer.packBoolean(true)
        packer.packString("elapsed_ms"); packer.packInt(14)
        val event = (MessagePackVfsCodec.decode(packer.toByteArray()) as IncomingPacket.Search).event
        assertEquals(SearchEvent.FilesFound(11, listOf("/w/src/lib.rs"), truncated = true, elapsedMs = 14), event)
    }
}
