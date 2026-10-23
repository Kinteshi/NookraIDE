package com.avfstudio.nativeide.notebook

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.UUID

enum class CellType(val key: String) {
    Code("code"),
    Markdown("markdown"),
    Raw("raw"),
}

class NotebookCell(
    val id: String,
    type: CellType,
    source: String,
    outputs: List<JsonObject> = emptyList(),
    executionCount: Int? = null,
    val metadata: JsonObject = JsonObject(),
    val attachments: JsonObject? = null,
) {
    var type by mutableStateOf(type)
    var source by mutableStateOf(source)
    val outputs = mutableStateListOf<JsonObject>().apply { addAll(outputs) }
    var executionCount by mutableStateOf(executionCount)
    var running by mutableStateOf(false)
    var queued by mutableStateOf(false)

    companion object {
        fun empty(type: CellType = CellType.Code) =
            NotebookCell(UUID.randomUUID().toString().substring(0, 8), type, "")
    }
}

/** An nbformat 4 notebook. Unknown metadata is carried through untouched so Git diffs stay small. */
class NotebookDocument(
    val path: String,
    cells: List<NotebookCell>,
    val metadata: JsonObject,
    private val nbformatMinor: Int,
) {
    val cells = mutableStateListOf<NotebookCell>().apply { addAll(cells) }
    var dirty by mutableStateOf(false)

    val kernelName: String?
        get() = metadata.getAsJsonObject("kernelspec")?.get("name")?.takeIf { it.isJsonPrimitive }?.asString

    fun setKernel(name: String, displayName: String, language: String) {
        metadata.add("kernelspec", JsonObject().apply {
            addProperty("name", name)
            addProperty("display_name", displayName)
            addProperty("language", language)
        })
        dirty = true
    }

    fun toJson(): String {
        val root = JsonObject()
        root.add("cells", JsonArray().apply {
            cells.forEach { cell ->
                add(JsonObject().apply {
                    addProperty("cell_type", cell.type.key)
                    addProperty("id", cell.id)
                    add("metadata", cell.metadata)
                    add("source", sourceLines(cell.source))
                    cell.attachments?.let { add("attachments", it) }
                    if (cell.type == CellType.Code) {
                        cell.executionCount?.let { addProperty("execution_count", it) } ?: add("execution_count", JsonNull.INSTANCE)
                        add("outputs", JsonArray().apply { cell.outputs.forEach(::add) })
                    }
                })
            }
        })
        root.add("metadata", metadata)
        root.addProperty("nbformat", 4)
        // Cell ids need nbformat 4.5.
        root.addProperty("nbformat_minor", maxOf(nbformatMinor, 5))
        return gson.toJson(root) + "\n"
    }

    companion object {
        private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create()

        fun parse(path: String, text: String): NotebookDocument {
            val root = if (text.isBlank()) JsonObject() else JsonParser.parseString(text).asJsonObject
            val major = root.get("nbformat")?.asInt ?: 4
            require(major == 4) { "Only nbformat 4 notebooks are supported (found $major)" }
            val cells = root.getAsJsonArray("cells")?.mapNotNull { element ->
                val cell = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                NotebookCell(
                    id = cell.get("id")?.takeIf { it.isJsonPrimitive }?.asString
                        ?: UUID.randomUUID().toString().substring(0, 8),
                    type = CellType.entries.firstOrNull { it.key == cell.get("cell_type")?.asString } ?: CellType.Raw,
                    source = multiline(cell.get("source")),
                    outputs = cell.getAsJsonArray("outputs")?.mapNotNull { it.takeIf { o -> o.isJsonObject }?.asJsonObject }
                        .orEmpty(),
                    executionCount = cell.get("execution_count")?.takeIf { it.isJsonPrimitive }?.asInt,
                    metadata = cell.getAsJsonObject("metadata") ?: JsonObject(),
                    attachments = cell.getAsJsonObject("attachments"),
                )
            }.orEmpty().ifEmpty { listOf(NotebookCell.empty()) }
            return NotebookDocument(
                path,
                cells,
                root.getAsJsonObject("metadata") ?: JsonObject(),
                root.get("nbformat_minor")?.asInt ?: 5,
            )
        }

        /** nbformat stores multi-line strings either as one string or as a list of lines. */
        fun multiline(value: JsonElement?): String = when {
            value == null || value.isJsonNull -> ""
            value.isJsonArray -> value.asJsonArray.joinToString("") { it.asString }
            else -> value.asString
        }

        private fun sourceLines(source: String): JsonArray = JsonArray().apply {
            var start = 0
            while (start < source.length) {
                val end = source.indexOf('\n', start).let { if (it < 0) source.length else it + 1 }
                add(source.substring(start, end))
                start = end
            }
        }
    }
}
