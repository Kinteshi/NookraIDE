import Editor from "@monaco-editor/react";
import type { editor as MonacoEditor } from "monaco-editor";
import { FitAddon } from "@xterm/addon-fit";
import { Terminal } from "@xterm/xterm";
import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type CSSProperties,
  type PointerEvent as ReactPointerEvent,
} from "react";
import {
  asFsEntries,
  packetFsEvent,
  packetVariant,
  StudioConnection,
  VfsRequestError,
  type Welcome,
} from "./studio-connection";
import {
  useStudioStore,
  type EditorDocument,
  type WorkspaceEntry,
} from "./studio-store";
import { serverForLanguage } from "./lsp-routing";
import { useAdaptiveLayout } from "./use-adaptive-layout";

type MonacoApi = typeof import("monaco-editor");
type View = "files" | "editor" | "terminal" | "preview";
type Overlay = "quick-open" | "command-palette" | null;
type PreviewPort = {
  port: number;
  protocol: "Tcp" | "Tcp6";
  processName: string | null;
};

const DEFAULT_SOCKET_URL = "ws://127.0.0.1:19860/ws";
const NAV_ITEMS: Array<{ id: View; label: string; icon: string }> = [
  { id: "files", label: "Files", icon: "▤" },
  { id: "editor", label: "Editor", icon: "⌘" },
  { id: "terminal", label: "Terminal", icon: ">_" },
  { id: "preview", label: "Preview / Git", icon: "◇" },
];

function record(value: unknown): Record<string, unknown> | null {
  return typeof value === "object" && value !== null && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : null;
}

function languageForPath(path: string): string {
  const extension = path.split(".").pop()?.toLowerCase();
  const languages: Record<string, string> = {
    c: "c",
    cc: "cpp",
    cpp: "cpp",
    css: "css",
    go: "go",
    h: "cpp",
    hpp: "cpp",
    html: "html",
    java: "java",
    js: "javascript",
    json: "json",
    jsx: "javascript",
    md: "markdown",
    py: "python",
    rb: "ruby",
    rs: "rust",
    sh: "shell",
    toml: "toml",
    ts: "typescript",
    tsx: "typescript",
    xml: "xml",
    yaml: "yaml",
    yml: "yaml",
  };
  return extension ? languages[extension] ?? "plaintext" : "plaintext";
}

function basename(path: string): string {
  return path.split("/").filter(Boolean).at(-1) ?? path;
}

function collectFiles(directories: Record<string, WorkspaceEntry[]>): WorkspaceEntry[] {
  const entries = new Map<string, WorkspaceEntry>();
  for (const list of Object.values(directories)) {
    for (const entry of list) {
      if (!entry.is_dir) entries.set(entry.path, entry);
    }
  }
  return [...entries.values()].sort((a, b) => a.path.localeCompare(b.path));
}

