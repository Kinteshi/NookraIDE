package io.github.kinteshi.nookraide.debug

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.google.gson.stream.JsonReader
import java.io.StringReader

sealed interface DebugState {
    data object Idle : DebugState
    data object Starting : DebugState
    data object Running : DebugState
    data class Stopped(val threadId: Int, val reason: String, val description: String?) : DebugState
}

/** A stack frame; [line] and [column] are 1-based as requested in `initialize`. */
data class DebugFrame(val id: Int, val name: String, val path: String?, val line: Int, val column: Int)

/**
 * A variable, scope or watch expression; children load lazily when [reference] > 0 and the node
 * is expanded. [parentReference] is the container it was listed from (setVariable needs it), 0
 * for scopes and watches. Set Value and a new pause change [value] in place.
 */
class DebugVariable(
    val name: String,
    value: String,
    val type: String?,
    reference: Int,
    val depth: Int,
    val parentReference: Int = 0,
) {
    var value by mutableStateOf(value)
    var reference by mutableStateOf(reference)
    /** A watch expression that failed to evaluate: [value] is the error. */
    var failed by mutableStateOf(false)
    val children = mutableStateListOf<DebugVariable>()
    var expanded by mutableStateOf(false)
}

data class DebugConsoleLine(val text: String, val kind: Kind) {
    enum class Kind { Output, Error, Input, Result, Info }
}

/**
 * Appends a DAP `output` chunk of [category]. Programs write partial lines (`print("a", b)` arrives
 * as "a", " ", "6\n"), so a stdout or stderr chunk continues the last line of the same category until
 * a newline ends it. Other categories (logpoints, debugger messages) are whole messages, and nothing
 * joins them: a logpoint once ended up in the middle of a half-printed line. [openCategory] is the
 * category whose line is unfinished; returns the same for the next chunk.
 */
fun appendDebugOutput(
    lines: MutableList<DebugConsoleLine>,
    chunk: String,
    kind: DebugConsoleLine.Kind,
    category: String,
    openCategory: String?,
): String? {
    if (chunk.isEmpty()) return openCategory
    val pieces = chunk.split('\n')
    pieces.forEachIndexed { index, piece ->
        val last = lines.lastOrNull()
        val isLastPiece = index == pieces.lastIndex
        if (isLastPiece && piece.isEmpty()) return@forEachIndexed
        if (index == 0 && openCategory == category && last != null) {
            lines[lines.lastIndex] = last.copy(text = last.text + piece)
        } else {
            lines.add(DebugConsoleLine(piece, kind))
        }
    }
    return if (!chunk.endsWith('\n') && category in PROGRAM_OUTPUT) category else null
}

private val PROGRAM_OUTPUT = setOf("stdout", "stderr")

/** debugpy `launch` arguments for running [program] with [python] in [cwd]. */
fun pythonLaunchArguments(program: String, cwd: String, python: String, justMyCode: Boolean = true): JsonObject =
    JsonObject().apply {
        addProperty("name", "Python: ${program.substringAfterLast('/')}")
        addProperty("type", "python")
        addProperty("request", "launch")
        addProperty("program", program)
        addProperty("cwd", cwd)
        addProperty("python", python)
        addProperty("console", "internalConsole")
        addProperty("justMyCode", justMyCode)
        addProperty("redirectOutput", true)
    }

/** `initialize` for [adapterId]; js-debug starts each program in a session of its own through [startDebugging]. */
fun dapInitializeArguments(adapterId: String = "debugpy", startDebugging: Boolean = false): JsonObject = JsonObject().apply {
    addProperty("clientID", "avf-studio")
    addProperty("clientName", "Nookra IDE")
    addProperty("adapterID", adapterId)
    if (startDebugging) addProperty("supportsStartDebuggingRequest", true)
    addProperty("linesStartAt1", true)
    addProperty("columnsStartAt1", true)
    addProperty("pathFormat", "path")
    addProperty("supportsVariableType", true)
    addProperty("supportsRunInTerminalRequest", false)
}

