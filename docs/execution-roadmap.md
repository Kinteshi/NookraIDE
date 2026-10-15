# Local Execution Roadmap — Getting Started with Claude Code / Antigravity

You don't need a tablet or phone plugged in to develop 95% of the project. Since avf-daemon is a standard Linux binary and the front-end runs on Tauri/Vite, **you can develop and test the Daemon and the UI directly on your Linux PC (x86_64)**, simulating phone, tablet and DeX screens on the desktop before cross-compiling to **ARM64 (aarch64)** and deploying to the phone.

## The two crucial questions: ARM64 and Async

### 1. "Linux on Android runs on ARM (aarch64). How do I test that on my PC (x86_64)?"

The OS inside Android's AVF is a **regular Debian Linux**. For the Rust code (tokio, axum, portable-pty, notify) and for Linux (ext4, /proc/net/tcp, stdio), **it makes no difference whether the processor underneath is an Intel/AMD x86_64 or a Snapdragon/Tensor aarch64**.
So your testing flow has **3 simple stages**:

1. **Stage 1 — Day to day (native on your x86_64 PC):** Configure `AVF_TOKEN` and one or more `AVF_WORKSPACE_ROOTS`, then run `cargo run -p avf-daemon`. Phases 1–3 validate the protocol, authenticated transport, VFS, PTY, and frontend on the same host. Language servers and Android packaging remain later phases.
2. **Stage 2 — Cross-build the ARM64 binary:** Build for the Debian guest's `aarch64-unknown-linux-gnu` target using a cross linker/sysroot compatible with the guest. Running that binary on an x86_64 PC requires a configured ARM64 emulator/binfmt setup; a successful cross-build alone is not a runtime test.
3. **Stage 3 — Real test on phone/tablet via adb forward:** Even before building the Tauri APK, you can copy the ARM64 avf-daemon binary into your phone's Debian, run it there, and on your PC use `adb forward tcp:19860 tcp:19860`. Your PC's browser then connects in real time to the Daemon running on the phone's ARM processor over USB.

### 2. "Does it have to be async?"

**Yes, necessarily async (using the tokio runtime in Rust)** — not because of ARM, but because the Daemon is a **concurrent event multiplexer**.
Think about what happens at the same time over a single WebSocket connection between the app and the VM:

* You're typing in the editor → sending LSP packets (stdin).
* rust-analyzer or pyright finishes analyzing a type → replies asynchronously on stdout.
* A Python script is running in the terminal (PTY) → spitting logs to stdout every 50ms.
* The file watcher (notify) detects the script created a new .csv → notifies the UI file tree.
* The port scanner sees FastAPI opened port 8000 → fires an event to open the preview.

If the daemon were synchronous/blocking, your terminal would freeze while it waited for pyright. With `tokio::select!` and async channels (`tokio::sync::mpsc`), Rust handles all these I/O sources simultaneously using under **15 MB of RAM** and almost zero idle CPU.

## Step 1: Prepare the Ground in your Linux Terminal

*(The Rust workspace and Phase 1 crates are in this repository; `SPEC.md` is at the root.)*

From the cloned repository root, install the optional ARM64 cross-compilation tools:

```bash
# Add the ARM64 Debian guest target and cargo-zigbuild
rustup target add aarch64-unknown-linux-gnu
cargo install cargo-zigbuild
```

For local daemon development, run `cargo test --workspace` from the repository root. Configure `AVF_TOKEN` and `AVF_WORKSPACE_ROOTS` before launching the daemon; do not point the VFS roots at the entire guest filesystem.

```bash
export AVF_TOKEN="$(openssl rand -hex 32)"
export AVF_WORKSPACE_ROOTS="$PWD"
cargo run -p avf-daemon
```

## Step 2: Where to use each tool (Claude Code vs. Antigravity)

* **Claude Code (in the VS Code terminal):** Your **main builder for back-end, tokio concurrency and architecture**. Since it runs directly in the shell, it runs `cargo check`, `cargo test`, installs packages with pnpm and fixes borrow checker or async/await errors in real time.
* **Antigravity:** Great for **fast visual generation, fine-tuning the Adaptive Layout (Mobile vs. DeX) and parallel exploration of React/Tailwind components**. In Phase 3 (Workbench UI), use it to prototype and refine the *Touch Modifier Bar* and the responsive transitions between portrait phone and landscape DeX.

## Step 3: The Exact Prompts (Copy and Paste in Order)

