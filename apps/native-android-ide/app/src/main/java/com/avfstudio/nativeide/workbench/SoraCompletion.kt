package com.avfstudio.nativeide.workbench

import android.os.Bundle
import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.lang.completion.CompletionCancelledException
import io.github.rosemoe.sora.lang.completion.CompletionItem
import io.github.rosemoe.sora.lang.completion.CompletionItemKind
import io.github.rosemoe.sora.lang.completion.CompletionPublisher
import io.github.rosemoe.sora.lang.completion.SimpleCompletionIconDrawer
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.text.ContentReference
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.component.EditorAutoCompletion
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Items the popup lists at most; the best ones come first. */
private const val MAX_SHOWN = 300
private const val REQUEST_TIMEOUT_MS = 15_000L
/** Ranks of one group (the server's items, or the snippets) stay below this. */
private const val RANK_GROUP = 1_000_000L

/** An item's place in the popup when the snippets show before the server's items arrive. */
private interface Ranked {
    var rank: Long
}

/** `adb shell setprop log.tag.AVFSUG DEBUG` logs suggest requests and what came back. */
private inline fun suggestLog(message: () -> String) {
    if (android.util.Log.isLoggable("AVFSUG", android.util.Log.DEBUG)) android.util.Log.d("AVFSUG", message())
}

/** What the suggest popup asks the workbench (its language servers) for. */
internal interface CompletionProvider {
    val languageId: String
    val triggerCharacters: Set<String>
    /** `editor.quickSuggestions`: pop up while typing; Ctrl+Space works either way. */
    val quickSuggestions: Boolean
    /** Null when no ready server completes this file; the grammar's word completion is used then. */
    fun completions(line: Int, column: Int, trigger: String?, incomplete: Boolean): CompletableFuture<LspCompletionList>?
    /** After an item is inserted: fetch its auto-import edits if the server sends them only on resolve. */
    fun accepted(item: LspCompletionItem)
    /** The snippets for this file's language (packs and the workspace's `.code-snippets` files). */
    fun snippets(): List<Snippet>
    /** `editor.snippetSuggestions`. */
    val snippetSuggestions: SnippetSuggestions
    /** A snippet body ready to insert at [line], [column]: indented like the line, variables filled in. */
    fun prepareSnippet(body: String, line: Int, column: Int): com.avfstudio.nativeide.editor.ParsedSnippet
}

/**
 * The editor language with the language server's completions in sora's suggest popup. sora asks
 * on its own thread after each edit; one list is kept per word being typed and filtered as it
 * grows (asking again only when the server said the list is incomplete), as VS Code does.
 */
