package io.github.kinteshi.quirecode.workbench

import com.aardarch.aardink.core.CodeDocument
import com.aardarch.aardink.core.CodeLens
import com.aardarch.aardink.core.InlayHint
import com.aardarch.aardink.core.CompletionItem
import com.aardarch.aardink.core.CompletionKind
import com.aardarch.aardink.core.FormattingOptions
import com.aardarch.aardink.core.HoverDoc
import com.aardarch.aardink.core.LanguageService
import com.aardarch.aardink.core.ParameterInformation
import com.aardarch.aardink.core.SignatureHelp
import com.aardarch.aardink.core.SignatureInformation
import com.aardarch.aardink.core.TextEdit
import io.github.kinteshi.quirecode.document.DocumentSession
import io.github.kinteshi.quirecode.editor.ClosingOvertype
import io.github.kinteshi.quirecode.editor.LanguageRules
import io.github.kinteshi.quirecode.editor.LspPosition
import io.github.kinteshi.quirecode.editor.LspRange
import io.github.kinteshi.quirecode.language.parseInlayHints
import io.github.kinteshi.quirecode.editor.LspTextEdit
import io.github.kinteshi.quirecode.editor.ParsedSnippet
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val REQUEST_TIMEOUT_MS = 15_000L

/**
 * What the app's language servers know, for the Aardink editor: completion (with the file's snippets),
 * hover and signature help as Markdown, through the workbench's language clients. What the
 * workbench does itself stays there (go to definition, rename, code actions, format, problems,
 * occurrences), and draws its own cards over the editor (hover, parameter hints).
 * The file's `language-configuration.json` ([LanguageRules]) gives closing pairs and Enter's indentation.
 */