function App() {
  const { compact, touchPrimary } = useAdaptiveLayout();
  const [connection] = useState(() => new StudioConnection());
  const [socketUrl, setSocketUrl] = useState(DEFAULT_SOCKET_URL);
  const [token, setToken] = useState("");
  const [connected, setConnected] = useState(false);
  const [welcome, setWelcome] = useState<Welcome | null>(null);
  const [connectionStatus, setConnectionStatus] = useState("Disconnected");
  const [terminalStatus, setTerminalStatus] = useState("Terminal disconnected");
  const [error, setError] = useState("");
  const [latency, setLatency] = useState<number | null>(null);
  const [vmSample, setVmSample] = useState("");
  const [previewPorts, setPreviewPorts] = useState<PreviewPort[]>([]);
  const [mobileView, setMobileView] = useState<View>("files");
  const [bottomOpen, setBottomOpen] = useState(true);
  const [bottomHeight, setBottomHeight] = useState(280);
  const [overlay, setOverlay] = useState<Overlay>(null);
  const [search, setSearch] = useState("");
  const [workspaceBusy, setWorkspaceBusy] = useState(false);
  const [saveConflictPath, setSaveConflictPath] = useState<string | null>(null);
  const refreshTimer = useRef<number | null>(null);
  const touchStart = useRef<{ x: number; y: number } | null>(null);
  const activeEditor = useRef<MonacoEditor.IStandaloneCodeEditor | null>(null);
  const workspaceRoot = useStudioStore((state) => state.workspaceRoot);
  const tabs = useStudioStore((state) => state.tabs);
  const activePath = useStudioStore((state) => state.activePath);
  const directories = useStudioStore((state) => state.directories);
  const sidebarOpen = useStudioStore((state) => state.sidebarOpen);
  const setSidebarOpen = useStudioStore((state) => state.setSidebarOpen);
  const setWorkspaceRoot = useStudioStore((state) => state.setWorkspaceRoot);
  const setDirectory = useStudioStore((state) => state.setDirectory);
  const openDocument = useStudioStore((state) => state.openDocument);
  const setActivePath = useStudioStore((state) => state.setActivePath);
  const setSplitPath = useStudioStore((state) => state.setSplitPath);
  const splitDirection = useStudioStore((state) => state.splitDirection);
  const setSplitDirection = useStudioStore((state) => state.setSplitDirection);
  const updateDocument = useStudioStore((state) => state.updateDocument);
  const markSaved = useStudioStore((state) => state.markSaved);
  const closeDocument = useStudioStore((state) => state.closeDocument);
  const splitPath = useStudioStore((state) => state.splitPath);

  const closeTab = useCallback(
    (path: string) => {
      const document = useStudioStore.getState().tabs.find((tab) => tab.path === path);
      if (
        document &&
        document.value !== document.savedValue &&
        !window.confirm(`Discard unsaved changes to ${document.name}?`)
      ) {
        return;
      }
      closeDocument(path);
    },
    [closeDocument],
  );

  const loadDirectory = useCallback(
    async (path: string) => {
      const result = await connection.requestVfs<{ ListDirRes: { entries: unknown } }>(
        "ListDirReq",
        { path },
      );
      setDirectory(path, asFsEntries(result.ListDirRes.entries));
    },
    [connection, setDirectory],
  );

  const loadWorkspace = useCallback(
    async (path: string) => {
      const normalized = path.trim().replace(/\/+$/, "") || "/";
      if (!normalized.startsWith("/")) {
        setError("Enter an absolute POSIX path inside an allowed workspace root.");
        return;
      }
      setWorkspaceBusy(true);
      setError("");
      try {
        const previousRoot = useStudioStore.getState().workspaceRoot;
        if (previousRoot && previousRoot !== normalized && connection.isReady()) {
          await connection.requestVfs("UnwatchReq", { path: previousRoot });
        }
        await loadDirectory(normalized);
        setWorkspaceRoot(normalized);
        await connection.requestVfs("WatchReq", {
          path: normalized,
          recursive: true,
        });
      } catch (cause) {
        setError(cause instanceof Error ? cause.message : String(cause));
      } finally {
        setWorkspaceBusy(false);
      }
    },
    [connection, loadDirectory, setWorkspaceRoot],
  );

  const refreshVisibleDirectories = useCallback(async () => {
    const state = useStudioStore.getState();
    const paths = new Set([state.workspaceRoot, ...state.expanded]);
    await Promise.all(
      [...paths]
        .filter(Boolean)
        .map(async (path) => {
          try {
            await loadDirectory(path);
          } catch (cause) {
            setError(cause instanceof Error ? cause.message : String(cause));
          }
        }),
    );
  }, [loadDirectory]);

  const openFile = useCallback(
    async (entry: WorkspaceEntry) => {
      if (entry.is_symlink) {
        setError("Opening symbolic links is disabled in this workbench.");
        return;
      }
      const existing = useStudioStore
        .getState()
        .tabs.find((tab) => tab.path === entry.path);
      if (existing) {
        setActivePath(entry.path);
        setMobileView("editor");
        return;
      }
      setError("");
      try {
        const result = await connection.requestVfs<{
          ReadFileRes: { content: Uint8Array; modified_ts: number };
        }>("ReadFileReq", { path: entry.path });
        const contents = new TextDecoder("utf-8", { fatal: true }).decode(
          result.ReadFileRes.content,
        );
        const document: EditorDocument = {
          path: entry.path,
          name: entry.name,
          language: languageForPath(entry.path),
          value: contents,
          savedValue: contents,
          modifiedTs: result.ReadFileRes.modified_ts,
        };
        openDocument(document);
        setMobileView("editor");
      } catch (cause) {
        setError(cause instanceof Error ? cause.message : String(cause));
      }
    },
    [connection, openDocument, setActivePath],
  );

  const saveDocument = useCallback(
    async (path: string, force = false) => {
      const document = useStudioStore
        .getState()
        .tabs.find((tab) => tab.path === path);
      if (!document || document.value === document.savedValue) return;
      setError("");
      try {
        const result = await connection.requestVfs<{
          WriteFileRes: { modified_ts: number };
        }>("WriteFileReq", {
          path,
          content: new TextEncoder().encode(document.value),
          expected_modified_ts: force ? null : document.modifiedTs,
        });
        markSaved(path, document.value, result.WriteFileRes.modified_ts);
        setSaveConflictPath(null);
      } catch (cause) {
        if (cause instanceof VfsRequestError && cause.code === "Conflict") {
          setSaveConflictPath(path);
        }
        setError(cause instanceof Error ? cause.message : String(cause));
      }
    },
    [connection, markSaved],
  );

  const reloadDocument = useCallback(
    async (path: string) => {
      const document = useStudioStore
        .getState()
        .tabs.find((tab) => tab.path === path);
      if (!document || !window.confirm(`Discard local changes to ${document.name} and reload from disk?`)) {
        return;
      }
      try {
        const result = await connection.requestVfs<{
          ReadFileRes: { content: Uint8Array; modified_ts: number };
        }>("ReadFileReq", { path });
        const contents = new TextDecoder("utf-8", { fatal: true }).decode(
          result.ReadFileRes.content,
        );
        markSaved(path, contents, result.ReadFileRes.modified_ts);
        setSaveConflictPath(null);
        setError("");
      } catch (cause) {
        setError(cause instanceof Error ? cause.message : String(cause));
      }
    },
    [connection, markSaved],
  );

  useEffect(() => {
    return connection.onPacket((packet) => {
      const fsEvent = packetFsEvent(packet);
      if (fsEvent) {
        if (refreshTimer.current !== null) window.clearTimeout(refreshTimer.current);
        refreshTimer.current = window.setTimeout(() => {
          void refreshVisibleDirectories();
        }, 180);
        return;
      }
      if ("ClientError" in packet) {
        const detail = record(packet.ClientError);
        setError(String(detail?.message ?? "Protocol error."));
        return;
      }
      if ("Disconnected" in packet) {
        setConnected(false);
        setConnectionStatus("Disconnected");
        setWelcome(null);
        setPreviewPorts([]);
        return;
      }
      const system = packetVariant(packet, "System");
      if (system?.[0] === "PortOpened") {
        const { port, protocol, process_name } = system[1];
        if (
          typeof port === "number" &&
          (protocol === "Tcp" || protocol === "Tcp6") &&
          (typeof process_name === "string" || process_name === null)
        ) {
          setPreviewPorts((current) => [
            ...current.filter((item) => item.port !== port || item.protocol !== protocol),
            { port, protocol, processName: process_name },
          ]);
        }
        return;
      }
      if (system?.[0] === "PortClosed") {
        const { port, protocol } = system[1];
        if (typeof port === "number" && (protocol === "Tcp" || protocol === "Tcp6")) {
          setPreviewPorts((current) =>
            current.filter((item) => item.port !== port || item.protocol !== protocol),
          );
        }
        return;
      }
      if (system?.[0] === "Pong") {
        const timestamp = system[1].timestamp;
        if (typeof timestamp === "number") {
          setLatency(Math.max(0, Date.now() - timestamp));
        }
        const cpu = system[1].cpu_usage;
        const used = system[1].mem_used_mb;
        const total = system[1].mem_total_mb;
        if (typeof cpu === "number" && typeof used === "number" && typeof total === "number") {
          setVmSample(`CPU ${cpu.toFixed(0)}% · RAM ${used}/${total} MB`);
        }
      }
    });
  }, [connection, refreshVisibleDirectories]);

  useEffect(() => {
    if (!connected) return;
    const timer = window.setInterval(() => {
      try {
        connection.ping(Date.now());
      } catch (cause) {
        setError(cause instanceof Error ? cause.message : String(cause));
      }
    }, 5000);
    return () => window.clearInterval(timer);
  }, [connected, connection]);

  useEffect(() => {
    return () => {
      if (refreshTimer.current !== null) window.clearTimeout(refreshTimer.current);
      connection.close();
    };
  }, [connection]);

  const connect = useCallback(async () => {
    if (!/^[\da-fA-F]{64}$/.test(token)) {
      setError("Enter the 64-character AVF token. It is kept in memory only.");
      return;
    }
    let parsedUrl: URL;
    try {
      parsedUrl = new URL(socketUrl);
    } catch {
      setError("Enter a valid websocket URL.");
      return;
    }
    if (parsedUrl.protocol !== "ws:" && parsedUrl.protocol !== "wss:") {
      setError("The websocket URL must use ws:// or wss://.");
      return;
    }
    setError("");
    setConnectionStatus("Connecting…");
    try {
      const daemonWelcome = await connection.connect(parsedUrl.toString(), token);
      setWelcome(daemonWelcome);
      setConnected(true);
      setConnectionStatus("Connected");
      const root = useStudioStore.getState().workspaceRoot;
      if (root) await loadWorkspace(root);
    } catch (cause) {
      setConnected(false);
      setConnectionStatus("Connection failed");
      setError(cause instanceof Error ? cause.message : String(cause));
    }
  }, [connection, loadWorkspace, socketUrl, token]);

  const disconnect = useCallback(() => {
    connection.close();
    setConnected(false);
    setWelcome(null);
    setConnectionStatus("Disconnected");
  }, [connection]);

  const toggleDirectory = useCallback(
    async (entry: WorkspaceEntry) => {
      if (entry.is_symlink) {
        setError("Opening symbolic links is disabled in this workbench.");
        return;
      }
      const state = useStudioStore.getState();
      const isExpanded = state.expanded.includes(entry.path);
      state.toggleExpanded(entry.path);
      if (!isExpanded && !state.directories[entry.path]) {
        try {
          await loadDirectory(entry.path);
        } catch (cause) {
          setError(cause instanceof Error ? cause.message : String(cause));
        }
      }
    },
    [loadDirectory],
  );

  const sendCommand = useCallback(
    (command: "quick-open" | "command-palette" | "sidebar" | "terminal") => {
      switch (command) {
        case "quick-open":
          setSearch("");
          setOverlay("quick-open");
          break;
        case "command-palette":
          setSearch("");
          setOverlay("command-palette");
          break;
        case "sidebar":
          setSidebarOpen(!useStudioStore.getState().sidebarOpen);
          break;
        case "terminal":
          setBottomOpen((open) => !open);
          setMobileView("terminal");
          break;
      }
    },
    [setSidebarOpen],
  );

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (!event.ctrlKey && !event.metaKey) return;
      const key = event.key.toLowerCase();
      if (key === "p" && event.shiftKey) {
        event.preventDefault();
        sendCommand("command-palette");
      } else if (key === "p") {
        event.preventDefault();
        sendCommand("quick-open");
      } else if (key === "b") {
        event.preventDefault();
        sendCommand("sidebar");
      } else if (key === "`") {
        event.preventDefault();
        sendCommand("terminal");
      } else if (key === "s") {
        event.preventDefault();
        if (activePath) void saveDocument(activePath);
      }
    };
    window.addEventListener("keydown", onKeyDown, true);
    return () => window.removeEventListener("keydown", onKeyDown, true);
  }, [activePath, saveDocument, sendCommand]);

  const changeBottomHeight = useCallback((event: ReactPointerEvent<HTMLDivElement>) => {
    event.preventDefault();
    const move = (pointerEvent: PointerEvent) => {
      setBottomHeight(Math.max(140, Math.min(window.innerHeight * 0.7, window.innerHeight - pointerEvent.clientY)));
    };
    const stop = () => {
      window.removeEventListener("pointermove", move);
      window.removeEventListener("pointerup", stop);
    };
    window.addEventListener("pointermove", move);
    window.addEventListener("pointerup", stop);
  }, []);

  const allFiles = useMemo(() => collectFiles(directories), [directories]);
  const activeDocument = tabs.find((tab) => tab.path === activePath) ?? null;
  const splitDocument = tabs.find((tab) => tab.path === splitPath) ?? null;
  const conflictedDocument = tabs.find((tab) => tab.path === saveConflictPath) ?? null;

  const chooseView = (view: View) => {
    setMobileView(view);
    if (view === "files") setSidebarOpen(true);
    if (view === "terminal") setBottomOpen(true);
  };
  const handleTouchStart = (event: React.TouchEvent<HTMLDivElement>) => {
    const touch = event.changedTouches[0];
    if (!touch) return;
    const edge = touch.clientX < 28 || touch.clientX > window.innerWidth - 28;
    touchStart.current = edge ? { x: touch.clientX, y: touch.clientY } : null;
  };
  const handleTouchEnd = (event: React.TouchEvent<HTMLDivElement>) => {
    const start = touchStart.current;
    touchStart.current = null;
    const touch = event.changedTouches[0];
    if (!compact || !start || !touch) return;
    const dx = touch.clientX - start.x;
    const dy = touch.clientY - start.y;
    if (Math.abs(dx) < 60 || Math.abs(dx) < Math.abs(dy) * 1.5) return;
    const currentIndex = NAV_ITEMS.findIndex((item) => item.id === mobileView);
    const direction = dx < 0 ? 1 : -1;
    const nextIndex = Math.max(0, Math.min(NAV_ITEMS.length - 1, currentIndex + direction));
    if (nextIndex !== currentIndex) chooseView(NAV_ITEMS[nextIndex]!.id);
  };

  return (
    <main className={`studio-shell ${compact ? "compact" : "expanded"}`}>
      <header className="workbench-header">
        <div className="brand">
          <span className="brand-mark">AVF</span>
          <div>
            <strong>AVF Studio</strong>
            <small>{workspaceRoot ? basename(workspaceRoot) : "No workspace open"}</small>
          </div>
        </div>
        <div className="connection-controls">
          <input
            aria-label="Daemon websocket URL"
            value={socketUrl}
            onChange={(event) => setSocketUrl(event.target.value)}
            disabled={connected}
            spellCheck={false}
          />
          <input
            aria-label="AVF authentication token"
            value={token}
            onChange={(event) => setToken(event.target.value)}
            type="password"
            autoComplete="off"
            placeholder="64-character VM token"
            disabled={connected}
          />
          {connected ? (
            <button className="button secondary" onClick={disconnect}>Disconnect</button>
          ) : (
            <button className="button primary" onClick={() => void connect()}>Connect</button>
          )}
        </div>
        <span className={`connection-badge ${connected ? "online" : ""}`}>
          <i />{connectionStatus}
        </span>
      </header>

      {!connected && (
        <section className="connect-notice">
          <strong>Connect to your Debian VM</strong>
          <span>Enter the daemon URL and token. The token is never persisted by this app.</span>
          {error && <p className="error-message" role="alert">{error}</p>}
        </section>
      )}

      <div
        className={`workbench ${compact ? `mobile-${mobileView}` : ""} ${!compact && mobileView === "preview" ? "desktop-preview" : ""}`}
        onTouchStart={handleTouchStart}
        onTouchEnd={handleTouchEnd}
      >
        {!compact && (
          <nav className="activity-bar" aria-label="Workbench activity">
            {NAV_ITEMS.map((item) => (
              <button
                key={item.id}
                className={mobileView === item.id ? "selected" : ""}
                title={item.label}
                aria-label={item.label}
                onClick={() => chooseView(item.id)}
              >
                {item.icon}
              </button>
            ))}
          </nav>
        )}

        <aside className={`sidebar ${compact ? "mobile-sidebar" : ""} ${!sidebarOpen && !compact ? "collapsed" : ""}`}>
          <div className="sidebar-heading">
            <span>EXPLORER</span>
            {!compact && <button className="icon-button" title="Close sidebar" onClick={() => setSidebarOpen(false)}>×</button>}
          </div>
          <Explorer
            root={workspaceRoot}
            connected={connected}
            busy={workspaceBusy}
            error={error}
            onLoadWorkspace={(path) => void loadWorkspace(path)}
            onToggleDirectory={(entry) => void toggleDirectory(entry)}
            onOpenFile={(entry) => void openFile(entry)}
          />
        </aside>

        <section className="workspace-main">
          <div className="editor-stage">
            <div className="editor-toolbar">
              <div className="editor-tabs">
                {tabs.map((tab) => (
                  <div key={tab.path} className={`editor-tab ${tab.path === activePath ? "active" : ""}`}>
                    <button onClick={() => setActivePath(tab.path)} title={tab.path}>
                      {tab.name}{tab.value !== tab.savedValue ? <span className="dirty-mark">●</span> : null}
                    </button>
                    <button
                      className="tab-close"
                      aria-label={`Close ${tab.name}`}
                      onClick={() => closeTab(tab.path)}
                    >
                      ×
                    </button>
                  </div>
                ))}
              </div>
              <div className="editor-actions">
                {tabs.length > 1 && !compact && (
                  <>
                    <button
                      className="icon-button"
                      title={splitPath ? "Close split view" : "Split editor"}
                      onClick={() => setSplitPath(splitPath ? null : tabs.find((tab) => tab.path !== activePath)?.path ?? null)}
                    >
                      ◫
                    </button>
                    {splitPath && (
                      <button
                        className="icon-button"
                        title={`Change to ${splitDirection === "horizontal" ? "vertical" : "horizontal"} split`}
                        onClick={() => setSplitDirection(splitDirection === "horizontal" ? "vertical" : "horizontal")}
                      >
                        ⇵
                      </button>
                    )}
                  </>
                )}
                <button className="icon-button" title="Save (Ctrl+S)" disabled={!activeDocument || activeDocument.value === activeDocument.savedValue} onClick={() => activePath && void saveDocument(activePath)}>
                  Save
                </button>
              </div>
            </div>
            {conflictedDocument && (
              <div className="conflict-banner" role="alert">
                <span>{conflictedDocument.name} changed on disk. Reload or explicitly overwrite.</span>
                <button onClick={() => void reloadDocument(conflictedDocument.path)}>Reload</button>
                <button className="danger-text" onClick={() => void saveDocument(conflictedDocument.path, true)}>Overwrite</button>
              </div>
            )}
            <div className={`editor-panes ${splitDocument ? `split ${splitDirection}` : ""}`}>
              {activeDocument ? (
                <MonacoPane
                  document={activeDocument}
                  compact={compact}
                  connection={connection}
                  workspaceRoot={workspaceRoot}
                  onError={setError}
                  onChange={(value) => updateDocument(activeDocument.path, value)}
                  onMount={(instance) => { activeEditor.current = instance; }}
                />
              ) : (
                <EmptyEditor connected={connected} />
              )}
              {splitDocument && (
                <MonacoPane
                  document={splitDocument}
                  compact={compact}
                  connection={connection}
                  workspaceRoot={workspaceRoot}
                  onError={setError}
                  onChange={(value) => updateDocument(splitDocument.path, value)}
                  onMount={(instance) => { activeEditor.current = instance; }}
                />
              )}
            </div>
          </div>

          <div className={`bottom-panel ${bottomOpen ? "" : "hidden"} ${compact ? "mobile-terminal-panel" : ""}`} style={{ "--panel-height": `${bottomHeight}px` } as CSSProperties}>
            {!compact && <div className="resize-handle" onPointerDown={changeBottomHeight} role="separator" aria-label="Resize terminal panel" />}
            <div className="panel-heading">
              <span>TERMINAL</span>
              <div>
                <button className="icon-button" title="Open terminal view" onClick={() => setMobileView("terminal")}>Open</button>
                <button className="icon-button" title="Hide terminal" onClick={() => setBottomOpen(false)}>×</button>
              </div>
            </div>
            <TerminalPane
              connection={connection}
              connected={connected}
              welcome={welcome}
              terminalStatus={terminalStatus}
              onStatus={setTerminalStatus}
              onError={setError}
            />
          </div>
        </section>

        <section className="preview-stage">
          <PreviewPanel ports={previewPorts} />
        </section>
      </div>

      {error && connected && (
        <div className="toast-error" role="alert">
          <span>{error}</span>
          <button aria-label="Dismiss error" onClick={() => setError("")}>×</button>
        </div>
      )}

      <footer className="status-bar">
        <span>{workspaceRoot || "No workspace"}</span>
        <span>{activeDocument ? `${activeDocument.language.toUpperCase()} · UTF-8` : "AVF Studio"}</span>
        <span>{vmSample || "VM telemetry waiting"}</span>
        <span>{latency === null ? "Ping —" : `${latency} ms`}</span>
        <button className="status-reconnect" onClick={() => void connect()} disabled={connected}>Wake / Reconnect</button>
      </footer>

      {compact && (
        <nav className="mobile-navigation" aria-label="Mobile navigation">
          {NAV_ITEMS.map((item) => (
            <button
              key={item.id}
              className={mobileView === item.id ? "selected" : ""}
              onClick={() => chooseView(item.id)}
            >
              <span>{item.icon}</span>
              <small>{item.label}</small>
            </button>
          ))}
        </nav>
      )}

      {compact && touchPrimary && (mobileView === "terminal" || mobileView === "editor") && (
        <TouchModifierBar onInput={(value) => {
          if (mobileView === "editor") {
            insertIntoEditor(activeEditor.current, value);
            return;
          }
          const session = readSessionId();
          if (session && connection.isReady()) {
            connection.sendPty(session, { Input: new TextEncoder().encode(value) });
          }
        }} />
      )}

      {overlay && (
        <div className="overlay-backdrop" onMouseDown={(event) => {
          if (event.target === event.currentTarget) setOverlay(null);
        }}>
          <section className="command-dialog" role="dialog" aria-modal="true">
            <input
              autoFocus
              value={search}
              placeholder={overlay === "quick-open" ? "Search files…" : "Type a command…"}
              onChange={(event) => setSearch(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === "Escape") setOverlay(null);
                if (event.key === "Enter") {
                  if (overlay === "quick-open") {
                    const entry = allFiles.find((file) => file.path.toLowerCase().includes(search.toLowerCase()));
                    if (entry) void openFile(entry);
                  } else {
                    runPaletteCommand(search, sendCommand, setSidebarOpen, saveDocument, activePath, setMobileView);
                  }
                  setOverlay(null);
                }
              }}
            />
            <div className="command-results">
              {overlay === "quick-open"
                ? allFiles
                    .filter((file) => file.path.toLowerCase().includes(search.toLowerCase()))
                    .slice(0, 12)
                    .map((file) => (
                      <button key={file.path} onClick={() => { void openFile(file); setOverlay(null); }}>
                        <span>{file.name}</span><small>{file.path}</small>
                      </button>
                    ))
                : ["Toggle Sidebar", "Toggle Terminal", "Save File", "Show Files", "Show Editor", "Show Preview / Git"]
                    .filter((command) => command.toLowerCase().includes(search.toLowerCase()))
                    .map((command) => (
                      <button key={command} onClick={() => {
                        runPaletteCommand(command, sendCommand, setSidebarOpen, saveDocument, activePath, setMobileView);
                        setOverlay(null);
                      }}>
                        {command}
                      </button>
                    ))}
            </div>
          </section>
        </div>
      )}
    </main>
  );
}

