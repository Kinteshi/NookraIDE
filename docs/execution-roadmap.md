# Local Execution Roadmap — Getting Started with Claude Code / Antigravity

You don't need a tablet or phone plugged in to develop 95% of the project. Since avf-daemon is a standard Linux binary and the front-end runs on Tauri/Vite, **you can develop and test the Daemon and the UI directly on your Linux PC (x86_64)**, simulating phone, tablet and DeX screens on the desktop before cross-compiling to **ARM64 (aarch64)** and deploying to the phone.

## The two crucial questions: ARM64 and Async

### 1. "Linux on Android runs on ARM (aarch64). How do I test that on my PC (x86_64)?"

The OS inside Android's AVF is a **regular Debian Linux**. For the Rust code (tokio, axum, portable-pty, notify) and for Linux (ext4, /proc/net/tcp, stdio), **it makes no difference whether the processor underneath is an Intel/AMD x86_64 or a Snapdragon/Tensor aarch64**.
So your testing flow has **3 simple stages**:

1. **Stage 1 — Day to day (native on your x86_64 PC):** Run `cargo run -p avf-daemon` normally on your Linux desktop. It opens your PC's terminal, reads your PC's files and starts the pyright/rust-analyzer installed on your PC. Zero emulation, instant compilation.
2. **Stage 2 — Testing the ARM64 binary on the PC (qemu-user-static):** To make sure the ARM64 build works before sending it to the phone, you don't need a heavy VM. Build with `cargo zigbuild --target aarch64-unknown-linux-musl` (produces a single static binary, independent of the phone Debian's glibc version) and run `./target/aarch64-unknown-linux-musl/debug/avf-daemon` directly in your terminal. Your PC's Linux (via binfmt_misc + qemu-user-static) runs ARM64 binaries transparently.
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

## Step 1: Prepare the Ground in your Linux Terminal (2 minutes)

*(Already done in this repo: toolchain installed via pacman, ARM64 musl target added, SPEC.md at the root.)*

Open a terminal, create the project folder and install cargo-zigbuild (the best tool today for compiling Rust to ARM64 without C linker headaches):

```bash
mkdir -p ~/AVFStudio
cd ~/AVFStudio
git init

# Create the base monorepo folders so the agent doesn't get lost
mkdir -p crates/avf-protocol crates/avf-daemon apps scripts

# Add the static ARM64 (musl) target and cargo-zigbuild
rustup target add aarch64-unknown-linux-musl
cargo install cargo-zigbuild
```

*(Save SPEC.md at the root: `~/AVFStudio/SPEC.md`.)*

## Step 2: Where to use each tool (Claude Code vs. Antigravity)

* **Claude Code (in the VS Code terminal):** Your **main builder for back-end, tokio concurrency and architecture**. Since it runs directly in the shell, it runs `cargo check`, `cargo test`, installs packages with pnpm and fixes borrow checker or async/await errors in real time.
* **Antigravity:** Great for **fast visual generation, fine-tuning the Adaptive Layout (Mobile vs. DeX) and parallel exploration of React/Tailwind components**. In Phase 3 (Workbench UI), use it to prototype and refine the *Touch Modifier Bar* and the responsive transitions between portrait phone and landscape DeX.

## Step 3: The Exact Prompts (Copy and Paste in Order)

Open **Claude Code** in `~/AVFStudio` and send one prompt at a time. Only move to the next phase when the previous one compiles without errors.

### Prompt 1 — Phase 1: Async Rust Foundation (avf-protocol + avf-daemon VFS)

**Paste into Claude Code:**

> Read @SPEC.md at the project root. We will execute strictly Phase 1 (Foundation & Protocol), 100% async with Tokio and compatible with both x86_64 and aarch64.
>
> 1. Create the workspace `Cargo.toml` at the root including `crates/avf-protocol` and `crates/avf-daemon`.
> 2. Implement `crates/avf-protocol` with all structs and enums specified in Section 4 of SPEC.md (`MultiplexedPacket`, `SystemMessage`, `PtyPayload`, `VfsMessage`, `FileEntry`), using `serde`, `serde_bytes`, `uuid` (feature `serde`) and `rmp-serde`, with the centralized `encode`/`decode` functions and the round-trip tests from Section 4.4.
> 3. Implement `crates/avf-daemon` using `tokio` (full), `axum` (feature `ws`), `sysinfo` (real CPU/RAM metrics in `Pong`), and `notify` / `tokio::fs` to answer all `VfsMessage` requests non-blockingly (`ListDirReq`, `ReadFileReq`, `WriteFileReq` with atomic write via temp file).
> 4. Use one `tokio::sync::mpsc::unbounded_channel` per WebSocket connection so multiple background tasks (VFS watcher, PTY, LSP, System) can send `MultiplexedPacket` to the WebSocket `SplitSink` without lock contention.
> 5. Expose `/health` and the multiplexed WebSocket at `/ws` on `127.0.0.1:19860`, with the `Hello`/`Welcome`/`Rejected` handshake, token (`AVF_TOKEN` or `~/.config/avf-studio/token`) and the `Origin` allowlist from Section 4.2.
> 6. Run `cargo check` and `cargo test` until it compiles with zero errors and zero warnings.

