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
