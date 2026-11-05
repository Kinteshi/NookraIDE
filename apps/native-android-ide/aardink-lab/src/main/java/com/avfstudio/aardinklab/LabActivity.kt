package com.avfstudio.aardinklab

import android.content.res.AssetManager
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.aardarch.aardink.core.CommentSyntax
import com.aardarch.aardink.core.IncrementalTokenizer
import com.aardarch.aardink.core.Token
import com.aardarch.aardink.core.rememberCodeEditorState
import com.aardarch.aardink.languages.DeclarativeGrammar
import com.aardarch.aardink.languages.DeclarativeTokenizer
import com.aardarch.aardink.ui.CodeEditorLayout
import com.aardarch.aardink.ui.EditorOptions
import com.aardarch.aardink.ui.EditorThemes
import com.aardarch.aardink.ui.LocalEditorTheme
import kotlinx.coroutines.delay
import org.eclipse.tm4e.core.grammar.IGrammar
import org.eclipse.tm4e.core.registry.IGrammarSource
import org.eclipse.tm4e.core.registry.IThemeSource
import org.eclipse.tm4e.core.registry.Registry

class LabActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val start = SystemClock.uptimeMillis()
        val lines = intent.getIntExtra("lines", 10_000)
        val tabs = intent.getIntExtra("tabs", 1)
        val textMate = intent.getStringExtra("grammar") == "textmate"
        val setup = if (textMate) labTextMate(assets) else null
        val theme = if (setup != null) {
            val (colors, styles) = textMateTokenColors(setup.first.colorMap)
            EditorThemes.VsCodeDark.copy(tokenColors = colors, tokenFontStyles = styles)
        } else {
            EditorThemes.VsCodeDark
        }
        setContent {
            // As in the app: one live editor, made again when the active tab changes.
            val tab by tabCycle(tabs)
            key(tab) {
                val tokenizer = remember {
                    if (setup != null) {
                        LoggingTokenizer(TextMateTokenizer(setup.second, CommentSyntax(line = "#")))
                    } else {
                        val json = assets.open("python.grammar.json").reader().readText()
                        DeclarativeTokenizer(DeclarativeGrammar.parse(json))
                    }
                }
                val state = rememberCodeEditorState(initialText = sampleDocument(lines, tab), tokenizer = tokenizer)
                CompositionLocalProvider(LocalEditorTheme provides theme) {
                    CodeEditorLayout(
                        state = state,
                        options = EditorOptions(),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            window.decorView.post {
                android.util.Log.i("AardinkLab", "first-frame-ms=${SystemClock.uptimeMillis() - start}")
            }
        }
    }
}

/** Grammar and theme as the app has them, loaded with no sora registry. */
private fun labTextMate(assets: AssetManager): Pair<Registry, IGrammar> {
    val registry = Registry()
    registry.setTheme(IThemeSource.fromInputStream(assets.open("textmate/avf-dark.tmTheme.json"), "avf-dark.tmTheme.json", null))
    val grammar = registry.addGrammar(
        IGrammarSource.fromInputStream(assets.open("textmate/python.tmLanguage.json"), "python.tmLanguage.json", null),
    )
    return registry to grammar
}

/** Says how long a full pass takes, the number the spike is after. */
private class LoggingTokenizer(private val tokenizer: IncrementalTokenizer) : IncrementalTokenizer by tokenizer {
    override fun tokenizeFull(text: String): List<Token> {
        val start = SystemClock.uptimeMillis()
        return tokenizer.tokenizeFull(text).also {
            android.util.Log.i("AardinkLab", "tm-full-ms=${SystemClock.uptimeMillis() - start} tokens=${it.size} chars=${text.length}")
        }
    }
}

/** The active tab: 0, then each of [tabs] in turn, three times round, as someone going through their files. */
@Composable
internal fun tabCycle(tabs: Int): State<Int> = produceState(0, tabs) {
    if (tabs <= 1) return@produceState
    repeat(tabs * 3) {
        delay(1_500)
        value = (value + 1) % tabs
    }
    android.util.Log.i("AardinkLab", "tabs-done")
}

fun sampleDocument(lines: Int, tab: Int = 0): String = buildString(lines * 112) {
    repeat(lines) { i ->
        append("value_$i = normalize(\"sample-$tab-$i\", mode=\"native\")  # line $i")
        if (i < lines - 1) append('\n')
    }
}
