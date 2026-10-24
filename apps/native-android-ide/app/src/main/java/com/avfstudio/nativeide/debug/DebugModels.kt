package com.avfstudio.nativeide.debug

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.gson.JsonArray
import com.google.gson.JsonObject

sealed interface DebugState {
    data object Idle : DebugState
    data object Starting : DebugState
    data object Running : DebugState
    data class Stopped(val threadId: Int, val reason: String, val description: String?) : DebugState
}

/** A stack frame; [line] and [column] are 1-based as requested in `initialize`. */
data class DebugFrame(val id: Int, val name: String, val path: String?, val line: Int, val column: Int)

/** A variable or scope; children load lazily when [reference] > 0 and the node is expanded. */
class DebugVariable(
    val name: String,
    val value: String,
    val type: String?,
    val reference: Int,
    val depth: Int,
) {
    val children = mutableStateListOf<DebugVariable>()
    var expanded by mutableStateOf(false)
}

data class DebugConsoleLine(val text: String, val kind: Kind) {
    enum class Kind { Output, Error, Input, Result, Info }
}

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

fun dapInitializeArguments(): JsonObject = JsonObject().apply {
    addProperty("clientID", "avf-studio")
    addProperty("clientName", "AVF Studio")
    addProperty("adapterID", "debugpy")
    addProperty("linesStartAt1", true)
    addProperty("columnsStartAt1", true)
    addProperty("pathFormat", "path")
    addProperty("supportsVariableType", true)
    addProperty("supportsRunInTerminalRequest", false)
}

fun setBreakpointsArguments(path: String, lines: Collection<Int>): JsonObject = JsonObject().apply {
    add("source", JsonObject().apply { addProperty("path", path) })
    add("breakpoints", JsonArray().apply { lines.sorted().forEach { line -> add(JsonObject().apply { addProperty("line", line) }) } })
}

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

fun parseVariables(body: JsonObject, depth: Int): List<DebugVariable> =
    body.getAsJsonArray("variables")?.map { element ->
        val variable = element.asJsonObject
        DebugVariable(
            name = variable.get("name").asString,
            value = variable.get("value")?.asString.orEmpty(),
            type = variable.get("type")?.takeUnless { it.isJsonNull }?.asString,
            reference = variable.get("variablesReference")?.asInt ?: 0,
            depth = depth,
        )
    }.orEmpty()