function runPaletteCommand(
  command: string,
  sendCommand: (command: "quick-open" | "command-palette" | "sidebar" | "terminal") => void,
  setSidebarOpen: (open: boolean) => void,
  saveDocument: (path: string) => Promise<void>,
  activePath: string | null,
  setMobileView: (view: View) => void,
) {
  const normalized = command.toLowerCase();
  if (normalized.includes("sidebar")) sendCommand("sidebar");
  else if (normalized.includes("terminal")) sendCommand("terminal");
  else if (normalized.includes("save") && activePath) void saveDocument(activePath);
  else if (normalized.includes("files")) {
    setSidebarOpen(true);
    setMobileView("files");
  } else if (normalized.includes("editor")) setMobileView("editor");
  else if (normalized.includes("preview")) setMobileView("preview");
}

function Explorer({
  root,
  connected,
  busy,
  error,
  onLoadWorkspace,
  onToggleDirectory,
  onOpenFile,
}: {
  root: string;
  connected: boolean;
  busy: boolean;
  error: string;
  onLoadWorkspace: (path: string) => void;
  onToggleDirectory: (entry: WorkspaceEntry) => void;
  onOpenFile: (entry: WorkspaceEntry) => void;
}) {
  const [path, setPath] = useState(root);
  const directories = useStudioStore((state) => state.directories);
  const expanded = useStudioStore((state) => state.expanded);

  useEffect(() => setPath(root), [root]);

  return (
    <div className="explorer">
      <div className="workspace-path-form">
        <label htmlFor="workspace-path">Allowed project directory</label>
        <div className="workspace-path-row">
          <input
            id="workspace-path"
            value={path}
            onChange={(event) => setPath(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === "Enter" && connected) onLoadWorkspace(path);
            }}
            placeholder="/home/user/project"
            disabled={!connected}
          />
          <button disabled={!connected || busy || !path.trim()} onClick={() => onLoadWorkspace(path)}>
            {busy ? "…" : "Open"}
          </button>
        </div>
      </div>
      <div className="tree-root">
        {root && (
          <>
            <div className="tree-root-label" title={root}>⌂ {root}</div>
            <FileTree
              entries={directories[root] ?? []}
              directories={directories}
              expanded={expanded}
              depth={0}
              onToggle={onToggleDirectory}
              onOpen={onOpenFile}
            />
          </>
        )}
        {!connected && <p className="explorer-empty">Connect to browse an allowed workspace.</p>}
        {connected && !root && <p className="explorer-empty">Enter a project path configured in AVF_WORKSPACE_ROOTS.</p>}
      </div>
      {error && (
        <div className="explorer-error" role="status">
          {error}
        </div>
      )}
    </div>
  );
}

