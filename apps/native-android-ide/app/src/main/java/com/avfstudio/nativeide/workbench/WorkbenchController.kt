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
import android.os.SystemClock
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
import com.avfstudio.nativeide.ai.GITHUB_SIGN_IN_COMMAND
import com.avfstudio.nativeide.ai.stripAnsi
import com.avfstudio.nativeide.ai.ChatAgentKind
import com.avfstudio.nativeide.ai.parseSignInLink
import com.avfstudio.nativeide.document.ContentResolverFileStore
import com.avfstudio.nativeide.document.devicePath
import com.avfstudio.nativeide.document.isDevicePath
import com.avfstudio.nativeide.document.isGuestPath
import com.avfstudio.nativeide.language.endOfLineHints
import com.avfstudio.nativeide.language.parseInlayHints
import com.avfstudio.nativeide.ai.parseDeviceCodePrompt
import com.avfstudio.nativeide.assets.AssetPackGrammarLoader
import com.avfstudio.nativeide.assets.AssetPackInstaller
import com.avfstudio.nativeide.assets.LANGUAGE_PACKS
import com.avfstudio.nativeide.assets.LanguagePack
import com.avfstudio.nativeide.assets.languagePackFor
import com.avfstudio.nativeide.assets.ImportedTheme
import com.avfstudio.nativeide.assets.InstalledAssetPack
import com.avfstudio.nativeide.assets.VsCodeThemeImporter
import com.avfstudio.nativeide.debug.DapClient
import com.avfstudio.nativeide.debug.DebugConsoleLine
import com.avfstudio.nativeide.debug.DebugFrame
import com.avfstudio.nativeide.debug.appendDebugOutput
import com.avfstudio.nativeide.debug.DebugState
import com.avfstudio.nativeide.debug.DebugVariable
import com.avfstudio.nativeide.debug.dapInitializeArguments
import com.avfstudio.nativeide.debug.parseScopes
import com.avfstudio.nativeide.debug.parseStackFrames
import com.avfstudio.nativeide.debug.parseVariables
import com.avfstudio.nativeide.debug.pythonLaunchArguments
import com.avfstudio.nativeide.debug.setBreakpointsArguments
import androidx.compose.runtime.mutableStateMapOf
import com.avfstudio.nativeide.document.DocumentRepository
import com.avfstudio.nativeide.document.DocumentSession
import com.avfstudio.nativeide.document.EncryptedDocumentCache
import com.avfstudio.nativeide.document.OpenDocument
import com.avfstudio.nativeide.document.PagedDocument
import com.avfstudio.nativeide.document.SaveResult
import com.avfstudio.nativeide.editor.EditorEvent
import com.avfstudio.nativeide.editor.LspPosition
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.avfstudio.nativeide.editor.LspRange
import com.avfstudio.nativeide.editor.LspTextEdit
import com.avfstudio.nativeide.editor.SoraTextEditorHost
import com.avfstudio.nativeide.editor.applyLspTextEdits
import com.avfstudio.nativeide.editor.endAfterInsert
import com.avfstudio.nativeide.language.CopilotClient
import com.avfstudio.nativeide.language.CopilotSuggestion
import com.avfstudio.nativeide.language.JsonRpcException
import com.avfstudio.nativeide.language.LanguageClient
import com.avfstudio.nativeide.language.LanguageDiagnostic
import com.avfstudio.nativeide.language.SettingsResolver
import com.avfstudio.nativeide.editor.EditorConfig
import com.avfstudio.nativeide.editor.EditorSelection
import com.avfstudio.nativeide.editor.EditorSurface
import com.avfstudio.nativeide.editor.LineMarks
import com.avfstudio.nativeide.editor.ProblemMark
import com.avfstudio.nativeide.editor.editorProblems
import com.avfstudio.nativeide.editor.problemLines
import com.avfstudio.nativeide.editor.problemMarks
import com.avfstudio.nativeide.editor.selectionStart
import com.avfstudio.nativeide.language.lspDocumentUri
import com.avfstudio.nativeide.language.untitledPathFor
import com.avfstudio.nativeide.editor.saveCleanupEdits
import com.avfstudio.nativeide.editor.SaveCleanups
import com.avfstudio.nativeide.language.signatureContext
import com.avfstudio.nativeide.language.parseDocumentHighlights
import com.avfstudio.nativeide.language.parseSignatureHelp
import com.avfstudio.nativeide.language.SignatureHint
import com.avfstudio.nativeide.notebook.CellRun
import com.avfstudio.nativeide.notebook.clearTiming
import com.avfstudio.nativeide.notebook.formatRunDuration
import com.avfstudio.nativeide.notebook.recordTiming
import com.avfstudio.nativeide.notebook.CellType
import com.avfstudio.nativeide.notebook.KernelClient
import com.avfstudio.nativeide.notebook.generatedKernelName
import com.avfstudio.nativeide.notebook.NotebookCell
import com.avfstudio.nativeide.notebook.NotebookDocument
import com.avfstudio.nativeide.transport.ConnectionProfileStore
import com.avfstudio.nativeide.transport.FileEntry
import com.avfstudio.nativeide.transport.GuestPort
import com.avfstudio.nativeide.transport.GuestVfsClient
import com.avfstudio.nativeide.transport.GuestVfsException
import com.avfstudio.nativeide.transport.GuestRejectedException
import com.avfstudio.nativeide.transport.KeystoreSecretCipher
import com.avfstudio.nativeide.transport.GitEvent
import com.avfstudio.nativeide.transport.GitFileStatus
import com.avfstudio.nativeide.transport.GitOperation
import com.avfstudio.nativeide.transport.GitResult
import com.avfstudio.nativeide.transport.GitStatusEntry
import com.avfstudio.nativeide.transport.GuestStats
import com.avfstudio.nativeide.transport.PtyEvent
import com.avfstudio.nativeide.transport.parseConnectionString
import com.avfstudio.nativeide.transport.SearchQuery
import com.avfstudio.nativeide.transport.SearchEvent
import com.avfstudio.nativeide.transport.SharedPreferencesKeyValueStore
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.connectbot.terminal.TerminalEmulatorFactory
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.math.roundToInt
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** How long a status bar message stays. */
private const val STATUS_SECONDS = 10L
/** Recently opened workspace folders, newest first. */
private const val RECENT_WORKSPACES_KEY = "recent-workspaces"
private const val RECENT_WORKSPACES_LIMIT = 10
/** Untitled (scratch:) files open at the last stop; their text is in the document cache. */
private const val SESSION_UNTITLED_KEY = "session-untitled"
/** Device files open at the last stop, read again through their content URI. */
private const val SESSION_DEVICE_KEY = "session-device"
private const val KEYBINDINGS_KEY = "user-keybindings"
private const val LANGUAGES_DECLINED_KEY = "languages-declined"
/** Built-in language packs are 10-60 KB; anything far bigger isn't one. */
private const val MAX_LANGUAGE_PACK_BYTES = 4 * 1024 * 1024
private val languagePackHttp by lazy { okhttp3.OkHttpClient() }
/** Rows the data viewer asks the kernel for. */
private const val DATA_VIEWER_ROWS = 1000
/** The native daemon's default port; hidden in Ports with the connection's own. */
private const val DAEMON_PORT = 19870
/** Commands whose ports are the app's own plumbing (kernels, the Jupyter bridge, the daemon). */
private val INTERNAL_COMMANDS = listOf("ipykernel", "avf-native-daemon", "avf-jupyter-bridge", "jupyter-bridge")
/** Two taps on the same file this close together keep its preview tab. */
private const val DOUBLE_TAP_MS = 500L
private val KEYBINDINGS_TEMPLATE = """
    // Keyboard shortcuts, in VS Code's keybindings.json format. A command starting with "-"
    // removes that default shortcut. "when" clauses are kept but not evaluated here.
    [
    ]
""".trimIndent()
/** Per folder, the guest files this device has a copy of (the document cache), for Explorer offline. */
private const val OFFLINE_FILES_PREFIX = "offline-files:"
private const val OFFLINE_FILES_LIMIT = 200

/** Copilot starts again after 3, 6 and 12 s when its server fails to start. */
private const val COPILOT_START_RETRIES = 3

/** Languages an untitled file can take, with the extension that gives it that language. */
val UNTITLED_LANGUAGES = linkedMapOf(
    "Plain text" to "",
    "Python" to "py",
    "TypeScript" to "ts",
    "JavaScript" to "js",
    "TSX" to "tsx",
    "JSX" to "jsx",
)
private const val MAX_IMPORTED_THEME_BYTES = 8 * 1024 * 1024
private const val MAX_OUTPUT_LINES = 500
/** Longest wait between reconnect attempts while the app is in front. */
private const val FOREGROUND_RECONNECT_CAP_S = 10L
/** How long after a reattach the replayed output may still make the emulator answer old queries. */
private const val TERMINAL_REPLAY_WINDOW_MS = 3000L
private const val MAX_QUICK_OPEN_FILES = 5000
/** Chat options remembered across sessions; approvals ("permissions") never are. */
private val rememberedChatCategories = setOf("model", "mode")
private const val LINUX_TERMINAL_PACKAGE = "com.android.virtualization.terminal"
private val skippedDirectories = setOf(
    ".git", "node_modules", "__pycache__", ".venv", "venv", ".mypy_cache", ".ruff_cache", ".pytest_cache",
    ".ipynb_checkpoints", "dist", "build", "target", ".gradle", ".idea", ".tox",
    // A home folder opened as the workspace: environments, not the user's files.
    "miniconda3", "miniforge3", "anaconda3", "mambaforge", "micromamba",
)

/** Hidden folders Quick Open still walks (project configuration, not caches). */
private val walkedHiddenDirectories = setOf(".github", ".vscode", ".devcontainer")

