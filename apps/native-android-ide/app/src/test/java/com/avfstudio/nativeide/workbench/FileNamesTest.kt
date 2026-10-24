package com.avfstudio.nativeide.workbench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FileNamesTest {
    @Test
    fun acceptsAnyPlainNameWithoutAddingAnExtension() {
        assertNull(entryNameProblem("Makefile", allowSubpath = false))
        assertNull(entryNameProblem(".env", allowSubpath = false))
        assertNull(entryNameProblem("notes.txt", allowSubpath = false))
        assertEquals("/home/droid/Projects/Makefile", resolveEntryPath("/home/droid/Projects/", "Makefile"))
    }

    @Test
    fun rejectsEmptyTraversalAndSlashesWhereNotAllowed() {
        assertNotNull(entryNameProblem(" ", allowSubpath = true))
        assertNotNull(entryNameProblem("..", allowSubpath = true))
        assertNotNull(entryNameProblem("a/../b", allowSubpath = true))
        assertNotNull(entryNameProblem("a//b", allowSubpath = true))
        assertNotNull(entryNameProblem("a/b", allowSubpath = false))
        assertNull(entryNameProblem("src/app/main.rs", allowSubpath = true))
    }

    @Test
    fun mapsOpenPathsThroughARename() {
        assertEquals("/w/new", renamedPath("/w/old", "/w/old", "/w/new"))
        assertEquals("/w/new/a.py", renamedPath("/w/old/a.py", "/w/old", "/w/new"))
        assertNull(renamedPath("/w/older/a.py", "/w/old", "/w/new"))
    }

    @Test
    fun picksTheNextFreeUntitledName() {
        assertEquals("Untitled-1", nextUntitledName(listOf("scratch:/welcome.py")))
        assertEquals("Untitled-3", nextUntitledName(listOf("scratch:/Untitled-1", "scratch:/Untitled-2")))
    }
}