function FileTree({
  entries,
  directories,
  expanded,
  depth,
  onToggle,
  onOpen,
}: {
  entries: WorkspaceEntry[];
  directories: Record<string, WorkspaceEntry[]>;
  expanded: string[];
  depth: number;
  onToggle: (entry: WorkspaceEntry) => void;
  onOpen: (entry: WorkspaceEntry) => void;
}) {
  const sorted = [...entries].sort((a, b) => {
    if (a.is_dir !== b.is_dir) return a.is_dir ? -1 : 1;
    return a.name.localeCompare(b.name);
  });
  if (sorted.length === 0) return <div className="tree-empty">Empty directory</div>;
  return (
    <ul className="file-tree">
      {sorted.map((entry) => {
        const isExpanded = expanded.includes(entry.path);
        return (
          <li key={entry.path}>
            <button
              className={`tree-entry ${entry.is_dir ? "directory" : ""}`}
              style={{ "--depth": depth } as CSSProperties}
              title={entry.path}
              onClick={() => entry.is_dir ? onToggle(entry) : onOpen(entry)}
            >
              <span className="tree-chevron">{entry.is_dir ? (isExpanded ? "⌄" : "›") : ""}</span>
              <span>{entry.is_symlink ? "↗" : entry.is_dir ? (isExpanded ? "▾" : "▸") : "·"}</span>
              <span className="tree-name">{entry.name}</span>
            </button>
            {entry.is_dir && isExpanded && (
              <FileTree
                entries={directories[entry.path] ?? []}
                directories={directories}
                expanded={expanded}
                depth={depth + 1}
                onToggle={onToggle}
                onOpen={onOpen}
              />
            )}
          </li>
        );
      })}
    </ul>
  );
}