### Prompt 2 — Phase 2: PTY Manager with Ring Buffer and Async Bridge

**Paste into Claude Code after Prompt 1 passes:**

> Great. Now the back-end part of Phase 2 of @SPEC.md (Section 5, item 1 – PTY Manager).
>
> 1. Add the `portable-pty` crate to `crates/avf-daemon`.
> 2. Create `src/pty.rs` managing multiple terminal sessions identified by `Uuid` in an `Arc<tokio::sync::RwLock<HashMap<Uuid, PtySession>>>`.
> 3. Since `portable-pty` read/write is synchronous (OS-blocking), isolate the `MasterPty` read loop inside `tokio::task::spawn_blocking`, sending read bytes through a Tokio async channel to the WebSocket, never blocking the async runtime.
> 4. Implement `PtyPayload::Spawn`, `Input`, `Output`, `Resize`, `Reattach`, `Kill`, `Spawned`, `Exit` and `Error`.
> 5. Implement a 64KB Ring Buffer (`VecDeque<u8>`) per session so that, if the WebSocket client reconnects and sends `PtyPayload::Reattach` (session in the `Pty` envelope), the daemon immediately returns the recent history and keeps streaming the live process output.
> 6. Integrate the `PtyManager` into the main `/ws` WebSocket loop and validate with `cargo check`.

### Prompt 3 — Phase 3: Chameleon Front-end (Mobile, Tablet & DeX) + Xterm + Monaco

**Paste into Claude Code (or Antigravity):**

> Now create the front-end in `apps/studio-ui` following Phases 2 and 3 and Section 6 of @SPEC.md.
>
> 1. Initialize a React + TypeScript + Vite + TailwindCSS project in `apps/studio-ui`.
> 2. Install `zustand`, `lucide-react`, `@xterm/xterm`, `@xterm/addon-fit`, `@xterm/addon-webgl` and `@monaco-editor/react` (or `monaco-editor` directly).
> 3. Create the multiplexed WebSocket client (`src/services/wsClient.ts`) that connects to `ws://127.0.0.1:19860/ws`, auto-reconnects, measures latency via `Ping/Pong` and dispatches events to the Zustand store.
> 4. Implement the `useAdaptiveLayout` hook and the Chameleon Workbench:
>    - On screens `< 768px` (Smartphone Mode): show a Bottom Navigation Bar switching between full-screen Explorer, Editor and Terminal, with the Touch Modifier Bar (`ESC`, `TAB`, sticky `CTRL`, brackets, arrows) fixed at the bottom.
>    - On screens `>= 768px` (Tablet / Samsung DeX / Desktop Mode): show the full VS Code layout (Activity Bar, File Explorer Sidebar, Editor with multiple tabs and resizable bottom Terminal panel).
> 5. Connect the File Explorer to the daemon's `VfsMessage` messages (browsing real Linux folders, opening files in Monaco and saving with `Ctrl+S` or the Touch Bar button) and connect Xterm.js to the daemon's `PtyPayload`.
> 6. Ensure `pnpm build` runs with no TypeScript errors.

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

### B. Building the Static ARM64 Binary for Android's Debian VM

When you want to run the daemon inside your phone or tablet's Linux (AVF):

```bash
# Build static ARM64 (musl) — works on any Debian version without glibc errors
cargo zigbuild --release -p avf-daemon --target aarch64-unknown-linux-musl
```

The final binary will be at `target/aarch64-unknown-linux-musl/release/avf-daemon`.
Drop it into your Android Downloads folder (mounted at `/mnt/shared` inside AVF's Debian VM) and run it there.
