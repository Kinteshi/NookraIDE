package io.github.kinteshi.quirecode.workbench

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.FindReplace
import androidx.compose.material.icons.outlined.HighlightAlt
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * VS Code's find widget at the top right of the editor: match case, whole word and regex beside
 * the query, the match count, previous and next, Find in Selection, and a replace row (with
 * Preserve Case) that the chevron opens. Its keys are VS Code's: Enter and Shift+Enter, Alt+C,
 * Alt+W, Alt+R, Alt+P, Alt+L, Ctrl+Shift+1 and Ctrl+Alt+Enter, Escape to close.
 */
@Composable
fun FindWidget(controller: WorkbenchController, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val findFocus = remember { FocusRequester() }
    val replaceFocus = remember { FocusRequester() }
    LaunchedEffect(controller.findFocus) {
        runCatching { if (controller.findFocusReplace) replaceFocus.requestFocus() else findFocus.requestFocus() }
    }
    fun keys(event: KeyEvent, replaceField: Boolean): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val alt = event.isAltPressed
        val ctrl = event.isCtrlPressed
        when {
            event.key == Key.Escape -> controller.closeFind()
            ctrl && alt && event.key == Key.Enter -> controller.replaceAll()
            ctrl && event.isShiftPressed && event.key == Key.One -> controller.replaceOne()
            event.key == Key.Enter && replaceField && !ctrl -> controller.replaceOne()
            event.key == Key.Enter && !ctrl -> controller.findNext(forward = !event.isShiftPressed)
            event.key == Key.F3 -> controller.findNext(forward = !event.isShiftPressed)
            ctrl && event.key == Key.H -> controller.findReplaceVisible = !controller.findReplaceVisible
            alt && event.key == Key.C -> toggle(controller) { findCaseSensitive = !findCaseSensitive }
            alt && event.key == Key.W -> toggle(controller) { findWholeWord = !findWholeWord }
            alt && event.key == Key.R -> toggle(controller) { findRegex = !findRegex }
            alt && event.key == Key.P -> controller.findPreserveCase = !controller.findPreserveCase
            alt && event.key == Key.L -> controller.toggleFindInSelection()
            else -> return false
        }
        return true
    }
    Surface(
        color = colors.surfaceContainerHigh,
        shape = RoundedCornerShape(bottomStart = 10.dp, bottomEnd = 10.dp),
        tonalElevation = 3.dp,
        shadowElevation = 4.dp,
        modifier = modifier.padding(end = 18.dp).widthIn(max = 520.dp),
    ) {
        Row(Modifier.padding(4.dp), verticalAlignment = Alignment.Top) {
            Tooltip("Toggle Replace") {
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .noKeyFocus()
                        .clickable(onClickLabel = "Toggle Replace") { controller.findReplaceVisible = !controller.findReplaceVisible },
                    contentAlignment = Alignment.Center,
                ) {
                    TreeChevron(
                        expanded = controller.findReplaceVisible,
                        animate = controller.settings.animations,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FindField(
                        value = controller.findQuery,
                        placeholder = "Find",
                        selectAll = controller.findFocus,
                        onAction = { controller.findNext(forward = true) },
                        error = controller.findResult.error != null || (controller.findQuery.isNotEmpty() && controller.findResult.matches.isEmpty()),
                        focus = findFocus,
                        controller = controller,
                        onValueChange = { controller.onFindQueryChanged(it) },
                        onKey = { keys(it, replaceField = false) },
                    ) {
                        OptionToggle("Aa", "Match Case (Alt+C)", controller.findCaseSensitive) { toggle(controller) { findCaseSensitive = !findCaseSensitive } }
                        OptionToggle("ab", "Match Whole Word (Alt+W)", controller.findWholeWord, underline = true) { toggle(controller) { findWholeWord = !findWholeWord } }
                        OptionToggle(".*", "Use Regular Expression (Alt+R)", controller.findRegex) { toggle(controller) { findRegex = !findRegex } }
                    }
                    Text(
                        findCountLabel(controller),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (controller.findResult.error != null) colors.error else colors.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 6.dp).widthIn(min = 64.dp),
                    )
                    WidgetButton(Icons.Outlined.KeyboardArrowUp, "Previous Match (Shift+Enter)") { controller.findNext(forward = false) }
                    WidgetButton(Icons.Outlined.KeyboardArrowDown, "Next Match (Enter)") { controller.findNext(forward = true) }
                    WidgetButton(Icons.Outlined.HighlightAlt, "Find in Selection (Alt+L)", on = controller.findScope != null) { controller.toggleFindInSelection() }
                    WidgetButton(Icons.Outlined.Close, "Close (Escape)") { controller.closeFind() }
                }
                VerticalReveal(visible = controller.findReplaceVisible, animate = controller.settings.animations) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FindField(
                            value = controller.replaceQuery,
                            placeholder = "Replace",
                            selectAll = null,
                            onAction = { controller.replaceOne() },
                            error = false,
                            focus = replaceFocus,
                            controller = controller,
                            onValueChange = { controller.replaceQuery = it },
                            onKey = { keys(it, replaceField = true) },
                        ) {
                            OptionToggle("AB", "Preserve Case (Alt+P)", controller.findPreserveCase) { controller.findPreserveCase = !controller.findPreserveCase }
                        }
                        WidgetButton(Icons.Outlined.FindReplace, "Replace (Enter)", enabled = controller.findResult.matches.isNotEmpty()) { controller.replaceOne() }
                        WidgetButton(Icons.Outlined.DoneAll, "Replace All (Ctrl+Alt+Enter)", enabled = controller.findResult.matches.isNotEmpty()) { controller.replaceAll() }
                    }
                }
            }
        }
    }
}

