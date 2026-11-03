package com.avfstudio.nativeide.workbench

import com.avfstudio.nativeide.ai.ChatAgentKind

/** js-debug as guest/tools/avf-node-agent.sh pins it. */
private const val JS_DEBUG_VERSION = "1.140.0"

/** Miniforge pinned like the tool catalog: a release at least a few weeks old, checksum per architecture. */
private const val MINIFORGE_VERSION = "26.7.2-0"
private const val MINIFORGE_SHA256_AARCH64 = "89b786c8d2c8b0fda7553914c1314ae4ddaa094503802f279377b19ac4463cb2"
private const val MINIFORGE_SHA256_X86_64 = "281b0ac7d550802efc81af633225a5e6116d29ae72f3ab4eae7168c3931a4c05"

/** apt first from the cached lists, then again after refreshing them (a fresh guest has none). */
internal fun aptInstall(packages: String) =
    "{ sudo apt-get install -y $packages || { sudo apt-get update && sudo apt-get install -y $packages; }; }"

/**
 * A guest tool that an IDE feature needs. The app checks for [commands] (any one will do) and,
 * when none is found, offers [install], which runs in a visible terminal so nothing changes silently.
 */
enum class GuestDependency(
    val label: String,
    val commands: List<String>,
    /** What needs it, shown next to the install offer. */
    val usedFor: String,
    /** How it gets installed, in a few words, for the confirmation. */
    val source: String,
    val install: String,
) {
    Git(
        "Git",
        listOf("git"),
        "Source Control, cloning and GitHub sign-in",
        "Debian packages (apt, needs sudo)",
        aptInstall("git"),
    ),
    GitHubCli(
        "GitHub CLI",
        listOf("gh"),
        "Signing in to GitHub for Copilot chat and Git",
        "the MaterialCode tool catalog (pinned, checksum-verified)",
        "avf-native-daemon tools install gh",
    ),
    Uv(
        "uv",
        listOf("uv"),
        "The Jupyter bridge, ty, ruff and quick Python environments",
        "the MaterialCode tool catalog (pinned, checksum-verified)",
        "avf-native-daemon tools install uv",
    ),
    Conda(
        "Conda",
        listOf("conda", "mamba"),
        "Conda environments for notebooks",
        "conda-forge's Miniforge $MINIFORGE_VERSION installer (checksum-verified; about 800 MB in ~/miniforge3 with ipykernel)",
        "f=Miniforge3-$MINIFORGE_VERSION-Linux-\$(uname -m).sh && " +
            "case \$(uname -m) in aarch64) sum=$MINIFORGE_SHA256_AARCH64;; x86_64) sum=$MINIFORGE_SHA256_X86_64;; *) echo \"No Miniforge build for \$(uname -m)\"; false;; esac && " +
            "curl -fL --proto '=https' -o \"/tmp/\$f\" \"https://github.com/conda-forge/miniforge/releases/download/$MINIFORGE_VERSION/\$f\" && " +
            "echo \"\$sum  /tmp/\$f\" | sha256sum -c && bash \"/tmp/\$f\" -b -p ~/miniforge3 && rm -f \"/tmp/\$f\" && " +
            "~/miniforge3/bin/conda install -y -n base ipykernel",
    ),
    JupyterBridge(
        "Jupyter bridge",
        listOf("avf-jupyter-bridge"),
        "Running notebook kernels",
        "the MaterialCode tool catalog",
        "avf-native-daemon tools install jupyter",
    ),
    TypeScript(
        "TypeScript 7",
        listOf("tsgo"),
        "TypeScript and JavaScript completion, errors and navigation",
        "the MaterialCode tool catalog (pinned, checksum-verified; about 26 MB)",
        "avf-native-daemon tools install typescript",
    ),
    Ripgrep(
        "ripgrep",
        listOf("rg"),
        "Search across files",
        "the MaterialCode tool catalog (pinned, checksum-verified)",
        "avf-native-daemon tools install ripgrep",
    ),
    // The setup writes these commands into ~/.local/bin, so finding them means it finished.
    ClaudeCode(
        "Claude Code",
        listOf("claude-agent-acp"),
        "Claude Code in the Chat view (and `claude` in terminals)",
        "npm: Claude Code's ACP adapter 0.91.0 on Node.js 24.22.0 (checksum-verified), in ~/.local/share/materialcode; about 330 MB, plus 205 MB for Node.js (shared with Gemini)",
        "avf-native-daemon tools install claude-code",
    ),
    Gemini(
        "Gemini CLI",
        listOf("gemini"),
        "Gemini in the Chat view (and `gemini` in terminals)",
        "npm: Gemini CLI 0.64.0 on Node.js 24.22.0 (checksum-verified), in ~/.local/share/materialcode; about 100 MB, plus 205 MB for Node.js (shared with Claude Code)",
        "avf-native-daemon tools install gemini",
    ),
    // Language servers beyond Python and TypeScript, offered when a file of their language opens.
    Gopls(
        "Go language server",
        listOf("gopls"),
        "Go: completion, errors and navigation",
        "Debian packages (apt, needs sudo; gopls 0.16)",
        "avf-native-daemon tools install gopls",
    ),
    Clangd(
        "C/C++ language server",
        listOf("clangd"),
        "C and C++: completion, errors and navigation (reads compile_commands.json)",
        "Debian packages (apt, needs sudo; clangd 19)",
        "avf-native-daemon tools install clangd",
    ),
    RustAnalyzer(
        "Rust language server",
        listOf("rust-analyzer"),
        "Rust: completion, errors and navigation (needs cargo in the guest)",
        "Debian packages (apt, needs sudo)",
        "avf-native-daemon tools install rust-analyzer",
    ),
    BashLanguageServer(
        "Bash language server",
        listOf("bash-language-server"),
        "Shell scripts: completion, hover and navigation",
        "npm: bash-language-server 5.8.1 on MaterialCode's Node.js 24.22.0 (checksum-verified, shared with the chat agents), in ~/.local/share/materialcode",
        "avf-native-daemon tools install bash-language-server",
    ),
    YamlLanguageServer(
        "YAML language server",
        listOf("yaml-language-server"),
        "YAML: completion and checks against JSON schemas",
        "npm: yaml-language-server 2.0.0 on MaterialCode's Node.js 24.22.0 (checksum-verified), in ~/.local/share/materialcode",
        "avf-native-daemon tools install yaml-language-server",
    ),
    JsonLanguageServer(
        "JSON language server",
        listOf("json-language-server"),
        "JSON: completion, checks and formatting (VS Code's own server)",
        "npm: vscode-langservers-extracted 4.10.0 on MaterialCode's Node.js 24.22.0 (checksum-verified), in ~/.local/share/materialcode",
        "avf-native-daemon tools install json-language-server",
    ),
    HtmlLanguageServer(
        "HTML language server",
        listOf("html-language-server"),
        "HTML: completion, hover and formatting (VS Code's own server)",
        "npm: vscode-langservers-extracted 4.10.0 on MaterialCode's Node.js 24.22.0 (checksum-verified), in ~/.local/share/materialcode",
        "avf-native-daemon tools install html-language-server",
    ),
    CssLanguageServer(
        "CSS language server",
        listOf("css-language-server"),
        "CSS, SCSS and Less: completion, hover and checks (VS Code's own server)",
        "npm: vscode-langservers-extracted 4.10.0 on MaterialCode's Node.js 24.22.0 (checksum-verified), in ~/.local/share/materialcode",
        "avf-native-daemon tools install css-language-server",
    ),
    DockerLanguageServer(
        "Dockerfile language server",
        listOf("docker-langserver"),
        "Dockerfiles: completion, hover and checks",
        "npm: dockerfile-language-server-nodejs 0.15.0 on MaterialCode's Node.js 24.22.0 (checksum-verified), in ~/.local/share/materialcode",
        "avf-native-daemon tools install docker-langserver",
    ),
    SqlLanguageServer(
        "SQL language server",
        listOf("sql-language-server"),
        "SQL: completion and checks",
        "npm: sql-language-server 1.7.1 on MaterialCode's Node.js 24.22.0 (checksum-verified), in ~/.local/share/materialcode",
        "avf-native-daemon tools install sql-language-server",
    ),
    Taplo(
        "TOML language server (Taplo)",
        listOf("taplo"),
        "TOML: completion, checks and formatting",
        "the MaterialCode tool catalog (Taplo 0.10.0, pinned, checksum-verified; about 13 MB)",
        "avf-native-daemon tools install taplo",
    ),
    Marksman(
        "Markdown language server (Marksman)",
        listOf("marksman"),
        "Markdown: links, headings, references and rename",
        "the MaterialCode tool catalog (Marksman 2026-02-08, pinned, checksum-verified; about 22 MB)",
        "avf-native-daemon tools install marksman",
    ),
    JsDebug(
        "Node.js debugger (js-debug)",
        listOf("js-debug-adapter"),
        "Debugging JavaScript on Node.js (breakpoints, stepping, variables)",
        "VS Code's js-debug $JS_DEBUG_VERSION (GitHub release, checksum-verified; about 3 MB) on MaterialCode's Node.js 24.22.0, in ~/.local/share/materialcode",
        "avf-native-daemon tools install js-debug",
    ),
    // Toolchains some of those servers work through, offered when one runs without its toolchain.
    GoToolchain(
        "Go toolchain",
        listOf("go"),
        "Go: gopls reads packages with the go command (and building and running Go)",
        "Debian packages (apt, needs sudo; golang-go 1.24 with GCC for cgo, about 530 MB)",
        aptInstall("golang-go"),
    ),
    CToolchain(
        "C/C++ compiler and headers",
        listOf("cc"),
        "C and C++: clangd finds the standard headers (stdio.h, <vector>) through them (and building)",
        "Debian packages (apt, needs sudo; build-essential: GCC, G++, make and the C library headers, about 290 MB)",
        aptInstall("build-essential"),
    ),
    RustToolchain(
        "Rust toolchain (cargo)",
        listOf("cargo"),
        "Rust: rust-analyzer reads the project with cargo (and building and running Rust)",
        "Debian packages (apt, needs sudo; cargo and rustc 1.85 with GCC and LLVM, about 970 MB)",
        aptInstall("cargo"),
    ),
    ;

    companion object {
        val allCommands: List<String> get() = entries.flatMap { it.commands }.distinct()

        /** What a chat agent needs installed; Copilot CLI comes with the daemon's installer. */
        fun forAgent(kind: ChatAgentKind): GuestDependency? = when (kind) {
            ChatAgentKind.Copilot -> null
            ChatAgentKind.ClaudeCode -> ClaudeCode
            ChatAgentKind.Gemini -> Gemini
        }
    }
}

