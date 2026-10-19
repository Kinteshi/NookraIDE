# AVF Studio — Track B: Native Android IDE Specification

> **Status:** v1.0 draft. Companion to [SPEC.md](../SPEC.md) (Track A, VS Code Host).
> **Audience:** implementing agents and reviewers.
> **One-line summary:** a Kotlin/Jetpack Compose IDE that looks and behaves like VS Code, renders natively on Android, and runs every heavy service (language servers, debuggers, terminals, Git, search, Jupyter kernels, AI agents) inside the AVF Debian guest through the same `avf-daemon` used by Track A.

---

## 0. Relationship to Track A and expectation setting

### 0.1 Shared with Track A (do not duplicate)

* The Android shell: onboarding, pairing, VM lifecycle, Settings, Diagnostics, Remote Host profiles, secrets (SPEC.md Section 6.2 to 6.4, 6.11).
* The guest Supervisor and its HTTP surface, pairing, sessions, file transfer, process supervision, port detector (SPEC.md Section 5).
* The native terminal view (`:terminal`, SPEC.md Phase 4).
* AI provider profiles and the secrets flow (SPEC.md Section 8.2, 8.6).

Track B adds the `:editor` module and enables the `native-protocol` feature of `avf-daemon`. A user chooses the editor surface in Settings → Editor: *VS Code (web)* or *Native*. Both can be installed; both see the same files and terminals.

### 0.2 What Track B can and cannot be (read before planning)

* **It cannot run VS Code extensions.** The VS Code extension host is a Node process bound to VS Code's internal protocol. Track B provides substitutes in three tiers (Section 7): *asset packs* (grammars, themes, snippets, icon themes in VS Code's own formats), *tool packs* (language servers, debug adapters, formatters, linters installed in the guest and described by manifests), and a future plugin API that is explicitly out of scope for v1.
* **It mimics VS Code's model, not its pixels.** Layout, commands, keybindings, settings keys, theme JSON, `launch.json`, `tasks.json`, `.vscode/settings.json`, and the Command Palette vocabulary follow VS Code so muscle memory and project configuration carry over. Rendering uses Compose with VS Code's visual language (Activity Bar, Side Bar, editor groups, Panel, Status Bar) and Material 3 only for system dialogs, sheets, and pickers.
* **It is a multi-year effort if built from scratch.** This spec therefore builds the editor on an existing native code editor engine (Section 4) and spends the project's effort on the workbench, services, and mobile ergonomics where a native app actually beats a WebView: input latency, touch selection, IME handling, 120 Hz scrolling, memory, and startup time.

### 0.3 Why do it at all

Track A is a WebView around a desktop UI. Track B is the product that feels designed for the device: instant start, native text selection handles, perfect soft-keyboard behavior, half the memory, system share sheets and drag-and-drop everywhere, and a UI that can be reshaped for a phone without CSS hacks. It also hedges the project against VS Code web changing in ways the Companion cannot patch.

---

## 1. Goals and Non-goals

### 1.1 Goals (v1)

1. Edit code with VS Code-grade language intelligence (completion, hover, diagnostics, go to definition, references, rename, code actions, formatting, inlay hints, semantic tokens, signature help, folding) for every language that has an LSP server installed in the guest.
2. Integrated terminal, Git, workspace search and replace, debugging via DAP, tasks, problems, and ports.
3. Jupyter notebooks with rich outputs.
4. AI: inline completions from any provider, a built-in chat agent with tool use and diff review, a native client for terminal agents through the Agent Client Protocol, and GitHub Copilot through its public language server.
5. VS Code theme and grammar compatibility (import any VS Code color theme, icon theme, and TextMate grammar).
6. A Compact mode that is genuinely good on a phone and an Expanded mode that is a credible laptop IDE on DeX.
7. Budgets: cold start to editable file under 1.5 s, app RSS under 250 MB with five files and two terminals open, 120 Hz scrolling, keystroke to glyph under 16 ms p95.

### 1.2 Non-goals (v1)

* VS Code extension host compatibility. Web extensions, Node extensions, and webview-based extension UIs are out.
* A plugin API for third parties. Design for it, do not build it.
* Running the IDE's services on the Android side. Everything that is not UI runs in the guest (or a Remote Host).
* Collaborative editing, Live Share.
* Pixel-identical cloning of VS Code's CSS.

---

## 2. Architecture

