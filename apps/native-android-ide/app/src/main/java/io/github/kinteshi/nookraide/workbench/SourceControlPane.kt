package io.github.kinteshi.nookraide.workbench

import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.kinteshi.nookraide.transport.GitFileStatus
import io.github.kinteshi.nookraide.transport.GitStatusEntry

/** VS Code-style Source Control view: branch and sync, commit box, staged and unstaged changes. */
@Composable
fun SourceControlView(controller: WorkbenchController) {
    val status = controller.gitStatus
    Column(Modifier.fillMaxSize()) {
        when {
            !controller.connected -> EmptyState(Icons.Outlined.CloudOff, "Not connected", "Connect to the guest to use Git.")
            controller.gitMissing -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Git isn't installed in the guest.", style = MaterialTheme.typography.bodyMedium)
                Button(
                    onClick = { controller.offerInstall(GuestDependency.Git, then = controller::refreshGitStatus) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Install Git") }
                OutlinedButton(onClick = { controller.checkGuestTools(controller::refreshGitStatus) }, modifier = Modifier.fillMaxWidth()) { Text("Check again") }
            }
            controller.gitNotARepository -> NotARepository(controller)
            controller.gitProblem != null -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(controller.gitProblem!!, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = controller::refreshGitStatus) { Text("Retry") }
            }
            status == null -> EmptyMessage("Reading repository status…")
            else -> RepositoryView(controller, status)
        }
    }
}

@Composable
private fun NotARepository(controller: WorkbenchController) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("The workspace folder isn't a Git repository.", style = MaterialTheme.typography.bodyMedium)
        Button(onClick = controller::gitInit, modifier = Modifier.fillMaxWidth()) { Text("Initialize repository") }
        OutlinedButton(onClick = { controller.runCommand("git.clone") }, modifier = Modifier.fillMaxWidth()) { Text("Clone repository…") }
    }
}

