package com.avfstudio.nativeide.workbench

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.avfstudio.nativeide.ai.AcpPermissionOption
import com.avfstudio.nativeide.language.CopilotSignIn
import com.avfstudio.nativeide.notebook.KernelClient
import com.avfstudio.nativeide.notebook.KernelSpec
import com.avfstudio.nativeide.notebook.NotebookDocument
import com.avfstudio.nativeide.document.DocumentSession
import org.connectbot.terminal.TerminalEmulator
import java.util.UUID

enum class SidebarView(val title: String) {
    Explorer("Explorer"),
    Search("Search"),
    SourceControl("Source Control"),
    Outline("Outline"),
    Problems("Problems"),
    References("References"),
    CallHierarchy("Call Hierarchy"),
}

enum class PanelTab(val title: String) {
    Terminal("Terminal"),
    Debug("Debug"),
    Problems("Problems"),
    Output("Output"),
    Ports("Ports"),
    Variables("Variables"),
    Interactive("Interactive"),
}

enum class CompactDestination(val title: String) {
    Files("Files"),
    Editor("Editor"),
    Terminal("Terminal"),
    Chat("Chat"),
    More("More"),
}

enum class FocusArea { Editor, Terminal, Notebook, Chat, Other }

/** What a [WorkbenchDialog.PathPrompt] does with the name the user types. */
sealed interface PathAction {
    data class NewFile(val parent: String) : PathAction
    data class NewFolder(val parent: String) : PathAction
    data class Rename(val path: String) : PathAction
    data class SaveAs(val documentPath: String) : PathAction
    /** Explorer's Move to…: the folder (relative to the workspace) for [paths]. */
    data class Move(val paths: List<String>) : PathAction
}

