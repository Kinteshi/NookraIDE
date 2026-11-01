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
}

enum class PanelTab(val title: String) {
    Terminal("Terminal"),
    Debug("Debug"),
    Problems("Problems"),
    Output("Output"),
    Ports("Ports"),
    Variables("Variables"),
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
}

sealed interface WorkbenchDialog {
    data object Connect : WorkbenchDialog
    data object QuickOpen : WorkbenchDialog
    data object Find : WorkbenchDialog
    data object GotoLine : WorkbenchDialog
    data object Encoding : WorkbenchDialog
    data object LineEnding : WorkbenchDialog
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
    ) : WorkbenchDialog
    /** Keyboard Shortcuts waits for the keys to give [commandId]. */
    data class RecordKeybinding(val commandId: String) : WorkbenchDialog
    data object KeybindingsJson : WorkbenchDialog
    /** The language packs MaterialCode knows, to fetch one by hand. */
    data object LanguagePacks : WorkbenchDialog
    /** Terminal: Open Detected Link…, newest first. */
    data class TerminalLinks(val links: List<TerminalLink>) : WorkbenchDialog
    /** Replace All in Search: [matches] occurrences in [files] files. */
    data class ConfirmReplaceAll(val matches: Int, val files: Int) : WorkbenchDialog
    data class Completion(val choices: List<CompletionChoice>) : WorkbenchDialog
    data class CodeActions(val choices: List<CodeActionChoice>) : WorkbenchDialog
    data class Hover(val text: String) : WorkbenchDialog
    data class Conflict(val document: DocumentSession) : WorkbenchDialog
    /** Asks for a file or folder name; [error] explains why the last attempt failed. */
    data class PathPrompt(val action: PathAction, val initial: String, val error: String? = null) : WorkbenchDialog
    data class ConfirmDelete(val path: String, val isDirectory: Boolean) : WorkbenchDialog
    data class GitPrompt(val prompt: com.avfstudio.nativeide.transport.GitEvent.Prompt) : WorkbenchDialog
    data class GitDiff(val title: String, val patch: String) : WorkbenchDialog
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
    /** Confirms a guest command that runs in a new terminal; [done] is the status when it exits 0. */
    /** Open Folder: the folder shown, its subfolders once listed, and a listing or create error. */
    data class OpenFolder(val path: String, val folders: List<com.avfstudio.nativeide.transport.FileEntry>? = null, val error: String? = null) : WorkbenchDialog
    data class GuestCommand(val title: String, val text: String, val command: String, val done: String) : WorkbenchDialog
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
}

/** An open `.ipynb`: the parsed notebook, the raw document for saving, and its kernel. */
class NotebookSession(
    val document: DocumentSession,
    val notebook: NotebookDocument,
    val kernel: KernelClient,
) {
    /** Display ids and pending clear_output(wait=True) of the running kernel's outputs. */
    val displays = com.avfstudio.nativeide.notebook.NotebookDisplays()
    /** Cells sent to the kernel and not answered yet, oldest first (the bridge runs them in this order). */
    val executing = ArrayDeque<com.avfstudio.nativeide.notebook.NotebookCell>()
    var kernelName by mutableStateOf<String?>(null)
    var kernelState by mutableStateOf("No kernel")
    var busy by mutableStateOf(false)
    var selectedCell by mutableStateOf(notebook.cells.firstOrNull()?.id)
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
    /** The Variables view: the last list from the kernel, whether a look is under way, and why it failed. */
    var variables by mutableStateOf<com.avfstudio.nativeide.notebook.KernelVariables?>(null)
    var variablesLoading by mutableStateOf(false)
    var variablesError by mutableStateOf<String?>(null)
}

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
