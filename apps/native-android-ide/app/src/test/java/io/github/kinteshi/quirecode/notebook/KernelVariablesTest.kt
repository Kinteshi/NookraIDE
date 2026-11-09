package io.github.kinteshi.quirecode.notebook

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KernelVariablesTest {
    @Test
    fun readsTheBridgesVariables() {
        val result = parseKernelVariables(
            JsonParser.parseString(
                """{"variables": [
                    {"name": "df", "type": "DataFrame", "module": "pandas.core.frame", "size": "569 × 31", "value": "columns: a, b", "viewable": true},
                    {"name": "x", "type": "int", "module": "builtins", "size": "", "value": "42", "viewable": false},
                    {"type": "int"}
                ], "truncated": true}""",
            ),
        )
        assertEquals(
            listOf(
                KernelVariable("df", "DataFrame", "569 × 31", "columns: a, b", viewable = true),
                KernelVariable("x", "int", "", "42", viewable = false),
            ),
            result.variables,
        )
        assertTrue(result.truncated)
        assertTrue(parseKernelVariables(JsonParser.parseString("""{"unsupported": true}""")).unsupported)
        assertEquals(KernelVariables(emptyList(), false), parseKernelVariables(null))
    }

    @Test
    fun turnsADataFrameIntoTheNativeTable() {
        val viewer = parseVariableTable(
            JsonParser.parseString(
                """{"columns": ["radius", "label"], "index_name": "", "index": ["0", "1"],
                    "rows": [[17.99, "malignant"], [20.570000000000004, null]], "total": 569}""",
            ),
        )!!
        assertEquals(569, viewer.total)
        assertEquals(listOf("", "radius", "label"), viewer.table.head.single().map { it.text })
        assertEquals(listOf("0", "17.99", "malignant"), viewer.table.body[0].map { it.text })
        assertEquals(listOf("1", "20.57", "None"), viewer.table.body[1].map { it.text })
        assertTrue(viewer.table.body[0][0].header)
        assertNull(parseVariableTable(JsonParser.parseString("{}")))
    }
}