sealed interface WorkbenchDialog {
    data object Connect : WorkbenchDialog
    data object QuickOpen : WorkbenchDialog
    data object GotoLine : WorkbenchDialog
    data object Encoding : WorkbenchDialog
    data object LineEnding : WorkbenchDialog
    /** Files from the share sheet, to save into [folder] of the workspace. */
    data class SharedFiles(
        val uris: List<android.net.Uri>,
        val names: List<String>,
        val folder: String,
        val saving: Boolean = false,
        val error: String? = null,
    ) : WorkbenchDialog
    /** The active file's indentation: spaces or tabs, the size, detect, convert. */
    data object IndentationPicker : WorkbenchDialog
    /** The language of an untitled file. */
    data object UntitledLanguage : WorkbenchDialog
    data object Rename : WorkbenchDialog
    data object SettingsJson : WorkbenchDialog
    data object Keybindings : WorkbenchDialog
    /** A DataFrame or array from the Variables view; [table] is null while it loads, [error] when it failed. */
    data class DataViewer(
        val name: String,
        val table: com.avfstudio.nativeide.notebook.VariableTable? = null,
        val error: String? = null,
        /** What is asked for: the filter, the shown column sorted by (0 is the index), the page's first row. */
        val query: String = "",
        val sort: Int? = null,
        val ascending: Boolean = true,
        val offset: Int = 0,
        val loading: Boolean = true,
    ) : WorkbenchDialog
    /** Keyboard Shortcuts waits for the keys to give [commandId]. */
    data class RecordKeybinding(val commandId: String) : WorkbenchDialog
    data object KeybindingsJson : WorkbenchDialog
    /** The language packs MaterialCode knows, to fetch one by hand. */
    data object LanguagePacks : WorkbenchDialog
    /** Terminal: Rename… for terminal [id]. */
    data class TerminalRename(val id: UUID, val name: String) : WorkbenchDialog
    /** Terminal: Create New Terminal (With Profile)… */
    data object TerminalProfiles : WorkbenchDialog
    /** Snippets: Insert Snippet's picker over the active file's snippets. */
    data class InsertSnippet(val snippets: List<Snippet>) : WorkbenchDialog
    /** Terminal: Open Detected Link…, newest first. */
    data class TerminalLinks(val links: List<TerminalLink>) : WorkbenchDialog
    /** Replace All in Search: [matches] occurrences in [files] files. */
    data class ConfirmReplaceAll(val matches: Int, val files: Int) : WorkbenchDialog
    data class CodeActions(val choices: List<CodeActionChoice>) : WorkbenchDialog
    data class Conflict(val document: DocumentSession) : WorkbenchDialog
    /** Asks for a file or folder name; [error] explains why the last attempt failed. */
    data class PathPrompt(val action: PathAction, val initial: String, val error: String? = null) : WorkbenchDialog
    data class ConfirmDelete(val paths: List<String>, val isDirectory: Boolean) : WorkbenchDialog
    data class GitPrompt(val prompt: com.avfstudio.nativeide.transport.GitEvent.Prompt) : WorkbenchDialog
    /**
     * A diff to read: a file's change ([entry], [staged] side; its hunks can be staged, unstaged
     * or discarded one by one) or a commit's ([entry] null: read only).
     */
    data class GitDiff(
        val title: String,
        val patch: String,
        val entry: com.avfstudio.nativeide.transport.GitStatusEntry? = null,
        val staged: Boolean = false,
    ) : WorkbenchDialog
    /** The stashes (`git stash list`), each with Apply, Pop and Drop. */
    data class GitStashes(val stashes: List<Pair<Int, String>>) : WorkbenchDialog
    /** A file's commits, newest first (VS Code's Timeline); a pick shows that commit's change to it. */
    data class GitFileHistory(val path: String, val commits: List<com.avfstudio.nativeide.transport.GitCommit>) : WorkbenchDialog
    data class GitBranches(val branches: List<com.avfstudio.nativeide.transport.GitBranch>) : WorkbenchDialog
    data class GitConfirmDiscard(val paths: List<String>, val untracked: Int) : WorkbenchDialog
    data object GitClone : WorkbenchDialog
    data object GuestResources : WorkbenchDialog
    /** Closing a tab with unsaved changes, as VS Code asks. */
    data class ConfirmClose(val path: String) : WorkbenchDialog
    /** Python environments found in the guest; [then] runs after one is chosen (e.g. start debugging). */
    data class InterpreterPicker(
        val options: List<Pair<String, String>>,
        val then: (() -> Unit)?,
        /** Ways to add an environment when the guest has none to pick. */
        val setup: List<EnvironmentSetup> = emptyList(),
    ) : WorkbenchDialog
    /** Set up (or re-pair) the Linux guest; [reason] says why it opened, e.g. a rejected token. */
    data class GuestSetup(val reason: String? = null) : WorkbenchDialog
    /** Offers to install a missing guest tool; [then] continues what needed it. */
    data class InstallDependency(val dependency: GuestDependency, val reason: String?, val then: (() -> Unit)?) : WorkbenchDialog
    /**
     * Edit Breakpoint (also Add Conditional Breakpoint and Add Logpoint) on [line] of [path]:
     * its condition, hit count and log message; [exists] offers Remove, [focus] is the field to start in.
     */
    data class EditBreakpoint(
        val path: String,
        val line: Int,
        val options: com.avfstudio.nativeide.debug.BreakpointOptions,
        val exists: Boolean,
        val focus: BreakpointField,
    ) : WorkbenchDialog
    /** Set Value of a variable in the paused frame. */
    data class SetVariable(val variable: com.avfstudio.nativeide.debug.DebugVariable) : WorkbenchDialog
    /** Confirms a guest command that runs in a new terminal; [done] is the status when it exits 0. */
    /** Open Folder: the folder shown, its subfolders once listed, and a listing or create error. */
    data class OpenFolder(val path: String, val folders: List<com.avfstudio.nativeide.transport.FileEntry>? = null, val error: String? = null) : WorkbenchDialog
    data class GuestCommand(
        val title: String,
        val text: String,
        val command: String,
        val done: String,
        /** What follows once the command succeeded (open the shell just installed). */
        val then: (() -> Unit)? = null,
    ) : WorkbenchDialog
    data class CopilotSignInCode(val signIn: CopilotSignIn) : WorkbenchDialog
    data class KernelPicker(
        val path: String,
        val specs: List<KernelSpec>,
        val installable: List<com.avfstudio.nativeide.notebook.InstallableKernel>,
        /** Missing tools that would add environments (conda), offered below the kernels. */
        val setup: List<GuestDependency> = emptyList(),
    ) : WorkbenchDialog
}