```
+-----------------------------------------------------------------------------+
| ANDROID APP (Kotlin / Compose)                                              |
|  :app  :core  :shell  :terminal  :vscodehost (Track A)                      |
|  :editor (Track B)                                                          |
|   ├─ workbench/      layout, activity bar, side bar, editor groups, panel,  |
|   │                   status bar, command palette, quick open, keybindings  |
|   ├─ texteditor/     editor engine wrapper, document model, decorations     |
|   ├─ language/       LSP client (LSP4J), semantic tokens, TextMate, themes  |
|   ├─ scm/            Git views, diff viewer, merge editor                   |
|   ├─ debug/          DAP client (LSP4J debug), breakpoints, variables       |
|   ├─ search/         ripgrep results, replace preview                       |
|   ├─ notebook/       cell model, Jupyter client, output renderers           |
|   ├─ ai/             completion, chat agent, ACP client, Copilot LS         |
|   ├─ settings/       settings model, JSON + UI, keybindings editor          |
|   └─ transport/      control channel client (MessagePack over WebSocket)    |
+-----------------------------------|-----------------------------------------+
                                    |  ws://127.0.0.1:19860/ws  (session cookie)
                                    |  http://127.0.0.1:19860/{files,jupyter,proxy}
+-----------------------------------|-----------------------------------------+
| GUEST: avf-daemon with feature "native-protocol"                            |
|   Vfs  (list/read/write/create/delete/rename/copy/stat/watch, chunked IO)   |
|   Rpc  (stdio JSON-RPC bridges: LSP servers, DAP adapters, ACP agents,      |
|         Copilot language server)                                            |
|   Search (ripgrep streaming), Git (git CLI JSON), Pty, Ports, Process       |
|   Tools registry: ~/.local/share/avf-studio/tools/<name>/manifest.json      |
|   Jupyter server (optional), CLI agents, uv/node toolchains                 |
+-----------------------------------------------------------------------------+
```

**Principle: the app is a renderer and an orchestrator.** It never parses a language, runs a compiler, or indexes a repository. The guest does. The app owns documents (text buffers with undo), the UI, and client-side state machines for LSP, DAP, Git, and notebooks.

---

## 3. Guest Protocol v2 (feature `native-protocol`)

Extends SPEC.md Section 5.5 and the v1 encoding rules (MessagePack, struct maps, string UUIDs, `serde_bytes`, external enum tagging, 32 MiB frames, `PROTOCOL_VERSION` bump on breaking change, round-trip tests for every variant). Authentication is the session cookie on the upgrade.

### 3.1 VFS (complete)

```rust
pub enum VfsMessage {
    // requests (client → daemon), every request gets exactly one *Res or VfsError
    ListDirReq   { req_id: u64, path: String },
    StatReq      { req_id: u64, path: String },
    ReadFileReq  { req_id: u64, path: String, offset: u64, len: u32 },   // chunked, len ≤ 4 MiB
    WriteFileReq { req_id: u64, path: String, content: Vec<u8>, expected_modified_ts: Option<u64>,
                   truncate: bool, offset: u64 },                           // chunked writes to a temp file, commit on Flush
    FlushReq     { req_id: u64, path: String },                            // atomic rename of the temp file
    CreateReq    { req_id: u64, path: String, kind: EntryKind },           // File | Dir (mkdir -p)
    DeleteReq    { req_id: u64, path: String, recursive: bool, to_trash: bool },
    RenameReq    { req_id: u64, from: String, to: String, overwrite: bool },
    CopyReq      { req_id: u64, from: String, to: String, overwrite: bool },
    WatchReq     { req_id: u64, path: String, recursive: bool },
    UnwatchReq   { req_id: u64, path: String },
    RootsReq     { req_id: u64 },                                          // configured workspace roots
    // responses
    ListDirRes { req_id: u64, entries: Vec<FileEntry> },
    StatRes    { req_id: u64, entry: FileEntry },
    ReadFileRes{ req_id: u64, content: Vec<u8>, eof: bool, modified_ts: u64, size: u64 },
    Ack        { req_id: u64, modified_ts: Option<u64> },
    RootsRes   { req_id: u64, roots: Vec<String> },
    VfsError   { req_id: u64, code: VfsErrorCode, message: String },
    FsEvent    { kind: FsEventKind, paths: Vec<String> },
}
```

Rules: v1 confinement, symlink policy, atomic writes, conflict detection, watcher batching and `Rescan` are unchanged. `to_trash` moves into `~/.local/share/avf-studio/trash/<timestamp>/` and the app offers undo for 10 s. `FileEntry` gains `mode: u32`, `is_hidden: bool`, `git_status: Option<GitFileStatus>` when the directory is inside a repository (computed from a cached `git status --porcelain=v2` refreshed on `FsEvent`). Files above 16 MiB open read-only with chunked paging in the editor; above 256 MiB the editor refuses and offers the DuckDB or hex preview.

### 3.2 Stdio JSON-RPC bridges

```rust
pub enum MultiplexedPacket { /* ... */ Rpc(RpcMessage) }

pub enum RpcKind { Lsp, Dap, Acp, Custom }

pub enum RpcMessage {
    Start   { bridge_id: Uuid, tool: String, kind: RpcKind, args: Vec<String>, cwd: Option<String>, env: Vec<(String, String)> },
    Started { bridge_id: Uuid, pid: u32 },
    Message { bridge_id: Uuid, body: String },          // raw JSON-RPC, no Content-Length header
    Stop    { bridge_id: Uuid },
    Exited  { bridge_id: Uuid, code: Option<i32> },
    Error   { bridge_id: Uuid, message: String },
}
```

