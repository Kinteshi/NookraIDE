package io.github.kinteshi.quirecode.workbench

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/**
 * The app's banner (R8: one look for all of them): a rounded card with an icon, the message, and actions that wrap below
 * the text when narrow. The error container for problems, [tonal] (the secondary container) for news and offers, or
 * [quiet] (a surface container) for a state that isn't a problem to fix now, as the connection being away (G22).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WorkbenchBanner(
    icon: ImageVector,
    message: String,
    tonal: Boolean = false,
    quiet: Boolean = false,
    textStyle: TextStyle? = null,
    actions: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = when {
            quiet -> colors.surfaceContainerHigh
            tonal -> colors.secondaryContainer
            else -> colors.errorContainer
        },
        contentColor = when {
            quiet -> colors.onSurface
            tonal -> colors.onSecondaryContainer
            else -> colors.onErrorContainer
        },
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        FlowRow(
            Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
            verticalArrangement = Arrangement.Center,
        ) {
            Row(
                Modifier.weight(1f).widthIn(min = 280.dp).padding(vertical = 6.dp).align(Alignment.CenterVertically),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(message, style = textStyle ?: MaterialTheme.typography.bodyMedium)
            }
            Row(Modifier.align(Alignment.CenterVertically), verticalAlignment = Alignment.CenterVertically) { actions() }
        }
    }
}
