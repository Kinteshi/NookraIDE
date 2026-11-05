package com.avfstudio.aardinklab

import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.dsl.languages
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import io.github.rosemoe.sora.widget.CodeEditor
import org.eclipse.tm4e.core.registry.IThemeSource

/** The same 10,000-line document in sora-editor with the app's TextMate grammar and theme, for comparison. */
class SoraLabActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val start = SystemClock.uptimeMillis()
        FileProviderRegistry.getInstance().addFileProvider(AssetsFileResolver(assets))
        GrammarRegistry.getInstance().loadGrammars(
            languages {
                language("python") {
                    grammar = "textmate/python.tmLanguage.json"
                    scopeName = "source.python"
                }
            }.build(),
        )
        ThemeRegistry.getInstance().loadTheme(
            IThemeSource.fromInputStream(assets.open("textmate/avf-dark.tmTheme.json"), "avf-dark.tmTheme.json", Charsets.UTF_8),
        )
        ThemeRegistry.getInstance().findThemeByFileName("avf-dark.tmTheme.json")?.let(ThemeRegistry.getInstance()::setTheme)
        val lines = intent.getIntExtra("lines", 10_000)
        val tabs = intent.getIntExtra("tabs", 1)
        setContent {
            // As in the app: one live editor, made again when the active tab changes.
            val tab by tabCycle(tabs)
            key(tab) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        CodeEditor(context).apply {
                            setTypefaceText(Typeface.MONOSPACE)
                            colorScheme = TextMateColorScheme.create(ThemeRegistry.getInstance())
                            setEditorLanguage(TextMateLanguage.create("source.python", true))
                            setText(sampleDocument(lines, tab))
                        }
                    },
                    onRelease = { it.release() },
                )
            }
            window.decorView.post {
                android.util.Log.i("AardinkLab", "first-frame-ms=${SystemClock.uptimeMillis() - start}")
            }
        }
    }
}