`tool` must name a tool from the guest Tools registry (Section 7.2); the client never sends an executable path. `env` entries are allowlisted by the tool manifest (for API keys injected per launch). `Lsp` and `Dap` use `Content-Length` framing on stdio; `Acp` uses newline-delimited JSON. v1 hibernation (keep LSP servers alive 30 minutes after disconnect, replay bounded history) applies to `Lsp` only.

**N1 implementation status:** the initial `native-protocol` slice implements v2 `Rpc` packet shapes and LSP-kind start/message/stop using the daemon's existing static language-server allowlist. It returns the process ID, forwards raw JSON-RPC, preserves LSP hibernation on disconnect, and stops the process when its last bridge explicitly stops. DAP, ACP, custom bridges, tool manifests/catalog/install, and per-launch args/cwd/env allowlisting remain unimplemented; requests for unsupported kinds or launch options return an explicit `RpcMessage::Error`. This is progress, not completion of N1 or Section 3.

### 3.3 Search

```rust
pub enum SearchMessage {
    StartReq { req_id: u64, roots: Vec<String>, query: String, regex: bool, case_sensitive: bool, whole_word: bool,
               include: Vec<String>, exclude: Vec<String>, max_results: u32, context_lines: u8 },
    Cancel   { req_id: u64 },
    Match    { req_id: u64, path: String, line: u32, col: u32, len: u32, preview: String, context_before: Vec<String>, context_after: Vec<String> },
    Done     { req_id: u64, total: u32, truncated: bool, elapsed_ms: u32 },
    Error    { req_id: u64, message: String },
}
```

Backed by `rg --json`. Respects `.gitignore` plus the exclude list from settings (`search.exclude`). Replace is performed client-side through chunked VFS writes with a preview diff; multi-file replace is one undoable operation in the app's undo journal.

### 3.4 Git

Backed by the `git` CLI (never `git2`) with `--porcelain` formats parsed in the daemon into typed JSON. Credentials: SSH keys and credential helpers in the guest; interactive prompts (passphrase, username/password, 2FA) are surfaced as `GitMessage::Prompt` and answered by the app with a masked field; answers are never logged.

```rust
pub enum GitMessage {
    Req  { req_id: u64, repo: String, op: GitOp },
    Res  { req_id: u64, result: GitResult },          // typed per op
    Progress { req_id: u64, phase: String, percent: Option<u8>, detail: String },
    Prompt   { req_id: u64, prompt_id: u64, text: String, secret: bool },
    PromptAnswer { req_id: u64, prompt_id: u64, answer: String },
    Error    { req_id: u64, message: String },
}
pub enum GitOp {
    Status, DiffFile { path: String, staged: bool }, Stage(Vec<String>), Unstage(Vec<String>), Discard(Vec<String>),
    Commit { message: String, amend: bool, signoff: bool }, Branches, Checkout { name: String, create: bool },
    Log { rev: Option<String>, limit: u32, path: Option<String> }, Show { rev: String, path: Option<String> },
    Fetch, Pull { rebase: bool }, Push { set_upstream: bool, force_with_lease: bool },
    Stash { op: StashOp }, Blame { path: String }, Remotes, Init, Clone { url: String, dest: String },
    MergeStatus, Resolve { path: String, content: Vec<u8> },
}
```

### 3.5 Jupyter

No new channel. The app speaks the Jupyter Server REST and WebSocket API through `/jupyter/**` on the Supervisor (sessions, kernels, kernelspecs, contents are not used; files go through VFS). The daemon starts the server on demand (`Process` channel).

### 3.6 Required daemon tests

Round-trips for every new variant; confinement tests for every VFS mutation including rename and copy across roots (rejected); chunked read/write correctness with partial frames; trash and restore; search cancellation and truncation; Git prompt flow with a fake askpass; bridge allowlist enforcement and env allowlist; `Acp` newline framing with partial lines.

---

## 4. Editor Engine

### 4.1 Decision

**Evaluate `sora-editor`** (`io.github.Rosemoe.sora-editor`, published 0.23.6 artifacts declare LGPL-2.1: keep it isolated until redistribution is approved): a mature native Android code editor view with TextMate grammar support, tree-sitter support, an LSP client module, and proven performance on large files. Wrap it in Compose with `AndroidView`. Rationale: it provides the hardest 60 % (text layout, selection handles, IME integration, incremental highlighting, large-file rendering) that would otherwise take a year. Verify the pinned version's feature set in Phase N0 **[VERIFY]**.

**Rejected:** a from-scratch Compose text editor (`BasicTextField` cannot handle code-sized documents with decorations at 120 Hz); embedding Monaco in a WebView (that is Track A); a Rust editor core through UniFFI (adds NDK and FFI complexity without a rendering solution).

