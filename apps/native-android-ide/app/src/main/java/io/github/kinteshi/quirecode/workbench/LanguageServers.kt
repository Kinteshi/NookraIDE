package io.github.kinteshi.quirecode.workbench

/**
 * Language servers beyond Python and TypeScript (`avf.languageServers.others`): each starts when
 * a file of its language opens, like those two, and is offered for install the first time one
 * opens without it. [id] is the guest tool and the daemon's server id; [extensions] and
 * [fileNames] map to the LSP language id the server is told. A server that works through a
 * [toolchain] (gopls runs `go`) gets that offered too when it runs without it.
 */
enum class OtherLanguageServer(
    val id: String,
    val extensions: Map<String, String>,
    val dependency: GuestDependency,
    val fileNames: Map<String, String> = emptyMap(),
    val toolchain: GuestDependency? = null,
) {
    Go("gopls", mapOf("go" to "go"), GuestDependency.Gopls, mapOf("go.mod" to "go.mod"), GuestDependency.GoToolchain),
    C(
        "clangd",
        mapOf("c" to "c", "h" to "c", "cpp" to "cpp", "cc" to "cpp", "cxx" to "cpp", "hpp" to "cpp", "hh" to "cpp", "hxx" to "cpp"),
        GuestDependency.Clangd,
        toolchain = GuestDependency.CToolchain,
    ),
    Rust("rust-analyzer", mapOf("rs" to "rust"), GuestDependency.RustAnalyzer, toolchain = GuestDependency.RustToolchain),
    Bash("bash-language-server", mapOf("sh" to "shellscript", "bash" to "shellscript"), GuestDependency.BashLanguageServer, mapOf(".bashrc" to "shellscript", ".bash_profile" to "shellscript")),
    Yaml("yaml-language-server", mapOf("yaml" to "yaml", "yml" to "yaml"), GuestDependency.YamlLanguageServer),
    Json("json-language-server", mapOf("json" to "json", "jsonc" to "jsonc", "code-snippets" to "jsonc"), GuestDependency.JsonLanguageServer),
    Html("html-language-server", mapOf("html" to "html", "htm" to "html"), GuestDependency.HtmlLanguageServer),
    Css("css-language-server", mapOf("css" to "css", "scss" to "scss", "less" to "less"), GuestDependency.CssLanguageServer),
    Docker("docker-langserver", mapOf("dockerfile" to "dockerfile"), GuestDependency.DockerLanguageServer, mapOf("dockerfile" to "dockerfile", "containerfile" to "dockerfile")),
    Sql("sql-language-server", mapOf("sql" to "sql"), GuestDependency.SqlLanguageServer),
    Toml("taplo", mapOf("toml" to "toml"), GuestDependency.Taplo, mapOf("cargo.lock" to "toml")),
    Markdown("marksman", mapOf("md" to "markdown", "markdown" to "markdown"), GuestDependency.Marksman),
    ;

    val label: String get() = dependency.label

    companion object {
        /** The server for [path] and the language id it gets, from the file's name, else its extension. */
        fun forPath(path: String): Pair<OtherLanguageServer, String>? {
            val name = path.substringAfterLast('/').lowercase()
            entries.firstNotNullOfOrNull { server -> server.fileNames[name]?.let { server to it } }?.let { return it }
            val extension = name.substringAfterLast('.', "")
            if (extension.isEmpty()) return null
            return entries.firstNotNullOfOrNull { server -> server.extensions[extension]?.let { server to it } }
        }

        fun byId(id: String): OtherLanguageServer? = entries.firstOrNull { it.id == id }

        /** A server whose guest tool the daemon reports missing ("tool 'gopls' is not installed …"). */
        fun missingIn(message: String): OtherLanguageServer? {
            val lower = message.lowercase()
            return entries.firstOrNull { "tool '${it.id}' is not installed" in lower || "${it.id} is not set up" in lower }
        }
    }
}

/** `avf.sql.styleRules`: sqlint's level for all its rules, which are about layout rather than mistakes. */
enum class SqlStyleRules(val key: String, val label: String) {
    Warning("warning", "Warnings"),
    Error("error", "Errors"),
    Off("off", "Off"),
}

/**
 * sqlint's rules (inside sql-language-server), all at [level]. Given rules, sqlint runs only
 * those; `reserved-word-case` keeps its default option, upper case.
 */
fun sqlLintRules(level: SqlStyleRules): Map<String, Any> = listOf(
    "align-column-to-the-first",
    "column-new-line",
    "linebreak-after-clause-keyword",
    "reserved-word-case",
    "space-surrounding-operators",
    "where-clause-new-line",
    "align-where-clause-to-the-first",
    "require-as-to-rename-column",
).associateWith { rule -> if (rule == "reserved-word-case") listOf(level.key, "upper") else level.key }

/**
 * rust-analyzer's `linkedProjects` for the crates of the open Rust files ([manifests], their
 * Cargo.toml paths), or null to leave it to rust-analyzer's own discovery. That discovery looks
 * at [root] and its folders one level down only, so a deeper crate needs linking; and as linking
 * turns discovery off, every open crate is linked then.
 */
fun rustLinkedProjects(root: String, manifests: Collection<String>): List<String>? {
    val base = root.trimEnd('/') + "/"
    val deep = manifests.any { manifest ->
        manifest.startsWith(base) && manifest.removePrefix(base).count { it == '/' } >= 2
    }
    return if (deep) manifests.distinct().sorted() else null
}

/** A server's answer to `initialize` when it was initialized already (a session another client started). */
fun alreadyInitialized(message: String): Boolean {
    val lower = message.lowercase()
    return "unknown request: initialize" in lower || "already initialized" in lower || "initialize request sent twice" in lower
}
