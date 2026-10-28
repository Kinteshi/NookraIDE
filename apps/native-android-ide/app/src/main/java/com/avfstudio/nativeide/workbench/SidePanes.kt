package com.avfstudio.nativeide.workbench

import androidx.compose.foundation.background
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.FilterChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Functions
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.avfstudio.nativeide.language.LanguageDiagnostic

@Composable
fun SidebarContent(controller: WorkbenchController, view: SidebarView, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(view.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (view == SidebarView.Explorer && controller.connected) {
                IconButton(onClick = { controller.beginNewEntry(folder = false) }) {
                    Icon(Icons.AutoMirrored.Outlined.NoteAdd, contentDescription = "New file")
                }
                IconButton(onClick = { controller.beginNewEntry(folder = true) }) {
                    Icon(Icons.Outlined.CreateNewFolder, contentDescription = "New folder")
                }
            }
            if (view == SidebarView.Explorer) {
                IconButton(onClick = { controller.currentDirectory.takeIf(String::isNotEmpty)?.let(controller::loadDirectory) }) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "Refresh")
                }
            }
        }
        when (view) {
            SidebarView.Explorer -> ExplorerList(controller)
            SidebarView.Search -> SearchView(controller)
            SidebarView.SourceControl -> SourceControlView(controller)
            SidebarView.Outline -> OutlineList(controller)
            SidebarView.Problems -> ProblemsList(controller)
            SidebarView.References -> ReferencesList(controller)
        }
    }
}

