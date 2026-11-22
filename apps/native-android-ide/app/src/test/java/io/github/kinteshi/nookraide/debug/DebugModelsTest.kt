package io.github.kinteshi.nookraide.debug

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

    @Test
    fun breakpointRequestCarriesConditionsHitCountsAndLogMessages() {
        val request = setBreakpointsArguments(
            "/w/a.py",
            setOf(4, 2, 7),
            mapOf(2 to BreakpointOptions(condition = " i > 3 "), 7 to BreakpointOptions(hitCondition = ">5", logMessage = "i is {i}")),
        )
        val breakpoints = request.getAsJsonArray("breakpoints").map { it.asJsonObject }
        assertEquals(listOf(2, 4, 7), breakpoints.map { it.get("line").asInt })
        assertEquals("i > 3", breakpoints[0].get("condition").asString)
        assertEquals(false, breakpoints[1].has("condition"))
        assertEquals(">5", breakpoints[2].get("hitCondition").asString)
        assertEquals("i is {i}", breakpoints[2].get("logMessage").asString)
        assertEquals(true, BreakpointOptions(logMessage = "x").isLogpoint)
        assertEquals(true, BreakpointOptions(condition = "  ").isPlain)
    }

    @Test
    fun readsLaunchJsonWithCommentsAndTrailingCommas() {
        val text = """{
            // a comment, with a comma,
            "version": "0.2.0",
            "configurations": [
                { "name": "Run, then stop", "type": "debugpy", "request": "launch", "program": "${'$'}{file}", /* x, */ },
                { "name": "Attach", "type": "debugpy", "request": "attach", "connect": { "host": "localhost", "port": 5678, }, },
                { "type": "debugpy" },
            ],
        }"""
        val configurations = parseLaunchConfigurations(text)
        assertEquals(listOf("Run, then stop", "Attach"), configurations.map { it.name })
        assertEquals("attach", configurations[1].request)
        assertEquals(5678, configurations[1].arguments.getAsJsonObject("connect").get("port").asInt)
        assertEquals(listOf("Python Debugger: Current File", "Python Debugger: Current File with Arguments", "Node.js: Current File", "Python Debugger: Attach (port 5678)"),
            parseLaunchConfigurations(LAUNCH_JSON_TEMPLATE).map { it.name })
        assertEquals("""{"a": "x,}" }""", withoutTrailingCommas("""{"a": "x,}",}"""))
    }

    @Test
    fun resolvesVsCodeVariables() {
        val variables = debugVariables("/home/d/Projects", "/home/d/Projects/app/main.py", 12, null, "/usr/bin/python3")
        val configuration = JsonParser.parseString(
            """{"program":"${'$'}{file}","cwd":"${'$'}{fileDirname}","args":["${'$'}{relativeFile}","${'$'}{fileBasenameNoExtension}${'$'}{fileExtname}","${'$'}{env:HOME}"],
                "python":"${'$'}{command:python.interpreterPath}","n":5}""",
        )
        val resolved = resolveDebugVariables(configuration, variables).asJsonObject
        assertEquals("/home/d/Projects/app/main.py", resolved.get("program").asString)
        assertEquals("/home/d/Projects/app", resolved.get("cwd").asString)
        assertEquals(listOf("app/main.py", "main.py", "${'$'}{env:HOME}"), resolved.getAsJsonArray("args").map { it.asString })
        assertEquals("/usr/bin/python3", resolved.get("python").asString)
        assertEquals(5, resolved.get("n").asInt)
        assertEquals("app", variables["relativeFileDirname"])
    }

    @Test
    fun pythonArgumentsKeepTheConfigurationAndUseTheDebugConsole() {
        val launch = pythonDebugArguments(
            JsonParser.parseString("""{"type":"debugpy","request":"launch","program":"/w/a.py","console":"integratedTerminal"}""").asJsonObject,
            "/opt/py/bin/python",
            "/w",
        )
        assertEquals("internalConsole", launch.get("console").asString)
        assertEquals("/opt/py/bin/python", launch.get("python").asString)
        assertEquals("/w", launch.get("cwd").asString)
        val attach = pythonDebugArguments(JsonParser.parseString("""{"request":"attach","python":"/x"}""").asJsonObject, "/opt/py/bin/python", "/w")
        assertEquals("/x", attach.get("python").asString)
        assertEquals(false, attach.has("cwd"))
    }

    @Test
    fun inlineValuesCoverThePausedFunction() {
        val lines = """
            |total = 0
            |def scale(values, factor):
            |    out = []  # factor here is a comment
            |    for v in values:
            |        out.append(v * factor)
            |    label = "values"
            |    return out
        """.trimMargin().lines()
        val values = mapOf("values" to "[1, 2]", "factor" to "3", "out" to "[3, 6]", "v" to "2", "total" to "0", "label" to "'values'")
        val inline = inlineDebugValues(lines, pausedLine = 5, values = values)
        assertEquals(setOf(1, 2, 3, 4, 5), inline.keys)
        assertEquals("values = [1, 2], factor = 3", inline[1])
        assertEquals("out = [3, 6]", inline[2])
        assertEquals("v = 2, values = [1, 2]", inline[3])
        assertEquals("label = 'values'", inline[5])
        assertEquals(emptyMap<Int, String>(), inlineDebugValues(lines, 9, values))
    }

    @Test
    fun nodeArgumentsUseJsDebugsTypeAndTheDebugConsole() {
        val launch = nodeDebugArguments(JsonParser.parseString("""{"type":"node","request":"launch","program":"/w/a.js"}""").asJsonObject, "/w")
        assertEquals("pwa-node", launch.get("type").asString)
        assertEquals("internalConsole", launch.get("console").asString)
        assertEquals("std", launch.get("outputCapture").asString)
        assertEquals("/w", launch.get("cwd").asString)
        assertEquals(true, dapInitializeArguments("pwa-node", startDebugging = true).get("supportsStartDebuggingRequest").asBoolean)
        assertEquals(false, dapInitializeArguments().has("supportsStartDebuggingRequest"))
    }

    @Test
    fun breakpointsMoveWithTheText() {
        // Enter on line 3 (0-based 2) after its code: lines below move, line 3 stays.
        assertEquals(3, movedBreakpointLine(3, insertion = true, startLine = 2, startColumn = 8, endLine = 3))
        assertEquals(6, movedBreakpointLine(5, insertion = true, startLine = 2, startColumn = 8, endLine = 3))
        assertEquals(2, movedBreakpointLine(2, insertion = true, startLine = 2, startColumn = 8, endLine = 3))
        // Two lines pasted at the start of line 3 push it down.
        assertEquals(5, movedBreakpointLine(3, insertion = true, startLine = 2, startColumn = 0, endLine = 4))
        // Typing on one line moves nothing.
        assertEquals(3, movedBreakpointLine(3, insertion = true, startLine = 2, startColumn = 0, endLine = 2))
        // Lines 2–3 deleted (from 0-based 1 to 3): line 6 moves up two, a breakpoint inside ends on the joined line.
        assertEquals(4, movedBreakpointLine(6, insertion = false, startLine = 1, startColumn = 4, endLine = 3))
        assertEquals(2, movedBreakpointLine(4, insertion = false, startLine = 1, startColumn = 4, endLine = 3))
        assertEquals(2, movedBreakpointLine(2, insertion = false, startLine = 1, startColumn = 4, endLine = 3))
        assertEquals(1, movedBreakpointLine(1, insertion = false, startLine = 1, startColumn = 4, endLine = 3))
    }

    @Test
    fun codeLldbConfigurations() {
        val rust = builtInDebugConfigurations().first { it.name == RUST_CARGO_PROJECT }.arguments
        val launch = lldbDebugArguments(rust, "/home/droid/Projects")
        assertEquals("console", launch.get("terminal").asString)
        assertEquals("/home/droid/Projects", launch.get("cwd").asString)
        assertEquals(false, launch.has("sourceLanguages"))
        assertEquals("""{"evaluateForHovers":true,"commandCompletions":true,"sourceLanguages":["rust"]}""", lldbAdapterSettings(rust).toString())
        assertEquals("gcc -g -O0 -Wall '/p/hello.c' -o '/p/hello'", cBuildCommand("/p/hello.c"))
        assertEquals("g++ -g -O0 -Wall '/p/it'\\''s.cpp' -o '/p/it'\\''s'", cBuildCommand("/p/it's.cpp"))
    }

    @Test
    fun cargoPackageNameReadsThePackageTable() {
        val manifest = """
            [workspace]
            name = "not-this"

            [package]
            # the binary
            name = "hello-rs"
            version = "0.1.0"

            [dependencies]
            name = "nor-this"
        """.trimIndent()
        assertEquals("hello-rs", cargoPackageName(manifest))
        assertEquals(null, cargoPackageName("[workspace]\nmembers = []\n"))
    }

    @Test
    fun inlineValuesFindACFunctionsStart() {
        val lines = listOf(
            "int other(void) { return 1; }",
            "int total = 9;",
            "int main(void) {",
            "    int n = 3;",
            "    int total = n * 2;",
            "    return total;",
            "}",
        )
        assertEquals(
            mapOf(3 to "n = 3", 4 to "total = 6, n = 3", 5 to "total = 6"),
            inlineDebugValues(lines, 5, mapOf("n" to "3", "total" to "6")),
        )
    }
}
