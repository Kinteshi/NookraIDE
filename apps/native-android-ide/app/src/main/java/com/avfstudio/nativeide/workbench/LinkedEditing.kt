package com.avfstudio.nativeide.workbench

/** Files whose tags get linked editing: markup, and JSX in scripts. */
internal val LINKED_EDITING_LANGUAGES = setOf("html", "htm", "xhtml", "xml", "svg", "vue", "svelte", "astro", "jsx", "tsx", "xsl", "xaml", "plist")

private fun isTagNameChar(char: Char) = char.isLetterOrDigit() || char == '-' || char == '_' || char == ':' || char == '.'

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
 * The name of the tag at [offset] in [text] and its partner's, as ranges (`start until end`), for an editor that
 * edits linked ranges itself (Aardink's `linkedEditingRanges`); null off a tag's name or for a tag with no partner.
 */
internal fun linkedTagRanges(text: String, offset: Int): List<IntRange>? {
    val all = tags(text)
    val tag = all.firstOrNull { offset in it.nameStart..it.nameEnd } ?: return null
    val other = partner(all, tag) ?: return null
    return listOf(tag.nameStart until tag.nameEnd, other.nameStart until other.nameEnd).sortedBy { it.first }
}
