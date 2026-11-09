package io.github.kinteshi.quirecode.workbench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HoverCardTest {
    @Test
    fun theDocsTitleNamesTheWordAtThePlace() {
        val line = "df = pd.DataFrame(data)"
        assertEquals("DataFrame", wordAt(line, 8))
        assertEquals("DataFrame", wordAt(line, 12))
        // Just after a word, as a caret at its end is.
        assertEquals("DataFrame", wordAt(line, 17))
        assertEquals("pd", wordAt(line, 5))
        assertNull(wordAt(line, 4))
        assertNull(wordAt("", 0))
    }
}
