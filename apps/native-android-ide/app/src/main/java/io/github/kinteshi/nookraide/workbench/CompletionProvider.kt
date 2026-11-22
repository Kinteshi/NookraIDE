package io.github.kinteshi.nookraide.workbench

import java.util.concurrent.CompletableFuture

/** What the editor's suggestions ask the workbench (its language servers) for. */
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
    fun prepareSnippet(body: String, line: Int, column: Int): io.github.kinteshi.nookraide.editor.ParsedSnippet
}
