package com.avfstudio.nativeide.workbench

/** A row of the side-by-side diff: unchanged, only on one side, or changed on both. */
enum class DiffKind { Hunk, Context, Removed, Added, Changed }

data class DiffRow(
    val kind: DiffKind,
    val leftNumber: Int? = null,
    val left: String? = null,
    val rightNumber: Int? = null,
    val right: String? = null,
)

private val hunkHeader = Regex("""^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@(.*)$""")

/**
 * A unified diff (`git diff` of one file) as side-by-side rows: removed lines on the left,
 * added on the right, a run of removals and additions paired line by line as changes, as
 * VS Code's diff editor lines them up.
 */
fun sideBySideRows(patch: String): List<DiffRow> {
    val rows = mutableListOf<DiffRow>()
    var left = 0
    var right = 0
    var inHunk = false
    val removed = mutableListOf<Pair<Int, String>>()
    val added = mutableListOf<Pair<Int, String>>()
    fun flush() {
        for (index in 0 until maxOf(removed.size, added.size)) {
            val old = removed.getOrNull(index)
            val new = added.getOrNull(index)
            rows += DiffRow(
                kind = when {
                    old != null && new != null -> DiffKind.Changed
                    old != null -> DiffKind.Removed
                    else -> DiffKind.Added
                },
                leftNumber = old?.first,
                left = old?.second,
                rightNumber = new?.first,
                right = new?.second,
            )
        }
        removed.clear()
        added.clear()
    }
    for (line in patch.lines()) {
        val header = hunkHeader.find(line)
        if (header != null) {
            flush()
            left = header.groupValues[1].toInt()
            right = header.groupValues[2].toInt()
            inHunk = true
            rows += DiffRow(DiffKind.Hunk, left = line)
            continue
        }
        if (!inHunk) continue
        when {
            line.startsWith("-") -> removed += left++ to line.substring(1)
            line.startsWith("+") -> added += right++ to line.substring(1)
            line.startsWith(" ") || line.isEmpty() -> {
                flush()
                val text = if (line.isEmpty()) "" else line.substring(1)
                rows += DiffRow(DiffKind.Context, left++, text, right++, text)
            }
            line.startsWith("\\") -> Unit // "\ No newline at end of file"
            else -> {
                flush()
                inHunk = false
            }
        }
    }
    flush()
    // A trailing empty context row comes from the patch's final newline.
    if (rows.lastOrNull()?.let { it.kind == DiffKind.Context && it.left.isNullOrEmpty() } == true) rows.removeAt(rows.lastIndex)
    return rows
}

/**
 * A file's patch cut at its hunks: the file header (`diff --git` … `+++`) and each hunk from its
 * `@@` line, every piece ending with a newline.
 */
fun patchHunks(patch: String): Pair<String, List<String>> {
    val lines = patch.split('\n').let { if (it.lastOrNull() == "") it.dropLast(1) else it }
    val first = lines.indexOfFirst { hunkHeader.matches(it) }
    if (first < 0) return patch to emptyList()
    val header = lines.subList(0, first).joinToString("") { "$it\n" }
    val hunks = mutableListOf<String>()
    var current = StringBuilder()
    for (line in lines.subList(first, lines.size)) {
        if (hunkHeader.matches(line) && current.isNotEmpty()) {
            hunks += current.toString()
            current = StringBuilder()
        }
        current.append(line).append('\n')
    }
    if (current.isNotEmpty()) hunks += current.toString()
    return header to hunks
}

/** [patch] with only its hunk [index], for staging, unstaging or discarding just that part (`git apply` finds it if earlier hunks moved it). */
fun singleHunkPatch(patch: String, index: Int): String? {
    val (header, hunks) = patchHunks(patch)
    if (header.isBlank()) return null
    return hunks.getOrNull(index)?.let { header + it }
}

/** One line of `git blame --line-porcelain`: who last changed it, when (seconds) and in which commit. */
data class BlameLine(val hash: String, val author: String, val time: Long, val summary: String) {
    /** Lines not committed yet (Git's all-zero hash). */
    val uncommitted: Boolean get() = hash.all { it == '0' }
}

