package com.avfstudio.nativeide.workbench

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatPaneTest {
    @Test
    fun toolStatesFollowAcpStatuses() {
        assertEquals(ChatToolState.Running, chatToolState("pending"))
        assertEquals(ChatToolState.Running, chatToolState("in_progress"))
        assertEquals(ChatToolState.Done, chatToolState("completed"))
        assertEquals(ChatToolState.Failed, chatToolState("failed"))
        assertEquals(ChatToolState.Other, chatToolState(null))
    }

    @Test
    fun thoughtsLoseTheirMarks() {
        assertEquals("Generating lines", chatThoughtText("**Generating lines**\n\n…"))
        assertEquals("Title\n\nWhy", chatThoughtText("**Title**\n\nWhy"))
    }

    @Test
    fun wordsRankBeforeScatteredLetters() {
        val titles = listOf("Configure Workspace Snippets", "View: Reset Pane Sizes", "Reset Zoom", "Preset List")
        assertEquals(
            listOf("Reset Zoom", "View: Reset Pane Sizes", "Preset List", "Configure Workspace Snippets"),
            titles.filter { fuzzyMatch("reset", it) }.sortedBy { matchRank("reset", it) },
        )
    }

    @Test
    fun optionsKeepModelThenModeThenTheRest() {
        val ids = listOf("allow_all", "mode", "model")
        assertEquals(listOf("model", "mode", "allow_all"), ids.sortedBy(::chatOptionRank))
    }
}