/** What the guest has of each dependency, from the daemon's command probe. */
sealed interface DependencyState {
    /** Not checked yet, or the daemon is too old to answer. */
    data object Unknown : DependencyState
    data class Found(val path: String) : DependencyState
    data object Missing : DependencyState
}

internal fun dependencyStates(paths: Map<String, String?>): Map<GuestDependency, DependencyState> =
    GuestDependency.entries.associateWith { dependency ->
        val found = dependency.commands.firstNotNullOfOrNull { paths[it] }
        when {
            found != null -> DependencyState.Found(found)
            dependency.commands.all { it in paths } -> DependencyState.Missing
            else -> DependencyState.Unknown
        }
    }

/** The command a terminal runs for [dependency]; it closes its shell only when the install worked. */
internal fun installCommand(dependency: GuestDependency): String = "${dependency.install} && exit"

/** Error text from the daemon that means [dependency]'s program could not be started at all. */
internal fun missingDependencyIn(message: String): GuestDependency? {
    val lower = message.lowercase()
    val notFound = "no such file" in lower || "not found" in lower
    return when {
        "tool 'jupyter' is not installed" in lower -> GuestDependency.JupyterBridge
        "tool 'gh' is not installed" in lower -> GuestDependency.GitHubCli
        "tool 'typescript' is not installed" in lower -> GuestDependency.TypeScript
        "tool 'claude-code' is not installed" in lower -> GuestDependency.ClaudeCode
        "tool 'gemini' is not installed" in lower -> GuestDependency.Gemini
        "tool 'js-debug' is not installed" in lower || "js-debug is not set up" in lower -> GuestDependency.JsDebug
        "claude-code is not set up" in lower -> GuestDependency.ClaudeCode
        "gemini is not set up" in lower -> GuestDependency.Gemini
        "could not start ripgrep" in lower && notFound -> GuestDependency.Ripgrep
        "could not start git" in lower && notFound -> GuestDependency.Git
        "unable to find git executable" in lower -> GuestDependency.Git
        "'uv' was not found" in lower -> GuestDependency.Uv
        else -> null
    }
}