/** A zoom step for the image viewer showing [path]; [id] makes each press a new request. */
data class ImageZoomRequest(val path: String, val step: Int, val id: Int)

/** How a Markdown file shows its preview (VS Code's Ctrl+K V and Ctrl+Shift+V). */
enum class MarkdownPreviewMode { Side, Only }

/** The preview resolves relative links and images against this host plus the file's folder. */
const val MARKDOWN_BASE_HOST = "workspace.invalid"
const val MAX_PREVIEW_IMAGE_BYTES = 16 * 1024 * 1024
/** Files read for the peek and the References view's lines. */
const val MAX_LOCATION_TEXT_BYTES = 4 * 1024 * 1024

fun isMarkdownPath(path: String): Boolean =
    path.substringAfterLast('.', "").lowercase() in setOf("md", "markdown")

sealed interface ChatEntry {
    data class User(val text: String, val references: List<com.avfstudio.nativeide.ai.ChatReference> = emptyList()) : ChatEntry
    data class Agent(val text: String) : ChatEntry
    data class Thought(val text: String) : ChatEntry
    data class Tool(val id: String, val title: String, val status: String?) : ChatEntry
    data class Plan(val entries: List<String>) : ChatEntry
    data class Notice(val text: String) : ChatEntry
    /** `copilot login --device-code` is waiting for [code] to be entered at [url]. */
    data class DeviceCode(val url: String, val code: String, val done: Boolean = false) : ChatEntry
    /** A Claude Code or Gemini login in [terminal] waits for the code the page at [url] shows. */
    data class SignInLink(
        val agent: String,
        val url: String,
        val terminal: UUID,
        /** Gemini stays open after signing in and needs `/quit`. */
        val quitAfterCode: Boolean = false,
        val codeSent: Boolean = false,
        val done: Boolean = false,
    ) : ChatEntry
    data class Permission(
        val title: String,
        val options: List<AcpPermissionOption>,
        val respond: (String?) -> Unit,
        val answer: String? = null,
    ) : ChatEntry
}

/** One guest PTY shown in the Terminal panel; the emulator outlives the composable. */
class TerminalSession(val id: UUID, val emulator: TerminalEmulator, title: String) {
    /** The output as plain text, for find and detected links. */
    val textLog = TerminalTextLog()
    /**
     * Until this time (uptime ms) the emulator's automatic replies are dropped: a reattach replays
     * old output, and queries in it (a cursor-position request from gh, say) would otherwise be
     * answered again into the shell as `;1R;120R`.
     */
    @Volatile var replayUntil = 0L
    var title by mutableStateOf(title)
    var exitCode by mutableStateOf<Int?>(null)
    var exited by mutableStateOf(false)
    /** The shell answered the spawn (a failed start reports an error instead). */
    @Volatile var spawned = false
    /** The profile it started with (for a split's twin). */
    var profile: TerminalProfile? = null
    /** Bumped when shell integration reports a command starting or ending. */
    var commandsRevision by mutableStateOf(0)
    /** The last finished command's exit status (shell integration), for the tab's mark. */
    var lastExitCode by mutableStateOf<Int?>(null)
    /** The view's selection and scrolling, once it is shown. */
    var selection: org.connectbot.terminal.SelectionController? = null
    var scroll: TerminalScroll? = null
    /** The command the previous/next command keys went to last (an index into the commands). */
    var commandCursor: Int? = null
}

