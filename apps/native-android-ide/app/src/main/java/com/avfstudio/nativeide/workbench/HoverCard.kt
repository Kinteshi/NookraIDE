package com.avfstudio.nativeide.workbench

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** A problem as the hover card lists it: VS Code's "message  source(code)". */
data class HoverProblem(val severity: Int?, val message: String, val origin: String)

fun severityIcon(severity: Int?): ImageVector = when (severity) {
    1, null -> Icons.Outlined.ErrorOutline
    2 -> Icons.Outlined.WarningAmber
    else -> Icons.Outlined.Info
}

/** The hover card's body: the problems at the place, then the language server's Markdown; links resolve from [path]. */
@Composable
internal fun HoverCardContent(controller: WorkbenchController, path: String, problems: List<HoverProblem>, markdown: String?) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
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