Open **Claude Code** in `~/AVFStudio` and send one prompt at a time. Only move to the next phase when the previous one compiles without errors.

### Prompt 1 — Phase 1: Async Rust Foundation (avf-protocol + avf-daemon VFS)

**Paste into Claude Code:**

> Read @SPEC.md at the project root. Implement strictly Phase 1 (Foundation & Protocol); do not implement PTY, LSP, UI, port detection, or Android packaging.
>
> 1. Create the Cargo workspace and both crates, with the complete Section 4 schema. Centralize struct-map/human-readable MessagePack encode/decode; test every packet and enum variant, binary `Vec<u8>`, string UUID, malformed input, and trailing data.
> 2. Implement the Tokio/Axum daemon at `127.0.0.1:19860`, `/health`, and `/ws`. Enforce 32 MiB message limits, binary-only frames, a five-second Hello-first handshake, fixed 256-bit hex token validation (environment or private token file), protocol-version checks, exact configurable Origin allowlisting, and do not process application packets before Welcome.
> 3. Require `AVF_WORKSPACE_ROOTS` to configure one or more existing project roots. Implement VFS list/read/write/watch/unwatch with capability-scoped paths that reject traversal and symlink escapes. Apply the 16 MiB limit to reads and writes, use a unique same-directory temp file plus atomic rename, compare `expected_modified_ts`, and return exactly one request-correlated response.
> 4. Use bounded outbound and watcher queues. Apply 150 ms watcher batching, ignore `.git/objects`, `node_modules`, `target`, and `.venv`, preserve rename path ordering, emit `Rescan` after overflow, and clean up per-connection watchers.
> 5. Implement Ping/Pong from `/proc` telemetry with explicit sampling/error behavior. Do not use an unbounded channel as a memory/backpressure shortcut.
> 6. Add integration tests for handshake ordering, invalid token/version/origin, health, VFS confinement/symlink escapes, size limits, atomic writes/conflicts, and watcher delivery/cleanup. Run `cargo fmt --all -- --check`, `cargo test --workspace`, and `cargo clippy --workspace --all-targets -- -D warnings`.

### Prompt 2 — Phase 2: PTY Manager, Ring Buffer and Terminal View

**Paste into Claude Code after Prompt 1 passes:**

> Implement Phase 2 of @SPEC.md (Section 5, item 1 – PTY Manager, plus its minimal Xterm.js terminal view).
>
> 1. Add the `portable-pty` crate to `crates/avf-daemon`.
> 2. Create `src/pty.rs` managing multiple terminal sessions identified by `Uuid` in an `Arc<tokio::sync::RwLock<HashMap<Uuid, PtySession>>>`.
> 3. Since `portable-pty` read/write is synchronous (OS-blocking), isolate the `MasterPty` read loop inside `tokio::task::spawn_blocking`, sending read bytes through a Tokio async channel to the WebSocket, never blocking the async runtime.
> 4. Implement `PtyPayload::Spawn`, `Input`, `Output`, `Resize`, `Reattach`, `Kill`, `Spawned`, `Exit` and `Error`.
> 5. Implement a 64KB Ring Buffer (`VecDeque<u8>`) per session so that, if the WebSocket client reconnects and sends `PtyPayload::Reattach` (session in the `Pty` envelope), the daemon immediately returns the recent history and keeps streaming the live process output.
> 6. Integrate the `PtyManager` into the main `/ws` WebSocket loop and add a minimal Xterm.js terminal view in `apps/studio-ui` that exercises spawn, input, output, resize, kill and reattach.
> 7. Keep WebSocket writes serialized through the existing bounded outbound queue; do not add an unbounded sender. Test terminal session behavior and validate with `cargo check` and `cargo test`.

### Prompt 3 — Phase 3: Chameleon Front-end (Mobile, Tablet & DeX) + Monaco

**Paste into Claude Code (or Antigravity):**

