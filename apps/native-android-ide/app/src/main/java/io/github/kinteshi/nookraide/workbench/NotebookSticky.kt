package io.github.kinteshi.nookraide.workbench

/** A list item's index, top offset and height (px), from the notebook list's layout. */
internal data class StickyItem(val index: Int, val offset: Int, val size: Int)

/** The cell whose header sticks, and how far (px, zero or less) the cell's end pushes it up. */
internal data class StickyCell(val index: Int, val offset: Int)

/**
 * The cell to keep a sticky header for: the one across the viewport's top edge whose own top has
 * scrolled away, if it is tall enough for a header to help (three headers high). Items past
 * [cellCount] (the add-cell buttons) never stick.
 */
internal fun stickyCell(items: List<StickyItem>, viewportStart: Int, cellCount: Int, headerHeight: Int): StickyCell? {
    val top = items.firstOrNull { it.offset < viewportStart && it.offset + it.size > viewportStart } ?: return null
    if (top.index >= cellCount || top.size < headerHeight * 3) return null
    val visibleBottom = top.offset + top.size - viewportStart
    return StickyCell(top.index, minOf(0, visibleBottom - headerHeight))
}
