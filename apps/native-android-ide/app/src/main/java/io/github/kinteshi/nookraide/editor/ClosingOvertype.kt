package io.github.kinteshi.nookraide.editor

/**
 * VS Code's "type over" for closers it put in itself: typing `)` in front of a `)` that auto-closing
 * inserted steps over it rather than doubling it. Aardink 0.9 auto-closes but doesn't type over, so the
 * Aardink editor keeps the inserted closers here, moved along with each edit, and asks before a key is
 * typed whether it steps over one.
 *
 * Aardink tells its listeners about a change after making it, so a closer is announced before the change
 * that inserts it ([expect]) and placed when that change is reported, not moved by it.
 */
class ClosingOvertype {
    private class Expected(val offset: Int, val text: String, val closerAt: Int, val closer: String)

    private val closers = ArrayList<Pair<Int, Char>>()
    private val expected = ArrayList<Expected>()

    /** An edit inserting [text] at [offset] is coming, and puts [closer] at [closerAt]. */
    fun expect(offset: Int, text: String, closerAt: Int, closer: String) {
        expected += Expected(offset, text, closerAt, closer)
    }

    /** An edit replaced [deleted] characters at [offset] with [inserted]: the closers after it move, those in it go. */
    fun edited(offset: Int, deleted: Int, inserted: String) {
        val delta = inserted.length - deleted
        val moved = closers.mapNotNull { (at, c) ->
            when {
                at < offset -> at to c
                at < offset + deleted -> null
                else -> (at + delta) to c
            }
        }
        closers.clear()
        closers += moved
        val announced = expected.firstOrNull { deleted == 0 && it.offset == offset && it.text == inserted }
        if (announced != null) {
            expected.remove(announced)
            announced.closer.forEachIndexed { i, c -> closers += (announced.closerAt + i) to c }
        } else {
            expected.clear()
        }
    }

    /** Whether typing [typed] with the caret at [caret] steps over a closer auto-closing inserted there. */
    fun stepsOver(caret: Int, typed: Char, text: CharSequence): Boolean {
        val found = closers.any { (at, c) -> at == caret && c == typed && text.getOrNull(caret) == typed }
        if (found) closers.removeAll { it.first == caret }
        return found
    }

    /** Whether the closer at [caret] in [text] is one auto-closing inserted (so Backspace before it takes the pair). */
    fun autoClosedAt(caret: Int, text: CharSequence): Boolean =
        closers.any { (at, c) -> at == caret && text.getOrNull(caret) == c }

    fun clear() {
        closers.clear()
        expected.clear()
    }
}
