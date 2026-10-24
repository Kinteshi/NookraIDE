package com.avfstudio.nativeide

import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.avfstudio.nativeide.workbench.KeyRouter
import androidx.compose.ui.platform.ComposeView
import android.widget.FrameLayout
import android.view.KeyEvent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.avfstudio.nativeide.workbench.NativeWorkbench
import com.avfstudio.nativeide.assets.AssetPackGrammarLoader
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.dsl.languages
import io.github.rosemoe.sora.langs.textmate.registry.provider.FileResolver
import io.github.rosemoe.sora.widget.CodeEditor
import org.eclipse.tm4e.core.registry.IThemeSource
import java.io.File
import java.io.FileNotFoundException
import kotlin.math.ceil

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val root = object : FrameLayout(this) {
            override fun dispatchKeyEventPreIme(event: KeyEvent): Boolean =
                KeyRouter.route(event) || super.dispatchKeyEventPreIme(event)
        }
        root.addView(ComposeView(this).apply { setContent { NativeWorkbench() } })
        setContentView(root)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = KeyRouter.route(event) || super.dispatchKeyEvent(event)
}

@Composable
private fun NativeEditorLab() {
    val firstPaintMs = remember { mutableStateOf<Long?>(null) }
    val keyToFrameMs = remember { mutableStateListOf<Long>() }
    val lineCount = remember { 10_000 }
    val sampleDocument = remember { buildSampleDocument(lineCount) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1E1E1E)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF252526))
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text("AVF Native IDE", color = Color(0xFFF3F3F3), fontSize = 18.sp)
            Spacer(Modifier.height(4.dp))
            Text("N0 editor engine evaluation", color = Color(0xFFB8B8B8), fontSize = 12.sp)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF2D2D2D))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("sample.py", color = Color(0xFFFFFFFF), fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            Text("Python · $lineCount lines", color = Color(0xFF9D9D9D), fontSize = 12.sp)
        }

        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            factory = { viewContext ->
                PythonTextMate.initialize(viewContext)
                val startedAt = SystemClock.elapsedRealtime()
                CodeEditor(viewContext).apply {
                    setTypefaceText(Typeface.MONOSPACE)
                    setTextSize(14f)
                    setLineNumberEnabled(true)
                    setWordwrap(false)
                    setTabWidth(4)
                    setDisableSoftKbdIfHardKbdAvailable(false)
                    setColorScheme(TextMateColorScheme.create(ThemeRegistry.getInstance()))
                    setEditorLanguage(TextMateLanguage.create("source.python", true))
                    setText(sampleDocument)
                    subscribeAlways(ContentChangeEvent::class.java) { event ->
                        if (event.action != ContentChangeEvent.ACTION_SET_NEW_TEXT) {
                            val changedAt = SystemClock.elapsedRealtimeNanos()
                            viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                                override fun onPreDraw(): Boolean {
                                    if (viewTreeObserver.isAlive) {
                                        viewTreeObserver.removeOnPreDrawListener(this)
                                    }
                                    if (keyToFrameMs.size == 100) keyToFrameMs.removeAt(0)
                                    keyToFrameMs.add(
                                        (SystemClock.elapsedRealtimeNanos() - changedAt) / 1_000_000,
                                    )
                                    return true
                                }
                            })
                        }
                    }
                    viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                        override fun onPreDraw(): Boolean {
                            if (viewTreeObserver.isAlive) {
                                viewTreeObserver.removeOnPreDrawListener(this)
                            }
                            firstPaintMs.value = SystemClock.elapsedRealtime() - startedAt
                            return true
                        }
                    })
                }
            },
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF252526))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                text = firstPaintMs.value?.let { "First editor frame: ${it} ms" }
                    ?: "Measuring first editor frame…",
                color = Color(0xFFD4D4D4),
                fontSize = 12.sp,
            )
            val inputLatencyP95 = keyToFrameMs.sorted()
                .getOrNull((ceil(keyToFrameMs.size * 0.95).toInt() - 1).coerceAtLeast(0))
            Text(
                text = inputLatencyP95?.let {
                    "Edit event to next editor frame p95: ${it} ms (${keyToFrameMs.size} samples)"
                } ?: "Type to measure edit-to-frame latency",
                color = Color(0xFFD4D4D4),
                fontSize = 12.sp,
            )
            Text(
                "TextMate Python grammar · guest LSP completion pending",
                color = Color(0xFF9D9D9D),
                fontSize = 11.sp,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF181818))
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceAround,
        ) {
            listOf("Files", "Editor", "Terminal", "More").forEach { destination ->
                Surface(
                    color = if (destination == "Editor") Color(0xFF37373D) else Color.Transparent,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        destination,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        color = if (destination == "Editor") Color.White else Color(0xFFB8B8B8),
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

internal fun buildSampleDocument(lines: Int): String = buildString(lines * 112) {
    repeat(lines) { index ->
        append("value_$index = normalize(\"sample-$index\", mode=\"native\")  # line $index")
        if (index < lines - 1) append('\n')
    }
}

internal const val LIGHT_SYNTAX_THEME = "avf-light.tmTheme.json"
internal const val DARK_SYNTAX_THEME = "avf-dark.tmTheme.json"

internal object PythonTextMate {
    @Volatile
    private var initialized = false

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return

        val appContext = context.applicationContext
        FileProviderRegistry.getInstance().addFileProvider(
            FileResolver { path ->
                try {
                    appContext.assets.open(path)
                } catch (_: FileNotFoundException) {
                    null
                }
            },
        )
        FileProviderRegistry.getInstance().addFileProvider(
            FileResolver { path ->
                val prefix = "asset-packs/"
                if (!path.startsWith(prefix)) return@FileResolver null
                val root = File(appContext.filesDir, "asset-packs").canonicalFile
                val file = File(root, path.removePrefix(prefix)).canonicalFile
                if (file.path.startsWith(root.path + File.separator) && file.isFile) file.inputStream() else null
            },
        )

        val definition = languages {
            language("python") {
                grammar = "textmate/python.tmLanguage.json"
                scopeName = "source.python"
            }
        }.build()
        GrammarRegistry.getInstance().loadGrammars(definition)
        AssetPackGrammarLoader.loadInstalled(appContext)

        // Light and dark syntax themes follow the Material theme; an imported VS Code theme wins.
        listOf(LIGHT_SYNTAX_THEME, DARK_SYNTAX_THEME).forEach { fileName ->
            ThemeRegistry.getInstance().loadTheme(
                IThemeSource.fromInputStream(appContext.assets.open("textmate/$fileName"), fileName, Charsets.UTF_8),
            )
        }
        initialized = true
    }
}
