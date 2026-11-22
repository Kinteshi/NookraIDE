package io.github.kinteshi.nookraide.notebook

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** One name in the kernel's namespace, as the Variables view lists it. */
data class KernelVariable(
    val name: String,
    val type: String,
    /** Rows × columns for arrays and DataFrames, the length of a collection or string; empty otherwise. */
    val size: String,
    /** A short repr (or a DataFrame's columns, an array's dtype). */
    val value: String,
    /** A DataFrame, Series or 1–2 dimensional array that opens in the data viewer. */
    val viewable: Boolean,
)

/** What the bridge's `variables` returned: the list, whether it stopped at its limit, or that the kernel isn't Python. */
data class KernelVariables(val variables: List<KernelVariable>, val truncated: Boolean, val unsupported: Boolean = false)

fun parseKernelVariables(result: JsonElement?): KernelVariables {
    val body = result?.takeIf { it.isJsonObject }?.asJsonObject ?: return KernelVariables(emptyList(), false)
    if (body.get("unsupported")?.asBoolean == true) return KernelVariables(emptyList(), false, unsupported = true)
    val variables = body.getAsJsonArray("variables")?.mapNotNull { element ->
        val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        fun text(name: String) = item.get(name)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        KernelVariable(
            name = text("name").ifEmpty { return@mapNotNull null },
            type = text("type"),
            size = text("size"),
            value = text("value"),
            viewable = item.get("viewable")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
        )
    }.orEmpty()
    return KernelVariables(variables, body.get("truncated")?.asBoolean ?: false)
}

/**
 * A page of a DataFrame or array the bridge sent for the data viewer, as the notebook's native
 * table: [total] rows in all, [matched] after the filter, the page starting at [offset].
 * [pages] is false from a bridge that only sends the first rows (guest tools before batch 30).
 */
data class VariableTable(
    val table: OutputTable,
    val total: Int,
    val matched: Int = total,
    val offset: Int = 0,
    /** Why the filter wasn't read as a pandas query (it was matched as text instead). */
    val filterError: String? = null,
    val pages: Boolean = false,
)

fun parseVariableTable(result: JsonElement?): VariableTable? {
    val body = result?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
    val columns = body.getAsJsonArray("columns")?.map { it.asString } ?: return null
    val index = body.getAsJsonArray("index")?.map { it.asString }.orEmpty()
    val head = listOf(
        listOf(TableCell(body.text("index_name"), header = true)) + columns.map { TableCell(it, header = true) },
    )
    val rows = body.getAsJsonArray("rows")?.mapIndexed { row, values ->
        listOf(TableCell(index.getOrElse(row) { row.toString() }, header = true)) +
            values.asJsonArray.map { value ->
                TableCell(if (value.isJsonNull) "None" else value.asJsonPrimitive.let { if (it.isNumber) formatNumber(it.asString) else it.asString }, header = false)
            }
    }.orEmpty()
    val total = body.get("total")?.asInt ?: rows.size
    return VariableTable(
        OutputTable(head, rows, caption = ""),
        total = total,
        matched = body.get("matched")?.takeIf { it.isJsonPrimitive }?.asInt ?: total,
        offset = body.get("offset")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0,
        filterError = body.text("error").ifEmpty { null },
        pages = body.has("matched"),
    )
}

private fun JsonObject.text(name: String) = get(name)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

/** Whole numbers stay as they are; long float tails are cut to six significant decimals, as pandas prints them. */
private fun formatNumber(text: String): String {
    val value = text.toDoubleOrNull() ?: return text
    if ('.' !in text && 'e' !in text && 'E' !in text) return text
    return if (value != 0.0 && (kotlin.math.abs(value) >= 1e16 || kotlin.math.abs(value) < 1e-4)) "%.6e".format(java.util.Locale.ROOT, value)
    else "%.6f".format(java.util.Locale.ROOT, value).trimEnd('0').let { if (it.endsWith('.')) it + "0" else it }
}
