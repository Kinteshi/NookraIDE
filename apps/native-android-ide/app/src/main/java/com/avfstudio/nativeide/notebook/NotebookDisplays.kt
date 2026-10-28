package com.avfstudio.nativeide.notebook

import com.google.gson.JsonObject

/**
 * Live output bookkeeping while a notebook runs: outputs shown with a display id
 * (`display(obj, display_id=True)`), which an update replaces wherever they are, and
 * `clear_output(wait=True)`, which empties a cell only when its next output arrives so a figure
 * redrawn in a loop doesn't flicker.
 */
class NotebookDisplays {
    private val shown = mutableMapOf<String, MutableList<JsonObject>>()
    private val pendingClears = mutableSetOf<String>()

    fun clear(cell: NotebookCell, wait: Boolean) {
        if (wait) {
            pendingClears += cell.id
        } else {
            pendingClears -= cell.id
            cell.outputs.clear()
        }
    }

    /** Empties [cell] if clear_output(wait=True) is waiting for its next output. */
    fun beforeOutput(cell: NotebookCell) {
        if (pendingClears.remove(cell.id)) cell.outputs.clear()
    }

    /** Adds [output] to [cell], after a pending clear, and remembers its [displayId]. */
    fun append(cell: NotebookCell, output: JsonObject, displayId: String?) {
        beforeOutput(cell)
        cell.outputs.add(output)
        if (displayId != null) shown.getOrPut(displayId) { mutableListOf() } += output
    }

    /** Replaces every output still shown with [displayId]; false when there is none. */
    fun update(cells: List<NotebookCell>, displayId: String, output: JsonObject): Boolean {
        val previous = shown[displayId] ?: return false
        val replaced = mutableListOf<JsonObject>()
        previous.forEach { old ->
            cells.forEach { cell ->
                val index = cell.outputs.indexOfFirst { it === old }
                if (index >= 0) {
                    val copy = output.deepCopy()
                    cell.outputs[index] = copy
                    replaced += copy
                }
            }
        }
        if (replaced.isEmpty()) shown.remove(displayId) else shown[displayId] = replaced
        return replaced.isNotEmpty()
    }
}