**Exit criteria for replacing the engine later:** if `sora-editor` cannot meet the budgets in Section 1.1 or the feature list below after Phase N3, a Compose-native engine becomes a separate spec. Design the `TextEditorHost` interface (Section 4.3) so the engine is swappable.

### 4.2 Required editor features (VS Code parity list)

Selection and editing: multi-cursor (Ctrl+Alt+Up/Down, Ctrl+D, Alt+Click), column selection, word and line operations, move/copy line, join lines, toggle comment (line and block from language configuration), auto-closing pairs and surrounding, auto-indent from language configuration and `onEnterRules`, snippets with tab stops and placeholders (VS Code snippet syntax), format on save and on paste, trim trailing whitespace, final newline, indentation detection, EOL handling, encoding detection (UTF-8, UTF-16, Latin-1) with status bar switch.

Navigation: go to line, go to symbol in file and workspace, go to definition/declaration/type definition/implementation, peek definition, find all references, breadcrumbs, outline, bracket matching and colorization, sticky scroll, folding (indent, language, region markers), minimap (Expanded only, off by default on phones).

Find: in-file find and replace with regex, case, whole word, selection scope, preserve case; find widget keyboard parity with VS Code.

Language intelligence (via LSP): completion with kinds, icons, documentation, snippet insertion, commit characters, auto-import edits; signature help; hover with Markdown rendering; diagnostics squiggles with severity colors and gutter markers; code actions and quick fixes with lightbulb; rename with preview; document and range formatting; inlay hints; semantic tokens; document highlights; code lens; call and type hierarchy (Expanded only); linked editing; workspace symbols.

Views: split editor groups (horizontal and vertical, up to 2 × 2 on Expanded, single group on Compact with a tab strip), tabs with dirty indicators and pinning, preview tabs, diff editor (side-by-side and inline, with navigation and stage-hunk in SCM diffs), merge editor (3-way) for conflicts, image viewer (zoom, pan), Markdown preview (CommonMark + GFM tables, Mermaid optional), hex view for binaries, read-only large-file paging mode.

Touch specifics: native selection handles and magnifier, long-press context menu with Cut/Copy/Paste/Select All/Go to Definition/Rename/Format/Quick Fix, double-tap selects word, triple-tap selects line, two-finger scroll, pinch to change font size (persisted), edge glow on overscroll, the Touch Modifier Bar from SPEC.md Section 6.7 rendered in Compose (no key event injection needed; the bar calls editor actions directly).

### 4.3 Document model

* `Document`: identity (URI in guest), text buffer owned by the engine, version counter, EOL, encoding, language id, dirty flag, undo/redo stack with grouping, save state (`expected_modified_ts` for optimistic writes), external-change handling (prompt to reload or keep, with diff).
* `TextEditorHost` interface: open/close, apply `TextEdit[]` (LSP semantics, UTF-16 positions), decorations API (ranges with styles, gutter icons, inline hints, ghost text), viewport queries, cursor/selection API, event stream (content change with incremental deltas, selection change, viewport change, focus).
* Incremental LSP sync (`textDocument/didChange` with range edits), 150 ms debounce for diagnostics requests that are pull-based, immediate for push.
* Autosave modes: off, after delay, on focus change, on window change (VS Code keys `files.autoSave`, `files.autoSaveDelay`).

---

## 5. Workbench

### 5.1 Layout (Expanded)

Activity Bar (left, 48 dp), Side Bar (resizable, 180 to 50 % width, collapsible with Ctrl+B), Editor area with groups and tabs, Panel (bottom by default, movable to right, maximizable, Ctrl+J), Status Bar (22 dp). Secondary Side Bar (right) for AI chat and Outline (Ctrl+Alt+B). All sizes persist per window size class. Drag-and-drop: tabs between groups, files from Explorer into the editor and terminal (path insertion), text between editors, files from other apps (Android drag-and-drop in multi-window) into Explorer as uploads.

### 5.2 Layout (Compact)

Bottom navigation (Files, Editor, Terminal, Source Control, More) as in SPEC.md Section 6.8, but implemented natively: each destination is a full-screen Compose surface; the editor keeps a compact tab strip; the Panel views (Problems, Output, Debug Console, Ports) are a swipeable pager inside Terminal's destination; Search, Debug, Notebooks, AI, Tools live in *More*. Side Bar views open as modal bottom sheets at 60 % height with drag to full. Command Palette and Quick Open are full-screen sheets with the keyboard pre-opened. Back navigation follows Android conventions (predictive back between destinations and sheets). Status Bar content collapses into a single chip row above the bottom navigation.

### 5.3 Command system