@Composable
fun ExplorerList(controller: WorkbenchController) {
    val directory = controller.currentDirectory
    when {
        !controller.connected && controller.roots.isEmpty() -> EmptyMessage("Connect to a guest to browse its workspace.")
        directory.isEmpty() -> LazyColumn {
            items(controller.roots) { root ->
                PaneRow(Icons.Outlined.Folder, root.substringAfterLast('/').ifEmpty { root }) { controller.loadDirectory(root) }
            }
        }
        else -> LazyColumn {
            item {
                val parent = directory.substringBeforeLast('/', "")
                val atRoot = controller.roots.any { it.trimEnd('/') == directory.trimEnd('/') }
                PaneRow(Icons.AutoMirrored.Outlined.ArrowBack, if (atRoot) directory else "..", muted = true) {
                    if (!atRoot && parent.isNotEmpty()) controller.loadDirectory(parent)
                }
            }
            items(controller.entries, key = { it.path }) { entry ->
                var menu by remember { mutableStateOf(false) }
                Box {
                    PaneRow(
                        when {
                            entry.isDirectory -> Icons.Outlined.Folder
                            entry.name.endsWith(".ipynb") -> Icons.Outlined.Code
                            else -> Icons.AutoMirrored.Outlined.InsertDriveFile
                        },
                        entry.name,
                        selected = entry.path == controller.activePath,
                        onLongClick = { menu = true },
                    ) {
                        if (entry.isDirectory) controller.loadDirectory(entry.path) else controller.openPath(entry.path)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (entry.isDirectory) {
                            DropdownMenuItem(
                                text = { Text("New file here") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.NoteAdd, contentDescription = null) },
                                onClick = { menu = false; controller.beginNewEntry(folder = false, parent = entry.path) },
                            )
                        }
                        if (!entry.isDirectory) {
                            DropdownMenuItem(
                                text = { Text("Add to Copilot chat") },
                                leadingIcon = { Icon(Icons.Outlined.AutoAwesome, contentDescription = null) },
                                onClick = { menu = false; controller.attachFileToChat(entry.path) },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, contentDescription = null) },
                            onClick = { menu = false; controller.beginRenameEntry(entry.path) },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                            onClick = { menu = false; controller.beginDeleteEntry(entry.path, entry.isDirectory) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OutlineList(controller: WorkbenchController) {
    val document = controller.activeDocument
    val rows = remember(controller.outlineSymbols) { flattenOutline(controller.outlineSymbols) }
    val showProblems = controller.settings.outlineProblems
    val path = document?.path
    val fileDiagnostics = controller.diagnostics.filter { path != null && runCatching { java.net.URI(it.uri).path }.getOrNull() == path }
    val problems = if (showProblems) outlineProblems(rows, fileDiagnostics) else rows.map { null }
    var problemsOnly by remember { mutableStateOf(false) }
    val visible = if (showProblems && problemsOnly) rowsWithProblems(rows, problems) else rows.indices.toList()
    when {
        document == null -> EmptyMessage("Open a file to show its symbols.")
        rows.isEmpty() -> EmptyMessage("No symbols found.")
        else -> Column {
            if (showProblems && fileDiagnostics.isNotEmpty()) {
                Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = problemsOnly,
                        onClick = { problemsOnly = !problemsOnly },
                        label = { Text("Problems only · ${fileDiagnostics.size}") },
                        leadingIcon = { Icon(Icons.Outlined.FilterList, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    )
                }
            }
            LazyColumn {
                items(visible) { index ->
                    val (symbol, depth) = rows[index]
                    PaneRow(
                        when (symbol.kind) {
                            5, 10, 11, 23 -> Icons.Outlined.DataObject
                            6, 9, 12 -> Icons.Outlined.Functions
                            else -> Icons.Outlined.Code
                        },
                        symbol.name,
                        indent = depth,
                        trailing = problems[index]?.let { problem -> { ProblemBadge(problem) } },
                    ) {
                        val uri = java.net.URI("file", "", document.path, null).toASCIIString()
                        controller.navigateToLocation(LanguageLocation(uri, symbol.range))
                    }
                }
            }
        }
    }
}

@Composable
fun ProblemsList(controller: WorkbenchController) {
    val issues = controller.languageServerIssues
    val diagnostics = controller.diagnostics
    if (issues.isEmpty() && diagnostics.isEmpty()) {
        EmptyMessage("No problems have been detected in the open files.")
        return
    }
    val groups = problemsByFile(diagnostics)
    // Files the user folded; new files start open.
    val collapsed = remember { mutableStateMapOf<String, Boolean>() }
    LazyColumn {
        items(issues) { issue ->
            ProblemRow(Icons.Outlined.ErrorOutline, MaterialTheme.colorScheme.error, "${issue.server} unavailable", issue.message) {}
        }
        groups.forEach { (uri, items) ->
            val folded = collapsed[uri] == true
            item(key = "file:$uri") {
                PaneRow(
                    if (folded) Icons.AutoMirrored.Outlined.KeyboardArrowRight else Icons.Outlined.ExpandMore,
                    uri.substringAfterLast('/'),
                    trailing = { ProblemBadge(ProblemCount(items.size, items.minOf { it.severity ?: 1 })) },
                ) { collapsed[uri] = !folded }
            }
            if (!folded) {
                items(items) { diagnostic -> DiagnosticRow(diagnostic) { controller.navigateToLocation(LanguageLocation(diagnostic.uri, diagnostic.range)) } }
            }
        }
    }
}

@Composable
private fun DiagnosticRow(diagnostic: LanguageDiagnostic, onClick: () -> Unit) {
    val (icon, tint) = severityStyle(diagnostic.severity)
    val line = diagnostic.range.getAsJsonObject("start")?.get("line")?.asInt?.plus(1)
    ProblemRow(
        icon,
        tint,
        diagnostic.message.lineSequence().first(),
        "${diagnostic.uri.substringAfterLast('/')}${line?.let { ":$it" }.orEmpty()}${diagnostic.source?.let { " · $it" }.orEmpty()}",
        onClick,
    )
}

@Composable
private fun ProblemRow(icon: ImageVector, tint: androidx.compose.ui.graphics.Color, title: String, detail: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp).padding(top = 2.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ReferencesList(controller: WorkbenchController) {
    val locations = controller.referenceLocations
    if (locations.isEmpty()) {
        EmptyMessage("Run Find All References (Shift+F12) on a symbol.")
        return
    }
    LazyColumn {
        items(locations) { location ->
            val line = location.range.getAsJsonObject("start")?.get("line")?.asInt?.plus(1) ?: 1
            PaneRow(Icons.AutoMirrored.Outlined.InsertDriveFile, "${location.uri.substringAfterLast('/')}:$line") {
                controller.navigateToLocation(location)
            }
        }
    }
}

@Composable
fun OutputLog(controller: WorkbenchController) {
    val lines = controller.outputLog
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), reverseLayout = true) {
        items(lines.asReversed()) { line ->
            Text(
                line,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 1.dp),
            )
        }
    }
}

@Composable
fun PaneRow(
    icon: ImageVector,
    label: String,
    muted: Boolean = false,
    selected: Boolean = false,
    indent: Int = 0,
    onLongClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp)
            .background(if (selected) colors.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent, MaterialTheme.shapes.small)
            .then(if (onLongClick != null) Modifier.onSecondaryClick(onLongClick) else Modifier)
            .combinedClickable(onLongClick = onLongClick, onClick = onClick)
            .padding(start = (10 + indent * 14).dp, end = 10.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
        )
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = when {
                selected -> colors.onSecondaryContainer
                muted -> colors.onSurfaceVariant
                else -> colors.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = if (trailing != null) Modifier.weight(1f) else Modifier,
        )
        trailing?.invoke()
    }
}

/** The colour and icon of an LSP severity (1 error, 2 warning, else information). */
@Composable
private fun severityStyle(severity: Int?): Pair<ImageVector, androidx.compose.ui.graphics.Color> {
    val colors = MaterialTheme.colorScheme
    return when (severity) {
        1, null -> Icons.Outlined.ErrorOutline to colors.error
        2 -> Icons.Outlined.WarningAmber to colors.tertiary
        else -> Icons.Outlined.Info to colors.primary
    }
}

/** A problem count beside an outline entry, in the colour of its worst problem. */
@Composable
private fun ProblemBadge(problem: ProblemCount) {
    val (_, tint) = severityStyle(problem.worst)
    Text(
        problem.count.toString(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .background(tint, androidx.compose.foundation.shape.CircleShape)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

@Composable
fun EmptyMessage(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp),
    )
}

@Composable
fun PaneDivider() = HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

internal fun flattenOutline(symbols: List<OutlineSymbol>): List<Pair<OutlineSymbol, Int>> =
    buildList {
        fun append(items: List<OutlineSymbol>, depth: Int) {
            items.forEach { symbol ->
                add(symbol to depth)
                append(symbol.children, depth + 1)
            }
        }
        append(symbols, 0)
    }

/** Right-click (touchpad or mouse) opens the same menu as a long press. */
private fun Modifier.onSecondaryClick(action: () -> Unit): Modifier = pointerInput(action) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                event.changes.forEach { it.consume() }
                action()
            }
        }
    }
}

