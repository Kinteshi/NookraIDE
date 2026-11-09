package io.github.kinteshi.quirecode.notebook

/**
 * One cell of an [OutputTable]. [header] is a `<th>` (column names, the index); [spanned] marks a
 * position covered by a colspan/rowspan from the left or above, which carries the same text so a
 * sorted table can still show it.
 */
data class TableCell(val text: String, val header: Boolean, val spanned: Boolean = false)

/** A DataFrame's HTML table as a grid: the column header rows, then the body rows, all as wide. */
data class OutputTable(val head: List<List<TableCell>>, val body: List<List<TableCell>>, val caption: String) {
    val columnCount: Int get() = (head + body).maxOfOrNull { it.size } ?: 0

    /** The leading columns of `<th>` in every row, the index; none when the whole table is that. */
    val indexColumns: Int
        get() {
            val count = body.minOfOrNull { row -> row.takeWhile(TableCell::header).size } ?: 0
            return if (count >= columnCount) 0 else count
        }
}

enum class SortDirection { Ascending, Descending }

/**
 * pandas and polars `_repr_html_` (`<table class="dataframe">`) as an [OutputTable]. Anything
 * else (a Styler, several tables, nested ones) returns null and keeps its HTML view.
 */
fun parseDataFrameHtml(html: String): OutputTable? {
    if (!html.contains("dataframe")) return null
    val rows = mutableListOf<Pair<Boolean, List<RawCell>>>()
    val captionParts = mutableListOf<String>()
    var tables = 0
    var depth = 0
    var inHead = false
    var skipping: String? = null
    var row: MutableList<RawCell>? = null
    var cell: RawCell? = null
    val outside = StringBuilder()
    var index = 0
    fun flushOutside() {
        decodeHtmlText(outside.toString()).takeIf { it.isNotEmpty() }?.let(captionParts::add)
        outside.clear()
    }
    fun text(value: String) {
        when {
            skipping != null -> Unit
            cell != null -> cell!!.text.append(value)
            depth == 0 -> outside.append(value)
        }
    }
    for (match in tagPattern.findAll(html)) {
        text(html.substring(index, match.range.first))
        index = match.range.last + 1
        val closing = match.groupValues[1] == "/"
        val name = match.groupValues[2].lowercase()
        val attributes = match.groupValues[3]
        if (name.isEmpty()) continue // a comment
        if (skipping != null) {
            if (closing && name == skipping) skipping = null
            continue
        }
        when (name) {
            "style", "script" -> if (!closing) skipping = name
            "table" -> if (closing) {
                depth--
            } else {
                if (depth > 0) return null
                if (!classPattern.containsMatchIn(attributes)) return null
                tables++
                if (tables > 1) return null
                flushOutside()
                depth++
            }
            "thead" -> inHead = !closing
            "tbody" -> if (!closing) inHead = false
            "tr" -> if (depth > 0) {
                // A cell left open (no </td>) ends with its row.
                cell?.let { open -> row?.add(open) }
                cell = null
                if (closing) {
                    row?.let { rows += inHead to it }
                    row = null
                } else {
                    row?.let { rows += inHead to it }
                    row = mutableListOf()
                }
            }
            "th", "td" -> if (depth > 0) {
                cell?.let { open -> row?.add(open) }
                cell = if (closing) {
                    null
                } else {
                    if (row == null) row = mutableListOf()
                    RawCell(
                        header = name == "th",
                        colspan = spanAttribute(attributes, "colspan"),
                        rowspan = spanAttribute(attributes, "rowspan"),
                    )
                }
            }
            "br" -> text(" ")
        }
    }
    text(html.substring(index))
    flushOutside()
    if (tables != 1) return null
    cell?.let { open -> row?.add(open) }
    row?.let { rows += inHead to it }
    if (rows.isEmpty()) return null
    val grid = layOut(rows.map { it.second })
    val width = grid.maxOf { it.size }
    val padded = grid.map { line -> line + List(width - line.size) { TableCell("", header = false) } }
    val headCount = rows.indexOfFirst { !it.first }.let { if (it < 0) rows.size else it }
    return OutputTable(padded.take(headCount), padded.drop(headCount), captionParts.joinToString(" · "))
}

