package com.avfstudio.nativeide.workbench

import com.avfstudio.nativeide.ai.AcpEvent
import com.avfstudio.nativeide.ai.AcpSessionInfo
import com.avfstudio.nativeide.ai.parseSessionList
import com.avfstudio.nativeide.ai.parseSessionUpdate
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class ChatHistoryTest {
    @Test
    fun sessionListPagesReadTheirSessionsAndCursor() {
        // Copilot CLI 1.0.105's answer, shortened.
        val (sessions, next) = parseSessionList(
            JsonParser.parseString(
                """{"sessions":[{"sessionId":"a","cwd":"/home/droid/Projects","title":"Reply OK","updatedAt":"2026-10-28T17:22:56.103Z"},
                   {"sessionId":"b","cwd":"/home/droid/Projects"}],"nextCursor":"c2"}""",
            ),
        )
        assertEquals(listOf(AcpSessionInfo("a", "Reply OK", "/home/droid/Projects", "2026-10-28T17:22:56.103Z"), AcpSessionInfo("b", null, "/home/droid/Projects", null)), sessions)
        assertEquals("c2", next)
        assertEquals(emptyList<AcpSessionInfo>() to null, parseSessionList(JsonParser.parseString("null")))
    }

    @Test
    fun rowsAreNewestFirstWithoutRemovedOnesAndUseTheAppsNames() {
        val rows = chatSessionRows(
            listOf(
                AcpSessionInfo("old", "Old", null, "2026-10-27T10:00:00Z"),
                AcpSessionInfo("new", " ", null, "2026-10-28T10:00:00Z"),
                AcpSessionInfo("gone", "Gone", null, "2026-10-28T11:00:00Z"),
            ),
            hidden = setOf("gone"),
            nameOf = { if (it == "old") "Renamed" else null },
            current = "old",
        )
        assertEquals(listOf("Untitled chat", "Renamed"), rows.map { it.title })
        assertEquals(listOf(false, true), rows.map { it.current })
        assertEquals("Old", rows.last().agentTitle)
    }

    @Test
    fun agesReadLikeVsCodesHistory() {
        val now = Instant.parse("2026-10-28T20:00:00Z")
        fun age(at: String) = chatSessionAge(at, now, ZoneOffset.UTC)
        assertEquals("Just now", age("2026-10-28T19:59:40Z"))
        assertEquals("12 min ago", age("2026-10-28T19:48:00Z"))
        assertEquals("3 h ago", age("2026-10-28T17:00:00Z"))
        assertEquals("Yesterday", age("2026-10-27T23:00:00Z"))
        assertEquals("", chatSessionAge(null, now, ZoneOffset.UTC))
    }

    @Test
    fun replayedUserMessagesBecomeUserChunks() {
        val update = JsonParser.parseString("""{"sessionUpdate":"user_message_chunk","content":{"type":"text","text":"hi"}}""").asJsonObject
        assertEquals(AcpEvent.UserMessageChunk("hi"), parseSessionUpdate(update))
    }
}
