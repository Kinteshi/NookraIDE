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
    data object Commands : WorkbenchDialog
    data object QuickOpen : WorkbenchDialog
    data object Find : WorkbenchDialog
    data object GotoLine : WorkbenchDialog
    data object Encoding : WorkbenchDialog
    data object LineEnding : WorkbenchDialog
    data object Rename : WorkbenchDialog
    data object SettingsJson : WorkbenchDialog
    data object Keybindings : WorkbenchDialog
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
    /** Python environments found in the guest; [then] runs after one is chosen (e.g. start debugging). */
    data class InterpreterPicker(val options: List<Pair<String, String>>, val then: (() -> Unit)?) : WorkbenchDialog
    /** Set up (or re-pair) the Linux guest; [reason] says why it opened, e.g. a rejected token. */
    data class GuestSetup(val reason: String? = null) : WorkbenchDialog
    data class CopilotSignInCode(val signIn: CopilotSignIn) : WorkbenchDialog
    data class KernelPicker(
        val path: String,
        val specs: List<KernelSpec>,
        val installable: List<com.avfstudio.nativeide.notebook.InstallableKernel>,
    ) : WorkbenchDialog
}

sealed interface ChatEntry {
    data class User(val text: String, val references: List<com.avfstudio.nativeide.ai.ChatReference> = emptyList()) : ChatEntry
    data class Agent(val text: String) : ChatEntry
    data class Thought(val text: String) : ChatEntry
    data class Tool(val id: String, val title: String, val status: String?) : ChatEntry
    data class Plan(val entries: List<String>) : ChatEntry
    data class Notice(val text: String) : ChatEntry
    /** `copilot login --device-code` is waiting for [code] to be entered at [url]. */
    data class DeviceCode(val url: String, val code: String, val done: Boolean = false) : ChatEntry
    data class Permission(
        val title: String,
        val options: List<AcpPermissionOption>,
        val respond: (String?) -> Unit,
        val answer: String? = null,
    ) : ChatEntry
}

/** One guest PTY shown in the Terminal panel; the emulator outlives the composable. */
class TerminalSession(val id: UUID, val emulator: TerminalEmulator, title: String) {
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
    var kernelName by mutableStateOf<String?>(null)
    var kernelState by mutableStateOf("No kernel")
    var busy by mutableStateOf(false)
    var selectedCell by mutableStateOf(notebook.cells.firstOrNull()?.id)
    /** Bumped to put the keyboard back into the selected cell (e.g. after a kernel starts). */
    var focusRequest by mutableStateOf(0)
    /** Where the cursor lands in the next focused cell: true = end (arrived from below), false = start. */
    var caretAtEnd: Boolean? = null
}

/** One file's matches in the Search view. */
class SearchFileResult(val path: String) {
    val matches = mutableStateListOf<com.avfstudio.nativeide.transport.SearchEvent.Match>()
    var expanded by mutableStateOf(true)
}
