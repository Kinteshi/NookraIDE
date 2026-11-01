package com.avfstudio.nativeide.notebook

import com.google.gson.JsonObject
import java.time.Instant

/** A finished run of a code cell: failed (an error, an interrupt) or done, and how long it took. */
data class CellRun(val failed: Boolean, val millis: Long?, val finishedAt: Long?)

/** How an output is drawn, picked from its menu; null keeps the default from Settings. */
data class OutputPresentation(
    val view: OutputView? = null,
    val expanded: Boolean = false,
    val sortColumn: Int? = null,
    val sortDirection: SortDirection = SortDirection.Ascending,
    /** A JSON tree's open nodes, by path (`$`, `$.a`, `$.a[0]`); the top level starts open. */
    val jsonExpanded: Set<String> = setOf("$"),
)

/** The views an output can be drawn with; [label] reads after "Show as". */
enum class OutputView(val label: String) {
    Plotly("Plotly chart"),
    Vega("Vega chart"),
    Image("image"),
    Svg("SVG image"),
    Table("table"),
    Html("HTML"),
    Latex("formula"),
    Json("JSON tree"),
    Markdown("Markdown"),
    Text("plain text"),
}

/** JupyterLab's `recordTiming` keys in a code cell's `metadata.execution`. */
private const val EXECUTION = "execution"
private const val EXECUTE_INPUT = "iopub.execute_input"
private const val STATUS_BUSY = "iopub.status.busy"
private const val STATUS_IDLE = "iopub.status.idle"
private const val EXECUTE_REPLY = "shell.execute_reply"

/** The run a notebook saved with timing (by JupyterLab, or here with the setting on), so it shows again on reopen. */
fun savedRun(metadata: JsonObject, outputs: List<JsonObject>): CellRun? {
    val execution = metadata.get(EXECUTION)?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
    fun instant(key: String) = execution.get(key)?.takeIf { it.isJsonPrimitive }?.asString
        ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
    val start = instant(EXECUTE_INPUT) ?: instant(STATUS_BUSY) ?: return null
    val end = instant(EXECUTE_REPLY) ?: instant(STATUS_IDLE) ?: return null
    return CellRun(outputs.any { it.get("output_type")?.asString == "error" }, (end - start).coerceAtLeast(0), end)
}

/** Writes a run's start and end the way JupyterLab does; [startedAt] and [finishedAt] are wall clock ms. */
fun recordTiming(metadata: JsonObject, startedAt: Long, finishedAt: Long) {
    val start = Instant.ofEpochMilli(startedAt).toString()
    val end = Instant.ofEpochMilli(finishedAt).toString()
    metadata.add(EXECUTION, JsonObject().apply {
        addProperty(STATUS_BUSY, start)
        addProperty(EXECUTE_INPUT, start)
        addProperty(EXECUTE_REPLY, end)
        addProperty(STATUS_IDLE, end)
    })
}

/** Drops a saved timing that a new run (or clearing outputs) made stale; true when there was one. */
fun clearTiming(metadata: JsonObject): Boolean = metadata.remove(EXECUTION) != null

fun savedOutputsHidden(metadata: JsonObject): Boolean =
    metadata.get("jupyter")?.takeIf { it.isJsonObject }?.asJsonObject?.get("outputs_hidden")
        ?.takeIf { it.isJsonPrimitive }?.asBoolean
        ?: metadata.get("collapsed")?.takeIf { it.isJsonPrimitive }?.asBoolean
        ?: false

/** JupyterLab writes both its own key and the classic Notebook's `collapsed`; expanding removes them. */
fun writeOutputsHidden(metadata: JsonObject, hidden: Boolean) {
    val jupyter = metadata.get("jupyter")?.takeIf { it.isJsonObject }?.asJsonObject
    if (hidden) {
        metadata.add("jupyter", (jupyter ?: JsonObject()).apply { addProperty("outputs_hidden", true) })
        metadata.addProperty("collapsed", true)
    } else {
        jupyter?.remove("outputs_hidden")
        if (jupyter != null && jupyter.size() == 0) metadata.remove("jupyter")
        metadata.remove("collapsed")
    }
}

/** "0.4s", "12.3s", "2m 05s", "1h 02m": the cell's run time in its gutter. */
fun formatRunDuration(millis: Long): String {
    val seconds = millis / 1000
    return when {
        millis < 60_000 -> "%.1fs".format(java.util.Locale.ROOT, millis / 1000.0)
        seconds < 3600 -> "%dm %02ds".format(java.util.Locale.ROOT, seconds / 60, seconds % 60)
        else -> "%dh %02dm".format(java.util.Locale.ROOT, seconds / 3600, seconds % 3600 / 60)
    }
}
