package io.github.kinteshi.nookraide.workbench

import org.junit.Assert.assertEquals
import org.junit.Test

class DiffModelsTest {
    @Test
    fun pairsRemovalsAndAdditionsSideBySide() {
        val patch = """
            diff --git a/x.py b/x.py
            --- a/x.py
            +++ b/x.py
            @@ -1,4 +1,4 @@ def f():
             a = 1
            -b = 2
            -c = 3
            +b = 20
             d = 4
            +e = 5
        """.trimIndent()
        val rows = sideBySideRows(patch)
        assertEquals(listOf(DiffKind.Hunk, DiffKind.Context, DiffKind.Changed, DiffKind.Removed, DiffKind.Context, DiffKind.Added), rows.map { it.kind })
        assertEquals(DiffRow(DiffKind.Changed, 2, "b = 2", 2, "b = 20"), rows[2])
        assertEquals(DiffRow(DiffKind.Removed, 3, "c = 3", null, null), rows[3])
        assertEquals(DiffRow(DiffKind.Context, 4, "d = 4", 3, "d = 4"), rows[4])
        assertEquals(DiffRow(DiffKind.Added, null, null, 4, "e = 5"), rows[5])
        assertEquals(5..4 to (5..5), changedSpans("b = 2", "b = 20"))
    }

    @Test
    fun findsAndResolvesConflicts() {
        val text = listOf(
            "start", "<<<<<<< HEAD", "ours", "||||||| base", "old", "=======", "theirs", "more", ">>>>>>> feature", "end",
        ).joinToString("\n")
        val conflict = findConflicts(text).single()
        assertEquals(MergeConflict(1, 3, 5, 8, "HEAD", "feature"), conflict)
        assertEquals(listOf("ours"), conflictResolution(text, conflict, ConflictChoice.Current))
        assertEquals(listOf("theirs", "more"), conflictResolution(text, conflict, ConflictChoice.Incoming))
        assertEquals(listOf("ours", "theirs", "more"), conflictResolution(text, conflict, ConflictChoice.Both))
        assertEquals(emptyList<MergeConflict>(), findConflicts("no\n=======\nconflict"))
    }
}
