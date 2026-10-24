package com.avfstudio.nativeide.debug

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test

/** Payloads recorded from debugpy 1.8.22 (guest/tools/avf-debugpy.py). */
class DebugModelsTest {
    @Test
    fun parsesStackFramesFromDebugpy() {
        val body = JsonParser.parseString(
            """{"stackFrames":[{"id":2,"name":"add","line":3,"column":1,"source":{"path":"/w/prog.py"}},
               {"id":3,"name":"<module>","line":5,"column":1,"source":{"path":"/w/prog.py"}}],"totalFrames":2}""",
        ).asJsonObject
        assertEquals(
            listOf(DebugFrame(2, "add", "/w/prog.py", 3, 1), DebugFrame(3, "<module>", "/w/prog.py", 5, 1)),
            parseStackFrames(body),
        )
    }

    @Test
    fun parsesScopesAndVariables() {
        val scopes = parseScopes(
            JsonParser.parseString("""{"scopes":[{"name":"Locals","variablesReference":4},{"name":"Globals","variablesReference":5}]}""").asJsonObject,
        )
        assertEquals(listOf("Locals" to 4, "Globals" to 5), scopes.map { it.name to it.reference })
        val variables = parseVariables(
            JsonParser.parseString("""{"variables":[{"name":"a","value":"0","type":"int","variablesReference":0},
                {"name":"items","value":"[1, 2]","type":"list","variablesReference":7}]}""").asJsonObject,
            depth = 1,
        )
        assertEquals(listOf("a" to "0", "items" to "[1, 2]"), variables.map { it.name to it.value })
        assertEquals(7, variables[1].reference)
        assertEquals(1, variables[1].depth)
    }

    @Test
    fun buildsLaunchAndBreakpointRequests() {
        val launch = pythonLaunchArguments("/w/app/main.py", "/w/app", "/home/droid/miniconda3/bin/python")
        assertEquals("/home/droid/miniconda3/bin/python", launch.get("python").asString)
        assertEquals("internalConsole", launch.get("console").asString)
        val breakpoints = setBreakpointsArguments("/w/a.py", setOf(9, 3))
        assertEquals(listOf(3, 9), breakpoints.getAsJsonArray("breakpoints").map { it.asJsonObject.get("line").asInt })
    }
}
