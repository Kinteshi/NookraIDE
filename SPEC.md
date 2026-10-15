# SPEC.md — AVF Studio (Native-Feel VS Code IDE for Android Mobile, Tablets & DeX powered by AVF)

## 1. Project Overview

**AVF Studio** is an open-source IDE designed for the modern Android ecosystem. It adapts dynamically from **smartphones** (portable, portrait use) to **120Hz tablets** and **native Desktop modes (Samsung DeX / Android 16+ Desktop Mode)** with multiple monitors, mouse and physical keyboard.

Instead of running compilers in slow emulators (`proot`) or relying exclusively on a remote cloud, AVF Studio uses a **local Host-Guest architecture**:

* **Host (Universal Android front-end):** A **Tauri v2** app with a UI that looks and behaves like VS Code (`Monaco Editor` + `@codingame/monaco-vscode-api` + responsive chameleon Workbench + `Xterm.js`).
* **Guest (Linux back-end):** An ultra-light async daemon written in **Rust** (`avf-daemon`) running inside the native Debian virtual machine of the **Android Virtualization Framework (AVF / KVM)**.

## 2. System Architecture and Data Flow

```
+-----------------------------------------------------------------------+
| HOST: ANDROID DEVICE (Phone / Tablet / DeX - Tauri v2 + SystemWebView)|
|                                                                       |
|  +-----------------------+  +-------------------+  +---------------+  |
|  |   Monaco Editor UI    |  |  Xterm.js (WebGL) |  | File Explorer |  |
|  |(monaco-languageclient)|  |  Terminal Canvas  |  |  & Git Tree   |  |
|  +-----------+-----------+  +---------+---------+  +-------+-------+  |
|              |                        |                    |          |
|              +------------------------+--------------------+          |
|                                       |                               |
|                     [ Tauri IPC Bridge (Rust Core) ]                  |
+---------------------------------------|-------------------------------+
                                        |
                      Multiplexed WebSocket (localhost:19860)
        [ 1 Binary frame = 1 MultiplexedPacket in MessagePack (Hello+token first) ]
                                        |
+---------------------------------------|-------------------------------+
| GUEST: AVF DEBIAN VM (KVM Virtualized Linux)                          |
|                                                                       |
|  +-----------------------------------------------------------------+  |
|  |                  avf-ide-daemon (Tokio / Axum)                  |  |
|  |                                                                 |  |
|  |  +-------------------+  +------------------+  +--------------+  |  |
|  |  |    LSP Router     |  |   PTY Manager    |  | VFS & Git    |  |  |
|  |  | (Async Stdio Pipe)|  |  (portable-pty)  |  | (notify/git2)|  |  |
|  |  +---------+---------+  +--------+---------+  +------+-------+  |  |
|  +------------|---------------------|-------------------|----------+  |
|               |                     |                   |             |
|       +-------+-------+     +-------+-------+   +-------+-------+     |
|       | rust-analyzer |     |  zsh / bash   |   |  Linux ext4   |     |
|       | pyright / ruff|     |  tmux / htop  |   |  File System  |     |
|       | clangd / vtsls|     |  python / uv  |   |  (Fast I/O)   |     |
|       +---------------+     +---------------+   +---------------+     |
+-----------------------------------------------------------------------+
```

## 3. Monorepo Structure (Cargo Workspace + pnpm)

```
avf-studio/
├── Cargo.toml                 # Shared Rust workspace
├── pnpm-workspace.yaml        # Node/front-end workspace
├── crates/
│   ├── avf-protocol/          # Shared structs (Serde, RPC/LSP/PTY/VFS envelopes)
│   └── avf-daemon/            # Linux binary (aarch64-unknown-linux-gnu / x86_64) for the Debian VM
├── apps/
│   └── studio-ui/             # Front-end React 19 + TypeScript + Tailwind + Monaco + Xterm.js
│       └── src-tauri/         # Tauri v2 core (Android target + window/DeX detection)
├── docs/                      # Supporting guides (execution roadmap, model/Arc B580 setup)
└── scripts/
    └── bootstrap-avf.sh       # Provisions the daemon in the AVF VM's systemd and generates the auth token
```