* Every user action is a `Command { id, title, category, keybinding?, when? }` with VS Code ids where an equivalent exists (`workbench.action.files.save`, `editor.action.formatDocument`, `workbench.action.terminal.new`, ...). Unknown VS Code ids typed into the palette show "not available in Native mode".
* Command Palette (Ctrl+Shift+P) with fuzzy match, recently used, and keybinding display; Quick Open (Ctrl+P) with `@` symbols, `#` workspace symbols, `:` line, `>` commands, `?` help, like VS Code.
* `when` clause contexts: `editorTextFocus`, `terminalFocus`, `editorHasSelection`, `editorLangId`, `isCompact`, `inDebugMode`, `notebookEditorFocused`, `scmProviderCount`, and others added as needed.

### 5.4 Keybindings

* Default keymap = VS Code Linux defaults. User overrides in `keybindings.json` using VS Code's format (`key`, `command`, `when`, `args`), stored in the guest under `~/.config/avf-studio/User/` and mirrored locally for offline start. Chords supported (`Ctrl+K Ctrl+S`).
* Hardware key interception table from SPEC.md Section 6.6 applies; in Native mode the app owns all key events so `Ctrl+Space`, `Escape`, and layout-switch combos are handled deterministically.
* Keybindings editor UI (searchable table, record keys, conflict display).

### 5.5 Settings

* Three scopes: default, user (`~/.config/avf-studio/User/settings.json`), workspace (`.vscode/settings.json`, read and written compatibly; unknown keys preserved). JSON editor with schema validation and a Settings UI with search, like VS Code. VS Code keys are honored wherever the feature exists (`editor.*`, `files.*`, `search.*`, `terminal.integrated.*`, `workbench.colorTheme`, `git.*` subset, `python.defaultInterpreterPath`, `[language]` overrides). LSP servers receive settings through `workspace/configuration` with the same section names VS Code extensions use (for example `python.analysis`, `rust-analyzer`), so existing project configs work.
* Sync: settings, keybindings, snippets, and asset packs are files in the guest; the Remote Host profile shares them across devices by pointing at the same host. No cloud sync in v1.

### 5.6 Themes and icons

* Import any VS Code color theme JSON (`colors` and `tokenColors`, including `include` chains and semantic token colors) and icon theme JSON with its font or SVG assets. Map `colors` keys to Compose workbench tokens; unknown keys fall back to VS Code's documented defaults.
* Ship Dark Modern, Light Modern, Dark+ (compat), Light+ (compat), High Contrast Dark, and one Material You theme that derives from the system palette. Follow system dark mode with preferred light/dark theme settings.
* Bundle JetBrains Mono and Cascadia Code (OFL); allow user fonts from guest paths.

### 5.7 Views (Side Bar and Panel)

* **Explorer:** virtualized tree, inline rename and create, multi-select, drag-and-drop move, compact folders, Git decorations (colors and badges), problems decorations, `files.exclude`, open editors section, outline section, timeline (Git history of file).
* **Search:** query with include/exclude, live streaming results grouped by file, replace with preview and per-match accept, search editor (results in a document) on Expanded.
* **Source Control:** changes, staged changes, merge changes, commit message box with `#` and co-author completions, inline diff open on tap, stage and discard per file and per hunk, branch picker in Status Bar, sync action, stash, commit graph (log with lanes), blame in gutter (toggle), timeline.
* **Run and Debug:** `launch.json` compatible configurations (subset: `type`, `request`, `program`, `args`, `cwd`, `env`, `console`, `stopOnEntry`, `justMyCode`, `module`, `port`, `host`), breakpoints (line, conditional, hit count, logpoints, function), call stack, variables with expansion and set value, watch, debug console with evaluation, debug toolbar, inline values, exception breakpoints. Adapters: `debugpy`, `codelldb`, `vscode-js-debug` (`js-debug` DAP server), `delve`, installed as tool packs.
* **Tools** (replaces "Extensions"): browse installed and available tool packs and asset packs, install and update from the guest registry, show server status and logs, restart a server.
* **AI:** chat and agents (Section 8).
* **Panel:** Terminal (tabs, split, rename, kill, scrollback search, link detection for paths and URLs, shell integration marks), Problems (filter, group, navigate), Output (per-channel logs from servers and the app), Debug Console, Ports (from the port detector with open-in-preview via `/proxy/{port}/` in a WebView tab or Custom Tab), Tasks output.

### 5.8 Tasks

`tasks.json` subset: `shell` and `process` types, `command`, `args`, `cwd`, `env`, `problemMatcher` with the built-in `$tsc`, `$eslint-stylish`, `$rustc`, `$python`, `$gcc` matchers, `group` build/test, `isBackground` with begin/end patterns, `dependsOn`. Runs in a terminal tab. Ctrl+Shift+B runs the default build task.

### 5.9 Notebooks