@Composable
private fun SearchView(controller: WorkbenchController) {
    val colors = MaterialTheme.colorScheme
    val focus = remember { FocusRequester() }
    var showGlobs by remember { mutableStateOf(controller.searchInclude.isNotEmpty() || controller.searchExclude.isNotEmpty()) }
    LaunchedEffect(controller.searchFocusRequest) { if (controller.searchFocusRequest > 0) runCatching { focus.requestFocus() } }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = controller.searchText,
                onValueChange = {
                    controller.searchText = it
                    controller.scheduleSearch()
                },
                placeholder = { Text("Search") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onSearch = { controller.runSearch() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                SearchToggle("Aa", "Match case", controller.searchCaseSensitive) {
                    controller.searchCaseSensitive = it
                    controller.runSearch()
                }
                SearchToggle("ab", "Match whole word", controller.searchWholeWord) {
                    controller.searchWholeWord = it
                    controller.runSearch()
                }
                SearchToggle(".*", "Use regular expression", controller.searchRegex) {
                    controller.searchRegex = it
                    controller.runSearch()
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { showGlobs = !showGlobs }) {
                    Icon(Icons.Outlined.FilterList, contentDescription = "Files to include or exclude")
                }
            }
            if (showGlobs) {
                OutlinedTextField(
                    value = controller.searchInclude,
                    onValueChange = { controller.searchInclude = it; controller.scheduleSearch() },
                    label = { Text("Files to include") },
                    placeholder = { Text("e.g. *.py, src/**") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = controller.searchExclude,
                    onValueChange = { controller.searchExclude = it; controller.scheduleSearch() },
                    label = { Text("Files to exclude") },
                    placeholder = { Text("e.g. *.min.js, build/**") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            controller.searchSummary?.let {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (controller.searchRunning) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text(it, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f, fill = false))
                    if (missingDependencyIn(it) == GuestDependency.Ripgrep || "needs ripgrep" in it) {
                        TextButton(onClick = { controller.offerInstall(GuestDependency.Ripgrep) { controller.scheduleSearch() } }) { Text("Install") }
                    }
                }
            }
        }
        val root = controller.workspaceRoot.orEmpty().trimEnd('/')
        LazyColumn(Modifier.fillMaxSize()) {
            controller.searchResults.forEach { file ->
                item(key = "file:${file.path}") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { file.expanded = !file.expanded }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            if (file.expanded) Icons.Outlined.ExpandMore else Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(file.path.substringAfterLast('/'), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                        Text(
                            file.path.removePrefix(root).removePrefix("/").substringBeforeLast('/', ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text("${file.matches.size}", style = MaterialTheme.typography.labelSmall, color = colors.primary)
                    }
                }
                if (file.expanded) {
                    items(file.matches, key = { "match:${file.path}:${it.line}:${it.column}" }) { match ->
                        Text(
                            matchPreview(match, colors.primary.copy(alpha = 0.3f)),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { controller.openSearchMatch(match) }
                                .padding(start = 40.dp, end = 12.dp, top = 3.dp, bottom = 3.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchToggle(label: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    FilterChip(
        selected = checked,
        onClick = { onChange(!checked) },
        label = { Text(label, fontFamily = FontFamily.Monospace) },
        modifier = Modifier.semantics { contentDescription = description },
    )
}

/** The line with the match highlighted, trimmed so a match far to the right stays visible. */
private fun matchPreview(match: com.avfstudio.nativeide.transport.SearchEvent.Match, highlight: androidx.compose.ui.graphics.Color) =
    buildAnnotatedString {
        val line = match.preview.trimEnd('\n', '\r')
        val indent = line.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
        val start = maxOf(indent, match.column - 40).coerceAtMost(match.column)
        val end = (match.column + match.length).coerceAtMost(line.length)
        if (start > indent) append("…")
        append(line.substring(start.coerceAtMost(line.length), match.column.coerceIn(0, line.length)))
        withStyle(SpanStyle(background = highlight)) { append(line.substring(match.column.coerceIn(0, line.length), end.coerceAtLeast(match.column.coerceIn(0, line.length)))) }
        append(line.substring(end.coerceAtLeast(0)))
        append("  :${match.line}")
    }