internal class AardinkLanguageService(
    private val controller: WorkbenchController,
    private val document: DocumentSession,
    /** The file's `language-configuration.json`, for closing pairs and Enter; null for none. */
    private val rules: LanguageRules?,
    /** What kind of token is at an offset (`string.…`, `comment.…`), for a pair's `notIn`. */
    val tokenKindAt: (Int) -> String?,
    /** Told of each closer this inserts, so typing it again steps over it. */
    private val overtype: ClosingOvertype? = null,
    /** Whether the workbench's own cards are in charge (Aardink's geometry is reachable): its hover and signature help then. */
    private val ownCards: () -> Boolean = { false },
) : LanguageService {
    private val provider = controller.completionProvider(document)
    /** The server's item behind each one shown, for `completionItem/resolve`. */
    private val serverItems = ConcurrentHashMap<CompletionItem, LspCompletionItem>()

    @Volatile
    override var completionsAreIncomplete: Boolean = false
        private set

    /** What typed after a word asks the server for suggestions. */
    val serverTriggerCharacters: Set<Char>
        get() = provider.triggerCharacters.mapNotNull { it.singleOrNull() }.toSet()

    /** Our list for a hardware keyboard is in charge ([KeyboardSuggestions]): Aardink's strip isn't opened by typing then. */
    @Volatile
    var ownList = false

    override val triggerCharacters: Set<Char>
        get() = if (ownList) emptySet() else serverTriggerCharacters

    override val supportsRename: Boolean = false

    override suspend fun completions(document: CodeDocument, cursorOffset: Int): List<CompletionItem> {
        val (line, column) = document.offsetToLineCol(cursorOffset)
        val list = provider.completions(line, column, null, completionsAreIncomplete)?.awaitOrNull()
        completionsAreIncomplete = list?.incomplete ?: false
        serverItems.clear()
        val fromServer = list?.items.orEmpty().sortedBy { it.sortText }.map { item ->
            completionItem(item, document).also { serverItems[it] = item }
        }
        return when (provider.snippetSuggestions) {
            SnippetSuggestions.None -> fromServer
            SnippetSuggestions.Top -> snippetItems(priority = 0) + fromServer.map { it.copy(sortPriority = 1) }
            SnippetSuggestions.Bottom -> fromServer + snippetItems(priority = 1)
            SnippetSuggestions.Inline -> (fromServer + snippetItems(priority = 0)).sortedBy { it.label.lowercase() }
        }
    }

    override suspend fun resolveCompletion(item: CompletionItem): CompletionItem {
        val serverItem = serverItems[item] ?: return item
        val client = controller.languageClientFor(this.document.path)?.takeIf { it.isReady && it.supportsCompletionResolve } ?: return item
        val resolved = client.resolveCompletion(serverItem.raw).awaitOrNull()?.takeIf { it.isJsonObject }?.asJsonObject ?: return item
        val documentation = resolved.get("documentation")?.let(::hoverContents)?.takeIf { it.isNotBlank() }
        val edits = parseCompletionItem(resolved)?.additionalEdits.orEmpty()
        return item.copy(
            documentation = documentation ?: item.documentation,
            additionalEdits = item.additionalEdits.ifEmpty { edits.map { textEdit(it, documentText()) } },
        )
    }

    override suspend fun hoverDoc(document: CodeDocument, offset: Int): HoverDoc? {
        if (!controller.settings.editorHover) return null
        // The touch menu's Info, while the workbench's card is in charge: that card, at the word.
        if (ownCards()) {
            val (line, column) = document.offsetToLineCol(offset)
            withContext(Dispatchers.Main) { controller.showHoverAt(this@AardinkLanguageService.document.path, LspPosition(line, column)) }
            return null
        }
        val client = controller.languageClientFor(this.document.path)?.takeIf { it.isReady } ?: return null
        val (line, column) = document.offsetToLineCol(offset)
        val reply = client.hover(this.document.path, LspPosition(line, column)).awaitOrNull()
        val markdown = reply?.takeIf { it.isJsonObject }?.asJsonObject?.get("contents")?.let(::hoverContents)
        // Aardink's card always draws a title with a line under it: the word, rather than an empty row.
        val word = wordRange(document, offset)?.let { document.subSequence(it.first, it.last + 1).toString() }.orEmpty()
        return markdown?.takeIf { it.isNotBlank() }?.let { HoverDoc(title = word, content = it) }
    }

    override suspend fun signatureHelp(document: CodeDocument, offset: Int): SignatureHelp? {
        // The workbench asks for parameter hints itself after `(` and `,`, and draws them at the caret.
        if (ownCards()) return null
        val client = controller.languageClientFor(this.document.path)?.takeIf { it.isReady } ?: return null
        val (line, column) = document.offsetToLineCol(offset)
        val context = JsonObject().apply {
            addProperty("triggerKind", 1)
            addProperty("isRetrigger", false)
        }
        return parseAardinkSignatureHelp(client.signatureHelp(this.document.path, LspPosition(line, column), context).awaitOrNull())
    }

    /** The server's hints for [range]'s lines, among the characters; Aardink asks around its view after each pause. */
    override suspend fun inlayHints(document: CodeDocument, range: IntRange): List<InlayHint> {
        if (!controller.settings.inlayHints) return emptyList()
        val client = controller.languageClientFor(this.document.path)?.takeIf { it.isReady && it.supportsInlayHints } ?: return emptyList()
        val (startLine, startColumn) = document.offsetToLineCol(range.first.coerceIn(0, document.length))
        val (endLine, endColumn) = document.offsetToLineCol((range.last + 1).coerceIn(0, document.length))
        val reply = client.inlayHints(this.document.path, LspRange(LspPosition(startLine, startColumn), LspPosition(endLine, endColumn))).awaitOrNull()
        return aardinkInlayHints(parseInlayHints(reply), document)
    }

    /**
     * Rows above lines: a Python file's cells (Run Cell, Run Above, Run Below, with the Interactive window's setting),
     * merge conflicts' Accept Current, Incoming and Both, and the server's lenses (reference counts) with the setting.
     * Aardink asks after a pause in typing; what's tapped is found again in the text as it is then.
     */
    override suspend fun codeLenses(document: CodeDocument): List<CodeLens> {
        val text = document.text
        val local = textLenses(text, document, this.document.languageId == "py" && controller.settings.interactiveCodeLens, controller)
        val fromServer = withContext(Dispatchers.Main) { controller.codeLensesFor(this@AardinkLanguageService.document) }.awaitOrNull().orEmpty()
        return local + fromServer.map { item ->
            CodeLens(offsetIn(document, item.position), item.title) { controller.post { controller.openCodeLens(item) } }
        }
    }

    /** A tag's name and its partner's (`editor.linkedEditing`), from the workbench's own tag matcher. */
    override suspend fun linkedEditingRanges(document: CodeDocument, offset: Int): com.aardarch.aardink.core.LinkedEditingRanges? {
        if (!controller.settings.linkedEditing || this.document.languageId !in LINKED_EDITING_LANGUAGES) return null
        return linkedTagRanges(document.text, offset)?.let { com.aardarch.aardink.core.LinkedEditingRanges(it) }
    }

    // The workbench reports problems itself, and formats through its own command.
    override suspend fun diagnostics(document: CodeDocument): List<com.aardarch.aardink.core.Diagnostic> = emptyList()

    override suspend fun format(document: CodeDocument, options: FormattingOptions): String = document.text

    override val formatOnTypeCharacters: Set<Char>
        get() = if (!controller.settings.formatOnType) emptySet() else controller.languageClientFor(this.document.path)?.takeIf { it.isReady }?.onTypeTriggerCharacters.orEmpty()

    override suspend fun formatOnType(
        document: CodeDocument,
        offset: Int,
        typed: Char,
        options: FormattingOptions,
    ): List<TextEdit> {
        if (!controller.settings.formatOnType) return emptyList()
        val client = controller.languageClientFor(this.document.path)?.takeIf { it.isReady && typed in it.onTypeTriggerCharacters } ?: return emptyList()
        val (line, column) = document.offsetToLineCol(offset.coerceIn(0, document.length))
        val edits = client.onTypeFormatting(this.document.path, LspPosition(line, column), typed.toString(), options.tabSize, options.insertSpaces).awaitOrNull().orEmpty()
        return edits.map { textEdit(it, document.text) }
    }

    /** The previous line's indentation, more or less as the file's language configuration says after `:` and the like. */
    override fun smartIndent(document: CodeDocument, lineIndex: Int, options: FormattingOptions): String {
        val before = document.lineText((lineIndex - 1).coerceAtLeast(0))
        val after = if (lineIndex < document.lineCount) document.lineText(lineIndex) else ""
        val unit = if (options.insertSpaces) " ".repeat(options.tabSize) else "\t"
        return rules?.enterIndentation(before, after, unit) ?: before.takeWhile { it == ' ' || it == '\t' }
    }

    override fun autoClose(document: CodeDocument, offset: Int, charTyped: Char): String? =
        rules?.autoClose(document, offset, charTyped, tokenKindAt)?.also { overtype?.expect(offset + 1, it, closerAt = offset + 1, closer = it) }

    override fun surroundingClose(typed: Char): String? = rules?.surroundingPairs?.get(typed)

    private fun snippetItems(priority: Int): List<CompletionItem> = provider.snippets().flatMap { snippet ->
        snippet.prefixes.map { prefix ->
            CompletionItem(
                label = prefix,
                kind = CompletionKind.Snippet,
                insertText = snippet.body,
                documentation = snippet.description,
                isSnippet = true,
                detail = snippet.name,
                sortPriority = priority,
            )
        }
    }

    private fun documentText(): String = document.text

    private suspend fun <T> CompletableFuture<T>.awaitOrNull(): T? =
        runCatching { withTimeoutOrNull(REQUEST_TIMEOUT_MS) { await() } }.getOrNull()
}