## 4. Protocol Contract (`crates/avf-protocol`)

All communication between the Host (Android) and the Guest (Debian VM) goes through a single multiplexed WebSocket connection (`ws://127.0.0.1:19860/ws`). This avoids the overhead of multiple connections and makes automatic reconnection easier after the device sleeps or the screen changes (e.g. plugging the phone into a DeX monitor).

### 4.1 Encoding (Wire Format)

* **One frame = one packet.** Each **Binary** WebSocket message carries exactly one `MultiplexedPacket` encoded as **MessagePack**. Text frames are rejected with `SystemMessage::ProtocolError` and the connection is closed.
* **Rust:** `rmp-serde` using `rmp_serde::Serializer::new(buf).with_struct_map().with_human_readable()`:
  * `with_struct_map()` → structs become maps with field names (tolerant to protocol evolution and readable in DevTools).
  * `with_human_readable()` → `Uuid` is serialized as a string (`"550e8400-..."`), not as 16 raw bytes.
  * `Vec<u8>` fields use `#[serde(with = "serde_bytes")]` so they become MessagePack's `bin` type (avoiding the 3–4× overhead of a number array).
* **TypeScript:** `@msgpack/msgpack` (`encode`/`decode`). `bin` fields arrive as `Uint8Array`, ready for `xterm.write()`.
* **Enums use serde's default external representation** (`{ "Variant": { ...fields } }`, or `"Variant"` for unit variants). **Do not use** `#[serde(tag = ..., content = ...)]`: internally/adjacently tagged representations require `deserialize_any` and break with binary formats.
* **Maximum frame size:** 32 MiB (`WebSocketUpgrade::max_message_size`). File reads above 16 MiB return `VfsErrorCode::TooLarge`.
* **Versioning:** the `PROTOCOL_VERSION` constant is incremented on every breaking change. Daemon and app refuse mismatched versions during the handshake.

### 4.2 Handshake and Authentication

The daemon exposes a full shell and file read/write. Since the port is forwarded to the Android Host, **any app or web page on the device** could try to connect to `127.0.0.1:19860`. Therefore:

1. **Shared token:** `bootstrap-avf.sh` generates a random 256-bit token (hex), saved to `~/.config/avf-studio/token` (mode `0600`) in the VM. The user pastes/scans the token into the app once; Tauri stores it in Android secure storage. The daemon also accepts `AVF_TOKEN` via environment variable (development).
2. **Mandatory first frame:** the client must send `SystemMessage::Hello` within **5 seconds** of the upgrade. Any other packet before `Hello` is rejected and the connection is closed.
3. **Validation:** the daemon compares the token in constant time (`subtle::ConstantTimeEq`) and checks `protocol_version`. It replies `Welcome` (success) or `Rejected` (and closes the connection).
4. **`Origin` allowlist (defense in depth):** the upgrade on `/ws` is only accepted if the `Origin` header is in a configurable list (default: `http://tauri.localhost`, `tauri://localhost`; in development also `http://localhost:5173`). The token remains the primary barrier.
5. The token **never** goes in the URL (avoids leaking into logs); it only travels inside `Hello`.
6. `/health` requires no token and returns only `{ "status": "ok", "protocol_version": N }`.

### 4.3 Types

