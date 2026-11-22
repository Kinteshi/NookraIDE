package io.github.kinteshi.quirecode.workbench

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A component the app ships, from assets/licenses/notices.json (scripts/third-party-notices.py). */
internal data class ThirdPartyComponent(
    val group: String,
    val name: String,
    val title: String,
    val version: String?,
    val license: String,
    val url: String?,
    val text: String,
)

internal fun loadThirdPartyNotices(context: Context): List<ThirdPartyComponent> {
    val root = context.assets.open("licenses/notices.json").use { JsonParser.parseReader(it.reader()) }.asJsonObject
    val texts = root.getAsJsonObject("texts")
    return root.getAsJsonArray("components").map { element ->
        val component = element.asJsonObject
        fun string(name: String) = component.get(name)?.takeUnless { it.isJsonNull }?.asString
        ThirdPartyComponent(
            group = string("group").orEmpty(),
            name = string("name").orEmpty(),
            title = string("title") ?: string("name").orEmpty(),
            version = string("version"),
            license = string("license").orEmpty(),
            url = string("url"),
            text = texts.get(string("text"))?.asString.orEmpty(),
        )
    }
}

/** Settings > About > Open-source licences: every component the app ships, by group, and a component's licence text. */
@Composable
internal fun LicensesDialog(controller: WorkbenchController) {
    val context = LocalContext.current
    val components by produceState<List<ThirdPartyComponent>?>(null) {
        value = withContext(Dispatchers.IO) { runCatching { loadThirdPartyNotices(context) }.getOrDefault(emptyList()) }
    }
    var shown by remember { mutableStateOf<ThirdPartyComponent?>(null) }
    var filter by remember { mutableStateOf("") }
    val colors = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = { if (shown != null) shown = null else controller.dialog = null },
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.padding(24.dp).fillMaxWidth().heightIn(max = 760.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                shown?.let {
                    IconButton(onClick = { shown = null }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
                }
                Text(shown?.title ?: "Open-source licences")
            }
        },
        text = {
            val current = shown
            when {
                current != null -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        listOfNotNull(current.name, current.version, current.license).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                    current.url?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.primary) }
                    Text(current.text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
                components == null -> Text("Loading…")
                else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Nookra IDE is licensed under the Apache License 2.0. It includes the work of others, each under its own licence:",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = filter,
                        onValueChange = { filter = it },
                        placeholder = { Text("Filter") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val visible = components.orEmpty().filter {
                        filter.isBlank() || it.title.contains(filter, true) || it.name.contains(filter, true) || it.license.contains(filter, true)
                    }
                    LazyColumn(Modifier.fillMaxWidth()) {
                        visible.groupBy { it.group }.forEach { (group, inGroup) ->
                            item(key = group) {
                                Text(
                                    "$group (${inGroup.size})",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = colors.primary,
                                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                                )
                            }
                            items(inGroup, key = { "$group/${it.name}/${it.version}" }) { component ->
                                Column(Modifier.fillMaxWidth().clickable { shown = component }.padding(vertical = 6.dp)) {
                                    Text(component.title, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        listOfNotNull(component.version, component.license).joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { controller.dialog = null }) { Text("Close") } },
    )
}
