package io.github.kinteshi.quirecode.workbench

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** The identifier at [column] of [line] (or just before it), for the Docs tab's title; null off one. */
internal fun wordAt(line: String, column: Int): String? {
    fun part(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'
    var start = column.coerceIn(0, line.length)
    if ((start == line.length || !part(line[start])) && start > 0 && part(line[start - 1])) start--
    if (start >= line.length || !part(line[start])) return null
    var end = start
    while (start > 0 && part(line[start - 1])) start--
    while (end < line.length && part(line[end])) end++
    return line.substring(start, end)
}

/** A problem as the hover card lists it: VS Code's "message  source(code)". */
data class HoverProblem(val severity: Int?, val message: String, val origin: String)

fun severityIcon(severity: Int?): ImageVector = when (severity) {
    1, null -> Icons.Outlined.ErrorOutline
    2 -> Icons.Outlined.WarningAmber
    else -> Icons.Outlined.Info
}

/**
 * The hover card's body: the problems at the place, then the language server's Markdown; links resolve from [path].
 * With [onExpand], a button in its corner shows it whole in the panel's Docs tab (G20).
 */
@Composable
internal fun HoverCardContent(
    controller: WorkbenchController,
    path: String,
    problems: List<HoverProblem>,
    markdown: String?,
    onExpand: (() -> Unit)? = null,
) {
    Box {
        HoverCardBody(controller, path, problems, markdown, Modifier.padding(end = if (onExpand != null) 28.dp else 0.dp))
        if (onExpand != null) {
            Box(Modifier.align(Alignment.TopEnd).padding(2.dp)) {
                Tooltip("Show in the panel") {
                    IconButton(onClick = onExpand, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Outlined.OpenInFull, contentDescription = "Show in the panel", modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun HoverCardBody(controller: WorkbenchController, path: String, problems: List<HoverProblem>, markdown: String?, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        problems.forEach { problem ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(
                    severityIcon(problem.severity),
                    contentDescription = null,
                    tint = colors.problemColor(problem.severity),
                    modifier = Modifier.size(16.dp).padding(top = 2.dp),
                )
                Text(
                    problem.message + problem.origin.takeIf { it.isNotEmpty() }?.let { "  $it" }.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurface,
                )
            }
        }
        if (problems.isNotEmpty() && markdown != null) HorizontalDivider(color = colors.outlineVariant)
        markdown?.let {
            val source = remember(path) { controller.markdownSource(path, null) }
            NotebookMarkdown(controller, it, source)
        }
    }
}

/** The panel's Docs tab: an expanded hover card in full, scrolling, until it's closed or another replaces it. */
@Composable
internal fun DocsView(controller: WorkbenchController) {
    val docs = controller.expandedDocs ?: return
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                docs.title,
                style = MaterialTheme.typography.labelLarge,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Tooltip("Close") {
                IconButton(onClick = controller::closeDocs) { Icon(Icons.Outlined.Close, contentDescription = "Close", modifier = Modifier.size(18.dp)) }
            }
        }
        HoverCardBody(controller, docs.path, emptyList(), docs.markdown, Modifier.fillMaxWidth().padding(horizontal = 4.dp))
    }
}

