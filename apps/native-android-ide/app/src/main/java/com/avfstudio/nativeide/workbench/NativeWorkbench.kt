package com.avfstudio.nativeide.workbench

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.avfstudio.nativeide.PythonTextMate
import com.avfstudio.nativeide.GuestConnectionService
import com.avfstudio.nativeide.document.DocumentRepository
import com.avfstudio.nativeide.document.DocumentSession
import com.avfstudio.nativeide.document.EncryptedDocumentCache
import com.avfstudio.nativeide.document.OpenDocument
import com.avfstudio.nativeide.document.PagedDocument
import com.avfstudio.nativeide.document.SaveResult
import com.avfstudio.nativeide.editor.SoraEditorSurface
import com.avfstudio.nativeide.editor.AutoPairReplacement
import com.avfstudio.nativeide.editor.SoraBracketColorizer
import com.avfstudio.nativeide.editor.autoPairAfterInsertion
import com.avfstudio.nativeide.editor.pairedCloserOffsetAfterDeletion
import com.avfstudio.nativeide.editor.skipExistingCloserAfterInsertion
import com.avfstudio.nativeide.editor.SoraTextEditorHost
import com.avfstudio.nativeide.editor.LspPosition
import com.avfstudio.nativeide.editor.LspRange
import com.avfstudio.nativeide.editor.LspTextEdit
import com.avfstudio.nativeide.editor.TextEditorHost
import com.avfstudio.nativeide.editor.applyLspTextEdits
import com.avfstudio.nativeide.language.LanguageClient
import com.avfstudio.nativeide.language.LanguageDiagnostic
import com.avfstudio.nativeide.language.SettingsResolver
import com.avfstudio.nativeide.assets.AssetPackInstaller
import com.avfstudio.nativeide.assets.AssetPackGrammarLoader
import com.avfstudio.nativeide.assets.ImportedTheme
import com.avfstudio.nativeide.assets.InstalledAssetPack
import com.avfstudio.nativeide.assets.VsCodeThemeImporter
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.avfstudio.nativeide.transport.ConnectionProfile
import com.avfstudio.nativeide.transport.ConnectionProfileStore
import com.avfstudio.nativeide.transport.KeystoreSecretCipher
import com.avfstudio.nativeide.transport.SharedPreferencesKeyValueStore
import com.avfstudio.nativeide.transport.FileEntry
import com.avfstudio.nativeide.transport.GuestVfsException
import com.avfstudio.nativeide.transport.GuestVfsClient
import com.avfstudio.nativeide.workbench.Theme.background
import com.avfstudio.nativeide.workbench.Theme.editor
import com.avfstudio.nativeide.workbench.Theme.foreground
import com.avfstudio.nativeide.workbench.Theme.muted
import com.avfstudio.nativeide.workbench.Theme.panel
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import org.eclipse.tm4e.core.registry.IThemeSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

private const val SCRATCH_WELCOME_PATH = "scratch:/welcome.py"
private const val MAX_IMPORTED_THEME_BYTES = 8 * 1024 * 1024

private fun InputStream.readUtf8Limited(maxBytes: Int): String {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        total += count
        if (total > maxBytes) throw IOException("Theme JSON exceeds the 8 MiB limit")
        output.write(buffer, 0, count)
    }
    return String(output.toByteArray(), Charsets.UTF_8)
}

private data class LanguageLocation(val uri: String, val range: JsonObject)
private data class EditorNavigationTarget(val path: String, val line: Int, val character: Int)
private data class CompletionChoice(val label: String, val detail: String?, val edit: LspTextEdit?)
private data class CodeActionChoice(val title: String, val action: JsonObject)
private data class LanguageServerIssue(val server: String, val message: String)
private data class OutlineSymbol(
    val name: String,
    val kind: Int,
    val range: JsonObject,
    val children: List<OutlineSymbol>,
)