private inline fun toggle(controller: WorkbenchController, change: WorkbenchController.() -> Unit) {
    controller.change()
    controller.onFindQueryChanged()
}

/** "3 of 12", "?" of a count past the limit, or "No results", as VS Code words it. */
internal fun findCountLabel(controller: WorkbenchController): String {
    val result = controller.findResult
    return when {
        controller.findQuery.isEmpty() -> "No results"
        result.error != null -> result.error
        result.matches.isEmpty() -> "No results"
        else -> {
            val total = if (result.limited) "${result.matches.size}+" else result.matches.size.toString()
            val current = if (controller.findCurrent >= 0) (controller.findCurrent + 1).toString() else "?"
            "$current of $total"
        }
    }
}

@Composable
private fun FindField(
    value: String,
    placeholder: String,
    /** Each new value selects the whole text (the widget opened with a seed), as VS Code does. */
    selectAll: Int?,
    /** Enter: the on-screen keyboard (and a hardware one through it) sends it as the field's action. */
    onAction: () -> Unit,
    error: Boolean,
    focus: FocusRequester,
    controller: WorkbenchController,
    onValueChange: (String) -> Unit,
    onKey: (KeyEvent) -> Boolean,
    toggles: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    if (field.text != value) field = TextFieldValue(value, TextRange(value.length))
    LaunchedEffect(selectAll) {
        if (selectAll != null) field = field.copy(selection = TextRange(0, field.text.length))
    }
    Row(
        Modifier
            .widthIn(min = 160.dp, max = 280.dp)
            .background(colors.surfaceContainerHighest, RoundedCornerShape(6.dp))
            .border(1.dp, if (error) colors.error else colors.outlineVariant, RoundedCornerShape(6.dp))
            .padding(start = 8.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = field,
            onValueChange = {
                field = it
                if (it.text != value) onValueChange(it.text)
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onAction() }),
            textStyle = TextStyle(color = colors.onSurface, fontSize = 14.sp, fontFamily = FontFamily.Monospace),
            cursorBrush = SolidColor(colors.primary),
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 7.dp)
                .focusRequester(focus)
                // Keys go to this box, not to the editor's bindings.
                .onFocusChanged { state ->
                    if (state.isFocused) controller.focusArea = FocusArea.Chat
                    else if (controller.focusArea == FocusArea.Chat) controller.focusArea = FocusArea.Other
                }
                .onPreviewKeyEvent(onKey),
            decorationBox = { field ->
                Box {
                    if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    field()
                }
            },
        )
        toggles()
    }
}

@Composable
private fun OptionToggle(label: String, description: String, on: Boolean, underline: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Tooltip(description) { Box(
        Modifier
            .padding(1.dp)
            .size(width = 30.dp, height = 26.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(if (on) colors.primaryContainer else Color.Transparent)
            .border(1.dp, if (on) colors.primary else Color.Transparent, RoundedCornerShape(5.dp))
            .noKeyFocus()
            .clickable(onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            textDecoration = if (underline) TextDecoration.Underline else null,
            color = if (on) colors.onPrimaryContainer else colors.onSurfaceVariant,
        )
    } }
}

@Composable
private fun WidgetButton(icon: ImageVector, description: String, on: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Tooltip(description) { Box(
        Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (on) colors.primaryContainer else Color.Transparent)
            .noKeyFocus()
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            modifier = Modifier.size(20.dp),
            tint = when {
                !enabled -> colors.onSurfaceVariant.copy(alpha = 0.38f)
                on -> colors.onPrimaryContainer
                else -> colors.onSurfaceVariant
            },
        )
    } }
}
