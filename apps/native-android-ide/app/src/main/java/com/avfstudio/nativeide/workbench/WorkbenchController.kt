package com.avfstudio.nativeide.workbench

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.avfstudio.nativeide.GuestConnectionService
import com.avfstudio.nativeide.ai.AcpAuthMethod
import com.avfstudio.nativeide.ai.AcpClient
import com.avfstudio.nativeide.ai.AcpEvent
import com.avfstudio.nativeide.ai.ChatReference
import com.avfstudio.nativeide.ai.COPILOT_LOGIN_COMMAND
import com.avfstudio.nativeide.ai.parseDeviceCodePrompt
import com.avfstudio.nativeide.assets.AssetPackGrammarLoader
import com.avfstudio.nativeide.assets.AssetPackInstaller
import com.avfstudio.nativeide.assets.ImportedTheme
import com.avfstudio.nativeide.assets.InstalledAssetPack
import com.avfstudio.nativeide.assets.VsCodeThemeImporter
import com.avfstudio.nativeide.document.DocumentRepository
import com.avfstudio.nativeide.document.DocumentSession
import com.avfstudio.nativeide.document.EncryptedDocumentCache
import com.avfstudio.nativeide.document.OpenDocument
import com.avfstudio.nativeide.document.PagedDocument
import com.avfstudio.nativeide.document.SaveResult
import com.avfstudio.nativeide.editor.EditorEvent
import com.avfstudio.nativeide.editor.LspPosition
import com.avfstudio.nativeide.editor.LspRange
import com.avfstudio.nativeide.editor.LspTextEdit
import com.avfstudio.nativeide.editor.SoraTextEditorHost
import com.avfstudio.nativeide.editor.applyLspTextEdits
import com.avfstudio.nativeide.language.CopilotClient
import com.avfstudio.nativeide.language.CopilotSuggestion
import com.avfstudio.nativeide.language.JsonRpcException
import com.avfstudio.nativeide.language.LanguageClient
import com.avfstudio.nativeide.language.LanguageDiagnostic
import com.avfstudio.nativeide.language.SettingsResolver
import com.avfstudio.nativeide.notebook.CellType
import com.avfstudio.nativeide.notebook.KernelClient
import com.avfstudio.nativeide.notebook.generatedKernelName
import com.avfstudio.nativeide.notebook.NotebookCell
import com.avfstudio.nativeide.notebook.NotebookDocument
import com.avfstudio.nativeide.transport.ConnectionProfileStore
import com.avfstudio.nativeide.transport.FileEntry
import com.avfstudio.nativeide.transport.GuestVfsClient
import com.avfstudio.nativeide.transport.GuestVfsException
import com.avfstudio.nativeide.transport.KeystoreSecretCipher
import com.avfstudio.nativeide.transport.PtyEvent
import com.avfstudio.nativeide.transport.SharedPreferencesKeyValueStore
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.rosemoe.sora.widget.CodeEditor
import org.connectbot.terminal.TerminalEmulatorFactory
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

private const val SCRATCH_WELCOME_PATH = "scratch:/welcome.py"
private const val MAX_IMPORTED_THEME_BYTES = 8 * 1024 * 1024
private const val MAX_OUTPUT_LINES = 500
private const val MAX_QUICK_OPEN_FILES = 5000
private val skippedDirectories = setOf(
    ".git", "node_modules", "__pycache__", ".venv", "venv", ".mypy_cache", ".ruff_cache", ".pytest_cache",
    ".ipynb_checkpoints", "dist", "build", "target", ".gradle", ".idea", ".tox",
)

/**
 * All workbench state and behaviour. Composables only read this state and call its methods,
 * so callbacks never capture a stale composition and the UI functions stay small.
 * Everything here runs on the main thread; background completions re-enter through [post].
 */
class WorkbenchController(private val context: Context) {
    val vfs = GuestVfsClient()
    val languageClients = mapOf(
        "python" to LanguageClient(vfs),
        "python-format" to LanguageClient(vfs),
        "typescript" to LanguageClient(vfs),
    )
    private val appVersion =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "0"
    val copilot = CopilotClient(vfs, appVersion)
    val chatAgent = AcpClient(vfs)
    private val defaultSettings =
        JsonParser.parseString("""{"editor":{"fontSize":14,"tabSize":4,"insertSpaces":true}}""").asJsonObject
    private val preferences = context.getSharedPreferences("native-ide-settings", Context.MODE_PRIVATE)
    private val cache = EncryptedDocumentCache(context)
    val repository = DocumentRepository(vfs, cache)
    val editorHost = SoraTextEditorHost()
    private val io = Executors.newSingleThreadExecutor()
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val profileStore = ConnectionProfileStore(SharedPreferencesKeyValueStore(context), KeystoreSecretCipher())
    private val editorDispatcher = EditorKeybindingDispatcher(EditorCommands.all)
    private val globalDispatcher = EditorKeybindingDispatcher(EditorCommands.global)
    private val notebookDispatcher = EditorKeybindingDispatcher(EditorCommands.global + EditorCommands.notebook)
    private val consumedKeyDowns = mutableSetOf<Int>()
    private var lastHandledKey: Pair<Long, Int>? = null
    private val scheduledDraftWrites = mutableMapOf<String, ScheduledFuture<*>>()
    private val scheduledOutlineRefreshes = mutableMapOf<String, ScheduledFuture<*>>()
    private var scheduledInlineRequest: ScheduledFuture<*>? = null
    private var scheduledServerStop: ScheduledFuture<*>? = null
    private var scheduledChatStop: ScheduledFuture<*>? = null
    private var scheduledAutoSave: ScheduledFuture<*>? = null
    private val diagnosticsByClient = mutableMapOf<LanguageClient, MutableMap<String, List<LanguageDiagnostic>>>()

    // Status and output log -------------------------------------------------------------------
    private var statusText by mutableStateOf("Guest disconnected")
    var status: String
        get() = statusText
        set(value) {
            statusText = value
            outputLog.add(value)
            while (outputLog.size > MAX_OUTPUT_LINES) outputLog.removeAt(0)
        }
    val outputLog = mutableStateListOf<String>()

    // Connection --------------------------------------------------------------------------------
    var connected by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    private var keepAliveError: String? = null
    var endpoint by mutableStateOf("")
    var token by mutableStateOf("")
    var profiles by mutableStateOf(profileStore.profiles())
        private set
    var profileId by mutableStateOf<String?>(null)
    var profileName by mutableStateOf("")
    /** Set by the UI: asks Android for notification permission, then calls [connectGuest]. */
    var requestNotificationPermission: (() -> Unit)? = null

    // Files and documents ---------------------------------------------------------------------
    var roots by mutableStateOf(emptyList<String>())
        private set
    var workspaceRoot by mutableStateOf<String?>(null)
        private set
    var currentDirectory by mutableStateOf("")
        private set
    var entries by mutableStateOf(emptyList<FileEntry>())
        private set
    val documents = mutableStateListOf<DocumentSession>()
    private val closedDocuments = mutableListOf<DocumentSession>()
    var activePath by mutableStateOf<String?>(null)
    var pagedDocument by mutableStateOf<PagedDocument?>(null)
        private set
    /** Bumped on edits so dirty markers recompose; DocumentSession itself is not observable. */
    var documentRevision by mutableIntStateOf(0)
        private set
    val notebooks = mutableMapOf<String, NotebookSession>()
    val activeDocument: DocumentSession? get() = documents.firstOrNull { it.path == activePath }
    val activeNotebook: NotebookSession? get() = activePath?.let(notebooks::get)

    var quickOpenIndex by mutableStateOf(emptyList<FileEntry>())
        private set
    var quickOpenIndexing by mutableStateOf(false)
        private set

    // Language intelligence ---------------------------------------------------------------------
    val diagnostics = mutableStateListOf<LanguageDiagnostic>()
    var languageServerIssues by mutableStateOf(emptyList<LanguageServerIssue>())
        private set
    var outlineSymbols by mutableStateOf(emptyList<OutlineSymbol>())
        private set
    var referenceLocations by mutableStateOf(emptyList<LanguageLocation>())
        private set
    var editorView by mutableStateOf<CodeEditor?>(null)
        private set
    private var pendingNavigation: EditorNavigationTarget? = null
    var inlineSuggestion by mutableStateOf<Pair<String, CopilotSuggestion>?>(null)
        private set
    var cursorPosition by mutableStateOf<LspPosition?>(null)
    private val cursorHistory = ArrayDeque<Pair<String, LspPosition?>>()
    private var restoringCursor = false
        private set
    /** Bumped when the editor scrolls so the inline suggestion follows the cursor. */
    var editorViewportTick by mutableIntStateOf(0)
        private set

    // Settings --------------------------------------------------------------------------------
    var userSettings by mutableStateOf(
        runCatching { JsonParser.parseString(preferences.getString("user-settings", "{}")).asJsonObject }
            .getOrDefault(JsonObject()),
    )
        private set
    private var workspaceSettings by mutableStateOf(JsonObject())
    val settings by derivedStateOf {
        WorkbenchSettings.from(SettingsResolver.merge(defaultSettings, userSettings, workspaceSettings))
    }
    var copilotEnabled by mutableStateOf(preferences.getBoolean("copilot-enabled", false))
        private set
    var copilotStatus by mutableStateOf(if (copilotEnabled) "Disconnected" else "Off")
        private set
    /** Last `didChangeStatus` kind; anything but Normal/InProgress means the user has to act. */
    private var copilotStatusKind by mutableStateOf<String?>(null)

    // Layout ----------------------------------------------------------------------------------
    var sidebar by mutableStateOf<SidebarView?>(SidebarView.Explorer)
    var panelVisible by mutableStateOf(false)
    var panelTab by mutableStateOf(PanelTab.Terminal)
    var chatVisible by mutableStateOf(false)
    var settingsVisible by mutableStateOf(false)
    var compactDestination by mutableStateOf(CompactDestination.Editor)
    var focusArea by mutableStateOf(FocusArea.Other)

    // Dialogs ---------------------------------------------------------------------------------
    var dialog by mutableStateOf<WorkbenchDialog?>(null)
    var commandFilter by mutableStateOf("")
    var quickOpenFilter by mutableStateOf("")
    var findQuery by mutableStateOf("")
    var replaceQuery by mutableStateOf("")
    var gotoLine by mutableStateOf("")
    var renameValue by mutableStateOf("")
    var settingsDraft by mutableStateOf("")
    /** Set by the UI to open the system file picker for a VSIX ("vsix") or theme JSON ("theme"). */
    var pickAssetPack: ((String) -> Unit)? = null

    // Chat ------------------------------------------------------------------------------------
    val chatEntries = mutableStateListOf<ChatEntry>()
    var chatBusy by mutableStateOf(false)
        private set
    var chatAuthMethods by mutableStateOf(emptyList<AcpAuthMethod>())
    /** Files and excerpts attached to the next chat message. */
    val chatAttachments = mutableStateListOf<ChatReference>()
    /** When set, Quick Open attaches the picked file to chat instead of opening it. */
    var quickOpenAttachesToChat by mutableStateOf(false)
    private var chatLoginTerminal: UUID? = null
        private set

    // Terminals -------------------------------------------------------------------------------
    val terminals = mutableStateListOf<TerminalSession>()
    var activeTerminalId by mutableStateOf<UUID?>(null)
    val activeTerminal: TerminalSession? get() = terminals.firstOrNull { it.id == activeTerminalId }

    private val editorSubscription = editorHost.observe(::onEditorEvent)

    init {
        val welcome = cache.load(SCRATCH_WELCOME_PATH)?.let {
            DocumentSession(it.path, it.text, it.modifiedTs, it.dirty, encoding = it.encoding, eol = it.eol)
        } ?: DocumentSession(
            SCRATCH_WELCOME_PATH,
            "# Welcome to AVF Native IDE\n# Edit this scratch file offline, or connect to a guest workspace.\n\n" +
                "def greet(name: str) -> str:\n    return f\"Hello, {name}!\"\n",
            baseModifiedTs = null,
            dirty = false,
        )
        documents.add(welcome)
        activePath = welcome.path
        profiles.firstOrNull { it.id == profileStore.lastUsedId() }?.let(::selectProfile)
        preferences.getString("grammar-load-errors", null)
            ?.takeIf(String::isNotBlank)?.let { status = "Asset-pack grammar load failed: $it" }
        bindListeners()
    }

    fun post(action: () -> Unit) {
        mainHandler.post(action)
    }

