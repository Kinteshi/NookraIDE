package com.avfstudio.nativeide.workbench

/** Miniforge pinned like the tool catalog: a release at least a few weeks old, checksum per architecture. */
private const val MINIFORGE_VERSION = "26.7.2-0"
private const val MINIFORGE_SHA256_AARCH64 = "89b786c8d2c8b0fda7553914c1314ae4ddaa094503802f279377b19ac4463cb2"
private const val MINIFORGE_SHA256_X86_64 = "281b0ac7d550802efc81af633225a5e6116d29ae72f3ab4eae7168c3931a4c05"

/** apt first from the cached lists, then again after refreshing them (a fresh guest has none). */
private fun aptInstall(packages: String) =
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
    Ripgrep(
        "ripgrep",
        listOf("rg"),
        "Search across files",
        "the MaterialCode tool catalog (pinned, checksum-verified)",
        "avf-native-daemon tools install ripgrep",
    ),
    ;

    companion object {
        val allCommands: List<String> get() = entries.flatMap { it.commands }.distinct()
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
