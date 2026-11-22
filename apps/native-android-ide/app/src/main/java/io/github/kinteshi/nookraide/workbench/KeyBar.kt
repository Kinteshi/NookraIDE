package io.github.kinteshi.nookraide.workbench

import android.os.SystemClock
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A modifier key on the bar: off, on for the next key only, or locked on until tapped again. */
enum class Latch { Off, Once, Locked }

/**
 * The key bar above the on-screen keyboard (Termux's extra keys): Esc, Tab, arrows and the like
 * are sent as hardware keys would be, so shortcuts, the terminal and text fields all take them.
 * Ctrl, Alt and Shift stay pressed for the next key, from the bar or from the on-screen keyboard
 * (Ctrl then S saves, Ctrl then C interrupts in the terminal).
 */
object KeyBar {
    var ctrl by mutableStateOf(Latch.Off)
    var alt by mutableStateOf(Latch.Off)
    var shift by mutableStateOf(Latch.Off)

    /** Sends a key event through the activity, as a physical keyboard's would arrive. */
    @Volatile
    var inject: ((KeyEvent) -> Boolean)? = null

    /** Whether Ctrl or Alt waits for the next typed character (Shift alone leaves typing alone). */
    val holdsTyping: Boolean get() = ctrl != Latch.Off || alt != Latch.Off

    fun toggle(current: Latch): Latch = when (current) {
        Latch.Off -> Latch.Once
        Latch.Once -> Latch.Locked
        Latch.Locked -> Latch.Off
    }

    private fun meta(): Int =
        (if (ctrl != Latch.Off) KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON else 0) or
            (if (alt != Latch.Off) KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON else 0) or
            (if (shift != Latch.Off) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0)

    /** One-shot modifiers end after a key. */
    fun release() {
        if (ctrl == Latch.Once) ctrl = Latch.Off
        if (alt == Latch.Once) alt = Latch.Off
        if (shift == Latch.Once) shift = Latch.Off
    }

    /** Presses [keyCode] with the latched modifiers and [extraMeta]. */
    fun press(keyCode: Int, extraMeta: Int = 0) {
        val send = inject ?: return
        val meta = meta() or extraMeta
        val now = SystemClock.uptimeMillis()
        send(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_SOFT_KEYBOARD))
        send(KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, 0, meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_SOFT_KEYBOARD))
        release()
    }

    /**
     * A character typed on the on-screen keyboard while Ctrl or Alt is latched: sent as that key
     * combination instead (Ctrl+S, Ctrl+/). False when nothing is latched or the character has no key.
     */
    fun typed(text: CharSequence): Boolean {
        if (!holdsTyping || text.length != 1) return false
        val events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(charArrayOf(text[0])) ?: return false
        val down = events.firstOrNull { it.action == KeyEvent.ACTION_DOWN && !KeyEvent.isModifierKey(it.keyCode) } ?: return false
        press(down.keyCode, down.metaState and (KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON))
        return true
    }

    /**
     * Terminal input from the on-screen keyboard with Ctrl or Alt latched: Ctrl makes a control
     * character (Ctrl+C is 0x03), Alt puts Escape before it, as a terminal's Meta does.
     */
    fun terminalBytes(bytes: ByteArray): ByteArray {
        if (!holdsTyping || bytes.size != 1) return bytes
        val modified = modifiedTerminalByte(bytes[0].toInt() and 0xFF, ctrl != Latch.Off, alt != Latch.Off) ?: return bytes
        release()
        return modified
    }
}

/** [byte] with Ctrl (letters and `@[\]^_ ?` to control characters) and Alt (an Escape before it); null if Ctrl can't apply. */
internal fun modifiedTerminalByte(byte: Int, ctrl: Boolean, alt: Boolean): ByteArray? {
    val code = if (ctrl) {
        when (byte) {
            in 'a'.code..'z'.code -> byte - 'a'.code + 1
            in '@'.code..'_'.code -> byte - '@'.code
            ' '.code -> 0
            '?'.code -> 0x7F
            else -> return null
        }
    } else byte
    return if (alt) byteArrayOf(0x1B, code.toByte()) else byteArrayOf(code.toByte())
}

/** The one character [new] inserted at [old]'s caret, if that is the whole change (typing, not a paste or an edit elsewhere). */
internal fun typedCharacter(old: TextFieldValue, new: TextFieldValue): Char? {
    if (!old.selection.collapsed || new.text.length != old.text.length + 1) return null
    val at = old.selection.start
    if (new.selection != TextRange(at + 1)) return null
    if (new.text.substring(0, at) != old.text.substring(0, at) || new.text.substring(at + 1) != old.text.substring(at)) return null
    return new.text[at]
}

/**
 * The code editor's IME connection: a character typed with Ctrl or Alt latched becomes that
 * shortcut. Keyboards that compose words resend the whole word with each letter ("matha" after
 * "math"), so the letter is what the word gained.
 */
