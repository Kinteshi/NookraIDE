package io.github.kinteshi.quirecode.workbench

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.kinteshi.quirecode.BuildConfig

/**
 * The debug build is its own app ("Nookra IDE Dev", `.dev`) installed beside the release one. So the two aren't
 * mistaken for each other, the dev app defaults to its own orange palette instead of the wallpaper's colours, has an
 * orange launcher icon, and shows a DEV badge by its name.
 */
object DevBuild {
    val enabled: Boolean = BuildConfig.DEV_BUILD
    val appName: String = if (enabled) "Nookra IDE Dev" else "Nookra IDE"

    /** Settings the dev app starts from, under the user's: the dev palette instead of dynamic colour. */
    val defaultSettings: String = if (enabled) """{"avf.theme.dynamicColor":false}""" else "{}"
}

/** A small DEV badge beside the app's name, in the dev build only. */
@Composable
fun DevBadge(modifier: Modifier = Modifier) {
    if (!DevBuild.enabled) return
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.primary, contentColor = colors.onPrimary, shape = MaterialTheme.shapes.small, modifier = modifier) {
        Text("DEV", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp))
    }
}