internal class SoraCompletionLanguage(
    private val base: Language,
    private val provider: CompletionProvider,
    /** Called (on sora's thread) when items are about to show, to clear what would cover them. */
    private val onItems: () -> Unit = {},
) : Language by base {
    /** Set by Ctrl+Space: the next request shows the popup even with nothing typed. */
    @Volatile var invokedNext = false

    private data class Cached(val line: Int, val wordStart: Int, val before: String, val list: LspCompletionList)

    @Volatile private var cache: Cached? = null

    override fun requireAutoComplete(content: ContentReference, position: CharPosition, publisher: CompletionPublisher, extraArguments: Bundle) {
        val invoked = invokedNext.also { invokedNext = false }
        val lineText = content.getLine(position.line)
        val column = position.column.coerceIn(0, lineText.length)
        val wordStart = completionWordStart(lineText, column)
        val word = lineText.substring(wordStart, column)
        val before = lineText.substring(0, wordStart)
        val trigger = if (word.isEmpty()) provider.triggerCharacters.firstOrNull { before.endsWith(it) } else null
        val cached = cache?.takeIf { it.line == position.line && it.wordStart == wordStart && it.before == before }
        suggestLog { "request ${position.line}:$column word='$word' invoked=$invoked cached=${cached != null}" }
        if (!invoked && cached == null) {
            if (!provider.quickSuggestions) return
            if (word.isEmpty() && trigger == null) return
            if (word.first().isDigit()) return
            if (inLineComment(before, provider.languageId)) return
        }
        val snippets = snippetItems(word, wordStart, invoked)
        var snippetsShown = false
        val list = if (cached != null && !cached.list.incomplete && !invoked) {
            cached.list
        } else {
            val future = provider.completions(position.line, column, trigger, incomplete = cached?.list?.incomplete == true)
            if (future == null) {
                cache = null
                // No server: the snippets, then the grammar's words.
                if (snippets.isNotEmpty()) {
                    onItems()
                    publisher.addItems(snippets.map { it.second })
                }
                base.requireAutoComplete(content, position, publisher, extraArguments)
                return
            }
            // The snippets show at once and the server's items join them when it answers, as in
            // VS Code: a fresh server still indexing can take seconds.
            if (snippets.isNotEmpty() && !future.isDone) {
                publisher.setComparator(compareBy { (it as? Ranked)?.rank ?: Long.MAX_VALUE })
                snippets.forEachIndexed { index, (score, item) -> (item as Ranked).rank = snippetRank(index, score) }
                onItems()
                publisher.addItems(snippets.map { it.second })
                publisher.updateList(true)
                snippetsShown = true
            }
            await(future, publisher) ?: return.also { suggestLog { "no answer for '$word'" } }
        }
        cache = Cached(position.line, wordStart, before, list)
        val items = filterCompletions(list.items, word).take(MAX_SHOWN)
        suggestLog { "'$word': ${list.items.size} items, ${items.size} match, ${snippets.size} snippets${if (snippetsShown) " (shown first)" else ""}" }
        if (items.isEmpty() && snippets.isEmpty()) return
        // A newer key may have replaced this request while the server answered; its items would be stale.
        publisher.checkCancelled()
        val lspItems = items.mapIndexed { index, item ->
            SoraLspItem(item, word.length, wordStart, provider).apply { rank = lspRank(index, completionScore(word, item.filterText) ?: 0) }
        }
        if (snippetsShown) {
            // In their place among the snippets already shown.
            publisher.addItems(lspItems)
            return
        }
        onItems()
        publisher.addItems(
            when (provider.snippetSuggestions) {
                SnippetSuggestions.Top -> snippets.map { it.second } + lspItems
                SnippetSuggestions.Bottom, SnippetSuggestions.None -> lspItems + snippets.map { it.second }
                // Mixed by how well each matches what was typed (the server's order otherwise).
                SnippetSuggestions.Inline -> {
                    val scored = items.map { completionScore(word, it.filterText) ?: 0 }.zip(lspItems)
                    (scored + snippets).sortedByDescending { it.first }.map { it.second }
                }
            },
        )
    }

    /** Where an item goes when snippets were shown first: the same order as when all arrive together. */
    private fun snippetRank(index: Int, score: Int): Long = when (provider.snippetSuggestions) {
        SnippetSuggestions.Top -> index.toLong()
        SnippetSuggestions.Bottom, SnippetSuggestions.None -> RANK_GROUP + index
        SnippetSuggestions.Inline -> -score * RANK_GROUP + RANK_GROUP / 2 + index
    }

    private fun lspRank(index: Int, score: Int): Long = when (provider.snippetSuggestions) {
        SnippetSuggestions.Top -> RANK_GROUP + index
        SnippetSuggestions.Bottom, SnippetSuggestions.None -> index.toLong()
        SnippetSuggestions.Inline -> -score * RANK_GROUP + index
    }

    /** Snippets whose prefix matches the typed [word] (any with Ctrl+Space on nothing), with their match score. */
    private fun snippetItems(word: String, wordStart: Int, invoked: Boolean): List<Pair<Int, CompletionItem>> {
        if (provider.snippetSuggestions == SnippetSuggestions.None || (word.isEmpty() && !invoked)) return emptyList()
        return provider.snippets().flatMap { snippet ->
            snippet.prefixes.mapNotNull { prefix ->
                val score = if (word.isEmpty()) 0 else completionScore(word, prefix) ?: return@mapNotNull null
                score to SoraSnippetItem(snippet, prefix, word.length, wordStart, provider) as CompletionItem
            }
        }
    }

    /** Waits for the server (a fresh one indexing auto-imports can take seconds), giving up when sora cancels (another key) or after [REQUEST_TIMEOUT_MS]. */
    private fun <T> await(future: CompletableFuture<T>, publisher: CompletionPublisher): T? {
        val deadline = System.currentTimeMillis() + REQUEST_TIMEOUT_MS
        while (true) {
            try {
                return future.get(40, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                try {
                    publisher.checkCancelled()
                } catch (cancelled: CompletionCancelledException) {
                    suggestLog { "cancelled while waiting" }
                    future.cancel(false)
                    throw cancelled
                }
                if (System.currentTimeMillis() > deadline) return null
            } catch (_: ExecutionException) {
                return null
            } catch (_: InterruptedException) {
                // sora interrupts a request a newer key replaced.
                future.cancel(false)
                return null
            }
        }
    }
}

/**
 * sora's suggest popup with its first item selected after every refresh, as VS Code's is, so
 * Enter and Tab accept it (sora leaves nothing selected, and then Enter types a new line).
 */
internal class PreselectingCompletion(private val codeEditor: CodeEditor) : EditorAutoCompletion(codeEditor) {
    /** Only typing asks: an edit made while another view has the keys (Replace All, a rename) doesn't. */
    override fun requireCompletion() {
        if (codeEditor.hasFocus()) super.requireCompletion()
    }

    /**
     * sora refuses when no highlight span covers the cursor yet: a new line before it is
     * highlighted again, or a file with no grammar at all. Only a span marked so (a comment, a
     * string) keeps the popup away.
     */
    override fun checkNoCompletion(): Boolean {
        val span = io.github.rosemoe.sora.lang.styling.StylesUtils.getSpanForPosition(codeEditor.styles, codeEditor.cursor.left()) ?: return false
        return io.github.rosemoe.sora.lang.styling.TextStyle.isNoCompletion(span.style)
    }

    override fun updateCompletionWindowPosition(shouldSetSize: Boolean) {
        super.updateCompletionWindowPosition(shouldSetSize)
        if (currentSelection < 0 && adapter != null && adapter.count > 0) moveDown()
    }

    /**
     * sora accepts only while its request is alive; a list it shows after that request was
     * replaced (a fast key) would otherwise ignore Enter and Tab. Such an item is inserted here
     * at the cursor instead.
     */
    override fun select(pos: Int): Boolean {
        if (completionThread != null || pos < 0 || !isShowing || adapter == null || pos >= adapter.count) return super.select(pos)
        val item = adapter.getItem(pos)
        val text = codeEditor.text
        text.beginBatchEdit()
        try {
            item.performCompletion(codeEditor, text, codeEditor.cursor.left())
        } finally {
            text.endBatchEdit()
        }
        hide()
        return true
    }
}

/** LSP's CompletionItemKind (1 = Text … 25 = TypeParameter) as sora names it. */
private val LSP_KINDS = listOf(
    "Text", "Method", "Function", "Constructor", "Field", "Variable", "Class", "Interface", "Module", "Property",
    "Unit", "Value", "Enum", "Keyword", "Snippet", "Color", "File", "Reference", "Folder", "EnumMember",
    "Constant", "Struct", "Event", "Operator", "TypeParameter",
)

private fun soraKind(kind: Int): CompletionItemKind =
    LSP_KINDS.getOrNull(kind - 1)?.let { name -> CompletionItemKind.entries.firstOrNull { it.name == name } } ?: CompletionItemKind.Text

private class SoraLspItem(
    private val item: LspCompletionItem,
    prefix: Int,
    private val wordStart: Int,
    private val provider: CompletionProvider,
) : CompletionItem(item.label + item.labelDetail.orEmpty(), item.description?.take(48)), Ranked {
    override var rank = 0L

    init {
        prefixLength = prefix
        val itemKind = soraKind(item.kind)
        kind = itemKind
        icon = SimpleCompletionIconDrawer.draw(itemKind)
        sortText = item.sortText
    }

    /**
     * sora calls this inside one batch edit, so one Undo takes back the item and its imports.
     * It passes where the request was made; the cursor is where typing has reached since.
     */
    override fun performCompletion(editor: CodeEditor, text: Content, requestLine: Int, requestColumn: Int) {
        val line = requestLine
        val column = editor.cursor.takeIf { it.leftLine == requestLine }?.leftColumn ?: requestColumn
        val (range, insert) = completionEdit(item, line, wordStart, column)
        val native = editor as? NativeCodeEditor
        if (item.snippet && native != null) {
            // A snippet: its stops, Tab between them.
            val from = text.indexer.getCharIndex(range.start.line, range.start.character)
            val to = text.indexer.getCharIndex(line, column)
            native.insertSnippet(provider.prepareSnippet(item.insertText, range.start.line, range.start.character), from, to)
        } else {
            text.replace(range.start.line, range.start.character, line, column, insert)
            val stop = if (item.snippet) snippetToPlain(item.insertText).second else insert.length
            val head = insert.substring(0, stop.coerceIn(0, insert.length))
            val lines = head.split('\n')
            val cursorLine = range.start.line + lines.size - 1
            val cursorColumn = if (lines.size == 1) range.start.character + head.length else lines.last().length
            editor.setSelection(cursorLine, cursorColumn)
        }
        // Auto-imports: last first, so earlier positions stay right; the cursor follows them.
        item.additionalEdits.sortedWith(compareBy({ it.range.start.line }, { it.range.start.character })).asReversed().forEach { edit ->
            val (start, end) = edit.range
            if (start.line < text.lineCount && end.line < text.lineCount) {
                text.replace(start.line, start.character, end.line, end.character, edit.newText)
            }
        }
        provider.accepted(item)
    }
}

/** A snippet in the suggest popup: its prefix, its name beside it; accepting it starts its stops. */
private class SoraSnippetItem(
    private val snippet: Snippet,
    prefix: String,
    typed: Int,
    private val wordStart: Int,
    private val provider: CompletionProvider,
) : CompletionItem(prefix, snippet.name), Ranked {
    override var rank = 0L

    init {
        prefixLength = typed
        kind = CompletionItemKind.Snippet
        icon = SimpleCompletionIconDrawer.draw(CompletionItemKind.Snippet)
    }

    override fun performCompletion(editor: CodeEditor, text: Content, requestLine: Int, requestColumn: Int) {
        val native = editor as? NativeCodeEditor ?: return
        val column = editor.cursor.takeIf { it.leftLine == requestLine }?.leftColumn ?: requestColumn
        val from = text.indexer.getCharIndex(requestLine, wordStart.coerceAtMost(column))
        val to = text.indexer.getCharIndex(requestLine, column)
        native.insertSnippet(provider.prepareSnippet(snippet.body, requestLine, wordStart.coerceAtMost(column)), from, to)
    }
}