/** An open `.ipynb`: the parsed notebook, the raw document for saving, and its kernel. */
class NotebookSession(
    val document: DocumentSession,
    val notebook: NotebookDocument,
    val kernel: KernelClient,
) {
    /** Display ids and pending clear_output(wait=True) of the running kernel's outputs. */
    val displays = com.avfstudio.nativeide.notebook.NotebookDisplays()
    /** The kernel's live ipywidgets, drawn where an output names them. */
    val widgets = com.avfstudio.nativeide.notebook.NotebookWidgets()
    /** Cells sent to the kernel and not answered yet, oldest first (the bridge runs them in this order). */
    val executing = ArrayDeque<com.avfstudio.nativeide.notebook.NotebookCell>()
    var kernelName by mutableStateOf<String?>(null)
    var kernelState by mutableStateOf("No kernel")
    var busy by mutableStateOf(false)
    var selectedCell by mutableStateOf(notebook.cells.firstOrNull()?.id)
    /** Set when find selects a cell: the keys stay in the find box instead of moving into the cell. */
    var keepFocusOnSelect = false
    /** Find's matches by cell (offsets in its source) and the current one; [findReveal] asks the cell to show it. */
    var findMatches by mutableStateOf<Map<String, List<IntRange>>>(emptyMap())
    var findCurrentMatch by mutableStateOf<Pair<String, IntRange>?>(null)
    var findReveal by mutableStateOf(0)
    /** Bumped to put the keyboard back into the selected cell (e.g. after a kernel starts). */
    var focusRequest by mutableStateOf(0)
    /** Where the cursor lands in the next focused cell: true = end (arrived from below), false = start. */
    var caretAtEnd: Boolean? = null
    /** With [caretAtEnd], the horizontal position (px) to keep on the target row, as Up/Down do within a cell. */
    var caretGoalX: Float? = null
    /** The column Up/Down aim for (px), kept across short lines and cells; cleared by any other input. */
    var stickyGoalX: Float? = null
    /** Kernel completions shown under the focused cell. */
    internal var cellCompletion by mutableStateOf<CellCompletion?>(null)
    /** Copilot's suggestion for the focused cell, drawn as ghost text there. */
    internal var cellSuggestion by mutableStateOf<CellSuggestion?>(null)
    /** The selected cell's caret, for language features asked from the keyboard (hover, go to definition). */
    var cellCaret = 0
    /** A cell to put the caret in at an offset (go to definition, a problem); the cell takes it once shown. */
    var cellCaretRequest by mutableStateOf<Pair<String, Int>?>(null)
    /** The running kernel's Python (from its kernelspec); the cells' language server resolves imports with it. */
    var kernelPython: String? = null
    /** Problems in code cells from the Python language server, by cell id. */
    val cellProblems = androidx.compose.runtime.mutableStateMapOf<String, List<CellProblem>>()
    /** The hover card in a cell: problems there and the server's hover. */
    var cellHover by mutableStateOf<CellHover?>(null)
    /** The Variables view: the last list from the kernel, whether a look is under way, and why it failed. */
    var variables by mutableStateOf<com.avfstudio.nativeide.notebook.KernelVariables?>(null)
    var variablesLoading by mutableStateOf(false)
    var variablesError by mutableStateOf<String?>(null)
    /** Deleted cells, newest last, for Undo; [lastDeleted] shows the Undo bar until it times out. */
    val deletedCells = ArrayDeque<DeletedCell>()
    var lastDeleted by mutableStateOf<DeletedCell?>(null)
    /** The cell being dragged by its handle, and how far it is from where it lies now (px). */
    var draggedCell by mutableStateOf<String?>(null)
    var dragOffset by mutableStateOf(0f)
}

