package com.avfstudio.nativeide.transport

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the exact bytes the app sends for hand-encoded requests. The same hex strings are decoded
 * by the daemon's protocol test `app_encoded_requests_decode` (crates/avf-protocol), so a change on
 * either side that breaks the wire format fails a test.
 */
class WireFixturesTest {
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun gitRequestsMatchTheDaemonFixtures() {
        assertEquals(GIT_STATUS, hex(MessagePackVfsCodec.gitRequest(1, "/w", GitOperation.Status)))
        assertEquals(GIT_STAGE, hex(MessagePackVfsCodec.gitRequest(2, "/w", GitOperation.Stage(listOf("a.py")))))
        assertEquals(GIT_COMMIT, hex(MessagePackVfsCodec.gitRequest(3, "/w", GitOperation.Commit("msg"))))
        assertEquals(GIT_PUSH, hex(MessagePackVfsCodec.gitRequest(4, "/w", GitOperation.Push(setUpstream = true))))
        assertEquals(GIT_LOG, hex(MessagePackVfsCodec.gitRequest(5, "/w", GitOperation.Log(limit = 10))))
    }

    @Test
    fun searchAndPairRequestsMatchTheDaemonFixtures() {
        assertEquals(SEARCH_START, hex(MessagePackVfsCodec.searchStart(6, SearchQuery("todo", listOf("/w"), include = listOf("*.py")))))
        assertEquals(PAIR, hex(MessagePackVfsCodec.pair("ABCD-EFGH")))
    }

    private fun bytes(hex: String) = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun daemonRepliesDecode() {
        val status = (MessagePackVfsCodec.decode(bytes(GIT_STATUS_RESULT)) as IncomingPacket.Git).event as GitEvent.Result
        val result = status.result as GitResult.Status
        assertEquals("main", result.branch)
        assertEquals(1, result.ahead)
        assertEquals(GitStatusEntry("src/a.py", null, "M", ".", GitFileStatus.Modified), result.entries.single())
        assertEquals(true, result.entries.single().staged)
        assertEquals(false, result.entries.single().unstaged)
        assertEquals(GitEvent.Result(2, GitResult.Success), (MessagePackVfsCodec.decode(bytes(GIT_SUCCESS)) as IncomingPacket.Git).event)
        assertEquals(
            GitEvent.Prompt(3, 9, "Password for 'https://github.com': ", true),
            (MessagePackVfsCodec.decode(bytes(GIT_PROMPT)) as IncomingPacket.Git).event,
        )
        assertEquals(
            // The wire's col is ripgrep's 1-based byte column; the app's column is 0-based.
            SearchEvent.Match(4, "/w/a.py", 3, 1, 4, "x todo"),
            (MessagePackVfsCodec.decode(bytes(SEARCH_MATCH)) as IncomingPacket.Search).event,
        )
    }

    companion object {
        const val GIT_STATUS_RESULT = "81a347697481a352657382a67265715f696401a6726573756c7481a653746174757385a66272616e6368a46d61696ea8757073747265616dc0a5616865616401a6626568696e6400a7656e74726965739185a470617468a87372632f612e7079ad6f726967696e616c5f70617468c0ac696e6465785f737461747573a14daf776f726b747265655f737461747573a12ea6737461747573a84d6f646966696564"
        const val GIT_SUCCESS = "81a347697481a352657382a67265715f696402a6726573756c74a753756363657373"
        const val GIT_PROMPT = "81a347697481a650726f6d707484a67265715f696403a970726f6d70745f696409a474657874d92350617373776f726420666f72202768747470733a2f2f6769746875622e636f6d273a20a6736563726574c3"
        const val SEARCH_MATCH = "81a653656172636881a54d6174636888a67265715f696404a470617468a72f772f612e7079a46c696e6503a3636f6c02a36c656e04a770726576696577a67820746f646fae636f6e746578745f6265666f726590ad636f6e746578745f616674657290"

        // Requests: regenerate by printing hex(...) above; replies: from the daemon's
        // daemon_encoded_replies_are_stable test. Keep crates/avf-protocol's copies in sync.
        const val GIT_STATUS = "81a347697481a352657183a67265715f696401a47265706fa22f77a26f70a6537461747573"
        const val GIT_STAGE = "81a347697481a352657183a67265715f696402a47265706fa22f77a26f7081a5537461676591a4612e7079"
        const val GIT_COMMIT = "81a347697481a352657183a67265715f696403a47265706fa22f77a26f7081a6436f6d6d697483a76d657373616765a36d7367a5616d656e64c2a77369676e6f6666c2"
        const val GIT_PUSH = "81a347697481a352657183a67265715f696404a47265706fa22f77a26f7081a45075736882ac7365745f757073747265616dc3b0666f7263655f776974685f6c65617365c2"
        const val GIT_LOG = "81a347697481a352657183a67265715f696405a47265706fa22f77a26f7081a34c6f6783a3726576c0a56c696d69740aa470617468c0"
        const val SEARCH_START = "81a653656172636881a853746172745265718aa67265715f696406a5726f6f747391a22f77a57175657279a4746f646fa57265676578c2ae636173655f73656e736974697665c2aa77686f6c655f776f7264c2a7696e636c75646591a42a2e7079a76578636c75646590ab6d61785f726573756c7473cd07d0ad636f6e746578745f6c696e657300"
        const val PAIR = "81a653797374656d81a45061697283b070726f746f636f6c5f76657273696f6e02a4636f6465a9414243442d45464748ab636c69656e745f6e616d65b66176662d6e61746976652d616e64726f69642d696465"
    }
}