function MonacoPane({
  document,
  compact,
  connection,
  workspaceRoot,
  onError,
  onChange,
  onMount,
}: {
  document: EditorDocument;
  compact: boolean;
  connection: StudioConnection;
  workspaceRoot: string;
  onError: (message: string) => void;
  onChange: (value: string) => void;
  onMount: (instance: MonacoEditor.IStandaloneCodeEditor, monaco: MonacoApi) => void;
}) {
  const editorRef = useRef<MonacoEditor.IStandaloneCodeEditor | null>(null);
  const monacoRef = useRef<MonacoApi | null>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const [editorReady, setEditorReady] = useState(false);
  useEffect(() => {
    if (!containerRef.current) return;
    const observer = new ResizeObserver(() => editorRef.current?.layout());
    observer.observe(containerRef.current);
    return () => observer.disconnect();
  }, []);
  useEffect(() => {
    let disposed = false;
    let release: (() => void) | undefined;
    const model = editorRef.current?.getModel();
    const monaco = monacoRef.current;
    if (!model || !monaco || !workspaceRoot || !serverForLanguage(document.language)) return;
    void import("./lsp-client")
      .then(({ openLspDocument }) =>
        openLspDocument(
          connection,
          workspaceRoot,
          document.language,
          model,
          monaco,
          onError,
        ),
      )
      .then((dispose) => {
        if (disposed) dispose();
        else release = dispose;
      })
      .catch((cause: unknown) => {
        onError(cause instanceof Error ? cause.message : String(cause));
      });
    return () => {
      disposed = true;
      release?.();
    };
  }, [connection, document.language, document.path, editorReady, onError, workspaceRoot]);

  return (
    <div className="monaco-pane" ref={containerRef}>
      <Editor
        path={document.path}
        language={document.language}
        value={document.value}
        onChange={(value) => onChange(value ?? "")}
        onMount={(instance, monaco) => {
          editorRef.current = instance;
          monacoRef.current = monaco;
          setEditorReady(true);
          onMount(instance, monaco);
          instance.layout();
        }}
        options={{
          automaticLayout: true,
          minimap: { enabled: !compact },
          lineNumbersMinChars: compact ? 2 : 4,
          wordWrap: compact ? "on" : "off",
          fontSize: compact ? 13 : 14,
          scrollBeyondLastLine: false,
          padding: { top: 10 },
          readOnly: false,
        }}
        theme="vs-dark"
      />
    </div>
  );
}