private fun walksIntoForQuickOpen(name: String): Boolean =
    name !in skippedDirectories && (!name.startsWith('.') || name in walkedHiddenDirectories)

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
    /** The Chat view's agent; switching agents ([selectChatAgent]) replaces it. */
    var chatAgent = AcpClient(vfs)
        private set
    private val defaultSettings =
        JsonParser.parseString("""{"editor":{"fontSize":14,"tabSize":4,"insertSpaces":true}}""").asJsonObject
    private val preferences = context.getSharedPreferences("native-ide-settings", Context.MODE_PRIVATE)
    private val cache = EncryptedDocumentCache(context)
    val repository = DocumentRepository(vfs, cache, ContentResolverFileStore(context))
    val editorHost = SoraTextEditorHost()
    private val io = Executors.newSingleThreadExecutor()
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val profileStore = ConnectionProfileStore(SharedPreferencesKeyValueStore(context), KeystoreSecretCipher())
    /** The user's `keybindings.json` (kept on this device, like user settings), as parsed. */
    var keybindingRules by mutableStateOf(
        runCatching { parseKeybindings(preferences.getString(KEYBINDINGS_KEY, "").orEmpty()).rules }.getOrDefault(emptyList()),
    )
        private set
    private var editorDispatcher = EditorKeybindingDispatcher(applyKeybindings(EditorCommands.all, keybindingRules))
    private var globalDispatcher = EditorKeybindingDispatcher(applyKeybindings(EditorCommands.global, keybindingRules))
    private var notebookDispatcher =
        EditorKeybindingDispatcher(applyKeybindings(EditorCommands.global + EditorCommands.notebook, keybindingRules))
    private var terminalDispatcher =
        EditorKeybindingDispatcher(applyKeybindings(EditorCommands.terminal + EditorCommands.global, keybindingRules))
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
    private var statusClear: ScheduledFuture<*>? = null
    /**
     * The status bar's message. Like VS Code's status messages it goes after a few seconds; the
     * Output panel (a tap on the status bar) keeps every one.
     */
    var status: String
        get() = statusText
        set(value) {
            statusText = value
            outputLog.add(value)
            while (outputLog.size > MAX_OUTPUT_LINES) outputLog.removeAt(0)
            statusClear?.cancel(false)
            // After dispose() the timer refuses work; the message just stays then.
            statusClear = runCatching {
                timer.schedule({ post { if (statusText == value) statusText = "" } }, STATUS_SECONDS, TimeUnit.SECONDS)
            }.getOrNull()
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
    /** The selected profile's pinned certificate (remote hosts paired with a connection string). */
    private var profileFingerprint: String? = null
    /** Shown above the editor when the last connection attempt failed; null when connected. */
    var guestProblem by mutableStateOf<String?>(null)
        private set
    var setupCommand by mutableStateOf<String?>(null)
        private set
    var setupError by mutableStateOf<String?>(null)
        private set
    var pairingError by mutableStateOf<String?>(null)
        private set
    private var autoConnecting = false
    private var sessionRestored = false
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
    /** Explorer folders open in place (tree view), and the children loaded for each. */
    val expandedFolders = mutableStateListOf<String>()
    val folderChildren = mutableStateMapOf<String, List<FileEntry>>()
    val documents = mutableStateListOf<DocumentSession>()
    /** Folders opened before, newest first (VS Code's Open Recent). */
    var recentWorkspaces by mutableStateOf(emptyList<String>())
        private set
    /** The workspace whose tabs [persistSession] records; set as soon as one is chosen. */
    private var sessionWorkspace: String? = null
    /** Set while switching: closing the old tabs must not overwrite their saved session. */
    private var switchingWorkspace = false

    private val closedDocuments = mutableListOf<DocumentSession>()
    private var activePathState by mutableStateOf<String?>(null)
    var activePath: String?
        get() = activePathState
        set(value) {
            val previous = activePathState
            activePathState = value
            // files.autoSave onFocusChange: switching tabs saves the one left behind (text or notebook).
            if (previous != null && previous != value && settings.autoSave == AutoSaveMode.OnFocusChange) {
                post { autoSave(previous) }
            }
        }
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
    /** The open text editor, whichever engine draws it; null while none is shown. */
    var editorSurface by mutableStateOf<EditorSurface?>(null)
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
    // The views shown come back on the next launch, as VS Code restores its layout
    // (`avf.session.restore` off starts from the defaults).
    private val restoreLayout = settings.restoreSession
    private val sidebarState = mutableStateOf(
        if (restoreLayout) layoutValue("layout-sidebar", SidebarView.Explorer.name).let { name -> SidebarView.entries.firstOrNull { it.name == name } } else SidebarView.Explorer,
    )
    var sidebar: SidebarView?
        get() = sidebarState.value
        set(value) {
            sidebarState.value = value
            saveLayout("layout-sidebar", value?.name.orEmpty())
        }
    private val panelVisibleState = mutableStateOf(restoreLayout && layoutValue("layout-panel", "") == "shown")
    var panelVisible: Boolean
        get() = panelVisibleState.value
        set(value) {
            panelVisibleState.value = value
            saveLayout("layout-panel", if (value) "shown" else "")
        }
    private val panelTabState = mutableStateOf(
        PanelTab.entries.firstOrNull { restoreLayout && it.name == layoutValue("layout-panel-tab", "") } ?: PanelTab.Terminal,
    )
    var panelTab: PanelTab
        get() = panelTabState.value
        set(value) {
            panelTabState.value = value
            saveLayout("layout-panel-tab", value.name)
        }
    private val chatVisibleState = mutableStateOf(restoreLayout && layoutValue("layout-chat", "") == "shown")
    var chatVisible: Boolean
        get() = chatVisibleState.value
        set(value) {
            chatVisibleState.value = value
            saveLayout("layout-chat", if (value) "shown" else "")
        }
    var settingsVisible by mutableStateOf(false)
    var compactDestination by mutableStateOf(CompactDestination.Editor)
    private var focusAreaState by mutableStateOf(FocusArea.Other)
    var focusArea: FocusArea
        get() = focusAreaState
        set(value) {
            val left = focusAreaState in setOf(FocusArea.Editor, FocusArea.Notebook) && value != focusAreaState
            focusAreaState = value
            // files.autoSave onFocusChange: leaving the editor (a panel, the sidebar) saves it.
            if (left && settings.autoSave == AutoSaveMode.OnFocusChange) activePath?.let(::autoSave)
        }

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
    /** Whether the chat agent runs in the guest (its Stop button); the client itself isn't observable. */
    var chatAgentRunning by mutableStateOf(false)
        private set
    var chatAuthMethods by mutableStateOf(emptyList<AcpAuthMethod>())
    /** Which agent [chatAgent] is (`avf.chat.agent`). */
    var chatAgentKind by mutableStateOf(ChatAgentKind.Copilot)
        private set
    /** The agent turned a message down for want of a sign-in; the pane offers one. */
    var chatSignInNeeded by mutableStateOf(false)
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
        // Untitled files come back with their text (VS Code's hot exit); saved files reopen once a
        // workspace connects (restoreSession). Nothing opens by default: the empty editor offers
        // folders and files.
        recentWorkspaces = loadRecentWorkspaces()
        refreshLauncherShortcuts()
        restoreUntitledFiles()
        if (settings.chatAgent != ChatAgentKind.Copilot) {
            chatAgent = AcpClient(vfs, settings.chatAgent.tool)
            chatAgentKind = settings.chatAgent
        }
        profiles.firstOrNull { it.id == profileStore.lastUsedId() }?.let(::selectProfile)
        preferences.getString("grammar-load-errors", null)
            ?.takeIf(String::isNotBlank)?.let { status = "Asset-pack grammar load failed: $it" }
        bindListeners()
        // Pick up where the last session left off: reconnect, then reopen its tabs (onWorkspaceConnected).
        if (settings.autoConnect && endpoint.isNotBlank() && token.isNotBlank()) {
            autoConnecting = true
            connectGuest()
        } else if (settings.offlineWorkspace) {
            openOfflineWorkspace()
        }
    }

    fun post(action: () -> Unit) {
        mainHandler.post(action)
    }

    private fun bindListeners() {
        vfs.portListener = { port, open -> post { onGuestPort(port, open) } }
        vfs.connectionStateListener = { isConnected, failure ->
            post {
                connected = isConnected
                // The daemon lists its open ports again after each handshake.
                guestPorts.clear()
                portsConnectedAt = android.os.SystemClock.uptimeMillis()
                if (!isConnected) {
                    languageClients.values.forEach(LanguageClient::onDisconnected)
                    copilot.language.onDisconnected()
                    notebookMirrors.clear()
                    notebooks.values.forEach { it.cellSuggestion = null }
                    chatAgent.onDisconnected()
                    notebooks.values.forEach { it.kernel.onDisconnected(); it.kernelState = "Disconnected" }
                    inlineSuggestion = null
                    chatBusy = false
                    copilotStatus = if (copilotEnabled) "Disconnected" else "Off"
                }
                if (!isConnected && !busy) {
                    GuestConnectionService.stop(context)
                    if (!failure.isNullOrBlank()) status = "Guest disconnected: $failure"
                    // The daemon restarts on updates and the VM may pause; come back by ourselves.
                    if (wasConnected) scheduleReconnect()
                }
                wasConnected = isConnected
            }
        }
        languageClients.values.forEach { client ->
            client.diagnosticsListener = { uri, updates ->
                lspLog { "diagnostics $uri: ${updates.size}" }
                post {
                    diagnosticsByClient.getOrPut(client) { mutableMapOf() }[uri] = updates
                    diagnostics.clear()
                    diagnostics.addAll(diagnosticsByClient.values.flatMap { it.values.flatten() })
                    refreshEditorProblems()
                }
            }
            client.messageListener = { message -> post { status = message } }
            client.inlayHintRefreshListener = {
                post { activePath?.takeIf { languageClientFor(it) === client }?.let { scheduleInlayHints(it, delayMs = 100) } }
            }
        }
        copilot.language.messageListener = { message -> post { status = message } }
        copilot.language.showDocumentHandler = { uri -> openExternal(uri) }
        copilot.statusListener = { copilotState ->
            post {
                copilotStatus = copilotState.message.ifEmpty { copilotState.kind }
                copilotStatusKind = copilotState.kind
                onCopilotSignInState(copilotState)
                if (copilotState.kind == "Error" || copilotState.kind == "Warning") {
                    status = "Copilot: ${copilotState.message.ifEmpty { copilotState.kind }}"
                } else if (copilotState.kind == "Normal" && dialog is WorkbenchDialog.CopilotSignInCode) {
                    dialog = null
                    status = "Copilot signed in"
                    enableCopilot(true)
                }
            }
        }
        bindChatAgent()
    }

    private fun bindChatAgent() {
        val agent = chatAgent
        // A silent resume replays the conversation the pane already shows; drop that replay.
        agent.eventListener = { event -> if (!suppressChatReplay) post { appendChat(event) } }
        agent.configListener = { options -> post { if (agent === chatAgent) chatConfigOptions = options } }
        agent.runningListener = { running -> post { if (agent === chatAgent) chatAgentRunning = running } }
        agent.exitListener = { message ->
            post {
                if (agent === chatAgent) {
                    chatBusy = false
                    chatEntries.add(ChatEntry.Notice(message))
                }
            }
        }
    }

    fun dispose() {
        stopStatsPolling()
        editorSubscription.close()
        languageClients.values.forEach(LanguageClient::close)
        copilot.language.close()
        chatAgent.stop()
        notebooks.values.forEach { it.kernel.stop() }
        terminals.forEach { it.emulator.close() }
        documents.forEach { document -> io.execute { repository.persistDraft(document) } }
        vfs.connectionStateListener = null
        vfs.portListener = null
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
        val previousTypescriptServer = settings.typescriptLanguageServer
        val previousTypeChecking = settings.pythonTypeChecking
        val previousLintRules = settings.pythonLintRules
        val previousInlayHints = settings.inlayHints
        userSettings = next
        updateLanguageSettings()
        editorSurface?.applyConfig(editorConfig())
        refreshEditorProblems()
        terminals.forEach { /* font size is read by the composable */ }
        // Servers read these at start, so a change restarts them.
        if (settings.pythonLanguageServer != previousServer || settings.pythonTypeChecking != previousTypeChecking) {
            restartLanguageServer("python")
        }
        if (settings.pythonLintRules != previousLintRules) restartLanguageServer("python-format")
        if (settings.typescriptLanguageServer != previousTypescriptServer) restartLanguageServer("typescript")
        if (settings.chatAgent != chatAgentKind) switchChatAgent(settings.chatAgent)
        if (settings.inlayHints != previousInlayHints) activePath?.let { scheduleInlayHints(it, delayMs = 0) }
        refreshLauncherShortcuts()
    }

    /**
     * Stops [language]'s server and, once it is gone, starts it again for the open documents
     * (starting earlier found it still running and did nothing). Its old problems are dropped;
     * the new server reports again.
     */
    private fun restartLanguageServer(language: String) {
        val client = languageClients.getValue(language)
        if (!client.isStarted) return
        client.stop().whenComplete { _, _ ->
            post {
                diagnosticsByClient.remove(client)
                diagnostics.clear()
                diagnostics.addAll(diagnosticsByClient.values.flatMap { it.values.flatten() })
                refreshEditorProblems()
                documents.forEach { ensureLanguageServers(it.path) }
            }
        }
    }

    /** The editor options from the settings, for [EditorSurface.applyConfig]. */
    fun editorConfig(): EditorConfig = settings.let {
        EditorConfig(
            fontSizeSp = it.editorFontSize,
            tabSize = it.tabSize,
            insertSpaces = it.insertSpaces,
            autoIndent = it.autoIndent,
            useTabStops = it.useTabStops,
            wordWrap = it.wordWrap,
            lineNumbers = it.lineNumbers,
            problemUnderlines = it.problemUnderlines,
            showUnused = it.showUnused,
            showDeprecated = it.showDeprecated,
            pinchToZoom = it.pinchToZoom,
        )
    }

    private fun updateLanguageSettings() {
        languageClients.forEach { (language, client) ->
            client.updateSettings(SettingsResolver.sections(serverSettings(language)))
        }
    }

    /** The settings a language server sees: settings.json plus what the app's own pickers add. */
    private fun serverSettings(language: String): JsonObject {
        var merged = SettingsResolver.merge(defaultSettings, userSettings, workspaceSettings, language)
        fun fill(path: String, value: Any?) {
            if (value != null && merged.getPath(path) == null) merged = merged.withPath(path, value)
        }
        if (language == "python") {
            // basedpyright reads the interpreter from its settings (ty gets it at start, as env).
            if (settings.pythonInterpreter.isNotBlank()) fill("python.pythonPath", settings.pythonInterpreter)
            fill("basedpyright.analysis.typeCheckingMode", settings.pythonTypeChecking.basedpyrightMode)
            // ty's inline configuration wins over ty.toml; `all` sets every rule's level.
            fill("ty.configuration.rules.all", settings.pythonTypeChecking.tyRulesLevel)
        }
        if (language == "python-format" && settings.pythonLintRules.extendSelect.isNotEmpty()) {
            fill("ruff.lint.extendSelect", com.google.gson.JsonArray().apply { settings.pythonLintRules.extendSelect.forEach(::add) })
        }
        return merged
    }

    /** Ruff takes its editor settings only at start (`initializationOptions.settings`). */
    private fun initializationOptions(language: String, tool: String): JsonObject? =
        if (tool == "ruff") {
            JsonObject().apply { add("settings", serverSettings(language).getPath("ruff")?.deepCopy() ?: JsonObject()) }
        } else {
            null
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
        profileFingerprint = profile.fingerprint
    }

    fun newProfile() {
        profileId = null
        profileName = ""
        endpoint = ""
        token = ""
        profileFingerprint = null
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
        guestProblem = null
        status = "Connecting to guest"
        languageServerIssues = emptyList()
        val notificationsAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        keepAliveError = if (notificationsAllowed) null else {
            "Notification permission denied; Android may hide the background connection notification"
        }
        startKeepAlive("Connecting")
        // A paired remote host's pin only applies to the endpoint it was paired with.
        val pin = profileFingerprint?.takeIf { endpoint.trim().startsWith("wss://") }
        vfs.connect(endpoint.trim(), token, pin).thenCompose { vfs.roots() }.whenComplete { guestRoots, error ->
            post {
                busy = false
                if (error != null) {
                    connected = false
                    GuestConnectionService.stop(context)
                    onConnectFailed(error)
                    return@post
                }
                connected = true
                roots = guestRoots
                if (dialog == WorkbenchDialog.Connect) dialog = null
                io.execute {
                    val saved = profileStore.save(profileId, profileName, endpoint, token, pin)
                    post {
                        profileId = saved.id
                        profileName = saved.name
                        profiles = profileStore.profiles()
                    }
                }
                startKeepAlive("Connected")
                startStatsPolling()
                checkGuestTools()
                guestUnreachable = false
                unreachableFailures = 0
                guestForwardingSuspected = false
                reconnectAttempt = 0
                scheduledReconnect?.cancel(false)
                scheduledReconnect = null
                status = when {
                    keepAliveError != null -> "Connected; background keepalive unavailable: $keepAliveError"
                    guestRoots.isEmpty() -> "Connected; guest has no workspace roots"
                    else -> "Guest connected"
                }
                // A folder opened offline may belong to another guest than this one.
                if (workspaceRoot?.let { root -> guestRoots.none { isPathWithinRoot(root, it) } } == true) workspaceRoot = null
                if (workspaceRoot == null) chooseWorkspace(guestRoots, ::onWorkspaceConnected) else onWorkspaceConnected(workspaceRoot!!)
                reattachTerminals()
                documents.filter { it.dirty && isGuestPath(it.path) }.forEach(::reconcileDocument)
                refreshCleanDocuments()
            }
        }
    }

    // Search ------------------------------------------------------------------------------------

    var searchText by mutableStateOf("")
    var searchRegex by mutableStateOf(false)
    var searchCaseSensitive by mutableStateOf(false)
    var searchWholeWord by mutableStateOf(false)
    var searchInclude by mutableStateOf("")
    var searchExclude by mutableStateOf("")
    val searchResults = mutableStateListOf<SearchFileResult>()
    var searchSummary by mutableStateOf<String?>(null)
        private set
    var searchRunning by mutableStateOf(false)
        private set
    /** Bumped to put the keyboard in the search field (Ctrl+Shift+F). */
    var searchFocusRequest by mutableStateOf(0)
        private set
    private var activeSearch: Long? = null
    private var scheduledSearch: ScheduledFuture<*>? = null

    /** Ctrl+Shift+F: show Search, seeded with the editor selection as VS Code does. */
    fun showSearch() {
        editorSurface?.takeIf { focusArea == FocusArea.Editor }?.let { surface ->
            val range = surface.selectionOffsets
            val selected = surface.text.substring(range.first, range.last)
            if (selected.isNotEmpty() && '\n' !in selected) {
                searchText = selected
                scheduleSearch(0)
            }
        }
        sidebar = SidebarView.Search
        compactDestination = CompactDestination.Files
        searchFocusRequest++
    }

    /** Re-runs the search shortly after typing stops; each run cancels the previous one. */
    fun scheduleSearch(delayMs: Long = 350) {
        scheduledSearch?.cancel(false)
        scheduledSearch = timer.schedule({ post { runSearch() } }, delayMs, TimeUnit.MILLISECONDS)
    }

    fun runSearch() {
        scheduledSearch?.cancel(false)
        activeSearch?.let(vfs::cancelSearch)
        activeSearch = null
        searchResults.clear()
        val text = searchText
        if (text.isEmpty()) {
            searchRunning = false
            searchSummary = null
            return
        }
        val root = workspaceRoot
        if (root == null || !vfs.isConnected()) {
            searchRunning = false
            searchSummary = "Connect to a guest to search"
            return
        }
        fun globs(value: String) = value.split(',').map(String::trim).filter(String::isNotEmpty)
        val query = SearchQuery(
            query = text,
            roots = listOf(root),
            regex = searchRegex,
            caseSensitive = searchCaseSensitive,
            wholeWord = searchWholeWord,
            include = globs(searchInclude),
            exclude = globs(searchExclude),
        )
        val files = HashMap<String, SearchFileResult>()
        searchRunning = true
        searchSummary = "Searching…"
        var requestId: Long? = null
        requestId = vfs.search(query) { event ->
            post {
                if (activeSearch != requestId) return@post
                when (event) {
                    is SearchEvent.Match -> files.getOrPut(event.path) {
                        SearchFileResult(event.path).also(searchResults::add)
                    }.matches.add(event)
                    is SearchEvent.Done -> {
                        searchRunning = false
                        activeSearch = null
                        searchSummary = when {
                            event.total == 0 -> "No results"
                            else -> "${event.total} result${if (event.total == 1) "" else "s"} in ${searchResults.size} " +
                                "file${if (searchResults.size == 1) "" else "s"}" +
                                if (event.truncated) " (stopped at ${query.maxResults}; refine the search)" else ""
                        }
                    }
                    is SearchEvent.Error -> {
                        searchRunning = false
                        activeSearch = null
                        searchSummary = if (missingDependencyIn(event.message) == GuestDependency.Ripgrep) {
                            "Search needs ripgrep, which isn't installed in the guest"
                        } else {
                            event.message
                        }
                    }
                }
            }
        }
        activeSearch = requestId
        if (requestId == null) {
            searchRunning = false
            searchSummary = "Connect to a guest to search"
        }
    }

    /** Search's replace field (VS Code's toggle beside the search box) and its text. */
    var searchReplaceVisible by mutableStateOf(false)
    var searchReplace by mutableStateOf("")
    var replacing by mutableStateOf(false)
        private set

    private val searchPattern: SearchPattern
        get() = SearchPattern(searchText, searchRegex, searchCaseSensitive, searchWholeWord)

    /** What a match would become with the current replace text, for its preview; null without one. */
    fun replacementPreviewFor(match: SearchEvent.Match): String? =
        if (!searchReplaceVisible) null else replacementPreview(match.preview.trimEnd('\n', '\r'), match.column, searchPattern, searchReplace)

    fun replaceSearchMatch(file: SearchFileResult, match: SearchEvent.Match) =
        replaceInFiles(mapOf(file.path to setOf(MatchPosition(match.line, match.column))))

    fun replaceInSearchFile(file: SearchFileResult) = replaceInFiles(mapOf(file.path to null))

    /** Replace All asks first, with the count, as VS Code does. */
    fun confirmReplaceAll() {
        val total = searchResults.sumOf { it.matches.size }
        if (total == 0) return
        dialog = WorkbenchDialog.ConfirmReplaceAll(total, searchResults.size)
    }

    fun replaceAllSearchResults() {
        dialog = null
        replaceInFiles(searchResults.associate { it.path to null })
    }

    /**
     * Replaces in each file: all matches (null) or only those positions. Open files change in
     * their editor and stay unsaved, as in VS Code; closed ones are read, changed and saved (a
     * file changed in the guest since is left alone and named). Search runs again afterwards.
     */
    private fun replaceInFiles(targets: Map<String, Set<MatchPosition>?>) {
        if (replacing || targets.isEmpty()) return
        val pattern = searchPattern
        val replacement = searchReplace
        if (runCatching { pattern.toRegex() }.isFailure) {
            status = "The search pattern isn't a valid regular expression here"
            return
        }
        var replaced = 0
        val closed = mutableListOf<Pair<String, Set<MatchPosition>?>>()
        targets.forEach { (path, only) ->
            val document = documents.firstOrNull { it.path == path }
            if (document == null || document.binary) {
                closed += path to only
                return@forEach
            }
            val (text, count) = replaceMatches(document.text, pattern, replacement, only)
            if (count == 0) return@forEach
            replaced += count
            document.updateText(text)
            if (path == activePath) editorSurface?.setText(text) else notifyDocumentChanged(path, document.version, text)
            onDocumentEdited(document)
        }
        if (closed.isEmpty()) {
            status = "Replaced $replaced occurrence${if (replaced == 1) "" else "s"}; open files are unsaved"
            runSearch()
            return
        }
        replacing = true
        status = "Replacing…"
        val failures = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val counts = java.util.concurrent.atomic.AtomicInteger(replaced)
        val work = closed.map { (path, only) ->
            repository.open(path).thenCompose { opened ->
                val document = (opened as? OpenDocument.Editable)?.document
                    ?: return@thenCompose CompletableFuture.completedFuture<Unit>(Unit).also { failures += "${path.substringAfterLast('/')} (too large)" }
                val (text, count) = replaceMatches(document.text, pattern, replacement, only)
                if (count == 0) return@thenCompose CompletableFuture.completedFuture(Unit)
                document.updateText(text)
                repository.save(document).thenApply { result ->
                    if (result is SaveResult.Saved) counts.addAndGet(count) else failures += path.substringAfterLast('/')
                    Unit
                }
            }.exceptionally { error ->
                failures += "${path.substringAfterLast('/')} (${error.cause?.message ?: error.message})"
                Unit
            }
        }
        CompletableFuture.allOf(*work.toTypedArray()).whenComplete { _, _ ->
            post {
                replacing = false
                val total = counts.get()
                status = "Replaced $total occurrence${if (total == 1) "" else "s"}" +
                    if (failures.isEmpty()) "" else "; not changed: ${failures.joinToString(", ")}"
                runSearch()
            }
        }
    }

    fun openSearchMatch(match: SearchEvent.Match) {
        val position = JsonObject().apply {
            addProperty("line", match.line - 1)
            addProperty("character", match.column)
        }
        val end = JsonObject().apply {
            addProperty("line", match.line - 1)
            addProperty("character", match.column + match.length)
        }
        val range = JsonObject().apply {
            add("start", position)
            add("end", end)
        }
        navigateToLocation(LanguageLocation(java.net.URI("file", "", match.path, null).toASCIIString(), range), preview = true)
    }

    // Guest resources -----------------------------------------------------------------------------

    /** Latest daemon sample; null until connected. */
    var guestStats by mutableStateOf<GuestStats?>(null)
        private set
    /** Recent footprints (MB), oldest first, for the resources panel's graph. */
    val guestFootprintHistory = mutableStateListOf<Long>()
    private var statsPoll: ScheduledFuture<*>? = null
    private var appVisible = true

    /** The activity's visibility: sampling stops while the app is in the background. */
    fun onAppVisibilityChanged(visible: Boolean) {
        appVisible = visible
        if (visible) startStatsPolling() else stopStatsPolling()
        if (!visible && settings.autoSave in setOf(AutoSaveMode.OnFocusChange, AutoSaveMode.OnWindowChange)) autoSaveAll()
        // Coming back from Linux Terminal usually means the guest is up now.
        // A retry scheduled in the background may be a minute away; try now instead.
        if (visible && !connected && !busy && (guestUnreachable || scheduledReconnect != null)) {
            scheduledReconnect?.cancel(false)
            scheduledReconnect = null
            autoConnecting = true
            connectGuest()
        }
    }

    private fun startStatsPolling() {
        if (statsPoll != null || !appVisible || !vfs.isConnected()) return
        statsPoll = timer.scheduleWithFixedDelay({ sampleGuest() }, 0, 5, TimeUnit.SECONDS)
    }

    private fun stopStatsPolling() {
        statsPoll?.cancel(false)
        statsPoll = null
    }

    private fun sampleGuest() {
        if (!vfs.isConnected()) {
            post { stopStatsPolling() }
            return
        }
        vfs.ping().whenComplete { stats, error ->
            if (error != null) return@whenComplete
            post {
                guestStats = stats
                guestFootprintHistory.add(stats.footprintMb)
                if (guestFootprintHistory.size > 120) guestFootprintHistory.removeAt(0)
            }
        }
    }

    /** What the guest is running for the app, for the resources panel. */
    val guestComponents: List<String>
        get() = buildList {
            languageClients.filterValues { it.isStarted }.keys.forEach { language ->
                add(if (language == "python") "${settings.pythonLanguageServer} (Python)" else languageServerTool(language))
            }
            if (copilot.language.isStarted) add("Copilot language server (~280 MB)")
            if (chatAgent.isStarted) add("${chatAgentKind.label} chat agent (${chatAgentKind.memoryNote})")
            notebooks.values.count { it.kernel.isStarted }.takeIf { it > 0 }?.let { add("$it Jupyter kernel${if (it == 1) "" else "s"}") }
            if (debugger.isStarted) add("Python debugger")
            terminals.count { !it.exited }.takeIf { it > 0 }?.let { add("$it terminal${if (it == 1) "" else "s"}") }
        }

    /** Stops tools that no open document needs, the chat agent and the debugger. */
    fun stopIdleGuestTools() {
        val needed = documents.filterNot { it.path in notebooks }
            .flatMap { languageClientsFor(it.path) }.toSet()
        languageClients.values.filter { it.isStarted && it !in needed }.forEach(LanguageClient::close)
        if (chatAgent.isStarted && !chatBusy) endChatSession()
        if (debugger.isStarted && debugState == DebugState.Idle) debugger.stop()
        status = "Stopped idle guest tools"
    }

    // Debugging ---------------------------------------------------------------------------------

    private val debugger = DapClient(vfs)
    /** 1-based breakpoint lines per guest file, kept across launches. */
    val breakpoints = mutableStateMapOf<String, Set<Int>>().apply {
        runCatching {
            val saved = com.google.gson.JsonParser.parseString(preferences.getString("breakpoints", "{}")).asJsonObject
            saved.entrySet().forEach { (path, lines) -> put(path, lines.asJsonArray.map { it.asInt }.toSet()) }
        }
    }
    var debugState by mutableStateOf<DebugState>(DebugState.Idle)
        private set
    val debugFrames = mutableStateListOf<DebugFrame>()
    var selectedFrameId by mutableStateOf<Int?>(null)
        private set
    val debugVariables = mutableStateListOf<DebugVariable>()
    val debugConsole = mutableStateListOf<DebugConsoleLine>()
    private var debugOutputOpen = false
    /** Where execution is paused (path, 1-based line), drawn in the editor. */
    var executionPoint by mutableStateOf<Pair<String, Int>?>(null)
        private set

    fun toggleBreakpoint(path: String? = activePath, line: Int? = editorSurface?.selectionStart()?.line?.plus(1)) {
        if (path == null || line == null || !isGuestPath(path)) return
        val lines = breakpoints[path].orEmpty()
        val updated = if (line in lines) lines - line else lines + line
        if (updated.isEmpty()) breakpoints.remove(path) else breakpoints[path] = updated
        preferences.edit().putString(
            "breakpoints",
            com.google.gson.Gson().toJson(breakpoints.mapValues { it.value.sorted() }),
        ).apply()
        if (debugger.isStarted) debugger.request("setBreakpoints", setBreakpointsArguments(path, updated))
    }

    /** F5: continue when paused, otherwise launch the active Python file under debugpy. */
    fun startOrContinueDebugging() {
        when (val state = debugState) {
            is DebugState.Stopped -> {
                debugger.request("continue", JsonObject().apply { addProperty("threadId", state.threadId) })
                return
            }
            DebugState.Running, DebugState.Starting -> return
            DebugState.Idle -> Unit
        }
        val document = activeDocument
        val workspace = workspaceRoot
        if (document == null || workspace == null || !vfs.isConnected()) {
            status = "Connect to a guest and open a Python file to debug"
            return
        }
        if (!isGuestPath(document.path) || document.languageId != "py") {
            status = "Debugging runs saved Python files; open a .py file in the workspace"
            return
        }
        val python = settings.pythonInterpreter.ifEmpty {
            chooseInterpreter(then = ::startOrContinueDebugging)
            return
        }
        if (document.dirty) saveActiveDocument()
        val program = document.path
        val cwd = program.substringBeforeLast('/')
        debugState = DebugState.Starting
        debugConsole.clear()
        debugOutputOpen = false
        debugConsole.add(DebugConsoleLine("Launching ${program.substringAfterLast('/')} with $python", DebugConsoleLine.Kind.Info))
        panelVisible = true
        panelTab = PanelTab.Debug
        // The compact layout shows the panel's tabs under Terminal.
        compactDestination = CompactDestination.Terminal
        debugger.eventListener = { event, body -> post { onDebugEvent(event, body) } }
        debugger.exitListener = { message -> post { if (debugState != DebugState.Idle) endDebugSession(message) } }
        debugger.start(cwd)
            .thenCompose { debugger.request("initialize", dapInitializeArguments()) }
            .thenCompose {
                // debugpy answers `launch` only after configurationDone, which `initialized` triggers.
                debugger.request("launch", pythonLaunchArguments(program, cwd, python), timeoutSeconds = null)
            }
            .whenComplete { _, error ->
                post {
                    if (error != null && debugState != DebugState.Idle) {
                        endDebugSession("Launch failed: ${generateSequence(error) { it.cause }.last().message}")
                    } else if (debugState == DebugState.Starting) {
                        debugState = DebugState.Running
                    }
                }
            }
    }

    private fun onDebugEvent(event: String, body: JsonObject) {
        when (event) {
            "initialized" -> {
                val files = breakpoints.toMap()
                CompletableFuture.allOf(*files.map { (path, lines) -> debugger.request("setBreakpoints", setBreakpointsArguments(path, lines)) }.toTypedArray())
                    .thenCompose {
                        debugger.request("setExceptionBreakpoints", JsonObject().apply {
                            add("filters", com.google.gson.JsonArray().apply { add("uncaught") })
                        })
                    }
                    .thenCompose { debugger.request("configurationDone") }
                    .whenComplete { _, error -> if (error != null) post { endDebugSession("Debugger setup failed: ${error.message}") } }
            }
            "stopped" -> {
                val threadId = body.get("threadId")?.asInt ?: return
                debugState = DebugState.Stopped(threadId, body.get("reason")?.asString ?: "pause", body.get("description")?.asString)
                loadStack(threadId)
            }
            "continued" -> clearPausedState(DebugState.Running)
            "output" -> {
                val category = body.get("category")?.asString ?: "console"
                if (category == "telemetry") return
                val text = body.get("output")?.asString ?: return
                val kind = if (category == "stderr") DebugConsoleLine.Kind.Error else DebugConsoleLine.Kind.Output
                debugOutputOpen = appendDebugOutput(debugConsole, text, kind, debugOutputOpen)
                if (debugConsole.size > 2_000) debugConsole.removeRange(0, debugConsole.size - 2_000)
                if (debugConsole.size > 2_000) debugConsole.removeRange(0, debugConsole.size - 2_000)
            }
            "exited" -> debugConsole.add(DebugConsoleLine("Process exited with code ${body.get("exitCode")?.asInt ?: "?"}", DebugConsoleLine.Kind.Info))
            "terminated" -> endDebugSession(null)
        }
    }

    private fun loadStack(threadId: Int) {
        debugger.request("stackTrace", JsonObject().apply {
            addProperty("threadId", threadId)
            addProperty("startFrame", 0)
            addProperty("levels", 50)
        }).whenComplete { body, error ->
            post {
                if (error != null) {
                    status = "Debugger: ${error.message}"
                    return@post
                }
                debugFrames.clear()
                debugFrames.addAll(parseStackFrames(body))
                debugFrames.firstOrNull { it.path != null }?.let(::selectFrame)
            }
        }
    }

    fun selectFrame(frame: DebugFrame) {
        selectedFrameId = frame.id
        frame.path?.let { path ->
            executionPoint = path to frame.line
            navigateToLocation(LanguageLocation(java.net.URI("file", "", path, null).toASCIIString(), lspRange(frame.line - 1, (frame.column - 1).coerceAtLeast(0))))
        }
        debugger.request("scopes", JsonObject().apply { addProperty("frameId", frame.id) }).whenComplete { body, error ->
            post {
                debugVariables.clear()
                if (error != null) return@post
                val scopes = parseScopes(body)
                debugVariables.addAll(scopes)
                // Locals open by default, as in VS Code.
                scopes.firstOrNull()?.let(::toggleVariable)
            }
        }
    }

    /** Expands or collapses a scope or variable, loading its children the first time. */
    fun toggleVariable(variable: DebugVariable) {
        if (variable.reference <= 0) return
        variable.expanded = !variable.expanded
        if (!variable.expanded || variable.children.isNotEmpty()) return
        debugger.request("variables", JsonObject().apply { addProperty("variablesReference", variable.reference) }).whenComplete { body, error ->
            post { if (error == null) variable.children.addAll(parseVariables(body, variable.depth + 1)) }
        }
    }

    /** Debug console input: evaluated in the selected frame (or globally while running). */
    fun evaluateInDebugConsole(expression: String) {
        if (expression.isBlank()) return
        debugConsole.add(DebugConsoleLine("> $expression", DebugConsoleLine.Kind.Input))
        if (!debugger.isStarted) {
            debugConsole.add(DebugConsoleLine("Start debugging (F5) to evaluate expressions", DebugConsoleLine.Kind.Info))
            return
        }
        debugger.request("evaluate", JsonObject().apply {
            addProperty("expression", expression)
            selectedFrameId?.let { addProperty("frameId", it) }
            addProperty("context", "repl")
        }).whenComplete { body, error ->
            post {
                debugConsole.add(
                    if (error != null) {
                        DebugConsoleLine(error.cause?.message ?: error.message ?: "Evaluation failed", DebugConsoleLine.Kind.Error)
                    } else {
                        DebugConsoleLine(body.get("result")?.asString.orEmpty(), DebugConsoleLine.Kind.Result)
                    },
                )
            }
        }
    }

    fun debugStep(command: String) {
        val stopped = debugState as? DebugState.Stopped ?: return
        debugger.request(command, JsonObject().apply { addProperty("threadId", stopped.threadId) })
    }

    fun pauseDebugging() {
        if (debugState != DebugState.Running) return
        debugger.request("pause", JsonObject().apply { addProperty("threadId", 0) })
    }

    fun stopDebugging() {
        if (debugState == DebugState.Idle) return
        debugger.request("disconnect", JsonObject().apply { addProperty("terminateDebuggee", true) }, timeoutSeconds = 5)
            .whenComplete { _, _ -> post { endDebugSession(null) } }
    }

    fun restartDebugging() {
        if (debugState == DebugState.Idle) {
            startOrContinueDebugging()
            return
        }
        debugger.request("disconnect", JsonObject().apply { addProperty("terminateDebuggee", true) }, timeoutSeconds = 5)
            .whenComplete { _, _ ->
                post {
                    endDebugSession(null)
                    startOrContinueDebugging()
                }
            }
    }

    private fun clearPausedState(next: DebugState) {
        debugState = next
        debugFrames.clear()
        debugVariables.clear()
        selectedFrameId = null
        executionPoint = null
    }

    private fun endDebugSession(message: String?) {
        message?.let { debugConsole.add(DebugConsoleLine(it, DebugConsoleLine.Kind.Error)) }
        clearPausedState(DebugState.Idle)
        debugger.stop()
        debugConsole.add(DebugConsoleLine("Debug session ended", DebugConsoleLine.Kind.Info))
    }

    /** Lists guest Python environments (via the Jupyter bridge's discovery) and saves the choice. */
    fun chooseInterpreter(then: (() -> Unit)? = null) {
        val workspace = workspaceRoot ?: run {
            status = "Connect to a guest to choose a Python interpreter"
            return
        }
        status = "Looking for Python environments…"
        val discovery = KernelClient(vfs)
        discovery.launch(workspace).thenCompose { discovery.kernelSpecs() }.whenComplete { found, error ->
            discovery.stop()
            post {
                val options = buildList {
                    found?.specs?.forEach { spec -> spec.python?.let { add("${spec.displayName} (${it})" to it) } }
                    found?.installable?.forEach { add("${it.label} (${it.python})" to it.python) }
                    add("python3 on the daemon's PATH" to "python3")
                }.distinctBy { it.second }
                status = if (error != null) "Python discovery failed (${error.message}); pick python3 or set python.defaultInterpreterPath"
                else "Choose a Python interpreter"
                checkGuestTools {
                    val setup = buildList {
                        if (options.none { "/.venv/" in it.second }) add(EnvironmentSetup.CreateVenv)
                        if (guestTool(GuestDependency.Conda) == DependencyState.Missing) add(EnvironmentSetup.Install(GuestDependency.Conda))
                    }
                    dialog = WorkbenchDialog.InterpreterPicker(options, then, setup)
                }
            }
        }
    }

    /** A picker row that adds an environment; the picker opens again once it exists. */
    fun setUpEnvironment(setup: EnvironmentSetup, then: (() -> Unit)?) {
        dialog = null
        when (setup) {
            is EnvironmentSetup.Install -> offerInstall(setup.dependency) { chooseInterpreter(then) }
            EnvironmentSetup.CreateVenv -> {
                status = "Creating ~/.venv…"
                newTerminal(createVenvCommand("python3", guestTool(GuestDependency.Uv) is DependencyState.Found) + " && exit", onExit = { code ->
                    post {
                        if (code == 0) chooseInterpreter(then) else status = "~/.venv wasn't created; the terminal shows why"
                    }
                })
            }
        }
    }

    fun selectInterpreter(path: String, then: (() -> Unit)?) {
        dialog = null
        val changed = path != settings.pythonInterpreter
        updateSetting(WorkbenchSettings.PYTHON_INTERPRETER, path)
        status = "Python interpreter: $path"
        // The language server reads the environment at start, so it starts again with the new one.
        if (changed) restartLanguageServer("python")
        then?.invoke()
    }

    /** Breakpoints, the paused line and problem dots beside the line numbers of [path]'s editor. */
    fun refreshLineMarks(path: String? = activePath) {
        if (path == null || path != activePath) return
        editorSurface?.setLineMarks(
            LineMarks(
                breakpoints = breakpoints[path].orEmpty().map { it - 1 }.toSet(),
                pausedLine = executionPoint?.takeIf { it.first == path }?.second?.minus(1),
                problems = if (settings.problemUnderlines) editorProblemLines else emptyMap(),
            ),
        )
    }

    private fun lspRange(line: Int, character: Int) = JsonObject().apply {
        val position = JsonObject().apply {
            addProperty("line", line)
            addProperty("character", character)
        }
        add("start", position)
        add("end", position)
    }

    // Guest dependencies ------------------------------------------------------------------------

    /** What the guest has of each tool an IDE feature needs; Unknown until checked. */
    var guestTools by mutableStateOf<Map<GuestDependency, DependencyState>>(emptyMap())
        private set

    fun guestTool(dependency: GuestDependency): DependencyState = guestTools[dependency] ?: DependencyState.Unknown

    /** Asks the daemon which tools exist; [then] runs once the answer (or the failure) is in. */
    fun checkGuestTools(then: (() -> Unit)? = null) {
        vfs.probeCommands(GuestDependency.allCommands).whenComplete { paths, error ->
            post {
                guestTools = if (error != null) emptyMap() else dependencyStates(paths)
                then?.invoke()
            }
        }
    }

    /** Asks before installing [dependency]; [reason] says what needs it, [then] runs after a successful install. */
    fun offerInstall(dependency: GuestDependency, reason: String? = null, then: (() -> Unit)? = null) {
        dialog = WorkbenchDialog.InstallDependency(dependency, reason, then)
    }

    /** Installs in a visible terminal, checks the guest again, then continues with [then]. */
    fun installDependency(dependency: GuestDependency, then: (() -> Unit)? = null) {
        if (dialog is WorkbenchDialog.InstallDependency) dialog = null
        status = "Installing ${dependency.label}…"
        newTerminal(installCommand(dependency), onExit = { code ->
            post {
                checkGuestTools {
                    if (code == 0) {
                        status = "${dependency.label} installed"
                        then?.invoke()
                    } else {
                        status = "${dependency.label} wasn't installed; the terminal shows why"
                    }
                }
            }
        })
    }

    /** Where /tmp lives in the guest, when the daemon reports it. */
    val tmpPlacement: TmpPlacement?
        get() = guestStats?.let { tmpPlacement(it.tmpInMemory, it.tmpMountMasked) }

    /** Offers to keep /tmp on disk (or back in memory) from the next guest restart. */
    fun offerTmpPlacement(toDisk: Boolean) {
        dialog = if (toDisk) {
            WorkbenchDialog.GuestCommand(
                title = "Move /tmp to disk?",
                text = "/tmp is a tmpfs in the guest's memory, so big installs can fail with \"No space left on device\" " +
                    "and its files cost the tablet RAM. This masks systemd's tmp.mount: from the next guest restart " +
                    "/tmp is a folder on the guest's disk. It runs in a new terminal; enter your password if sudo asks.",
                command = TMP_TO_DISK_COMMAND,
                done = "/tmp moves to disk when the guest restarts (close Linux Terminal and open it again)",
            )
        } else {
            WorkbenchDialog.GuestCommand(
                title = "Keep /tmp in memory?",
                text = "This unmasks systemd's tmp.mount, so /tmp is a tmpfs in memory again from the next guest restart. " +
                    "It runs in a new terminal; enter your password if sudo asks.",
                command = TMP_TO_MEMORY_COMMAND,
                done = "/tmp goes back to memory when the guest restarts",
            )
        }
    }

    /** The port forwarding fix's state in the guest, when the daemon reports it. */
    val forwarderFix: ForwarderFix?
        get() = guestStats?.let { forwarderFix(it.forwarderFixInstalled, it.forwarderFixActive) }

    /** Offers to install (or remove) the fix for Linux Terminal's port forwarder. */
    fun offerForwarderFix(install: Boolean) {
        dialog = if (install) {
            WorkbenchDialog.GuestCommand(
                title = "Fix Linux Terminal's port forwarding?",
                text = "Linux Terminal's port forwarder in the guest exits when a program names a process or thread with " +
                    "a space (Python 3.14 names threads like \"Thread-1 (run)\"). From then on the app can't reach the " +
                    "guest until it restarts. This diverts /usr/sbin/tcpstates-libbpf, the tool the forwarder reads, and " +
                    "puts a small wrapper in its place that joins such names with \"_\". It takes effect when the guest " +
                    "restarts, and Undo puts the original back. It runs in a new terminal; enter your password if sudo asks.",
                command = FORWARDER_FIX_COMMAND,
                done = "Port forwarding fix installed; it takes effect when the guest restarts (close Linux Terminal and open it again)",
            )
        } else {
            WorkbenchDialog.GuestCommand(
                title = "Remove the port forwarding fix?",
                text = "This puts the original /usr/sbin/tcpstates-libbpf back from the next guest restart. Linux Terminal's " +
                    "forwarder can then stop again when a thread name has a space. It runs in a new terminal.",
                command = FORWARDER_UNDO_COMMAND,
                done = "Port forwarding fix removed; the original is back when the guest restarts",
            )
        }
    }

    fun runGuestCommand(command: WorkbenchDialog.GuestCommand) {
        if (dialog == command) dialog = null
        newTerminal("${command.command} && exit", onExit = { code ->
            post { status = if (code == 0) command.done else "The command didn't finish; the terminal shows why" }
        })
    }

    // Source control ----------------------------------------------------------------------------

    var gitStatus by mutableStateOf<GitResult.Status?>(null)
        private set
    /** Set when the workspace isn't a repository (offer Init/Clone) or Git failed to run. */
    var gitProblem by mutableStateOf<String?>(null)
        private set
    var gitNotARepository by mutableStateOf(false)
        private set
    /** Git itself is missing from the guest (the check said so, or Git couldn't be started). */
    val gitMissing: Boolean
        get() = guestTool(GuestDependency.Git) == DependencyState.Missing ||
            gitProblem?.let(::missingDependencyIn) == GuestDependency.Git
    /** Label of the Git operation in flight, shown in the view. */
    var gitBusy by mutableStateOf<String?>(null)
        private set
    var commitMessage by mutableStateOf("")

    private fun runGit(label: String?, operation: GitOperation, then: (GitResult) -> Unit = {}) {
        val repo = workspaceRoot
        if (repo == null || !vfs.isConnected()) {
            // Only when asked for: background refreshes (after a save, offline) stay quiet.
            if (label != null) status = "Connect to a guest to use Git"
            return
        }
        if (label != null) gitBusy = label
        vfs.git(repo, operation) { event ->
            post {
                when (event) {
                    is GitEvent.Progress -> status = listOfNotNull(label, event.phase, event.percent?.let { "$it%" }).joinToString(" · ")
                    is GitEvent.Prompt -> dialog = WorkbenchDialog.GitPrompt(event)
                    else -> Unit
                }
            }
        }.whenComplete { result, error ->
            post {
                if (label != null) gitBusy = null
                if (dialog is WorkbenchDialog.GitPrompt) dialog = null
                if (error != null) {
                    val message = generateSequence(error) { it.cause }.last().message ?: "Git failed"
                    if (operation is GitOperation.Status) {
                        gitStatus = null
                        gitNotARepository = "not a git repository" in message.lowercase()
                        gitProblem = if (gitNotARepository) null else message
                    } else {
                        status = "Git: $message"
                    }
                    return@post
                }
                then(result)
                if (operation !is GitOperation.Status && operation !is GitOperation.DiffFile && operation !is GitOperation.Branches) {
                    refreshGitStatus()
                }
            }
        }
    }

    // Merge conflicts ---------------------------------------------------------------------------

    /** The conflicts Git left in the active file, for the editor's actions over them. */
    fun activeConflicts(): List<MergeConflict> {
        documentRevision
        val document = activeDocument?.takeUnless { it.binary } ?: return emptyList()
        if ("<<<<<<<" !in document.text) return emptyList()
        return findConflicts(document.text)
    }

    /** Accept Current, Incoming or Both for one conflict (VS Code's actions above it). */
    fun resolveConflict(conflict: MergeConflict, choice: ConflictChoice) = resolveConflicts(listOf(conflict), choice)

    /** For all conflicts in the file ("Accept All Current/Incoming/Both"). */
    fun resolveAllConflicts(choice: ConflictChoice) = resolveConflicts(activeConflicts(), choice)

    private fun resolveConflicts(conflicts: List<MergeConflict>, choice: ConflictChoice) {
        val document = activeDocument ?: return
        if (conflicts.isEmpty()) {
            status = "No merge conflicts in ${document.path.substringAfterLast('/')}"
            return
        }
        val lines = document.text.split('\n')
        val edits = conflicts.map { conflict ->
            val replacement = conflictResolution(document.text, conflict, choice)
            if (conflict.end + 1 < lines.size) {
                LspTextEdit(LspRange(LspPosition(conflict.start, 0), LspPosition(conflict.end + 1, 0)), replacement.joinToString("") { "$it\n" })
            } else {
                LspTextEdit(LspRange(LspPosition(conflict.start, 0), LspPosition(conflict.end, lines[conflict.end].length)), replacement.joinToString("\n"))
            }
        }
        if (editorHost.isOpen(document.path)) {
            editorHost.applyEdits(document.path, edits)
        } else {
            document.updateText(applyLspTextEdits(document.text, edits))
            onDocumentEdited(document)
        }
        documentRevision++
        val left = findConflicts(document.text).size
        status = if (left == 0) {
            "No conflicts left in ${document.path.substringAfterLast('/')}; save it and stage it in Source Control to mark it resolved"
        } else {
            "$left conflict${if (left == 1) "" else "s"} left"
        }
    }

    /** Moves the cursor to the next (or previous) conflict, wrapping around. */
    fun goToConflict(forward: Boolean) {
        val conflicts = activeConflicts()
        val surface = editorSurface
        if (conflicts.isEmpty() || surface == null) {
            status = "No merge conflicts in this file"
            return
        }
        val line = cursorPosition?.line ?: 0
        val target = if (forward) conflicts.firstOrNull { it.start > line } ?: conflicts.first()
        else conflicts.lastOrNull { it.start < line } ?: conflicts.last()
        surface.moveCursor(LspPosition(target.start, 0))
    }

    /**
     * Explorer's marks by absolute path: Git state (`git.decorations.enabled`) and problems
     * (`problems.decorations.enabled`) on files, and the most important of them on folders.
     */
    val explorerMarks: Map<String, FileDecoration> by derivedStateOf {
        val root = workspaceRoot ?: return@derivedStateOf emptyMap()
        val problems = HashMap<String, Pair<Int, Int>>()
        if (settings.problemDecorations) {
            diagnostics.forEach { diagnostic ->
                val path = runCatching { java.net.URI(diagnostic.uri).path }.getOrNull() ?: return@forEach
                val (errors, warnings) = problems[path] ?: (0 to 0)
                when (diagnostic.severity ?: 1) {
                    1 -> problems[path] = errors + 1 to warnings
                    2 -> problems[path] = errors to warnings + 1
                }
            }
        }
        explorerDecorations(root, gitStatus?.entries.orEmpty(), problems, settings.gitDecorations, settings.problemDecorations)
    }

    fun refreshGitStatus() = runGit(null, GitOperation.Status) { result ->
        gitStatus = result as? GitResult.Status
        gitNotARepository = false
        gitProblem = null
    }

    fun stageFiles(paths: List<String>) {
        if (paths.isNotEmpty()) runGit("Staging", GitOperation.Stage(paths))
    }

    fun unstageFiles(paths: List<String>) {
        if (paths.isNotEmpty()) runGit("Unstaging", GitOperation.Unstage(paths))
    }

    /** Discarding is destructive (untracked files are deleted), so it is confirmed first. */
    fun requestDiscard(entries: List<GitStatusEntry>) {
        if (entries.isEmpty()) return
        dialog = WorkbenchDialog.GitConfirmDiscard(entries.map { it.path }, entries.count { it.status == GitFileStatus.Untracked })
    }

    fun discardFiles(paths: List<String>) {
        dialog = null
        runGit("Discarding", GitOperation.Discard(paths)) { status = "Discarded ${paths.size} file${if (paths.size == 1) "" else "s"}" }
    }

    /** Commits what is staged; with nothing staged it stages every change first (VS Code's smart commit). */
    fun commit() {
        val message = commitMessage.trim()
        val current = gitStatus ?: return
        if (message.isEmpty()) {
            status = "Write a commit message first"
            return
        }
        val doCommit = {
            runGit("Committing", GitOperation.Commit(message)) {
                commitMessage = ""
                status = "Committed: ${message.lineSequence().first()}"
            }
        }
        if (current.entries.none { it.staged }) {
            val all = current.entries.filter { it.unstaged }.map { it.path }
            if (all.isEmpty()) {
                status = "Nothing to commit"
                return
            }
            runGit("Staging", GitOperation.Stage(all)) { doCommit() }
        } else {
            doCommit()
        }
    }

    fun showGitDiff(entry: GitStatusEntry, staged: Boolean) {
        // A conflicted file opens in the editor, where each conflict has its Accept actions.
        if (entry.status == GitFileStatus.Untracked || entry.status == GitFileStatus.Conflicted) {
            workspaceRoot?.let { openPath("${it.trimEnd('/')}/${entry.path}") }
            return
        }
        runGit(null, GitOperation.DiffFile(entry.path, staged)) { result ->
            val diff = result as? GitResult.Diff ?: return@runGit
            dialog = WorkbenchDialog.GitDiff(
                "${entry.path.substringAfterLast('/')} (${if (staged) "staged" else "working tree"})",
                diff.patch.ifBlank { "No textual changes." },
            )
        }
    }

    fun answerGitPrompt(prompt: GitEvent.Prompt, answer: String?) {
        dialog = null
        // An empty answer makes Git fail the credential request instead of hanging.
        vfs.answerGitPrompt(prompt.requestId, prompt.promptId, answer.orEmpty())
    }

    fun showBranches() = runGit(null, GitOperation.Branches) { result ->
        (result as? GitResult.Branches)?.let { dialog = WorkbenchDialog.GitBranches(it.branches) }
    }

    fun checkoutBranch(name: String, create: Boolean) {
        dialog = null
        runGit(if (create) "Creating branch" else "Switching branch", GitOperation.Checkout(name, create)) {
            status = "On branch $name"
        }
    }

    fun gitFetch() = runGit("Fetching", GitOperation.Fetch) { status = "Fetched" }

    fun gitPull() = runGit("Pulling", GitOperation.Pull()) { status = "Pulled" }

    /** Pushes, publishing the branch (set upstream) when it has no upstream yet. */
    fun gitPush() {
        val publish = gitStatus?.upstream == null
        runGit(if (publish) "Publishing branch" else "Pushing", GitOperation.Push(setUpstream = publish)) {
            status = if (publish) "Branch published" else "Pushed"
        }
    }

    /** VS Code's Sync: pull, then push. */
    fun gitSync() = runGit("Pulling", GitOperation.Pull()) { gitPush() }

    fun gitInit() = runGit("Initializing", GitOperation.Init) { status = "Initialized a Git repository" }

    fun gitClone(url: String, folder: String) {
        val root = workspaceRoot ?: return
        dialog = null
        runGit("Cloning", GitOperation.Clone(url.trim(), "${root.trimEnd('/')}/${folder.trim()}")) {
            status = "Cloned into ${folder.trim()}"
            loadDirectory(root)
        }
    }

    // Guest setup, pairing and reset detection ----------------------------------------------


    private var wasConnected = false
    private var guestUnreachable = false
    /** Connection refusals in a row; after two, Linux Terminal itself is the likely culprit. */
    private var unreachableFailures = 0
    /** Set when the guest probably runs but Linux Terminal stopped forwarding its port. */
    var guestForwardingSuspected by mutableStateOf(false)
        private set
    private var reconnectAttempt = 0
    private var scheduledReconnect: ScheduledFuture<*>? = null

    /**
     * Retries a lost connection with backoff: 2 s … 60 s and ten attempts in the background, but
     * at most [FOREGROUND_RECONNECT_CAP_S] apart and without a limit while the app is in front, so
     * a guest that comes back is noticed within seconds.
     */
    private fun scheduleReconnect() {
        if (!settings.autoConnect || endpoint.isBlank() || token.isBlank() || scheduledReconnect != null) return
        if (!appVisible && reconnectAttempt >= 10) return
        val backoff = listOf(2L, 5L, 10L, 20L, 30L, 60L)[reconnectAttempt.coerceAtMost(5)]
        val delay = if (appVisible) backoff.coerceAtMost(FOREGROUND_RECONNECT_CAP_S) else backoff
        reconnectAttempt++
        status = "Guest disconnected; reconnecting in ${delay}s"
        scheduledReconnect = timer.schedule({
            post {
                scheduledReconnect = null
                if (!connected && !busy) {
                    autoConnecting = true
                    connectGuest()
                }
            }
        }, delay, TimeUnit.SECONDS)
    }

    private fun onConnectFailed(error: Throwable) {
        if (workspaceRoot == null && settings.offlineWorkspace) openOfflineWorkspace()
        val causes = generateSequence(error) { it.cause }.toList()
        val rejected = causes.filterIsInstance<GuestRejectedException>().firstOrNull()
        val unreachable = causes.any { it is java.net.ConnectException || it is java.net.SocketTimeoutException }
        val wasAutomatic = autoConnecting
        autoConnecting = false
        when {
            rejected?.reason == "InvalidToken" -> {
                guestProblem = "The guest no longer accepts this device's token. Was Linux Terminal reset?"
                status = guestProblem!!
                beginGuestSetup(
                    "The guest rejected the saved token, which happens after Linux Terminal is reset or the " +
                        "daemon is reinstalled. Set it up again, or if it is still installed, run " +
                        "`avf-native-daemon pair` in Linux Terminal and enter the new code.",
                )
            }
            unreachable -> {
                guestUnreachable = true
                unreachableFailures++
                // Seen on the tablet: the guest and daemon run, but Terminal reports no forwarded
                // port until it is force-stopped and opened again.
                guestForwardingSuspected = unreachableFailures >= 2
                guestProblem = if (guestForwardingSuspected) {
                    "Still can't reach the guest daemon. If Linux Terminal is already open, it may have stopped " +
                        "forwarding the connection: Force stop it in App info (this restarts the guest), then open it again."
                } else {
                    "Can't reach the guest daemon. Open Linux Terminal so the guest starts; if it was reset, set it up again."
                }
                status = guestProblem!!
                if (!wasAutomatic && dialog == WorkbenchDialog.Connect) dialog = null
                if (reconnectAttempt > 0) scheduleReconnect()
            }
            else -> {
                guestProblem = "Connection failed: ${rejected?.message ?: error.cause?.message ?: error.message}"
                status = guestProblem!!
            }
        }
    }

    fun beginGuestSetup(reason: String? = null) {
        pairingError = null
        dialog = WorkbenchDialog.GuestSetup(reason)
        if (setupCommand == null) prepareGuestSetup()
    }

    /** Copies the bundled daemon and installer to Downloads and builds the Linux Terminal command. */
    fun prepareGuestSetup() {
        setupError = null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            setupError = "Setting up the guest from the app needs Android 10 or later"
            return
        }
        io.execute {
            val result = runCatching { exportGuestBundle(context) }
            post {
                result.onSuccess { setupCommand = it }
                result.onFailure { setupError = it.message ?: "Could not write the setup files" }
            }
        }
    }

    fun copySetupCommand() {
        val command = setupCommand ?: return
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Setup command", command))
        status = "Command copied; paste it in Linux Terminal"
    }

    /** Linux Terminal's App info, where Force stop restarts the guest VM. */
    fun openLinuxTerminalAppInfo() {
        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(android.net.Uri.fromParts("package", LINUX_TERMINAL_PACKAGE, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure { status = "Could not open App info: ${it.message}" }
    }

    fun openLinuxTerminal() {
        // The launcher intent brings a running Terminal forward; a bare ACTION_MAIN would start a
        // second Terminal screen that never attaches to the VM.
        val intent = (
            context.packageManager.getLaunchIntentForPackage(LINUX_TERMINAL_PACKAGE)
                ?: Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setClassName(LINUX_TERMINAL_PACKAGE, "$LINUX_TERMINAL_PACKAGE.MainActivity")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        if (runCatching { context.startActivity(intent) }.isFailure) {
            status = "Linux Terminal isn't installed; enable it in Developer options"
        } else if (!connected) {
            // The VM takes a few seconds to boot; keep trying while the user is in Terminal.
            reconnectAttempt = 0
            scheduleReconnect()
        }
    }

    /** Trades the code the installer printed for the token, saves it in the profile and connects. */
    fun pairAndConnect(code: String) {
        val target = endpoint.trim().ifEmpty { GUEST_DAEMON_ENDPOINT }
        busy = true
        pairingError = null
        status = "Pairing with the guest"
        vfs.pair(target, code).whenComplete { newToken, error ->
            post {
                busy = false
                if (error != null) {
                    val cause = generateSequence(error) { it.cause }.firstOrNull { it is GuestRejectedException } ?: error.cause ?: error
                    pairingError = if (cause is java.net.ConnectException) {
                        "Can't reach the guest. Is the installer finished, and was port 19870 accepted?"
                    } else {
                        cause.message ?: "Pairing failed"
                    }
                    status = pairingError!!
                    return@post
                }
                endpoint = target
                token = newToken
                if (profileName.isBlank()) profileName = "Linux guest"
                dialog = null
                setupCommand = null
                connectGuest()
            }
        }
    }

    /**
     * Pairs with a Linux or WSL host from the string `avf-daemon pair --remote` printed: pins the
     * host's certificate, trades the code for the token, saves a profile and connects.
     */
    fun pairWithConnectionString(text: String) {
        val parsed = parseConnectionString(text) ?: run {
            pairingError = "That isn't a connection string; it starts with avf:// and has fp= and code="
            return
        }
        busy = true
        pairingError = null
        status = "Pairing with ${parsed.host}"
        vfs.pair(parsed.endpoint, parsed.code, parsed.fingerprint).whenComplete { newToken, error ->
            post {
                busy = false
                if (error != null) {
                    val causes = generateSequence(error) { it.cause }.toList()
                    pairingError = when {
                        causes.any { it is java.security.cert.CertificateException } ->
                            "The host's certificate doesn't match the connection string; run the pair command again"
                        causes.any { it is java.net.ConnectException || it is java.net.SocketTimeoutException } ->
                            "Can't reach ${parsed.host}:${parsed.port}. Same network? Firewall open for that port?"
                        else -> (causes.firstOrNull { it is GuestRejectedException } ?: causes.last()).message ?: "Pairing failed"
                    }
                    status = pairingError!!
                    return@post
                }
                val existing = profiles.firstOrNull { it.endpoint == parsed.endpoint }
                profileId = existing?.id
                profileName = existing?.name ?: parsed.host
                endpoint = parsed.endpoint
                token = newToken
                profileFingerprint = parsed.fingerprint
                dialog = null
                connectGuest()
            }
        }
    }

    // Session restore ------------------------------------------------------------------------

    /** Remembers open guest files, the active one and the Explorer folder for the next launch. */
    private fun layoutValue(key: String, fallback: String): String = preferences.getString(key, fallback) ?: fallback

    private fun saveLayout(key: String, value: String) {
        if (preferences.getString(key, null) != value) preferences.edit().putString(key, value).apply()
    }

    private fun persistSession() {
        val gson = com.google.gson.Gson()
        val untitled = documents.map { it.path }.filter { it.startsWith("scratch:") }
        val device = documents.map { it.path }.filter(::isDevicePath)
        preferences.edit()
            .putString(SESSION_UNTITLED_KEY, gson.toJson(untitled))
            .putString(SESSION_DEVICE_KEY, gson.toJson(device))
            .apply()
        // Before a workspace's tabs are restored the list is partial, and while switching the old
        // tabs close one by one: neither may overwrite what was saved.
        val workspace = sessionWorkspace ?: return
        if (!sessionRestored || switchingWorkspace) return
        val session = WorkspaceSession(
            open = documents.map { it.path }.filter(::isGuestPath),
            active = activePath?.takeIf(::isGuestPath),
            directory = currentDirectory.ifEmpty { null },
            expanded = expandedFolders.toList(),
            pinned = pinnedTabs.filter(::isGuestPath),
            preview = previewTab?.takeIf(::isGuestPath),
        )
        preferences.edit().putString(workspaceSessionKey(workspace), gson.toJson(session)).apply()
    }

    private fun restoreSession(workspace: String) {
        if (sessionRestored) return
        sessionRestored = true
        if (!settings.restoreSession) return
        val gson = com.google.gson.Gson()
        val saved = preferences.getString(workspaceSessionKey(workspace), null)
            ?.let { runCatching { gson.fromJson(it, WorkspaceSession::class.java) }.getOrNull() }
            ?: legacySession(workspace)
        val open = saved.open.orEmpty().filter { isPathWithinRoot(it, workspace) }
        pinnedTabs.addAll(saved.pinned.orEmpty().filter { it in open && it !in pinnedTabs })
        open.sortedBy { it == saved.active }.forEach { openPath(it, preview = it == saved.preview) }
        // Offline there is no folder to list; Explorer shows the files this device has instead.
        if (!vfs.isConnected()) return
        saved.directory?.takeIf { isPathWithinRoot(it, workspace) && it != workspace }?.let(::loadDirectory)
        saved.expanded.orEmpty().filter { isPathWithinRoot(it, workspace) }.forEach { path ->
            expandedFolders.add(path)
            loadFolderChildren(path)
        }
    }

    /** The single session earlier versions kept for whichever workspace was open. */
    private fun legacySession(workspace: String): WorkspaceSession {
        val gson = com.google.gson.Gson()
        fun list(key: String) = runCatching {
            gson.fromJson(preferences.getString(key, "[]"), Array<String>::class.java).toList()
        }.getOrDefault(emptyList()).filter { isPathWithinRoot(it, workspace) }
        return WorkspaceSession(
            open = list("session-open"),
            active = preferences.getString("session-active", null),
            directory = preferences.getString("session-directory", null),
            expanded = list("session-expanded"),
        )
    }

    private fun restoreUntitledFiles() {
        if (!settings.restoreSession) return
        val paths = runCatching {
            com.google.gson.Gson().fromJson(preferences.getString(SESSION_UNTITLED_KEY, "[]"), Array<String>::class.java).toList()
        }.getOrDefault(emptyList())
        paths.filter { it.startsWith("scratch:") }.forEach { path ->
            val cached = cache.load(path) ?: return@forEach
            documents.add(DocumentSession(cached.path, cached.text, cached.modifiedTs, cached.dirty, encoding = cached.encoding, eol = cached.eol))
        }
        activePath = documents.lastOrNull()?.path
        runCatching {
            com.google.gson.Gson().fromJson(preferences.getString(SESSION_DEVICE_KEY, "[]"), Array<String>::class.java).toList()
        }.getOrDefault(emptyList()).filter(::isDevicePath).forEach(::openPath)
    }

    // Offline ---------------------------------------------------------------------------------

    /** A folder is open but no guest is connected: tabs come from this device's copies. */
    val offline: Boolean get() = workspaceRoot != null && !connected

    /** Set by the activity: Android's file picker, whose choice comes back as [openDeviceFile]. */
    var pickLocalFile: (() -> Unit)? = null

    /**
     * Without a guest, opens a recent folder ([path], else the last one) from this device's
     * copies: its tabs as they were, editable; saves wait for the guest (the offline queue).
     */
    fun openOfflineWorkspace(path: String? = null) {
        if (connected) return
        val target = path ?: recentWorkspaces.firstOrNull() ?: return
        if (workspaceRoot == target) return
        // Another folder's tabs close as on switching folders: unsaved edits stay as drafts.
        persistSession()
        documents.filter { isGuestPath(it.path) }.forEach { document ->
            io.execute { repository.persistDraft(document) }
            closeDocument(document.path)
        }
        workspaceRoot = target
        sessionWorkspace = target
        sessionRestored = false
        restoreSession(target)
        status = "Offline: ${target.substringAfterLast('/')} from this device; saves wait for the guest"
    }

    /** The guest files of [workspace] this device keeps a copy of, for Explorer offline: the ones opened since, and its tabs. */
    fun offlineFiles(workspace: String? = workspaceRoot): List<String> {
        if (workspace == null) return emptyList()
        val remembered = runCatching {
            com.google.gson.Gson().fromJson(preferences.getString(OFFLINE_FILES_PREFIX + workspace, "[]"), Array<String>::class.java).toList()
        }.getOrDefault(emptyList())
        val tabs = runCatching {
            com.google.gson.Gson().fromJson(preferences.getString(workspaceSessionKey(workspace), null), WorkspaceSession::class.java).open.orEmpty()
        }.getOrDefault(emptyList())
        return (remembered + tabs).distinct().filter(repository::hasCopy)
    }

    private fun rememberOfflineFile(path: String) {
        val workspace = workspaceRoot?.takeIf { isPathWithinRoot(path, it) } ?: return
        val files = (listOf(path) + offlineFiles(workspace).filterNot { it == path }).take(OFFLINE_FILES_LIMIT)
        preferences.edit().putString(OFFLINE_FILES_PREFIX + workspace, com.google.gson.Gson().toJson(files)).apply()
    }

    private fun forgetOfflineFile(path: String) {
        val workspace = workspaceRoot ?: return
        val files = offlineFiles(workspace)
        if (path in files) preferences.edit().putString(OFFLINE_FILES_PREFIX + workspace, com.google.gson.Gson().toJson(files - path)).apply()
    }

    /** A file picked in Android's picker: kept readable and writable across restarts, then opened. */
    fun openDeviceFile(uri: android.net.Uri) {
        val resolver = context.contentResolver
        val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { resolver.takePersistableUriPermission(uri, flags) }
            .recoverCatching { resolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val name = runCatching {
            resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        openPath(devicePath(uri.toString(), name))
    }

    /**
     * After (re)connecting: clean tabs whose file changed in the guest meanwhile (opened offline
     * from an older copy, or edited elsewhere) are read again. Edited tabs go through
     * [reconcileDocument] and its conflict check instead.
     */
    private fun refreshCleanDocuments() {
        documents.filter { isGuestPath(it.path) && !it.dirty && it.path !in notebooks }.forEach { document ->
            vfs.stat(document.path).whenComplete { entry, error ->
                if (error != null || entry.isDirectory || entry.modifiedTs == document.baseModifiedTs) return@whenComplete
                repository.open(document.path).whenComplete { result, _ ->
                    val fresh = (result as? OpenDocument.Editable)?.document ?: return@whenComplete
                    post {
                        if (document.dirty || documents.none { it === document } || fresh.text == document.text) return@post
                        document.baseModifiedTs = fresh.baseModifiedTs
                        document.updateText(fresh.text)
                        document.markSaved(fresh.text)
                        if (activePath == document.path) editorSurface?.setText(fresh.text)
                        documentRevision++
                        status = "Reloaded ${document.path.substringAfterLast('/')}: it changed in the guest"
                    }
                }
            }
        }
    }

    // Workspaces ------------------------------------------------------------------------------

    private fun loadRecentWorkspaces(): List<String> = runCatching {
        com.google.gson.Gson().fromJson(preferences.getString(RECENT_WORKSPACES_KEY, "[]"), Array<String>::class.java).toList()
    }.getOrDefault(emptyList())

    /** Recent folders this guest serves, the open one left out. */
    val recentWorkspaceChoices: List<String>
        get() = recentWorkspaces.filter { recent -> recent != workspaceRoot && roots.any { isPathWithinRoot(recent, it) } }

    private fun rememberWorkspace(path: String) {
        recentWorkspaces = (listOf(path) + recentWorkspaces.filterNot { it == path }).take(RECENT_WORKSPACES_LIMIT)
        preferences.edit().putString(RECENT_WORKSPACES_KEY, com.google.gson.Gson().toJson(recentWorkspaces)).apply()
        refreshLauncherShortcuts()
    }

    fun forgetRecentWorkspace(path: String) {
        recentWorkspaces = recentWorkspaces.filterNot { it == path }
        preferences.edit().putString(RECENT_WORKSPACES_KEY, com.google.gson.Gson().toJson(recentWorkspaces)).apply()
        refreshLauncherShortcuts()
    }

    /** The launcher's shortcuts follow the recent folders (`avf.launcher.recentFolders`). */
    private fun refreshLauncherShortcuts() {
        val folders = if (settings.launcherShortcuts) recentWorkspaces else emptyList()
        io.execute { updateLauncherShortcuts(context, folders) }
    }

    /** An intent from [IntentRouter]: "Open with" from another app, or a recent-folder shortcut. */
    fun handleIntent(intent: android.content.Intent) {
        when (intent.action) {
            IntentRouter.ACTION_OPEN_FOLDER -> intent.getStringExtra(IntentRouter.EXTRA_FOLDER)?.let(::openFolderFromLauncher)
            android.content.Intent.ACTION_VIEW, android.content.Intent.ACTION_EDIT -> {
                val uri = intent.data?.takeIf { it.scheme == "content" } ?: return
                openDeviceFile(uri)
                compactDestination = CompactDestination.Editor
            }
        }
    }

    /** A launcher shortcut's folder: opened now, or first once the guest connects. */
    private fun openFolderFromLauncher(path: String) {
        if (connected) {
            openWorkspace(path)
        } else {
            rememberWorkspace(path)
            status = "Opening ${path.substringAfterLast('/')} when the guest connects"
        }
    }

    /** The folder to open on connecting: the last one, if this guest still has it, else its first root. */
    private fun chooseWorkspace(guestRoots: List<String>, then: (String) -> Unit) {
        val last = recentWorkspaces.firstOrNull { recent -> guestRoots.any { isPathWithinRoot(recent, it) } }
        if (last == null) {
            guestRoots.firstOrNull()?.let(then)
            return
        }
        vfs.stat(last).whenComplete { entry, error ->
            post { (if (error == null && entry?.isDirectory == true) last else guestRoots.firstOrNull())?.let(then) }
        }
    }

    /** File > Open Folder: browses the guest's roots for a folder to open as the workspace. */
    fun showOpenFolder(path: String? = null) {
        if (!connected) {
            status = "Connect to a guest to open a folder"
            dialog = WorkbenchDialog.Connect
            return
        }
        val start = path
            ?: workspaceRoot?.substringBeforeLast('/')?.takeIf { parent -> roots.any { isPathWithinRoot(parent, it) } }
            ?: roots.firstOrNull()
            ?: return
        dialog = WorkbenchDialog.OpenFolder(start)
        vfs.listDir(start).whenComplete { result, error ->
            post {
                val shown = dialog as? WorkbenchDialog.OpenFolder ?: return@post
                if (shown.path != start) return@post
                dialog = shown.copy(
                    folders = result?.let { sortedEntries(it).filter(FileEntry::isDirectory) },
                    error = error?.let { it.cause?.message ?: it.message ?: "Could not list the folder" },
                )
            }
        }
    }

    /** Creates [name] inside the folder the Open Folder dialog shows, then shows the new folder. */
    fun createFolderToOpen(parent: String, name: String) {
        val clean = name.trim().trim('/')
        if (clean.isEmpty() || clean.contains('/')) {
            (dialog as? WorkbenchDialog.OpenFolder)?.let { dialog = it.copy(error = "Type a folder name without slashes") }
            return
        }
        val target = "${parent.trimEnd('/')}/$clean"
        vfs.create(target, directory = true).whenComplete { _, error ->
            post {
                if (error != null) {
                    (dialog as? WorkbenchDialog.OpenFolder)?.let { dialog = it.copy(error = error.cause?.message ?: error.message) }
                } else {
                    showOpenFolder(target)
                }
            }
        }
    }

    /**
     * Makes [path] the workspace, as VS Code's Open Folder does in its window. Tabs close with
     * their drafts kept, so unsaved work comes back with its folder; language servers, Copilot and
     * the chat agent restart in the new folder. Terminals keep running where they are.
     */
    fun openWorkspace(path: String) {
        val target = normalizeGuestPath(path) ?: return
        if (dialog is WorkbenchDialog.OpenFolder) dialog = null
        if (!connected) {
            status = "Connect to a guest to open a folder"
            return
        }
        if (roots.none { isPathWithinRoot(target, it) }) {
            status = "$target is outside the folders the guest daemon serves"
            return
        }
        if (target == workspaceRoot) return
        persistSession()
        switchingWorkspace = true
        documents.map { it.path }.filter(::isGuestPath).forEach { open ->
            notebooks[open]?.takeIf { it.notebook.dirty }?.let { session -> session.document.updateText(session.notebook.toJson()) }
            documents.firstOrNull { it.path == open }?.let { document -> io.execute { repository.persistDraft(document) } }
            closeDocument(open)
        }
        switchingWorkspace = false
        languageClients.values.filter { it.isStarted }.forEach(LanguageClient::close)
        copilot.language.close()
        newChat()
        chatAgent.stop()
        if (debugState != DebugState.Idle) stopDebugging()
        diagnostics.clear()
        languageServerIssues = emptyList()
        expandedFolders.clear()
        folderChildren.clear()
        entries = emptyList()
        currentDirectory = ""
        quickOpenIndex = emptyList()
        searchResults.clear()
        searchSummary = null
        gitStatus = null
        gitProblem = null
        gitNotARepository = false
        workspaceRoot = null
        sessionRestored = false
        onWorkspaceConnected(target)
        status = "Opened ${target.substringAfterLast('/').ifEmpty { target }}"
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
        autoConnecting = false
        sessionWorkspace = workspace
        rememberWorkspace(workspace)
        loadDirectory(workspace)
        restoreSession(workspace)
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
                refreshGitStatus()
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
                    entries = sortedEntries(result)
                    persistSession()
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
                val nested = children.filter { it.isDirectory && walksIntoForQuickOpen(it.name) }.map { walk(it.path, depth + 1) }
                CompletableFuture.allOf(*nested.toTypedArray()).thenApply { }
            }.exceptionally { }
        walk(root, 0).whenComplete { _, _ ->
            post {
                quickOpenIndex = files.sortedBy { it.path }
                quickOpenIndexing = false
            }
        }
    }

    /** A folder row in the tree: opens or closes it in place, loading its children the first time. */
    fun toggleFolder(path: String) {
        if (path in expandedFolders) {
            expandedFolders.remove(path)
            persistSession()
            return
        }
        expandedFolders.add(path)
        persistSession()
        loadFolderChildren(path)
    }

    private fun loadFolderChildren(path: String) {
        vfs.listDir(path).whenComplete { result, error ->
            post {
                if (error != null) {
                    expandedFolders.remove(path)
                    folderChildren.remove(path)
                    status = "Explorer error: ${error.message ?: "request failed"}"
                } else {
                    folderChildren[path] = sortedEntries(result)
                }
            }
        }
    }

    /** Collapses every folder open in the tree (VS Code's Collapse Folders). */
    fun collapseFolders() {
        expandedFolders.clear()
        persistSession()
    }

    fun navigateToDirectory(path: String) {
        sidebar = SidebarView.Explorer
        compactDestination = CompactDestination.Files
        loadDirectory(path)
    }

    /**
     * Opens [path] in a tab, or selects its tab. With [preview] (a single tap in Explorer or on a
     * search match) it is a preview tab that the next such open replaces; a second tap within
     * [DOUBLE_TAP_MS] keeps it, like a double click in VS Code.
     */
    fun openPath(path: String, preview: Boolean = false) {
        // Offline, a guest file opens from this device's copy if there is one.
        if (!vfs.isConnected() && isGuestPath(path) && !repository.hasCopy(path)) {
            status = "Connect to a guest before opening files"
            return
        }
        val now = android.os.SystemClock.uptimeMillis()
        val doubleTap = preview && lastPreviewOpen?.let { (last, time) -> last == path && now - time < DOUBLE_TAP_MS } == true
        lastPreviewOpen = if (preview && !doubleTap) path to now else null
        documents.firstOrNull { it.path == path }?.let {
            if (doubleTap || !preview) keepTab(path)
            selectDocument(path)
            return
        }
        if (preview && !doubleTap && settings.enablePreview && !path.endsWith(".ipynb", ignoreCase = true)) {
            previewRequests += path
        } else {
            previewRequests -= path
        }
        // Images open in the viewer, which reads the file itself; there is no text to load.
        if (isImagePath(path)) {
            if (isGuestPath(path) && vfs.isConnected()) rememberOfflineFile(path)
            addTab(DocumentSession(path, "", null, dirty = false, binary = true), asPreview = previewRequests.remove(path))
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
                if (isGuestPath(result.document.path) && vfs.isConnected()) rememberOfflineFile(result.document.path)
                val existing = documents.firstOrNull { it.path == result.document.path }
                val session = existing ?: result.document.also { addTab(it, asPreview = previewRequests.remove(it.path)) }
                if (session.path.endsWith(".ipynb", ignoreCase = true) && !openNotebook(session)) {
                    documents.remove(session)
                    return
                }
                // An SVG opens drawn (avf.svg.openAsImage); the breadcrumb's buttons show its source.
                if (existing == null && isSvgPath(session.path) && settings.svgAsImage) markdownPreviews[session.path] = MarkdownPreviewMode.Only
                selectDocument(session.path)
                status = if (session.conflict != null) "Remote changes detected; choose how to resolve" else "Opened ${session.path.substringAfterLast('/')}"
                fetchLanguagePackIfWanted(session.path)
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
        persistSession()
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

    /** Tabs closed by "Save" once their save completes. */
    private val closeAfterSave = mutableSetOf<String>()

    private fun hasUnsavedChanges(path: String): Boolean =
        notebooks[path]?.notebook?.dirty ?: (documents.firstOrNull { it.path == path }?.dirty == true)

    /** The tab's close button and Ctrl+W: asks first when the tab has unsaved changes. */
    fun requestCloseDocument(path: String) {
        if (hasUnsavedChanges(path)) dialog = WorkbenchDialog.ConfirmClose(path) else closeDocument(path)
    }

    /** Tabs still to close after the current Save / Don't save prompt (Close Other Editors). */
    private val closeQueue = ArrayDeque<String>()

    /** Closes [paths], asking about each one with unsaved changes in turn; Cancel stops. */
    fun requestCloseDocuments(paths: List<String>) {
        closeQueue.clear()
        closeQueue.addAll(paths)
        continueClosing()
    }

    private fun continueClosing() {
        while (closeQueue.isNotEmpty()) {
            val path = closeQueue.removeFirst()
            if (hasUnsavedChanges(path)) {
                dialog = WorkbenchDialog.ConfirmClose(path)
                return
            }
            closeDocument(path)
        }
    }

    fun saveAndClose(path: String) {
        dialog = null
        activePath = path
        closeAfterSave += path
        saveActiveDocument()
        continueClosing()
    }

    fun closeDiscarding(path: String) {
        dialog = null
        closeDocument(path)
        repository.forget(path)
        forgetOfflineFile(path)
        continueClosing()
    }

    fun cancelClosing() {
        dialog = null
        closeQueue.clear()
    }

    private fun closeIfPendingAfterSave(path: String) {
        if (closeAfterSave.remove(path)) closeDocument(path)
    }

    fun closeDocument(path: String) {
        if (previewTab == path) previewTab = null
        pinnedTabs.remove(path)
        documents.firstOrNull { it.path == path }?.let(closedDocuments::add)
        notifyDocumentClosed(path)
        notebooks.remove(path)?.kernel?.stop()
        markdownPreviews.remove(path)
        closeNotebookMirror(path)
        documents.removeAll { it.path == path }
        if (activePath == path) activePath = documents.lastOrNull()?.path
        persistSession()
        scheduleIdleServerStop()
    }

    // Ports -------------------------------------------------------------------------------------

    /** TCP ports listening in the guest, from the daemon's scan (IPv4 and IPv6 apart). */
    val guestPorts = mutableStateListOf<GuestPort>()
    private var portsConnectedAt = 0L
    /**
     * Ports the view hides unless asked: the daemon's own, Jupyter kernels', the app's bridges,
     * and other users' (the guest's services: ssh, DNS, Linux Terminal's ttyd), whose command
     * the daemon can't read.
     */
    var showInternalPorts by mutableStateOf(false)

    /** One row per port number; the command comes from whichever socket had one. */
    val listedPorts: List<GuestPort>
        get() = guestPorts.groupBy { it.port }.map { (port, sockets) ->
            GuestPort(port, sockets.all { it.ipv6 }, sockets.firstNotNullOfOrNull { it.command })
        }.sortedBy { it.port }

    val visiblePorts: List<GuestPort> get() = listedPorts.filterNot(::isInternalPort)

    fun isInternalPort(port: GuestPort): Boolean {
        val daemonPort = runCatching { Uri.parse(endpoint.replaceFirst("ws", "http")).port }.getOrDefault(-1)
        val command = port.command.orEmpty()
        return port.command == null || port.port == daemonPort || port.port == DAEMON_PORT ||
            INTERNAL_COMMANDS.any { it in command }
    }

    private fun onGuestPort(port: GuestPort, open: Boolean) {
        guestPorts.removeAll { it.port == port.port && it.ipv6 == port.ipv6 }
        if (!open) return
        val known = guestPorts.any { it.port == port.port }
        guestPorts.add(port)
        // The list sent on connecting is not news; a server started since is.
        val settled = android.os.SystemClock.uptimeMillis() - portsConnectedAt > 3_000
        if (settled && !known && settings.portNotifications && !isInternalPort(port)) {
            status = "A server is listening on port ${port.port}${port.command?.let { " (${it.take(40)})" }.orEmpty()}; open it from Ports"
        }
    }

    /** Opens a guest port in the browser; Linux Terminal forwards it to this tablet once allowed. */
    fun openPort(port: GuestPort) = openLink("http://localhost:${port.port}")

    fun copyPortAddress(port: GuestPort) {
        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Address", "http://localhost:${port.port}"))
        status = "Copied http://localhost:${port.port}"
    }

    // Tabs --------------------------------------------------------------------------------------

    /** The preview tab (italic): the next file opened with a single tap replaces it (`workbench.editor.enablePreview`). */
    var previewTab by mutableStateOf<String?>(null)
        private set
    /** Pinned tabs: first in the strip, left open by Close Other Editors, and saved with the folder. */
    val pinnedTabs = mutableStateListOf<String>()
    /** Files being opened as the preview tab. */
    private val previewRequests = mutableSetOf<String>()
    private var lastPreviewOpen: Pair<String, Long>? = null

    /** Puts a newly opened tab in the strip: pinned ones after the other pinned, a preview in place of the old preview. */
    private fun addTab(document: DocumentSession, asPreview: Boolean) {
        val oldPreview = previewTab?.takeIf { asPreview }?.let { path -> documents.firstOrNull { it.path == path } }
        when {
            document.path in pinnedTabs -> documents.add(documents.count { it.path in pinnedTabs }, document)
            oldPreview != null && !oldPreview.dirty -> {
                val index = documents.indexOf(oldPreview)
                closeDocument(oldPreview.path)
                documents.add(index.coerceIn(0, documents.size), document)
            }
            else -> documents.add(document)
        }
        if (asPreview) previewTab = document.path
    }

    /** The preview tab stays open: edited, double-tapped, pinned, or Keep Editor Open. */
    fun keepTab(path: String) {
        if (previewTab == path) {
            previewTab = null
            persistSession()
        }
    }

    fun pinTab(path: String) {
        if (path in pinnedTabs) return
        keepTab(path)
        val document = documents.firstOrNull { it.path == path } ?: return
        documents.remove(document)
        documents.add(documents.count { it.path in pinnedTabs }, document)
        pinnedTabs.add(path)
        persistSession()
    }

    fun unpinTab(path: String) {
        if (!pinnedTabs.remove(path)) return
        val document = documents.firstOrNull { it.path == path } ?: return
        documents.remove(document)
        documents.add(documents.count { it.path in pinnedTabs }, document)
        persistSession()
    }

    /** Close Other Editors leaves [path] and the pinned tabs. */
    fun closeOtherTabs(path: String) {
        requestCloseDocuments(documents.map { it.path }.filter { it != path && it !in pinnedTabs })
    }

    fun copyPath(path: String) {
        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Path", path))
        status = "Copied $path"
    }

    // Markdown preview ----------------------------------------------------------------------------

    /** Markdown files shown with their preview, beside the editor or instead of it. */
    val markdownPreviews = mutableStateMapOf<String, MarkdownPreviewMode>()

    /** Ctrl+Shift+V and Ctrl+K V; the same again goes back to the editor alone. */
    fun toggleMarkdownPreview(mode: MarkdownPreviewMode, path: String? = activePath) {
        if (path == null || !hasPreview(path)) {
            status = "The preview is for Markdown and SVG files"
            return
        }
        if (markdownPreviews[path] == mode) markdownPreviews.remove(path) else markdownPreviews[path] = mode
    }

    /**
     * A link tapped in the preview. Relative links resolve against the file's folder under
     * [MARKDOWN_BASE_HOST]: guest files open in the editor (folders in Explorer); web links open
     * in the browser.
     */
    fun openMarkdownLink(url: String) {
        val uri = android.net.Uri.parse(url)
        if (uri.host == MARKDOWN_BASE_HOST) {
            val path = uri.path?.takeIf { it.startsWith("/") } ?: return
            if (roots.none { isPathWithinRoot(path, it) }) {
                status = "$path is outside the guest's folders"
                return
            }
            vfs.stat(path).whenComplete { entry, error ->
                post {
                    when {
                        error != null -> status = "No file at $path"
                        entry.isDirectory -> navigateToDirectory(path)
                        else -> openPath(path)
                    }
                }
            }
        } else if (uri.scheme in setOf("http", "https", "mailto")) {
            openLink(url)
        }
    }

    /** Markdown and SVG files have a preview beside or instead of their text. */
    fun hasPreview(path: String): Boolean = isMarkdownPath(path) || isSvgPath(path)

    /** The image viewer's file, read on its loader thread (guest or device; up to [MAX_PREVIEW_IMAGE_BYTES]). */
    fun readImageBytes(path: String): ByteArray {
        if (isGuestPath(path) && !vfs.isConnected()) throw java.io.IOException("Connect to the guest to show this image")
        return repository.readBytes(path, MAX_PREVIEW_IMAGE_BYTES).get(30, TimeUnit.SECONDS)
    }

    /** Ctrl+= and Ctrl+- on an image tab: the viewer zooms by [ImageZoomRequest.step]. */
    var imageZoomRequest by mutableStateOf<ImageZoomRequest?>(null)
        private set

    private fun zoomEditorOrImage(step: Int) {
        val path = activePath
        if (path != null && (isImagePath(path) || (isSvgPath(path) && markdownPreviews[path] == MarkdownPreviewMode.Only))) {
            imageZoomRequest = ImageZoomRequest(path, step, (imageZoomRequest?.id ?: 0) + 1)
        } else {
            zoomEditor(step.toFloat())
        }
    }

    /** An image the preview shows, read from the guest (on the WebView's loader thread). */
    fun readPreviewImage(path: String): ByteArray? {
        if (!vfs.isConnected() || roots.none { isPathWithinRoot(path, it) }) return null
        return runCatching { repository.readBytes(path, MAX_PREVIEW_IMAGE_BYTES).get(15, TimeUnit.SECONDS) }.getOrNull()
    }

    fun onDocumentEdited(document: DocumentSession) {
        documentRevision++
        if (previewTab == document.path) keepTab(document.path)
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
        scheduleAutoSave(document.path)
    }

    private fun scheduleAutoSave(path: String) {
        if (settings.autoSave != AutoSaveMode.AfterDelay || path.startsWith("scratch:")) return
        scheduledAutoSave?.cancel(false)
        scheduledAutoSave = timer.schedule({ post { autoSave(path) } }, settings.autoSaveDelayMs.toLong(), TimeUnit.MILLISECONDS)
    }

    /** Saves [path] if it has unsaved changes; scratch files stay local (saving them asks for a name). */
    private fun autoSave(path: String) {
        if (path.startsWith("scratch:")) return
        val session = notebooks[path]
        when {
            session != null -> if (session.notebook.dirty) saveNotebook(session)
            else -> documents.firstOrNull { it.path == path }?.takeIf { it.dirty }?.let { saveDocument(it, automatic = true) }
        }
    }

    private fun autoSaveAll() = documents.map { it.path }.forEach(::autoSave)

    fun saveActiveDocument() {
        activeNotebook?.let {
            saveNotebook(it)
            return
        }
        val document = activeDocument?.takeUnless { it.binary } ?: return
        if (document.path.startsWith("scratch:")) {
            if (vfs.isConnected()) {
                beginSaveAs(document)
                return
            }
            document.markSaved(document.text)
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

    /** [automatic]: an auto save, which leaves the cursor's line untrimmed (as VS Code does). */
    private fun saveDocument(document: DocumentSession, automatic: Boolean = false) {
        applySaveCleanups(document, automatic)
        status = "Saving ${document.path}"
        repository.save(document).whenComplete { result, error ->
            post {
                documentRevision++
                if (error != null) {
                    status = "Save failed: ${error.cause?.message ?: error.message}"
                    return@post
                }
                when (result) {
                    is SaveResult.Saved -> {
                        status = "Saved ${document.path}"
                        refreshGitStatus()
                        closeIfPendingAfterSave(document.path)
                    }
                    SaveResult.QueuedOffline -> {
                        status = "Saved locally; queued until guest reconnects"
                        closeIfPendingAfterSave(document.path)
                    }
                    is SaveResult.Conflict -> {
                        closeAfterSave.remove(document.path)
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
                if (activePath == document.path) editorSurface?.setText(document.text)
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

    /**
     * The status bar's language for an untitled file. The language follows the name's extension
     * (as it does for saved files), so "Python" makes `Untitled-1` into `Untitled-1.py`.
     */
    fun setUntitledLanguage(label: String) {
        dialog = null
        val document = activeDocument?.takeIf { it.path.startsWith("scratch:") } ?: return
        val extension = UNTITLED_LANGUAGES[label] ?: return
        val name = document.path.substringAfterLast('/')
        val base = if (document.languageId.isEmpty()) name else name.substringBeforeLast('.')
        val target = "scratch:/" + if (extension.isEmpty()) base else "$base.$extension"
        if (target == document.path) return
        if (documents.any { it.path == target }) {
            status = "${target.removePrefix("scratch:/")} is already open"
            return
        }
        notifyDocumentClosed(document.path)
        forgetDiagnostics(diagnosticUri(document.path))
        val renamed = document.copy(path = target)
        documents[documents.indexOf(document)] = renamed
        selectDocument(target)
        notifyDocumentOpened(renamed)
        ensureLanguageServers(target)
        status = "Language set to $label"
    }

    /** Drops what servers reported for a document that no longer exists under that URI. */
    private fun forgetDiagnostics(uri: String) {
        diagnosticsByClient.values.forEach { it.remove(uri) }
        diagnostics.removeAll { it.uri == uri }
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
        if (path == null || !isGuestPath(path)) {
            status = "Open or select a saved file to rename"
            return
        }
        dialog = WorkbenchDialog.PathPrompt(PathAction.Rename(path), path.substringAfterLast('/'))
    }

    fun beginDeleteEntry(path: String? = activePath, isDirectory: Boolean = false) {
        if (path == null || !isGuestPath(path)) {
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
        // Reload open folders; ones that went away (deleted, renamed) close.
        folderChildren.keys.toList().forEach { path ->
            if (path in expandedFolders) loadFolderChildren(path) else folderChildren.remove(path)
        }
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

    fun editorReady(surface: EditorSurface) {
        val newlyBound = editorSurface !== surface
        editorSurface = surface
        val target = pendingNavigation
        // The view is made empty and gets its text right after; a jump waits for the text.
        if (target != null && target.path == activePath && surface.text == activeDocument?.text) {
            surface.moveCursor(LspPosition(target.line, target.character))
            pendingNavigation = null
        }
        if (newlyBound) {
            activeDocument?.let(::refreshSemanticTokens)
            refreshEditorProblems()
        }
    }

    /** Problems of the active file, for the marks beside the editor's scrollbar. */
    var editorProblemMarks by mutableStateOf<List<ProblemMark>>(emptyList())
        private set

    private var editorProblemLines: Map<Int, Int> = emptyMap()

    /** Redraws the active file's problems in the editor (underlines, tooltips, faded code, marks). */
    fun refreshEditorProblems() {
        val surface = editorSurface ?: return
        val uri = activePath?.let(::diagnosticUri)
        val lineLengths = (0 until surface.lineCount).map { surface.lineText(it).length }
        val problems = editorProblems(diagnostics.filter { it.uri == uri }, lineLengths)
        surface.setProblems(problems, editorConfig()) { position ->
            surface.moveCursor(position, reveal = false)
            showCodeActions()
        }
        editorProblemLines = problemLines(problems)
        editorProblemMarks = if (settings.problemUnderlines) problemMarks(problems, surface.lineCount) else emptyList()
        refreshLineMarks()
        // A problem dot can widen the gutter, which moves the text: the overlays drawn at text
        // positions (inlay hints, ghost text) follow once the editor has laid it out again.
        mainHandler.postDelayed({ editorViewportTick++ }, 50)
    }

    /** The URI a language server reports a document's diagnostics under. */
    fun diagnosticUri(path: String): String = lspDocumentUri(path, workspaceRoot)

    /** Maps a path from a language server back to the open document, untitled ones included. */
    fun documentPathFor(lspPath: String): String = untitledPathFor(lspPath, workspaceRoot) ?: lspPath

    /** A tap on a scrollbar mark: put the cursor on that problem. */
    fun goToProblemMark(mark: ProblemMark) {
        val surface = editorSurface ?: return
        surface.moveCursor(LspPosition(mark.line, mark.column))
        surface.focus()
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
                if (event.uri == activePath) {
                    scheduleSymbolHighlights(event.uri, event.selection)
                    // The cursor left the call, or moved to another argument.
                    if (signatureHint != null) requestSignature(event.uri, trigger = null, delayMs = 80)
                }
                val shown = inlineSuggestion
                if (shown != null && (shown.first != event.uri || event.selection.active != shown.second.range.end &&
                        !cursorWithinSuggestion(shown.second, event.selection.active))
                ) {
                    clearInlineSuggestion()
                }
            }
            is EditorEvent.ContentChanged -> run {
                notifyDocumentChanged(event.uri, event.version, event.text)
                post {
                    if (activePath == event.uri) {
                        scheduleOutlineRefresh(event.uri)
                        scheduleInlineSuggestion()
                        afterEditForHints(event.uri)
                        // Hints are kept by line; once lines come or go they'd sit on the wrong ones.
                        if (editorSurface?.lineCount != inlayHintLineCount) inlayHintLines = emptyMap()
                        scheduleInlayHints(event.uri)
                    }
                }
            }
            is EditorEvent.ViewportChanged -> post {
                editorViewportTick++
                // Hints are asked for around the visible lines; scrolling past them asks again.
                val window = inlayHintWindow
                if (event.uri == activePath && settings.inlayHints && (window == null ||
                        event.visibleLines.first < window.first || event.visibleLines.last > window.last)
                ) {
                    scheduleInlayHints(event.uri, delayMs = 150)
                }
            }
            is EditorEvent.FocusChanged -> post {
                if (event.focused) focusArea = FocusArea.Editor else if (focusArea == FocusArea.Editor) focusArea = FocusArea.Other
            }
            is EditorEvent.Zoomed -> post {
                editorViewportTick++
                // Saved once the fingers stop, rounded like the Settings slider.
                val size = event.fontSizeSp.roundToInt().coerceIn(8, 40)
                scheduledZoomSave?.cancel(false)
                scheduledZoomSave = timer.schedule({
                    post {
                        if (size.toFloat() != settings.editorFontSize) {
                            updateSetting(WorkbenchSettings.EDITOR_FONT_SIZE, size)
                            status = "Editor font size $size"
                        }
                    }
                }, 600, TimeUnit.MILLISECONDS)
            }
        }
    }

    private var scheduledZoomSave: ScheduledFuture<*>? = null

    private fun cursorWithinSuggestion(suggestion: CopilotSuggestion, position: LspPosition): Boolean =
        position.line == suggestion.range.start.line && position.character >= suggestion.range.start.character

    // Language servers ------------------------------------------------------------------------

    fun languageClientFor(path: String): LanguageClient? =
        if (isDevicePath(path)) null else when (path.substringAfterLast('.', "").lowercase()) {
            "py", "pyi" -> languageClients["python"]
            "ts", "tsx", "js", "jsx", "mts", "cts", "mjs", "cjs" -> languageClients["typescript"]
            else -> null
        }

    private fun languageClientsFor(path: String): List<LanguageClient> =
        if (isDevicePath(path)) emptyList() else listOfNotNull(
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
            else -> settings.typescriptLanguageServer
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
        // Device files have no guest path for the servers (or Copilot) to know them by.
        if (document.path in notebooks || isDevicePath(document.path) || document.binary) return
        lspLanguageId(document.path)?.let { languageId ->
            languageClientsFor(document.path).filter { it.isReady }.forEach { client ->
                lspLog { "didOpen ${document.path} v${document.version} (${document.text.length} chars) in ${languageClients.entries.firstOrNull { it.value === client }?.key}" }
                client.didOpen(document.path, languageId, document.version, document.text)
            }
        }
        if (copilot.language.isReady) {
            copilot.language.didOpen(document.path, copilotLanguageId(document.path), document.version, document.text)
        }
    }

    private fun notifyDocumentChanged(path: String, version: Long, text: String) {
        if (isDevicePath(path)) return
        languageClientsFor(path).filter { it.isReady }.forEach { client -> client.didChange(path, version, text) }
        if (copilot.language.isReady) copilot.language.didChange(path, version, text)
    }

    private fun notifyDocumentClosed(path: String) {
        if (isDevicePath(path) || isImagePath(path)) return
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
        if (!vfs.isConnected() || path in notebooks) return
        scheduledServerStop?.cancel(false)
        languageClients.filterValues { it in languageClientsFor(path) && !it.isStarted }.forEach { (language, client) ->
            val tool = languageServerTool(language)
            status = "Starting $tool"
            // ty finds the chosen interpreter's environment through CONDA_PREFIX / VIRTUAL_ENV.
            val env = if (language == "python" && tool == "ty") pythonEnvironmentVariables(settings.pythonInterpreter) else emptyList()
            client.start(workspace, tool, env, initializationOptions(language, tool)).whenComplete { _, error ->
                post {
                    reportLanguageServer(language, error)
                    if (error != null) {
                        val message = error.cause?.message ?: error.message.orEmpty()
                        status = "$tool: $message"
                        // A server the guest doesn't have yet (TypeScript 7): offer it once, then start it.
                        missingDependencyIn(message)?.takeIf { dialog == null }?.let { dependency ->
                            offerInstall(dependency, "${dependency.label} isn't installed in the guest.") { ensureLanguageServers(path) }
                        }
                        return@post
                    }
                    status = "$tool ready"
                    documents.filter { client in languageClientsFor(it.path) }.forEach { document ->
                        notifyDocumentOpened(document)
                        if (activePath == document.path) {
                            refreshOutline(document)
                            refreshSemanticTokens(document)
                            scheduleInlayHints(document.path, delayMs = 0)
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
                    val needed = documents.filterNot { it.path in notebooks }
                        .flatMap { languageClientsFor(it.path) }.toSet()
                    languageClients.values.filter { it.isStarted && it !in needed }.forEach(LanguageClient::close)
                }
            },
            settings.languageServerIdleSeconds.toLong(),
            TimeUnit.SECONDS,
        )
    }

    private fun refreshOutline(document: DocumentSession) {
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
        closeParameterHints()
        clearSymbolHighlights()
        clearInlayHints()
        refreshEditorProblems()
        activeDocument?.let { document ->
            refreshOutline(document)
            refreshSemanticTokens(document)
            scheduleInlayHints(document.path, delayMs = 0)
        }
        if (copilot.language.isReady) copilot.didFocus(activePath)
    }

    private fun refreshSemanticTokens(document: DocumentSession) {
        if (!settings.semanticHighlighting) return
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
        if (client?.isReady != true) {
            status = "Language intelligence requires a connected Python or TypeScript language server"
            return null
        }
        val start = editorSurface?.selectionStart() ?: return null
        return document to start
    }

    fun navigateToLocation(location: LanguageLocation, preview: Boolean = false) {
        val path = java.net.URI(location.uri).path?.let(::documentPathFor) ?: return
        val start = location.range.getAsJsonObject("start") ?: return
        val target = EditorNavigationTarget(path, start.get("line").asInt, start.get("character").asInt)
        compactDestination = CompactDestination.Editor
        if (activePath == path) {
            editorSurface?.moveCursor(LspPosition(target.line, target.character))
            pendingNavigation = null
        } else {
            pendingNavigation = target
            openPath(path, preview)
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
        val end = edit.endAfterInsert()
        editorSurface?.moveCursor(end)
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
        serverEdits: Map<String, List<LspTextEdit>>,
        successMessage: String,
        onApplied: (() -> Unit)? = null,
    ) {
        val edits = serverEdits.mapKeys { (path, _) -> documentPathFor(path) }
        if (edits.isEmpty()) {
            onApplied?.invoke()
            return
        }
        if (edits.keys.any { path -> !path.startsWith("scratch:") && roots.none { root -> isPathWithinRoot(path, root) } }) {
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
                        if (activePath == path && editorHost.selection(path) != null) {
                            // In place: the cursor, colours and undo history stay; the host tells the servers.
                            editorHost.applyEdits(path, edits.getValue(path))
                        } else {
                            target.updateText(text)
                            notifyDocumentChanged(path, target.version, target.text)
                        }
                        onDocumentEdited(target)
                    }
                    status = "$successMessage in ${updated.size} file(s); save to persist changes"
                    onApplied?.invoke()
                } catch (applyError: RuntimeException) {
                    status = "Workspace edit could not be applied: ${applyError.message}"
                }
            }
        }
    }

    /** Whether the cursor is on this problem (its last character included), as VS Code asks for actions. */
    private fun LanguageDiagnostic.covers(position: LspPosition): Boolean {
        fun point(name: String) = range.getAsJsonObject(name)?.let { LspPosition(it.get("line").asInt, it.get("character").asInt) }
        val start = point("start") ?: return false
        val end = point("end") ?: return false
        val order = compareBy<LspPosition>({ it.line }, { it.character })
        return order.compare(start, position) <= 0 && order.compare(position, end) <= 0
    }

    /** Asks every server of the file (for Python, ty or basedpyright and Ruff) and lists their actions together. */
    fun showCodeActions() {
        val (document, position) = activeLspPosition() ?: return
        val uri = diagnosticUri(document.path)
        val clients = languageClientsFor(document.path).filter { it.isReady }
        val requests = clients.map { client ->
            // Each server gets back its own diagnostics, with their data.
            val fileDiagnostics = JsonArray().apply {
                diagnosticsByClient[client]?.get(uri).orEmpty().filter { it.covers(position) }.forEach { diagnostic ->
                    add(JsonObject().apply {
                        add("range", diagnostic.range)
                        diagnostic.severity?.let { addProperty("severity", it) }
                        addProperty("message", diagnostic.message)
                        diagnostic.source?.let { addProperty("source", it) }
                        diagnostic.code?.let { add("code", it) }
                        if (diagnostic.tags.isNotEmpty()) add("tags", JsonArray().apply { diagnostic.tags.forEach(::add) })
                        diagnostic.data?.let { add("data", it) }
                    })
                }
            }
            client.codeActions(document.path, LspRange(position, position), fileDiagnostics)
                .handle { response, error -> Triple(client, response, error) }
        }
        CompletableFuture.allOf(*requests.toTypedArray()).whenComplete { _, _ ->
            val answers = requests.map { it.join() }
            post {
                val choices = answers.flatMap { (client, response, _) ->
                    response?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { value ->
                        val action = value.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                        val title = action.get("title")?.takeUnless { it.isJsonNull }?.asString ?: return@mapNotNull null
                        CodeActionChoice(title, action, client)
                    }.orEmpty()
                }
                val failure = answers.firstNotNullOfOrNull { it.third }
                when {
                    choices.isNotEmpty() -> dialog = WorkbenchDialog.CodeActions(choices)
                    failure != null -> status = "Code actions failed: ${failure.cause?.message ?: failure.message}"
                    else -> status = "No code actions available"
                }
            }
        }
    }

    fun applyCodeAction(choice: CodeActionChoice) {
        val document = activeDocument ?: return
        val client = choice.client ?: languageClientFor(document.path) ?: return
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
        val surface = editorSurface ?: return
        val token = commentTokens(document.languageId).line ?: run {
            toggleBlockComment()
            return
        }
        val offsets = surface.selectionOffsets
        val rangeStart = surface.positionAt(offsets.first)
        val rangeEnd = surface.positionAt(offsets.last)
        val first = rangeStart.line
        val last = if (rangeEnd.line > first && rangeEnd.character == 0) rangeEnd.line - 1 else rangeEnd.line
        val lines = surface.text.split('\n')
        val toggled = toggleLineComments(lines.subList(first, last + 1), token)
        val updated = (lines.subList(0, first) + toggled + lines.subList(last + 1, lines.size)).joinToString("\n")
        val offsetOf = { line: Int, column: Int -> (0 until line).sumOf { lines.getOrElse(it) { "" }.length + 1 } + column }
        val shift = { line: Int, column: Int ->
            val delta = toggled.getOrNull(line - first)?.let { it.length - lines[line].length } ?: 0
            offsetOf(line, maxOf(0, column + delta)) + (first until line).sumOf { toggled[it - first].length - lines[it].length }
        }
        replaceEditorText(surface, updated, shift(rangeStart.line, rangeStart.character), shift(rangeEnd.line, rangeEnd.character))
    }

    private fun toggleBlockComment() {
        val document = activeDocument ?: return
        val surface = editorSurface ?: return
        val (open, close) = commentTokens(document.languageId).block ?: run {
            status = "No block comment syntax for this file type"
            return
        }
        val range = surface.selectionOffsets
        val edit = toggleBlockComment(surface.text, range.first, range.last, open, close)
        replaceEditorText(surface, edit.text, edit.selectionStart, edit.selectionEnd)
    }

    /** Applies [updated] as one minimal replacement so undo and incremental highlighting keep working. */
    private fun replaceEditorText(surface: EditorSurface, updated: String, selectionStart: Int, selectionEnd: Int) {
        val span = changedSpan(surface.text, updated)
        val from = surface.positionAt(span.start)
        val to = surface.positionAt(span.oldEnd)
        surface.applyEdits(listOf(LspTextEdit(LspRange(from, to), updated.substring(span.start, span.newEnd))))
        surface.setSelection(
            EditorSelection(
                surface.positionAt(selectionStart.coerceIn(0, updated.length)),
                surface.positionAt(selectionEnd.coerceIn(0, updated.length)),
            ),
        )
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
            .filter { it.uri == diagnosticUri(document.path) }
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
        val surface = editorSurface ?: return
        while (cursorHistory.isNotEmpty() && cursorHistory.last() == path to cursorPosition) cursorHistory.removeLast()
        val (historyPath, position) = cursorHistory.removeLastOrNull() ?: return
        if (historyPath != path || position == null) return
        restoringCursor = true
        surface.moveCursor(position)
    }

    private fun copySelection(cut: Boolean) {
        val surface = editorSurface ?: return
        val range = surface.selectionOffsets
        if (range.first == range.last) {
            status = if (cut) "Select text before cutting" else "Select text before copying"
            return
        }
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = surface.text.substring(range.first, range.last)
        clipboard.setPrimaryClip(ClipData.newPlainText("MaterialCode", text))
        if (cut) surface.deleteSelection()
    }

    private fun pasteClipboard() {
        val text = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString() ?: return
        val surface = editorSurface ?: return
        val start = surface.selectionOffsets.first
        surface.insertAtCursor(text)
        if (settings.formatOnPaste) formatPasted(start, start + text.length)
    }

    /** `editor.formatOnPaste`: range-formats what was just pasted, when the formatter can format a range. */
    private fun formatPasted(startOffset: Int, endOffset: Int) {
        val document = activeDocument ?: return
        val surface = editorSurface ?: return
        val client = formattingClientFor(document.path)?.takeIf { it.isReady && it.supportsRangeFormatting } ?: return
        val range = LspRange(surface.positionAt(startOffset), surface.positionAt(endOffset))
        val version = document.version
        client.rangeFormatting(document.path, range, settings.tabSize, settings.insertSpaces).whenComplete { edits, error ->
            post {
                // Typing on since the paste: the edits no longer fit the text.
                if (error != null || edits.isNullOrEmpty() || document.version != version || !editorHost.isOpen(document.path)) return@post
                editorHost.applyEdits(document.path, edits)
                onDocumentEdited(document)
            }
        }
    }

    /**
     * VS Code's save participants: trailing whitespace, final newline, final newlines. The open
     * editor gets them as edits (so they undo); a file saved in the background gets them in its text.
     */
    private fun applySaveCleanups(document: DocumentSession, automatic: Boolean) {
        // Two trailing spaces are a line break in Markdown.
        val markdown = document.path.substringAfterLast('.', "").lowercase() in setOf("md", "markdown")
        val cleanups = SaveCleanups(
            trimTrailingWhitespace = settings.trimTrailingWhitespace && !markdown,
            insertFinalNewline = settings.insertFinalNewline,
            trimFinalNewlines = settings.trimFinalNewlines,
        )
        if (!cleanups.any || document.path in notebooks) return
        // An auto save on leaving the editor (Settings) runs after its pane has gone.
        val surface = editorSurface?.takeIf { activeNotebook == null && activePath == document.path && editorHost.isOpen(document.path) }
        val keepLine = if (automatic) surface?.selection?.active?.line else null
        val edits = saveCleanupEdits(document.text, cleanups, document.eol, keepLine)
        if (edits.isEmpty()) return
        if (surface != null) {
            editorHost.applyEdits(document.path, edits)
            onDocumentEdited(document)
        } else {
            document.updateText(applyLspTextEdits(document.text, edits))
        }
    }

    // Parameter hints and symbol highlights ---------------------------------------------------

    /** The signature shown above the cursor while typing a call (in [activePath]). */
    var signatureHint by mutableStateOf<SignatureHint?>(null)
        private set
    private var scheduledSignature: ScheduledFuture<*>? = null
    private var signatureRequest = 0L
    private var scheduledHighlights: ScheduledFuture<*>? = null
    private var highlightRequest = 0L

    /** After an edit: a trigger character (`(`, `,`) asks for hints; while they show, any edit updates them. */
    private fun afterEditForHints(path: String) {
        clearSymbolHighlights()
        if (!settings.parameterHints) return
        val surface = editorSurface ?: return
        val client = languageClientFor(path)?.takeIf { it.isReady && it.supportsSignatureHelp } ?: return
        val cursor = surface.selection.active
        val typed = surface.lineText(cursor.line).getOrNull(cursor.character - 1)?.toString()
        when {
            typed != null && typed in client.signatureTriggerCharacters -> requestSignature(path, typed)
            signatureHint != null -> requestSignature(path, typed?.takeIf { it in client.signatureRetriggerCharacters }, delayMs = 80)
        }
    }

    private fun requestSignature(path: String, trigger: String?, invoked: Boolean = false, delayMs: Long = 0) {
        scheduledSignature?.cancel(false)
        scheduledSignature = timer.schedule({
            post {
                if (activePath != path) return@post
                val client = languageClientFor(path)?.takeIf { it.isReady && it.supportsSignatureHelp } ?: run {
                    if (invoked) status = "Parameter hints need a language server for this file"
                    return@post
                }
                val position = editorSurface?.selection?.active ?: return@post
                val id = ++signatureRequest
                client.signatureHelp(path, position, signatureContext(trigger, signatureHint, invoked)).whenComplete { result, error ->
                    post {
                        if (id != signatureRequest || activePath != path) return@post
                        val hint = if (error == null) parseSignatureHelp(result) else null
                        signatureHint = hint
                        editorSurface?.parameterHintsShown = hint != null
                        if (invoked && hint == null) status = "No parameter hints here"
                    }
                }
            }
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    fun closeParameterHints() {
        scheduledSignature?.cancel(false)
        signatureRequest++
        signatureHint = null
        editorSurface?.parameterHintsShown = false
    }

    /** Highlights the symbol's other uses once the cursor rests (VS Code waits about as long). */
    private fun scheduleSymbolHighlights(path: String, selection: EditorSelection) {
        scheduledHighlights?.cancel(false)
        if (!settings.occurrencesHighlight || selection.anchor != selection.active) {
            clearSymbolHighlights()
            return
        }
        scheduledHighlights = timer.schedule({ post { requestSymbolHighlights(path) } }, 250, TimeUnit.MILLISECONDS)
    }

    private fun requestSymbolHighlights(path: String) {
        val document = activeDocument?.takeIf { it.path == path && activeNotebook == null } ?: return
        val client = languageClientFor(path)?.takeIf { it.isReady && it.supportsDocumentHighlight } ?: return
        val position = editorSurface?.selection?.active ?: return
        val version = document.version
        val id = ++highlightRequest
        client.documentHighlights(path, position).whenComplete { result, error ->
            post {
                if (id != highlightRequest || activePath != path || document.version != version) return@post
                val highlights = if (error == null) parseDocumentHighlights(result) else emptyList()
                editorSurface?.setSymbolHighlights(highlights.map { it.range to it.write })
            }
        }
    }

    // Inlay hints ---------------------------------------------------------------------------------

    /** The active file's inlay hints as their line-end text, by line. */
    var inlayHintLines by mutableStateOf<Map<Int, List<String>>>(emptyMap())
        private set
    /** The lines the shown hints were asked for. */
    private var inlayHintWindow: IntRange? = null
    private var scheduledInlayHints: ScheduledFuture<*>? = null
    private var inlayHintRequest = 0L
    private var inlayHintLineCount = 0

    private fun scheduleInlayHints(path: String, delayMs: Long = 400) {
        scheduledInlayHints?.cancel(false)
        if (!settings.inlayHints) {
            clearInlayHints()
            return
        }
        scheduledInlayHints = timer.schedule({ post { requestInlayHints(path) } }, delayMs, TimeUnit.MILLISECONDS)
    }

    /** Asks for the visible lines and 100 on either side, so a short scroll needs no new request. */
    private fun requestInlayHints(path: String) {
        val document = activeDocument?.takeIf { it.path == path && activeNotebook == null } ?: return
        val client = languageClientFor(path)?.takeIf { it.isReady && it.supportsInlayHints } ?: return
        val surface = editorSurface ?: return
        val visible = surface.visibleLines
        val lastLine = surface.lineCount - 1
        val first = (visible.first - 100).coerceIn(0, lastLine)
        val last = (visible.last + 100).coerceIn(first, lastLine)
        val range = LspRange(LspPosition(first, 0), LspPosition(last, surface.lineText(last).length))
        val version = document.version
        val id = ++inlayHintRequest
        client.inlayHints(path, range).whenComplete { result, error ->
            post {
                if (id != inlayHintRequest || activePath != path) return@post
                // ContentModified: a newer edit is on its way, and so is the request after it.
                if (error != null || document.version != version) return@post
                val current = editorSurface ?: return@post
                inlayHintWindow = first..last
                inlayHintLineCount = current.lineCount
                inlayHintLines = parseInlayHints(result).groupBy { it.position.line }
                    .filterKeys { it < current.lineCount }
                    .mapValues { (line, hints) -> endOfLineHints(current.lineText(line), hints) }
            }
        }
    }

    private fun clearInlayHints() {
        scheduledInlayHints?.cancel(false)
        inlayHintRequest++
        inlayHintWindow = null
        inlayHintLines = emptyMap()
    }

    private fun clearSymbolHighlights() {
        scheduledHighlights?.cancel(false)
        highlightRequest++
        editorSurface?.setSymbolHighlights(emptyList())
    }

    fun findInEditor(next: Boolean) {
        val document = activeDocument ?: return
        val surface = editorSurface ?: return
        if (findQuery.isEmpty()) {
            dialog = WorkbenchDialog.Find
            return
        }
        val text = surface.text
        val selected = surface.selectionOffsets
        val foundAt = if (next) {
            text.indexOf(findQuery, selected.last.coerceAtMost(text.length)).takeIf { it >= 0 } ?: text.indexOf(findQuery)
        } else {
            val from = (selected.first - 1).coerceAtLeast(0)
            text.lastIndexOf(findQuery, from).takeIf { it >= 0 } ?: text.lastIndexOf(findQuery)
        }
        if (foundAt < 0) {
            status = "No matches for \"$findQuery\" in ${document.path.substringAfterLast('/')}"
            return
        }
        val end = surface.positionAt(foundAt + findQuery.length)
        // Reveal the match first, then select it (moving the cursor would drop the selection).
        surface.moveCursor(end)
        surface.setSelection(EditorSelection(surface.positionAt(foundAt), end))
    }

    fun replaceAll() {
        val document = activeDocument ?: return
        if (findQuery.isEmpty()) return
        val count = document.text.windowed(findQuery.length).count { it == findQuery }
        document.updateText(document.text.replace(findQuery, replaceQuery))
        editorSurface?.setText(document.text)
        onDocumentEdited(document)
        status = "Replaced $count occurrence(s)"
    }

    /** The status bar's line number: a small dialog with a number pad, kinder to touch than Quick Open. */
    fun showGoToLine() {
        gotoLine = ""
        dialog = WorkbenchDialog.GotoLine
    }

    fun goToLine() {
        val target = gotoLine.toIntOrNull()
        val surface = editorSurface
        if (target != null && surface != null) {
            surface.moveCursor(LspPosition((target - 1).coerceIn(0, surface.lineCount - 1), 0))
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
        editorSurface?.setText(normalized)
        onDocumentEdited(document)
        dialog = null
        status = "Line endings set to $selected"
    }

    private fun zoomEditor(delta: Float) {
        updateSetting(WorkbenchSettings.EDITOR_FONT_SIZE, (settings.editorFontSize + delta).coerceIn(8f, 40f))
    }

    // Copilot -----------------------------------------------------------------------------------

    /** Signed out, the language server only costs memory (~280 MB in the guest). */
    private var copilotSignedOut: Boolean
        get() = preferences.getBoolean("copilot-signed-out", false)
        set(value) = preferences.edit().putBoolean("copilot-signed-out", value).apply()
    private var scheduledCopilotStop: ScheduledFuture<*>? = null

    private fun onCopilotSignInState(state: com.avfstudio.nativeide.language.CopilotStatus) {
        val signedOut = state.message.contains("not signed", ignoreCase = true) || state.kind == "NotSignedIn"
        if (state.kind == "Normal" || state.kind == "InProgress") {
            copilotSignedOut = false
            scheduledCopilotStop?.cancel(false)
            return
        }
        if (!signedOut) return
        copilotSignedOut = true
        scheduledCopilotStop?.cancel(false)
        scheduledCopilotStop = timer.schedule({
            post {
                if (copilotSignedOut && dialog !is WorkbenchDialog.CopilotSignInCode && copilot.language.isStarted) {
                    copilot.language.close()
                    copilotStatus = "Signed out"
                    copilotStatusKind = "SignedOut"
                }
            }
        }, 30, TimeUnit.SECONDS)
    }

    private fun startCopilot(attempt: Int = 0) {
        val workspace = workspaceRoot ?: return
        if (!copilotEnabled || copilot.language.isStarted || !vfs.isConnected()) return
        if (copilotSignedOut) {
            // Started by "Sign in" (beginCopilotSignIn) instead, so a signed-out guest doesn't pay for it.
            copilotStatus = "Signed out"
            copilotStatusKind = "SignedOut"
            return
        }
        copilotStatus = "Starting"
        copilot.start(workspace).whenComplete { _, error ->
            post {
                if (error != null && attempt < COPILOT_START_RETRIES && connected) {
                    // Right after a daemon restart the server once exited at once (cause unknown;
                    // not seen again in five restarts on 10-28, and the daemon now logs exits with
                    // their stderr). Starting again a few seconds later has worked; wait longer each time.
                    val delay = 3L shl attempt
                    status = "Copilot: ${error.cause?.message ?: error.message}; retrying in $delay s"
                    timer.schedule({ post { startCopilot(attempt + 1) } }, delay, TimeUnit.SECONDS)
                    return@post
                }
                reportLanguageServer("copilot", error)
                if (error != null) {
                    copilotStatus = "Unavailable"
                    status = "Copilot: ${error.cause?.message ?: error.message}"
                } else {
                    copilotStatus = "Ready"
                    // A new server instance hasn't seen any notebook mirror yet.
                    notebookMirrors.clear()
                    documents.forEach(::notifyDocumentOpened)
                    copilot.didFocus(activePath)
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
        if (activePath?.let(::isDevicePath) == true) return
        val document = activeDocument ?: return
        val surface = editorSurface ?: return
        if (!copilotEnabled || !copilot.language.isReady) {
            if (invoked) status = "Copilot is not ready; run GitHub Copilot: Sign In"
            return
        }
        if (surface.selectionOffsets.let { it.first != it.last }) return
        val version = document.version
        val position = surface.selectionStart()
        copilotLog { "request ${document.path.substringAfterLast('/')} ${position.line}:${position.character} v$version invoked=$invoked" }
        copilot.inlineCompletion(document.path, version, position, settings.tabSize, settings.insertSpaces, invoked)
            .whenComplete { suggestions, error ->
                post {
                    val current = editorSurface?.selectionStart()
                    val stillCurrent = activePath == document.path && document.version == version && current == position
                    val suggestion = suggestions?.firstOrNull { it.insertText.isNotBlank() }
                    copilotLog {
                        "reply ${suggestions?.size} current=$stillCurrent (v${document.version} ${current?.line}:${current?.character}) " +
                            "range=${suggestion?.range} text=${suggestion?.insertText?.take(60)?.replace("\n", "⏎")} error=${error?.cause?.message ?: error?.message}"
                    }
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
        val surface = editorSurface ?: return null
        if (path != activePath) return null
        val cursor = surface.selectionStart()
        if (cursor.line != suggestion.range.start.line) return suggestion.insertText
        val typed = surface.lineText(cursor.line)
            .substring(suggestion.range.start.character.coerceAtMost(cursor.character), cursor.character)
        return ghostAfterTyped(typed, suggestion.insertText) ?: suggestion.insertText
    }

    private fun acceptInlineSuggestion() {
        val (path, suggestion) = inlineSuggestion ?: return
        val surface = editorSurface ?: return
        inlineSuggestion = null
        if (activePath != path) return
        val range = suggestion.range
        try {
            val endLine = range.end.line.coerceAtMost(surface.lineCount - 1)
            val endColumn = range.end.character.coerceAtMost(surface.lineText(endLine).length)
            val edit = LspTextEdit(LspRange(range.start, LspPosition(endLine, endColumn)), suggestion.insertText)
            surface.applyEdits(listOf(edit))
            surface.moveCursor(edit.endAfterInsert(), reveal = false)
            copilot.accepted(suggestion)
        } catch (error: RuntimeException) {
            status = "Could not apply the Copilot suggestion: ${error.message}"
        }
    }

    // Chat ------------------------------------------------------------------------------------

    private fun appendChat(event: AcpEvent) {
        when (event) {
            is AcpEvent.UserMessageChunk -> {
                val last = chatEntries.lastOrNull()
                if (last is ChatEntry.User) chatEntries[chatEntries.lastIndex] = last.copy(text = last.text + event.text)
                else chatEntries.add(ChatEntry.User(event.text))
            }
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
        ensureChatSession(workspace).thenCompose { chatAgent.prompt(text, references) }.whenComplete { stopReason, error ->
            post {
                chatBusy = false
                scheduleChatStop()
                chatAuthMethods = chatAgent.authMethods.takeIf { !chatAgent.hasSession }.orEmpty()
                val cause = (error as? CompletionException)?.cause ?: error
                when {
                    cause is JsonRpcException && cause.code == -32000 -> {
                        chatSignInNeeded = true
                        chatEntries.add(ChatEntry.Notice(signInNotice(chatAgentKind)))
                    }
                    cause != null -> chatProblem(cause, "Agent error")
                    stopReason != "end_turn" -> chatEntries.add(ChatEntry.Notice("Turn ended: $stopReason"))
                }
            }
        }
    }

    /** What the pane says when the agent wants a sign-in first. */
    private fun signInNotice(kind: ChatAgentKind): String = when (kind) {
        ChatAgentKind.Copilot ->
            "Copilot chat needs its own GitHub sign-in (separate from inline suggestions). " +
                "Tap “Sign in” for one GitHub login shared with Git, then send again. " +
                "If chat still asks, run “Copilot: Sign In to Chat with copilot login” from the command palette."
        ChatAgentKind.ClaudeCode ->
            "Claude Code needs a sign-in with your Claude account. Tap “Sign in”, open the page, and paste the code it shows."
        ChatAgentKind.Gemini ->
            "Gemini needs a sign-in with your Google account. Tap “Sign in”, open the page, and paste the code it shows. " +
                "An API key works too: put GEMINI_API_KEY=… in ~/.gemini/.env in the guest."
    }

    /** A failed chat request: when the agent isn't installed, the notice comes with an offer to install it. */
    private fun chatProblem(error: Throwable, prefix: String) {
        val cause = (error as? CompletionException)?.cause ?: error
        val message = cause.message.orEmpty()
        val missing = missingDependencyIn(message)
        if (missing != null) {
            chatEntries.add(ChatEntry.Notice("${missing.label} isn't installed in the guest yet."))
            offerInstall(missing, "${missing.label} answers in the Chat view")
        } else {
            chatEntries.add(ChatEntry.Notice("$prefix: $message"))
        }
    }

    // Chat options (model, mode, approvals) ------------------------------------------------

    /** The open session's options as the agent reports them; empty until a session exists. */
    var chatConfigOptions by mutableStateOf<List<com.avfstudio.nativeide.ai.AcpConfigOption>>(emptyList())
        private set
    var chatOptionsLoading by mutableStateOf(false)
        private set

    /**
     * A session for the next prompt. When the pane shows a conversation whose agent stopped (idle,
     * reconnect, reasoning effort), it is loaded again quietly so the conversation goes on;
     * otherwise a new one starts.
     */
    private fun ensureChatSession(workspace: String): CompletableFuture<Unit> {
        if (chatAgent.hasSession) return CompletableFuture.completedFuture(Unit)
        val resume = chatShownSession?.takeIf { settings.chatResume }
        return startChatAgent(workspace).thenCompose {
            if (resume != null && chatAgent.canLoadSession) {
                suppressChatReplay = true
                chatAgent.loadSession(resume, workspace)
                    .whenComplete { _, _ -> suppressChatReplay = false }
                    .exceptionallyCompose { chatAgent.newSession(workspace) }
            } else {
                chatAgent.newSession(workspace)
            }
        }.thenCompose {
            chatShownSession = chatAgent.currentSessionId
            applyRememberedChatOptions()
        }
    }

    private fun startChatAgent(workspace: String): CompletableFuture<Unit> =
        if (chatAgent.isStarted) CompletableFuture.completedFuture(Unit) else chatAgent.start(workspace, chatAgentArgs())

    // Chat history ------------------------------------------------------------------------------

    /** The agent session the pane shows (null for a new, empty chat). */
    @Volatile private var chatShownSession: String? = null
    @Volatile private var suppressChatReplay = false

    /** Past chats in this workspace, from the agent's own store (Copilot keeps them in ~/.copilot). */
    var chatSessions by mutableStateOf<List<ChatSessionRow>>(emptyList())
        private set
    var chatSessionsLoading by mutableStateOf(false)
        private set
    var showChatHistory by mutableStateOf(false)

    fun openChatHistory() {
        val workspace = workspaceRoot
        if (workspace == null || !vfs.isConnected()) {
            status = "Connect to a guest workspace to see past chats"
            return
        }
        showChatHistory = true
        chatSessionsLoading = true
        startChatAgent(workspace).thenCompose {
            if (chatAgent.canListSessions) chatAgent.listSessions(workspace) else CompletableFuture.completedFuture(emptyList())
        }.whenComplete { sessions, error ->
            post {
                chatSessionsLoading = false
                scheduleChatStop()
                if (error != null) {
                    status = "Past chats: ${(error.cause ?: error).message}"
                    return@post
                }
                if (!chatAgent.canListSessions) status = "This agent doesn't list its past chats"
                chatSessions = chatSessionRows(sessions, chatHiddenSessions(), ::chatSessionName, chatShownSession)
            }
        }
    }

    /** Shows a past chat: the agent replays it into the pane, and new messages continue it. */
    fun openChatSession(row: ChatSessionRow) {
        val workspace = workspaceRoot ?: return
        if (chatBusy) {
            status = "Wait for the current reply, or stop it, before switching chats"
            return
        }
        showChatHistory = false
        if (row.id == chatShownSession && chatEntries.isNotEmpty()) return
        chatAgent.leaveSession()
        chatEntries.clear()
        chatShownSession = null
        chatBusy = true
        startChatAgent(workspace).thenCompose { chatAgent.loadSession(row.id, workspace) }.whenComplete { _, error ->
            post {
                chatBusy = false
                scheduleChatStop()
                if (error != null) {
                    chatEntries.add(ChatEntry.Notice("Could not open “${row.title}”: ${(error.cause ?: error).message}"))
                } else {
                    chatShownSession = row.id
                }
            }
        }
    }

    /** A new, empty chat beside the others; the one shown stays in the history. */
    fun newChat() {
        if (chatBusy) chatAgent.cancel()
        chatAgent.leaveSession()
        chatShownSession = null
        chatEntries.clear()
        chatBusy = false
        showChatHistory = false
    }

    /** ACP has no rename, so the name is the app's own, shown instead of the agent's title. */
    fun renameChatSession(row: ChatSessionRow, name: String) {
        val trimmed = name.trim()
        preferences.edit().apply {
            if (trimmed.isEmpty()) remove("chat-title-${row.id}") else putString("chat-title-${row.id}", trimmed)
        }.apply()
        chatSessions = chatSessions.map { if (it.id == row.id) it.copy(title = trimmed.ifEmpty { row.agentTitle }) else it }
    }

    /** ACP has no delete; the chat leaves this list (Copilot keeps its files under ~/.copilot/session-state). */
    fun removeChatSession(row: ChatSessionRow) {
        preferences.edit().putStringSet("chat-hidden", chatHiddenSessions() + row.id).apply()
        chatSessions = chatSessions.filterNot { it.id == row.id }
        if (row.id == chatShownSession) newChat()
    }

    private fun chatHiddenSessions(): Set<String> = preferences.getStringSet("chat-hidden", emptySet()).orEmpty()

    private fun chatSessionName(id: String): String? = preferences.getString("chat-title-$id", null)

    /** Copilot CLI reads the reasoning effort at launch only; ACP has no option for it. Claude Code offers effort as a session option instead. */
    private fun chatAgentArgs(): List<String> =
        settings.chatReasoningEffort.takeIf { it.isNotEmpty() && chatAgentKind == ChatAgentKind.Copilot }
            ?.let { listOf("--reasoning-effort", it) }.orEmpty()

    /** Each agent remembers its own choices; Copilot keeps the keys it had before there were others. */
    private fun chatOptionKey(id: String): String =
        if (chatAgentKind == ChatAgentKind.Copilot) "chat-option-$id" else "chat-option-${chatAgentKind.key}-$id"

    /** Saves the agent choice; [switchChatAgent] follows from the settings change. */
    fun selectChatAgent(kind: ChatAgentKind) {
        if (kind == chatAgentKind) return
        if (chatBusy) {
            status = "Wait for the current reply, or stop it, before switching agents"
            return
        }
        updateSetting(WorkbenchSettings.CHAT_AGENT, kind.key)
    }

    /**
     * Another agent answers from the next message on. The chat shown belongs to the old agent,
     * which keeps it in its own history, so the pane starts empty.
     */
    private fun switchChatAgent(kind: ChatAgentKind) {
        val previous = chatAgent
        previous.eventListener = null
        previous.configListener = null
        previous.runningListener = null
        previous.exitListener = null
        previous.stop()
        scheduledChatStop?.cancel(false)
        chatAgent = AcpClient(vfs, kind.tool)
        chatAgentKind = kind
        bindChatAgent()
        chatAgentRunning = false
        chatBusy = false
        chatConfigOptions = emptyList()
        chatAuthMethods = emptyList()
        chatSignInNeeded = false
        chatShownSession = null
        chatSessions = emptyList()
        showChatHistory = false
        chatEntries.clear()
        status = "Chat uses ${kind.label}"
    }

    /** Saves the effort; a running agent ends so the next message starts one with it. */
    fun setChatReasoningEffort(effort: String) {
        if (effort == settings.chatReasoningEffort) return
        updateSetting(WorkbenchSettings.CHAT_REASONING_EFFORT, effort.ifEmpty { null })
        if (chatAgent.isStarted && !chatBusy) {
            chatAgent.stop()
            chatConfigOptions = emptyList()
            chatEntries.add(ChatEntry.Notice("Reasoning effort: ${reasoningEffortLabel(effort)}. ${nextMessageNote()} with it."))
        }
    }

    /** Re-applies the model (and a non-autonomous mode) chosen earlier; approvals always start at the agent's default. */
    private fun applyRememberedChatOptions(): CompletableFuture<Unit> {
        var chain = CompletableFuture.completedFuture(Unit)
        chatAgent.configOptions.filter { it.category in rememberedChatCategories }.forEach { option ->
            val saved = preferences.getString(chatOptionKey(option.id), null)
            // The agent's own name for the saved value, for the picker before the next session.
            option.choices.firstOrNull { it.value == saved }?.let { rememberChatChoice(option.id, it.value, it.name) }
            if (saved != null && saved != option.current && option.choices.any { it.value == saved }) {
                chain = chain.thenCompose { chatAgent.setConfigOption(option.id, saved) }.exceptionally { }
            }
        }
        return chain
    }

    /** The picker's first tap before any message: opens a session so the agent lists its options. */
    fun loadChatOptions() {
        val workspace = workspaceRoot
        if (workspace == null || !vfs.isConnected() || chatOptionsLoading) return
        chatOptionsLoading = true
        ensureChatSession(workspace).whenComplete { _, error ->
            post {
                chatOptionsLoading = false
                scheduleChatStop()
                if (error != null) chatProblem(error, "${chatAgentKind.label} chat")
            }
        }
    }

    fun setChatOption(option: com.avfstudio.nativeide.ai.AcpConfigOption, value: String, name: String? = null) {
        // Autopilot is never carried over to a new session, so it isn't shown as the remembered mode either.
        if (option.category in rememberedChatCategories && !value.endsWith("#autopilot")) {
            rememberChatChoice(option.id, value, name ?: option.choices.firstOrNull { it.value == value }?.name)
        }
        chatAgent.setConfigOption(option.id, value).whenComplete { _, error ->
            post { if (error != null) status = "Could not change ${option.name}: ${(error.cause ?: error).message}" }
        }
    }

    /** Saves a choice to re-apply; the name is kept with the value it names, so it can't go stale. */
    private fun rememberChatChoice(id: String, value: String, name: String?) {
        preferences.edit()
            .putString(chatOptionKey(id), value)
            .apply {
                if (name != null) putString("${chatOptionKey(id)}-name", name).putString("${chatOptionKey(id)}-name-of", value)
                else remove("${chatOptionKey(id)}-name").remove("${chatOptionKey(id)}-name-of")
            }
            .apply()
    }

    /** What the picker shows before a session exists: the remembered choice's name, if it will be re-applied. */
    fun rememberedChatChoice(id: String): String? {
        val value = preferences.getString(chatOptionKey(id), null)?.takeUnless { it.endsWith("#autopilot") } ?: return null
        return preferences.getString("${chatOptionKey(id)}-name", null)
            ?.takeIf { preferences.getString("${chatOptionKey(id)}-name-of", null) == value }
            ?: chatValueLabel(value)
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
                        chatEntries.add(ChatEntry.Notice("Agent stopped after $minutes idle minutes to free guest memory; ${nextMessageNote().replaceFirstChar { it.lowercase() }}."))
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
        chatEntries.add(ChatEntry.Notice("Agent stopped; ${nextMessageNote().replaceFirstChar { it.lowercase() }}."))
    }

    /** What the next message does once the agent stopped: agents that reload sessions go on with the chat. */
    private fun nextMessageNote(): String =
        if (settings.chatResume && chatAgent.canLoadSession && chatShownSession != null) {
            "The next message continues this chat"
        } else {
            "The next message starts a new chat"
        }

    // Chat context ------------------------------------------------------------------------------

    private fun attach(reference: ChatReference) {
        chatAttachments.removeAll { it.path == reference.path && it.startLine == reference.startLine && it.detail == reference.detail }
        chatAttachments.add(reference)
        chatVisible = true
        status = "Added ${reference.label} to chat"
    }

    fun attachFileToChat(path: String? = activePath) {
        if (path == null || !isGuestPath(path)) {
            status = "Save the file to the guest before adding it to chat"
            return
        }
        attach(ChatReference(path))
    }

    /** The editor selection (or the cursor's line), or the selected notebook cell, as an excerpt. */
    fun attachSelectionToChat() {
        val path = activePath?.takeIf(::isGuestPath) ?: run {
            status = "Save the file to the guest before adding it to chat"
            return
        }
        activeNotebook?.let { session ->
            val index = session.notebook.cells.indexOfFirst { it.id == session.selectedCell }
            val cell = session.notebook.cells.getOrNull(index) ?: return
            attach(ChatReference(path, text = cell.source, detail = "cell ${index + 1}"))
            return
        }
        val surface = editorSurface ?: return
        val range = surface.selectionOffsets
        val hasSelection = range.first != range.last
        val start = surface.positionAt(range.first)
        val end = surface.positionAt(range.last)
        val firstLine = start.line
        val lastLine = if (hasSelection && end.line > firstLine && end.character == 0) end.line - 1 else end.line
        val excerpt = if (hasSelection) surface.text.substring(range.first, range.last) else surface.lineText(firstLine)
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

    /** The pane's and Settings' Sign in: Copilot through the GitHub CLI, the others with their own CLI. */
    fun signInChatAgent() {
        val kind = chatAgentKind
        if (kind == ChatAgentKind.Copilot) {
            signInGitHubInTerminal()
            return
        }
        val dependency = GuestDependency.forAgent(kind)
        if (dependency != null && guestTool(dependency) == DependencyState.Missing) {
            offerInstall(dependency, "${kind.label} answers in the Chat view") { signInChatAgent() }
            return
        }
        chatAgent.stop()
        chatAuthMethods = emptyList()
        chatSignInNeeded = false
        chatVisible = true
        val output = StringBuilder()
        var shown = false
        var terminal: UUID? = null
        terminal = newTerminal(
            kind.loginCommand,
            onOutput = { text ->
                if (!shown) {
                    output.append(text)
                    if (output.length > 32_768) output.delete(0, output.length - 8_192)
                    val id = terminal
                    parseSignInLink(output.toString())?.takeIf { id != null }?.let { link ->
                        shown = true
                        post { chatEntries.add(ChatEntry.SignInLink(kind.label, link.url, id!!, quitAfterCode = kind == ChatAgentKind.Gemini)) }
                    }
                }
            },
            onExit = { code ->
                post {
                    if (code == 0) finishChatSignIn(true, "Signed in to ${kind.label}. Send your message again.")
                    else finishChatSignIn(false, "${kind.label} sign-in did not finish; the terminal shows why.")
                }
            },
        )
        chatLoginTerminal = terminal
        if (terminal != null) chatEntries.add(ChatEntry.Notice("Signing in to ${kind.label} in the terminal…"))
    }

    /** Types the code the sign-in page showed into the waiting login. */
    fun sendSignInCode(entry: ChatEntry.SignInLink, code: String) {
        val trimmed = code.trim()
        if (trimmed.isEmpty()) return
        vfs.ptyInput(entry.terminal, (trimmed + "\r").toByteArray())
        val index = chatEntries.indexOf(entry)
        if (index >= 0) chatEntries[index] = entry.copy(codeSent = true)
    }

    /** Gemini stays open after signing in; this quits it, which ends the login's shell. */
    fun closeSignInAgent(entry: ChatEntry.SignInLink) {
        vfs.ptyInput(entry.terminal, "/quit".toByteArray())
        timer.schedule({ vfs.ptyInput(entry.terminal, "\r".toByteArray()) }, 400, TimeUnit.MILLISECONDS)
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

    /**
     * One GitHub login for Copilot chat and Git through `gh` (see [GITHUB_SIGN_IN_COMMAND]). The
     * code appears as a card in chat; gh's "Press Enter to open the browser" is answered here.
     */
    fun signInGitHubInTerminal() {
        chatAgent.stop()
        chatAuthMethods = emptyList()
        chatVisible = true
        val output = StringBuilder()
        var prompted = false
        var enterSent = false
        var gitAnswered = false
        var terminal: UUID? = null
        terminal = newTerminal(
            GITHUB_SIGN_IN_COMMAND,
            onOutput = { text ->
                if (enterSent) return@newTerminal
                output.append(text)
                if (output.length > 16_384) output.delete(0, output.length - 4_096)
                val seen = output.toString()
                // gh asks before the code; its default (Yes) is what this flow is for.
                if (!gitAnswered && stripAnsi(seen).contains("Authenticate Git with your GitHub credentials")) {
                    gitAnswered = true
                    terminal?.let { vfs.ptyInput(it, "\r".toByteArray()) }
                }
                if (!prompted) {
                    parseDeviceCodePrompt(seen)?.let { prompt ->
                        prompted = true
                        post { chatEntries.add(ChatEntry.DeviceCode(prompt.url, prompt.code)) }
                    }
                }
                if (prompted && stripAnsi(seen).contains("Press Enter")) {
                    enterSent = true
                    terminal?.let { vfs.ptyInput(it, "\r".toByteArray()) }
                }
            },
            onExit = { code ->
                post {
                    if (code == 0) {
                        finishChatSignIn(true, "Signed in to GitHub. Copilot chat and Git use this account; send your message again.")
                        refreshGitStatus()
                    } else {
                        finishChatSignIn(false, "GitHub sign-in did not finish; the terminal shows why.")
                    }
                }
            },
        )
        chatLoginTerminal = terminal
        if (terminal != null) chatEntries.add(ChatEntry.Notice("Signing in to GitHub in the terminal (installs the GitHub CLI the first time)…"))
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
        val index = chatEntries.indexOfLast { it is ChatEntry.DeviceCode || it is ChatEntry.SignInLink }
        if (success && index >= 0) {
            chatEntries[index] = when (val entry = chatEntries[index]) {
                is ChatEntry.DeviceCode -> entry.copy(done = true)
                is ChatEntry.SignInLink -> entry.copy(done = true)
                else -> entry
            }
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
            onKeyboardInput = { bytes ->
                val replaying = SystemClock.uptimeMillis() < session.replayUntil
                if (!(replaying && isTerminalReply(bytes))) vfs.ptyInput(id, bytes)
            },
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
            is PtyEvent.Output -> {
                session.emulator.writeInput(event.data, 0, event.data.size)
                session.textLog.append(event.data)
            }
            is PtyEvent.Spawned -> if (initialCommand != null) {
                timer.schedule({ vfs.ptyInput(session.id, "$initialCommand\r".toByteArray()) }, 400, TimeUnit.MILLISECONDS)
            }
            is PtyEvent.Exit -> post {
                session.exited = true
                session.exitCode = event.code
                session.emulator.writeInput("\r\n[process exited with code ${event.code ?: "?"}]\r\n".toByteArray())
            }
            is PtyEvent.Error -> if (event.message == "unknown terminal session") {
                // After the daemon restarts (an update, a guest reboot) its shells are gone. Every
                // key or resize sent to one gets this error back; say it once.
                post { endLostTerminal(session) }
            } else {
                session.emulator.writeInput("\r\n[terminal error: ${event.message}]\r\n".toByteArray())
            }
        }
    }

    private fun endLostTerminal(session: TerminalSession) {
        if (session.exited) return
        session.exited = true
        session.emulator.writeInput("\r\n[this terminal ended when the guest daemon restarted; open a new one with +]\r\n".toByteArray())
    }

    // Terminal find and links ---------------------------------------------------------------------

    /** The terminal panel's find bar (Ctrl+F in a terminal, as in VS Code). */
    var terminalFindVisible by mutableStateOf(false)
    var terminalFindQuery by mutableStateOf("")
    var terminalFindCaseSensitive by mutableStateOf(false)
    /** Bumped to put the keyboard in the find field. */
    var terminalFindFocus by mutableIntStateOf(0)
        private set

    fun showTerminalFind() {
        panelVisible = true
        panelTab = PanelTab.Terminal
        compactDestination = CompactDestination.Terminal
        terminalFindVisible = true
        terminalFindFocus++
    }

    /** The active terminal's lines that have the find text (the view re-reads as output arrives). */
    fun terminalMatches(): List<TerminalMatch> {
        val session = activeTerminal ?: return emptyList()
        return findInTerminal(session.textLog.snapshot(), terminalFindQuery, terminalFindCaseSensitive)
    }

    /**
     * A traceback reference in a notebook output: `Cell In[3], line 5` selects the cell that ran
     * as [3] (its line is named in the status bar); a file opens at its line.
     */
    fun openTracebackTarget(session: NotebookSession, target: TracebackTarget) {
        when (target) {
            is TracebackTarget.Cell -> {
                val cell = session.notebook.cells.firstOrNull { it.executionCount == target.executionCount }
                if (cell == null) {
                    status = "No cell ran as [${target.executionCount}] since the kernel started; it may have run again"
                    return
                }
                session.selectedCell = cell.id
                session.caretAtEnd = false
                session.focusRequest++
                status = "Cell [${target.executionCount}], line ${target.line}"
            }
            is TracebackTarget.File -> {
                val root = workspaceRoot?.trimEnd('/') ?: return
                openGuestFileAt(guestPath(target.path, root), target.line, null)
            }
        }
    }

    /** A tapped find result: its first link opens; a line without one is copied. */
    fun openTerminalLine(text: String) {
        val link = detectTerminalLinks(listOf(text)).firstOrNull()
        if (link != null) {
            openTerminalLink(link)
        } else {
            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Terminal line", text))
            status = "Copied the line"
        }
    }

    /** Terminal: Open Detected Link…: the web addresses and file:line references in the terminal's output. */
    fun showTerminalLinks() {
        val session = activeTerminal
        if (session == null) {
            status = "Open a terminal first"
            return
        }
        val links = detectTerminalLinks(session.textLog.snapshot())
        if (links.isEmpty()) {
            status = "No links or file:line references in this terminal's output"
            return
        }
        commandFilter = ""
        dialog = WorkbenchDialog.TerminalLinks(links)
    }

    /**
     * Opens a link from the terminal: a web address in the browser; a file (relative ones from
     * the workspace folder, where terminals start) at its line, once the guest confirms it exists.
     */
    fun openTerminalLink(link: TerminalLink) {
        dialog = null
        when (link) {
            is TerminalLink.Web -> openLink(link.url)
            is TerminalLink.File -> {
                val root = workspaceRoot?.trimEnd('/') ?: return
                openGuestFileAt(guestPath(link.path, root), link.line, link.column)
            }
        }
    }

    /** The guest user's home, from the workspace path (/home/<user>/…). */
    private fun guestHome(root: String): String =
        Regex("^/home/[^/]+").find(root)?.value ?: root

    /** An absolute guest path for [path]: as is, `~/` from the home, else from [base]. */
    private fun guestPath(path: String, base: String): String = when {
        path.startsWith("/") -> path
        path == "~" || path.startsWith("~/") -> guestHome(base) + path.removePrefix("~")
        else -> java.io.File(base, path).normalize().path
    }

    /** Opens [path] at [line] and [column] (1-based) once the guest says it is a file. */
    private fun openGuestFileAt(path: String, line: Int?, column: Int?) {
        vfs.stat(path).whenComplete { entry, error ->
            post {
                if (error != null || entry.isDirectory) {
                    status = "No file at $path"
                    return@post
                }
                val position = JsonObject().apply {
                    addProperty("line", ((line ?: 1) - 1).coerceAtLeast(0))
                    addProperty("character", ((column ?: 1) - 1).coerceAtLeast(0))
                }
                navigateToLocation(LanguageLocation(java.net.URI("file", "", path, null).toASCIIString(), JsonObject().apply {
                    add("start", position)
                    add("end", position)
                }))
            }
        }
    }

    /** Reconnects the panel to shells the guest kept running, including ones from an earlier app session. */
    private fun reattachTerminals() {
        vfs.guestPtySessions.forEach { info ->
            val session = terminals.firstOrNull { it.id == info.sessionId }
                ?: createTerminalSession(info.sessionId, info.cwd.substringAfterLast('/').ifEmpty { "bash" })
                    .also(terminals::add)
            session.emulator.clearScreen()
            // The replay sends the scrollback again; the text copy starts over with it.
            session.textLog.clear()
            session.replayUntil = SystemClock.uptimeMillis() + TERMINAL_REPLAY_WINDOW_MS
            vfs.addPtyListener(session.id) { event -> onPtyEvent(session, event, null) }
            vfs.ptyReattach(session.id)
        }
        terminals.filter { session -> vfs.guestPtySessions.none { it.sessionId == session.id } }
            .forEach(::endLostTerminal)
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
        if (!isGuestPath(document.path)) {
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
        // A restored draft has unsaved cells.
        notebook.dirty = document.dirty
        val kernel = KernelClient(vfs)
        val session = NotebookSession(document, notebook, kernel)
        kernel.outputListener = { cellId, output, displayId -> post { appendCellOutput(session, cellId, output, displayId) } }
        kernel.updateDisplayListener = { displayId, output ->
            post { if (session.displays.update(notebook.cells, displayId, output)) notebook.dirty = true }
        }
        kernel.clearListener = { cellId, wait ->
            post { notebook.cells.firstOrNull { it.id == cellId }?.let { session.displays.clear(it, wait) } }
        }
        // The bridge runs executes one at a time, in the order sent, and says "busy" as each
        // starts: that starts the oldest cell still waiting (and its timer).
        kernel.busyListener = { busy ->
            post {
                session.busy = busy
                if (busy) {
                    session.executing.firstOrNull { !it.running }?.let { cell ->
                        cell.queued = false
                        cell.running = true
                        cell.runStartedAt = System.currentTimeMillis()
                    }
                }
            }
        }
        kernel.exitListener = { message ->
            post {
                session.kernelState = message
                session.kernelName = null
                val now = System.currentTimeMillis()
                notebook.cells.forEach { cell ->
                    if (cell.running) cell.lastRun = CellRun(failed = true, millis = cell.runStartedAt?.let { now - it }, finishedAt = now)
                    cell.running = false
                    cell.queued = false
                    cell.runStartedAt = null
                }
                session.executing.clear()
            }
        }
        notebooks[document.path] = session
        return true
    }

    private fun appendCellOutput(session: NotebookSession, cellId: String, output: JsonObject, displayId: String? = null) {
        val cell = session.notebook.cells.firstOrNull { it.id == cellId } ?: return
        session.displays.beforeOutput(cell)
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
            session.displays.append(cell, output, displayId)
        }
        session.notebook.dirty = true
    }

    /** Lists kernels from the guest (conda, venvs, system Python with ipykernel) and asks which to use. */
    /** Finds kernels; starts [preferredSpec] (or the notebook's own kernel) directly when it exists. */
    fun chooseKernel(session: NotebookSession, preferredSpec: String? = null) {
        val directory = session.document.path.substringBeforeLast('/')
        // Opening the picker while a kernel runs must not make it look stopped.
        val running = session.kernelName != null
        if (!running) session.kernelState = "Finding kernels…"
        val launched = if (session.kernel.isStarted) CompletableFuture.completedFuture(Unit) else session.kernel.launch(directory)
        launched.thenCompose { session.kernel.kernelSpecs() }.whenComplete { discovery, error ->
            post {
                if (error != null) {
                    val message = generateSequence(error) { it.cause }.last().message ?: "the bridge didn't start"
                    session.kernelState = "Kernel bridge unavailable"
                    if (missingDependencyIn(message) == GuestDependency.JupyterBridge) {
                        status = "Jupyter: the kernel bridge isn't installed in the guest"
                        offerInstall(GuestDependency.JupyterBridge, "Notebooks run through the Jupyter bridge, which isn't installed in the guest.") {
                            chooseKernel(session, preferredSpec)
                        }
                    } else {
                        status = "Jupyter: $message"
                    }
                    return@post
                }
                val specs = discovery.specs
                if (!running) session.kernelState = if (specs.isEmpty()) "No kernels yet" else "No kernel"
                val preferred = preferredSpec ?: session.notebook.kernelName ?: settings.jupyterDefaultKernel
                val match = specs.firstOrNull { it.name == preferred }
                // Rows that set up an environment, for a guest with no conda yet.
                val picker = {
                    checkGuestTools {
                        val setup = listOf(GuestDependency.Conda).filter { guestTool(it) == DependencyState.Missing }
                        if (specs.isEmpty() && discovery.installable.isEmpty() && setup.isEmpty()) {
                            session.kernelState = "No kernels found"
                            status = "No Python environment with ipykernel was found in the guest"
                        } else {
                            dialog = WorkbenchDialog.KernelPicker(session.document.path, specs, discovery.installable, setup)
                        }
                    }
                }
                if (match != null && session.kernelName == null) {
                    // A notebook from elsewhere (VS Code, Copilot) names a kernel such as "python3"
                    // that may not start here; fall back to asking instead of failing on every run.
                    startKernel(session, match, onFailure = picker)
                } else {
                    picker()
                }
            }
        }
    }

    fun startKernel(session: NotebookSession, spec: com.avfstudio.nativeide.notebook.KernelSpec, onFailure: (() -> Unit)? = null) {
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
        ready.thenCompose { session.kernel.startKernel(spec.name, directory, settings.plotlyDisplayIds) }.whenComplete { _, error ->
            post {
                if (error != null) {
                    session.kernelState = "Kernel failed to start"
                    status = "Kernel ${spec.displayName}: ${error.cause?.message ?: error.message}"
                    // Queued cells wait for the kernel picked next instead of retrying this one.
                    if (onFailure != null) onFailure() else pendingExecution.remove(session.document.path)?.forEach { it.queued = false }
                    documentRevision++
                } else {
                    session.kernelName = spec.displayName
                    session.kernelState = "Idle"
                    session.variables = null
                    refreshVariablesIfShown(session)
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

    /** Drops cells waiting for a kernel (the picker was dismissed), so the next run asks again. */
    fun cancelPendingExecution(session: NotebookSession) {
        pendingExecution.remove(session.document.path)
        session.notebook.cells.forEach { it.queued = false }
        documentRevision++
    }

    /**
     * Installs ipykernel into a guest environment in a visible terminal, so nothing changes
     * silently. When pip succeeds the terminal closes its shell and the new kernel starts.
     */
    fun installKernelPackage(session: NotebookSession, candidate: com.avfstudio.nativeide.notebook.InstallableKernel) {
        dialog = null
        session.notebook.cells.forEach { it.queued = false }
        pendingExecution.remove(session.document.path)
        // Debian's system Python refuses pip (PEP 668, "externally managed"): give it a venv in
        // ~/.venv, which the bridge discovers, instead of installing into the system.
        val systemPython = candidate.label == "system"
        val uv = guestTool(GuestDependency.Uv) is DependencyState.Found
        val command = if (systemPython) {
            createVenvCommand(candidate.python, uv) + " && exit"
        } else {
            ipykernelInstallCommand(candidate.python, (guestTool(GuestDependency.Conda) as? DependencyState.Found)?.path, uv) + " && exit"
        }
        newTerminal(command, onExit = { code ->
            post {
                if (code == 0) {
                    chooseKernel(session, generatedKernelName(if (systemPython) ".venv" else candidate.label))
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
        cell.lastRun = null
        cell.outputs.clear()
        cell.outputPresentation.clear()
        // An earlier run's saved timing no longer applies (JupyterLab drops it too).
        clearTiming(cell.metadata)
        session.executing.addLast(cell)
        session.notebook.dirty = true
        documentRevision++
        session.kernel.execute(cell.id, cell.source).whenComplete { reply, error ->
            post {
                val now = System.currentTimeMillis()
                val started = cell.runStartedAt
                session.executing.remove(cell)
                cell.queued = false
                cell.running = false
                cell.runStartedAt = null
                val millis = started?.let { now - it }
                when {
                    error != null -> {
                        status = "Execution failed: ${error.cause?.message ?: error.message}"
                        cell.lastRun = CellRun(failed = true, millis = millis, finishedAt = now)
                    }
                    // Skipped after an earlier cell failed (stop_on_error): it didn't run.
                    reply.status == "aborted" -> cell.lastRun = null
                    else -> {
                        cell.executionCount = reply.executionCount
                        cell.lastRun = CellRun(failed = reply.status == "error", millis = millis, finishedAt = now)
                        if (settings.recordTiming && started != null) recordTiming(cell.metadata, started, now)
                    }
                }
                session.kernelState = if (session.executing.isEmpty()) "Idle" else "Busy"
                documentRevision++
                if (session.executing.isEmpty()) refreshVariablesIfShown(session)
            }
        }
        session.kernelState = "Busy"
    }

    // Variables ---------------------------------------------------------------------------------

    /** The notebook the Variables view shows: the active one, else the last one looked at. */
    private var variablesNotebook: String? = null

    val variablesSession: NotebookSession?
        get() = activeNotebook?.also { variablesNotebook = it.document.path } ?: variablesNotebook?.let(notebooks::get)

    private val variablesShown: Boolean
        get() = panelTab == PanelTab.Variables && (panelVisible || compactDestination == CompactDestination.Terminal)

    /** After a run, the view follows the kernel; a closed view asks nothing (it costs the kernel a moment). */
    private fun refreshVariablesIfShown(session: NotebookSession) {
        if (variablesShown && variablesSession === session) refreshVariables(session)
    }

    fun refreshVariables(session: NotebookSession? = variablesSession) {
        session ?: return
        if (!session.kernel.isStarted || session.kernelName == null) {
            session.variables = null
            session.variablesError = null
            return
        }
        if (session.variablesLoading) return
        session.variablesLoading = true
        session.kernel.variables().whenComplete { result, error ->
            post {
                session.variablesLoading = false
                session.variablesError = error?.let { it.cause?.message ?: it.message ?: "The kernel didn't answer" }
                if (error == null) session.variables = result
            }
        }
    }

    /** Opens the Variables view (the notebook toolbar's button, "Jupyter: Show Variables"). */
    fun showVariables() {
        panelVisible = true
        panelTab = PanelTab.Variables
        compactDestination = CompactDestination.Terminal
        refreshVariables()
    }

    /** A DataFrame, Series or array in the data viewer: its first [DATA_VIEWER_ROWS] rows as a sortable table. */
    fun openDataViewer(session: NotebookSession, variable: com.avfstudio.nativeide.notebook.KernelVariable) {
        dialog = WorkbenchDialog.DataViewer(variable.name)
        session.kernel.table(variable.name, DATA_VIEWER_ROWS).whenComplete { table, error ->
            post {
                if ((dialog as? WorkbenchDialog.DataViewer)?.name != variable.name) return@post
                dialog = when {
                    error != null -> WorkbenchDialog.DataViewer(variable.name, error = error.cause?.message ?: error.message ?: "The kernel didn't answer")
                    table == null -> WorkbenchDialog.DataViewer(variable.name, error = "${variable.name} can't be shown as a table")
                    else -> WorkbenchDialog.DataViewer(variable.name, table)
                }
            }
        }
    }

    /** The status bar says when the cell's last run finished, how, and how long it took. */
    fun describeCellRun(session: NotebookSession, cell: NotebookCell) {
        val run = cell.lastRun ?: return
        val number = session.notebook.cells.indexOf(cell) + 1
        val at = run.finishedAt?.let {
            " at " + java.text.DateFormat.getTimeInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(it))
        }.orEmpty()
        val took = run.millis?.let { " in ${formatRunDuration(it)}" }.orEmpty()
        status = "Cell $number ${if (run.failed) "failed" else "finished"}$at$took"
    }

    fun setOutputsHidden(session: NotebookSession, cell: NotebookCell, hidden: Boolean) {
        cell.hideOutputs(hidden)
        session.notebook.dirty = true
        documentRevision++
    }

    fun copyOutputText(text: String) {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Cell output", text))
        status = "Output copied"
    }

    fun clearOutput(session: NotebookSession, cell: NotebookCell, index: Int) {
        if (index !in cell.outputs.indices) return
        cell.outputs.removeAt(index)
        // Picked views follow their outputs down by one.
        val shifted = cell.outputPresentation.mapNotNull { (key, value) ->
            when {
                key < index -> key to value
                key > index -> key - 1 to value
                else -> null
            }
        }
        cell.outputPresentation.clear()
        cell.outputPresentation.putAll(shifted)
        session.notebook.dirty = true
        documentRevision++
    }

    fun clearCellOutputs(session: NotebookSession, cell: NotebookCell) {
        cell.outputs.clear()
        cell.outputPresentation.clear()
        session.notebook.dirty = true
        documentRevision++
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
                session.variables = null
                refreshVariablesIfShown(session)
            }
        }
    }

    fun clearAllOutputs(session: NotebookSession) {
        session.notebook.cells.forEach { cell ->
            cell.outputs.clear()
            cell.outputPresentation.clear()
            cell.executionCount = null
            // As VS Code: clearing outputs also clears the run marks and their saved timing.
            if (!cell.running && !cell.queued) cell.lastRun = null
            clearTiming(cell.metadata)
        }
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
    fun selectAdjacentCell(session: NotebookSession, cell: NotebookCell, delta: Int, goalX: Float? = null): Boolean {
        val cells = session.notebook.cells
        val target = cells.getOrNull(cells.indexOf(cell) + delta) ?: return false
        session.selectedCell = target.id
        session.caretAtEnd = delta < 0
        session.caretGoalX = goalX
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
        // Keep a draft like text files do, so a restart (the tablet has rebooted mid-session)
        // doesn't lose unsaved cells.
        val path = session.document.path
        scheduleAutoSave(path)
        synchronized(scheduledDraftWrites) {
            scheduledDraftWrites.remove(path)?.cancel(false)
            scheduledDraftWrites[path] = timer.schedule(
                {
                    post {
                        if (notebooks[path] !== session || !session.notebook.dirty) return@post
                        session.document.updateText(session.notebook.toJson())
                        io.execute { repository.persistDraft(session.document) }
                    }
                    synchronized(scheduledDraftWrites) { scheduledDraftWrites.remove(path) }
                },
                500,
                TimeUnit.MILLISECONDS,
            )
        }
    }

    // Copilot in notebooks ----------------------------------------------------------------------

    /** Notebook path → the text last sent to Copilot for its mirror document, and its version. */
    private val notebookMirrors = mutableMapOf<String, Pair<String, Long>>()
    private var scheduledCellRequest: ScheduledFuture<*>? = null

    private fun mirrorPath(notebookPath: String) = "$notebookPath.avf-copilot.py"

    /** Sends the notebook's cells to Copilot as one Python document; returns its line map and version. */
    private fun syncNotebookMirror(session: NotebookSession): Pair<NotebookMirror, Long> {
        val path = session.document.path
        val mirror = notebookMirror(session.notebook.cells)
        val previous = notebookMirrors[path]
        val version = when {
            previous == null -> 1L.also { copilot.language.didOpen(mirrorPath(path), "python", it, mirror.text) }
            previous.first != mirror.text -> (previous.second + 1).also { copilot.language.didChange(mirrorPath(path), it, mirror.text) }
            else -> previous.second
        }
        notebookMirrors[path] = mirror.text to version
        return mirror to version
    }

    private fun closeNotebookMirror(path: String) {
        if (notebookMirrors.remove(path) != null) copilot.language.didClose(mirrorPath(path))
    }

    /** Every cell edit or caret move: drop a suggestion that no longer fits and ask again after typing. */
    /** Ctrl+Space, or Tab after a name: asks the running kernel what could come next. */
    fun requestCellCompletion(session: NotebookSession, cell: NotebookCell, value: TextFieldValue) {
        if (session.kernelName == null || !session.kernel.isStarted) {
            status = "Start a kernel to get completions (run a cell or pick one)"
            return
        }
        val code = value.text
        val caret = value.selection.start
        session.kernel.complete(code, caret).whenComplete { result, error ->
            post {
                when {
                    error != null -> status = "Completion failed: ${error.cause?.message ?: error.message}"
                    cell.source != code -> Unit
                    result.matches.isEmpty() -> status = "No completions here"
                    else -> session.cellCompletion = CellCompletion(cell.id, result.start.coerceIn(0, caret), result.matches)
                }
            }
        }
    }

    fun onCellInput(session: NotebookSession, cell: NotebookCell, value: TextFieldValue, textChanged: Boolean) {
        session.cellCompletion?.let { completion ->
            if (completion.cellId != cell.id || completion.visibleItems(value) == null) session.cellCompletion = null
        }
        val shown = session.cellSuggestion
        if (shown != null && (shown.cellId != cell.id || !value.selection.collapsed ||
                cellGhostText(value.text, value.selection.start, shown) == null)
        ) {
            session.cellSuggestion = null
        }
        if (!textChanged) return
        scheduledCellRequest?.cancel(false)
        if (!copilotEnabled || !settings.notebookCopilot || !copilot.language.isReady || cell.type != CellType.Code ||
            !value.selection.collapsed
        ) return
        val caret = value.selection.start
        val source = value.text
        scheduledCellRequest = timer.schedule(
            { post { requestCellSuggestion(session, cell, source, caret) } },
            settings.inlineSuggestDelayMs.toLong(),
            TimeUnit.MILLISECONDS,
        )
    }

    private fun requestCellSuggestion(session: NotebookSession, cell: NotebookCell, source: String, caret: Int) {
        if (cell.source != source || notebooks[session.document.path] !== session) return
        val (mirror, version) = syncNotebookMirror(session)
        val startLine = mirror.startLines[cell.id] ?: return
        val (line, column) = cellPosition(source, caret)
        copilotLog { "cell request ${cell.id} $line:$column v$version" }
        copilot.inlineCompletion(
            mirrorPath(session.document.path), version, LspPosition(startLine + line, column),
            settings.tabSize, settings.insertSpaces, invoked = false,
        ).whenComplete { suggestions, error ->
            post {
                val suggestion = suggestions?.firstOrNull { it.insertText.isNotBlank() }
                    ?.let { cellSuggestion(cell, startLine, it) }
                copilotLog { "cell reply ${suggestions?.size} mapped=${suggestion != null} current=${cell.source == source} error=${error?.message}" }
                if (suggestion != null && cell.source == source && session.selectedCell == cell.id &&
                    cellGhostText(source, caret, suggestion) != null
                ) {
                    session.cellSuggestion = suggestion
                    copilot.didShow(suggestion.source)
                }
            }
        }
    }

    /** Tab with a suggestion showing: the cell text with it applied, caret after it. */
    fun acceptCellSuggestion(session: NotebookSession, cell: NotebookCell, value: TextFieldValue): TextFieldValue? {
        val suggestion = session.cellSuggestion?.takeIf { it.cellId == cell.id } ?: return null
        if (cellGhostText(value.text, value.selection.start, suggestion) == null) return null
        session.cellSuggestion = null
        val end = suggestion.end.coerceIn(suggestion.start, value.text.length)
        val text = value.text.substring(0, suggestion.start) + suggestion.text + value.text.substring(end)
        copilot.accepted(suggestion.source)
        return TextFieldValue(text, TextRange(suggestion.start + suggestion.text.length))
    }

    /** `adb shell setprop log.tag.AVFLSP DEBUG` logs documents opened in language servers and their diagnostics. */
    private inline fun lspLog(message: () -> String) {
        if (android.util.Log.isLoggable("AVFLSP", android.util.Log.DEBUG)) android.util.Log.d("AVFLSP", message())
    }

    /** `adb shell setprop log.tag.AVFCOP DEBUG` logs inline suggestion requests and replies. */
    private inline fun copilotLog(message: () -> String) {
        if (android.util.Log.isLoggable("AVFCOP", android.util.Log.DEBUG)) android.util.Log.d("AVFCOP", message())
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
                        closeIfPendingAfterSave(session.document.path)
                    }
                    result is SaveResult.QueuedOffline -> {
                        status = "Saved locally; queued until guest reconnects"
                        closeIfPendingAfterSave(session.document.path)
                    }
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

    // Language packs (syntax colours on demand) -------------------------------------------------

    /** Bumped when a grammar arrives or goes, so open editors are made again with it. */
    var grammarRevision by mutableIntStateOf(0)
        private set
    /** Packs being downloaded, by id. */
    val languagePackDownloads = mutableStateListOf<String>()
    /** File types the user said "Not now" to in this session. */
    private val languagePacksPostponed = mutableStateListOf<String>()
    private var languagePacksDeclined by mutableStateOf(preferences.getStringSet(LANGUAGES_DECLINED_KEY, emptySet()).orEmpty())

    private val packsDirectory: File get() = File(context.filesDir, "asset-packs")

    /** Whether some grammar colours [path] (Python's built in, or an installed pack's). */
    fun hasGrammar(path: String): Boolean {
        val name = path.substringAfterLast('/')
        return name.substringAfterLast('.', "").lowercase() in setOf("py", "pyi") || AssetPackGrammarLoader.scopeNameFor(name) != null
    }

    /**
     * The pack the editor offers for the active file: a text file with no grammar whose type a
     * VS Code built-in extension colours, unless the user turned the offer down
     * (`avf.languages.download`: ask, always, never).
     */
    val languagePackOffer: LanguagePack?
        get() {
            grammarRevision
            val document = activeDocument?.takeUnless { it.binary || activeNotebook != null } ?: return null
            if (settings.languageDownloads != LanguageDownloads.Ask || hasGrammar(document.path)) return null
            val pack = languagePackFor(document.path.substringAfterLast('/')) ?: return null
            return pack.takeUnless { it.id in languagePacksPostponed || it.id in languagePacksDeclined }
        }

    /** With `always`, a file without colours fetches its pack as soon as it opens. */
    private fun fetchLanguagePackIfWanted(path: String) {
        if (settings.languageDownloads != LanguageDownloads.Always || hasGrammar(path)) return
        languagePackFor(path.substringAfterLast('/'))?.let(::installLanguagePack)
    }

    fun postponeLanguagePack(pack: LanguagePack) {
        languagePacksPostponed += pack.id
    }

    fun declineLanguagePack(pack: LanguagePack) {
        languagePacksDeclined = languagePacksDeclined + pack.id
        preferences.edit().putStringSet(LANGUAGES_DECLINED_KEY, languagePacksDeclined).apply()
        status = "${pack.languageName} files stay without colours; Settings → Languages can fetch them later"
    }

    /** Downloads [pack] from Open VSX, checks its pinned SHA-256, installs it, and colours open tabs. */
    fun installLanguagePack(pack: LanguagePack) {
        if (pack.id in languagePackDownloads) return
        languagePackDownloads += pack.id
        status = "Downloading ${pack.languageName} colours from Open VSX…"
        io.execute {
            val result = runCatching {
                val directory = File(packsDirectory, pack.id)
                if (!directory.isDirectory) {
                    val request = okhttp3.Request.Builder().url(pack.downloadUrl).build()
                    val bytes = languagePackHttp.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw IOException("Open VSX answered ${response.code}")
                        val body = response.body ?: throw IOException("Open VSX sent nothing")
                        if (body.contentLength() > MAX_LANGUAGE_PACK_BYTES) throw IOException("The pack is larger than expected")
                        body.byteStream().use { input ->
                            val output = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(16 * 1024)
                            while (output.size() <= MAX_LANGUAGE_PACK_BYTES) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        }
                    }
                    if (bytes.size > MAX_LANGUAGE_PACK_BYTES) throw IOException("The pack is larger than expected")
                    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                    if (digest != pack.sha256) throw IOException("The download doesn't match the checksum MaterialCode expects")
                    AssetPackInstaller(packsDirectory).install(bytes.inputStream())
                }
                AssetPackGrammarLoader.loadPack(directory)
            }
            post {
                languagePackDownloads -= pack.id
                result.onSuccess {
                    grammarRevision++
                    status = "${pack.languageName} files are coloured now"
                }.onFailure { error ->
                    status = "Couldn't get ${pack.languageName} colours: ${error.message ?: error.javaClass.simpleName}"
                }
            }
        }
    }

    /** The language packs on this device (downloaded or imported .vsix files with grammars), for Settings. */
    fun installedLanguagePacks(): List<Pair<String, String>> {
        grammarRevision
        return packsDirectory.listFiles()?.filter { File(it, "extension/package.json").isFile }?.mapNotNull { directory ->
            val known = LANGUAGE_PACKS.firstOrNull { it.id == directory.name }
            val manifest = runCatching { JsonParser.parseString(File(directory, "extension/package.json").readText()).asJsonObject }.getOrNull()
            if (manifest?.getAsJsonObject("contributes")?.has("grammars") != true) return@mapNotNull null
            directory.name to (known?.languageName ?: manifest.get("displayName")?.asString?.takeUnless { it.startsWith("%") } ?: directory.name)
        }.orEmpty().sortedBy { it.second.lowercase() }
    }

    fun removeLanguagePack(id: String) {
        AssetPackGrammarLoader.forgetPack(id)
        File(packsDirectory, id).deleteRecursively()
        grammarRevision++
        status = "Removed $id; its files open without colours"
    }

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

    // Keyboard shortcuts --------------------------------------------------------------------------

    /** Every command's bindings now: the user's rules over the defaults (several when a command has more than one). */
    val effectiveCommands: List<EditorCommand> get() = applyKeybindings(EditorCommands.all, keybindingRules)

    /** The first key [commandId] answers to, for menus and the palette; empty when it has none. */
    fun keybindingFor(commandId: String): String =
        effectiveCommands.firstOrNull { it.id == commandId && it.keybinding.isNotEmpty() }?.keybinding.orEmpty()

    /** Whether the user changed [commandId]'s binding (Keyboard Shortcuts marks it and offers Reset). */
    fun keybindingChanged(commandId: String): Boolean = keybindingRules.any { it.commandId == commandId }

    private fun applyKeybindingRules(rules: List<KeybindingRule>, text: String = formatKeybindings(rules)): Boolean {
        if (!preferences.edit().putString(KEYBINDINGS_KEY, text).commit()) {
            status = "Keyboard shortcuts could not be saved"
            return false
        }
        keybindingRules = rules
        editorDispatcher = EditorKeybindingDispatcher(applyKeybindings(EditorCommands.all, rules))
        globalDispatcher = EditorKeybindingDispatcher(applyKeybindings(EditorCommands.global, rules))
        notebookDispatcher = EditorKeybindingDispatcher(applyKeybindings(EditorCommands.global + EditorCommands.notebook, rules))
        terminalDispatcher = EditorKeybindingDispatcher(applyKeybindings(EditorCommands.terminal + EditorCommands.global, rules))
        return true
    }

    /** Keys recorded for [WorkbenchDialog.RecordKeybinding]: one stroke, or two for a chord. */
    var recordedKeys by mutableStateOf(emptyList<KeyStroke>())
        private set

    fun beginRecordingKeybinding(commandId: String) {
        recordedKeys = emptyList()
        dialog = WorkbenchDialog.RecordKeybinding(commandId)
    }

    /**
     * A key pressed while Keyboard Shortcuts records (the dialog's own window gets it, so the
     * IME never does): modifiers alone wait, Enter saves, Escape cancels, a third stroke starts over.
     */
    fun recordKey(event: AndroidKeyEvent): Boolean {
        val recording = dialog as? WorkbenchDialog.RecordKeybinding ?: return false
        if (event.action != AndroidKeyEvent.ACTION_DOWN) return true
        if (AndroidKeyEvent.isModifierKey(event.keyCode)) return true
        val plain = !event.isCtrlPressed && !event.isAltPressed && !event.isShiftPressed
        if (plain && event.keyCode == AndroidKeyEvent.KEYCODE_ESCAPE) {
            dialog = WorkbenchDialog.Keybindings
            return true
        }
        if (plain && event.keyCode == AndroidKeyEvent.KEYCODE_ENTER) {
            if (recordedKeys.isNotEmpty()) saveRecordedKeybinding(recording.commandId)
            return true
        }
        val stroke = editorKeyStroke(event.keyCode, event.isCtrlPressed, event.isShiftPressed, event.isAltPressed, allowUnmodified = true)
            ?: return true
        recordedKeys = if (recordedKeys.size >= 2) listOf(stroke) else recordedKeys + stroke
        return true
    }

    /** Other commands the recorded keys already run, shown under them in the recorder. */
    fun recordedKeyConflicts(commandId: String): List<String> {
        val keys = recordedKeys.joinToString(" ") { it.label() }
        if (keys.isEmpty()) return emptyList()
        val strokes = keys.split(' ').map(KeyStroke::parse)
        return effectiveCommands.filter { it.id != commandId && it.keybinding.isNotEmpty() && it.keybinding.split(' ').map(KeyStroke::parse) == strokes }
            .map { it.title }.distinct()
    }

    fun saveRecordedKeybinding(commandId: String) {
        val keys = recordedKeys.joinToString(" ") { it.label() }.ifEmpty { return }
        if (applyKeybindingRules(rebind(keybindingRules, commandId, keys))) {
            status = "${commandTitle(commandId)}: $keys"
            dialog = WorkbenchDialog.Keybindings
        }
    }

    fun removeKeybinding(commandId: String) {
        if (applyKeybindingRules(rebind(keybindingRules, commandId, null))) {
            status = "${commandTitle(commandId)} has no shortcut now"
            dialog = WorkbenchDialog.Keybindings
        }
    }

    fun resetKeybinding(commandId: String) {
        if (applyKeybindingRules(resetKeybinding(keybindingRules, commandId))) {
            status = "${commandTitle(commandId)} uses its default shortcut again"
            dialog = WorkbenchDialog.Keybindings
        }
    }

    private fun commandTitle(commandId: String) = EditorCommands.all.firstOrNull { it.id == commandId }?.title ?: commandId

    /** Preferences: Open Keyboard Shortcuts (JSON): the rules as VS Code writes them. */
    fun openKeybindingsJson() {
        settingsDraft = preferences.getString(KEYBINDINGS_KEY, null)?.takeIf(String::isNotBlank) ?: KEYBINDINGS_TEMPLATE
        dialog = WorkbenchDialog.KeybindingsJson
    }

    fun saveKeybindingsJson() {
        val parsed = try {
            parseKeybindings(settingsDraft)
        } catch (error: RuntimeException) {
            status = "keybindings.json: ${error.message}"
            return
        }
        // The text is kept as typed (comments too); rules that can't apply are only reported.
        if (!applyKeybindingRules(parsed.rules, settingsDraft)) return
        dialog = null
        status = if (parsed.problems.isEmpty()) "Keyboard shortcuts saved" else {
            "Keyboard shortcuts saved; skipped ${parsed.problems.size}: ${parsed.problems.joinToString("; ")}"
        }
    }

    // Quick Open --------------------------------------------------------------------------------

    /** Opens Quick Open in the mode [prefix] picks (`>` commands, `@` symbols, `#` workspace symbols, `:` line). */
    fun openQuickOpen(prefix: String = "") {
        quickOpenFilter = prefix
        workspaceSymbols = emptyList()
        dialog = WorkbenchDialog.QuickOpen
    }

    /** `workspace/symbol` matches for Quick Open's `#`, from every running server that offers them. */
    var workspaceSymbols by mutableStateOf(emptyList<QuickOpenItem.Symbol>())
        private set
    var workspaceSymbolsSearching by mutableStateOf(false)
        private set
    private var scheduledSymbolSearch: java.util.concurrent.ScheduledFuture<*>? = null
    private var symbolSearchGeneration = 0

    private fun workspaceSymbolClients(): List<LanguageClient> =
        listOfNotNull(languageClients["python"], languageClients["typescript"]).filter { it.isReady && it.supportsWorkspaceSymbols }

    fun searchWorkspaceSymbols(query: String) {
        scheduledSymbolSearch?.cancel(false)
        val generation = ++symbolSearchGeneration
        val clients = workspaceSymbolClients()
        if (query.isBlank() || clients.isEmpty()) {
            workspaceSymbols = emptyList()
            workspaceSymbolsSearching = false
            return
        }
        workspaceSymbolsSearching = true
        scheduledSymbolSearch = timer.schedule(
            {
                val requests = clients.map { client -> client.workspaceSymbols(query.trim()).exceptionally { null } }
                CompletableFuture.allOf(*requests.toTypedArray()).whenComplete { _, _ ->
                    val root = workspaceRoot.orEmpty().trimEnd('/')
                    val items = requests.flatMap { request ->
                        workspaceSymbolItems(request.getNow(null)) { path -> path.removePrefix("$root/") }
                    }.distinctBy { Triple(it.name, it.location.uri, it.detail) }
                    post {
                        if (generation != symbolSearchGeneration) return@post
                        workspaceSymbols = rankSymbols(items, query).take(200)
                        workspaceSymbolsSearching = false
                    }
                }
            },
            200,
            TimeUnit.MILLISECONDS,
        )
    }

    /** What Quick Open lists for [query] in its current mode. */
    fun quickOpenItems(query: String): List<QuickOpenItem> {
        val mode = quickOpenMode(query)
        val text = query.removePrefix(mode.prefix)
        return when (mode) {
            QuickOpenMode.Files -> {
                val root = workspaceRoot.orEmpty().trimEnd('/')
                quickOpenIndex
                    .filter { fuzzyMatch(text, it.path.removePrefix(root)) }
                    .sortedBy { if (it.name.startsWith(text, true)) 0 else 1 }
                    .take(200)
                    .map(QuickOpenItem::File)
            }
            QuickOpenMode.Commands -> EditorCommands.all.distinctBy { it.id }
                .filter { fuzzyMatch(text.trim(), it.title) || it.id.contains(text.trim(), true) }
                .map { QuickOpenItem.Command(it, keybindingFor(it.id)) }
            QuickOpenMode.EditorSymbols -> {
                val document = activeDocument
                when {
                    document == null || activeNotebook != null -> listOf(QuickOpenItem.Message("Open a text file to go to its symbols"))
                    languageClientFor(document.path)?.isReady != true ->
                        listOf(QuickOpenItem.Message("No language server for ${document.path.substringAfterLast('/')}; symbols come from Python and TypeScript servers"))
                    else -> editorSymbolItems(outlineSymbols, diagnosticUri(document.path), text)
                        .ifEmpty { listOf(QuickOpenItem.Message("No matching symbols")) }
                }
            }
            QuickOpenMode.WorkspaceSymbols -> when {
                workspaceSymbolClients().isEmpty() ->
                    listOf(QuickOpenItem.Message("Open a Python or TypeScript file first: its language server finds the workspace's symbols"))
                text.isBlank() -> listOf(QuickOpenItem.Message("Type the name of a class, function or variable"))
                workspaceSymbols.isEmpty() -> listOf(QuickOpenItem.Message(if (workspaceSymbolsSearching) "Searching…" else "No matching symbols"))
                else -> workspaceSymbols
            }
            QuickOpenMode.Line -> {
                val surface = editorSurface
                if (surface == null || activeDocument == null || activeNotebook != null) {
                    listOf(QuickOpenItem.Message("Open a text file to go to a line"))
                } else {
                    listOf(lineItem(text, surface.lineCount, (cursorPosition?.line ?: 0) + 1))
                }
            }
            QuickOpenMode.Help -> QuickOpenMode.entries.filter { it != QuickOpenMode.Help }.map(QuickOpenItem::Prefix)
        }
    }

    fun pickQuickOpen(item: QuickOpenItem) {
        when (item) {
            is QuickOpenItem.Message -> return
            is QuickOpenItem.Prefix -> {
                quickOpenFilter = item.mode.prefix
                return
            }
            else -> Unit
        }
        dialog = null
        when (item) {
            is QuickOpenItem.File -> if (quickOpenAttachesToChat) attachFileToChat(item.entry.path) else openPath(item.entry.path)
            is QuickOpenItem.Command -> runCommand(item.command.id)
            is QuickOpenItem.Symbol -> navigateToLocation(item.location)
            is QuickOpenItem.Line -> editorSurface?.let { surface ->
                val line = (item.line - 1).coerceIn(0, surface.lineCount - 1)
                // Past the line's end the cursor goes to its end, as in VS Code.
                val length = activeDocument?.text?.lineSequence()?.elementAtOrNull(line)?.length ?: 0
                surface.moveCursor(LspPosition(line, ((item.column ?: 1) - 1).coerceIn(0, length)))
            }
            else -> Unit
        }
        quickOpenAttachesToChat = false
    }

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
            focusArea == FocusArea.Terminal -> terminalDispatcher
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
        val surface = editorSurface
        when (commandId) {
            "workbench.action.files.save" -> saveActiveDocument()
            "workbench.action.files.openFolder", "workbench.action.openRecent" -> showOpenFolder()
            "workbench.action.files.newUntitledFile" -> newUntitledFile()
            "workbench.action.files.saveAs" -> beginSaveAs()
            "workbench.action.debug.start" -> startOrContinueDebugging()
            "workbench.action.debug.stop" -> stopDebugging()
            "workbench.action.debug.restart" -> restartDebugging()
            "workbench.action.debug.pause" -> pauseDebugging()
            "workbench.action.debug.stepOver" -> debugStep("next")
            "workbench.action.debug.stepInto" -> debugStep("stepIn")
            "workbench.action.debug.stepOut" -> debugStep("stepOut")
            "editor.debug.action.toggleBreakpoint" -> toggleBreakpoint()
            "python.setInterpreter" -> chooseInterpreter()
            "workbench.view.scm" -> {
                sidebar = if (sidebar == SidebarView.SourceControl) null else SidebarView.SourceControl
                if (sidebar == SidebarView.SourceControl) refreshGitStatus()
            }
            "git.refresh" -> refreshGitStatus()
            "git.commit" -> commit()
            "git.checkout" -> showBranches()
            "git.pull" -> gitPull()
            "git.push" -> gitPush()
            "git.sync" -> gitSync()
            "git.fetch" -> gitFetch()
            "git.clone" -> dialog = WorkbenchDialog.GitClone
            "git.init" -> gitInit()
            "workbench.view.search" -> if (sidebar == SidebarView.Search && focusArea == FocusArea.Other) sidebar = null else showSearch()
            "explorer.newFile" -> beginNewEntry(folder = false)
            "explorer.newFolder" -> beginNewEntry(folder = true)
            "renameFile" -> beginRenameEntry()
            "deleteFile" -> beginDeleteEntry()
            "workbench.action.files.openFile", "workbench.action.quickOpen" -> openQuickOpen()
            "workbench.action.showCommands" -> openQuickOpen(">")
            "workbench.action.gotoSymbol" -> openQuickOpen("@")
            "workbench.action.showAllSymbols" -> openQuickOpen("#")
            "outline.focus" -> toggleSidebar(SidebarView.Outline)
            "workbench.action.closeActiveEditor" -> activePath?.let(::requestCloseDocument)
            "workbench.action.closeOtherEditors" -> activePath?.let(::closeOtherTabs)
            "workbench.action.keepEditor" -> activePath?.let(::keepTab)
            "workbench.action.pinEditor" -> activePath?.let { if (it in pinnedTabs) unpinTab(it) else pinTab(it) }
            "workbench.action.unpinEditor" -> activePath?.let(::unpinTab)
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
            "workbench.action.output.toggleOutput" -> {
                panelVisible = !(panelVisible && panelTab == PanelTab.Output)
                panelTab = PanelTab.Output
            }
            "avf.guest.showResources" -> dialog = WorkbenchDialog.GuestResources
            "workbench.action.terminal.toggleTerminal" -> toggleTerminal()
            "workbench.action.terminal.new" -> newTerminal()
            "workbench.action.chat.open" -> {
                chatVisible = !chatVisible
                if (chatVisible) compactDestination = CompactDestination.Chat
            }
            "workbench.action.openSettings" -> settingsVisible = true
            "workbench.action.openSettingsJson" -> openSettingsJson()
            "workbench.action.openGlobalKeybindings" -> {
                commandFilter = ""
                dialog = WorkbenchDialog.Keybindings
            }
            "workbench.action.openGlobalKeybindingsFile" -> openKeybindingsJson()
            "jupyter.showVariables" -> showVariables()
            "workbench.action.terminal.focusFind" -> showTerminalFind()
            "workbench.action.terminal.openDetectedLink" -> showTerminalLinks()
            "merge-conflict.accept.current", "merge-conflict.accept.incoming", "merge-conflict.accept.both" -> {
                val line = cursorPosition?.line ?: 0
                val conflict = activeConflicts().firstOrNull { line in it.start..it.end }
                val choice = when (commandId) {
                    "merge-conflict.accept.current" -> ConflictChoice.Current
                    "merge-conflict.accept.incoming" -> ConflictChoice.Incoming
                    else -> ConflictChoice.Both
                }
                if (conflict == null) status = "Put the cursor in a conflict first" else resolveConflict(conflict, choice)
            }
            "merge-conflict.accept.all-current" -> resolveAllConflicts(ConflictChoice.Current)
            "merge-conflict.accept.all-incoming" -> resolveAllConflicts(ConflictChoice.Incoming)
            "merge-conflict.next" -> goToConflict(forward = true)
            "merge-conflict.previous" -> goToConflict(forward = false)
            "ports.focus" -> {
                panelVisible = true
                panelTab = PanelTab.Ports
                compactDestination = CompactDestination.Terminal
            }
            "workbench.action.debug.run" -> runActiveFile()
            "editor.action.fontZoomIn" -> zoomEditorOrImage(1)
            "editor.action.fontZoomOut" -> zoomEditorOrImage(-1)
            "workbench.action.connect" -> dialog = WorkbenchDialog.Connect
            "undo" -> surface?.undo()
            "redo" -> surface?.redo()
            "editor.action.clipboardCopyAction" -> copySelection(false)
            "editor.action.clipboardCutAction" -> copySelection(true)
            "editor.action.clipboardPasteAction" -> pasteClipboard()
            "editor.action.selectAll" -> surface?.selectAll()
            "editor.action.indentLines" -> surface?.let { indentOrInsertTab(it, settings.tabSize, settings.insertSpaces) }
            "editor.action.outdentLines" -> surface?.outdentSelection(settings.tabSize)
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
                if (surface != null && editCurrentLine(document, surface, commandId)) onDocumentEdited(document)
            }
            "actions.find", "editor.action.startFindReplaceAction" -> {
                replaceQuery = ""
                dialog = WorkbenchDialog.Find
            }
            "editor.action.nextMatchFindAction" -> findInEditor(true)
            "editor.action.previousMatchFindAction" -> findInEditor(false)
            "workbench.action.gotoLine" -> openQuickOpen(":")
            "editor.action.formatDocument" -> formatActiveDocument()
            "editor.action.goToDefinition", "editor.action.peekDefinition" -> goToDefinition()
            "editor.action.goToReferences" -> findReferences()
            "editor.action.rename" -> beginRename()
            "editor.action.triggerSuggest" -> showCompletion()
            "editor.action.triggerParameterHints" -> activePath?.let { requestSignature(it, trigger = null, invoked = true) }
            "closeParameterHints" -> closeParameterHints()
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
            "github.copilot.chat.signIn" -> signInGitHubInTerminal()
            "github.copilot.chat.signInCli" -> signInChatInTerminal()
            "avf.chat.selectCopilot" -> selectChatAgent(ChatAgentKind.Copilot)
            "avf.chat.selectClaudeCode" -> selectChatAgent(ChatAgentKind.ClaudeCode)
            "avf.chat.selectGemini" -> selectChatAgent(ChatAgentKind.Gemini)
            "avf.chat.signIn" -> signInChatAgent()
            "markdown.showPreview" -> toggleMarkdownPreview(MarkdownPreviewMode.Only)
            "markdown.showPreviewToSide" -> toggleMarkdownPreview(MarkdownPreviewMode.Side)
            "workbench.action.files.openLocalFile" -> pickLocalFile?.invoke()
            "github.signIn" -> signInGitHubInTerminal()
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
                val matched = activeDocument?.let { document -> surface?.let { jumpToMatchingBracket(document, it) } } ?: false
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