    private fun bindListeners() {
        vfs.connectionStateListener = { isConnected, failure ->
            post {
                connected = isConnected
                if (!isConnected) {
                    languageClients.values.forEach(LanguageClient::onDisconnected)
                    copilot.language.onDisconnected()
                    chatAgent.onDisconnected()
                    notebooks.values.forEach { it.kernel.onDisconnected(); it.kernelState = "Disconnected" }
                    inlineSuggestion = null
                    chatBusy = false
                    copilotStatus = if (copilotEnabled) "Disconnected" else "Off"
                }
                if (!isConnected && !busy) {
                    GuestConnectionService.stop(context)
                    if (!failure.isNullOrBlank()) status = "Guest disconnected: $failure"
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
        copilot.language.messageListener = { message -> post { status = message } }
        copilot.language.showDocumentHandler = { uri -> openExternal(uri) }
        copilot.statusListener = { copilotState ->
            post {
                copilotStatus = copilotState.message.ifEmpty { copilotState.kind }
                copilotStatusKind = copilotState.kind
                if (copilotState.kind == "Error" || copilotState.kind == "Warning") {
                    status = "Copilot: ${copilotState.message.ifEmpty { copilotState.kind }}"
                } else if (copilotState.kind == "Normal" && dialog is WorkbenchDialog.CopilotSignInCode) {
                    dialog = null
                    status = "Copilot signed in"
                    enableCopilot(true)
                }
            }
        }
        chatAgent.eventListener = { event -> post { appendChat(event) } }
        chatAgent.exitListener = { message ->
            post {
                chatBusy = false
                chatEntries.add(ChatEntry.Notice(message))
            }
        }
    }

    fun dispose() {
        editorSubscription.close()
        languageClients.values.forEach(LanguageClient::close)
        copilot.language.close()
        chatAgent.stop()
        notebooks.values.forEach { it.kernel.stop() }
        terminals.forEach { it.emulator.close() }
        documents.forEach { document -> io.execute { repository.persistDraft(document) } }
        vfs.connectionStateListener = null
        vfs.close()
        GuestConnectionService.stop(context)
        io.shutdown()
        timer.shutdown()
    }

    fun persistDrafts() {
        documents.forEach { document -> io.execute { repository.persistDraft(document) } }
    }

    /** Opens a web link from the terminal or chat in the Android browser. Guest `localhost` links
     *  work when the Terminal app forwards that port. */
    fun openLink(url: String) {
        val scheme = Uri.parse(url).scheme?.lowercase()
        status = when {
            scheme != "http" && scheme != "https" -> "Not opening $url"
            openExternal(url) -> "Opened $url"
            else -> "No app can open $url"
        }
    }

    private fun openExternal(uri: String): Boolean = runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess

    // Settings --------------------------------------------------------------------------------

    fun updateSetting(key: String, value: Any?) {
        applyUserSettings(userSettings.withPath(key, value))
    }

    fun openSettingsJson() {
        settingsDraft = com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(userSettings)
        dialog = WorkbenchDialog.SettingsJson
    }

    fun saveSettingsJson() {
        val parsed = try {
            JsonParser.parseString(settingsDraft).asJsonObject
        } catch (error: RuntimeException) {
            status = "User settings must be a JSON object: ${error.message}"
            return
        }
        applyUserSettings(parsed)
        dialog = null
        status = "User settings saved"
    }

    private fun applyUserSettings(next: JsonObject) {
        if (!preferences.edit().putString("user-settings", next.toString()).commit()) {
            status = "User settings could not be saved"
            return
        }
        val previousServer = settings.pythonLanguageServer
        userSettings = next
        updateLanguageSettings()
        editorView?.let(::applyEditorSettings)
        terminals.forEach { /* font size is read by the composable */ }
        if (settings.pythonLanguageServer != previousServer) {
            languageClients.getValue("python").close()
            post { documents.forEach { ensureLanguageServers(it.path) } }
        }
    }

    fun applyEditorSettings(view: CodeEditor) {
        val current = settings
        view.setTextSize(current.editorFontSize)
        view.setTabWidth(current.tabSize)
        if (view.isWordwrap != current.wordWrap) view.setWordwrap(current.wordWrap)
        view.setLineNumberEnabled(current.lineNumbers)
    }

    private fun updateLanguageSettings() {
        languageClients.forEach { (language, client) ->
            val merged = SettingsResolver.merge(defaultSettings, userSettings, workspaceSettings, language)
            client.updateSettings(SettingsResolver.sections(merged))
        }
    }

    private fun loadWorkspaceSettings(root: String): CompletableFuture<JsonObject> {
        val path = "${root.trimEnd('/')}/.vscode/settings.json"
        return vfs.stat(path).thenCompose { entry ->
            when {
                entry.isDirectory -> CompletableFuture.failedFuture(
                    IllegalStateException("Workspace settings path is a directory: $path"),
                )
                entry.size > 1024L * 1024L -> CompletableFuture.failedFuture(
                    IllegalStateException("Workspace settings exceed the 1 MiB limit: $path"),
                )
                entry.size == 0L -> CompletableFuture.completedFuture(JsonObject())
                else -> vfs.read(path, 0L, entry.size.toInt()).thenApply { packet ->
                    check(packet.eof && packet.content.size.toLong() == entry.size) {
                        "Workspace settings could not be read completely: $path"
                    }
                    try {
                        JsonParser.parseString(packet.content.toString(Charsets.UTF_8)).asJsonObject
                    } catch (error: RuntimeException) {
                        throw IllegalStateException("Workspace settings are invalid JSON: $path", error)
                    }
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

    // Connection ------------------------------------------------------------------------------

    fun selectProfile(profile: com.avfstudio.nativeide.transport.ConnectionProfile) {
        profileId = profile.id
        profileName = profile.name
        endpoint = profile.endpoint
        token = profileStore.token(profile.id).orEmpty()
    }

    fun newProfile() {
        profileId = null
        profileName = ""
        endpoint = ""
        token = ""
    }

    fun deleteProfile(id: String) {
        profileStore.delete(id)
        profiles = profileStore.profiles()
        if (profileId == id) newProfile()
    }

    fun requestGuestConnection() {
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        val ask = requestNotificationPermission
        if (needsPermission && ask != null) ask() else connectGuest()
    }

    fun connectGuest() {
        busy = true
        status = "Connecting to guest"
        languageServerIssues = emptyList()
        val notificationsAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        keepAliveError = if (notificationsAllowed) null else {
            "Notification permission denied; Android may hide the background connection notification"
        }
        startKeepAlive("Connecting")
        vfs.connect(endpoint.trim(), token).thenCompose { vfs.roots() }.whenComplete { guestRoots, error ->
            post {
                busy = false
                if (error != null) {
                    connected = false
                    GuestConnectionService.stop(context)
                    status = "Connection failed: ${error.cause?.message ?: error.message}"
                    return@post
                }
                connected = true
                roots = guestRoots
                if (dialog == WorkbenchDialog.Connect) dialog = null
                io.execute {
                    val saved = profileStore.save(profileId, profileName, endpoint, token)
                    post {
                        profileId = saved.id
                        profileName = saved.name
                        profiles = profileStore.profiles()
                    }
                }
                startKeepAlive("Connected")
                status = when {
                    keepAliveError != null -> "Connected; background keepalive unavailable: $keepAliveError"
                    guestRoots.isEmpty() -> "Connected; guest has no workspace roots"
                    else -> "Guest connected"
                }
                guestRoots.firstOrNull()?.let { workspace -> onWorkspaceConnected(workspace) }
                reattachTerminals()
                documents.filter { it.dirty && !it.path.startsWith("scratch:") }.forEach(::reconcileDocument)
            }
        }
    }

    private fun startKeepAlive(state: String) {
        try {
            GuestConnectionService.start(context, endpoint.trim(), state)
        } catch (error: SecurityException) {
            keepAliveError = error.message ?: "Android denied the foreground service request"
        } catch (error: IllegalStateException) {
            keepAliveError = error.message ?: "Android does not allow a foreground service at this time"
        }
    }

    private fun onWorkspaceConnected(workspace: String) {
        loadDirectory(workspace)
        loadWorkspaceSettings(workspace).whenComplete { loaded, settingsError ->
            post {
                if (settingsError != null) {
                    status = "Guest connected; could not load workspace settings: " +
                        (settingsError.cause?.message ?: settingsError.message)
                } else {
                    workspaceSettings = loaded
                    updateLanguageSettings()
                }
                workspaceRoot = workspace
                documents.forEach { ensureLanguageServers(it.path) }
                startCopilot()
                notebooks.values.forEach { session ->
                    session.kernelState = "No kernel"
                    session.kernelName = null
                }
            }
        }
    }

    private fun reconcileDocument(document: DocumentSession) {
        repository.reconcile(document).whenComplete { result, error ->
            post {
                when {
                    error != null -> status = "Reconnect save failed: ${error.message}"
                    result is SaveResult.Conflict -> {
                        dialog = WorkbenchDialog.Conflict(document)
                        status = "Reconnect found external edits in ${document.path}"
                    }
                    result is SaveResult.Saved -> {
                        documentRevision++
                        status = "Reconnected and saved ${document.path}"
                    }
                }
            }
        }
    }

    // Explorer and documents -----------------------------------------------------------------

    fun loadDirectory(path: String) {
        if (!vfs.isConnected()) {
            status = "Connect to a guest to browse files"
            return
        }
        vfs.listDir(path).whenComplete { result, error ->
            post {
                if (error != null) {
                    status = "Explorer error: ${error.message ?: "request failed"}"
                } else {
                    currentDirectory = path
                    entries = result.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenBy { it.name })
                }
            }
        }
    }

    /** Walks the workspace for Quick Open, skipping dependency and cache folders. */
    fun refreshQuickOpenIndex() {
        val root = workspaceRoot ?: return
        if (quickOpenIndexing || !vfs.isConnected()) return
        quickOpenIndexing = true
        val files = java.util.concurrent.ConcurrentLinkedQueue<FileEntry>()
        fun walk(path: String, depth: Int): CompletableFuture<Unit> =
            vfs.listDir(path).thenCompose { children ->
                children.filterNot(FileEntry::isDirectory).forEach(files::add)
                if (files.size > MAX_QUICK_OPEN_FILES || depth >= 8) return@thenCompose CompletableFuture.completedFuture(Unit)
                val nested = children.filter { it.isDirectory && it.name !in skippedDirectories }.map { walk(it.path, depth + 1) }
                CompletableFuture.allOf(*nested.toTypedArray()).thenApply { }
            }.exceptionally { }
        walk(root, 0).whenComplete { _, _ ->
            post {
                quickOpenIndex = files.sortedBy { it.path }
                quickOpenIndexing = false
            }
        }
    }

    fun navigateToDirectory(path: String) {
        sidebar = SidebarView.Explorer
        compactDestination = CompactDestination.Files
        loadDirectory(path)
    }

    fun openPath(path: String) {
        if (!vfs.isConnected()) {
            status = "Connect to a guest before opening files"
            return
        }
        documents.firstOrNull { it.path == path }?.let {
            selectDocument(path)
            return
        }
        busy = true
        status = "Opening $path"
        repository.open(path).whenComplete { result, error -> post { showOpenResult(result, error) } }
    }

    private fun showOpenResult(result: OpenDocument?, error: Throwable?) {
        busy = false
        if (error != null) {
            status = "Open failed: ${error.cause?.message ?: error.message ?: "unknown error"}"
            return
        }
        when (result) {
            is OpenDocument.Editable -> {
                val existing = documents.firstOrNull { it.path == result.document.path }
                val session = existing ?: result.document.also(documents::add)
                if (session.path.endsWith(".ipynb", ignoreCase = true) && !openNotebook(session)) {
                    documents.remove(session)
                    return
                }
                selectDocument(session.path)
                status = if (session.conflict != null) "Remote changes detected; choose how to resolve" else "Opened ${session.path.substringAfterLast('/')}"
                notifyDocumentOpened(session)
                ensureLanguageServers(session.path)
                if (lspLanguageId(session.path) != null) refreshOutline(session)
                if (session.conflict != null) dialog = WorkbenchDialog.Conflict(session)
            }
            is OpenDocument.Paged -> {
                activePath = null
                pagedDocument = result.document
                compactDestination = CompactDestination.Editor
                status = "Large file is read-only and paged"
            }
            null -> status = "Open returned no document"
        }
    }

    fun selectDocument(path: String) {
        activePath = path
        pagedDocument = null
        compactDestination = CompactDestination.Editor
    }

    fun pageLargeFile(direction: Int) {
        val current = pagedDocument ?: return
        repository.page(current.path, current, direction).whenComplete { page, error ->
            post {
                if (error == null) pagedDocument = page else status = "Page load failed: ${error.message}"
            }
        }
    }

    fun closeDocument(path: String) {
        documents.firstOrNull { it.path == path }?.let(closedDocuments::add)
        notifyDocumentClosed(path)
        notebooks.remove(path)?.kernel?.stop()
        documents.removeAll { it.path == path }
        if (activePath == path) activePath = documents.lastOrNull()?.path
        scheduleIdleServerStop()
    }

    fun onDocumentEdited(document: DocumentSession) {
        documentRevision++
        synchronized(scheduledDraftWrites) {
            scheduledDraftWrites.remove(document.path)?.cancel(false)
            scheduledDraftWrites[document.path] = timer.schedule(
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
        if (settings.autoSave && !document.path.startsWith("scratch:")) {
            scheduledAutoSave?.cancel(false)
            scheduledAutoSave = timer.schedule(
                { post { if (activePath == document.path && document.dirty) saveActiveDocument() } },
                settings.autoSaveDelayMs.toLong(),
                TimeUnit.MILLISECONDS,
            )
        }
    }

    fun saveActiveDocument() {
        activeNotebook?.let {
            saveNotebook(it)
            return
        }
        val document = activeDocument ?: return
        if (document.path.startsWith("scratch:")) {
            if (vfs.isConnected()) {
                beginSaveAs(document)
                return
            }
            document.dirty = false
            repository.persistDraft(document)
            documentRevision++
            status = "Saved locally; connect to save it to the guest"
            return
        }
        if (settings.formatOnSave && formattingClientFor(document.path)?.isReady == true) {
            formatActiveDocument { saveDocument(document) }
        } else {
            saveDocument(document)
        }
    }

    private fun saveDocument(document: DocumentSession) {
        status = "Saving ${document.path}"
        repository.save(document).whenComplete { result, error ->
            post {
                documentRevision++
                if (error != null) {
                    status = "Save failed: ${error.cause?.message ?: error.message}"
                    return@post
                }
                when (result) {
                    is SaveResult.Saved -> status = "Saved ${document.path}"
                    SaveResult.QueuedOffline -> status = "Saved locally; queued until guest reconnects"
                    is SaveResult.Conflict -> {
                        dialog = WorkbenchDialog.Conflict(document)
                        status = "Save conflict: remote file changed"
                    }
                }
            }
        }
    }

    fun keepLocalAfterConflict(document: DocumentSession) {
        repository.keepLocalAfterConflict(document).whenComplete { _, error ->
            post {
                dialog = null
                documentRevision++
                status = if (error != null) "Save failed: ${error.message}" else "Saved local draft over guest version"
            }
        }
    }

    fun loadRemoteAfterConflict(document: DocumentSession) {
        repository.discardLocalForRemote(document).whenComplete { _, error ->
            post {
                dialog = null
                documentRevision++
                if (activePath == document.path) editorView?.setText(document.text)
                status = if (error == null) "Loaded guest version" else "Reload failed: ${error.message}"
            }
        }
    }

    /** Ctrl+N: a plain-text scratch editor; saving it asks where to put it. */
    fun newUntitledFile() {
        val name = nextUntitledName(documents.map { it.path })
        val document = DocumentSession("scratch:/$name", "", baseModifiedTs = null, dirty = false)
        documents.add(document)
        selectDocument(document.path)
        status = "Created $name"
    }

    // File operations -------------------------------------------------------------------------

    /** Folder that New File/Folder and Save As start from: the Explorer's, else the workspace. */
    private fun explorerParent(): String? = currentDirectory.ifEmpty { null } ?: workspaceRoot

    fun beginNewEntry(folder: Boolean, parent: String? = explorerParent()) {
        if (!vfs.isConnected() || parent == null) {
            status = "Connect to a guest to create files"
            return
        }
        dialog = WorkbenchDialog.PathPrompt(if (folder) PathAction.NewFolder(parent) else PathAction.NewFile(parent), "")
    }

    fun beginRenameEntry(path: String? = activePath) {
        if (path == null || path.startsWith("scratch:")) {
            status = "Open or select a saved file to rename"
            return
        }
        dialog = WorkbenchDialog.PathPrompt(PathAction.Rename(path), path.substringAfterLast('/'))
    }

    fun beginDeleteEntry(path: String? = activePath, isDirectory: Boolean = false) {
        if (path == null || path.startsWith("scratch:")) {
            status = "Open or select a saved file to delete"
            return
        }
        dialog = WorkbenchDialog.ConfirmDelete(path, isDirectory)
    }

    fun beginSaveAs(document: DocumentSession? = activeDocument) {
        val parent = explorerParent()
        if (document == null || !vfs.isConnected() || parent == null) {
            status = "Connect to a guest to save files"
            return
        }
        dialog = WorkbenchDialog.PathPrompt(
            PathAction.SaveAs(document.path),
            resolveEntryPath(parent, document.path.removePrefix("scratch:/").substringAfterLast('/')),
        )
    }

    fun submitPathPrompt(prompt: WorkbenchDialog.PathPrompt, value: String) {
        val action = prompt.action
        val name = if (action is PathAction.SaveAs) value.trim() else value
        entryNameProblem(name, allowSubpath = action !is PathAction.Rename)?.let { problem ->
            dialog = prompt.copy(error = problem)
            return
        }
        fun fail(error: Throwable) = post { dialog = prompt.copy(error = failureMessage(error)) }
        when (action) {
            is PathAction.NewFile, is PathAction.NewFolder -> {
                val parent = (action as? PathAction.NewFile)?.parent ?: (action as PathAction.NewFolder).parent
                val folder = action is PathAction.NewFolder
                val target = resolveEntryPath(parent, name)
                val targetParent = target.substringBeforeLast('/')
                val parentReady = if (folder || targetParent == parent.trimEnd('/')) {
                    CompletableFuture.completedFuture(Unit)
                } else {
                    vfs.create(targetParent, directory = true).thenApply { }
                }
                parentReady.thenCompose { vfs.create(target, folder) }.whenComplete { _, error ->
                    if (error != null) return@whenComplete fail(error)
                    post {
                        dialog = null
                        afterFileSystemChange()
                        if (folder) status = "Created ${target.substringAfterLast('/')}" else openPath(target)
                    }
                }
            }
            is PathAction.Rename -> renameEntry(action.path, resolveEntryPath(action.path.substringBeforeLast('/'), name), ::fail)
            is PathAction.SaveAs -> saveDocumentAs(action.documentPath, resolveEntryPath(explorerParent() ?: "/", name), ::fail)
        }
    }

    private fun renameEntry(from: String, to: String, fail: (Throwable) -> Unit) {
        val affected = documents.filter { renamedPath(it.path, from, to) != null }
        affected.firstOrNull { it.dirty }?.let { document ->
            fail(IllegalStateException("Save ${document.path.substringAfterLast('/')} before renaming"))
            return
        }
        vfs.rename(from, to).whenComplete { _, error ->
            if (error != null) return@whenComplete fail(error)
            post {
                dialog = null
                val wasActive = activePath
                affected.forEach { document ->
                    closeDocument(document.path)
                    repository.forget(document.path)
                }
                // Reopen what was open under the old name; the previously active file last, so it stays in front.
                affected.sortedBy { it.path == wasActive }.forEach { document ->
                    renamedPath(document.path, from, to)?.let(::openPath)
                }
                afterFileSystemChange()
                status = "Renamed to ${to.substringAfterLast('/')}"
            }
        }
    }

    fun deleteEntry(path: String) {
        dialog = null
        vfs.delete(path).whenComplete { _, error ->
            post {
                if (error != null) {
                    status = "Delete failed: ${failureMessage(error)}"
                    return@post
                }
                documents.filter { renamedPath(it.path, path, path) != null }.forEach { document ->
                    closeDocument(document.path)
                    repository.forget(document.path)
                }
                afterFileSystemChange()
                status = "Moved ${path.substringAfterLast('/')} to the trash"
            }
        }
    }

    /** Writes [sourcePath]'s text to a new file [target] (never overwriting) and switches to it. */
    private fun saveDocumentAs(sourcePath: String, target: String, fail: (Throwable) -> Unit) {
        val source = documents.firstOrNull { it.path == sourcePath } ?: return
        val copy = DocumentSession(target, source.text, baseModifiedTs = null, dirty = true, encoding = source.encoding, eol = source.eol)
        vfs.create(target, directory = false)
            .thenCompose { repository.save(copy) }
            .whenComplete { result, error ->
                if (error != null) return@whenComplete fail(error)
                if (result !is SaveResult.Saved) return@whenComplete fail(IllegalStateException("The guest did not confirm the save"))
                post {
                    dialog = null
                    closeDocument(sourcePath)
                    if (sourcePath.startsWith("scratch:")) repository.forget(sourcePath)
                    afterFileSystemChange()
                    openPath(target)
                }
            }
    }

    private fun afterFileSystemChange() {
        currentDirectory.takeIf(String::isNotEmpty)?.let(::loadDirectory)
        quickOpenIndex = emptyList()
        refreshQuickOpenIndex()
    }

    private fun failureMessage(error: Throwable): String {
        val cause = generateSequence(error) { it.cause }.last()
        return when ((cause as? GuestVfsException)?.code) {
            "AlreadyExists" -> "That name is already taken"
            "NotFound" -> "The file no longer exists"
            "PermissionDenied" -> "Permission denied"
            else -> cause.message ?: error.message ?: "Request failed"
        }
    }

    // Editor binding ----------------------------------------------------------------------------

    fun editorReady(view: CodeEditor) {
        val newlyBound = editorView !== view
        editorView = view
        val target = pendingNavigation
        if (target != null && target.path == activePath) {
            view.setSelection(target.line, target.character)
            view.ensureSelectionVisible()
            pendingNavigation = null
        }
        if (newlyBound) activeDocument?.let(::refreshSemanticTokens)
    }

    private fun onEditorEvent(event: EditorEvent) {
        when (event) {
            is EditorEvent.SelectionChanged -> post {
                if (event.uri == activePath) {
                    if (!restoringCursor && cursorPosition != null && cursorPosition != event.selection.active) {
                        cursorHistory.addLast(event.uri to cursorPosition)
                        if (cursorHistory.size > 64) cursorHistory.removeFirst()
                    }
                    restoringCursor = false
                    cursorPosition = event.selection.active
                }
                val shown = inlineSuggestion
                if (shown != null && (shown.first != event.uri || event.selection.active != shown.second.range.end &&
                        !cursorWithinSuggestion(shown.second, event.selection.active))
                ) {
                    clearInlineSuggestion()
                }
            }
            is EditorEvent.ContentChanged -> if (!event.uri.startsWith("scratch:")) {
                notifyDocumentChanged(event.uri, event.version, event.text)
                post {
                    if (activePath == event.uri) {
                        scheduleOutlineRefresh(event.uri)
                        scheduleInlineSuggestion()
                    }
                }
            }
            is EditorEvent.ViewportChanged -> post { editorViewportTick++ }
            is EditorEvent.FocusChanged -> post {
                if (event.focused) focusArea = FocusArea.Editor else if (focusArea == FocusArea.Editor) focusArea = FocusArea.Other
            }
        }
    }

    private fun cursorWithinSuggestion(suggestion: CopilotSuggestion, position: LspPosition): Boolean =
        position.line == suggestion.range.start.line && position.character >= suggestion.range.start.character

    // Language servers ------------------------------------------------------------------------

    fun languageClientFor(path: String): LanguageClient? =
        when (path.substringAfterLast('.', "").lowercase()) {
            "py", "pyi" -> languageClients["python"]
            "ts", "tsx", "js", "jsx", "mts", "cts", "mjs", "cjs" -> languageClients["typescript"]
            else -> null
        }

    private fun languageClientsFor(path: String): List<LanguageClient> =
        listOfNotNull(
            languageClientFor(path),
            if (path.substringAfterLast('.', "").lowercase() in setOf("py", "pyi")) languageClients["python-format"] else null,
        ).distinct()

    private fun formattingClientFor(path: String): LanguageClient? =
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

    private fun languageServerTool(language: String): String =
        when (language) {
            "python" -> settings.pythonLanguageServer
            "python-format" -> "ruff"
            else -> "vtsls"
        }

    private fun copilotLanguageId(path: String): String =
        lspLanguageId(path) ?: when (path.substringAfterLast('.', "").lowercase()) {
            "rs" -> "rust"
            "kt", "kts" -> "kotlin"
            "go" -> "go"
            "java" -> "java"
            "c", "h" -> "c"
            "cpp", "cc", "hpp" -> "cpp"
            "sh", "bash" -> "shellscript"
            "md" -> "markdown"
            "json" -> "json"
            "toml" -> "toml"
            "yaml", "yml" -> "yaml"
            "html" -> "html"
            "css" -> "css"
            else -> "plaintext"
        }

    private fun notifyDocumentOpened(document: DocumentSession) {
        if (document.path.startsWith("scratch:") || document.path in notebooks) return
        lspLanguageId(document.path)?.let { languageId ->
            languageClientsFor(document.path).filter { it.isReady }.forEach { client ->
                client.didOpen(document.path, languageId, document.version, document.text)
            }
        }
        if (copilot.language.isReady) {
            copilot.language.didOpen(document.path, copilotLanguageId(document.path), document.version, document.text)
        }
    }

    private fun notifyDocumentChanged(path: String, version: Long, text: String) {
        languageClientsFor(path).filter { it.isReady }.forEach { client -> client.didChange(path, version, text) }
        if (copilot.language.isReady) copilot.language.didChange(path, version, text)
    }

    private fun notifyDocumentClosed(path: String) {
        languageClientsFor(path).forEach { it.didClose(path) }
        copilot.language.didClose(path)
    }

    private fun reportLanguageServer(language: String, error: Throwable?) {
        val others = languageServerIssues.filterNot { it.server == language }
        languageServerIssues = if (error == null) {
            others
        } else {
            others + LanguageServerIssue(language, error.cause?.message ?: error.message ?: "server not ready")
        }
    }

    /** Starts the servers a document needs, so idle languages cost no guest memory. */
    private fun ensureLanguageServers(path: String) {
        val workspace = workspaceRoot ?: return
        if (!vfs.isConnected() || path.startsWith("scratch:") || path in notebooks) return
        scheduledServerStop?.cancel(false)
        languageClients.filterValues { it in languageClientsFor(path) && !it.isStarted }.forEach { (language, client) ->
            val tool = languageServerTool(language)
            status = "Starting $tool"
            client.start(workspace, tool).whenComplete { _, error ->
                post {
                    reportLanguageServer(language, error)
                    if (error != null) {
                        status = "$tool: ${error.cause?.message ?: error.message}"
                        return@post
                    }
                    status = "$tool ready"
                    documents.filter { client in languageClientsFor(it.path) }.forEach { document ->
                        notifyDocumentOpened(document)
                        if (activePath == document.path) {
                            refreshOutline(document)
                            refreshSemanticTokens(document)
                        }
                    }
                }
            }
        }
    }

    /** Shuts down language servers that no open document uses after the configured delay. */
    private fun scheduleIdleServerStop() {
        scheduledServerStop?.cancel(false)
        scheduledServerStop = timer.schedule(
            {
                post {
                    val needed = documents.filterNot { it.path.startsWith("scratch:") || it.path in notebooks }
                        .flatMap { languageClientsFor(it.path) }.toSet()
                    languageClients.values.filter { it.isStarted && it !in needed }.forEach(LanguageClient::close)
                }
            },
            settings.languageServerIdleSeconds.toLong(),
            TimeUnit.SECONDS,
        )
    }

    private fun refreshOutline(document: DocumentSession) {
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
                outlineSymbols = if (error != null) emptyList() else response.toOutlineSymbols()
            }
        }
    }

    fun onActiveDocumentChanged() {
        outlineSymbols = emptyList()
        clearInlineSuggestion()
        activeDocument?.let { document ->
            refreshOutline(document)
            refreshSemanticTokens(document)
        }
        if (copilot.language.isReady) copilot.didFocus(activePath?.takeUnless { it.startsWith("scratch:") })
    }

    private fun refreshSemanticTokens(document: DocumentSession) {
        if (document.path.startsWith("scratch:") || !settings.semanticHighlighting) return
        val client = languageClientFor(document.path)
        if (client?.isReady != true) return
        client.semanticTokens(document.path).whenComplete { decorations, error ->
            post {
                if (activePath != document.path || editorHost.selection(document.path) == null) return@post
                // LSP ContentModified (-32801): the server saw a newer edit; the next refresh repaints.
                if ((error?.cause as? JsonRpcException)?.code == -32801) return@post
                if (error != null) {
                    status = "Semantic highlighting failed: ${error.cause?.message ?: error.message}"
                } else {
                    editorHost.setDecorations(document.path, decorations)
                }
            }
        }
    }

    private fun scheduleOutlineRefresh(path: String) {
        synchronized(scheduledOutlineRefreshes) {
            scheduledOutlineRefreshes.remove(path)?.cancel(false)
            scheduledOutlineRefreshes[path] = timer.schedule(
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

    private fun activeLspPosition(): Pair<DocumentSession, LspPosition>? {
        val document = activeDocument ?: return null
        val client = languageClientFor(document.path)
        if (client?.isReady != true || document.path.startsWith("scratch:")) {
            status = "Language intelligence requires a connected Python or TypeScript language server"
            return null
        }
        val cursor = editorView?.cursor ?: return null
        return document to LspPosition(cursor.leftLine, cursor.leftColumn)
    }

    fun navigateToLocation(location: LanguageLocation) {
        val path = java.net.URI(location.uri).path ?: return
        val start = location.range.getAsJsonObject("start") ?: return
        val target = EditorNavigationTarget(path, start.get("line").asInt, start.get("character").asInt)
        compactDestination = CompactDestination.Editor
        if (activePath == path) {
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

    private fun showCompletion() {
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
                val choices = items.mapNotNull { item ->
                    val value = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                    val label = value.get("label")?.takeUnless { it.isJsonNull }?.asString ?: return@mapNotNull null
                    val textEdit = value.getAsJsonObject("textEdit")
                    val replaceRange = textEdit?.getAsJsonObject("replace") ?: textEdit?.getAsJsonObject("range")
                    val edit = if (replaceRange != null && textEdit.has("newText")) {
                        replaceRange.toLspRange()?.let { LspTextEdit(it, textEdit.get("newText").asString) }
                    } else {
                        LspTextEdit(defaultRange, value.get("insertText")?.takeUnless { it.isJsonNull }?.asString ?: label)
                    }
                    CompletionChoice(label, value.get("detail")?.takeUnless { it.isJsonNull }?.asString, edit)
                }
                if (choices.isEmpty()) status = "No completion suggestions" else dialog = WorkbenchDialog.Completion(choices)
            }
        }
    }

    fun applyCompletion(choice: CompletionChoice) {
        dialog = null
        val document = activeDocument ?: return
        val edit = choice.edit ?: return
        editorHost.applyEdits(document.path, listOf(edit))
        onDocumentEdited(document)
    }

    private fun showHover() {
        val (document, position) = activeLspPosition() ?: return
        val client = languageClientFor(document.path) ?: return
        client.hover(document.path, position).whenComplete { response, error ->
            post {
                if (error != null) {
                    status = "Hover failed: ${error.cause?.message ?: error.message}"
                    return@post
                }
                val text = response.takeUnless { it.isJsonNull }?.asJsonObject?.get("contents")?.let(::hoverContents)
                if (text == null) status = "No hover information" else dialog = WorkbenchDialog.Hover(text)
            }
        }
    }

    private fun applyWorkspaceEdits(
        edits: Map<String, List<LspTextEdit>>,
        successMessage: String,
        onApplied: (() -> Unit)? = null,
    ) {
        if (edits.isEmpty()) {
            onApplied?.invoke()
            return
        }
        if (edits.keys.any { path -> roots.none { root -> isPathWithinRoot(path, root) } }) {
            status = "Workspace edit refused a change outside the connected workspace"
            return
        }
        val documentsToEdit = edits.mapValues { (path, _) ->
            documents.firstOrNull { it.path == path }?.let { CompletableFuture.completedFuture(it) }
                ?: repository.open(path).thenApply { opened ->
                    (opened as? OpenDocument.Editable)?.document ?: throw IllegalStateException("Cannot edit paged file $path")
                }
        }
        CompletableFuture.allOf(*documentsToEdit.values.toTypedArray()).whenComplete { _, openError ->
            post {
                if (openError != null) {
                    status = "Workspace edit could not load all affected files: ${openError.cause?.message ?: openError.message}"
                    return@post
                }
                try {
                    val updated = edits.mapValues { (path, textEdits) ->
                        val target = documentsToEdit.getValue(path).join()
                        target to applyLspTextEdits(target.text, textEdits)
                    }
                    updated.forEach { (path, update) ->
                        val (target, text) = update
                        if (documents.none { it.path == path }) documents.add(target)
                        notifyDocumentOpened(target)
                        target.updateText(text)
                        onDocumentEdited(target)
                        notifyDocumentChanged(path, target.version, target.text)
                        if (activePath == path) editorView?.setText(text)
                    }
                    status = "$successMessage in ${updated.size} file(s); save to persist changes"
                    onApplied?.invoke()
                } catch (applyError: RuntimeException) {
                    status = "Workspace edit could not be applied: ${applyError.message}"
                }
            }
        }
    }

    private fun showCodeActions() {
        val (document, position) = activeLspPosition() ?: return
        val client = languageClientFor(document.path) ?: return
        val uri = java.net.URI("file", "", document.path, null).toASCIIString()
        val fileDiagnostics = JsonArray().apply {
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
        client.codeActions(document.path, LspRange(position, position), fileDiagnostics).whenComplete { response, error ->
            post {
                if (error != null) {
                    status = "Code actions failed: ${error.cause?.message ?: error.message}"
                    return@post
                }
                val choices = if (response.isJsonArray) {
                    response.asJsonArray.mapNotNull { value ->
                        val action = value.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                        val title = action.get("title")?.takeUnless { it.isJsonNull }?.asString ?: return@mapNotNull null
                        CodeActionChoice(title, action)
                    }
                } else {
                    emptyList()
                }
                if (choices.isEmpty()) status = "No code actions available" else dialog = WorkbenchDialog.CodeActions(choices)
            }
        }
    }

    fun applyCodeAction(choice: CodeActionChoice) {
        val document = activeDocument ?: return
        val client = languageClientFor(document.path) ?: return
        dialog = null
        val action = choice.action
        val commandValue = action.get("command")?.takeUnless { it.isJsonNull }
        val commandObject = commandValue?.takeIf { it.isJsonObject }?.asJsonObject
        val command = commandObject?.get("command")?.takeUnless { it.isJsonNull }?.asString
            ?: commandValue?.takeIf { it.isJsonPrimitive }?.asString
        val arguments = commandObject?.getAsJsonArray("arguments") ?: JsonArray()
        val edits = try {
            action.get("edit")?.takeUnless { it.isJsonNull }?.workspaceTextEdits().orEmpty()
        } catch (error: RuntimeException) {
            status = "Code action returned invalid workspace edits: ${error.message}"
            return
        }
        if (edits.isEmpty() && command == null) {
            status = "Code action has no applicable edit or command"
            return
        }
        applyWorkspaceEdits(edits, "Applied ${choice.title}") {
            if (command != null) {
                client.executeCommand(command, arguments).whenComplete { _, error ->
                    post {
                        status = if (error == null) "Applied ${choice.title}" else "Code action failed: ${error.cause?.message ?: error.message}"
                    }
                }
            }
        }
    }

    private fun goToDefinition() {
        val (document, position) = activeLspPosition() ?: return
        val client = languageClientFor(document.path) ?: return
        client.definition(document.path, position).whenComplete { response, error ->
            post {
                if (error != null) {
                    status = "Go to Definition failed: ${error.cause?.message ?: error.message}"
                } else {
                    response.toLocations().firstOrNull()?.let(::navigateToLocation) ?: run { status = "No definition found" }
                }
            }
        }
    }

    private fun findReferences() {
        val (document, position) = activeLspPosition() ?: return
        val client = languageClientFor(document.path) ?: return
        client.references(document.path, position).whenComplete { response, error ->
            post {
                if (error != null) {
                    status = "Find References failed: ${error.cause?.message ?: error.message}"
                } else {
                    referenceLocations = response.toLocations()
                    sidebar = SidebarView.References
                    status = "${referenceLocations.size} reference(s)"
                }
            }
        }
    }

    private fun formatActiveDocument(onFormatted: (() -> Unit)? = null) {
        val document = activeDocument ?: return
        val client = formattingClientFor(document.path)
        if (client?.isReady != true) {
            status = if (document.path.substringAfterLast('.', "").lowercase() in setOf("py", "pyi")) {
                "Python formatting requires Ruff to be installed in the guest"
            } else {
                "Formatting requires a connected language server for this file"
            }
            onFormatted?.invoke()
            return
        }
        client.formatting(document.path).whenComplete { edits, error ->
            post {
                when {
                    error != null -> status = "Format failed: ${error.cause?.message ?: error.message}"
                    edits.isEmpty() -> status = "Document is already formatted"
                    else -> {
                        editorHost.applyEdits(document.path, edits)
                        onDocumentEdited(document)
                        status = "Formatted document"
                    }
                }
                onFormatted?.invoke()
            }
        }
    }

    private fun beginRename() {
        val (document, position) = activeLspPosition() ?: return
        renameValue = wordAtPosition(document.text, position)
        if (renameValue.isBlank()) status = "Place the cursor on a symbol to rename" else dialog = WorkbenchDialog.Rename
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
                dialog = null
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
                if (edits.isEmpty()) status = "Rename produced no edits" else applyWorkspaceEdits(edits, "Renamed symbol")
            }
        }
    }

    // Editor text commands --------------------------------------------------------------------

    /** Ctrl+/ over every selected line (a selection ending at column 0 excludes that line, as in VS Code). */
    private fun toggleLineComment() {
        val document = activeDocument ?: return
        val view = editorView ?: return
        val token = commentTokens(document.languageId).line ?: run {
            toggleBlockComment()
            return
        }
        val range = view.cursorRange
        val first = range.start.line
        val last = if (range.end.line > first && range.end.column == 0) range.end.line - 1 else range.end.line
        val lines = view.text.toString().split('\n')
        val toggled = toggleLineComments(lines.subList(first, last + 1), token)
        val updated = (lines.subList(0, first) + toggled + lines.subList(last + 1, lines.size)).joinToString("\n")
        val offsetOf = { line: Int, column: Int -> (0 until line).sumOf { lines.getOrElse(it) { "" }.length + 1 } + column }
        val shift = { line: Int, column: Int ->
            val delta = toggled.getOrNull(line - first)?.let { it.length - lines[line].length } ?: 0
            offsetOf(line, maxOf(0, column + delta)) + (first until line).sumOf { toggled[it - first].length - lines[it].length }
        }
        replaceEditorText(view, updated, shift(range.start.line, range.start.column), shift(range.end.line, range.end.column))
    }

    private fun toggleBlockComment() {
        val document = activeDocument ?: return
        val view = editorView ?: return
        val (open, close) = commentTokens(document.languageId).block ?: run {
            status = "No block comment syntax for this file type"
            return
        }
        val range = view.cursorRange
        val edit = toggleBlockComment(view.text.toString(), range.startIndex, range.endIndex, open, close)
        replaceEditorText(view, edit.text, edit.selectionStart, edit.selectionEnd)
    }

    /** Applies [updated] as one minimal replacement so undo and incremental highlighting keep working. */
    private fun replaceEditorText(view: CodeEditor, updated: String, selectionStart: Int, selectionEnd: Int) {
        val span = changedSpan(view.text.toString(), updated)
        val from = view.text.indexer.getCharPosition(span.start)
        val to = view.text.indexer.getCharPosition(span.oldEnd)
        view.text.replace(from.line, from.column, to.line, to.column, updated.substring(span.start, span.newEnd))
        val start = view.text.indexer.getCharPosition(selectionStart.coerceIn(0, updated.length))
        val end = view.text.indexer.getCharPosition(selectionEnd.coerceIn(0, updated.length))
        view.setSelectionRegion(start.line, start.column, end.line, end.column)
    }

    /** F8 / Shift+F8: the next or previous diagnostic in the active file, wrapping around. */
    private fun goToProblem(forward: Boolean) {
        val document = activeDocument ?: return
        val cursor = cursorPosition ?: LspPosition(0, 0)
        fun position(diagnostic: LanguageDiagnostic): LspPosition {
            val start = diagnostic.range.getAsJsonObject("start")
            return LspPosition(start?.get("line")?.asInt ?: 0, start?.get("character")?.asInt ?: 0)
        }
        val order = compareBy<LspPosition>({ it.line }, { it.character })
        val problems = diagnostics
            .filter { runCatching { java.net.URI(it.uri).path }.getOrNull() == document.path }
            .sortedWith(compareBy(order) { position(it) })
        if (problems.isEmpty()) {
            status = "No problems in this file"
            return
        }
        val target = if (forward) {
            problems.firstOrNull { order.compare(position(it), cursor) > 0 } ?: problems.first()
        } else {
            problems.lastOrNull { order.compare(position(it), cursor) < 0 } ?: problems.last()
        }
        navigateToLocation(LanguageLocation(target.uri, target.range))
        status = target.message.lineSequence().first()
    }

    /** Ctrl+U: back to where the cursor was before its last move in this file. */
    private fun undoCursorMove() {
        val path = activePath ?: return
        val view = editorView ?: return
        while (cursorHistory.isNotEmpty() && cursorHistory.last() == path to cursorPosition) cursorHistory.removeLast()
        val (historyPath, position) = cursorHistory.removeLastOrNull() ?: return
        if (historyPath != path || position == null) return
        restoringCursor = true
        view.setSelection(position.line, position.character)
        view.ensureSelectionVisible()
    }

    private fun copySelection(cut: Boolean) {
        val view = editorView ?: return
        val range = view.cursorRange
        if (range.startIndex == range.endIndex) {
            status = if (cut) "Select text before cutting" else "Select text before copying"
            return
        }
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = view.text.toString().substring(range.startIndex, range.endIndex)
        clipboard.setPrimaryClip(ClipData.newPlainText("AVF Studio", text))
        if (cut) view.deleteText()
    }

    private fun pasteClipboard() {
        val text = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString() ?: return
        editorView?.insertText(text, text.length)
    }

    fun findInEditor(next: Boolean) {
        val document = activeDocument ?: return
        val view = editorView ?: return
        if (findQuery.isEmpty()) {
            dialog = WorkbenchDialog.Find
            return
        }
        val text = view.text.toString()
        val start = view.cursor.right
        val foundAt = if (next) {
            text.indexOf(findQuery, start.coerceAtMost(text.length)).takeIf { it >= 0 } ?: text.indexOf(findQuery)
        } else {
            val from = (view.cursor.left - 1).coerceAtLeast(0)
            text.lastIndexOf(findQuery, from).takeIf { it >= 0 } ?: text.lastIndexOf(findQuery)
        }
        if (foundAt < 0) {
            status = "No matches for \"$findQuery\" in ${document.path.substringAfterLast('/')}"
            return
        }
        val begin = view.text.indexer.getCharPosition(foundAt)
        val end = view.text.indexer.getCharPosition(foundAt + findQuery.length)
        view.setSelectionRegion(begin.line, begin.column, end.line, end.column)
        view.ensureSelectionVisible()
    }

    fun replaceAll() {
        val document = activeDocument ?: return
        if (findQuery.isEmpty()) return
        val count = document.text.windowed(findQuery.length).count { it == findQuery }
        document.updateText(document.text.replace(findQuery, replaceQuery))
        editorView?.setText(document.text)
        onDocumentEdited(document)
        status = "Replaced $count occurrence(s)"
    }

    fun goToLine() {
        val target = gotoLine.toIntOrNull()
        val view = editorView
        if (target != null && view != null) {
            val line = (target - 1).coerceIn(0, view.lineCount - 1)
            view.setSelection(line, 0)
            view.ensureSelectionVisible()
        }
        dialog = null
    }

    fun setEncoding(encoding: String) {
        val document = activeDocument ?: return
        document.encoding = encoding
        repository.persistDraft(document)
        dialog = null
        documentRevision++
        status = "Encoding set to $encoding"
    }

    fun setLineEnding(selected: String) {
        val document = activeDocument ?: return
        val replacement = if (selected == "CRLF") "\r\n" else "\n"
        val normalized = document.text.replace("\r\n", "\n").replace("\r", "\n").replace("\n", replacement)
        document.updateText(normalized)
        document.eol = replacement
        editorView?.setText(normalized)
        onDocumentEdited(document)
        dialog = null
        status = "Line endings set to $selected"
    }

    private fun zoomEditor(delta: Float) {
        updateSetting(WorkbenchSettings.EDITOR_FONT_SIZE, (settings.editorFontSize + delta).coerceIn(8f, 40f))
    }

    // Copilot -----------------------------------------------------------------------------------

    private fun startCopilot() {
        val workspace = workspaceRoot ?: return
        if (!copilotEnabled || copilot.language.isStarted || !vfs.isConnected()) return
        copilotStatus = "Starting"
        copilot.start(workspace).whenComplete { _, error ->
            post {
                reportLanguageServer("copilot", error)
                if (error != null) {
                    copilotStatus = "Unavailable"
                    status = "Copilot: ${error.cause?.message ?: error.message}"
                } else {
                    copilotStatus = "Ready"
                    documents.forEach(::notifyDocumentOpened)
                    copilot.didFocus(activePath?.takeUnless { it.startsWith("scratch:") })
                }
            }
        }
    }

    fun enableCopilot(enabled: Boolean) {
        copilotEnabled = enabled
        preferences.edit().putBoolean("copilot-enabled", enabled).apply()
        when {
            !enabled -> {
                inlineSuggestion = null
                copilotStatus = "Off"
                copilot.language.close()
            }
            copilot.language.isReady -> documents.forEach(::notifyDocumentOpened)
            else -> startCopilot()
        }
    }

    /** Status-bar tap: switches suggestions off when Copilot is healthy, otherwise signs in
     *  (a signed-in server just answers "already signed in"), so a sign-in prompt never turns Copilot off. */
    fun onCopilotStatusClick() {
        if (copilotEnabled && copilotStatusKind in setOf("Normal", "InProgress")) {
            runCommand("github.copilot.toggleInlineSuggestions")
        } else {
            beginCopilotSignIn()
        }
    }

    fun beginCopilotSignIn() {
        if (!vfs.isConnected()) {
            status = "Connect to a guest before signing in to Copilot"
            return
        }
        // Copilot stays disabled (and its ~280 MB server stopped) until sign-in succeeds.
        val waitForServer = when {
            copilot.language.isReady -> CompletableFuture.completedFuture(Unit)
            copilot.language.isStarted -> CompletableFuture.supplyAsync({ Unit }, CompletableFuture.delayedExecutor(3, TimeUnit.SECONDS))
            else -> copilot.start(workspaceRoot ?: return)
        }
        status = "Requesting a Copilot sign-in code"
        waitForServer.thenCompose { copilot.signIn() }.whenComplete { signIn, error ->
            post {
                when {
                    error != null -> status = "Copilot sign-in failed: ${error.cause?.message ?: error.message}"
                    signIn == null -> {
                        status = "Copilot is already signed in"
                        enableCopilot(true)
                    }
                    else -> dialog = WorkbenchDialog.CopilotSignInCode(signIn)
                }
            }
        }
    }

    fun continueCopilotSignIn(signIn: com.avfstudio.nativeide.language.CopilotSignIn) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("Copilot code", signIn.userCode))
        status = "Code copied; finish signing in on GitHub"
        if (signIn.command == null) openExternal(signIn.verificationUri ?: "https://github.com/login/device")
        copilot.finishSignIn(signIn).whenComplete { _, error ->
            post {
                if (error == null) {
                    if (dialog is WorkbenchDialog.CopilotSignInCode) dialog = null
                    status = "Copilot signed in"
                    enableCopilot(true)
                } else if (signIn.command == null) {
                    status = "Copilot sign-in failed: ${error.message}"
                }
            }
        }
    }

    fun abandonCopilotSignIn() {
        dialog = null
        if (!copilotEnabled) copilot.language.close()
    }

    private fun signOutCopilot() {
        copilot.signOut().whenComplete { _, error ->
            post {
                status = if (error == null) "Signed out of Copilot" else "Copilot sign-out failed: ${error.message}"
                if (error == null) enableCopilot(false)
            }
        }
    }

    fun clearInlineSuggestion() {
        scheduledInlineRequest?.cancel(false)
        if (inlineSuggestion != null) inlineSuggestion = null
    }

    private fun requestInlineSuggestion(invoked: Boolean) {
        val document = activeDocument ?: return
        val view = editorView ?: return
        if (!copilotEnabled || !copilot.language.isReady || document.path.startsWith("scratch:")) {
            if (invoked) status = "Copilot is not ready; run GitHub Copilot: Sign In"
            return
        }
        val cursor = view.cursor
        if (cursor.isSelected) return
        val version = document.version
        val position = LspPosition(cursor.leftLine, cursor.leftColumn)
        copilot.inlineCompletion(document.path, version, position, settings.tabSize, settings.insertSpaces, invoked)
            .whenComplete { suggestions, error ->
                post {
                    val current = editorView?.cursor
                    val stillCurrent = activePath == document.path && document.version == version &&
                        current != null && current.leftLine == position.line && current.leftColumn == position.character
                    val suggestion = suggestions?.firstOrNull { it.insertText.isNotBlank() }
                    when {
                        error != null -> if (invoked) status = "Copilot: ${error.cause?.message ?: error.message}"
                        stillCurrent && suggestion != null -> {
                            inlineSuggestion = document.path to suggestion
                            copilot.didShow(suggestion)
                        }
                        invoked -> status = "Copilot has no suggestion here"
                    }
                }
            }
    }

    private fun scheduleInlineSuggestion() {
        clearInlineSuggestion()
        if (!copilotEnabled || !copilot.language.isReady) return
        scheduledInlineRequest = timer.schedule(
            { post { requestInlineSuggestion(invoked = false) } },
            settings.inlineSuggestDelayMs.toLong(),
            TimeUnit.MILLISECONDS,
        )
    }

    /** The part of the suggestion after the cursor, which is what the editor shows as ghost text. */
    fun inlineGhostText(): String? {
        val (path, suggestion) = inlineSuggestion ?: return null
        val view = editorView ?: return null
        if (path != activePath) return null
        val cursor = view.cursor
        if (cursor.leftLine != suggestion.range.start.line) return suggestion.insertText
        val typed = view.text.getLineString(cursor.leftLine)
            .substring(suggestion.range.start.character.coerceAtMost(cursor.leftColumn), cursor.leftColumn)
        return if (suggestion.insertText.startsWith(typed)) suggestion.insertText.substring(typed.length) else suggestion.insertText
    }

    private fun acceptInlineSuggestion() {
        val (path, suggestion) = inlineSuggestion ?: return
        val view = editorView ?: return
        inlineSuggestion = null
        if (activePath != path) return
        val range = suggestion.range
        try {
            val endLine = range.end.line.coerceAtMost(view.lineCount - 1)
            val endColumn = range.end.character.coerceAtMost(view.text.getColumnCount(endLine))
            view.text.replace(range.start.line, range.start.character, endLine, endColumn, suggestion.insertText)
            val inserted = suggestion.insertText.split('\n')
            val caretLine = range.start.line + inserted.lastIndex
            val caretColumn = if (inserted.size == 1) range.start.character + inserted.last().length else inserted.last().length
            view.setSelection(caretLine, caretColumn)
            copilot.accepted(suggestion)
        } catch (error: RuntimeException) {
            status = "Could not apply the Copilot suggestion: ${error.message}"
        }
    }

    // Chat ------------------------------------------------------------------------------------

    private fun appendChat(event: AcpEvent) {
        when (event) {
            is AcpEvent.MessageChunk -> {
                val last = chatEntries.lastOrNull()
                if (last is ChatEntry.Agent) chatEntries[chatEntries.lastIndex] = last.copy(text = last.text + event.text)
                else chatEntries.add(ChatEntry.Agent(event.text))
            }
            is AcpEvent.ThoughtChunk -> {
                val last = chatEntries.lastOrNull()
                if (last is ChatEntry.Thought) chatEntries[chatEntries.lastIndex] = last.copy(text = last.text + event.text)
                else chatEntries.add(ChatEntry.Thought(event.text))
            }
            is AcpEvent.ToolCall -> {
                val index = chatEntries.indexOfLast { it is ChatEntry.Tool && it.id == event.id }
                if (index >= 0) {
                    val existing = chatEntries[index] as ChatEntry.Tool
                    chatEntries[index] = existing.copy(title = event.title ?: existing.title, status = event.status ?: existing.status)
                } else {
                    chatEntries.add(ChatEntry.Tool(event.id, event.title ?: event.kind ?: "Tool call", event.status))
                }
            }
            is AcpEvent.Plan -> chatEntries.add(ChatEntry.Plan(event.entries))
            is AcpEvent.PermissionRequest -> chatEntries.add(ChatEntry.Permission(event.title, event.options, event.respond))
            is AcpEvent.Commands -> Unit
        }
    }

    fun sendChat(text: String) {
        val workspace = workspaceRoot
        if (workspace == null || !vfs.isConnected()) {
            chatEntries.add(ChatEntry.Notice("Connect to a guest workspace first."))
            return
        }
        val references = chatAttachments.toList()
        chatAttachments.clear()
        chatEntries.add(ChatEntry.User(text, references))
        chatBusy = true
        scheduledChatStop?.cancel(false)
        val ready = when {
            chatAgent.hasSession -> CompletableFuture.completedFuture(Unit)
            chatAgent.isStarted -> chatAgent.newSession(workspace)
            else -> chatAgent.start(workspace).thenCompose { chatAgent.newSession(workspace) }
        }
        ready.thenCompose { chatAgent.prompt(text, references) }.whenComplete { stopReason, error ->
            post {
                chatBusy = false
                scheduleChatStop()
                chatAuthMethods = chatAgent.authMethods.takeIf { !chatAgent.hasSession }.orEmpty()
                val cause = (error as? CompletionException)?.cause ?: error
                when {
                    cause is JsonRpcException && cause.code == -32000 -> chatEntries.add(
                        ChatEntry.Notice(
                            "Copilot chat needs its own GitHub sign-in (separate from inline suggestions). " +
                                "Tap “Sign in” to run `copilot login` in the terminal, then send again.",
                        ),
                    )
                    cause != null -> chatEntries.add(ChatEntry.Notice("Agent error: ${cause.message}"))
                    stopReason != "end_turn" -> chatEntries.add(ChatEntry.Notice("Turn ended: $stopReason"))
                }
            }
        }
    }

    /** The agent holds about 300 MB in the guest; end it after the configured idle time. */
    private fun scheduleChatStop() {
        scheduledChatStop?.cancel(false)
        val minutes = settings.chatIdleMinutes
        scheduledChatStop = timer.schedule(
            {
                post {
                    if (!chatBusy && chatAgent.isStarted) {
                        chatAgent.stop()
                        chatEntries.add(ChatEntry.Notice("Agent stopped after $minutes idle minutes; the next message starts a new session."))
                    }
                }
            },
            minutes.toLong(),
            TimeUnit.MINUTES,
        )
    }

    fun cancelChat() = chatAgent.cancel()

    fun endChatSession() {
        chatAgent.stop()
        chatBusy = false
        chatEntries.add(ChatEntry.Notice("Agent session ended"))
    }

    // Chat context ------------------------------------------------------------------------------

    private fun attach(reference: ChatReference) {
        chatAttachments.removeAll { it.path == reference.path && it.startLine == reference.startLine && it.detail == reference.detail }
        chatAttachments.add(reference)
        chatVisible = true
        status = "Added ${reference.label} to Copilot chat"
    }

    fun attachFileToChat(path: String? = activePath) {
        if (path == null || path.startsWith("scratch:")) {
            status = "Save the file to the guest before adding it to chat"
            return
        }
        attach(ChatReference(path))
    }

    /** The editor selection (or the cursor's line), or the selected notebook cell, as an excerpt. */
    fun attachSelectionToChat() {
        val path = activePath?.takeUnless { it.startsWith("scratch:") } ?: run {
            status = "Save the file to the guest before adding it to chat"
            return
        }
        activeNotebook?.let { session ->
            val index = session.notebook.cells.indexOfFirst { it.id == session.selectedCell }
            val cell = session.notebook.cells.getOrNull(index) ?: return
            attach(ChatReference(path, text = cell.source, detail = "cell ${index + 1}"))
            return
        }
        val view = editorView ?: return
        val range = view.cursorRange
        val text = view.text.toString()
        val hasSelection = range.startIndex != range.endIndex
        val firstLine = range.start.line
        val lastLine = if (hasSelection && range.end.line > firstLine && range.end.column == 0) range.end.line - 1 else range.end.line
        val excerpt = if (hasSelection) {
            text.substring(range.startIndex, range.endIndex)
        } else {
            view.text.getLineString(firstLine)
        }
        attach(ChatReference(path, firstLine + 1, lastLine + 1, excerpt))
    }

    fun pickFileForChat() {
        if (!vfs.isConnected()) {
            status = "Connect to a guest to attach files"
            return
        }
        quickOpenFilter = ""
        quickOpenAttachesToChat = true
        dialog = WorkbenchDialog.QuickOpen
    }

    fun removeChatAttachment(reference: ChatReference) {
        chatAttachments.remove(reference)
    }

    /** Copilot CLI keeps its own credentials, so chat sign-in runs `copilot login` in a terminal
     *  and lifts its device code into the chat pane. */
    fun signInChatInTerminal() {
        chatAgent.stop()
        chatAuthMethods = emptyList()
        val output = StringBuilder()
        var prompted = false
        chatLoginTerminal = newTerminal(
            COPILOT_LOGIN_COMMAND,
            onOutput = { text ->
                if (!prompted) {
                    output.append(text)
                    parseDeviceCodePrompt(output.toString())?.let { prompt ->
                        prompted = true
                        post { chatEntries.add(ChatEntry.DeviceCode(prompt.url, prompt.code)) }
                    } ?: run { if (output.length > 16_384) output.delete(0, output.length - 4_096) }
                }
            },
            onExit = { code ->
                post {
                    if (code == 0) finishChatSignIn(true, "Signed in. Send your message again.")
                    else finishChatSignIn(false, "Sign-in did not finish; the terminal shows why.")
                }
            },
        )
        if (chatLoginTerminal != null) chatEntries.add(ChatEntry.Notice("Starting `copilot login` in the terminal..."))
    }

    fun openDeviceLogin(entry: ChatEntry.DeviceCode) {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("GitHub code", entry.code))
        openLink(entry.url)
    }

    fun cancelChatSignIn() {
        chatLoginTerminal?.let(::closeTerminal)
    }

    private fun finishChatSignIn(success: Boolean, notice: String) {
        if (chatLoginTerminal == null) return
        chatLoginTerminal = null
        val index = chatEntries.indexOfLast { it is ChatEntry.DeviceCode }
        if (success && index >= 0) {
            chatEntries[index] = (chatEntries[index] as ChatEntry.DeviceCode).copy(done = true)
        } else if (index >= 0) {
            chatEntries.removeAt(index)
        }
        chatEntries.add(ChatEntry.Notice(notice))
    }

    fun answerPermission(entry: ChatEntry.Permission, optionId: String?) {
        entry.respond(optionId)
        val index = chatEntries.indexOf(entry)
        if (index >= 0) {
            chatEntries[index] = entry.copy(answer = entry.options.firstOrNull { it.id == optionId }?.name ?: "Cancelled")
        }
    }

    // Terminals -------------------------------------------------------------------------------

    /** Opens a guest shell in the panel; [command] is typed into it once the shell is up.
     *  [onOutput] sees the decoded output (on the socket thread) and [onExit] the shell's exit code. */
    fun newTerminal(
        command: String? = null,
        onOutput: ((String) -> Unit)? = null,
        onExit: ((Int?) -> Unit)? = null,
    ): UUID? {
        if (!vfs.isConnected()) {
            status = "Connect to a guest to open a terminal"
            return null
        }
        val id = UUID.randomUUID()
        val session = createTerminalSession(id, "bash")
        vfs.addPtyListener(id) { event ->
            onPtyEvent(session, event, command)
            when (event) {
                is PtyEvent.Output -> onOutput?.invoke(String(event.data, Charsets.UTF_8))
                is PtyEvent.Exit -> onExit?.invoke(event.code)
                else -> Unit
            }
        }
        if (!vfs.ptySpawn(id, workspaceRoot, 120, 32)) {
            status = "Could not start a terminal"
            vfs.removePtyListener(id)
            session.emulator.close()
            return null
        }
        terminals.add(session)
        showTerminal(id)
        return id
    }

    private fun createTerminalSession(id: UUID, title: String): TerminalSession {
        lateinit var session: TerminalSession
        val emulator = TerminalEmulatorFactory.create(
            initialRows = 32,
            initialCols = 120,
            defaultForeground = Color.White,
            defaultBackground = Color.Black,
            onKeyboardInput = { bytes -> vfs.ptyInput(id, bytes) },
            onResize = { dimensions ->
                if (dimensions.columns > 0 && dimensions.rows > 0) vfs.ptyResize(id, dimensions.columns, dimensions.rows)
            },
            onClipboardCopy = { text ->
                post {
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText("Terminal", text))
                }
            },
            autoDetectUrls = true,
        )
        session = TerminalSession(id, emulator, title)
        return session
    }

    private fun onPtyEvent(session: TerminalSession, event: PtyEvent, initialCommand: String?) {
        when (event) {
            is PtyEvent.Output -> session.emulator.writeInput(event.data, 0, event.data.size)
            is PtyEvent.Spawned -> if (initialCommand != null) {
                timer.schedule({ vfs.ptyInput(session.id, "$initialCommand\r".toByteArray()) }, 400, TimeUnit.MILLISECONDS)
            }
            is PtyEvent.Exit -> post {
                session.exited = true
                session.exitCode = event.code
                session.emulator.writeInput("\r\n[process exited with code ${event.code ?: "?"}]\r\n".toByteArray())
            }
            is PtyEvent.Error -> session.emulator.writeInput("\r\n[terminal error: ${event.message}]\r\n".toByteArray())
        }
    }

    /** Reconnects the panel to shells the guest kept running, including ones from an earlier app session. */
    private fun reattachTerminals() {
        vfs.guestPtySessions.forEach { info ->
            val session = terminals.firstOrNull { it.id == info.sessionId }
                ?: createTerminalSession(info.sessionId, info.cwd.substringAfterLast('/').ifEmpty { "bash" })
                    .also(terminals::add)
            session.emulator.clearScreen()
            vfs.addPtyListener(session.id) { event -> onPtyEvent(session, event, null) }
            vfs.ptyReattach(session.id)
        }
        terminals.filter { session -> vfs.guestPtySessions.none { it.sessionId == session.id } && !session.exited }
            .forEach { it.exited = true }
        if (activeTerminalId == null) activeTerminalId = terminals.firstOrNull()?.id
    }

    fun showTerminal(id: UUID? = activeTerminalId) {
        activeTerminalId = id ?: terminals.firstOrNull()?.id
        panelVisible = true
        panelTab = PanelTab.Terminal
        compactDestination = CompactDestination.Terminal
    }

    fun closeTerminal(id: UUID) {
        val session = terminals.firstOrNull { it.id == id } ?: return
        if (!session.exited) vfs.ptyKill(id)
        vfs.removePtyListener(id)
        session.emulator.close()
        terminals.remove(session)
        if (activeTerminalId == id) activeTerminalId = terminals.lastOrNull()?.id
        if (id == chatLoginTerminal) finishChatSignIn(false, "Sign-in cancelled.")
    }

    private fun toggleTerminal() {
        if (panelVisible && panelTab == PanelTab.Terminal) {
            panelVisible = false
            return
        }
        if (terminals.isEmpty()) newTerminal() else showTerminal()
    }

    private fun runActiveFile() {
        val document = activeDocument ?: return
        if (document.path.startsWith("scratch:")) {
            status = "Save the file to the guest workspace before running it"
            return
        }
        val command = when (document.languageId) {
            "py" -> "python3 '${document.path}'"
            "sh" -> "bash '${document.path}'"
            "js", "mjs" -> "node '${document.path}'"
            else -> {
                status = "No run command for .${document.languageId} files"
                return
            }
        }
        saveActiveDocument()
        val target = activeTerminal?.takeUnless { it.exited }
        if (target == null) newTerminal(command) else {
            vfs.ptyInput(target.id, "$command\r".toByteArray())
            showTerminal(target.id)
        }
    }

    // Notebooks -------------------------------------------------------------------------------

    private fun openNotebook(document: DocumentSession): Boolean {
        val notebook = try {
            NotebookDocument.parse(document.path, document.text)
        } catch (error: RuntimeException) {
            status = "Could not open notebook: ${error.message}"
            return false
        }
        val kernel = KernelClient(vfs)
        val session = NotebookSession(document, notebook, kernel)
        kernel.outputListener = { cellId, output -> post { appendCellOutput(session, cellId, output) } }
        kernel.clearListener = { cellId, _ -> post { notebook.cells.firstOrNull { it.id == cellId }?.outputs?.clear() } }
        kernel.busyListener = { busy -> post { session.busy = busy } }
        kernel.exitListener = { message ->
            post {
                session.kernelState = message
                session.kernelName = null
                notebook.cells.forEach { it.running = false; it.queued = false }
            }
        }
        notebooks[document.path] = session
        return true
    }

    private fun appendCellOutput(session: NotebookSession, cellId: String, output: JsonObject) {
        val cell = session.notebook.cells.firstOrNull { it.id == cellId } ?: return
        val last = cell.outputs.lastOrNull()
        // Merge consecutive stream chunks the way Jupyter front ends do.
        if (output.get("output_type")?.asString == "stream" && last?.get("output_type")?.asString == "stream" &&
            last.get("name")?.asString == output.get("name")?.asString
        ) {
            val merged = last.deepCopy()
            merged.addProperty(
                "text",
                NotebookDocument.multiline(last.get("text")) + NotebookDocument.multiline(output.get("text")),
            )
            cell.outputs[cell.outputs.lastIndex] = merged
        } else {
            cell.outputs.add(output)
        }
        session.notebook.dirty = true
    }

    /** Lists kernels from the guest (conda, venvs, system Python with ipykernel) and asks which to use. */
    /** Finds kernels; starts [preferredSpec] (or the notebook's own kernel) directly when it exists. */
    fun chooseKernel(session: NotebookSession, preferredSpec: String? = null) {
        val directory = session.document.path.substringBeforeLast('/')
        session.kernelState = "Finding kernels…"
        val launched = if (session.kernel.isStarted) CompletableFuture.completedFuture(Unit) else session.kernel.launch(directory)
        launched.thenCompose { session.kernel.kernelSpecs() }.whenComplete { discovery, error ->
            post {
                if (error != null) {
                    session.kernelState = "Kernel bridge unavailable"
                    status = "Jupyter: ${error.cause?.message ?: error.message}. Install it with `avf-native-daemon tools install jupyter`."
                    return@post
                }
                val specs = discovery.specs
                if (specs.isEmpty() && discovery.installable.isEmpty()) {
                    session.kernelState = "No kernels found"
                    status = "No Python environment with ipykernel was found in the guest"
                    return@post
                }
                session.kernelState = if (specs.isEmpty()) "No kernels yet" else "No kernel"
                val preferred = preferredSpec ?: session.notebook.kernelName ?: settings.jupyterDefaultKernel
                val match = specs.firstOrNull { it.name == preferred }
                if (match != null && session.kernelName == null) startKernel(session, match) else {
                    dialog = WorkbenchDialog.KernelPicker(session.document.path, specs, discovery.installable)
                }
            }
        }
    }

    fun startKernel(session: NotebookSession, spec: com.avfstudio.nativeide.notebook.KernelSpec) {
        if (dialog is WorkbenchDialog.KernelPicker) dialog = null
        val directory = session.document.path.substringBeforeLast('/')
        session.kernelState = "Starting ${spec.displayName}…"
        val ready = if (session.kernelName != null) {
            // Switching kernels: restart the bridge so it owns exactly one kernel.
            session.kernel.stop()
            CompletableFuture.supplyAsync({ Unit }, CompletableFuture.delayedExecutor(1, TimeUnit.SECONDS))
                .thenCompose { session.kernel.launch(directory) }
        } else {
            CompletableFuture.completedFuture(Unit)
        }
        ready.thenCompose { session.kernel.startKernel(spec.name, directory) }.whenComplete { _, error ->
            post {
                if (error != null) {
                    session.kernelState = "Kernel failed to start"
                    status = "Kernel ${spec.displayName}: ${error.cause?.message ?: error.message}"
                } else {
                    session.kernelName = spec.displayName
                    session.kernelState = "Idle"
                    session.focusRequest++
                    if (session.notebook.kernelName != spec.name) session.notebook.setKernel(spec.name, spec.displayName, spec.language)
                    documentRevision++
                    pendingExecution.remove(session.document.path)?.forEach { pendingCell ->
                        pendingCell.queued = false
                        runCell(session, pendingCell)
                    }
                }
            }
        }
    }

    private val pendingExecution = mutableMapOf<String, MutableList<NotebookCell>>()

    /**
     * Installs ipykernel into a guest environment in a visible terminal, so nothing changes
     * silently. When pip succeeds the terminal closes its shell and the new kernel starts.
     */
    fun installKernelPackage(session: NotebookSession, candidate: com.avfstudio.nativeide.notebook.InstallableKernel) {
        dialog = null
        session.notebook.cells.forEach { it.queued = false }
        pendingExecution.remove(session.document.path)
        newTerminal("'${candidate.python}' -m pip install ipykernel && exit", onExit = { code ->
            post {
                if (code == 0) {
                    chooseKernel(session, generatedKernelName(candidate.label))
                } else {
                    session.kernelState = "ipykernel install failed"
                    status = "pip could not install ipykernel into ${candidate.label}; the terminal shows why"
                }
            }
        })
        session.kernelState = "Installing ipykernel…"
        status = "Installing ipykernel into ${candidate.label}…"
    }

    fun runCell(session: NotebookSession, cell: NotebookCell, advance: Boolean = false) {
        if (advance) selectNextCell(session, cell, insertIfLast = true)
        if (cell.type != CellType.Code || cell.running || cell.queued) return
        if (session.kernelName == null) {
            val pending = pendingExecution.getOrPut(session.document.path) { mutableListOf() }
            val firstRequest = pending.isEmpty()
            pending.add(cell)
            cell.queued = true
            if (firstRequest) chooseKernel(session)
            return
        }
        cell.queued = true
        cell.outputs.clear()
        session.notebook.dirty = true
        documentRevision++
        session.kernel.execute(cell.id, cell.source).whenComplete { reply, error ->
            post {
                cell.queued = false
                cell.running = false
                if (error != null) {
                    status = "Execution failed: ${error.cause?.message ?: error.message}"
                } else {
                    cell.executionCount = reply.executionCount
                }
                session.kernelState = "Idle"
                documentRevision++
            }
        }
        cell.running = true
        session.kernelState = "Busy"
    }

    fun runAllCells(session: NotebookSession) = session.notebook.cells.forEach { runCell(session, it) }

    fun interruptKernel(session: NotebookSession) {
        session.kernel.interrupt().whenComplete { _, error -> post { if (error != null) status = "Interrupt failed: ${error.message}" } }
    }

    fun restartKernel(session: NotebookSession) {
        session.kernelState = "Restarting…"
        session.kernel.restart().whenComplete { _, error ->
            post {
                session.kernelState = if (error == null) "Idle" else "Restart failed"
                session.notebook.cells.forEach { it.executionCount = null }
            }
        }
    }

    fun clearAllOutputs(session: NotebookSession) {
        session.notebook.cells.forEach { it.outputs.clear(); it.executionCount = null }
        session.notebook.dirty = true
        documentRevision++
    }

    fun addCell(session: NotebookSession, after: NotebookCell?, type: CellType = CellType.Code) {
        val cell = NotebookCell.empty(type)
        val index = after?.let { session.notebook.cells.indexOf(it) + 1 } ?: session.notebook.cells.size
        session.notebook.cells.add(index.coerceIn(0, session.notebook.cells.size), cell)
        session.selectedCell = cell.id
        session.notebook.dirty = true
        documentRevision++
    }

    fun deleteCell(session: NotebookSession, cell: NotebookCell) {
        val index = session.notebook.cells.indexOf(cell)
        session.notebook.cells.remove(cell)
        if (session.notebook.cells.isEmpty()) session.notebook.cells.add(NotebookCell.empty())
        session.selectedCell = session.notebook.cells.getOrNull(index.coerceAtMost(session.notebook.cells.lastIndex))?.id
        session.notebook.dirty = true
        documentRevision++
    }

    /** Selects and focuses the cell [delta] away from [cell]; false at either end of the notebook. */
    fun selectAdjacentCell(session: NotebookSession, cell: NotebookCell, delta: Int): Boolean {
        val cells = session.notebook.cells
        val target = cells.getOrNull(cells.indexOf(cell) + delta) ?: return false
        session.selectedCell = target.id
        session.caretAtEnd = delta < 0
        session.focusRequest++
        return true
    }

    fun moveCell(session: NotebookSession, cell: NotebookCell, delta: Int) {
        val cells = session.notebook.cells
        val index = cells.indexOf(cell)
        val target = index + delta
        if (index < 0 || target !in cells.indices) return
        cells.removeAt(index)
        cells.add(target, cell)
        session.notebook.dirty = true
        documentRevision++
    }

    fun onCellEdited(session: NotebookSession) {
        session.notebook.dirty = true
        documentRevision++
    }

    private fun selectNextCell(session: NotebookSession, cell: NotebookCell, insertIfLast: Boolean) {
        val cells = session.notebook.cells
        val index = cells.indexOf(cell)
        if (index == cells.lastIndex && insertIfLast) addCell(session, cell) else {
            session.selectedCell = cells.getOrNull(index + 1)?.id ?: cell.id
        }
    }

    fun saveNotebook(session: NotebookSession) {
        session.document.updateText(session.notebook.toJson())
        status = "Saving ${session.document.path}"
        repository.save(session.document).whenComplete { result, error ->
            post {
                when {
                    error != null -> status = "Save failed: ${error.cause?.message ?: error.message}"
                    result is SaveResult.Saved -> {
                        session.notebook.dirty = false
                        status = "Saved ${session.document.path}"
                    }
                    result is SaveResult.QueuedOffline -> status = "Saved locally; queued until guest reconnects"
                    result is SaveResult.Conflict -> {
                        dialog = WorkbenchDialog.Conflict(session.document)
                        status = "Save conflict: remote notebook changed"
                    }
                }
                documentRevision++
            }
        }
    }

    // Asset packs and themes ------------------------------------------------------------------

    fun installAssetPack(uri: Uri, mode: String) {
        status = "Installing asset pack…"
        io.execute {
            try {
                val importer = VsCodeThemeImporter()
                val installed: InstalledAssetPack?
                val theme: ImportedTheme?
                if (mode == "theme") {
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
                theme?.semanticTokenColors?.values?.forEach(::parseThemeColor)
                post {
                    if (theme != null) {
                        saveImportedTheme(context, theme)
                        loadTextMateTheme(theme.textMateThemeJson)
                        editorThemeRevision++
                        status = if (installed == null) "Applied syntax theme ${theme.name}" else {
                            "Installed ${installed.displayName} and applied syntax theme ${theme.name}"
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

    fun clearImportedTheme() {
        clearSavedImportedTheme(context)
        editorThemeRevision++
        status = "Syntax colours follow the app theme again"
    }

    /** Bumped when the syntax theme changes so open editors re-apply their colour scheme. */
    var editorThemeRevision by mutableIntStateOf(0)
        private set

    // Commands and keys -------------------------------------------------------------------------

    /**
     * Handles a hardware key press. Both the root composable and the code editor call this, so a
     * key seen twice (same down time and key code) is consumed but not run again.
     */
    /**
     * Window-level entry point (see [KeyRouter]): runs before the IME and regardless of which view
     * has focus. In the code editor, plain Tab/Enter/Escape stay with the editor (completion
     * popup, inline suggestions); in a terminal, unbound Ctrl+letter goes to the shell as a
     * control byte so an IME can't swallow Ctrl+C.
     */
    fun routeHardwareKey(event: AndroidKeyEvent): Boolean {
        if (dialog != null) return false
        if (event.action == AndroidKeyEvent.ACTION_UP) return consumedKeyDowns.remove(event.keyCode)
        if (event.action != AndroidKeyEvent.ACTION_DOWN) return false
        val modified = event.isCtrlPressed || event.isAltPressed
        if (focusArea == FocusArea.Editor && !modified &&
            event.keyCode in setOf(AndroidKeyEvent.KEYCODE_TAB, AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_ESCAPE)
        ) {
            return false
        }
        val handled = handleKeyDown(event) || sendTerminalControlKey(event)
        if (handled) consumedKeyDowns += event.keyCode
        if (modified) android.util.Log.d("AVFKEY", "route ${AndroidKeyEvent.keyCodeToString(event.keyCode)} meta=${event.metaState} dev=${event.deviceId} focus=$focusArea handled=$handled")
        return handled
    }

    private fun sendTerminalControlKey(event: AndroidKeyEvent): Boolean {
        if (focusArea != FocusArea.Terminal || !event.isCtrlPressed || event.isAltPressed || event.isShiftPressed) return false
        val code = when (event.keyCode) {
            in AndroidKeyEvent.KEYCODE_A..AndroidKeyEvent.KEYCODE_Z -> event.keyCode - AndroidKeyEvent.KEYCODE_A + 1
            AndroidKeyEvent.KEYCODE_LEFT_BRACKET -> 27
            AndroidKeyEvent.KEYCODE_BACKSLASH -> 28
            AndroidKeyEvent.KEYCODE_RIGHT_BRACKET -> 29
            else -> return false
        }
        val terminal = terminals.firstOrNull { it.id == activeTerminalId && !it.exited } ?: return false
        if (event.repeatCount == 0 || code != 3) vfs.ptyInput(terminal.id, byteArrayOf(code.toByte()))
        return true
    }

    fun handleKeyDown(event: AndroidKeyEvent): Boolean {
        if (event.action != AndroidKeyEvent.ACTION_DOWN) return false
        val identity = event.downTime to event.keyCode
        if (identity == lastHandledKey) return true
        val dispatcher = when {
            focusArea == FocusArea.Editor -> editorDispatcher
            activeNotebook != null && focusArea in setOf(FocusArea.Notebook, FocusArea.Other) -> notebookDispatcher
            else -> globalDispatcher
        }
        val stroke = editorKeyStroke(
            event.keyCode,
            event.isCtrlPressed,
            event.isShiftPressed,
            event.isAltPressed,
            dispatcher.isAwaitingChord,
        ) ?: return false
        if (stroke.key == "TAB" && !stroke.ctrl && !stroke.alt && focusArea != FocusArea.Editor) return false
        val command = dispatcher.dispatch(stroke)
        if (command == null) {
            if (dispatcher.isAwaitingChord) lastHandledKey = identity
            return dispatcher.isAwaitingChord
        }
        lastHandledKey = identity
        runCommand(command)
        return true
    }

    fun runCommand(commandId: String) {
        val view = editorView
        when (commandId) {
            "workbench.action.files.save" -> saveActiveDocument()
            "workbench.action.files.newUntitledFile" -> newUntitledFile()
            "workbench.action.files.saveAs" -> beginSaveAs()
            "explorer.newFile" -> beginNewEntry(folder = false)
            "explorer.newFolder" -> beginNewEntry(folder = true)
            "renameFile" -> beginRenameEntry()
            "deleteFile" -> beginDeleteEntry()
            "workbench.action.files.openFile", "workbench.action.quickOpen" -> {
                quickOpenFilter = ""
                dialog = WorkbenchDialog.QuickOpen
            }
            "workbench.action.showCommands" -> {
                commandFilter = ""
                dialog = WorkbenchDialog.Commands
            }
            "workbench.action.closeActiveEditor" -> activePath?.let(::closeDocument)
            "workbench.action.closeOtherEditors" -> activePath?.let { path ->
                documents.filter { it.path != path }.map { it.path }.forEach(::closeDocument)
            }
            "workbench.action.reopenClosedEditor" -> closedDocuments.removeLastOrNull()?.let { document ->
                if (document.path.endsWith(".ipynb")) openPath(document.path) else {
                    documents.add(document)
                    selectDocument(document.path)
                    notifyDocumentOpened(document)
                }
            }
            "workbench.action.quickSwitchEditor", "workbench.action.nextEditor" -> cycleEditor(1)
            "workbench.action.previousEditor" -> cycleEditor(-1)
            "workbench.action.toggleSidebarVisibility" -> sidebar = if (sidebar == null) SidebarView.Explorer else null
            "workbench.action.togglePanel" -> panelVisible = !panelVisible
            "workbench.view.explorer" -> toggleSidebar(SidebarView.Explorer)
            "workbench.actions.view.problems" -> {
                panelVisible = true
                panelTab = PanelTab.Problems
            }
            "workbench.action.gotoSymbol" -> toggleSidebar(SidebarView.Outline)
            "workbench.action.output.toggleOutput" -> {
                panelVisible = !(panelVisible && panelTab == PanelTab.Output)
                panelTab = PanelTab.Output
            }
            "workbench.action.terminal.toggleTerminal" -> toggleTerminal()
            "workbench.action.terminal.new" -> newTerminal()
            "workbench.action.chat.open" -> {
                chatVisible = !chatVisible
                if (chatVisible) compactDestination = CompactDestination.Chat
            }
            "workbench.action.openSettings" -> settingsVisible = true
            "workbench.action.openSettingsJson" -> openSettingsJson()
            "workbench.action.openGlobalKeybindings" -> dialog = WorkbenchDialog.Keybindings
            "workbench.action.debug.run" -> runActiveFile()
            "editor.action.fontZoomIn" -> zoomEditor(1f)
            "editor.action.fontZoomOut" -> zoomEditor(-1f)
            "workbench.action.connect" -> dialog = WorkbenchDialog.Connect
            "undo" -> view?.undo()
            "redo" -> view?.redo()
            "editor.action.clipboardCopyAction" -> copySelection(false)
            "editor.action.clipboardCutAction" -> copySelection(true)
            "editor.action.clipboardPasteAction" -> pasteClipboard()
            "editor.action.selectAll" -> view?.let {
                val lastLine = (it.lineCount - 1).coerceAtLeast(0)
                it.setSelectionRegion(0, 0, lastLine, it.text.getColumnCount(lastLine))
            }
            "editor.action.indentLines" -> view?.indentSelection()
            "editor.action.outdentLines" -> view?.unindentSelection()
            "editor.action.commentLine" -> toggleLineComment()
            "editor.action.blockComment" -> toggleBlockComment()
            "editor.action.marker.next" -> goToProblem(forward = true)
            "editor.action.marker.prev" -> goToProblem(forward = false)
            "cursorUndo" -> undoCursorMove()
            "editor.action.moveLinesUpAction",
            "editor.action.moveLinesDownAction",
            "editor.action.copyLinesUpAction",
            "editor.action.copyLinesDownAction",
            "editor.action.deleteLines",
            "editor.action.insertLineAfter",
            "editor.action.insertLineBefore",
            -> activeDocument?.let { document ->
                if (view != null && editCurrentLine(document, view, commandId)) onDocumentEdited(document)
            }
            "actions.find", "editor.action.startFindReplaceAction" -> {
                replaceQuery = ""
                dialog = WorkbenchDialog.Find
            }
            "editor.action.nextMatchFindAction" -> findInEditor(true)
            "editor.action.previousMatchFindAction" -> findInEditor(false)
            "workbench.action.gotoLine" -> {
                gotoLine = ""
                dialog = WorkbenchDialog.GotoLine
            }
            "editor.action.formatDocument" -> formatActiveDocument()
            "editor.action.goToDefinition", "editor.action.peekDefinition" -> goToDefinition()
            "editor.action.goToReferences" -> findReferences()
            "editor.action.rename" -> beginRename()
            "editor.action.triggerSuggest" -> showCompletion()
            "editor.action.showHover" -> showHover()
            "editor.action.quickFix" -> showCodeActions()
            "editor.action.inlineSuggest.trigger" -> requestInlineSuggestion(invoked = true)
            "editor.action.inlineSuggest.commit" -> acceptInlineSuggestion()
            "editor.action.inlineSuggest.hide" -> clearInlineSuggestion()
            "github.copilot.signIn" -> beginCopilotSignIn()
            "github.copilot.signOut" -> signOutCopilot()
            "github.copilot.toggleInlineSuggestions" -> {
                enableCopilot(!copilotEnabled)
                status = if (copilotEnabled) "Copilot inline suggestions on" else "Copilot inline suggestions off"
            }
            "github.copilot.chat.signIn" -> signInChatInTerminal()
            "github.copilot.chat.attachFile" -> attachFileToChat()
            "github.copilot.chat.attachSelection" -> attachSelectionToChat()
            "notebook.cell.executeAndSelectBelow", "notebook.cell.execute", "notebook.cell.executeAndInsertBelow" ->
                activeNotebook?.let { session ->
                    val cell = session.notebook.cells.firstOrNull { it.id == session.selectedCell } ?: return@let
                    when (commandId) {
                        "notebook.cell.executeAndSelectBelow" -> runCell(session, cell, advance = true)
                        "notebook.cell.execute" -> runCell(session, cell)
                        else -> {
                            runCell(session, cell)
                            addCell(session, cell)
                        }
                    }
                }
            "notebook.cell.insertCodeCellBelow" -> activeNotebook?.let { session ->
                addCell(session, session.notebook.cells.firstOrNull { it.id == session.selectedCell })
            }
            "notebook.focusPreviousEditor", "notebook.focusNextEditor" -> activeNotebook?.let { session ->
                val cell = session.notebook.cells.firstOrNull { it.id == session.selectedCell } ?: return@let
                selectAdjacentCell(session, cell, if (commandId == "notebook.focusNextEditor") 1 else -1)
            }
            "jupyter.selectKernel" -> activeNotebook?.let(::chooseKernel)
            "jupyter.runAll" -> activeNotebook?.let(::runAllCells)
            "jupyter.restartKernel" -> activeNotebook?.let(::restartKernel)
            "jupyter.interruptKernel" -> activeNotebook?.let(::interruptKernel)
            "editor.action.jumpToBracket" -> {
                val matched = activeDocument?.let { document -> view?.let { jumpToMatchingBracket(document, it) } } ?: false
                if (!matched) status = "No matching bracket"
            }
            "editor.action.addSelectionToNextFindMatch",
            "editor.action.selectHighlights",
            "editor.action.insertCursorAbove",
            "editor.action.insertCursorBelow",
            -> status = "Multi-cursor is not available in the pinned Sora editor yet"
            "editor.action.toggleWordWrap" -> updateSetting(WorkbenchSettings.WORD_WRAP, if (settings.wordWrap) "off" else "on")
            else -> status = "${EditorCommands.all.firstOrNull { it.id == commandId }?.title ?: commandId} is not available yet"
        }
    }

    private fun toggleSidebar(view: SidebarView) {
        sidebar = if (sidebar == view) null else view
    }

    private fun cycleEditor(delta: Int) {
        if (documents.size < 2) return
        val index = documents.indexOfFirst { it.path == activePath }.coerceAtLeast(0)
        selectDocument(documents[(index + delta).mod(documents.size)].path)
    }
}
