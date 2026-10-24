package com.avfstudio.nativeide.transport

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
        // "ação = 1": "ação" is 6 UTF-8 bytes but 4 chars; the match "= 1" starts at byte 7.
        val event = (MessagePackVfsCodec.decode(match("ação = 1", 7, 3)) as IncomingPacket.Search).event
        assertEquals(SearchEvent.Match(7, "/w/a.py", 3, 5, 3, "ação = 1"), event)
        val accented = (MessagePackVfsCodec.decode(match("x = ação", 4, 6)) as IncomingPacket.Search).event as SearchEvent.Match
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
}