function EmptyEditor({ connected }: { connected: boolean }) {
  return (
    <div className="empty-editor">
      <div className="empty-editor-mark">AVF</div>
      <h2>{connected ? "Open a file to get started" : "Connect to your VM"}</h2>
      <p>{connected ? "Choose a file from the Explorer to edit it in Monaco." : "The workbench keeps your workspace local to the Debian VM."}</p>
      <kbd>Ctrl + P</kbd><span>Quick Open</span>
    </div>
  );
}

function PreviewPanel({ ports }: { ports: PreviewPort[] }) {
  return (
    <div className="preview-placeholder">
      <div className="preview-icon">◇</div>
      <h2>Preview &amp; Git</h2>
      {ports.length > 0 ? (
        <ul className="preview-ports" aria-label="Detected guest ports">
          {ports.map(({ port, protocol, processName }) => (
            <li key={`${protocol}:${port}`}>
              <span>{processName ? `${processName} · ` : ""}{protocol === "Tcp6" ? "TCP6" : "TCP"} {port}</span>
              <a href={`http://127.0.0.1:${port}`} target="_blank" rel="noreferrer">Open</a>
            </li>
          ))}
        </ul>
      ) : (
        <p>No guest web ports detected. Preview and Git controls remain reserved for later phases.</p>
      )}
      <span className="coming-soon">Git controls are not part of the current phase.</span>
    </div>
  );
}