/**
 * The body sorted by [column] (numbers by value, before text), without pandas' `...` rows;
 * missing values (empty, NaN, None) stay last either way, as `sort_values` puts them.
 */
fun OutputTable.sortedBody(column: Int, direction: SortDirection): List<List<TableCell>> {
    val (missing, present) = body.filterNot(::isEllipsisRow).partition { isMissing(it.getOrNull(column)?.text) }
    val comparator = Comparator<List<TableCell>> { a, b -> compareTableValues(a.getOrNull(column)?.text, b.getOrNull(column)?.text) }
    return present.sortedWith(if (direction == SortDirection.Ascending) comparator else comparator.reversed()) + missing
}

/** pandas' truncation row (every cell `...`). */
fun isEllipsisRow(row: List<TableCell>): Boolean = row.isNotEmpty() && row.all { it.text == "..." || it.text == "…" }

/** Tab-separated, header rows first, the way spreadsheets paste it. */
fun OutputTable.toTsv(rows: List<List<TableCell>> = body): String = buildString {
    head.forEach { line -> appendLine(line.joinToString("\t") { if (it.spanned) "" else it.text }) }
    rows.forEach { line -> appendLine(line.joinToString("\t") { it.text }) }
}.trimEnd('\n')

/** A number for sorting and right alignment: `1,234`, `-0.5`, `12%`, `1e-3`; NaN and text are null. */
fun tableNumber(text: String): Double? =
    text.trim().removeSuffix("%").replace(",", "").takeIf { it.isNotEmpty() }?.toDoubleOrNull()?.takeUnless { it.isNaN() }

private fun compareTableValues(a: String?, b: String?): Int {
    val x = a?.let(::tableNumber)
    val y = b?.let(::tableNumber)
    return when {
        x != null && y != null -> x.compareTo(y)
        x != null -> -1
        y != null -> 1
        else -> a.orEmpty().compareTo(b.orEmpty(), ignoreCase = true)
    }
}

private fun isMissing(value: String?): Boolean =
    value == null || value.isBlank() || value in setOf("NaN", "nan", "None", "null", "<NA>", "NaT")

private class RawCell(val header: Boolean, val colspan: Int, val rowspan: Int) {
    val text = StringBuilder()
}

/** Places cells on the grid, filling what each colspan/rowspan covers. */
private fun layOut(rows: List<List<RawCell>>): List<List<TableCell>> {
    val grid = mutableListOf<MutableMap<Int, TableCell>>()
    rows.forEachIndexed { r, line ->
        while (grid.size <= r) grid += mutableMapOf()
        var column = 0
        line.forEach { raw ->
            while (grid[r].containsKey(column)) column++
            val text = decodeHtmlText(raw.text.toString())
            for (dr in 0 until raw.rowspan) {
                while (grid.size <= r + dr) grid += mutableMapOf()
                for (dc in 0 until raw.colspan) {
                    grid[r + dr][column + dc] = TableCell(text, raw.header, spanned = dr > 0 || dc > 0)
                }
            }
            column += raw.colspan
        }
    }
    // A rowspan reaching past the last row would add rows nobody wrote.
    return grid.take(rows.size).map { cells ->
        val width = (cells.keys.maxOrNull() ?: -1) + 1
        List(width) { cells[it] ?: TableCell("", header = false) }
    }
}

private val tagPattern = Regex("""<!--[\s\S]*?-->|<(/?)([a-zA-Z][a-zA-Z0-9]*)([^>]*)>""")
private val classPattern = Regex("""class\s*=\s*["'][^"']*\bdataframe\b""")
private val entityPattern = Regex("""&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);""")
private val namedEntities = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "hellip" to "…", "times" to "×")

private fun spanAttribute(attributes: String, name: String): Int =
    Regex("""$name\s*=\s*["']?(\d+)""").find(attributes)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1, 1000) ?: 1

/** Entities decoded and whitespace collapsed, as a browser shows the text. */
internal fun decodeHtmlText(raw: String): String = raw.replace(entityPattern) { match ->
    val entity = match.groupValues[1]
    when {
        entity.startsWith("#x") || entity.startsWith("#X") -> entity.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
        entity.startsWith("#") -> entity.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) }
        else -> namedEntities[entity]
    } ?: match.value
}.replace(Regex("""\s+"""), " ").trim()