```rust
use serde::{Deserialize, Serialize};
use uuid::Uuid;

pub const PROTOCOL_VERSION: u32 = 1;

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub enum MultiplexedPacket {
    System(SystemMessage),
    Vfs(VfsMessage),
    /// `session_id` is generated by the CLIENT (UUID v4) at `Spawn` time
    /// and identifies the session in all subsequent messages.
    Pty { session_id: Uuid, data: PtyPayload },
    /// `json_rpc` is the raw JSON-RPC body, without the `Content-Length` header.
    Lsp { server_id: String, json_rpc: String },
}

// ───────────────────────────── System ─────────────────────────────

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub enum SystemMessage {
    // Handshake (client → daemon, always the first frame)
    Hello { protocol_version: u32, auth_token: String, client_name: String },
    // Handshake (daemon → client)
    Welcome {
        protocol_version: u32,
        daemon_version: String,
        /// Live PTY sessions, so the client can decide which to reattach.
        pty_sessions: Vec<PtySessionInfo>,
    },
    Rejected { reason: RejectReason },

    // Telemetry
    Ping { timestamp: u64 },
    Pong { timestamp: u64, cpu_usage: f32, mem_used_mb: u64, mem_total_mb: u64 },

    // Port detector (daemon → client)
    PortOpened { port: u16, protocol: PortProtocol, process_name: Option<String> },
    PortClosed { port: u16, protocol: PortProtocol },

    /// Malformed frame, invalid frame type, or out-of-order message.
    ProtocolError { message: String },
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub enum RejectReason {
    InvalidToken,
    VersionMismatch { daemon_version: u32 },
    HandshakeTimeout,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum PortProtocol { Tcp, Tcp6 }

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct PtySessionInfo {
    pub session_id: Uuid,
    pub shell: String,
    pub cwd: String,
    pub cols: u16,
    pub rows: u16,
}

// ───────────────────────────── PTY ─────────────────────────────

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub enum PtyPayload {
    // client → daemon
    Spawn { shell: Option<String>, cwd: Option<String>, cols: u16, rows: u16 },
    Input(#[serde(with = "serde_bytes")] Vec<u8>),
    Resize { cols: u16, rows: u16 },
    /// Reattaches to the envelope's session. The daemon replies with an `Output`
    /// containing the ring buffer (up to 64KB) and keeps streaming live.
    Reattach,
    /// Terminates the process (SIGHUP, then SIGKILL after 2s).
    Kill,

    // daemon → client
    Spawned { pid: u32 },
    Output(#[serde(with = "serde_bytes")] Vec<u8>),
    /// `code` is `None` when the process was killed by a signal.
    Exit { code: Option<i32> },
    /// E.g.: unknown session on `Reattach`/`Input`, duplicate `session_id` on `Spawn`, shell failed to start.
    Error { message: String },
}

// ───────────────────────────── VFS ─────────────────────────────

/// `req_id` is assigned by the client, unique per connection. Every request
/// gets exactly ONE response: the matching `*Res` or `VfsError`.
/// Paths are absolute (POSIX) inside the VM.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub enum VfsMessage {
    // client → daemon
    ListDirReq { req_id: u64, path: String },
    ReadFileReq { req_id: u64, path: String },
    WriteFileReq {
        req_id: u64,
        path: String,
        #[serde(with = "serde_bytes")]
        content: Vec<u8>,
        /// Optimistic concurrency: if `Some` and the file's current mtime
        /// differs, the write is refused with `VfsErrorCode::Conflict`.
        expected_modified_ts: Option<u64>,
    },
    WatchReq { req_id: u64, path: String, recursive: bool },
    UnwatchReq { req_id: u64, path: String },

    // daemon → client
    ListDirRes { req_id: u64, entries: Vec<FileEntry> },
    ReadFileRes {
        req_id: u64,
        #[serde(with = "serde_bytes")]
        content: Vec<u8>,
        modified_ts: u64,
    },
    WriteFileRes { req_id: u64, modified_ts: u64 },
    WatchRes { req_id: u64 },
    VfsError { req_id: u64, code: VfsErrorCode, message: String },

    // daemon → client (unsolicited)
    FsEvent { kind: FsEventKind, paths: Vec<String> },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum VfsErrorCode {
    NotFound,
    PermissionDenied,
    AlreadyExists,
    IsADirectory,
    NotADirectory,
    TooLarge,
    Conflict,
    InvalidPath,
    Io,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum FsEventKind {
    Create,
    Modify,
    Remove,
    /// `paths` = `[from, to]`.
    Rename,
    /// The watcher lost events (overflow); the client must reload the tree.
    Rescan,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct FileEntry {
    pub name: String,
    pub path: String,
    pub is_dir: bool,
    pub is_symlink: bool,
    pub size: u64,
    /// Milliseconds since the Unix epoch.
    pub modified_ts: u64,
}
```

