@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package io.github.kinteshi.nookraide.workbench

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.unit.TextUnit
import org.connectbot.terminal.SelectionController
import org.connectbot.terminal.TerminalEmulator

/** Scrolling the terminal's view by rows (termlib keeps its scroll controller internal). */
interface TerminalScroll {
    /** Rows scrolled up from the bottom. */
    val position: Int
    val maxScrollback: Int
    fun scrollBy(rows: Int)
    fun scrollToBottom()
}

/**
 * termlib's terminal with its scroll controller handed out ([TerminalScroll]); the public
 * `Terminal` takes the same arguments but keeps scrolling to itself.
 */
@Composable
fun ScrollableTerminal(
    terminalEmulator: TerminalEmulator,
    modifier: Modifier,
    initialFontSize: TextUnit,
    backgroundColor: Color,
    foregroundColor: Color,
    selectionBackgroundColor: Color,
    keyboardEnabled: Boolean,
    onHyperlinkClick: (String) -> Unit,
    onSelectionControllerAvailable: (SelectionController) -> Unit,
    onScrollAvailable: (TerminalScroll) -> Unit,
    onInterceptKey: (KeyEvent) -> Boolean,
) {
    org.connectbot.terminal.TerminalWithAccessibility(
        terminalEmulator = terminalEmulator,
        modifier = modifier,
        initialFontSize = initialFontSize,
        backgroundColor = backgroundColor,
        foregroundColor = foregroundColor,
        selectionBackgroundColor = selectionBackgroundColor,
        keyboardEnabled = keyboardEnabled,
        onHyperlinkClick = onHyperlinkClick,
        onSelectionControllerAvailable = onSelectionControllerAvailable,
        onScrollControllerAvailable = { controller ->
            onScrollAvailable(object : TerminalScroll {
                override val position: Int get() = controller.scrollbackPosition
                override val maxScrollback: Int get() = controller.maxScrollback
                override fun scrollBy(rows: Int) = controller.scrollBy(rows)
                override fun scrollToBottom() = controller.scrollToBottom()
            })
        },
        onInterceptKey = onInterceptKey,
    )
}