/** A breakpoint's extras (VS Code's conditional breakpoints and logpoints); blank means unset. */
data class BreakpointOptions(val condition: String = "", val hitCondition: String = "", val logMessage: String = "") {
    val isPlain: Boolean get() = condition.isBlank() && hitCondition.isBlank() && logMessage.isBlank()
    val isLogpoint: Boolean get() = logMessage.isNotBlank()
}

/**
 * Where a breakpoint on 1-based [line] goes after an edit, as VS Code's editor moves it with the
 * text. An insertion at 0-based ([startLine], [startColumn]) adds [lines] line breaks: lines below
 * move down, and so does the breakpoint's own line when the insertion is at its first column (Enter
 * there pushes the code down). A deletion from [startLine] to [endLine] (0-based) moves lines below
 * it up; a breakpoint on a line it joins into [startLine] ends there.
 */
fun movedBreakpointLine(line: Int, insertion: Boolean, startLine: Int, startColumn: Int, endLine: Int): Int {
    val index = line - 1
    val lines = endLine - startLine
    if (lines == 0) return line
    return if (insertion) {
        if (index > startLine || (index == startLine && startColumn == 0)) line + lines else line
    } else {
        when {
            index > endLine -> line - lines
            index > startLine -> startLine + 1
            else -> line
        }
    }
}

fun setBreakpointsArguments(path: String, lines: Collection<Int>, options: Map<Int, BreakpointOptions> = emptyMap()): JsonObject = JsonObject().apply {
    add("source", JsonObject().apply { addProperty("path", path) })
    add("breakpoints", JsonArray().apply {
        lines.sorted().forEach { line ->
            add(JsonObject().apply {
                addProperty("line", line)
                options[line]?.let { extra ->
                    extra.condition.takeIf { it.isNotBlank() }?.let { addProperty("condition", it.trim()) }
                    extra.hitCondition.takeIf { it.isNotBlank() }?.let { addProperty("hitCondition", it.trim()) }
                    extra.logMessage.takeIf { it.isNotBlank() }?.let { addProperty("logMessage", it) }
                }
            })
        }
    })
}

/**
 * A debug configuration: one of launch.json's, or the built-in "current file" one. [arguments]
 * go to the adapter as written once their `${…}` variables are resolved.
 */
data class DebugConfiguration(val name: String, val arguments: JsonObject) {
    val type: String get() = arguments.get("type")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
    val request: String get() = arguments.get("request")?.takeIf { it.isJsonPrimitive }?.asString ?: "launch"
}

/** The configurations in a launch.json (JSON with comments and trailing commas, as VS Code reads it). */
fun parseLaunchConfigurations(text: String): List<DebugConfiguration> {
    val root = runCatching {
        JsonParser.parseReader(JsonReader(StringReader(withoutTrailingCommas(text))).apply { isLenient = true })
    }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject ?: return emptyList()
    return root.getAsJsonArray("configurations")?.mapNotNull { element ->
        val configuration = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        val name = configuration.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: return@mapNotNull null
        DebugConfiguration(name, configuration)
    }.orEmpty()
}

/** [text] without the commas before `}` or `]`, outside strings and comments. */
fun withoutTrailingCommas(text: String): String {
    val out = StringBuilder(text.length)
    var index = 0
    var pendingComma = -1
    while (index < text.length) {
        val char = text[index]
        when {
            char == '"' -> {
                pendingComma = -1
                val end = run {
                    var at = index + 1
                    while (at < text.length && text[at] != '"') at += if (text[at] == '\\') 2 else 1
                    minOf(at + 1, text.length)
                }
                out.append(text, index, end)
                index = end
                continue
            }
            text.startsWith("//", index) -> {
                val end = text.indexOf('\n', index).let { if (it < 0) text.length else it }
                out.append(text, index, end)
                index = end
                continue
            }
            text.startsWith("/*", index) -> {
                val end = text.indexOf("*/", index + 2).let { if (it < 0) text.length else it + 2 }
                out.append(text, index, end)
                index = end
                continue
            }
            char == ',' -> pendingComma = out.length
            char == '}' || char == ']' -> if (pendingComma >= 0) {
                out.setCharAt(pendingComma, ' ')
                pendingComma = -1
            }
            !char.isWhitespace() -> pendingComma = -1
        }
        out.append(char)
        index++
    }
    return out.toString()
}

