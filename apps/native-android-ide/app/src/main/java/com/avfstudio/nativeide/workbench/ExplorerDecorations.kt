package com.avfstudio.nativeide.workbench

import androidx.compose.ui.graphics.Color
import com.avfstudio.nativeide.transport.GitFileStatus
import com.avfstudio.nativeide.transport.GitStatusEntry

/** What colours a name in Explorer, most important first (a folder takes its children's first). */
enum class DecorationTone { Conflict, Error, Deleted, Warning, Modified, Added, Untracked }

/**
 * How Explorer marks a file or folder: its colour, the Git letter VS Code shows (M, U, A, D, R,
 * C, ! for a conflict) and its problem count. Folders get only the colour and a dot.
 */
data class FileDecoration(val tone: DecorationTone, val gitLetter: String? = null, val problems: Int = 0, val folder: Boolean = false)

private fun gitTone(entry: GitStatusEntry): Pair<DecorationTone, String> = when (entry.status) {
    GitFileStatus.Conflicted -> DecorationTone.Conflict to "!"
    GitFileStatus.Untracked -> DecorationTone.Untracked to "U"
    GitFileStatus.Added -> DecorationTone.Added to "A"
    GitFileStatus.Deleted -> DecorationTone.Deleted to "D"
    GitFileStatus.Renamed -> DecorationTone.Added to "R"
    GitFileStatus.Copied -> DecorationTone.Added to "C"
    GitFileStatus.Modified -> DecorationTone.Modified to "M"
}

/**
 * Decorations by absolute path. [gitEntries] are relative to [root] (the repository root);
 * [problems] counts errors and warnings per absolute path. A file's colour is its worst problem,
 * else its Git state; each folder above a decorated file takes the most important colour below it.
 */
fun explorerDecorations(
    root: String,
    gitEntries: List<GitStatusEntry>,
    problems: Map<String, Pair<Int, Int>>,
    showGit: Boolean = true,
    showProblems: Boolean = true,
): Map<String, FileDecoration> {
    val base = root.trimEnd('/')
    val files = HashMap<String, FileDecoration>()
    if (showGit) {
        gitEntries.forEach { entry ->
            val (tone, letter) = gitTone(entry)
            files["$base/${entry.path}"] = FileDecoration(tone, letter)
        }
    }
    if (showProblems) {
        problems.forEach { (path, counts) ->
            val (errors, warnings) = counts
            if (errors + warnings == 0) return@forEach
            val tone = if (errors > 0) DecorationTone.Error else DecorationTone.Warning
            val git = files[path]
            files[path] = FileDecoration(
                if (git != null && git.tone.ordinal < tone.ordinal) git.tone else tone,
                git?.gitLetter,
                errors + warnings,
            )
        }
    }
    val result = HashMap<String, FileDecoration>(files)
    files.forEach { (path, decoration) ->
        // Deleted files aren't on disk, but their folder still shows the change.
        var parent = path.substringBeforeLast('/', "")
        while (parent.length > base.length && parent.startsWith(base)) {
            val current = result[parent]
            if (current == null || decoration.tone.ordinal < current.tone.ordinal) {
                result[parent] = FileDecoration(decoration.tone, folder = true)
            }
            parent = parent.substringBeforeLast('/', "")
        }
    }
    return result
}

/** VS Code's gitDecoration and problem colours, for dark and light themes. */
fun DecorationTone.color(dark: Boolean): Color = when (this) {
    DecorationTone.Conflict -> if (dark) Color(0xFFE4676B) else Color(0xFFAD0707)
    DecorationTone.Error -> if (dark) Color(0xFFF88070) else Color(0xFFB01011)
    DecorationTone.Deleted -> if (dark) Color(0xFFC74E39) else Color(0xFFAD0707)
    DecorationTone.Warning -> if (dark) Color(0xFFCCA700) else Color(0xFF895503)
    DecorationTone.Modified -> if (dark) Color(0xFFE2C08D) else Color(0xFF895503)
    DecorationTone.Added, DecorationTone.Untracked -> if (dark) Color(0xFF73C991) else Color(0xFF007100)
}
