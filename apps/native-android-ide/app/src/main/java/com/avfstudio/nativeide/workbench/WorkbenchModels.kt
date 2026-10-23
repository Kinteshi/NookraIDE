package com.avfstudio.nativeide.workbench

import androidx.compose.runtime.getValue
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
    Outline("Outline"),
    Problems("Problems"),
    References("References"),
}

enum class PanelTab(val title: String) {
    Terminal("Terminal"),
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

enum class FocusArea { Editor, Terminal, Other }

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
    data class CopilotSignInCode(val signIn: CopilotSignIn) : WorkbenchDialog
    data class KernelPicker(
        val path: String,
        val specs: List<KernelSpec>,
        val installable: List<com.avfstudio.nativeide.notebook.InstallableKernel>,
    ) : WorkbenchDialog
}

sealed interface ChatEntry {
    data class User(val text: String) : ChatEntry
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
}
