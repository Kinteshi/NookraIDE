package io.github.kinteshi.quirecode.transport

/** Options of a workspace search, mirroring the daemon's `SearchMessage::StartReq`. */
data class SearchQuery(
    val query: String,
    val roots: List<String>,
    val regex: Boolean = false,
    val caseSensitive: Boolean = false,
    val wholeWord: Boolean = false,
    val include: List<String> = emptyList(),
    val exclude: List<String> = emptyList(),
    val maxResults: Int = 2_000,
    val contextLines: Int = 0,
)

/** Streamed search output; [Match] columns are 0-based UTF-8 byte offsets turned into chars. */
sealed interface SearchEvent {
    val requestId: Long

    data class Match(
        override val requestId: Long,
        val path: String,
        val line: Int,
        val column: Int,
        val length: Int,
        val preview: String,
    ) : SearchEvent

    data class Done(override val requestId: Long, val total: Int, val truncated: Boolean, val elapsedMs: Int) : SearchEvent
    data class Error(override val requestId: Long, val message: String) : SearchEvent
}
