package com.avfstudio.aardinklab

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.aardarch.aardink.core.rememberCodeEditorState
import com.aardarch.aardink.languages.DeclarativeGrammar
import com.aardarch.aardink.languages.DeclarativeTokenizer
import com.aardarch.aardink.ui.CodeEditorLayout
import com.aardarch.aardink.ui.EditorOptions
import com.aardarch.aardink.ui.EditorThemes
import com.aardarch.aardink.ui.LocalEditorTheme

class LabActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val start = SystemClock.uptimeMillis()
        setContent {
            val tokenizer = remember {
                val json = assets.open("python.grammar.json").reader().readText()
                DeclarativeTokenizer(DeclarativeGrammar.parse(json))
            }
            val state = rememberCodeEditorState(initialText = sampleDocument(10_000), tokenizer = tokenizer)
            CompositionLocalProvider(LocalEditorTheme provides EditorThemes.VsCodeDark) {
                CodeEditorLayout(
                    state = state,
                    options = EditorOptions(),
                    modifier = Modifier.fillMaxSize(),
                )
            }
            window.decorView.post {
                android.util.Log.i("AardinkLab", "first-frame-ms=${SystemClock.uptimeMillis() - start}")
            }
        }
    }
}

fun sampleDocument(lines: Int): String = buildString(lines * 112) {
    repeat(lines) { i ->
        append("value_$i = normalize(\"sample-$i\", mode=\"native\")  # line $i")
        if (i < lines - 1) append('\n')
    }
}