/** `git blame --line-porcelain` output as each line's (0-based) blame. */
fun parseBlame(text: String): Map<Int, BlameLine> {
    val result = HashMap<Int, BlameLine>()
    var hash = ""
    var line = -1
    var author = ""
    var time = 0L
    var summary = ""
    for (row in text.lineSequence()) {
        when {
            row.startsWith("\t") -> if (line >= 0) result[line] = BlameLine(hash, author, time, summary)
            row.startsWith("author ") -> author = row.removePrefix("author ")
            row.startsWith("author-time ") -> time = row.removePrefix("author-time ").toLongOrNull() ?: 0L
            row.startsWith("summary ") -> summary = row.removePrefix("summary ")
            else -> {
                val parts = row.split(' ')
                if (parts.size >= 3 && parts[0].length >= 40 && parts[0].all { it.isLetterOrDigit() }) {
                    hash = parts[0]
                    line = (parts[2].toIntOrNull() ?: 0) - 1
                }
            }
        }
    }
    return result
}

/** "3 days ago", as VS Code's blame and timeline say it. */
fun relativeTime(seconds: Long, now: Long = System.currentTimeMillis() / 1000): String {
    val delta = (now - seconds).coerceAtLeast(0)
    fun ago(value: Long, unit: String) = "$value $unit${if (value == 1L) "" else "s"} ago"
    return when {
        delta < 60 -> "just now"
        delta < 3600 -> ago(delta / 60, "minute")
        delta < 86_400 -> ago(delta / 3600, "hour")
        delta < 7 * 86_400 -> ago(delta / 86_400, "day")
        delta < 30 * 86_400 -> ago(delta / (7 * 86_400), "week")
        delta < 365 * 86_400 -> ago(delta / (30 * 86_400), "month")
        else -> ago(delta / (365 * 86_400), "year")
    }
}

/** `git stash list`: each stash's index and description ("On main: wip"). */
fun parseStashList(text: String): List<Pair<Int, String>> =
    text.lineSequence().mapNotNull { line ->
        val index = Regex("""^stash@\{(\d+)\}: ?(.*)$""").find(line) ?: return@mapNotNull null
        index.groupValues[1].toInt() to index.groupValues[2]
    }.toList()

/** The part of [old] and [new] between their common start and end: what changed inside the line. */
fun changedSpans(old: String, new: String): Pair<IntRange, IntRange> {
    var prefix = 0
    while (prefix < old.length && prefix < new.length && old[prefix] == new[prefix]) prefix++
    var suffix = 0
    while (suffix < old.length - prefix && suffix < new.length - prefix && old[old.length - 1 - suffix] == new[new.length - 1 - suffix]) suffix++
    return prefix until old.length - suffix to (prefix until new.length - suffix)
}

/**
 * A conflict Git left in a file: `<<<<<<<` at [start], an optional `|||||||` base (diff3), `=======`
 * at [separator], `>>>>>>>` at [end] (0-based lines). [current] is ours (HEAD), [incoming] theirs.
 */
data class MergeConflict(
    val start: Int,
    val base: Int?,
    val separator: Int,
    val end: Int,
    val currentLabel: String,
    val incomingLabel: String,
)

enum class ConflictChoice { Current, Incoming, Both }

fun findConflicts(text: String): List<MergeConflict> {
    val lines = text.split('\n')
    val conflicts = mutableListOf<MergeConflict>()
    var start = -1
    var base: Int? = null
    var separator = -1
    lines.forEachIndexed { index, raw ->
        val line = raw.removeSuffix("\r")
        when {
            line.startsWith("<<<<<<<") -> {
                start = index
                base = null
                separator = -1
            }
            start >= 0 && separator < 0 && line.startsWith("|||||||") -> base = index
            start >= 0 && line == "=======" -> separator = index
            start >= 0 && separator >= 0 && line.startsWith(">>>>>>>") -> {
                conflicts += MergeConflict(
                    start, base, separator, index,
                    lines[start].removeSuffix("\r").removePrefix("<<<<<<<").trim(),
                    line.removePrefix(">>>>>>>").trim(),
                )
                start = -1
            }
        }
    }
    return conflicts
}

/** The lines that replace the whole conflict (markers included) for [choice]. */
fun conflictResolution(text: String, conflict: MergeConflict, choice: ConflictChoice): List<String> {
    val lines = text.split('\n')
    val current = lines.subList(conflict.start + 1, conflict.base ?: conflict.separator)
    val incoming = lines.subList(conflict.separator + 1, conflict.end)
    return when (choice) {
        ConflictChoice.Current -> current
        ConflictChoice.Incoming -> incoming
        ConflictChoice.Both -> current + incoming
    }
}