/** VS Code's `${…}` variables in every string of [value]; one it doesn't know stays as written. */
fun resolveDebugVariables(value: JsonElement, variables: Map<String, String>): JsonElement = when {
    value.isJsonPrimitive && value.asJsonPrimitive.isString ->
        JsonPrimitive(DEBUG_VARIABLE.replace(value.asString) { match -> variables[match.groupValues[1]] ?: match.value })
    value.isJsonArray -> JsonArray().apply { value.asJsonArray.forEach { add(resolveDebugVariables(it, variables)) } }
    value.isJsonObject -> JsonObject().apply { value.asJsonObject.entrySet().forEach { (key, item) -> add(key, resolveDebugVariables(item, variables)) } }
    else -> value
}

// Android's regex engine (ICU) needs the closing brace escaped too.
private val DEBUG_VARIABLE = Regex("""\$\{([^}]+)\}""")

/** The predefined variables for [file] (null when no file is open) in [workspace]. */
fun debugVariables(workspace: String, file: String?, line: Int?, selectedText: String?, python: String): Map<String, String> = buildMap {
    put("workspaceFolder", workspace)
    put("workspaceRoot", workspace)
    put("workspaceFolderBasename", workspace.trimEnd('/').substringAfterLast('/'))
    put("cwd", workspace)
    put("pathSeparator", "/")
    put("/", "/")
    put("command:python.interpreterPath", python)
    put("command:python.interpreter", python)
    if (file != null) {
        val name = file.substringAfterLast('/')
        val directory = file.substringBeforeLast('/')
        put("file", file)
        put("fileBasename", name)
        put("fileBasenameNoExtension", name.substringBeforeLast('.'))
        put("fileExtname", name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" })
        put("fileDirname", directory)
        put("fileDirnameBasename", directory.substringAfterLast('/'))
        val root = workspace.trimEnd('/') + "/"
        put("relativeFile", file.removePrefix(root))
        put("relativeFileDirname", directory.removePrefix(root).removePrefix(workspace.trimEnd('/')))
    }
    line?.let { put("lineNumber", it.toString()) }
    selectedText?.let { put("selectedText", it) }
}

/**
 * A debugpy configuration ready to send: the interpreter, the working folder (the workspace, as
 * the Python extension defaults) and the Debug console for output; the app has no terminal to
 * hand the program to, so `console` is always the internal one.
 */
fun pythonDebugArguments(configuration: JsonObject, python: String, workspace: String): JsonObject = configuration.deepCopy().apply {
    if (!has("python")) addProperty("python", python)
    addProperty("console", "internalConsole")
    if (!has("redirectOutput")) addProperty("redirectOutput", true)
    if (get("request")?.asString != "attach" && !has("cwd")) addProperty("cwd", workspace)
}

/** Debug: Add Configuration's launch.json when the workspace has none. */
const val LAUNCH_JSON_TEMPLATE = """{
    // Debug configurations: the Debug panel picks one, F5 starts it.
    // Variables: ${'$'}{file}, ${'$'}{workspaceFolder}, ${'$'}{fileDirname}, ${'$'}{command:python.interpreterPath}…
    "version": "0.2.0",
    "configurations": [
        {
            "name": "Python Debugger: Current File",
            "type": "debugpy",
            "request": "launch",
            "program": "${'$'}{file}"
        },
        {
            "name": "Python Debugger: Current File with Arguments",
            "type": "debugpy",
            "request": "launch",
            "program": "${'$'}{file}",
            "args": ["--example", "value"]
        },
        {
            "name": "Node.js: Current File",
            "type": "node",
            "request": "launch",
            "program": "${'$'}{file}"
        },
        {
            // Start the program with: python -m debugpy --listen 5678 --wait-for-client app.py
            "name": "Python Debugger: Attach (port 5678)",
            "type": "debugpy",
            "request": "attach",
            "connect": { "host": "localhost", "port": 5678 }
        }
    ]
}
"""

/**
 * VS Code's inline values where no language provides them: on each line of the paused function
 * up to the paused line ([pausedLine], 0-based), the names there the frame knows ([values]), as
 * `name = value`. The function starts at the closest line above, less indented, that opens a
 * `def`, `function`, `fn` or `func`; at most [maxLines] lines back otherwise.
 */
fun inlineDebugValues(lines: List<String>, pausedLine: Int, values: Map<String, String>, maxLines: Int = 60): Map<Int, String> {
    if (pausedLine !in lines.indices || values.isEmpty()) return emptyMap()
    val indent = lines[pausedLine].indentation()
    var start = (pausedLine - maxLines).coerceAtLeast(0)
    for (line in pausedLine - 1 downTo start) {
        val text = lines[line]
        if (text.isNotBlank() && text.indentation() < indent && FUNCTION_START.containsMatchIn(text)) {
            start = line
            break
        }
    }
    return (start..pausedLine).mapNotNull { line ->
        val code = lines[line].substringBefore('#').let { if (it.trimStart().startsWith("//")) "" else it }
        val names = IDENTIFIER.findAll(code.replace(STRING_LITERAL, "")).map { it.value }.distinct().filter { it in values }.take(4).toList()
        if (names.isEmpty()) null else line to names.joinToString(", ") { name -> "$name = ${values.getValue(name).shortened(40)}" }
    }.toMap()
}

private fun String.indentation(): Int = takeWhile { it == ' ' || it == '\t' }.length
private fun String.shortened(max: Int): String = if (length <= max) this else take(max - 1) + "…"
// The last alternative is a C or C++ function definition: unindented, ending at its parameters.
private val FUNCTION_START = Regex("""^\s*(async\s+)?(def|function|fn|func)\b|=>\s*\{?\s*$|^[A-Za-z_][^;=#(]*\([^;]*\)\s*(const\s*)?\{?\s*$""")
private val IDENTIFIER = Regex("""[A-Za-z_][A-Za-z0-9_]*""")
private val STRING_LITERAL = Regex(""""(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*'""")

fun parseStackFrames(body: JsonObject): List<DebugFrame> =
    body.getAsJsonArray("stackFrames")?.mapNotNull { element ->
        val frame = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        DebugFrame(
            id = frame.get("id").asInt,
            name = frame.get("name")?.asString ?: "?",
            path = frame.getAsJsonObject("source")?.get("path")?.takeUnless { it.isJsonNull }?.asString,
            line = frame.get("line")?.asInt ?: 0,
            column = frame.get("column")?.asInt ?: 0,
        )
    }.orEmpty()

fun parseScopes(body: JsonObject): List<DebugVariable> =
    body.getAsJsonArray("scopes")?.map { element ->
        val scope = element.asJsonObject
        DebugVariable(scope.get("name").asString, "", null, scope.get("variablesReference")?.asInt ?: 0, depth = 0)
    }.orEmpty()

fun parseVariables(body: JsonObject, depth: Int, parentReference: Int = 0): List<DebugVariable> =
    body.getAsJsonArray("variables")?.map { element ->
        val variable = element.asJsonObject
        DebugVariable(
            name = variable.get("name").asString,
            value = variable.get("value")?.asString.orEmpty(),
            type = variable.get("type")?.takeUnless { it.isJsonNull }?.asString,
            reference = variable.get("variablesReference")?.asInt ?: 0,
            depth = depth,
            parentReference = parentReference,
        )
    }.orEmpty()

/** The adapter types that run on debugpy (the Python extension's old and new names). */
val PYTHON_DEBUG_TYPES = setOf("debugpy", "python")
/** The types js-debug takes for Node.js ("node" is VS Code's name for its pwa-node). */
val NODE_DEBUG_TYPES = setOf("pwa-node", "node")

/**
 * A js-debug configuration ready to send: VS Code's "node" type as js-debug's own, the
 * workspace as the working folder, and the program's console and stdout in the Debug console.
 */
fun nodeDebugArguments(configuration: JsonObject, workspace: String): JsonObject = configuration.deepCopy().apply {
    addProperty("type", "pwa-node")
    addProperty("console", "internalConsole")
    if (!has("outputCapture")) addProperty("outputCapture", "std")
    if (get("request")?.asString != "attach" && !has("cwd")) addProperty("cwd", workspace)
}

/** CodeLLDB's type, for C, C++ and Rust. */
val LLDB_DEBUG_TYPES = setOf("lldb")

/**
 * A CodeLLDB configuration ready to send: the program's output in the Debug console (the app has
 * no terminal to hand it), the workspace as the working folder. `sourceLanguages` is the
 * adapter's setting, not the launch's, as CodeLLDB's extension moves it ([lldbAdapterSettings]).
 */
fun lldbDebugArguments(configuration: JsonObject, workspace: String): JsonObject = configuration.deepCopy().apply {
    addProperty("terminal", "console")
    remove("sourceLanguages")
    if (get("request")?.asString != "attach" && !has("cwd")) addProperty("cwd", workspace)
}

/** CodeLLDB's adapter settings (`--settings`) as its extension passes them by default. */
fun lldbAdapterSettings(configuration: JsonObject): JsonObject = JsonObject().apply {
    addProperty("evaluateForHovers", true)
    addProperty("commandCompletions", true)
    configuration.get("sourceLanguages")?.takeIf { it.isJsonArray }?.let { add("sourceLanguages", it.deepCopy()) }
}

/** The package's name in a Cargo.toml (its binary's name under target/debug). */
fun cargoPackageName(manifest: String): String? {
    var inPackage = false
    manifest.lineSequence().forEach { raw ->
        val line = raw.substringBefore('#').trim()
        if (line.startsWith("[")) inPackage = line == "[package]"
        if (inPackage) CARGO_NAME.matchEntire(line)?.let { return it.groupValues[1] }
    }
    return null
}

private val CARGO_NAME = Regex("""name\s*=\s*"([^"]+)"""")

/** The files C/C++: Build and Debug Current File compiles: g++ for C++, gcc for C. */
val C_SOURCE_EXTENSIONS = setOf("c", "cpp", "cc", "cxx", "c++")

/**
 * The build the C/C++ configuration runs first, as VS Code's "C/C++: gcc build active file" task
 * does: [file] with debug information, beside it without its extension.
 */
fun cBuildCommand(file: String): String {
    val compiler = if (file.substringAfterLast('.') == "c") "gcc" else "g++"
    val output = file.substringBeforeLast('.')
    return "$compiler -g -O0 -Wall ${shellQuote(file)} -o ${shellQuote(output)}"
}

private fun shellQuote(text: String): String = "'" + text.replace("'", "'\\''") + "'"

const val CURRENT_FILE_CONFIGURATION = "Python: Current File"
const val NODE_CURRENT_FILE = "Node.js: Current File"
const val C_CURRENT_FILE = "C/C++: Build and Debug Current File"
const val RUST_CARGO_PROJECT = "Rust: Build and Debug Cargo Project"

/** VS Code's dynamic configurations, offered beside launch.json's. */
fun builtInDebugConfigurations(): List<DebugConfiguration> = listOf(
    DebugConfiguration(
        CURRENT_FILE_CONFIGURATION,
        JsonParser.parseString("""{"name":"$CURRENT_FILE_CONFIGURATION","type":"debugpy","request":"launch","program":"${'$'}{file}"}""").asJsonObject,
    ),
    DebugConfiguration(
        NODE_CURRENT_FILE,
        JsonParser.parseString("""{"name":"$NODE_CURRENT_FILE","type":"pwa-node","request":"launch","program":"${'$'}{file}"}""").asJsonObject,
    ),
    DebugConfiguration(
        C_CURRENT_FILE,
        JsonParser.parseString(
            """{"name":"$C_CURRENT_FILE","type":"lldb","request":"launch","program":"${'$'}{fileDirname}/${'$'}{fileBasenameNoExtension}","cwd":"${'$'}{fileDirname}"}""",
        ).asJsonObject,
    ),
    // The program is the crate's binary, found once its Cargo.toml is read.
    DebugConfiguration(
        RUST_CARGO_PROJECT,
        JsonParser.parseString("""{"name":"$RUST_CARGO_PROJECT","type":"lldb","request":"launch","sourceLanguages":["rust"]}""").asJsonObject,
    ),
    DebugConfiguration(
        "Python: Attach (port 5678)",
        JsonParser.parseString("""{"name":"Python: Attach (port 5678)","type":"debugpy","request":"attach","connect":{"host":"localhost","port":5678}}""").asJsonObject,
    ),
)