### 4.4 Guarantees Required by Tests (`avf-protocol`)

* *Round-trip* test (`encode → decode == original`) for **every** variant of every enum above.
* Test ensuring `Vec<u8>` is encoded as MessagePack `bin` (not as an array) and `Uuid` is encoded as a string.
* Public functions `encode(&MultiplexedPacket) -> Vec<u8>` and `decode(&[u8]) -> Result<MultiplexedPacket, DecodeError>` centralized in the crate, so the daemon and tests never configure `rmp-serde` differently.

## 5. `avf-daemon` Specification (Rust / Guest VM)

* **Runtime & Web Server:** `tokio` (full) + `axum` (with WebSocket support on `/ws` and healthcheck on `/health`).
* **Default Port:** `127.0.0.1:19860` (exposed to the Android Host via AVF's native port forwarding).
* **Security:** mandatory handshake with token and `Origin` allowlist, per Section 4.2. No PTY/VFS/LSP message is processed before `Welcome`.
* **Core Modules:**
  1. **PTY Manager (`portable-pty`):**
     * Creation, dynamic resizing (`PtySize { rows, cols }`) and persistence of terminal sessions (`/bin/bash`, `/bin/zsh` or `tmux`).
     * **Reconnection Ring Buffer:** Keeps the last 64KB of output for each session in memory (`VecDeque<u8>`). If the user locks the phone or plugs into an external monitor (causing the Android Activity to be recreated), on `PtyPayload::Reattach` the daemon immediately returns the history without killing the running process.
  2. **LSP Bridge (`tokio::process`):**
     * Manages the lifecycle of LSP servers via `stdio` (e.g. `rust-analyzer`, `basedpyright-langserver --stdio`, `vtsls --stdio`, `clangd`).
     * **Framing / Unframing:**
       * *Input (WebSocket → StdIn):* Receives the clean `json_rpc` string from Monaco, computes `json_rpc.len()`, formats `Content-Length: {}\r\n\r\n{}` and writes it to `ChildStdin`.
       * *Output (StdOut → WebSocket):* Uses a `tokio::io::BufReader` on `ChildStdout`, reads headers up to `\r\n\r\n`, extracts `Content-Length`, reads exactly that many bytes of JSON body and dispatches a `MultiplexedPacket::Lsp` over the WebSocket.
     * **Graceful Hibernation:** If the WebSocket disconnects, LSP processes stay alive for up to 30 minutes (configurable) to avoid reindexing when the user returns to the app.
  3. **Virtual File System (VFS) & Watcher (`notify`):**
     * Async operations using `tokio::fs`. File writes are always atomic (write to `.filename.avf.tmp` and `rename`).
     * File watcher (`notify-debouncer-full`, which preserves the event type for `FsEventKind`) with a 150ms debounce, enabled by `WatchReq`, notifying the front-end of external changes (e.g. `git checkout`, script-generated artifacts). Ignores `.git/objects`, `node_modules`, `target` and `.venv` by default.
  4. **Port Detector (`/proc/net/tcp` scanner):**
     * Background task that reads `/proc/net/tcp` and `/proc/net/tcp6` every 2 seconds to detect ports in `LISTEN` state (`0A`). Emits `SystemMessage::PortOpened` so the front-end can automatically open a Web Preview or Jupyter panel.

## 6. `studio-ui` Specification (Chameleon Front-end: Mobile, Tablet & DeX)

* **UI Framework:** React 19 + TypeScript + Zustand (state management) + TailwindCSS.
* **Core Editor:** `monaco-editor` integrated with `@codingame/monaco-vscode-api` and `monaco-languageclient`.
* **Terminal:** `@xterm/xterm` with `@xterm/addon-webgl` and `@xterm/addon-fit`.
* **Adaptive Layout Engine (`useAdaptiveLayout`):** The front-end reacts in real time to window dimensions (`window.innerWidth`), orientation, and presence of peripherals (`pointer: fine` vs `pointer: coarse`):
  1. **Compact / Mobile Mode (`width < 768px` – smartphones in portrait):**
     * **Bottom Navigation Bar:** One-tap switching between 4 full-screen views: *Files*, *Editor*, *Terminal* and *Preview/Git* (with lateral edge-swipe support).
     * **Small-Screen Optimized Editor:** Automatically hides the *Minimap*, reduces line-number *gutter* spacing and enables *Word Wrap* by default to avoid horizontal scrolling on the phone.
     * **Sliding Drawer:** The file tree and command palette open as *Bottom Sheets* or overlay drawers.
  2. **Expanded / Workstation Mode (`width >= 768px` – tablets, unfolded foldables, Samsung DeX and Android 16+ Desktop Mode):**
     * **Full VS Code Layout:** Left Activity Bar, collapsible Sidebar, Editor Area with *Split View* support (horizontal/vertical) and resizable Bottom Panel for Terminal/Output.
     * **Zero-Reload DeX Continuity:** When the phone is connected to an external monitor (Compact → Expanded transition), Zustand state preserves the Monaco instances and calls `fitAddon.fit()` on Xterm.js, sending `PtyPayload::Resize` to the daemon without losing any typed line.
* **Mobile & Desktop Ergonomics:**
  1. **Touch Modifier Bar (smart auto-hide):** Compact bar docked just above the virtual keyboard (`visualViewport` API), shown whenever no physical keyboard is detected. Contains precision keys: `ESC`, `TAB`, `CTRL` (sticky), `ALT` (sticky), `{`, `}`, `(`, `)`, `[`, `]`, `:`, `;`, `/`, plus a virtual trackpad/arrow keys with smooth *repeat*.
  2. **Keyboard Shortcut Interceptor:** Clean capture of `Ctrl+P` (Quick Open), `Ctrl+Shift+P` (Command Palette), `Ctrl+B` (Toggle Sidebar) and ``Ctrl+` `` (Toggle Terminal) in DeX/physical keyboard mode.
  3. **VM Health Indicator:** Status Bar badge showing Debian VM CPU/RAM usage, WebSocket ping latency (ms) and a quick *Wake/Reconnect* button.
  4. **Local AI Copilot Provider:** Native Monaco integration (`registerInlineCompletionsProvider`) pointing to a configurable OpenAI-compatible endpoint (e.g. your Intel Arc B580 on the local network/Tailscale running Qwen Coder for zero-cost FIM autocomplete).

## 7. Phased Implementation Plan (For Agent Execution)

* **Phase 1 (Foundation & Protocol):** Initialize the Cargo workspace, define all message structs in `crates/avf-protocol` and implement the basic `axum` server in `crates/avf-daemon` with authenticated handshake (`Hello`/`Welcome`/`Rejected`), centralized MessagePack encoding, ping/pong, system telemetry and file operations (`VFS`, including `VfsError` and the watcher).
* **Phase 2 (PTY & Terminal):** Implement the `PtyManager` with ring buffer in the daemon and connect it to the `Xterm.js` component in the front-end, ensuring ANSI color support, dynamic resize and session reconnection.
* **Phase 3 (Adaptive Workbench, Monaco & VFS):** Build the responsive shell (Compact Mobile Mode vs Expanded Tablet/DeX Mode) and integrate the VM's real file tree with the Monaco Editor (multiple tabs, dirty state, save with `Ctrl+S` or Touch Bar button).
* **Phase 4 (LSP Bridge):** Implement the `stdio` proxy for `Content-Length` in Rust and plug it into `monaco-languageclient` (validate with Python/`basedpyright` and Rust/`rust-analyzer`).
* **Phase 5 (Android Packaging & Bootstrap):** Configure the Tauri v2 Android build (including `AndroidManifest.xml` flags for free resizing in DeX/Desktop Mode: `android:resizeableActivity="true"`) and create the `bootstrap-avf.sh` script (installs the systemd service and generates the token at `~/.config/avf-studio/token`).