@Composable
fun NativeWorkbench() {
    val context = LocalContext.current.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    val vfs = remember { GuestVfsClient() }
    val languageClients = remember {
        mapOf(
            "python" to LanguageClient(vfs),
            "python-format" to LanguageClient(vfs),
            "typescript" to LanguageClient(vfs),
        )
    }
    val defaultSettings = remember {
        JsonParser.parseString("""{"editor":{"fontSize":14,"tabSize":4,"insertSpaces":true}}""").asJsonObject
    }
    val cache = remember { EncryptedDocumentCache(context) }
    val repository = remember { DocumentRepository(vfs, cache) }
    val editorHost = remember { SoraTextEditorHost() }
    val io = remember { Executors.newSingleThreadExecutor() }
    val draftTimer = remember { Executors.newSingleThreadScheduledExecutor() }
    val scheduledDraftWrites = remember { mutableMapOf<String, ScheduledFuture<*>>() }
    val scheduledOutlineRefreshes = remember { mutableMapOf<String, ScheduledFuture<*>>() }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val initialDocument = remember {
        cache.load(SCRATCH_WELCOME_PATH)?.let {
            DocumentSession(it.path, it.text, it.modifiedTs, it.dirty, encoding = it.encoding, eol = it.eol)
        } ?: DocumentSession(
            SCRATCH_WELCOME_PATH,
            "# Welcome to AVF Native IDE\n# Edit this scratch file offline, or connect to a guest workspace.\n\ndef greet(name: str) -> str:\n    return f\"Hello, {name}!\"\n",
            baseModifiedTs = null,
            dirty = false,
        )
    }

    val documents = remember { mutableStateListOf(initialDocument) }
    val closedDocuments = remember { mutableStateListOf<DocumentSession>() }
    val diagnostics = remember { mutableStateListOf<LanguageDiagnostic>() }
    var languageServerIssues by remember { mutableStateOf(emptyList<LanguageServerIssue>()) }
    val diagnosticsByClient = remember {
        mutableMapOf<LanguageClient, MutableMap<String, List<LanguageDiagnostic>>>()
    }
    var outlineSymbols by remember { mutableStateOf(emptyList<OutlineSymbol>()) }
    var userSettings by remember {
        val saved = context.getSharedPreferences("native-ide-settings", Context.MODE_PRIVATE)
            .getString("user-settings", "{}") ?: "{}"
        mutableStateOf(JsonParser.parseString(saved).asJsonObject)
    }
    var workspaceSettings by remember { mutableStateOf(JsonObject()) }
    var importedWorkbenchColors by remember {
        val savedColors = context.getSharedPreferences("native-ide-assets", Context.MODE_PRIVATE)
            .getString("workbench-colors", "{}") ?: "{}"
        mutableStateOf(JsonParser.parseString(savedColors).asJsonObject.entrySet().mapNotNull { (name, value) ->
            if (value.isJsonPrimitive && value.asJsonPrimitive.isString) name to value.asString else null
        }.toMap())
    }
    var settingsDraft by remember { mutableStateOf(userSettings.toString()) }
    var settingsDialog by remember { mutableStateOf(false) }
    var assetImportMode by remember { mutableStateOf("vsix") }
    fun languageClientFor(path: String): LanguageClient? =
        when (path.substringAfterLast('.', "").lowercase()) {
            "py", "pyi" -> languageClients["python"]
            "ts", "tsx", "js", "jsx", "mts", "cts", "mjs", "cjs" -> languageClients["typescript"]
            else -> null
        }

    fun languageClientsFor(path: String): List<LanguageClient> =
        listOfNotNull(
            languageClientFor(path),
            if (path.substringAfterLast('.', "").lowercase() in setOf("py", "pyi")) {
                languageClients["python-format"]
            } else {
                null
            },
        ).distinct()

    fun formattingClientFor(path: String): LanguageClient? =
        if (path.substringAfterLast('.', "").lowercase() in setOf("py", "pyi")) {
            languageClients["python-format"]
        } else {
            languageClientFor(path)
        }

    fun lspLanguageId(path: String): String? =
        when (path.substringAfterLast('.', "").lowercase()) {
            "py", "pyi" -> "python"
            "ts", "mts", "cts" -> "typescript"
            "tsx" -> "typescriptreact"
            "js", "mjs", "cjs" -> "javascript"
            "jsx" -> "javascriptreact"
            else -> null
        }

    fun settingsLanguageId(path: String): String? =
        when (languageClientFor(path)) {
            languageClients["python"] -> "python"
            languageClients["typescript"] -> "typescript"
            else -> null
        }

    val latestEdit by rememberUpdatedState<(DocumentSession) -> Unit> { document ->
        synchronized(scheduledDraftWrites) {
            scheduledDraftWrites.remove(document.path)?.cancel(false)
            scheduledDraftWrites[document.path] = draftTimer.schedule(
                {
                    io.execute {
                        repository.persistDraft(document)
                        synchronized(scheduledDraftWrites) { scheduledDraftWrites.remove(document.path) }
                    }
                },
                350,
                TimeUnit.MILLISECONDS,
            )
        }
    }
    var connectDialog by remember { mutableStateOf(false) }
    var commandDialog by remember { mutableStateOf(false) }
    var quickOpenDialog by remember { mutableStateOf(false) }
    var findDialog by remember { mutableStateOf(false) }
    var gotoLineDialog by remember { mutableStateOf(false) }
    var encodingDialog by remember { mutableStateOf(false) }
    var eolDialog by remember { mutableStateOf(false) }
    var completionDialog by remember { mutableStateOf(false) }
    var completionChoices by remember { mutableStateOf(emptyList<CompletionChoice>()) }
    var codeActionDialog by remember { mutableStateOf(false) }
    var codeActionChoices by remember { mutableStateOf(emptyList<CodeActionChoice>()) }
    var hoverText by remember { mutableStateOf<String?>(null) }
    var renameDialog by remember { mutableStateOf(false) }
    var renameValue by remember { mutableStateOf("") }
    var pendingNavigation by remember { mutableStateOf<EditorNavigationTarget?>(null) }
    var referenceLocations by remember { mutableStateOf(emptyList<LanguageLocation>()) }
    var connected by remember { mutableStateOf(false) }
    var keepAliveError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Guest disconnected") }
    var endpoint by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    val profileStore = remember {
        ConnectionProfileStore(SharedPreferencesKeyValueStore(context), KeystoreSecretCipher())
    }
    var profiles by remember { mutableStateOf(profileStore.profiles()) }
    var profileId by remember { mutableStateOf<String?>(null) }
    var profileName by remember { mutableStateOf("") }
    fun selectProfile(profile: ConnectionProfile) {
        profileId = profile.id
        profileName = profile.name
        endpoint = profile.endpoint
        token = profileStore.token(profile.id).orEmpty()
    }
    LaunchedEffect(Unit) {
        profiles.firstOrNull { it.id == profileStore.lastUsedId() }?.let(::selectProfile)
    }
    var roots by remember { mutableStateOf(emptyList<String>()) }
    var currentDirectory by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf(emptyList<FileEntry>()) }
    var activePath by remember { mutableStateOf<String?>(initialDocument.path) }
    var pagedDocument by remember { mutableStateOf<PagedDocument?>(null) }
    var currentDestination by remember { mutableStateOf("Editor") }
    var commandFilter by remember { mutableStateOf("") }
    var quickOpenFilter by remember { mutableStateOf("") }
    var findQuery by remember { mutableStateOf("") }
    var replaceQuery by remember { mutableStateOf("") }
    var gotoLine by remember { mutableStateOf("") }
    var conflictDocument by remember { mutableStateOf<DocumentSession?>(null) }
    var editorView by remember { mutableStateOf<CodeEditor?>(null) }
    val activeDocument = documents.firstOrNull { it.path == activePath }
    lateinit var toggleLineCommentAction: () -> Unit
    lateinit var findInEditorAction: (Boolean) -> Unit
    lateinit var copySelectionAction: (Boolean) -> Unit
    lateinit var pasteClipboardAction: () -> Unit

    fun post(action: () -> Unit) {
        mainHandler.post(action)
    }

    LaunchedEffect(importedWorkbenchColors) {
        Theme.applyWorkbenchColors(importedWorkbenchColors)
    }

    LaunchedEffect(Unit) {
        val grammarLoadErrors = context.getSharedPreferences("native-ide-assets", Context.MODE_PRIVATE)
            .getString("grammar-load-errors", null)
        if (!grammarLoadErrors.isNullOrBlank()) status = "Asset-pack grammar load failed: $grammarLoadErrors"
    }

    fun updateLanguageSettings() {
        languageClients.forEach { (language, client) ->
            val merged = SettingsResolver.merge(
                defaults = defaultSettings,
                user = userSettings,
                workspace = workspaceSettings,
                languageId = language,
            )
            client.updateSettings(SettingsResolver.sections(merged))
        }
    }

    fun loadWorkspaceSettings(root: String): CompletableFuture<JsonObject> {
        val path = "${root.trimEnd('/')}/.vscode/settings.json"
        return vfs.stat(path).thenCompose { entry ->
            if (entry.isDirectory) {
                return@thenCompose CompletableFuture.failedFuture(
                    IllegalStateException("Workspace settings path is a directory: $path"),
                )
            }
            if (entry.size > 1024L * 1024L) {
                return@thenCompose CompletableFuture.failedFuture(
                    IllegalStateException("Workspace settings exceed the 1 MiB limit: $path"),
                )
            }
            if (entry.size == 0L) return@thenCompose CompletableFuture.completedFuture(JsonObject())
            vfs.read(path, 0L, entry.size.toInt()).thenApply { packet ->
                if (!packet.eof || packet.content.size.toLong() != entry.size) {
                    throw IllegalStateException("Workspace settings could not be read completely: $path")
                }
                try {
                    JsonParser.parseString(packet.content.toString(Charsets.UTF_8)).asJsonObject
                } catch (error: RuntimeException) {
                    throw IllegalStateException("Workspace settings are invalid JSON: $path", error)
                }
            }
        }.exceptionallyCompose { error ->
            val cause = generateSequence(error) { it.cause }.last()
            if (cause is GuestVfsException && cause.code == "NotFound") {
                CompletableFuture.completedFuture(JsonObject())
            } else {
                CompletableFuture.failedFuture(cause)
            }
        }
    }

    fun saveUserSettings() {
        val parsed = try {
            JsonParser.parseString(settingsDraft).asJsonObject
        } catch (error: RuntimeException) {
            status = "User settings must be a JSON object: ${error.message}"
            return
        }
        val saved = context.getSharedPreferences("native-ide-settings", Context.MODE_PRIVATE)
            .edit()
            .putString("user-settings", parsed.toString())
            .commit()
        if (!saved) {
            status = "User settings could not be saved"
            return
        }
        userSettings = parsed
        settingsDraft = parsed.toString()
        updateLanguageSettings()
        settingsDialog = false
        status = "User settings saved"
    }

    fun refreshOutline(document: DocumentSession) {
        if (document.path.startsWith("scratch:")) {
            if (activePath == document.path) outlineSymbols = emptyList()
            return
        }
        val client = languageClientFor(document.path)
        if (client?.isReady != true) {
            if (activePath == document.path) outlineSymbols = emptyList()
            return
        }
        client.documentSymbols(document.path).whenComplete { response, error ->
            post {
                if (activePath != document.path) return@post
                if (error != null) {
                    outlineSymbols = emptyList()
                    if (currentDestination == "Outline") {
                        status = "Outline failed: ${error.cause?.message ?: error.message}"
                    }
                } else {
                    outlineSymbols = response.toOutlineSymbols()
                }
            }
        }
    }

    fun refreshSemanticTokens(document: DocumentSession) {
        if (document.path.startsWith("scratch:")) return
        val client = languageClientFor(document.path)
        if (client?.isReady != true) return
        client.semanticTokens(document.path).whenComplete { decorations, error ->
            post {
                if (activePath != document.path || editorHost.selection(document.path) == null) return@post
                if (error != null) {
                    status = "Semantic highlighting failed: ${error.cause?.message ?: error.message}"
                } else {
                    editorHost.setDecorations(document.path, decorations)
                }
            }
        }
    }

    val assetPackPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            status = "Installing asset pack…"
            io.execute {
                try {
                    val importer = VsCodeThemeImporter()
                    val installed: InstalledAssetPack?
                    val theme: ImportedTheme?
                    if (assetImportMode == "theme") {
                        val json = context.contentResolver.openInputStream(uri)?.use { it.readUtf8Limited(MAX_IMPORTED_THEME_BYTES) }
                            ?: throw IOException("Could not open the selected theme")
                        theme = importer.importJson(json, uri.lastPathSegment ?: "Imported Theme")
                        installed = null
                    } else {
                        installed = context.contentResolver.openInputStream(uri)?.use { input ->
                            AssetPackInstaller(File(context.filesDir, "asset-packs")).install(input)
                        } ?: throw IOException("Could not open the selected asset pack")
                        AssetPackGrammarLoader.loadPack(installed.directory)
                        theme = installed.themes.firstOrNull()?.let { contribution ->
                            importer.import(
                                File(installed.directory, "extension/${contribution.path.removePrefix("./")}"),
                                File(installed.directory, "extension"),
                            )
                        }
                    }
                    theme?.let {
                        it.workbenchColors.values.forEach(::parseThemeColor)
                        it.semanticTokenColors.values.forEach(::parseThemeColor)
                    }
                    post {
                        if (theme != null) {
                            saveImportedTheme(context, theme)
                            importedWorkbenchColors = theme.workbenchColors
                            Theme.applyWorkbenchColors(theme.workbenchColors)
                            editorView?.let { view ->
                                loadTextMateTheme(theme.textMateThemeJson)
                                view.setColorScheme(TextMateColorScheme.create(ThemeRegistry.getInstance()))
                                SoraBracketColorizer.configureColors(view, theme.semanticTokenColors)
                                activePath?.let { path ->
                                    documents.firstOrNull { it.path == path }?.let(::refreshSemanticTokens)
                                }
                            }
                            status = if (installed == null) {
                                "Applied theme ${theme.name}"
                            } else {
                                "Installed ${installed.displayName} and applied theme ${theme.name}"
                            }
                        } else {
                            status = "Installed ${installed?.displayName ?: "asset pack"}; no theme contribution was found"
                        }
                    }
                } catch (error: Exception) {
                    post { status = "Asset pack import failed: ${error.message ?: error.javaClass.simpleName}" }
                }
            }
        }
    }

    fun scheduleOutlineRefresh(path: String) {
        synchronized(scheduledOutlineRefreshes) {
            scheduledOutlineRefreshes.remove(path)?.cancel(false)
            scheduledOutlineRefreshes[path] = draftTimer.schedule(
                {
                    post {
                        synchronized(scheduledOutlineRefreshes) { scheduledOutlineRefreshes.remove(path) }
                        documents.firstOrNull { it.path == path }?.let { document ->
                            refreshOutline(document)
                            refreshSemanticTokens(document)
                        }
                    }
                },
                500,
                TimeUnit.MILLISECONDS,
            )
        }
    }

    fun loadDirectory(path: String) {
        if (!vfs.isConnected()) {
            status = "Connect to a guest to browse files"
            return
        }
        status = "Loading $path"
        vfs.listDir(path).whenComplete { result, error ->
            post {
                if (error != null) {
                    status = "Explorer error: ${error.message ?: "request failed"}"
                } else {
                    currentDirectory = path
                    entries = result.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenBy { it.name })
                    status = "Explorer ready"
                }
            }
        }
    }

    fun navigateToDirectory(path: String) {
        currentDestination = "Files"
        loadDirectory(path)
    }

    fun showOpenResult(result: OpenDocument?, error: Throwable?) {
        busy = false
        if (error != null) {
            status = "Open failed: ${error.cause?.message ?: error.message ?: "unknown error"}"
            return
        }
        when (result) {
            is OpenDocument.Editable -> {
                val existing = documents.firstOrNull { it.path == result.document.path }
                val session = existing ?: result.document.also(documents::add)
                activePath = session.path
                pagedDocument = null
                currentDestination = "Editor"
                status = if (session.conflict != null) "Remote changes detected; choose how to resolve" else "Ready"
                val languageId = lspLanguageId(session.path)
                if (languageId != null) {
                    languageClientsFor(session.path).filter { it.isReady }.forEach { client ->
                        client.didOpen(session.path, languageId, session.version, session.text)
                    }
                    refreshOutline(session)
                }
                if (session.conflict != null) conflictDocument = session
            }
            is OpenDocument.Paged -> {
                activePath = null
                pagedDocument = result.document
                currentDestination = "Editor"
                status = "Large file is read-only and paged"
            }
            null -> status = "Open returned no document"
        }
    }

    fun openPath(path: String) {
        if (!vfs.isConnected()) {
            status = "Connect to a guest before opening files"
            return
        }
        busy = true
        status = "Opening $path"
        repository.open(path).whenComplete { result, error -> post { showOpenResult(result, error) } }
    }

    fun connectGuest() {
        busy = true
        status = "Connecting to guest"
        languageServerIssues = emptyList()
        val notificationPermissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        keepAliveError = if (notificationPermissionGranted) null else {
            "Notification permission denied; Android may hide the background connection notification"
        }
        try {
            GuestConnectionService.start(context, endpoint.trim(), "Connecting")
        } catch (error: SecurityException) {
            keepAliveError = error.message ?: "Android denied the foreground service request"
        } catch (error: IllegalStateException) {
            keepAliveError = error.message ?: "Android does not allow a foreground service at this time"
        }
        vfs.connect(endpoint.trim(), token).thenCompose { vfs.roots() }.whenComplete { guestRoots, error ->
            post {
                busy = false
                if (error != null) {
                    connected = false
                    GuestConnectionService.stop(context)
                    status = "Connection failed: ${error.cause?.message ?: error.message}"
                } else {
                    connected = true
                    roots = guestRoots
                    connectDialog = false
                    io.execute {
                        val saved = profileStore.save(profileId, profileName, endpoint, token)
                        post {
                            profileId = saved.id
                            profileName = saved.name
                            profiles = profileStore.profiles()
                        }
                    }
                    try {
                        GuestConnectionService.start(context, endpoint.trim(), "Connected")
                    } catch (serviceError: SecurityException) {
                        keepAliveError = serviceError.message ?: "Android denied the foreground service request"
                    } catch (serviceError: IllegalStateException) {
                        keepAliveError = serviceError.message ?: "Android does not allow a foreground service at this time"
                    }
                    status = when {
                        keepAliveError != null -> "Connected; background keepalive unavailable: $keepAliveError"
                        guestRoots.isEmpty() -> "Connected; guest has no workspace roots"
                        else -> "Guest connected"
                    }
                    guestRoots.firstOrNull()?.let(::loadDirectory)
                    guestRoots.firstOrNull()?.let { workspace ->
                        loadWorkspaceSettings(workspace).whenComplete { settings, settingsError ->
                            post {
                                if (settingsError != null) {
                                    status = "Guest connected; could not load workspace settings: " +
                                        (settingsError.cause?.message ?: settingsError.message)
                                } else {
                                    workspaceSettings = settings
                                    updateLanguageSettings()
                                    val starts = mapOf(
                                        "python" to languageClients.getValue("python")
                                            .start(workspace, "basedpyright"),
                                        "python-format" to languageClients.getValue("python-format")
                                            .start(workspace, "ruff"),
                                        "typescript" to languageClients.getValue("typescript")
                                            .start(workspace, "vtsls"),
                                    )
                                    CompletableFuture.allOf(*starts.values.toTypedArray()).whenComplete { _, _ ->
                                        post {
                                            documents.forEach { document ->
                                                val languageId = lspLanguageId(document.path)
                                                if (languageId != null) {
                                                    languageClientsFor(document.path).filter { it.isReady }
                                                        .forEach { client ->
                                                            client.didOpen(
                                                                document.path,
                                                                languageId,
                                                                document.version,
                                                                document.text,
                                                            )
                                                        }
                                                    if (activePath == document.path) {
                                                        refreshOutline(document)
                                                        refreshSemanticTokens(document)
                                                    }
                                                }
                                            }
                                            val unavailable = starts.mapNotNull { (language, future) ->
                                                if (languageClients.getValue(language).isReady) return@mapNotNull null
                                                val failure = future.handle { _, error -> error }.getNow(null)
                                                LanguageServerIssue(
                                                    language,
                                                    failure?.cause?.message ?: failure?.message ?: "server not ready",
                                                )
                                            }
                                            languageServerIssues = unavailable
                                            val ready = starts.keys.filter { languageClients.getValue(it).isReady }
                                            status = buildList {
                                                if (ready.isNotEmpty()) add("${ready.joinToString()} language server(s) ready")
                                                if (unavailable.isNotEmpty()) {
                                                    add(unavailable.joinToString("; ") { "${it.server}: ${it.message}" })
                                                }
                                            }.joinToString("; ").ifEmpty { "No language servers are ready" }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    documents.filter { it.dirty }.forEach { document ->
                        repository.reconcile(document).whenComplete { result, reconcileError ->
                            post {
                                if (reconcileError != null) {
                                    status = "Reconnect save failed: ${reconcileError.message}"
                                } else if (result is SaveResult.Conflict) {
                                    conflictDocument = document
                                    status = "Reconnect found external edits in ${document.path}"
                                } else if (result is SaveResult.Saved) {
                                    status = "Reconnected and saved ${document.path}"
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        connectGuest()
    }

    fun requestGuestConnection() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            connectGuest()
        }
    }

    fun saveActiveDocument() {
        val document = activeDocument ?: return
        if (document.path.startsWith("scratch:")) {
            document.dirty = false
            repository.persistDraft(document)
            status = "Saved locally"
            return
        }
        status = "Saving ${document.path}"
        repository.save(document).whenComplete { result, error ->
            post {
                if (error != null) {
                    status = "Save failed: ${error.cause?.message ?: error.message}"
                } else {
                    when (result) {
                        is SaveResult.Saved -> status = "Saved ${document.path}"
                        SaveResult.QueuedOffline -> status = "Saved locally; queued until guest reconnects"
                        is SaveResult.Conflict -> {
                            conflictDocument = document
                            status = "Save conflict: remote file changed"
                        }
                    }
                }
            }
        }
    }

    fun activeLspPosition(): Pair<DocumentSession, LspPosition>? {
        val document = activeDocument ?: return null
        val client = languageClientFor(document.path)
        if (client?.isReady != true || document.path.startsWith("scratch:")) {
            status = "Language intelligence requires a connected Python or TypeScript language server"
            return null
        }
        val cursor = editorView?.cursor ?: return null
        return document to LspPosition(cursor.leftLine, cursor.leftColumn)
    }

    fun editorReady(view: CodeEditor) {
        val newlyBound = editorView !== view
        editorView = view
        val target = pendingNavigation
        if (target != null && target.path == activeDocument?.path) {
            view.setSelection(target.line, target.character)
            view.ensureSelectionVisible()
            pendingNavigation = null
        }
        if (newlyBound) activeDocument?.let(::refreshSemanticTokens)
    }

    fun navigateToLocation(location: LanguageLocation) {
        val path = java.net.URI(location.uri).path ?: return
        val start = location.range.getAsJsonObject("start") ?: return
        val target = EditorNavigationTarget(path, start.get("line").asInt, start.get("character").asInt)
        currentDestination = "Editor"
        if (activeDocument?.path == path) {
            editorView?.let { view ->
                view.setSelection(target.line, target.character)
                view.ensureSelectionVisible()
            }
            pendingNavigation = null
        } else {
            pendingNavigation = target
            openPath(path)
        }
    }

    fun showCompletion() {
        val (document, position) = activeLspPosition() ?: return
        val client = languageClientFor(document.path) ?: return
        client.completion(document.path, position).whenComplete { response, error ->
            post {
                if (error != null) {
                    status = "Completion failed: ${error.cause?.message ?: error.message}"
                    return@post
                }
                val items = when {
                    response.isJsonArray -> response.asJsonArray
                    response.isJsonObject -> response.asJsonObject.getAsJsonArray("items") ?: JsonArray()
                    else -> JsonArray()
                }
                val defaultRange = completionWordRange(document.text, position)
                completionChoices = items.mapNotNull { item ->
                    if (!item.isJsonObject) return@mapNotNull null
                    val value = item.asJsonObject
                    val label = value.get("label")?.takeUnless { it.isJsonNull }?.asString ?: return@mapNotNull null
                    val textEdit = value.getAsJsonObject("textEdit")
                    val replaceRange = textEdit?.getAsJsonObject("replace") ?: textEdit?.getAsJsonObject("range")
                    val edit = if (replaceRange != null && textEdit.has("newText")) {
                        replaceRange.toLspRange()?.let { LspTextEdit(it, textEdit.get("newText").asString) }
                    } else {
                        LspTextEdit(defaultRange, value.get("insertText")?.takeUnless { it.isJsonNull }?.asString ?: label)
                    }
                    CompletionChoice(
                        label,
                        value.get("detail")?.takeUnless { it.isJsonNull }?.asString,
                        edit,
                    )
                }
                completionDialog = completionChoices.isNotEmpty()
                status = if (completionChoices.isEmpty()) "No completion suggestions" else "Completion suggestions"
            }
        }
    }

    fun showHover() {
        val (document, position) = activeLspPosition() ?: return
        val client = languageClientFor(document.path) ?: return
        client.hover(document.path, position).whenComplete { response, error ->
            post {
                if (error != null) {
                    status = "Hover failed: ${error.cause?.message ?: error.message}"
                    return@post
                }
                hoverText = response.takeUnless { it.isJsonNull }?.asJsonObject
                    ?.get("contents")?.let(::hoverContents)
                if (hoverText == null) status = "No hover information"
            }
        }
    }

    fun applyWorkspaceEdits(
        edits: Map<String, List<LspTextEdit>>,
        client: LanguageClient,
        successMessage: String,
        onApplied: (() -> Unit)? = null,
    ) {
        if (edits.isEmpty()) {
            onApplied?.invoke()
            return
        }
        val outsideWorkspace = edits.keys.firstOrNull { path ->
            roots.none { root -> isPathWithinRoot(path, root) }
        }
        if (outsideWorkspace != null) {
            status = "Workspace edit refused a change outside the connected workspace"
            return
        }
        val documentsToEdit = edits.mapValues { (path, _) ->
            documents.firstOrNull { it.path == path }?.let {
                CompletableFuture.completedFuture(it)
            } ?: repository.open(path).thenApply { opened ->
                (opened as? OpenDocument.Editable)?.document
                    ?: throw IllegalStateException("Cannot edit paged file $path")
            }
        }
        CompletableFuture.allOf(*documentsToEdit.values.toTypedArray()).whenComplete { _, openError ->
            post {
                if (openError != null) {
                    status = "Workspace edit could not load all affected files: " +
                        (openError.cause?.message ?: openError.message)
                    return@post
                }
                try {
                    val updatedTexts = edits.mapValues { (path, textEdits) ->
                        val target = documentsToEdit.getValue(path).join()
                        target to applyLspTextEdits(target.text, textEdits)
                    }
                    updatedTexts.forEach { (path, update) ->
                        val (target, text) = update
                        if (documents.none { it.path == path }) documents.add(target)
                        val languageId = lspLanguageId(path)
                        if (languageId != null) {
                            languageClientsFor(path).filter { it.isReady }.forEach { recipient ->
                                recipient.didOpen(path, languageId, target.version, target.text)
                            }
                        }
                        target.updateText(text)
                        latestEdit(target)
                        if (languageId != null) {
                            languageClientsFor(path).filter { it.isReady }.forEach { recipient ->
                                recipient.didChange(path, target.version, target.text)
                            }
                        }
                        if (activePath == path) editorView?.setText(text)
                    }
                    status = "$successMessage in ${updatedTexts.size} file(s); save to persist changes"
                    onApplied?.invoke()
                } catch (applyError: RuntimeException) {
                    status = "Workspace edit could not be applied: ${applyError.message}"
                }
            }
        }
    }

    fun showCodeActions() {
        val (document, position) = activeLspPosition() ?: return
        val client = languageClientFor(document.path) ?: return
        val uri = java.net.URI("file", null, document.path, null).toASCIIString()
        val diagnosticsForFile = JsonArray().apply {
            diagnostics.filter { it.uri == uri }.forEach { diagnostic ->
                add(JsonObject().apply {
                    add("range", diagnostic.range)
                    diagnostic.severity?.let { addProperty("severity", it) }
                    addProperty("message", diagnostic.message)
                    diagnostic.source?.let { addProperty("source", it) }
                    diagnostic.code?.let { add("code", it) }
                })
            }
        }
        val cursorRange = LspRange(position, position)
        client.codeActions(document.path, cursorRange, diagnosticsForFile).whenComplete { response, error ->
            post {
                if (error != null) {
                    status = "Code actions failed: ${error.cause?.message ?: error.message}"
                    return@post
                }
                codeActionChoices = if (response.isJsonArray) {
                    response.asJsonArray.mapNotNull { value ->
                        if (!value.isJsonObject) return@mapNotNull null
                        val action = value.asJsonObject
                        val title = action.get("title")?.takeUnless { it.isJsonNull }?.asString
                            ?: return@mapNotNull null
                        CodeActionChoice(title, action)
                    }
                } else {
                    emptyList()
                }
                codeActionDialog = codeActionChoices.isNotEmpty()
                status = if (codeActionChoices.isEmpty()) "No code actions available" else "Select a code action"
            }
        }
    }

    fun applyCodeAction(choice: CodeActionChoice) {
        val document = activeDocument ?: return
        val client = languageClientFor(document.path) ?: return
        codeActionDialog = false
        val action = choice.action
        val commandValue = action.get("command")?.takeUnless { it.isJsonNull }
        val commandObject = commandValue?.takeIf { it.isJsonObject }?.asJsonObject
        val command = commandObject?.get("command")?.takeUnless { it.isJsonNull }?.asString
            ?: commandValue?.takeIf { it.isJsonPrimitive }?.asString
        val arguments = commandObject?.getAsJsonArray("arguments") ?: JsonArray()
        val workspaceEdit = action.get("edit")?.takeUnless { it.isJsonNull }
        val edits = try {
            workspaceEdit?.workspaceTextEdits().orEmpty()
        } catch (error: RuntimeException) {
            status = "Code action returned invalid workspace edits: ${error.message}"
            return
        }
        if (edits.isEmpty() && command == null) {
            status = "Code action has no applicable edit or command"
            return
        }
        val execute = {
            if (command != null) {
                client.executeCommand(command, arguments).whenComplete { _, error ->
                    post {
                        status = if (error == null) "Applied ${choice.title}" else {
                            "Code action failed: ${error.cause?.message ?: error.message}"
                        }
                    }
                }
            }
        }
        applyWorkspaceEdits(edits, client, "Applied ${choice.title}", execute)
    }

    fun goToDefinition() {
        val (document, position) = activeLspPosition() ?: return
        val client = languageClientFor(document.path) ?: return
        client.definition(document.path, position).whenComplete { response, error ->
            post {
                if (error != null) {
                    status = "Go to Definition failed: ${error.cause?.message ?: error.message}"
                } else {
                    val location = response.toLocations().firstOrNull()
                    if (location == null) status = "No definition found" else navigateToLocation(location)
                }
            }
        }
    }

    fun findReferences() {
        val (document, position) = activeLspPosition() ?: return
        val client = languageClientFor(document.path) ?: return
        client.references(document.path, position).whenComplete { response, error ->
            post {
                if (error != null) {
                    status = "Find References failed: ${error.cause?.message ?: error.message}"
                } else {
                    referenceLocations = response.toLocations()
                    currentDestination = "References"
                    status = "${referenceLocations.size} reference(s)"
                }
            }
        }
    }

    fun formatActiveDocument() {
        val document = activeDocument ?: return
        val client = formattingClientFor(document.path)
        if (client?.isReady != true) {
            status = if (document.path.substringAfterLast('.', "").lowercase() in setOf("py", "pyi")) {
                "Python formatting requires Ruff to be installed in the guest"
            } else {
                "Formatting requires a connected language server for this file"
            }
            return
        }
        client.formatting(document.path).whenComplete { edits, error ->
            post {
                if (error != null) {
                    status = "Format failed: ${error.cause?.message ?: error.message}"
                } else if (edits.isEmpty()) {
                    status = "Document is already formatted"
                } else {
                    editorHost.applyEdits(document.path, edits)
                    latestEdit(document)
                    status = "Formatted document"
                }
            }
        }
    }

    fun beginRename() {
        val (document, position) = activeLspPosition() ?: return
        renameValue = wordAtPosition(document.text, position)
        if (renameValue.isBlank()) {
            status = "Place the cursor on a symbol to rename"
            return
        }
        renameDialog = true
    }

    fun applyRename() {
        val (document, position) = activeLspPosition() ?: return
        val client = languageClientFor(document.path) ?: return
        val newName = renameValue.trim()
        if (newName.isEmpty()) {
            status = "A new symbol name is required"
            return
        }
        client.rename(document.path, position, newName).whenComplete { response, error ->
            post {
                renameDialog = false
                if (error != null) {
                    status = "Rename failed: ${error.cause?.message ?: error.message}"
                    return@post
                }
                val edits = try {
                    response.workspaceTextEdits()
                } catch (parseError: RuntimeException) {
                    status = "Rename returned invalid workspace edits: ${parseError.message}"
                    return@post
                }
                if (edits.isEmpty()) {
                    status = "Rename produced no edits"
                } else {
                    applyWorkspaceEdits(edits, client, "Renamed symbol")
                }
            }
        }
    }

    fun runCommand(commandId: String) {
        when (commandId) {
            "workbench.action.files.save" -> saveActiveDocument()
            "workbench.action.files.newUntitledFile" -> {
                val document = DocumentSession(
                    "scratch:/Untitled-${documents.size}.py",
                    "",
                    baseModifiedTs = null,
                    dirty = false,
                )
                documents.add(document)
                activePath = document.path
                currentDestination = "Editor"
                status = "Created untitled file"
            }
            "workbench.action.files.openFile", "workbench.action.quickOpen" -> {
                quickOpenFilter = ""
                quickOpenDialog = true
            }
            "workbench.action.showCommands" -> {
                commandFilter = ""
                commandDialog = true
            }
            "workbench.action.closeActiveEditor" -> {
                activePath?.let { path ->
                    documents.firstOrNull { it.path == path }?.let(closedDocuments::add)
                    documents.removeAll { it.path == path }
                }
                activePath = documents.lastOrNull()?.path
                pagedDocument = null
                status = "Editor closed"
            }
            "workbench.action.files.saveAs" -> {
                quickOpenFilter = ""
                quickOpenDialog = true
                status = "Choose a guest path from Explorer for Save As"
            }
            "undo" -> editorView?.undo()
            "redo" -> editorView?.redo()
            "editor.action.clipboardCopyAction" -> copySelectionAction(false)
            "editor.action.clipboardCutAction" -> copySelectionAction(true)
            "editor.action.clipboardPasteAction" -> pasteClipboardAction()
            "editor.action.selectAll" -> editorView?.let { view ->
                val lastLine = (view.lineCount - 1).coerceAtLeast(0)
                view.setSelectionRegion(0, 0, lastLine, view.text.getColumnCount(lastLine))
            }
            "editor.action.indentLines" -> editorView?.indentSelection()
            "editor.action.outdentLines" -> editorView?.unindentSelection()
            "editor.action.commentLine" -> {
                toggleLineCommentAction()
                activeDocument?.let {
                    latestEdit(it)
                    status = "Toggled line comment"
                }
            }
            "editor.action.moveLinesUpAction",
            "editor.action.moveLinesDownAction",
            "editor.action.copyLinesUpAction",
            "editor.action.copyLinesDownAction",
            "editor.action.deleteLines",
            "editor.action.insertLineAfter",
            "editor.action.insertLineBefore",
            -> activeDocument?.let { document ->
                editorView?.let { editor ->
                    if (editCurrentLine(document, editor, commandId)) {
                        latestEdit(document)
                        status = "Edited line"
                    }
                }
            }
            "actions.find", "editor.action.startFindReplaceAction" -> {
                findDialog = true
                replaceQuery = ""
            }
            "workbench.action.gotoLine" -> gotoLineDialog = true
            "workbench.action.quickSwitchEditor" -> {
                if (documents.size > 1) {
                    val next = (documents.indexOf(activeDocument) + 1) % documents.size
                    activePath = documents[next].path
                    pagedDocument = null
                }
            }
            "workbench.action.closeOtherEditors" -> {
                activePath?.let { path ->
                    closedDocuments.addAll(documents.filter { it.path != path })
                    documents.removeAll { it.path != path }
                }
            }
            "workbench.action.reopenClosedEditor" -> {
                closedDocuments.removeLastOrNull()?.let { document ->
                    documents.add(document)
                    activePath = document.path
                    pagedDocument = null
                    status = "Reopened ${document.path}"
                }
            }
            "editor.action.formatDocument" -> formatActiveDocument()
            "editor.action.goToDefinition", "editor.action.peekDefinition" -> goToDefinition()
            "editor.action.goToReferences" -> findReferences()
            "editor.action.rename" -> beginRename()
            "editor.action.triggerSuggest" -> showCompletion()
            "editor.action.showHover" -> showHover()
            "editor.action.quickFix" -> showCodeActions()
            "workbench.action.openSettings" -> {
                settingsDraft = userSettings.toString()
                settingsDialog = true
            }
            "editor.action.jumpToBracket" -> {
                val matched = activeDocument?.let { document ->
                    editorView?.let { jumpToMatchingBracket(document, it) }
                } ?: false
                status = if (matched) "Moved to matching bracket" else "No matching bracket"
            }
            "editor.action.addSelectionToNextFindMatch",
            "editor.action.selectHighlights",
            "editor.action.insertCursorAbove",
            "editor.action.insertCursorBelow",
            -> status = "Multi-cursor is not available in the pinned Sora editor yet"
            "editor.action.nextMatchFindAction" -> findInEditorAction(true)
            "editor.action.previousMatchFindAction" -> findInEditorAction(false)
            "editor.action.toggleWordWrap" -> editorView?.let { it.setWordwrap(!it.isWordwrap) }
            else -> status = "${EditorCommands.top50.firstOrNull { it.id == commandId }?.title ?: commandId} is not available yet"
        }
    }

    fun toggleLineComment() {
        val document = activeDocument ?: return
        val view = editorView ?: return
        val cursorLine = view.cursor.leftLine.coerceIn(0, document.text.count { it == '\n' })
        val lines = document.text.split('\n').toMutableList()
        if (cursorLine !in lines.indices) return
        val comment = when (document.languageId) {
            "py", "sh", "yaml", "toml" -> "# "
            "sql" -> "-- "
            else -> "// "
        }
        val selected = lines[cursorLine]
        val start = selected.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) 0 else it }
        val token = comment.trimEnd()
        lines[cursorLine] = if (selected.startsWith(token, start)) {
            val hasSpace = selected.getOrNull(start + token.length) == ' '
            selected.removeRange(start, start + token.length + if (hasSpace) 1 else 0)
        } else {
            selected.substring(0, start) + comment + selected.substring(start)
        }
        document.updateText(lines.joinToString("\n"))
        view.setText(document.text)
        view.setSelection(cursorLine, 0)
    }

    fun copySelection(cut: Boolean) {
        val document = activeDocument ?: return
        val view = editorView ?: return
        val range = view.cursorRange
        if (range.startIndex == range.endIndex) {
            status = if (cut) "Select text before cutting" else "Select text before copying"
            return
        }
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(
            ClipData.newPlainText(
                document.path.substringAfterLast('/'),
                document.text.substring(range.startIndex, range.endIndex),
            ),
        )
        if (cut) view.deleteText()
    }

    fun pasteClipboard() {
        val item = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
            ?: return
        editorView?.insertText(item, 1)
    }
    copySelectionAction = ::copySelection
    pasteClipboardAction = ::pasteClipboard

    fun findInEditor(next: Boolean) {
        val document = activeDocument ?: return
        val view = editorView ?: return
        if (findQuery.isEmpty()) {
            findDialog = true
            return
        }
        val start = view.cursor.right
        val foundAt = if (next) {
            document.text.indexOf(findQuery, start.coerceAtMost(document.text.length))
                .takeIf { it >= 0 } ?: document.text.indexOf(findQuery)
        } else {
            val from = (start - findQuery.length - 1).coerceAtLeast(0)
            document.text.lastIndexOf(findQuery, from)
                .takeIf { it >= 0 } ?: document.text.lastIndexOf(findQuery)
        }
        if (foundAt >= 0) {
            val prefix = document.text.substring(0, foundAt)
            val line = prefix.count { it == '\n' }
            val column = prefix.length - (prefix.lastIndexOf('\n') + 1)
            view.setSelectionRegion(line, column, line, column + findQuery.length)
            view.ensureSelectionVisible()
        } else {
            status = "No matches for \"$findQuery\""
        }
    }
    toggleLineCommentAction = ::toggleLineComment
    findInEditorAction = ::findInEditor

    val commandHandler by rememberUpdatedState<(String) -> Unit>(::runCommand)
    val editHandler by rememberUpdatedState<(DocumentSession) -> Unit>(latestEdit)
    val wide = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 840
    val resolvedEditorSettings = SettingsResolver.merge(
        defaultSettings,
        userSettings,
        workspaceSettings,
        activeDocument?.path?.let(::settingsLanguageId),
    ).getAsJsonObject("editor")
    val editorFontSize = resolvedEditorSettings?.get("fontSize")?.asFloat?.coerceIn(8f, 40f) ?: 14f
    val editorTabWidth = resolvedEditorSettings?.get("tabSize")?.asInt?.coerceIn(1, 8) ?: 4

    LaunchedEffect(activeDocument?.path) {
        outlineSymbols = emptyList()
        activeDocument?.let(::refreshOutline)
    }

    DisposableEffect(lifecycleOwner, documents) {
        vfs.connectionStateListener = { isConnected, failure ->
            post {
                connected = isConnected
                if (!isConnected) languageClients.values.forEach(LanguageClient::onDisconnected)
                if (!isConnected && !busy) {
                    GuestConnectionService.stop(context)
                    if (failure != null) status = "Guest disconnected: $failure"
                }
            }
        }
        languageClients.values.forEach { client ->
            client.diagnosticsListener = { uri, updates ->
                post {
                    diagnosticsByClient.getOrPut(client) { mutableMapOf() }[uri] = updates
                    diagnostics.clear()
                    diagnostics.addAll(diagnosticsByClient.values.flatMap { it.values.flatten() })
                }
            }
            client.messageListener = { message -> post { status = message } }
        }
        val editorSubscription = editorHost.observe { event ->
            if (event is com.avfstudio.nativeide.editor.EditorEvent.ContentChanged &&
                !event.uri.startsWith("scratch:")
            ) {
                languageClientsFor(event.uri).filter { it.isReady }.forEach { client ->
                    client.didChange(event.uri, event.version, event.text)
                }
                if (activePath == event.uri) scheduleOutlineRefresh(event.uri)
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                documents.forEach { document ->
                    io.execute { repository.persistDraft(document) }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            editorSubscription.close()
            languageClients.values.forEach { client ->
                client.close()
                client.diagnosticsListener = null
                client.messageListener = null
            }
            vfs.connectionStateListener = null
            vfs.close()
            GuestConnectionService.stop(context)
            io.shutdown()
            draftTimer.shutdown()
        }
    }

    BackHandler(enabled = commandDialog || quickOpenDialog || findDialog || connectDialog || activePath != null) {
        when {
            commandDialog -> commandDialog = false
            quickOpenDialog -> quickOpenDialog = false
            findDialog -> findDialog = false
            connectDialog -> connectDialog = false
            else -> {
                activePath = null
                pagedDocument = null
                currentDestination = "Files"
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding(),
    ) {
        WorkbenchTopBar(
            connected = connected,
            busy = busy,
            status = status,
            onConnect = { connectDialog = true },
            onCommands = { commandDialog = true },
            onQuickOpen = { quickOpenDialog = true },
            onNewScratch = { runCommand("workbench.action.files.newUntitledFile") },
        )
        if (wide) {
            Row(Modifier.weight(1f)) {
                ActivityBar(currentDestination) { currentDestination = it }
                if (currentDestination == "Files" || currentDestination == "Editor") {
                    ExplorerPane(
                        roots = roots,
                        currentDirectory = currentDirectory,
                        entries = entries,
                        onOpen = ::openPath,
                        onNavigate = ::loadDirectory,
                    )
                } else {
                    if (currentDestination == "Problems") {
                        ProblemsPane(diagnostics, languageServerIssues) { diagnostic ->
                            navigateToLocation(LanguageLocation(diagnostic.uri, diagnostic.range))
                        }
                    } else if (currentDestination == "References") {
                        ReferencesPane(referenceLocations, onSelect = ::navigateToLocation)
                    } else if (currentDestination == "Outline") {
                        OutlinePane(activeDocument, outlineSymbols, onSelect = ::navigateToLocation)
                    } else SideView(currentDestination)
                }
                EditorArea(
                    documents = documents,
                    activeDocument = activeDocument,
                    activePaged = pagedDocument,
                    status = status,
                    editorHost = editorHost,
                    editorFontSize = editorFontSize,
                    editorTabWidth = editorTabWidth,
                    onSelectDocument = { activePath = it; pagedDocument = null },
                    onCloseDocument = { path ->
                        languageClientsFor(path).forEach { it.didClose(path) }
                        documents.removeAll { it.path == path }
                        if (activePath == path) activePath = documents.lastOrNull()?.path
                    },
                    onNavigateDirectory = ::navigateToDirectory,
                    onSave = ::saveActiveDocument,
                    onPage = { direction ->
                        val current = pagedDocument ?: return@EditorArea
                        repository.page(current.path, current, direction).whenComplete { page, error ->
                            post {
                                if (error == null) pagedDocument = page
                                else status = "Page load failed: ${error.message}"
                            }
                        }
                    },
                    onEditorReady = ::editorReady,
                    onEdit = { document ->
                        editHandler(document)
                    },
                    onCommand = { commandHandler(it) },
                )
            }
        } else {
            when (currentDestination) {
                "Files" -> ExplorerPane(
                    roots = roots,
                    currentDirectory = currentDirectory,
                    entries = entries,
                    modifier = Modifier.weight(1f),
                    onOpen = ::openPath,
                    onNavigate = ::loadDirectory,
                )
                "Editor" -> EditorArea(
                    documents = documents,
                    activeDocument = activeDocument,
                    activePaged = pagedDocument,
                    status = status,
                    editorHost = editorHost,
                    editorFontSize = editorFontSize,
                    editorTabWidth = editorTabWidth,
                    modifier = Modifier.weight(1f),
                    onSelectDocument = { activePath = it; pagedDocument = null },
                    onCloseDocument = { path ->
                        languageClientsFor(path).forEach { it.didClose(path) }
                        documents.removeAll { it.path == path }
                        if (activePath == path) activePath = documents.lastOrNull()?.path
                    },
                    onNavigateDirectory = ::navigateToDirectory,
                    onSave = ::saveActiveDocument,
                    onPage = { direction ->
                        val current = pagedDocument ?: return@EditorArea
                        repository.page(current.path, current, direction).whenComplete { page, error ->
                            post {
                                if (error == null) pagedDocument = page
                                else status = "Page load failed: ${error.message}"
                            }
                        }
                    },
                    onEditorReady = ::editorReady,
                    onEdit = { document -> editHandler(document) },
                    onCommand = { commandHandler(it) },
                )
                "Problems" -> ProblemsPane(
                    diagnostics,
                    languageServerIssues,
                    Modifier.weight(1f),
                ) { diagnostic ->
                    navigateToLocation(LanguageLocation(diagnostic.uri, diagnostic.range))
                }
                "Outline" -> OutlinePane(
                    activeDocument,
                    outlineSymbols,
                    Modifier.weight(1f),
                    ::navigateToLocation,
                )
                "References" -> ReferencesPane(
                    referenceLocations,
                    Modifier.weight(1f),
                    ::navigateToLocation,
                )
                else -> SideView(currentDestination, Modifier.weight(1f))
            }
            CompactNavigation(currentDestination) { currentDestination = it }
        }
        StatusBar(
            status = status,
            document = activeDocument,
            paged = pagedDocument,
            onSave = ::saveActiveDocument,
            onEncodingClick = { encodingDialog = activeDocument != null },
            onEolClick = { eolDialog = activeDocument != null },
        )
    }

    if (connectDialog) {
        ConnectDialog(
            endpoint = endpoint,
            token = token,
            profiles = profiles,
            profileId = profileId,
            profileName = profileName,
            onProfileSelected = ::selectProfile,
            onNewProfile = {
                profileId = null
                profileName = ""
                endpoint = ""
                token = ""
            },
            onProfileDeleted = { id ->
                profileStore.delete(id)
                profiles = profileStore.profiles()
                if (profileId == id) {
                    profileId = null
                    profileName = ""
                    endpoint = ""
                    token = ""
                }
            },
            onProfileNameChange = { profileName = it },
            onEndpointChange = { endpoint = it },
            onTokenChange = { token = it },
            onConnect = ::requestGuestConnection,
            onDismiss = { connectDialog = false },
        )
    }
    if (commandDialog) {
        CommandDialog(
            filter = commandFilter,
            onFilter = { commandFilter = it },
            onCommand = {
                commandDialog = false
                runCommand(it)
            },
            onDismiss = { commandDialog = false },
        )
    }
    if (quickOpenDialog) {
        QuickOpenDialog(
            query = quickOpenFilter,
            entries = entries.filterNot(FileEntry::isDirectory),
            onQuery = { quickOpenFilter = it },
            onOpen = {
                quickOpenDialog = false
                openPath(it)
            },
            onDismiss = { quickOpenDialog = false },
        )
    }
    if (findDialog) {
        FindDialog(
            query = findQuery,
            replacement = replaceQuery,
            onQuery = { findQuery = it },
            onReplacement = { replaceQuery = it },
            onFindNext = { findInEditor(next = true) },
            onReplaceAll = {
                val document = activeDocument
                if (document != null && findQuery.isNotEmpty()) {
                    document.updateText(document.text.replace(findQuery, replaceQuery))
                    editorView?.setText(document.text)
                    repository.persistDraft(document)
                    status = "Replaced all occurrences"
                }
            },
            onDismiss = { findDialog = false },
        )
    }
    if (gotoLineDialog) {
        GotoLineDialog(
            value = gotoLine,
            onValueChange = { gotoLine = it },
            onGo = {
                val target = gotoLine.toIntOrNull()
                val view = editorView
                if (target != null && view != null) {
                    val line = (target - 1).coerceIn(0, view.lineCount - 1)
                    view.setSelection(line, 0)
                    view.ensureSelectionVisible()
                }
                gotoLineDialog = false
            },
            onDismiss = { gotoLineDialog = false },
        )
    }
    if (encodingDialog) {
        activeDocument?.let { document ->
            ChoiceDialog(
                title = "File Encoding",
                selected = document.encoding,
                options = listOf("UTF-8", "UTF-16LE", "UTF-16BE", "ISO-8859-1"),
                onSelected = {
                    document.encoding = it
                    repository.persistDraft(document)
                    encodingDialog = false
                    status = "Encoding set to $it"
                },
                onDismiss = { encodingDialog = false },
            )
        }
    }
    if (eolDialog) {
        activeDocument?.let { document ->
            ChoiceDialog(
                title = "Line Ending",
                selected = if (document.eol == "\r\n") "CRLF" else "LF",
                options = listOf("LF", "CRLF"),
                onSelected = { selected ->
                    val replacement = if (selected == "CRLF") "\r\n" else "\n"
                    val normalized = document.text.replace("\r\n", "\n").replace("\r", "\n")
                        .replace("\n", replacement)
                    document.updateText(normalized)
                    document.eol = replacement
                    editorView?.setText(normalized)
                    repository.persistDraft(document)
                    eolDialog = false
                    status = "Line endings set to $selected"
                },
                onDismiss = { eolDialog = false },
            )
        }
    }
    if (completionDialog) {
        CompletionDialog(
            choices = completionChoices,
            onChoose = { choice ->
                val document = activeDocument
                val edit = choice.edit
                if (document != null && edit != null) {
                    editorHost.applyEdits(document.path, listOf(edit))
                    latestEdit(document)
                }
                completionDialog = false
            },
            onDismiss = { completionDialog = false },
        )
    }
    if (codeActionDialog) {
        AlertDialog(
            onDismissRequest = { codeActionDialog = false },
            title = { Text("Code Actions") },
            text = {
                Column {
                    codeActionChoices.forEach { choice ->
                        TextButton(
                            onClick = { applyCodeAction(choice) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(choice.title, color = foreground)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { codeActionDialog = false }) { Text("Close") }
            },
        )
    }
    hoverText?.let { contents ->
        AlertDialog(
            onDismissRequest = { hoverText = null },
            title = { Text("Hover") },
            text = { Text(contents, color = foreground, fontFamily = FontFamily.Monospace) },
            confirmButton = { TextButton(onClick = { hoverText = null }) { Text("Close") } },
        )
    }
    if (renameDialog) {
        AlertDialog(
            onDismissRequest = { renameDialog = false },
            title = { Text("Rename Symbol") },
            text = {
                OutlinedTextField(
                    value = renameValue,
                    onValueChange = { renameValue = it },
                    label = { Text("New name") },
                    singleLine = true,
                )
            },
            confirmButton = { TextButton(onClick = ::applyRename) { Text("Rename") } },
            dismissButton = { TextButton(onClick = { renameDialog = false }) { Text("Cancel") } },
        )
    }
    if (settingsDialog) {
        AlertDialog(
            onDismissRequest = { settingsDialog = false },
            title = { Text("User Settings") },
            text = {
                Column {
                    Text(
                        "User scope is stored on this device. Workspace settings are loaded from " +
                            "${roots.firstOrNull()?.trimEnd('/') ?: "<workspace>"}/.vscode/settings.json.",
                        color = muted,
                        fontSize = 11.sp,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            assetImportMode = "vsix"
                            assetPackPicker.launch(arrayOf("*/*"))
                        }) { Text("Import VSIX") }
                        TextButton(onClick = {
                            assetImportMode = "theme"
                            assetPackPicker.launch(arrayOf("application/json", "text/*", "*/*"))
                        }) { Text("Import theme JSON") }
                    }
                    OutlinedTextField(
                        value = settingsDraft,
                        onValueChange = { settingsDraft = it },
                        label = { Text("settings.json") },
                        modifier = Modifier.fillMaxWidth().height(300.dp),
                        minLines = 10,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = ::saveUserSettings) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { settingsDialog = false }) { Text("Cancel") }
            },
        )
    }
    conflictDocument?.let { document ->
        val remote = document.conflict
        if (remote != null) {
            AlertDialog(
                onDismissRequest = { conflictDocument = null },
                title = { Text("File changed on guest") },
                text = {
                    Text("Your local draft is preserved. Load the guest copy or save your draft on top of the latest version.")
                },
                confirmButton = {
                    TextButton(onClick = {
                        repository.keepLocalAfterConflict(document).whenComplete { result, error ->
                            post {
                                conflictDocument = null
                                status = if (error != null) "Save failed: ${error.message}" else "Saved local draft over guest version"
                            }
                        }
                    }) { Text("Keep my draft") }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = {
                            repository.discardLocalForRemote(document).whenComplete { _, error ->
                                post {
                                    conflictDocument = null
                                    if (error == null) status = "Loaded guest version"
                                    else status = "Reload failed: ${error.message}"
                                }
                            }
                        }) { Text("Load guest copy") }
                        TextButton(onClick = { conflictDocument = null }) { Text("Later") }
                    }
                },
            )
        }
    }
}

@Composable
private fun WorkbenchTopBar(
    connected: Boolean,
    busy: Boolean,
    status: String,
    onConnect: () -> Unit,
    onCommands: () -> Unit,
    onQuickOpen: () -> Unit,
    onNewScratch: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(panel)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("AVF", color = Color(0xFF4FC1FF), fontSize = 16.sp, fontFamily = FontFamily.Monospace)
        Text("Native IDE", color = foreground, fontSize = 14.sp)
        Surface(
            modifier = Modifier.weight(1f).clickable(onClick = onQuickOpen),
            color = background,
            shape = RoundedCornerShape(5.dp),
        ) {
            Text(
                "Search files  Ctrl+P",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                color = muted,
                fontSize = 12.sp,
            )
        }
        TextButtonCompact("+", "New scratch file", onNewScratch)
        TextButtonCompact("⌘", "Commands", onCommands)
        Button(
            onClick = onConnect,
            enabled = !busy,
            shape = RoundedCornerShape(5.dp),
        ) {
            Text(if (busy) "Connecting…" else if (connected) "Guest connected" else "Connect guest")
        }
    }
}

@Composable
private fun ActivityBar(selected: String, onSelect: (String) -> Unit) {
    Column(
        Modifier
            .width(48.dp)
            .fillMaxHeight()
            .background(Color(0xFF333333))
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        listOf(
            "Files" to "E",
            "Search" to "S",
            "Source Control" to "G",
            "Run" to "R",
            "Problems" to "!",
            "Outline" to "O",
            "More" to "…",
        ).forEach {
            val color = if (it.first == selected) foreground else muted
            Text(
                it.second,
                modifier = Modifier.clickable { onSelect(it.first) }.padding(vertical = 5.dp),
                color = color,
                fontSize = 17.sp,
            )
        }
    }
}

@Composable
private fun ExplorerPane(
    roots: List<String>,
    currentDirectory: String,
    entries: List<FileEntry>,
    modifier: Modifier = Modifier,
    onOpen: (String) -> Unit,
    onNavigate: (String) -> Unit,
) {
    Column(
        modifier
            .width(260.dp)
            .fillMaxHeight()
            .background(panel),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("EXPLORER", color = muted, fontSize = 11.sp)
            Spacer(Modifier.weight(1f))
            Text("↻", color = foreground, modifier = Modifier.clickable {
                if (currentDirectory.isNotEmpty()) onNavigate(currentDirectory)
            })
        }
        Text(
            currentDirectory.ifEmpty { "WORKSPACE" },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
            color = foreground,
            fontSize = 12.sp,
        )
        if (currentDirectory.isNotEmpty()) {
            Text(
                "‹ Workspace roots",
                modifier = Modifier.fillMaxWidth().clickable {
                    if (roots.isNotEmpty()) onNavigate(roots.first())
                }.padding(horizontal = 14.dp, vertical = 8.dp),
                color = muted,
                fontSize = 12.sp,
            )
        }
        if (currentDirectory.isEmpty() && roots.isEmpty()) {
            Text(
                "Connect a guest to browse its workspace.",
                modifier = Modifier.padding(14.dp),
                color = muted,
                fontSize = 12.sp,
            )
        } else if (currentDirectory.isEmpty()) {
            LazyColumn {
                items(roots) { root ->
                    ExplorerRow(root.substringAfterLast('/').ifEmpty { root }, true) { onNavigate(root) }
                }
            }
        } else {
            LazyColumn {
                items(entries, key = FileEntry::path) { entry ->
                    ExplorerRow(entry.name, entry.isDirectory) {
                        if (entry.isDirectory) onNavigate(entry.path) else onOpen(entry.path)
                    }
                }
            }
        }
    }
}

@Composable
private fun ExplorerRow(name: String, directory: Boolean, onClick: () -> Unit) {
    Text(
        (if (directory) "▸  " else "   ") + name,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp),
        color = foreground,
        fontSize = 12.sp,
        maxLines = 1,
    )
}

@Composable
private fun SideView(destination: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().background(editor).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(destination, color = foreground, fontSize = 20.sp)
        Text("This N2 workbench view is a placeholder.", color = muted, fontSize = 13.sp)
    }
}

@Composable
private fun ProblemsPane(
    diagnostics: List<LanguageDiagnostic>,
    serverIssues: List<LanguageServerIssue>,
    modifier: Modifier = Modifier,
    onSelect: (LanguageDiagnostic) -> Unit,
) {
    Column(
        modifier.fillMaxSize().background(panel),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Problems", color = foreground, fontSize = 16.sp, modifier = Modifier.padding(14.dp))
        if (diagnostics.isEmpty() && serverIssues.isEmpty()) {
            Text("No language diagnostics", color = muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 14.dp))
        } else {
            LazyColumn {
                items(serverIssues) { issue ->
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 7.dp),
                    ) {
                        Text(
                            "Language server unavailable: ${issue.server}",
                            color = foreground,
                            fontSize = 12.sp,
                        )
                        Text(issue.message, color = muted, fontSize = 10.sp)
                    }
                }
                items(diagnostics) { diagnostic ->
                    val severity = when (diagnostic.severity) {
                        1 -> "Error"
                        2 -> "Warning"
                        3 -> "Info"
                        4 -> "Hint"
                        else -> "Diagnostic"
                    }
                    Column(
                        Modifier.fillMaxWidth().clickable { onSelect(diagnostic) }
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                    ) {
                        Text("$severity: ${diagnostic.message}", color = foreground, fontSize = 12.sp)
                        Text(
                            "${diagnostic.uri.substringAfterLast('/')}" +
                                (diagnostic.source?.let { " · $it" } ?: ""),
                            color = muted,
                            fontSize = 10.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OutlinePane(
    document: DocumentSession?,
    symbols: List<OutlineSymbol>,
    modifier: Modifier = Modifier,
    onSelect: (LanguageLocation) -> Unit,
) {
    val rows = remember(symbols) { flattenOutline(symbols) }
    Column(modifier.width(260.dp).fillMaxHeight().background(panel)) {
        Text("OUTLINE", color = muted, fontSize = 11.sp, modifier = Modifier.padding(14.dp))
        when {
            document == null -> Text("Open a file to show its symbols", color = muted, fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 14.dp))
            rows.isEmpty() -> Text("No symbols found", color = muted, fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 14.dp))
            else -> LazyColumn {
                items(rows) { (symbol, depth) ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            val uri = java.net.URI("file", null, document.path, null).toASCIIString()
                            onSelect(LanguageLocation(uri, symbol.range))
                        }.padding(start = (12 + depth * 14).dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(symbolKindLabel(symbol.kind), color = Color(0xFF4FC1FF), fontSize = 10.sp)
                        Text(
                            symbol.name,
                            modifier = Modifier.padding(start = 8.dp),
                            color = foreground,
                            fontSize = 12.sp,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

private fun flattenOutline(symbols: List<OutlineSymbol>): List<Pair<OutlineSymbol, Int>> =
    buildList {
        fun append(items: List<OutlineSymbol>, depth: Int) {
            items.forEach { symbol ->
                add(symbol to depth)
                append(symbol.children, depth + 1)
            }
        }
        append(symbols, 0)
    }

private fun symbolKindLabel(kind: Int): String = when (kind) {
    5 -> "C"
    6, 9, 10, 11 -> "M"
    12 -> "ƒ"
    13, 14 -> "v"
    else -> "·"
}

@Composable
private fun ReferencesPane(
    locations: List<LanguageLocation>,
    modifier: Modifier = Modifier,
    onSelect: (LanguageLocation) -> Unit,
) {
    Column(modifier.fillMaxSize().background(panel)) {
        Text("References", color = foreground, fontSize = 16.sp, modifier = Modifier.padding(14.dp))
        if (locations.isEmpty()) {
            Text("No references found", color = muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 14.dp))
        } else {
            LazyColumn {
                items(locations) { location ->
                    val start = location.range.getAsJsonObject("start")
                    val line = start?.get("line")?.asInt?.plus(1) ?: 1
                    Text(
                        "${location.uri.substringAfterLast('/')}:$line",
                        modifier = Modifier.fillMaxWidth().clickable { onSelect(location) }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        color = foreground,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun CompletionDialog(
    choices: List<CompletionChoice>,
    onChoose: (CompletionChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Completions") },
        text = {
            LazyColumn(Modifier.height(360.dp)) {
                items(choices) { choice ->
                    Column(
                        Modifier.fillMaxWidth().clickable { onChoose(choice) }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                    ) {
                        Text(choice.label, color = foreground, fontSize = 13.sp)
                        choice.detail?.let { Text(it, color = muted, fontSize = 11.sp) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun EditorArea(
    documents: List<DocumentSession>,
    activeDocument: DocumentSession?,
    activePaged: PagedDocument?,
    status: String,
    editorHost: TextEditorHost,
    editorFontSize: Float,
    editorTabWidth: Int,
    modifier: Modifier = Modifier,
    onSelectDocument: (String) -> Unit,
    onCloseDocument: (String) -> Unit,
    onNavigateDirectory: (String) -> Unit,
    onSave: () -> Unit,
    onPage: (Int) -> Unit,
    onEditorReady: (CodeEditor) -> Unit,
    onEdit: (DocumentSession) -> Unit,
    onCommand: (String) -> Unit,
) {
    Column(modifier.fillMaxSize().background(editor)) {
        if (documents.isNotEmpty() || activePaged != null) {
            Row(
                Modifier.fillMaxWidth().height(38.dp).background(panel),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                documents.forEach { document ->
                    Surface(
                        modifier = Modifier.clickable { onSelectDocument(document.path) },
                        color = if (document.path == activeDocument?.path) editor else panel,
                    ) {
                        Row(
                            Modifier.padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                document.path.substringAfterLast('/') + if (document.dirty) " •" else "",
                                color = foreground,
                                fontSize = 12.sp,
                                maxLines = 1,
                            )
                            Text(
                                "×",
                                modifier = Modifier.clickable { onCloseDocument(document.path) },
                                color = muted,
                                fontSize = 13.sp,
                            )
                        }
                    }
                }
                activePaged?.let {
                    Text(
                        "${it.path.substringAfterLast('/')} (large file)",
                        modifier = Modifier.padding(12.dp),
                        color = foreground,
                        fontSize = 12.sp,
                    )
                }
                Spacer(Modifier.weight(1f))
                TextButtonCompact("Save", "Save", onSave)
            }
        }

        activeDocument?.takeUnless { it.path.startsWith("scratch:") }?.let { document ->
            val segments = document.path.split('/').filter(String::isNotEmpty)
            var directory = ""
            Row(
                Modifier.fillMaxWidth().height(28.dp).background(editor).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                segments.forEachIndexed { index, segment ->
                    directory += "/$segment"
                    val targetDirectory = directory
                    Text(
                        segment,
                        modifier = if (index < segments.lastIndex) {
                            Modifier.clickable { onNavigateDirectory(targetDirectory) }
                        } else {
                            Modifier
                        },
                        color = if (index == segments.lastIndex) foreground else muted,
                        fontSize = 11.sp,
                        maxLines = 1,
                    )
                    if (index < segments.lastIndex) Text("›", color = muted, fontSize = 11.sp)
                }
            }
        }

        when {
            activePaged != null -> {
                Row(
                    Modifier.fillMaxWidth().background(Color(0xFF3A2E1E)).padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Read-only large file · ${(activePaged.size / (1024 * 1024))} MiB · page ${activePaged.pageIndex + 1}/${activePaged.pageCount}",
                        color = Color(0xFFFFD28B),
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f),
                    )
                    TextButtonCompact("‹", "Previous page", { onPage(-1) }, enabled = activePaged.canGoPrevious)
                    TextButtonCompact("›", "Next page", { onPage(1) }, enabled = activePaged.canGoNext)
                }
                LargeFilePage(activePaged.text)
            }
            activeDocument != null -> {
                key(activeDocument.path) {
                    NativeEditorView(
                        activeDocument,
                        editorHost,
                        editorFontSize,
                        editorTabWidth,
                        onEditorReady,
                        onEdit,
                        onCommand,
                    )
                }
            }
            else -> EmptyEditor(status)
        }
    }
}

@Composable
private fun NativeEditorView(
    document: DocumentSession,
    editorHost: TextEditorHost,
    editorFontSize: Float,
    editorTabWidth: Int,
    onEditorReady: (CodeEditor) -> Unit,
    onEdit: (DocumentSession) -> Unit,
    onCommand: (String) -> Unit,
) {
    val editCallback by rememberUpdatedState(onEdit)
    val commandCallback by rememberUpdatedState(onCommand)
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            PythonTextMate.initialize(context)
            restoreImportedTheme(context)
            NativeCodeEditor(context) { commandCallback(it) }.apply {
                setTextSize(editorFontSize)
                setTypefaceText(android.graphics.Typeface.MONOSPACE)
                setTypefaceLineNumber(android.graphics.Typeface.MONOSPACE)
                setLineNumberEnabled(true)
                setWordwrap(false)
                setTabWidth(editorTabWidth)
                setDisableSoftKbdIfHardKbdAvailable(false)
                setColorScheme(TextMateColorScheme.create(io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry.getInstance()))
                val language = if (document.languageId == "py") {
                    TextMateLanguage.create("source.python", true)
                } else {
                    AssetPackGrammarLoader.scopeNameForExtension(document.languageId)
                        ?.let { TextMateLanguage.create(it, true) }
                        ?: editorLanguage
                }
                setEditorLanguage(language)
                props.symbolPairAutoCompletion = false
                SoraBracketColorizer.configureColors(this, loadSavedSemanticColors(context))
                val bracketColorizer = SoraBracketColorizer(this, document.languageId)
                val bracketColorizerHandler = Handler(Looper.getMainLooper())
                val updateBracketColors = Runnable { bracketColorizer.updateAsync() }
                bracketColorizerHandler.postDelayed(updateBracketColors, 250)
                setText(document.text)
                editorHost.open(document, SoraEditorSurface(this, bracketColorizer))
                startSelectionTracking()
                var previousText = document.text
                subscribeAlways(ContentChangeEvent::class.java) { event ->
                    if (event.action != ContentChangeEvent.ACTION_SET_NEW_TEXT) {
                        if (!isApplyingAutoPair && event.action == ContentChangeEvent.ACTION_INSERT) {
                            val currentText = text.toString()
                            val insertedText = event.changedText.toString()
                            val insertionOffset = event.changeStart.index
                            val pairEdit = skipExistingCloserAfterInsertion(
                                previousText,
                                currentText,
                                insertionOffset,
                                insertedText,
                            ) ?: autoPairAfterInsertion(
                                previousText,
                                currentText,
                                insertionOffset,
                                insertedText,
                                selectionBeforeChange,
                            )
                            pairEdit?.let(::applyAutoPairReplacement)
                        } else if (!isApplyingAutoPair && event.action == ContentChangeEvent.ACTION_DELETE) {
                            pairedCloserOffsetAfterDeletion(
                                previousText,
                                text.toString(),
                                event.changeStart.index,
                                event.changedText.toString(),
                            )?.let(::deletePairedCloser)
                        }
                        document.updateText(text.toString())
                        editCallback(document)
                        previousText = text.toString()
                        bracketColorizerHandler.removeCallbacks(updateBracketColors)
                        bracketColorizerHandler.postDelayed(updateBracketColors, 250)
                    }
                }
                onEditorReady(this)
            }
        },
        update = { view ->
            if (view.text.toString() != document.text) view.setText(document.text)
            view.setTextSize(editorFontSize)
            view.setTabWidth(editorTabWidth)
            view.setEditable(true)
            onEditorReady(view)
        },
    )
    DisposableEffect(editorHost, document.path) {
        onDispose { editorHost.close(document.path) }
    }
}

private class NativeCodeEditor(
    context: Context,
    private val onCommand: (String) -> Unit,
) : CodeEditor(context) {
    private val dispatcher = EditorKeybindingDispatcher()
    var selectionBeforeChange: IntRange = 0 until 0
        private set
    var isApplyingAutoPair: Boolean = false
        private set

    fun startSelectionTracking() {
        fun captureSelection() {
            selectionBeforeChange = cursorRange.startIndex until cursorRange.endIndex
        }
        captureSelection()
        subscribeAlways(SelectionChangeEvent::class.java) { captureSelection() }
    }

    fun deletePairedCloser(offset: Int) {
        val position = text.indexer.getCharPosition(offset)
        isApplyingAutoPair = true
        try {
            text.delete(position.line, position.column, position.line, position.column + 1)
        } finally {
            isApplyingAutoPair = false
        }
    }

    fun applyAutoPairReplacement(replacement: AutoPairReplacement) {
        replacement.deleteOffset?.let { offset ->
            val deletionPosition = text.indexer.getCharPosition(offset)
            text.delete(
                deletionPosition.line,
                deletionPosition.column,
                deletionPosition.line,
                deletionPosition.column + 1,
            )
        }
        if (replacement.insertion.isNotEmpty()) {
            val insertionPosition = text.indexer.getCharPosition(replacement.insertionOffset)
            isApplyingAutoPair = true
            try {
                text.beginBatchEdit()
                try {
                    text.insert(insertionPosition.line, insertionPosition.column, replacement.insertion)
                } finally {
                    text.endBatchEdit()
                }
            } finally {
                isApplyingAutoPair = false
            }
        }
        val selectionStart = text.indexer.getCharPosition(replacement.selectionStart)
        val selectionEnd = text.indexer.getCharPosition(replacement.selectionEnd)
        setSelectionRegion(
            selectionStart.line,
            selectionStart.column,
            selectionEnd.line,
            selectionEnd.column,
        )
    }

    override fun dispatchKeyEvent(event: AndroidKeyEvent): Boolean {
        if (event.action == AndroidKeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            event.asEditorStroke(dispatcher.isAwaitingChord)?.let { stroke ->
                val command = dispatcher.dispatch(stroke)
                if (command != null) {
                    onCommand(command)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }
}

private fun AndroidKeyEvent.asEditorStroke(allowUnmodified: Boolean): KeyStroke? {
    return editorKeyStroke(keyCode, isCtrlPressed, isShiftPressed, isAltPressed, allowUnmodified)
}

internal fun editorKeyStroke(
    keyCode: Int,
    ctrl: Boolean,
    shift: Boolean,
    alt: Boolean,
    allowUnmodified: Boolean = false,
): KeyStroke? {
    val key = when (keyCode) {
        AndroidKeyEvent.KEYCODE_DPAD_UP -> "UP"
        AndroidKeyEvent.KEYCODE_DPAD_DOWN -> "DOWN"
        AndroidKeyEvent.KEYCODE_DPAD_LEFT -> "LEFT"
        AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> "RIGHT"
        AndroidKeyEvent.KEYCODE_TAB -> "TAB"
        AndroidKeyEvent.KEYCODE_ENTER -> "ENTER"
        AndroidKeyEvent.KEYCODE_SLASH -> "/"
        AndroidKeyEvent.KEYCODE_BACKSLASH -> "\\"
        AndroidKeyEvent.KEYCODE_LEFT_BRACKET -> "["
        AndroidKeyEvent.KEYCODE_RIGHT_BRACKET -> "]"
        AndroidKeyEvent.KEYCODE_0 -> "0"
        AndroidKeyEvent.KEYCODE_W -> "W"
        AndroidKeyEvent.KEYCODE_J -> "J"
        AndroidKeyEvent.KEYCODE_K -> "K"
        AndroidKeyEvent.KEYCODE_S -> "S"
        AndroidKeyEvent.KEYCODE_O -> "O"
        AndroidKeyEvent.KEYCODE_P -> "P"
        AndroidKeyEvent.KEYCODE_N -> "N"
        AndroidKeyEvent.KEYCODE_Z -> "Z"
        AndroidKeyEvent.KEYCODE_X -> "X"
        AndroidKeyEvent.KEYCODE_C -> "C"
        AndroidKeyEvent.KEYCODE_V -> "V"
        AndroidKeyEvent.KEYCODE_A -> "A"
        AndroidKeyEvent.KEYCODE_F -> "F"
        AndroidKeyEvent.KEYCODE_H -> "H"
        AndroidKeyEvent.KEYCODE_G -> "G"
        AndroidKeyEvent.KEYCODE_D -> "D"
        AndroidKeyEvent.KEYCODE_L -> "L"
        AndroidKeyEvent.KEYCODE_T -> "T"
        AndroidKeyEvent.KEYCODE_U -> "U"
        AndroidKeyEvent.KEYCODE_I -> "I"
        AndroidKeyEvent.KEYCODE_SPACE -> "SPACE"
        AndroidKeyEvent.KEYCODE_F2 -> "F2"
        AndroidKeyEvent.KEYCODE_F3 -> "F3"
        AndroidKeyEvent.KEYCODE_F8 -> "F8"
        AndroidKeyEvent.KEYCODE_F9 -> "F9"
        AndroidKeyEvent.KEYCODE_F12 -> "F12"
        else -> return null
    }
    if (!ctrl && !alt && !shift && !allowUnmodified &&
        key !in setOf("TAB", "F2", "F3", "F8", "F9", "F12")
    ) {
        return null
    }
    return KeyStroke(key, ctrl, shift, alt)
}

@Composable
private fun LargeFilePage(text: String) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            PythonTextMate.initialize(context)
            CodeEditor(context).apply {
                setTypefaceText(Typeface.MONOSPACE)
                setTextSize(13f)
                setLineNumberEnabled(true)
                setWordwrap(false)
                setEditable(false)
                setColorScheme(
                    TextMateColorScheme.create(
                        io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry.getInstance(),
                    ),
                )
                setText(text)
            }
        },
        update = { view ->
            if (view.text.toString() != text) view.setText(text)
            view.setEditable(false)
        },
    )
}

@Composable
private fun EmptyEditor(status: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Native Android IDE", color = foreground, fontSize = 22.sp)
        Spacer(Modifier.height(8.dp))
        Text("Connect to the guest, then open a file from Explorer or Quick Open.", color = muted, fontSize = 13.sp)
        Spacer(Modifier.height(16.dp))
        Text(status, color = Color(0xFF4FC1FF), fontSize = 12.sp)
    }
}

@Composable
private fun CompactNavigation(selected: String, onSelect: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(panel),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf("Files", "Editor", "Problems", "Outline", "Terminal", "Source Control", "More").forEach { destination ->
            Text(
                destination,
                modifier = Modifier.clickable { onSelect(destination) }.padding(horizontal = 7.dp, vertical = 12.dp),
                color = if (destination == selected) foreground else muted,
                fontSize = 11.sp,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun StatusBar(
    status: String,
    document: DocumentSession?,
    paged: PagedDocument?,
    onSave: () -> Unit,
    onEncodingClick: () -> Unit,
    onEolClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(24.dp)
            .background(Color(0xFF007ACC))
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(status, modifier = Modifier.weight(1f), color = Color.White, fontSize = 10.sp, maxLines = 1)
        Text(
            document?.let { if (it.dirty) "● Modified" else "Saved" }
                ?: paged?.let { "Read-only · ${it.pageIndex + 1}/${it.pageCount}" }
                ?: "UTF-8",
            modifier = Modifier.clickable(onClick = onSave),
            color = Color.White,
            fontSize = 10.sp,
        )
        if (document != null) {
            TextButtonCompact(document.encoding, "Change file encoding", onEncodingClick)
            TextButtonCompact(if (document.eol == "\r\n") "CRLF" else "LF", "Change line ending", onEolClick)
            Text(document.languageId.ifEmpty { "Plain Text" }, color = Color.White, fontSize = 10.sp)
        }
    }
}

private fun editCurrentLine(
    document: DocumentSession,
    editor: CodeEditor,
    commandId: String,
): Boolean {
    val lines = document.text.split('\n').toMutableList()
    val row = editor.cursor.leftLine.coerceIn(lines.indices)
    val column = editor.cursor.leftColumn
    var targetRow = row
    when (commandId) {
        "editor.action.moveLinesUpAction" -> {
            if (row == 0) return false
            lines.add(row - 1, lines.removeAt(row))
            targetRow = row - 1
        }
        "editor.action.moveLinesDownAction" -> {
            if (row == lines.lastIndex) return false
            lines.add(row + 1, lines.removeAt(row))
            targetRow = row + 1
        }
        "editor.action.copyLinesUpAction" -> lines.add(row, lines[row])
        "editor.action.copyLinesDownAction" -> {
            lines.add(row + 1, lines[row])
            targetRow = row + 1
        }
        "editor.action.deleteLines" -> {
            lines.removeAt(row)
            if (lines.isEmpty()) lines.add("")
            targetRow = row.coerceAtMost(lines.lastIndex)
        }
        "editor.action.insertLineAfter" -> {
            lines.add(row + 1, "")
            targetRow = row + 1
        }
        "editor.action.insertLineBefore" -> {
            lines.add(row, "")
            targetRow = row
        }
        else -> return false
    }
    val updated = lines.joinToString("\n")
    document.updateText(updated)
    editor.setText(updated)
    editor.setSelection(targetRow, column.coerceAtMost(lines[targetRow].length))
    return true
}

private fun jumpToMatchingBracket(document: DocumentSession, editor: CodeEditor): Boolean {
    val text = document.text
    val cursor = editor.cursor.right.coerceIn(0, text.length)
    val pairs = mapOf('(' to ')', '[' to ']', '{' to '}')
    val bracketIndex = when {
        cursor < text.length && (text[cursor] in pairs || text[cursor] in pairs.values) -> cursor
        cursor > 0 && (text[cursor - 1] in pairs || text[cursor - 1] in pairs.values) -> cursor - 1
        else -> return false
    }
    val bracket = text[bracketIndex]
    val opening = bracket in pairs
    val openChar = if (opening) bracket else pairs.entries.first { it.value == bracket }.key
    val closeChar = pairs.getValue(openChar)
    val step = if (opening) 1 else -1
    var depth = 0
    var index = bracketIndex
    while (index in text.indices) {
        if (opening) {
            when (text[index]) {
                openChar -> depth++
                closeChar -> depth--
            }
        } else {
            when (text[index]) {
                closeChar -> depth++
                openChar -> depth--
            }
        }
        if (depth == 0) {
            val prefix = text.substring(0, index)
            val line = prefix.count { it == '\n' }
            val column = prefix.length - (prefix.lastIndexOf('\n') + 1)
            editor.setSelection(line, column)
            editor.ensureSelectionVisible()
            return true
        }
        index += step
    }
    return false
}

@Composable
private fun ChoiceDialog(
    title: String,
    selected: String,
    options: List<String>,
    onSelected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { option ->
                    Text(
                        (if (option == selected) "●  " else "○  ") + option,
                        modifier = Modifier.fillMaxWidth().clickable { onSelected(option) }.padding(vertical = 10.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun ConnectDialog(
    endpoint: String,
    token: String,
    profiles: List<ConnectionProfile>,
    profileId: String?,
    profileName: String,
    onProfileSelected: (ConnectionProfile) -> Unit,
    onNewProfile: () -> Unit,
    onProfileDeleted: (String) -> Unit,
    onProfileNameChange: (String) -> Unit,
    onEndpointChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect to AVF guest") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Profiles are saved on this device; session tokens are encrypted with the Android Keystore.",
                    fontSize = 12.sp,
                )
                LazyColumn(Modifier.heightIn(max = 140.dp)) {
                    items(profiles) { profile ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onProfileSelected(profile) }.padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    (if (profile.id == profileId) "● " else "") + profile.name,
                                    fontSize = 13.sp,
                                )
                                Text(profile.endpoint, color = muted, fontSize = 11.sp)
                            }
                            TextButton(onClick = { onProfileDeleted(profile.id) }) { Text("Delete") }
                        }
                    }
                }
                if (profiles.isNotEmpty()) TextButton(onClick = onNewProfile) { Text("New profile") }
                OutlinedTextField(
                    value = profileName,
                    onValueChange = onProfileNameChange,
                    label = { Text("Profile name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = endpoint,
                    onValueChange = onEndpointChange,
                    label = { Text("WebSocket URL") },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("ws://127.0.0.1:19860/ws") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = onTokenChange,
                    label = { Text("Session token") },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
            }
        },
        confirmButton = { TextButton(onClick = onConnect) { Text("Connect") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CommandDialog(
    filter: String,
    onFilter: (String) -> Unit,
    onCommand: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Command Palette") },
        text = {
            Column {
                OutlinedTextField(value = filter, onValueChange = onFilter, placeholder = { Text("Type a command") })
                LazyColumn(Modifier.height(360.dp)) {
                    items(
                        (EditorCommands.top50 + EditorCommands.language).filter {
                            it.title.contains(filter, ignoreCase = true) || it.id.contains(filter, ignoreCase = true)
                        },
                    ) { command ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onCommand(command.id) }.padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(command.title, fontSize = 13.sp)
                            Text(command.keybinding, color = muted, fontSize = 11.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun QuickOpenDialog(
    query: String,
    entries: List<FileEntry>,
    onQuery: (String) -> Unit,
    onOpen: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Quick Open") },
        text = {
            Column {
                OutlinedTextField(value = query, onValueChange = onQuery, placeholder = { Text("Search files by name") })
                LazyColumn(Modifier.height(320.dp)) {
                    items(
                        entries.filter {
                            query.isBlank() || it.name.contains(query, ignoreCase = true) ||
                                it.path.contains(query, ignoreCase = true)
                        },
                        key = FileEntry::path,
                    ) { entry ->
                        Text(
                            entry.path,
                            modifier = Modifier.fillMaxWidth().clickable { onOpen(entry.path) }.padding(vertical = 8.dp),
                            fontSize = 12.sp,
                            maxLines = 1,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun FindDialog(
    query: String,
    replacement: String,
    onQuery: (String) -> Unit,
    onReplacement: (String) -> Unit,
    onFindNext: () -> Unit,
    onReplaceAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Find and Replace") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = query, onValueChange = onQuery, label = { Text("Find") }, singleLine = true)
                OutlinedTextField(
                    value = replacement,
                    onValueChange = onReplacement,
                    label = { Text("Replace") },
                    singleLine = true,
                )
            }
        },
        confirmButton = { TextButton(onClick = onFindNext) { Text("Find next") } },
        dismissButton = {
            Row {
                TextButton(onClick = onReplaceAll) { Text("Replace all") }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
}

@Composable
private fun GotoLineDialog(
    value: String,
    onValueChange: (String) -> Unit,
    onGo: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Go to Line") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text("Line number") },
                singleLine = true,
            )
        },
        confirmButton = { TextButton(onClick = onGo) { Text("Go") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TextButtonCompact(label: String, description: String, onClick: () -> Unit, enabled: Boolean = true) {
    Text(
        label,
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
        color = if (enabled) foreground else muted,
        fontSize = 12.sp,
        maxLines = 1,
    )
}

private object Theme {
    var background by mutableStateOf(Color(0xFF1E1E1E))
        private set
    var panel by mutableStateOf(Color(0xFF252526))
        private set
    var editor by mutableStateOf(Color(0xFF1E1E1E))
        private set
    var foreground by mutableStateOf(Color(0xFFD4D4D4))
        private set
    var muted by mutableStateOf(Color(0xFF858585))
        private set
    var border by mutableStateOf(Color(0xFF3C3C3C))
        private set
    var selection by mutableStateOf(Color(0xFF264F78))
        private set

    fun applyWorkbenchColors(colors: Map<String, String>) {
        colors["editor.background"]?.let { background = parseThemeColor(it); editor = parseThemeColor(it) }
        colors["sideBar.background"]?.let { panel = parseThemeColor(it) }
        colors["editor.foreground"]?.let { foreground = parseThemeColor(it) }
        colors["editorLineNumber.foreground"]?.let { muted = parseThemeColor(it) }
        (colors["editorGroup.border"] ?: colors["contrastBorder"])?.let { border = parseThemeColor(it) }
        colors["editor.selectionBackground"]?.let { selection = parseThemeColor(it) }
    }
}

private fun saveImportedTheme(context: Context, theme: ImportedTheme) {
    val workbenchColors = JsonObject().apply {
        theme.workbenchColors.forEach { (key, value) -> addProperty(key, value) }
    }
    val semanticColors = JsonObject().apply {
        theme.semanticTokenColors.forEach { (key, value) -> addProperty(key, value) }
    }
    context.getSharedPreferences("native-ide-assets", Context.MODE_PRIVATE).edit()
        .putString("theme-json", theme.textMateThemeJson)
        .putString("workbench-colors", workbenchColors.toString())
        .putString("semantic-colors", semanticColors.toString())
        .apply()
}

private fun loadSavedSemanticColors(context: Context): Map<String, String> {
    val json = context.getSharedPreferences("native-ide-assets", Context.MODE_PRIVATE)
        .getString("semantic-colors", "{}") ?: "{}"
    return JsonParser.parseString(json).asJsonObject.entrySet().mapNotNull { (name, value) ->
        if (value.isJsonPrimitive && value.asJsonPrimitive.isString) name to value.asString else null
    }.toMap()
}

private fun restoreImportedTheme(context: Context) {
    val json = context.getSharedPreferences("native-ide-assets", Context.MODE_PRIVATE)
        .getString("theme-json", null) ?: return
    loadTextMateTheme(json)
}

private fun loadTextMateTheme(json: String) {
    val source = IThemeSource.fromInputStream(
        ByteArrayInputStream(json.toByteArray(Charsets.UTF_8)),
        "imported-theme.tmTheme.json",
        Charsets.UTF_8,
    )
    ThemeRegistry.getInstance().loadTheme(source)
}

private fun parseThemeColor(value: String): Color {
    val normalized = when {
        value.matches(Regex("#[0-9a-fA-F]{8}")) ->
            "#${value.substring(7, 9)}${value.substring(1, 7)}"
        value.matches(Regex("#[0-9a-fA-F]{4}")) ->
            "#${value[4]}${value[1]}${value[2]}${value[3]}"
        else -> value
    }
    val rgb = Regex("""rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)(?:\s*,\s*([\d.]+))?\s*\)""")
        .matchEntire(normalized)
    val color = if (rgb != null) {
        val red = rgb.groupValues[1].toInt().coerceIn(0, 255)
        val green = rgb.groupValues[2].toInt().coerceIn(0, 255)
        val blue = rgb.groupValues[3].toInt().coerceIn(0, 255)
        val alpha = rgb.groupValues[4].takeIf(String::isNotEmpty)?.toFloatOrNull()
            ?.let { (it.coerceIn(0f, 1f) * 255).toInt() } ?: 255
        android.graphics.Color.argb(alpha, red, green, blue)
    } else {
        android.graphics.Color.parseColor(normalized)
    }
    return Color(color)
}

private fun JsonObject.toLspRange(): LspRange? {
    val start = getAsJsonObject("start") ?: return null
    val end = getAsJsonObject("end") ?: return null
    return LspRange(
        LspPosition(start.get("line").asInt, start.get("character").asInt),
        LspPosition(end.get("line").asInt, end.get("character").asInt),
    )
}

private fun JsonElement.toLocations(): List<LanguageLocation> {
    val values = when {
        isJsonArray -> asJsonArray.toList()
        isJsonObject -> listOf(this)
        else -> emptyList()
    }
    return values.mapNotNull { value ->
        if (!value.isJsonObject) return@mapNotNull null
        val location = value.asJsonObject
        val uri = location.get("uri")?.takeUnless { it.isJsonNull }?.asString
            ?: location.get("targetUri")?.takeUnless { it.isJsonNull }?.asString
            ?: return@mapNotNull null
        val range = location.getAsJsonObject("range")
            ?: location.getAsJsonObject("targetSelectionRange")
            ?: location.getAsJsonObject("targetRange")
            ?: return@mapNotNull null
        LanguageLocation(uri, range)
    }
}

private fun JsonElement.toOutlineSymbols(): List<OutlineSymbol> =
    if (!isJsonArray) emptyList() else asJsonArray.mapNotNull { value ->
        if (!value.isJsonObject) return@mapNotNull null
        val symbol = value.asJsonObject
        val name = symbol.get("name")?.takeUnless { it.isJsonNull }?.asString ?: return@mapNotNull null
        val range = symbol.getAsJsonObject("range")
            ?: symbol.getAsJsonObject("location")?.getAsJsonObject("range")
            ?: return@mapNotNull null
        OutlineSymbol(
            name = name,
            kind = symbol.get("kind")?.takeUnless { it.isJsonNull }?.asInt ?: 0,
            range = range,
            children = symbol.getAsJsonArray("children")?.toOutlineSymbols().orEmpty(),
        )
    }

private fun JsonElement.workspaceTextEdits(): Map<String, List<LspTextEdit>> {
    if (!isJsonObject) return emptyMap()
    val result = linkedMapOf<String, MutableList<LspTextEdit>>()
    val edit = asJsonObject
    edit.getAsJsonObject("changes")?.entrySet()?.forEach { (uri, values) ->
        result.getOrPut(java.net.URI(uri).path) { mutableListOf() }.addAll(values.textEdits())
    }
    edit.getAsJsonArray("documentChanges")?.forEach { change ->
        val document = change.asJsonObject.getAsJsonObject("textDocument") ?: return@forEach
        val uri = document.get("uri")?.asString ?: return@forEach
        val values = change.asJsonObject.getAsJsonArray("edits") ?: return@forEach
        result.getOrPut(java.net.URI(uri).path) { mutableListOf() }.addAll(values.textEdits())
    }
    return result
}

private fun isPathWithinRoot(path: String, root: String): Boolean {
    val normalizedPath = normalizeGuestPath(path) ?: return false
    val normalizedRoot = normalizeGuestPath(root) ?: return false
    return normalizedPath == normalizedRoot || normalizedPath.startsWith("$normalizedRoot/")
}

private fun normalizeGuestPath(path: String): String? {
    if (!path.startsWith('/')) return null
    val segments = mutableListOf<String>()
    path.split('/').forEach { segment ->
        when (segment) {
            "", "." -> Unit
            ".." -> if (segments.isEmpty()) return null else segments.removeAt(segments.lastIndex)
            else -> segments.add(segment)
        }
    }
    return "/" + segments.joinToString("/")
}

private fun JsonElement.textEdits(): List<LspTextEdit> =
    if (!isJsonArray) emptyList() else asJsonArray.mapNotNull { value ->
        if (!value.isJsonObject) return@mapNotNull null
        val item = value.asJsonObject
        val range = item.getAsJsonObject("range")?.toLspRange() ?: return@mapNotNull null
        val newText = item.get("newText")?.takeUnless { it.isJsonNull }?.asString ?: return@mapNotNull null
        LspTextEdit(range, newText)
    }

private fun hoverContents(value: JsonElement): String? = when {
    value.isJsonPrimitive -> value.asString
    value.isJsonObject -> value.asJsonObject.get("value")?.asString
        ?: value.asJsonObject.get("language")?.asString?.let { language ->
            value.asJsonObject.get("value")?.asString?.let { "```$language\n$it\n```" }
        }
    value.isJsonArray -> value.asJsonArray.mapNotNull(::hoverContents).joinToString("\n\n").ifEmpty { null }
    else -> null
}

private fun completionWordRange(text: String, position: LspPosition): LspRange {
    val line = text.lines().getOrNull(position.line).orEmpty()
    val cursor = position.character.coerceIn(0, line.length)
    var start = cursor
    while (start > 0 && isIdentifierCharacter(line[start - 1])) start--
    var end = cursor
    while (end < line.length && isIdentifierCharacter(line[end])) end++
    return LspRange(LspPosition(position.line, start), LspPosition(position.line, end))
}

private fun wordAtPosition(text: String, position: LspPosition): String {
    val line = text.lines().getOrNull(position.line).orEmpty()
    val range = completionWordRange(text, position)
    return line.substring(range.start.character, range.end.character)
}

private fun isIdentifierCharacter(char: Char): Boolean = char == '_' || char.isLetterOrDigit()