internal class KeyBarInputConnection(
    target: InputConnection,
    /** The editor's text between two offsets, for a word the keyboard marks as composing. */
    private val textBetween: (Int, Int) -> CharSequence?,
) : InputConnectionWrapper(target, true) {
    private var composing: CharSequence = ""

    override fun setComposingRegion(start: Int, end: Int): Boolean {
        // Tapping into a word makes some keyboards (Samsung's) compose it again from here.
        composing = textBetween(minOf(start, end), maxOf(start, end)) ?: ""
        return super.setComposingRegion(start, end)
    }

    override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
        val added = addedCharacter(composing, text)
        if (KeyBar.holdsTyping && added != null && KeyBar.typed(added.toString())) {
            // The word as it was before the shortcut's letter.
            val word = composing
            composing = ""
            return if (word.isEmpty()) true else super.commitText(word, newCursorPosition)
        }
        composing = ""
        return super.commitText(text, newCursorPosition)
    }

    override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
        val added = addedCharacter(composing, text)
        if (KeyBar.holdsTyping && added != null && KeyBar.typed(added.toString())) {
            // Ending the composition makes the keyboard start a new word instead of resending this one.
            composing = ""
            return super.finishComposingText()
        }
        composing = text
        return super.setComposingText(text, newCursorPosition)
    }

    override fun finishComposingText(): Boolean {
        composing = ""
        return super.finishComposingText()
    }
}

/** The one character [now] adds at the end of [before] (a letter typed into a composed word, or a lone character). */
internal fun addedCharacter(before: CharSequence, now: CharSequence): Char? =
    if (now.length == before.length + 1 && now.startsWith(before)) now.last() else null

private data class BarKey(val label: String, val keyCode: Int, val repeats: Boolean = false, val icon: ImageVector? = null)

private val barKeys = listOf(
    BarKey("Esc", KeyEvent.KEYCODE_ESCAPE),
    BarKey("Tab", KeyEvent.KEYCODE_TAB),
)

private val navigationKeys = listOf(
    BarKey("Left", KeyEvent.KEYCODE_DPAD_LEFT, repeats = true, icon = Icons.AutoMirrored.Outlined.KeyboardArrowLeft),
    BarKey("Up", KeyEvent.KEYCODE_DPAD_UP, repeats = true, icon = Icons.Outlined.KeyboardArrowUp),
    BarKey("Down", KeyEvent.KEYCODE_DPAD_DOWN, repeats = true, icon = Icons.Outlined.KeyboardArrowDown),
    BarKey("Right", KeyEvent.KEYCODE_DPAD_RIGHT, repeats = true, icon = Icons.AutoMirrored.Outlined.KeyboardArrowRight),
    BarKey("Home", KeyEvent.KEYCODE_MOVE_HOME),
    BarKey("End", KeyEvent.KEYCODE_MOVE_END),
    BarKey("PgUp", KeyEvent.KEYCODE_PAGE_UP, repeats = true),
    BarKey("PgDn", KeyEvent.KEYCODE_PAGE_DOWN, repeats = true),
)

/** The bar itself: one row that scrolls sideways on a narrow screen. */
@Composable
fun KeyBarRow(modifier: Modifier = Modifier, onLatch: () -> Unit = {}) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .background(colors.surfaceContainerHigh)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        barKeys.forEach { BarKeyButton(it) }
        LatchButton("Ctrl", KeyBar.ctrl) { KeyBar.ctrl = KeyBar.toggle(KeyBar.ctrl).also { onLatch() } }
        LatchButton("Alt", KeyBar.alt) { KeyBar.alt = KeyBar.toggle(KeyBar.alt).also { onLatch() } }
        LatchButton("Shift", KeyBar.shift) { KeyBar.shift = KeyBar.toggle(KeyBar.shift).also { onLatch() } }
        navigationKeys.forEach { BarKeyButton(it) }
    }
}

@Composable
private fun BarKeyButton(key: BarKey) {
    val scope = rememberCoroutineScope()
    BarKeyFace(key.label, Latch.Off, icon = key.icon, onAccessibilityActivate = { KeyBar.press(key.keyCode) }, modifier = Modifier.pointerInput(key) {
        detectTapGestures(
            onPress = {
                // Arrows and pages repeat while held, as a physical key does.
                var repeated = false
                val repeat = if (key.repeats) scope.launch {
                    delay(400)
                    repeated = true
                    while (true) {
                        KeyBar.press(key.keyCode)
                        delay(50)
                    }
                } else null
                val released = tryAwaitRelease()
                repeat?.cancel()
                if (released && !repeated) KeyBar.press(key.keyCode)
            },
        )
    })
}

@Composable
private fun LatchButton(label: String, latch: Latch, onTap: () -> Unit) {
    BarKeyFace(label, latch, onAccessibilityActivate = onTap, modifier = Modifier.pointerInput(label) { detectTapGestures(onTap = { onTap() }) })
}

@Composable
private fun BarKeyFace(
    label: String,
    latch: Latch,
    icon: ImageVector? = null,
    onAccessibilityActivate: (() -> Unit)? = null,
    modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val background = when (latch) {
        Latch.Off -> colors.surfaceContainerHighest
        Latch.Once -> colors.secondaryContainer
        Latch.Locked -> colors.primary
    }
    val content = when (latch) {
        Latch.Off -> colors.onSurface
        Latch.Once -> colors.onSecondaryContainer
        Latch.Locked -> colors.onPrimary
    }
    val (spoken, latchState) = keyBarKeyDescription(label, latch)
    Box(
        modifier
            .height(36.dp)
            .widthIn(min = 44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .then(if (latch == Latch.Once) Modifier.border(1.dp, colors.primary, RoundedCornerShape(8.dp)) else Modifier)
            .noKeyFocus()
            .semantics(mergeDescendants = true) {
                contentDescription = spoken
                if (latchState != null) stateDescription = latchState
                role = Role.Button
                if (onAccessibilityActivate != null) {
                    onClick { onAccessibilityActivate(); true }
                }
            }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = content) else Text(label, style = MaterialTheme.typography.labelLarge, color = content)
    }
}