* Cell model compatible with `nbformat` 4 (code, Markdown, raw; outputs; metadata; execution counts). Files are read and written through VFS with stable key ordering to minimize Git diffs.
* Kernel management through the Jupyter Server API behind the Supervisor; kernel picker lists kernelspecs from the local guest and from Remote Host profiles.
* Cell editor = the same `TextEditorHost` with LSP for Python through a notebook-aware document concatenation strategy (as Jupyter extensions do) or `basedpyright`'s notebook support where available.
* Output renderers, native where possible: `text/plain` with ANSI colors, `text/markdown`, `image/png`, `image/jpeg`, `image/svg+xml`, `text/html` (sanitized subset natively; full HTML and `application/vnd.plotly.v1+json`, `application/vnd.jupyter.widget-view+json` in a single shared WebView per notebook with a renderer bundle served from the app's assets), `application/json` (tree view), `text/latex` (KaTeX in the shared WebView), error tracebacks with ANSI and clickable frames.
* Touch UX: cell toolbar on tap, drag handles to reorder, swipe actions (run, delete with undo), run-all with progress, variable explorer (via `%whos`-style kernel introspection with a pandas/polars DataFrame viewer that pages rows and sorts and filters natively), plot viewer with pinch zoom and export.
* Interactive window: `# %%` cells in `.py` files run in a kernel with outputs in a side panel.

---

## 6. Language Support Model

* **Language registry:** VS Code language configuration files (`language-configuration.json`: comments, brackets, autoClosingPairs, surroundingPairs, folding, wordPattern, onEnterRules, indentationRules) and TextMate grammars are loaded from asset packs; tree-sitter grammars may be used by the engine for folding and highlighting when available.
* **LSP client:** Eclipse LSP4J (JSON-RPC and protocol types) over the `Rpc` bridge. Multiple servers per language (for example Ruff + basedpyright), workspace folders, `initializationOptions` and settings from the tool manifest and user settings, request cancellation on edit, progress reporting in the Status Bar, `window/showMessageRequest` as dialogs, `workspace/applyEdit` through the document model with undo grouping, file operations notifications (`willRename`, `didRename`) from Explorer actions, semantic tokens full and delta, pull diagnostics where supported.
* **Formatters and linters** that are not LSP servers (for example `black`, `prettier`) are tool packs with a `format` command contract: stdin to stdout. The app runs them through the `Rpc` bridge with `RpcKind::Custom` using a one-shot protocol defined in the manifest.

---

## 7. "Extensions" in Track B

### 7.1 Asset packs (VSIX-compatible)

The app can install a `.vsix` or a folder and use **only** its declarative contributions: `grammars`, `languages` (configuration), `themes`, `iconThemes`, `productIconThemes` (subset), `snippets`, `keybindings` (defaults), `configurationDefaults`. Any `main`/`browser` entry point is ignored and the pack is labeled "assets only" in the Tools view. Install sources: Open VSX download by ID (the VSIX is fetched by the guest and unpacked under `~/.local/share/avf-studio/assets/<publisher>.<name>/`), local file via SAF, or a URL.

### 7.2 Tool packs (guest registry)

A tool pack is a directory in the guest with `manifest.json`:

```json
{
  "name": "basedpyright",
  "version": "1.x",
  "kind": "lsp",
  "languages": ["python"],
  "command": ["basedpyright-langserver", "--stdio"],
  "install": { "uv": ["basedpyright"] },
  "settingsSection": "basedpyright",
  "env_allowlist": [],
  "initializationOptions": {}
}
```

`kind ∈ {lsp, dap, formatter, linter, acp-agent, cli}`. `install` supports `apt`, `uv`, `npm`, `cargo`, `download` (URL + SHA-256). `avf-daemon tools install <name>` and `tools update` run the installer with output streamed to the app. A curated catalog (`guest/tools/catalog.json`, versioned in the repo) lists packs for Python, TypeScript/JavaScript, Rust, Go, C/C++, Java (optional, heavy), Bash, YAML, JSON, TOML, Markdown, Dockerfile, SQL, and the debug adapters in Section 5.7. The app can only start tools present in the registry (the `Rpc` allowlist).

### 7.3 Future plugin API (design only)

Reserve the `avf.plugin` namespace. Candidates: WASM components with a capability-based API, or a JSON-RPC "sidecar" protocol where a guest process contributes commands, views (declarative), and tree data. Record decisions in `docs/adr/`. No implementation in v1.

---

## 8. AI (required)

### 8.1 Inline completions

Ghost text in the editor from the *Autocomplete* provider profile (shared with Track A, SPEC.md Section 8.2). FIM request built from the current file (prefix and suffix windows sized by model context), open-file snippets from the same language (up to 4, recency-ordered), and the file path. 250 ms debounce, cancellation on keystroke, Tab to accept, Ctrl+Right to accept word, Esc to dismiss, partial-accept for multi-line, 2 outstanding requests, per-language toggles, telemetry-free. Works with `openai-compatible` FIM, Anthropic Messages, Gemini, and Ollama. Requests originate from the app on the Android side (direct HTTPS to the provider) or, when the profile says `via_guest: true`, from the guest through a small relay in the daemon so a Tailscale-only endpoint works.

### 8.2 GitHub Copilot

Tool pack `copilot-language-server` (`@github/copilot-language-server`, installed with the server's bundled Node). The app speaks its LSP dialect over the `Rpc` bridge: device-code sign-in flow surfaced natively, `textDocument/inlineCompletion`, status, and sign-out. The completion source picker lets the user choose Copilot, a provider profile, or both with priority. Respect GitHub's terms as published for third-party editor integrations at implementation time **[VERIFY]**.

### 8.3 Built-in chat agent

A Secondary Side Bar view with conversation threads per workspace. Capabilities: streaming responses with Markdown and code blocks, "Apply" that opens a diff editor with accept per hunk, context attachments (selection, file, folder, problems, terminal output, Git diff, notebook cell), slash commands (`/explain`, `/fix`, `/tests`, `/doc`, `/commit` message generation), and an **agent mode** with tools executed in the guest through existing channels: read file, list directory, search, write file (always through a reviewed diff), run terminal command (approval required unless allowlisted per workspace), Git status and diff. Every write is an undoable checkpoint. Provider = the *Chat* profile; tool calling uses each provider's native tool API (`anthropic` tools, OpenAI functions, Gemini function calling); for providers without tools the agent mode is disabled.

### 8.4 Agent Client Protocol (terminal agents, native UI)

Implement an ACP client (Zed's Agent Client Protocol) over the `Rpc` bridge with `RpcKind::Acp`. Supported agents are tool packs of kind `acp-agent`: Claude Code (via its ACP adapter), Gemini CLI, Codex CLI (adapter), and any ACP-speaking agent installed in the guest. The UI shows the agent's plan, tool calls with approvals, file diffs rendered in the app's diff editor, terminal output, and permission prompts, all native. Sessions persist in the guest; the app can reattach after reconnecting. When ACP is unavailable for an agent, the Agents view launches it in a terminal tab as in Track A.

### 8.5 MCP

A settings UI edits an MCP server list (`~/.config/avf-studio/mcp.json`) in the format shared by the common agents; the app writes per-agent config files on request (Claude Code, Gemini CLI, Codex) and the built-in agent can consume MCP servers that run in the guest through the `Rpc` bridge (`RpcKind::Custom`, stdio transport).

### 8.6 Secrets

As SPEC.md Section 8.6: Keystore on the host; memory-only in the guest; injected per bridge launch through `env` entries allowed by the tool manifest; "Forget all keys" wipes both sides.

---

## 9. Terminal

Reuse `:terminal` from Track A Phase 4 with these additions: multiple tabs and splits, profiles (bash, zsh, fish, tmux), shell integration (OSC 633/133 marks for command navigation and exit status decorations), link detection (file paths with line numbers open in the editor, URLs open in Custom Tabs, `/proxy` rewriting for localhost URLs), find in scrollback, copy on select option, bracketed paste, true color, Sixel optional, 10 000 lines scrollback, GPU-friendly rendering at 120 Hz, IME-safe input with the modifier bar, and session reattachment through the v1 ring buffer.

License constraint: the emulator implementation must be Apache-2.0 or MIT compatible. If a GPL library is the only viable option, escalate before using it.

---

## 10. Mobile Ergonomics (Native-only wins)

* Instant start: workspace tree and last five documents are cached locally (encrypted at rest) so the editor renders before the guest answers; edits queue until connected and conflicts are resolved with the diff viewer.
* Native IME: the editor exposes a proper `InputConnection` with composing text support and no autocorrect by default in code; Markdown and chat get autocorrect.
* Haptics for sticky modifiers, breakpoints toggles, and successful Git pushes.
* System integrations: share sheet target (import), "Open with" for code files from Files apps (opens in a scratch workspace), app shortcuts (recent workspaces), widgets (optional) for agent status, Quick Settings tile to open the Recovery Terminal.
* Foldables: tabletop posture puts the terminal on the lower half; book posture puts Explorer left and editor right.
* Stylus: hover previews and precise selection with S Pen.

---

## 11. Performance Budgets (Native)

| Metric | Budget |
|---|---|
| Cold start to editable cached file | ≤ 1.5 s |
| App RSS, 5 files + 2 terminals + LSP | ≤ 250 MB |
| Keystroke to glyph, hardware keyboard | ≤ 16 ms p95 |
| Scroll at 120 Hz in a 10 000-line file | no dropped frames p95 |
| Open 50 MB log file (paged) | ≤ 1 s to first screen |
| Workspace search in 100k-file repo | first results ≤ 300 ms, streaming |
| Notebook with 200 cells and 50 images | open ≤ 2 s, scroll smooth |
| APK size (Track A + B) | ≤ 60 MB |

---

## 12. Testing

* **Daemon:** Section 3.6.
* **App unit:** document model (edits, undo grouping, LSP position mapping UTF-16), command system and `when` evaluation, keybinding parsing and chords, settings merge and schema, theme import mapping, notebook model round-trip against nbformat fixtures, Git porcelain parsing, DAP state machine.
* **App instrumented:** against a local x86_64 daemon over `adb reverse` with a fixture repository: open, edit, save, format, rename symbol across files, find references, debug a Python script to a breakpoint and inspect a variable, stage a hunk and commit, search and replace across files with undo, run a notebook cell with a matplotlib output, import a theme VSIX, agent mode edits a file with approval.
* **Golden screenshots** for the workbench in Compact and Expanded with the shipped themes.
* **Benchmarks** for Section 11 recorded per release on the reference device.

---

## 13. Phased Plan (Track B)

Prerequisite: Track A Phases 0 to 4 complete (shell, Supervisor, pairing, terminal exist).

### N0 — Engine evaluation (1 agent, 1 week)
Prototype `sora-editor` in Compose with a 10 000-line file, TextMate highlighting, LSP completion from basedpyright over the `Rpc` bridge, soft keyboard and hardware keyboard. Record budgets. **Gate:** budgets in Section 11 rows 3 and 4 met, or an engine decision memo.

### N1 — Protocol v2 and daemon (Rust)
Section 3 entirely, with tests. Tools registry and catalog with Python, TypeScript, Rust packs. **Acceptance:** daemon tests green; `avf-daemon tools install basedpyright` works in the guest and on x86_64.

The RPC packet and LSP bridge are the first N1 implementation slice; all remaining Section 3 channels and the tool registry/catalog acceptance criteria are still outstanding.

### N2 — Document model and single-file editor
Sections 4.2 (editing, navigation, find), 4.3, VFS-backed open/save with conflicts, Explorer (basic), tabs, Compact and Expanded skeleton layouts, Command Palette, Quick Open, keybindings defaults, Dark Modern theme. **Acceptance:** edit and save across reconnects; 50 MB file paging; VS Code keymap parity for the top 50 editor commands.

### N3 — Language intelligence
LSP client with all features in Section 4.2 "Language intelligence", Problems view, Outline, breadcrumbs, semantic tokens, settings scopes and `workspace/configuration`, asset packs (grammars, themes, snippets) and theme import. **Acceptance:** Python and TypeScript projects show parity with VS Code for completion, hover, diagnostics, rename, references, format, code actions; three community themes import correctly.

### N4 — Terminal, Search, Tasks, Ports
Section 9 additions, Section 5.7 Search, Section 5.8 Tasks, Ports and preview. **Acceptance:** search in a 100k-file repo streams first results under 300 ms; `tasks.json` build task with `$tsc` matcher populates Problems; preview opens a guest web app.

### N5 — Source Control
Section 3.4 and 5.7 Source Control, diff editor, merge editor, blame, timeline, commit graph. **Acceptance:** clone over HTTPS with a token prompt, resolve a 3-way conflict, stage a hunk, push with lease, all without a terminal.

### N6 — Debugging
Section 5.7 Run and Debug with `debugpy`, `codelldb`, `js-debug`. **Acceptance:** breakpoints, stepping, variables, watch, debug console, and `launch.json` compatibility across the three adapters; attach to a running process.

### N7 — Notebooks and data tools
Section 5.9 and the DataFrame viewer, plot viewer, interactive window, remote kernels. **Acceptance:** the notebook acceptance criteria from SPEC.md Section 9 plus 200-cell performance budget.

### N8 — AI
Section 8 entirely: inline completion, Copilot language server, built-in chat agent with tools and diff review, ACP client with at least two agents, MCP settings. **Acceptance:** agent mode edits two files under approval and the edits are undoable in one step; ACP session with Claude Code survives app process death and reattaches; completions under 1 s.

### N9 — Polish and release
Section 10, Section 11 gates, golden screenshots, accessibility (TalkBack through the editor and tree), localization, documentation (`docs/native-editor.md`), release.

---

## 14. Risks and Mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| Editor engine falls short (IME edge cases, decorations, performance) | Core UX | N0 gate; swappable `TextEditorHost`; budget-driven exit criteria. |
| Users expect VS Code extensions | Adoption | Tools view explains tiers; asset packs cover themes and grammars; Track A remains one tap away. |
| LSP servers behave differently without their VS Code extension glue (for example Python environment discovery) | Language parity | Tool manifests carry the glue (interpreter discovery via `uv`, settings sections); borrow logic from the open-source extensions. |
| Jupyter rich outputs need web rendering | Notebook UX | One shared WebView per notebook for the long tail; native renderers for the common 90 %. |
| Scope creep toward a VS Code clone | Schedule | Parity lists in Sections 4.2 and 5.7 are the scope; anything else needs a spec change. |
| GPL-only libraries for terminal or Git | Licensing | Explicit constraint in Section 9; Git via CLI in the guest, never linked. |
