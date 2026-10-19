package com.avfstudio.nativeide.editor

data class AutoPairReplacement(
    val text: String,
    val insertionOffset: Int,
    val insertion: String,
    val selectionStart: Int,
    val selectionEnd: Int,
    val deleteOffset: Int? = null,
)

fun autoPairAfterInsertion(
    previousText: String,
    currentText: String,
    insertionOffset: Int,
    insertedText: String,
    previousSelection: IntRange?,
): AutoPairReplacement? {
    if (insertedText.length != 1 || insertionOffset !in 0..previousText.length) return null
    val closer = when (insertedText.single()) {
        '(' -> ')'
        '[' -> ']'
        '{' -> '}'
        '"' -> '"'
        '\'' -> '\''
        '`' -> '`'
        else -> return null
    }

    if (currentText.getOrNull(insertionOffset) != insertedText.single()) return null

    val selected = previousSelection
        ?.takeIf { !it.isEmpty() && it.first == insertionOffset && it.last < previousText.length }
        ?.let { previousText.substring(it.first, it.last + 1) }
        .orEmpty()
    val expectedCurrent = previousText.replaceRange(
        insertionOffset,
        insertionOffset + selected.length,
        insertedText,
    )
    if (currentText != expectedCurrent) return null

    // Only identical closers (quotes) are skipped; "(" before ")" must still pair so nesting works.
    if (selected.isEmpty() && closer == insertedText.single() && currentText.getOrNull(insertionOffset + 1) == closer) {
        return AutoPairReplacement(
            currentText,
            insertionOffset + 1,
            "",
            insertionOffset + 1,
            insertionOffset + 1,
        )
    }

    val insertedPairContent = selected + closer
    val newText = currentText.replaceRange(
        insertionOffset + 1,
        insertionOffset + 1,
        insertedPairContent,
    )
    val selectionStart = insertionOffset + 1
    return AutoPairReplacement(
        newText,
        selectionStart,
        insertedPairContent,
        selectionStart,
        selectionStart + selected.length,
    )
}

fun skipExistingCloserAfterInsertion(
    previousText: String,
    currentText: String,
    insertionOffset: Int,
    insertedText: String,
): AutoPairReplacement? {
    if (insertedText.length != 1 || insertionOffset !in previousText.indices) return null
    val closer = insertedText.single()
    if (previousText[insertionOffset] != closer) return null
    if (currentText != previousText.replaceRange(insertionOffset, insertionOffset, insertedText)) return null
    return AutoPairReplacement(
        previousText,
        insertionOffset,
        "",
        insertionOffset + 1,
        insertionOffset + 1,
        insertionOffset,
    )
}

/** Returns the offset of the closer to delete when backspace removed the opener of an empty pair. */
fun pairedCloserOffsetAfterDeletion(
    previousText: String,
    currentText: String,
    deletionOffset: Int,
    deletedText: String,
): Int? {
    if (deletedText.length != 1 || deletionOffset !in previousText.indices) return null
    val closer = when (deletedText.single()) {
        '(' -> ')'
        '[' -> ']'
        '{' -> '}'
        '"' -> '"'
        '\'' -> '\''
        '`' -> '`'
        else -> return null
    }
    if (previousText[deletionOffset] != deletedText.single()) return null
    if (currentText != previousText.removeRange(deletionOffset, deletionOffset + 1)) return null
    return deletionOffset.takeIf { currentText.getOrNull(it) == closer }
}