/**
 * Creates (or reuses) ~/.venv with ipykernel. Debian's own Python refuses pip (PEP 668), so
 * kernels and tools get a venv; uv makes one without the python3-venv package.
 */
internal fun createVenvCommand(python: String, uv: Boolean): String =
    if (uv) {
        "uv venv --allow-existing --python '$python' ~/.venv && uv pip install --python ~/.venv/bin/python ipykernel"
    } else {
        "{ '$python' -m venv --help >/dev/null 2>&1 && '$python' -m ensurepip --version >/dev/null 2>&1 || " +
            aptInstall("python3-venv") + "; } && '$python' -m venv ~/.venv && ~/.venv/bin/pip install ipykernel"
    }

/**
 * Installs ipykernel into the environment of [python]: with conda when it is one of [conda]'s
 * environments (so conda tracks the package), else pip, falling back to uv for uv-made
 * environments, which have no pip.
 */
internal fun ipykernelInstallCommand(python: String, conda: String?, uv: Boolean): String {
    val prefix = python.substringBeforeLast("/bin/", "")
    val condaRoot = conda?.substringBeforeLast("/bin/", "")?.takeIf { it.isNotEmpty() }
    if (condaRoot != null && prefix.isNotEmpty() && (prefix == condaRoot || prefix.startsWith("$condaRoot/envs/"))) {
        return "'$conda' install -y -p '$prefix' ipykernel"
    }
    return "{ '$python' -m pip install ipykernel" + (if (uv) " || uv pip install --python '$python' ipykernel" else "") + "; }"
}