function TerminalPane({
  connection,
  connected,
  welcome,
  terminalStatus,
  onStatus,
  onError,
}: {
  connection: StudioConnection;
  connected: boolean;
  welcome: Welcome | null;
  terminalStatus: string;
  onStatus: (status: string) => void;
  onError: (error: string) => void;
}) {
  const hostRef = useRef<HTMLDivElement>(null);
  const terminal = useRef<Terminal | null>(null);
  const fitAddon = useRef<FitAddon | null>(null);
  const sessionId = useRef<string | null>(null);
  const [sessionLabel, setSessionLabel] = useState("No terminal session");

  useEffect(() => {
    if (!hostRef.current) return;
    const instance = new Terminal({
      cursorBlink: true,
      convertEol: false,
      fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace",
      fontSize: 14,
      scrollback: 5000,
      theme: {
        background: "#111827",
        foreground: "#e5e7eb",
        cursor: "#93c5fd",
      },
    });
    const fit = new FitAddon();
    instance.loadAddon(fit);
    instance.open(hostRef.current);
    terminal.current = instance;
    fitAddon.current = fit;
    const input = instance.onData((data) => {
      const session = sessionId.current;
      if (!session || !connection.isReady()) return;
      try {
        connection.sendPty(session, { Input: new TextEncoder().encode(data) });
      } catch (cause) {
        onError(cause instanceof Error ? cause.message : String(cause));
      }
    });
    const resize = instance.onResize(({ cols, rows }) => {
      const session = sessionId.current;
      if (!session || !connection.isReady()) return;
      try {
        connection.sendPty(session, { Resize: { cols, rows } });
      } catch (cause) {
        onError(cause instanceof Error ? cause.message : String(cause));
      }
    });
    const observer = new ResizeObserver(() => {
      if (hostRef.current && hostRef.current.clientWidth > 0 && hostRef.current.clientHeight > 0) {
        fit.fit();
      }
    });
    observer.observe(hostRef.current);
    const unlisten = connection.onPacket((packet) => {
      const variant = packetVariant(packet, "Pty");
      if (!variant || variant[1].session_id !== sessionId.current) return;
      if (variant[0] === "Output" && variant[1].data instanceof Uint8Array) {
        instance.write(variant[1].data);
        return;
      }
      const payload = record(variant[1].data);
      if (!payload) return;
      if (variant[0] === "Spawned") {
        onStatus("Terminal ready");
        if (hostRef.current?.clientWidth && hostRef.current.clientHeight) fit.fit();
      } else if (variant[0] === "Exit") {
        onStatus("Shell exited");
        sessionId.current = null;
        setSessionLabel("Shell exited");
        try {
          window.sessionStorage.removeItem("avf-studio-pty-session");
        } catch (cause) {
          onError(cause instanceof Error ? cause.message : String(cause));
        }
      } else if (variant[0] === "Error") {
        onError(String(payload.message ?? "Terminal operation failed."));
      }
    });

    return () => {
      unlisten();
      observer.disconnect();
      input.dispose();
      resize.dispose();
      instance.dispose();
      terminal.current = null;
      fitAddon.current = null;
    };
  }, [connection, onError, onStatus]);

  useEffect(() => {
    if (!connected || !welcome || !terminal.current) return;
    let previousSession: string | null = null;
    try {
      previousSession = window.sessionStorage.getItem("avf-studio-pty-session");
    } catch (cause) {
      onError(cause instanceof Error ? cause.message : String(cause));
    }
    const canReattach =
      previousSession !== null &&
      welcome.pty_sessions.some((session) => session.session_id === previousSession);
    const session = canReattach && previousSession ? previousSession : globalThis.crypto.randomUUID();
    sessionId.current = session;
    setSessionLabel(session);
    try {
      window.sessionStorage.setItem("avf-studio-pty-session", session);
      connection.sendPty(
        session,
        canReattach
          ? "Reattach"
          : {
              Spawn: {
                shell: null,
                cwd: null,
                cols: terminal.current.cols || 80,
                rows: terminal.current.rows || 24,
              },
            },
      );
      onStatus(canReattach ? "Terminal ready" : "Starting shell…");
    } catch (cause) {
      onError(cause instanceof Error ? cause.message : String(cause));
    }
  }, [connected, connection, onError, onStatus, welcome]);

  useEffect(() => {
    if (!connected) {
      sessionId.current = null;
      setSessionLabel("Disconnected; shell will be available to reattach");
      onStatus("Terminal disconnected");
      return;
    }
    const frame = window.requestAnimationFrame(() => {
      if (hostRef.current?.clientWidth && hostRef.current.clientHeight) {
        fitAddon.current?.fit();
      }
    });
    return () => window.cancelAnimationFrame(frame);
  }, [connected, onStatus]);

  const killShell = () => {
    const session = sessionId.current;
    if (!session || !connection.isReady()) return;
    try {
      connection.sendPty(session, "Kill");
    } catch (cause) {
      onError(cause instanceof Error ? cause.message : String(cause));
    }
  };

  const startShell = () => {
    if (!connection.isReady() || !terminal.current) return;
    const session = globalThis.crypto.randomUUID();
    try {
      window.sessionStorage.setItem("avf-studio-pty-session", session);
      sessionId.current = session;
      setSessionLabel(session);
      connection.sendPty(session, {
        Spawn: {
          shell: null,
          cwd: null,
          cols: terminal.current.cols || 80,
          rows: terminal.current.rows || 24,
        },
      });
      onStatus("Starting shell…");
    } catch (cause) {
      onError(cause instanceof Error ? cause.message : String(cause));
    }
  };

  return (
    <div className="terminal-content">
      <div className="terminal-toolbar">
        <span className="terminal-session-label">{terminalStatus} · {sessionLabel}</span>
        {sessionId.current ? (
          <button className="terminal-action" disabled={!connected} onClick={killShell}>Kill shell</button>
        ) : (
          <button className="terminal-action" disabled={!connected} onClick={startShell}>New shell</button>
        )}
      </div>
      <div className="terminal-body">
        <div className="terminal-host" ref={hostRef} />
      </div>
    </div>
  );
}