/** LSP hints as Aardink shows them, at their offsets in [document]. */
internal fun aardinkInlayHints(hints: List<io.github.kinteshi.quirecode.language.InlayHint>, document: CodeDocument): List<InlayHint> =
    hints.map { InlayHint(offsetIn(document, it.position), it.label, it.tooltip) }

/** [position] in [document], clamped to its lines and their lengths. */
internal fun offsetIn(document: CodeDocument, position: LspPosition): Int {
    val line = position.line.coerceIn(0, document.lineCount - 1)
    return document.lineColToOffset(line, position.character.coerceIn(0, document.lineText(line).length))
}

/**
 * The lenses the text itself gives: each Python cell's runs ([cells]), and each merge conflict's choices. A tap finds the
 * cell or conflict again by its place in the text as it is then, since lines may have moved.
 */
internal fun textLenses(text: String, document: CodeDocument, cells: Boolean, controller: WorkbenchController?): List<CodeLens> {
    val lenses = mutableListOf<CodeLens>()
    if (cells) {
        pythonCells(text).withIndex().filter { !it.value.markdown }.forEach { (index, cell) ->
            val at = document.lineStart(cell.marker)
            listOf("Run Cell" to CellRange.Current, "Run Above" to CellRange.Above, "Run Below" to CellRange.CurrentAndBelow).forEach { (title, range) ->
                lenses += CodeLens(at, title) {
                    controller?.post { controller.activePythonCells().getOrNull(index)?.let { controller.runPythonCell(it, range) } }
                }
            }
        }
    }
    if ("<<<<<<<" in text) {
        findConflicts(text).forEachIndexed { index, conflict ->
            val at = document.lineStart(conflict.start)
            listOf("Accept Current" to ConflictChoice.Current, "Accept Incoming" to ConflictChoice.Incoming, "Accept Both" to ConflictChoice.Both).forEach { (title, choice) ->
                lenses += CodeLens(at, title) {
                    controller?.post { controller.activeConflicts().getOrNull(index)?.let { controller.resolveConflict(it, choice) } }
                }
            }
        }
    }
    return lenses
}

/** A server's completion item as Aardink lists it; [document] places its edits. */
internal fun completionItem(item: LspCompletionItem, document: CodeDocument): CompletionItem {
    fun offset(position: LspPosition): Int {
        val line = position.line.coerceIn(0, document.lineCount - 1)
        return document.lineColToOffset(line, position.character.coerceIn(0, document.lineText(line).length))
    }
    return CompletionItem(
        label = item.label,
        kind = completionKind(item.kind),
        insertText = item.insertText,
        filterText = item.filterText,
        replaceRange = item.insertRange?.let { offset(it.start) until offset(it.end) },
        additionalEdits = item.additionalEdits.map { edit -> TextEdit(offset(edit.range.start) until offset(edit.range.end), edit.newText) },
        isSnippet = item.snippet,
        detail = listOfNotNull(item.labelDetail, item.description).joinToString(" ").ifBlank { null },
    )
}