> Continue the front-end from Phase 2 and implement only Phase 3 and Section 6 of @SPEC.md. Reuse the existing Xterm.js terminal and PTY transport rather than rebuilding them.
>
> 1. Extend the Phase 2 React + TypeScript + Vite app in `apps/studio-ui`; configure TailwindCSS and add `zustand` and Monaco (`@monaco-editor/react` or `monaco-editor`). Retain the Phase 2 Xterm dependencies rather than rebuilding the terminal.
> 2. Extend the multiplexed WebSocket client and Zustand store with request-correlated VFS responses and filesystem events while preserving PTY reconnect and terminal state. The client supplies an absolute project path already included in `AVF_WORKSPACE_ROOTS`; the protocol does not enumerate configured roots.
> 3. Implement `useAdaptiveLayout` and the Chameleon Workbench:
>    - On screens `< 768px` (Compact Mode), show four full-screen destinations as specified in `SPEC.md`: Files, Editor, Terminal and Preview/Git. Keep the explorer and command palette in a drawer or sheet.
>    - On screens `>= 768px` (Expanded Mode), show the Activity Bar, collapsible Explorer, editor tabs/split view and resizable Terminal/Output panel.
>    - Preserve editor and terminal state across Compact/Expanded transitions; refit Xterm and send `PtyPayload::Resize` after dimensions change.
> 4. Connect the Explorer to VFS list/read/watch responses. Open text files in Monaco tabs, track dirty state, save with `Ctrl+S` or a button using `expected_modified_ts`, and surface conflicts for explicit reload or overwrite.
> 5. Add small-screen editor defaults, the touch modifier bar (`ESC`, `TAB`, sticky `CTRL`/`ALT`, delimiters and repeatable arrows), and the `Ctrl+P`, `Ctrl+Shift+P`, `Ctrl+B`, and ``Ctrl+` `` shortcuts.
> 6. Keep LSP server integration for Phase 4 and Android/Tauri packaging for Phase 5. Preview/Git navigation may be present, but do not imply backend features that have not been implemented.
> 7. Validate mobile and expanded viewport behavior and ensure `pnpm --filter @avf-studio/studio-ui build` has no TypeScript errors.

### Prompt 4 — Phase 4: Async LSP Bridge in Rust

**Paste into Claude Code:**

> Implement Phase 4 of @SPEC.md: the `LspRouter` in `crates/avf-daemon/src/lsp.rs` and the port scanner in `src/ports.rs`.
>
> 1. Create `lsp.rs` using `tokio::process::Command` to start LSP servers on demand (e.g. `basedpyright-langserver --stdio`, `rust-analyzer`, `vtsls --stdio`) with `Stdio::piped()`.
> 2. Implement async framing and unframing (`tokio::io::AsyncBufReadExt` and `AsyncReadExt::read_exact`) of the `Content-Length: <bytes>\r\n\r\n` header on `ChildStdin` and `ChildStdout`, forwarding only the clean JSON-RPC string via `MultiplexedPacket::Lsp` on the WebSocket.
> 3. Implement graceful hibernation: if the WebSocket drops, keep LSP processes alive in memory for fast reconnection.
> 4. Create `src/ports.rs` that reads `/proc/net/tcp` every 2 seconds asynchronously to detect ports entering LISTEN state (`0A`) and emits `SystemMessage::PortOpened`.
> 5. Validate with `cargo check` and write an async unit test (`#[tokio::test]`) for the LSP `Content-Length` parser.

## Step 4: Testing on the PC and Compiling for ARM64 (Android AVF)

### A. Testing on your PC right now (daily x86_64 development)

1. **Terminal 1 (Daemon on the PC):**
   ```bash
   cargo run -p avf-daemon
   ```
2. **Terminal 2 (UI):**
   ```bash
   cd apps/studio-ui && pnpm dev
   ```
3. Open http://localhost:5173 in the browser, press F12 and toggle between **Phone (412x915)** and **DeX/Tablet (1920x1080)** resolutions.

For the Phase 2 terminal harness, start the daemon with `AVF_TOKEN` and `AVF_WORKSPACE_ROOTS` set (see Step 1), then enter that same token in the UI's password field. The development Origin `http://localhost:5173` is allowed by default in debug builds. The harness keeps the token in memory only; do not put it in source files or browser storage.

### B. Building the Static ARM64 Binary for Android's Debian VM

When you want to run the daemon inside your phone or tablet's Linux (AVF):

```bash
# Cross-build for the Debian guest; configure the Zig target/sysroot to match
# the guest's glibc baseline before treating the binary as deployable.
cargo zigbuild --release -p avf-daemon --target aarch64-unknown-linux-gnu
```

The final binary will be at `target/aarch64-unknown-linux-gnu/release/avf-daemon`. Validate it in the actual AVF Debian guest before relying on it. A static musl build is an alternative deployment choice, not the default in `SPEC.md`; select and test it separately.