function TouchModifierBar({ onInput }: { onInput: (value: string) => void }) {
  const [control, setControl] = useState(false);
  const [alt, setAlt] = useState(false);
  const [bottom, setBottom] = useState(0);
  const trackpadOrigin = useRef<{ x: number; y: number } | null>(null);
  const trackpadLastMove = useRef(0);

  useEffect(() => {
    const viewport = window.visualViewport;
    if (!viewport) return;
    const update = () => setBottom(Math.max(0, window.innerHeight - viewport.height - viewport.offsetTop));
    viewport.addEventListener("resize", update);
    viewport.addEventListener("scroll", update);
    update();
    return () => {
      viewport.removeEventListener("resize", update);
      viewport.removeEventListener("scroll", update);
    };
  }, []);

  const send = (value: string) => {
    onInput(`${alt ? "\u001b" : ""}${value}`);
    setControl(false);
    setAlt(false);
  };
  const sendCharacter = (value: string) => {
    if (!control) {
      send(value);
      return;
    }
    const upper = value.toUpperCase();
    if (upper.length === 1 && upper >= "@" && upper <= "_") {
      send(String.fromCharCode(upper.charCodeAt(0) & 0x1f));
    } else {
      send(value);
    }
  };
  const sendRepeated = (value: string) => {
    let timer = 0;
    return {
      onPointerDown: (event: ReactPointerEvent<HTMLButtonElement>) => {
        event.preventDefault();
        send(value);
        timer = window.setTimeout(() => {
          timer = window.setInterval(() => send(value), 70);
        }, 350);
      },
      onPointerUp: () => {
        window.clearTimeout(timer);
        window.clearInterval(timer);
      },
      onPointerLeave: () => {
        window.clearTimeout(timer);
        window.clearInterval(timer);
      },
    };
  };
  const keys = ["ESC", "TAB", "C", "D", "Z", "{", "}", "(", ")", "[", "]", ":", ";", "/"];
  const values: Record<string, string> = {
    ESC: "\u001b",
    TAB: "\t",
  };
  return (
    <div className="touch-modifier-bar" style={{ bottom }}>
      {keys.map((key) => (
        <button key={key} onClick={() => sendCharacter(values[key] ?? key)}>{key}</button>
      ))}
      <button className={control ? "latched" : ""} onClick={() => setControl(!control)}>CTRL</button>
      <button className={alt ? "latched" : ""} onClick={() => setAlt(!alt)}>ALT</button>
      <button aria-label="Arrow left" {...sendRepeated("\u001b[D")}>←</button>
      <button aria-label="Arrow down" {...sendRepeated("\u001b[B")}>↓</button>
      <button aria-label="Arrow up" {...sendRepeated("\u001b[A")}>↑</button>
      <button aria-label="Arrow right" {...sendRepeated("\u001b[C")}>→</button>
      <div
        className="virtual-trackpad"
        role="button"
        tabIndex={0}
        aria-label="Virtual trackpad; swipe to send arrow keys"
        onPointerDown={(event) => {
          event.preventDefault();
          event.currentTarget.setPointerCapture(event.pointerId);
          trackpadOrigin.current = { x: event.clientX, y: event.clientY };
        }}
        onPointerMove={(event) => {
          const origin = trackpadOrigin.current;
          if (!origin || Date.now() - trackpadLastMove.current < 70) return;
          const dx = event.clientX - origin.x;
          const dy = event.clientY - origin.y;
          if (Math.max(Math.abs(dx), Math.abs(dy)) < 10) return;
          send(Math.abs(dx) > Math.abs(dy) ? (dx < 0 ? "\u001b[D" : "\u001b[C") : (dy < 0 ? "\u001b[A" : "\u001b[B"));
          trackpadLastMove.current = Date.now();
          trackpadOrigin.current = { x: event.clientX, y: event.clientY };
        }}
        onPointerUp={() => { trackpadOrigin.current = null; }}
        onPointerCancel={() => { trackpadOrigin.current = null; }}
      >
        Trackpad
      </div>
    </div>
  );
}

function insertIntoEditor(
  instance: MonacoEditor.IStandaloneCodeEditor | null,
  input: string,
) {
  if (!instance) return;
  const commands: Record<string, string> = {
    "\u001b[A": "cursorUp",
    "\u001b[B": "cursorDown",
    "\u001b[C": "cursorRight",
    "\u001b[D": "cursorLeft",
    "\u0001": "editor.action.selectAll",
    "\u0003": "editor.action.clipboardCopyAction",
    "\u0016": "editor.action.clipboardPasteAction",
    "\u001a": "undo",
  };
  if (input in commands) {
    instance.focus();
    instance.trigger("touch-modifier", commands[input]!, null);
    return;
  }
  if (input === "\u001b") {
    instance.focus();
    instance.trigger("touch-modifier", "closeFindWidget", null);
    return;
  }
  if (input === "\t") {
    instance.focus();
    instance.trigger("touch-modifier", "tab", null);
    return;
  }
  const text = input.startsWith("\u001b") ? input.slice(1) : input;
  const selection = instance.getSelection();
  if (!selection || text.length === 0) return;
  instance.executeEdits("touch-modifier", [{ range: selection, text, forceMoveMarkers: true }]);
  instance.focus();
}

function readSessionId(): string | null {
  try {
    return window.sessionStorage.getItem("avf-studio-pty-session");
  } catch {
    return null;
  }
}

export default App;