/** A row in the Python interpreter picker that adds an environment instead of choosing one. */
sealed interface EnvironmentSetup {
    data object CreateVenv : EnvironmentSetup
    data class Install(val dependency: GuestDependency) : EnvironmentSetup
}

/** Where the guest's /tmp lives, from the daemon's stats. */
enum class TmpPlacement(val summary: String) {
    Memory("In memory (a RAM-backed tmpfs, half the guest's memory). The app's own tools already use ~/.cache/tmp; moving /tmp covers every program"),
    DiskAfterRestart("Moves to disk when the guest restarts: close Linux Terminal and open it again"),
    MovedToDisk("On disk (moved by MaterialCode)"),
    Disk("On disk"),
}

internal fun tmpPlacement(inMemory: Boolean?, masked: Boolean?): TmpPlacement? = when {
    inMemory == null -> null
    inMemory && masked == true -> TmpPlacement.DiskAfterRestart
    inMemory -> TmpPlacement.Memory
    masked == true -> TmpPlacement.MovedToDisk
    else -> TmpPlacement.Disk
}

/**
 * The fix for Linux Terminal's port forwarder, from the daemon's stats. The forwarder exits when
 * a process or thread name has a space (Python 3.14 thread names), and the app can't reach the
 * guest until it restarts; the fix (`avf-native-daemon guest fix-port-forwarding`) wraps the tool
 * it reads.
 */
enum class ForwarderFix(val summary: String) {
    Missing("Off. Linux Terminal stops forwarding the app's connection when a program names a thread with a space (Python 3.14 does), until the guest restarts"),
    AfterRestart("Installed; takes effect when the guest restarts: close Linux Terminal and open it again"),
    Active("On (installed by MaterialCode)"),
}

internal fun forwarderFix(installed: Boolean?, active: Boolean?): ForwarderFix? = when (installed) {
    null -> null
    false -> ForwarderFix.Missing
    true -> if (active == true) ForwarderFix.Active else ForwarderFix.AfterRestart
}

/** Runs as root: it diverts `/usr/sbin/tcpstates-libbpf` and installs the daemon's wrapper there. */
internal const val FORWARDER_FIX_COMMAND = "sudo \"\$HOME/.local/bin/avf-native-daemon\" guest fix-port-forwarding"
internal const val FORWARDER_UNDO_COMMAND = "$FORWARDER_FIX_COMMAND --undo"

/** Debian's tmpfs /tmp comes from systemd's tmp.mount; masked, /tmp stays on the root filesystem. */
internal const val TMP_TO_DISK_COMMAND = "sudo systemctl mask tmp.mount"
internal const val TMP_TO_MEMORY_COMMAND = "sudo systemctl unmask tmp.mount"

private val CONDA_ROOT_NAMES = setOf("miniconda3", "miniforge3", "anaconda3", "mambaforge", "micromamba", "miniconda", "anaconda")

/**
 * How a Python language server learns which environment [python] belongs to: ty reads a conda
 * environment from CONDA_PREFIX and a venv from VIRTUAL_ENV (which must hold a pyvenv.cfg).
 * Nothing for the system Python or a bare command name.
 */
internal fun pythonEnvironmentVariables(python: String): List<Pair<String, String>> {
    val prefix = python.substringBeforeLast("/bin/", "")
    if (prefix.isEmpty() || prefix == "/usr" || prefix == "/usr/local") return emptyList()
    val conda = "/envs/" in prefix || prefix.substringAfterLast('/') in CONDA_ROOT_NAMES
    return listOf((if (conda) "CONDA_PREFIX" else "VIRTUAL_ENV") to prefix)
}

/** The short name the status bar shows for an interpreter path: its environment's folder. */
internal fun pythonEnvironmentName(python: String): String {
    val prefix = python.substringBeforeLast("/bin/", "")
    return when {
        python.isBlank() -> "Select Python"
        prefix.isEmpty() -> python
        prefix == "/usr" || prefix == "/usr/local" -> "system"
        else -> prefix.substringAfterLast('/')
    }
}