/** A deleted cell and where it was; [placeholder] is the empty cell added when it was the last one. */
class DeletedCell(val cell: com.avfstudio.nativeide.notebook.NotebookCell, val index: Int, val placeholder: String? = null)

/** One file's matches in the Search view. */
class SearchFileResult(val path: String) {
    val matches = mutableStateListOf<com.avfstudio.nativeide.transport.SearchEvent.Match>()
    var expanded by mutableStateOf(true)
}

/** A workspace's open tabs and Explorer state, saved per folder (Gson fills what the JSON has). */
data class WorkspaceSession(
    val open: List<String>? = null,
    val active: String? = null,
    val directory: String? = null,
    val expanded: List<String>? = null,
    val pinned: List<String>? = null,
    val preview: String? = null,
)

internal fun workspaceSessionKey(workspace: String) = "session:$workspace"

/** The editor's hover card: problems at [position] (worst first) and the language server's hover Markdown. */
data class HoverCard(
    val path: String,
    val position: com.avfstudio.nativeide.editor.LspPosition,
    val problems: List<com.avfstudio.nativeide.language.LanguageDiagnostic>,
    val markdown: String?,
)

/** What a location request asks the language server for ([method] is `textDocument/<method>`). */
enum class LocationKind(val method: String, val title: String) {
    Definition("definition", "Definition"),
    TypeDefinition("typeDefinition", "Type Definition"),
    Implementation("implementation", "Implementations"),
    Declaration("declaration", "Declaration"),
    References("references", "References"),
}

/** The peek: [locations] found from [path] at [position], [selected] shown in its preview. */
data class PeekView(
    val kind: LocationKind,
    val path: String,
    val position: com.avfstudio.nativeide.editor.LspPosition,
    val locations: List<LanguageLocation>,
    val selected: Int,
)

/** The Call Hierarchy view: [root] at [path], its callers ([incoming]) or callees. */
data class CallHierarchyView(val path: String, val root: CallHierarchyNode, val incoming: Boolean)

/** A call hierarchy item (LSP `CallHierarchyItem`) and where the call happens ([fromRanges]). */
data class CallHierarchyNode(val key: String, val item: com.google.gson.JsonObject, val fromRanges: List<com.google.gson.JsonObject>) {
    val name: String get() = item.get("name")?.asString.orEmpty()
    val detail: String get() = item.get("detail")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
    val uri: String get() = item.get("uri")?.asString.orEmpty()
    val kind: Int get() = item.get("kind")?.takeIf { it.isJsonPrimitive }?.asInt ?: 12
    val line: Int get() = item.getAsJsonObject("selectionRange")?.getAsJsonObject("start")?.get("line")?.asInt ?: 0
}

/** The hex view's pages are this long; device files up to [MAX_HEX_DEVICE_BYTES] are read whole. */
const val HEX_PAGE_BYTES = 64 * 1024
const val MAX_HEX_DEVICE_BYTES = 64 * 1024 * 1024

/** A binary file in the hex view: its [size], the pages read so far, and the selected byte. */
class HexFile(val path: String, val size: Long) {
    val pages = androidx.compose.runtime.mutableStateMapOf<Int, ByteArray>()
    var selected by mutableStateOf<Long?>(null)

    fun byteAt(offset: Long): Byte? = pages[(offset / HEX_PAGE_BYTES).toInt()]?.getOrNull((offset % HEX_PAGE_BYTES).toInt())
}

/** A problem in a code cell: [start]..[end] of its source. */
data class CellProblem(val cellId: String, val start: Int, val end: Int, val severity: Int?, val message: String, val origin: String)

/** A hover card in cell [cellId] at [offset] of its source. */
data class CellHover(val cellId: String, val offset: Int, val problems: List<CellProblem>, val markdown: String?)

/** The field Edit Breakpoint starts in. */
enum class BreakpointField { Condition, HitCount, LogMessage }
