package com.avfstudio.nativeide.workbench

/** Files whose tags get linked editing: markup, and JSX in scripts. */
internal val LINKED_EDITING_LANGUAGES = setOf("html", "htm", "xhtml", "xml", "svg", "vue", "svelte", "astro", "jsx", "tsx", "xsl", "xaml", "plist")

private fun isTagNameChar(char: Char) = char.isLetterOrDigit() || char == '-' || char == '_' || char == ':' || char == '.'

/** The partner tag's name to rewrite, as offsets in the text after the edit. */
internal data class LinkedTagEdit(val start: Int, val end: Int, val name: String)

private data class Tag(val nameStart: Int, val nameEnd: Int, val name: String, val closing: Boolean, val selfClosing: Boolean)

/** Every tag in [text] in order, skipping comments; a `<` that doesn't start a name isn't one. */
private fun tags(text: String): List<Tag> {
    val found = mutableListOf<Tag>()
    var index = text.indexOf('<')
    while (index >= 0 && index < text.length - 1) {
        if (text.startsWith("<!--", index)) {
            val close = text.indexOf("-->", index + 4)
            if (close < 0) break
            index = text.indexOf('<', close + 3)
            continue
        }
        val closing = text[index + 1] == '/'
        val nameStart = index + if (closing) 2 else 1
        var nameEnd = nameStart
        while (nameEnd < text.length && isTagNameChar(text[nameEnd])) nameEnd++
        // An emptied name (`< class="a">`, `</>`) is still a tag, so retyping it keeps the pair linked.
        val named = nameEnd > nameStart && text[nameStart].isLetter()
        val emptied = nameEnd == nameStart && nameStart < text.length && (text[nameStart] == '>' || (!closing && text[nameStart] == ' '))
        if (named || emptied) {
            val gt = text.indexOf('>', nameEnd)
            val selfClosing = !closing && gt > 0 && text[gt - 1] == '/'
            found += Tag(nameStart, nameEnd, text.substring(nameStart, nameEnd), closing, selfClosing)
        }
        index = text.indexOf('<', index + 1)
    }
    return found
}

/** The tag that matches [tag] (its closing tag, or the opening one for a closing tag), counting nested tags of the same name. */
private fun partner(all: List<Tag>, tag: Tag): Tag? {
    val at = all.indexOf(tag)
    var depth = 0
    if (!tag.closing) {
        if (tag.selfClosing) return null
        for (other in all.subList(at + 1, all.size)) {
            if (other.name != tag.name || other.selfClosing) continue
            if (!other.closing) depth++ else if (depth == 0) return other else depth--
        }
    } else {
        for (other in all.subList(0, at).asReversed()) {
            if (other.name != tag.name || other.selfClosing) continue
            if (other.closing) depth++ else if (depth == 0) return other else depth--
        }
    }
    return null
}

/**
 * VS Code's linked editing for tags: an edit at [offset] in [before] that removed [removed]
 * characters and inserted [inserted] inside a tag's name changes its partner the same way.
 * Null when the edit isn't inside a tag name or the tag has no partner.
 */
internal fun linkedTagEdit(before: String, offset: Int, removed: Int, inserted: String): LinkedTagEdit? {
    if (inserted.any { !isTagNameChar(it) }) return null
    val editEnd = offset + removed
    if (offset < 1 || editEnd > before.length || before.substring(offset, editEnd).any { !isTagNameChar(it) }) return null
    var nameStart = offset
    while (nameStart > 0 && isTagNameChar(before[nameStart - 1])) nameStart--
    if (nameStart == 0 || (before[nameStart - 1] != '<' && !(before[nameStart - 1] == '/' && nameStart >= 2 && before[nameStart - 2] == '<'))) return null
    val all = tags(before)
    val tag = all.firstOrNull { it.nameStart == nameStart } ?: return null
    if (editEnd > tag.nameEnd) return null
    // Only a finished tag: one being typed (`<di`) has no `>` yet and no partner to follow.
    val gt = before.indexOf('>', tag.nameEnd)
    val lt = before.indexOf('<', tag.nameEnd)
    if (gt < 0 || (lt in 0 until gt)) return null
    val other = partner(all, tag) ?: return null
    val newName = tag.name.substring(0, offset - nameStart) + inserted + tag.name.substring(editEnd - nameStart)
    val shift = if (other.nameStart > offset) inserted.length - removed else 0
    return LinkedTagEdit(other.nameStart + shift, other.nameEnd + shift, newName)
}