@Composable
private fun RepositoryView(controller: WorkbenchController, status: io.github.kinteshi.nookraide.transport.GitResult.Status) {
    val colors = MaterialTheme.colorScheme
    val staged = status.entries.filter { it.staged }
    val changes = status.entries.filter { it.unstaged }
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The branch gets all the width the buttons leave; a narrow pane used to cut it to "…".
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = controller::showBranches, modifier = Modifier.weight(1f, fill = false)) {
                Icon(Icons.AutoMirrored.Outlined.CallSplit, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(" ${status.branch ?: "(detached)"}", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (status.upstream != null && (status.ahead > 0 || status.behind > 0)) {
                TextButton(onClick = controller::gitSync) {
                    Icon(Icons.Outlined.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(" ${status.behind}↓ ${status.ahead}↑", maxLines = 1)
                }
            }
        }
        if (status.upstream == null) {
            Tooltip("Publish branch") { IconButton(onClick = controller::gitPush) { Icon(Icons.Outlined.CloudUpload, contentDescription = "Publish branch") } }
        }
        Tooltip("Refresh") { IconButton(onClick = controller::refreshGitStatus) { Icon(Icons.Outlined.Refresh, contentDescription = "Refresh") } }
        Box {
            Tooltip("More Git actions") { IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "More Git actions") } }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Pull") }, leadingIcon = { Icon(Icons.Outlined.CloudDownload, null) }, onClick = { menu = false; controller.gitPull() })
                DropdownMenuItem(text = { Text("Push") }, leadingIcon = { Icon(Icons.Outlined.CloudUpload, null) }, onClick = { menu = false; controller.gitPush() })
                DropdownMenuItem(text = { Text("Fetch") }, leadingIcon = { Icon(Icons.Outlined.Sync, null) }, onClick = { menu = false; controller.gitFetch() })
                DropdownMenuItem(text = { Text("Checkout to…") }, leadingIcon = { Icon(Icons.AutoMirrored.Outlined.CallSplit, null) }, onClick = { menu = false; controller.showBranches() })
                DropdownMenuItem(text = { Text("Stash") }, onClick = { menu = false; controller.stashChanges(includeUntracked = false) })
                DropdownMenuItem(text = { Text("Stash (include untracked)") }, onClick = { menu = false; controller.stashChanges(includeUntracked = true) })
                DropdownMenuItem(text = { Text("Pop latest stash") }, onClick = { menu = false; controller.popLatestStash() })
                DropdownMenuItem(text = { Text("Stashes…") }, onClick = { menu = false; controller.showStashes() })
                DropdownMenuItem(text = { Text("File history (active file)") }, onClick = { menu = false; controller.showFileHistory() })
                DropdownMenuItem(text = { Text("Clone…") }, onClick = { menu = false; controller.runCommand("git.clone") })
            }
        }
    }
    controller.gitBusy?.let { label ->
        Row(Modifier.padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            Text("  $label…", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        }
    }
    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = controller.commitMessage,
            onValueChange = { controller.commitMessage = it },
            placeholder = { Text("Message (Ctrl+Enter to commit)") },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 140.dp)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Enter && event.isCtrlPressed) {
                        controller.commit()
                        true
                    } else {
                        false
                    }
                },
        )
        Button(
            onClick = controller::commit,
            enabled = controller.gitBusy == null && controller.commitMessage.isNotBlank() && status.entries.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (staged.isEmpty() && changes.isNotEmpty()) "Commit all" else "Commit") }
    }
    LazyColumn(Modifier.fillMaxSize()) {
        if (staged.isNotEmpty()) {
            item { SectionHeader("Staged changes", staged.size) { Tooltip("Unstage all") { IconButton(onClick = { controller.unstageFiles(staged.map { it.path }) }) { Icon(Icons.Outlined.Remove, "Unstage all") } } } }
            items(staged, key = { "staged:${it.path}" }) { entry ->
                ChangeRow(entry, entry.indexStatus, onOpen = { controller.showGitDiff(entry, staged = true) }) {
                    Tooltip("Unstage") { IconButton(onClick = { controller.unstageFiles(listOf(entry.path)) }) { Icon(Icons.Outlined.Remove, "Unstage") } }
                }
            }
        }
        item {
            SectionHeader("Changes", changes.size) {
                if (changes.isNotEmpty()) {
                    Tooltip("Discard all changes") { IconButton(onClick = { controller.requestDiscard(changes) }) { Icon(Icons.AutoMirrored.Outlined.Undo, "Discard all changes") } }
                    Tooltip("Stage all changes") { IconButton(onClick = { controller.stageFiles(changes.map { it.path }) }) { Icon(Icons.Outlined.Add, "Stage all changes") } }
                }
            }
        }
        if (changes.isEmpty() && staged.isEmpty()) item { EmptyMessage("No changes.") }
        items(changes, key = { "change:${it.path}" }) { entry ->
            val letter = when (entry.status) {
                GitFileStatus.Untracked -> "U"
                GitFileStatus.Conflicted -> "!"
                else -> entry.worktreeStatus
            }
            ChangeRow(entry, letter, onOpen = { controller.showGitDiff(entry, staged = false) }) {
                Tooltip("Discard changes") { IconButton(onClick = { controller.requestDiscard(listOf(entry)) }) { Icon(Icons.AutoMirrored.Outlined.Undo, "Discard changes") } }
                Tooltip("Stage changes") { IconButton(onClick = { controller.stageFiles(listOf(entry.path)) }) { Icon(Icons.Outlined.Add, "Stage changes") } }
            }
        }
        // VS Code's Source Control Graph, as a list: the branch's commits, newest first.
        item {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = controller::toggleGitHistory).padding(start = 12.dp, end = 16.dp, top = 10.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (controller.gitHistoryOpen) Icons.Outlined.ExpandMore else Icons.Outlined.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("History", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(start = 4.dp))
            }
        }
        if (controller.gitHistoryOpen) {
            val history = controller.gitHistory
            when {
                history == null -> item { EmptyMessage("Reading history…") }
                history.isEmpty() -> item { EmptyMessage("No commits yet.") }
                else -> items(history, key = { "commit:${it.hash}" }) { commit ->
                    Column(Modifier.fillMaxWidth().clickable { controller.showCommit(commit) }.padding(start = 34.dp, end = 12.dp, top = 5.dp, bottom = 5.dp)) {
                        Text(commit.subject, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${commit.author} · ${relativeTime(commit.timestamp)} · ${commit.hash.take(7)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, count: Int, actions: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text("$count", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        actions()
    }
}

@Composable
private fun ChangeRow(entry: GitStatusEntry, letter: String, onOpen: () -> Unit, actions: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val tint = when (entry.status) {
        GitFileStatus.Added, GitFileStatus.Untracked -> Color(0xFF73C991)
        GitFileStatus.Deleted -> colors.error
        GitFileStatus.Conflicted -> Color(0xFFE4676B)
        GitFileStatus.Renamed, GitFileStatus.Copied -> Color(0xFF4FC1FF)
        GitFileStatus.Modified -> Color(0xFFE2C08D)
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(letter, color = tint, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, modifier = Modifier.width(18.dp))
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(entry.path.substringAfterLast('/'), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            entry.path.substringBeforeLast('/', "").takeIf(String::isNotEmpty)?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        actions()
    }
}
