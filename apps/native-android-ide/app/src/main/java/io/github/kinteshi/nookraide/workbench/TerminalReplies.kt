package io.github.kinteshi.nookraide.workbench

/**
 * What a terminal emulator sends by itself in answer to a program's query: cursor position
 * (`ESC [ row ; col R`), status (`ESC [ 0 n`), device attributes (`ESC [ ? … c`, `ESC [ > … c`)
 * and OSC colour answers (`ESC ] 11 ; rgb:… BEL`). Keys the user types never look like this.
 */
private val TERMINAL_REPLY = Regex(
    "(?:\\u001b\\[\\d+;\\d+R|\\u001b\\[\\d*n|\\u001b\\[[?>]?[\\d;]*c|\\u001b\\]\\d+;[^\\u0007\\u001b]*(?:\\u0007|\\u001b\\\\))+",
)

internal fun isTerminalReply(bytes: ByteArray): Boolean = TERMINAL_REPLY.matches(String(bytes, Charsets.ISO_8859_1))
