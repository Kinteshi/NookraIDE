package io.github.kinteshi.nookraide.workbench

import io.github.kinteshi.nookraide.ai.AcpSessionInfo
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A past chat as the history lists it: the app's name for it if renamed, else the agent's title. */
data class ChatSessionRow(
    val id: String,
    val title: String,
    val agentTitle: String,
    val updatedAt: String?,
    /** The chat the pane shows now. */
    val current: Boolean,
)

/** Newest first, without the chats removed from the list; untitled ones get a placeholder. */
fun chatSessionRows(
    sessions: List<AcpSessionInfo>,
    hidden: Set<String>,
    nameOf: (String) -> String?,
    current: String?,
): List<ChatSessionRow> =
    sessions.filterNot { it.id in hidden }
        .sortedByDescending { it.updatedAt.orEmpty() }
        .map { session ->
            val agentTitle = session.title?.trim()?.takeIf { it.isNotEmpty() } ?: "Untitled chat"
            ChatSessionRow(session.id, nameOf(session.id) ?: agentTitle, agentTitle, session.updatedAt, session.id == current)
        }

/** "Just now", "12 min ago", "3 h ago", "Yesterday", or the date for older chats. */
fun chatSessionAge(updatedAt: String?, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String {
    val time = updatedAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return ""
    val age = Duration.between(time, now)
    return when {
        age.toMinutes() < 1 -> "Just now"
        age.toMinutes() < 60 -> "${age.toMinutes()} min ago"
        age.toHours() < 24 && time.atZone(zone).toLocalDate() == now.atZone(zone).toLocalDate() -> "${age.toHours()} h ago"
        time.atZone(zone).toLocalDate() == now.atZone(zone).toLocalDate().minusDays(1) -> "Yesterday"
        else -> DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()).withZone(zone).format(time)
    }
}