private fun textEdit(edit: LspTextEdit, text: String): TextEdit {
    val document = CodeDocument(text)
    fun offset(position: LspPosition): Int {
        val line = position.line.coerceIn(0, document.lineCount - 1)
        return document.lineColToOffset(line, position.character.coerceIn(0, document.lineText(line).length))
    }
    return TextEdit(offset(edit.range.start) until offset(edit.range.end), edit.newText)
}

/** LSP's `CompletionItemKind` as Aardink's. */
internal fun completionKind(kind: Int): CompletionKind = when (kind) {
    2 -> CompletionKind.Method
    3 -> CompletionKind.Function
    4 -> CompletionKind.Constructor
    5 -> CompletionKind.Field
    6 -> CompletionKind.Variable
    7 -> CompletionKind.Class
    8 -> CompletionKind.Interface
    9 -> CompletionKind.Module
    10 -> CompletionKind.Property
    12 -> CompletionKind.Value
    13 -> CompletionKind.Enum
    14 -> CompletionKind.Keyword
    15 -> CompletionKind.Snippet
    16 -> CompletionKind.Color
    17 -> CompletionKind.File
    18 -> CompletionKind.Reference
    19 -> CompletionKind.Folder
    20 -> CompletionKind.EnumMember
    21 -> CompletionKind.Constant
    22 -> CompletionKind.Struct
    23 -> CompletionKind.Event
    24 -> CompletionKind.Operator
    else -> CompletionKind.Text
}

/** A `textDocument/signatureHelp` reply as Aardink shows it; null when there's no signature. */
internal fun parseAardinkSignatureHelp(reply: JsonElement?): SignatureHelp? {
    val help = reply?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
    val signatures = help.getAsJsonArray("signatures")?.mapNotNull { element ->
        val signature = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        val label = signature.get("label")?.takeIf { it.isJsonPrimitive }?.asString ?: return@mapNotNull null
        val parameters = signature.getAsJsonArray("parameters")?.mapNotNull { parameter ->
            val value = parameter.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val name = value.get("label")
            val range = name?.takeIf { it.isJsonArray && it.asJsonArray.size() == 2 }?.asJsonArray
                ?.let { it[0].asInt until it[1].asInt }
            ParameterInformation(
                label = if (range != null) label.substring(range.first.coerceIn(0, label.length), range.last.plus(1).coerceIn(0, label.length)) else name?.asString.orEmpty(),
                documentation = value.get("documentation")?.let(::hoverContents)?.takeIf { it.isNotBlank() },
                labelRange = range,
            )
        }.orEmpty()
        SignatureInformation(label, signature.get("documentation")?.let(::hoverContents)?.takeIf { it.isNotBlank() }, parameters)
    }.orEmpty()
    if (signatures.isEmpty()) return null
    val active = help.get("activeSignature")?.takeIf { it.isJsonPrimitive }?.asInt?.coerceIn(0, signatures.size - 1) ?: 0
    val parameter = help.getAsJsonArray("signatures")[active].asJsonObject.get("activeParameter")?.takeIf { it.isJsonPrimitive }?.asInt
        ?: help.get("activeParameter")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
    return SignatureHelp(signatures, active, parameter)
}

/** A parsed snippet back in VS Code's syntax, for Aardink's own snippet engine: its text, stops and mirrors. */
internal fun snippetSyntax(snippet: ParsedSnippet): String {
    data class Mark(val at: Int, val end: Int, val index: Int, val first: Boolean, val choices: List<String>?)
    val marks = snippet.stops.flatMap { stop ->
        stop.spans.mapIndexed { i, span -> Mark(span.start, span.end, stop.index, i == 0, stop.choices) }
    }.sortedBy { it.at }
    fun escape(text: String) = text.replace("\\", "\\\\").replace("$", "\\$").replace("}", "\\}")
    val out = StringBuilder()
    var at = 0
    for (mark in marks) {
        if (mark.at < at) continue
        out.append(escape(snippet.text.substring(at, mark.at)))
        val inner = snippet.text.substring(mark.at, mark.end)
        when {
            !mark.first || (inner.isEmpty() && mark.choices == null) -> out.append("\${").append(mark.index).append('}')
            mark.choices != null -> out.append("\${").append(mark.index).append('|')
                .append(mark.choices.joinToString(",") { it.replace(",", "\\,").replace("|", "\\|") }).append("|}")
            else -> out.append("\${").append(mark.index).append(':').append(escape(inner)).append('}')
        }
        at = mark.end
    }
    out.append(escape(snippet.text.substring(at)))
    return out.toString()
}
