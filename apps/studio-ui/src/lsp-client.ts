import {
  AbstractMessageReader,
  AbstractMessageWriter,
  type DataCallback,
  type Message,
} from "vscode-jsonrpc";
import { MonacoLanguageClient } from "monaco-languageclient";
import type {
  IDisposable,
  editor as MonacoEditor,
  IMarkdownString,
  IRange,
  languages as MonacoLanguages,
  Position,
} from "monaco-editor";
import { StudioConnection } from "./studio-connection";
import { serverForLanguage, type ServerId } from "./lsp-routing";

type MonacoApi = typeof import("monaco-editor");

const managers = new WeakMap<StudioConnection, LspClientManager>();

function asRecord(value: unknown): Record<string, unknown> | null {
  return typeof value === "object" && value !== null && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : null;
}

function isMessage(value: unknown): value is Message {
  const message = asRecord(value);
  if (message?.jsonrpc !== "2.0") return false;
  return typeof message.method === "string" ||
    typeof message.id === "string" ||
    typeof message.id === "number" ||
    message.id === null;
}

class LspPacketReader extends AbstractMessageReader {
  private callback: DataCallback | null = null;
  private unlisten: (() => void) | null = null;

  constructor(
    private readonly connection: StudioConnection,
    private readonly serverId: ServerId,
    private readonly generation: number,
  ) {
    super();
  }

  listen(callback: DataCallback) {
    if (this.callback) throw new Error("LSP packet reader is already listening.");
    this.callback = callback;
    this.unlisten = this.connection.onPacket((packet) => {
      if (this.connection.getGeneration() !== this.generation) return;
      if ("Disconnected" in packet) {
        this.fireClose();
        return;
      }
      if ("ClientError" in packet) {
        const error = asRecord(packet.ClientError);
        this.fireError(new Error(String(error?.message ?? "LSP websocket error.")));
        return;
      }
      const envelope = asRecord(packet.Lsp);
      if (envelope?.server_id !== this.serverId || typeof envelope.json_rpc !== "string") {
        return;
      }
      try {
        const message: unknown = JSON.parse(envelope.json_rpc);
        if (!isMessage(message)) throw new Error("Language server sent invalid JSON-RPC.");
        this.callback?.(message);
      } catch (cause) {
        this.fireError(cause instanceof Error ? cause : new Error(String(cause)));
      }
    });
    return {
      dispose: () => {
        this.unlisten?.();
        this.unlisten = null;
        this.callback = null;
      },
    };
  }

  override dispose(): void {
    this.unlisten?.();
    this.unlisten = null;
    this.callback = null;
    super.dispose();
  }
}

class LspPacketWriter extends AbstractMessageWriter {
  constructor(
    private readonly connection: StudioConnection,
    private readonly serverId: ServerId,
    private readonly generation: number,
  ) {
    super();
  }

  async write(message: Message): Promise<void> {
    try {
      this.connection.sendLsp(this.serverId, JSON.stringify(message), this.generation);
    } catch (cause) {
      const error = cause instanceof Error ? cause : new Error(String(cause));
      this.fireError(error, message);
      throw error;
    }
  }

  end(): void {
    this.fireClose();
  }
}

type ActiveLspDocument = {
  serverId: ServerId;
  language: string;
  workspaceRoot: string;
  model: MonacoEditor.ITextModel;
  monaco: MonacoApi;
  references: number;
  version: number;
  generation: number;
  attaching: { generation: number; promise: Promise<void> } | null;
  changeTimer: number | null;
  changeListener: IDisposable;
};

class LspClientManager {
  private readonly clients = new Map<ServerId, Promise<MonacoLanguageClient>>();
  private readonly providers = new Set<string>();
  private readonly documents = new Map<string, ActiveLspDocument>();

  constructor(
    private readonly connection: StudioConnection,
    private readonly reportError: (message: string) => void,
  ) {
    connection.onPacket((packet) => {
      if ("Disconnected" in packet) {
        this.clients.clear();
        for (const document of this.documents.values()) {
          document.generation = 0;
          if (document.changeTimer !== null) {
            window.clearTimeout(document.changeTimer);
            document.changeTimer = null;
          }
        }
        return;
      }
      const system = asRecord(packet.System);
      if (system && "Welcome" in system) {
        for (const document of this.documents.values()) {
          void this.attachDocument(document).catch((cause: unknown) => {
            this.reportError(errorMessage(cause));
          });
        }
      }
    });
  }

  async openDocument(
    serverId: ServerId,
    language: string,
    workspaceRoot: string,
    model: MonacoEditor.ITextModel,
    monaco: MonacoApi,
  ): Promise<() => void> {
    const uri = model.uri.toString();
    const existing = this.documents.get(uri);
    if (existing) {
      existing.references += 1;
      return () => this.releaseDocument(uri);
    }
    const document: ActiveLspDocument = {
      serverId,
      language,
      workspaceRoot,
      model,
      monaco,
      references: 1,
      version: 0,
      generation: 0,
      attaching: null,
      changeTimer: null,
      changeListener: { dispose: () => undefined },
    };
    document.changeListener = model.onDidChangeContent(() => this.documentChanged(uri));
    this.documents.set(uri, document);
    try {
      await this.attachDocument(document);
      return () => this.releaseDocument(uri);
    } catch (cause) {
      this.documents.delete(uri);
      document.changeListener.dispose();
      throw cause;
    }
  }

  private getClient(
    serverId: ServerId,
    workspaceRoot: string,
    monaco: MonacoApi,
  ): Promise<MonacoLanguageClient> {
    const existing = this.clients.get(serverId);
    if (existing) return existing;
    const generation = this.connection.getGeneration();
    const reader = new LspPacketReader(this.connection, serverId, generation);
    const writer = new LspPacketWriter(this.connection, serverId, generation);
    const client = new MonacoLanguageClient({
      id: `avf-${serverId}`,
      name: `AVF ${serverId}`,
      clientOptions: {
        documentSelector: [],
        workspaceFolder: {
          index: 0,
          name: workspaceRoot.split("/").filter(Boolean).at(-1) ?? workspaceRoot,
          uri: monaco.Uri.file(workspaceRoot),
        },
      },
      messageTransports: { reader, writer },
    });
    client.onNotification("textDocument/publishDiagnostics", (params: unknown) => {
      this.publishDiagnostics(serverId, monaco, params);
    });

    const starting = client.start().then(() => {
      return client;
    }).catch(async (cause: unknown) => {
      if (this.clients.get(serverId) === starting) this.clients.delete(serverId);
      await client.dispose().catch((disposeCause: unknown) => {
        this.reportError(errorMessage(disposeCause));
      });
      throw cause;
    });
    this.clients.set(serverId, starting);
    return starting;
  }

  private registerProviders(
    serverId: ServerId,
    language: string,
    workspaceRoot: string,
    monaco: MonacoApi,
  ): void {
    const providerKey = `${serverId}:${language}`;
    if (this.providers.has(providerKey)) return;
    this.providers.add(providerKey);
    const position = (model: MonacoEditor.ITextModel, pos: Position) => ({
      textDocument: { uri: model.uri.toString() },
      position: { line: pos.lineNumber - 1, character: pos.column - 1 },
    });

    monaco.languages.registerCompletionItemProvider(language, {
      provideCompletionItems: async (model, pos) => {
        const client = await this.getClient(serverId, workspaceRoot, monaco);
        const result: unknown = await client.sendRequest("textDocument/completion", position(model, pos));
        const response = asRecord(result);
        const items = Array.isArray(result) ? result : response?.items;
        if (!Array.isArray(items)) return { suggestions: [] };
        const word = model.getWordUntilPosition(pos);
        const range = {
          startLineNumber: pos.lineNumber,
          endLineNumber: pos.lineNumber,
          startColumn: word.startColumn,
          endColumn: pos.column,
        };
        return {
          suggestions: items
            .map((item) => completionItem(item, monaco, range))
            .filter((item): item is MonacoLanguages.CompletionItem => item !== null),
        };
      },
    });
    monaco.languages.registerHoverProvider(language, {
      provideHover: async (model, pos) => {
        const client = await this.getClient(serverId, workspaceRoot, monaco);
        const result: unknown = await client.sendRequest("textDocument/hover", position(model, pos));
        const hover = asRecord(result);
        const contents = markdownContents(hover?.contents);
        return contents.length > 0 ? { contents } : null;
      },
    });
  }

  private async attachDocument(document: ActiveLspDocument): Promise<void> {
    const generation = this.connection.getGeneration();
    if (document.generation === generation && this.connection.isReady()) return;
    if (document.attaching?.generation === generation) return document.attaching.promise;
    const attaching = (async () => {
      if (!this.connection.isReady()) throw new Error("Connect to the daemon before starting language services.");
      const client = await this.getClient(
        document.serverId,
        document.workspaceRoot,
        document.monaco,
      );
      this.registerProviders(
        document.serverId,
        document.language,
        document.workspaceRoot,
        document.monaco,
      );
      if (generation !== this.connection.getGeneration() || !this.connection.isReady()) {
        throw new Error("Daemon connection changed while starting a language server.");
      }
      document.version += 1;
      await client.sendNotification("textDocument/didOpen", {
        textDocument: {
          uri: document.model.uri.toString(),
          languageId: document.language,
          version: document.version,
          text: document.model.getValue(),
        },
      });
      document.generation = generation;
    })();
    document.attaching = { generation, promise: attaching };
    try {
      await attaching;
    } finally {
      if (document.attaching?.promise === attaching) document.attaching = null;
    }
  }

  private documentChanged(uri: string): void {
    const document = this.documents.get(uri);
    if (!document) return;
    document.version += 1;
    if (document.changeTimer !== null) window.clearTimeout(document.changeTimer);
    document.changeTimer = window.setTimeout(() => {
      document.changeTimer = null;
      const generation = document.generation;
      const clientPromise = this.clients.get(document.serverId);
      if (
        !clientPromise ||
        !this.connection.isReady() ||
        generation !== this.connection.getGeneration()
      ) {
        return;
      }
      void clientPromise
        .then((client) =>
          client.sendNotification("textDocument/didChange", {
            textDocument: {
              uri: document.model.uri.toString(),
              version: document.version,
            },
            contentChanges: [{ text: document.model.getValue() }],
          }),
        )
        .catch((cause: unknown) => this.reportError(errorMessage(cause)));
    }, 120);
  }

  private releaseDocument(uri: string): void {
    const document = this.documents.get(uri);
    if (!document) return;
    document.references -= 1;
    if (document.references > 0) return;
    this.documents.delete(uri);
    if (document.changeTimer !== null) window.clearTimeout(document.changeTimer);
    document.changeListener.dispose();
    const generation = document.generation;
    const clientPromise = this.clients.get(document.serverId);
    if (
      clientPromise &&
      this.connection.isReady() &&
      generation === this.connection.getGeneration()
    ) {
      void clientPromise
        .then((client) =>
          client.sendNotification("textDocument/didClose", {
            textDocument: { uri: document.model.uri.toString() },
          }),
        )
        .catch((cause: unknown) => this.reportError(errorMessage(cause)));
    }
    document.monaco.editor.setModelMarkers(
      document.model,
      `avf-lsp-${document.serverId}`,
      [],
    );
  }

  private publishDiagnostics(serverId: ServerId, monaco: MonacoApi, value: unknown): void {
    const params = asRecord(value);
    if (typeof params?.uri !== "string" || !Array.isArray(params.diagnostics)) return;
    const model = monaco.editor.getModel(monaco.Uri.parse(params.uri));
    if (!model) return;
    const markers = params.diagnostics
      .map((value) => diagnosticMarker(value, monaco))
      .filter((marker): marker is MonacoEditor.IMarkerData => marker !== null);
    monaco.editor.setModelMarkers(model, `avf-lsp-${serverId}`, markers);
  }
}

function completionItem(
  value: unknown,
  monaco: MonacoApi,
  range: IRange,
): MonacoLanguages.CompletionItem | null {
  const item = asRecord(value);
  if (typeof item?.label !== "string") return null;
  const insertText =
    typeof item.insertText === "string" ? item.insertText : item.label;
  const newText = asRecord(item.textEdit)?.newText;
  const kind = typeof item.kind === "number" ? item.kind : 1;
  const completion: MonacoLanguages.CompletionItem = {
    label: item.label,
    kind: completionKind(kind, monaco),
    insertText: typeof newText === "string" ? newText : insertText,
    range,
  };
  if (typeof item.detail === "string") completion.detail = item.detail;
  const docs = markup(item.documentation);
  if (docs) completion.documentation = docs;
  if (typeof item.sortText === "string") completion.sortText = item.sortText;
  if (typeof item.filterText === "string") completion.filterText = item.filterText;
  if (item.insertTextFormat === 2) {
    completion.insertTextRules = monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet;
  }
  return completion;
}

function completionKind(kind: number, monaco: MonacoApi): MonacoLanguages.CompletionItemKind {
  const kinds: Record<number, MonacoLanguages.CompletionItemKind> = {
    1: monaco.languages.CompletionItemKind.Text,
    2: monaco.languages.CompletionItemKind.Method,
    3: monaco.languages.CompletionItemKind.Function,
    4: monaco.languages.CompletionItemKind.Constructor,
    5: monaco.languages.CompletionItemKind.Field,
    6: monaco.languages.CompletionItemKind.Variable,
    7: monaco.languages.CompletionItemKind.Class,
    8: monaco.languages.CompletionItemKind.Interface,
    9: monaco.languages.CompletionItemKind.Module,
    10: monaco.languages.CompletionItemKind.Property,
    11: monaco.languages.CompletionItemKind.Unit,
    12: monaco.languages.CompletionItemKind.Value,
    13: monaco.languages.CompletionItemKind.Enum,
    14: monaco.languages.CompletionItemKind.Keyword,
    15: monaco.languages.CompletionItemKind.Snippet,
    16: monaco.languages.CompletionItemKind.Color,
    17: monaco.languages.CompletionItemKind.File,
    18: monaco.languages.CompletionItemKind.Reference,
    19: monaco.languages.CompletionItemKind.Folder,
    20: monaco.languages.CompletionItemKind.EnumMember,
    21: monaco.languages.CompletionItemKind.Constant,
    22: monaco.languages.CompletionItemKind.Struct,
    23: monaco.languages.CompletionItemKind.Event,
    24: monaco.languages.CompletionItemKind.Operator,
    25: monaco.languages.CompletionItemKind.TypeParameter,
  };
  return kinds[kind] ?? monaco.languages.CompletionItemKind.Text;
}

function markup(value: unknown): string | IMarkdownString | undefined {
  if (typeof value === "string") return value;
  const object = asRecord(value);
  if (typeof object?.value === "string") return { value: object.value };
  return undefined;
}

function markdownContents(value: unknown): MonacoLanguages.Hover["contents"] {
  const items = Array.isArray(value) ? value : [value];
  return items.flatMap((item) => {
    if (typeof item === "string") return [{ value: item }];
    const object = asRecord(item);
    if (typeof object?.value !== "string") return [];
    if (typeof object.language === "string") {
      return [{ language: object.language, value: object.value }];
    }
    return [{ value: object.value }];
  });
}

function diagnosticMarker(
  value: unknown,
  monaco: MonacoApi,
): MonacoEditor.IMarkerData | null {
  const diagnostic = asRecord(value);
  const range = asRecord(diagnostic?.range);
  const start = asRecord(range?.start);
  const end = asRecord(range?.end);
  if (
    typeof diagnostic?.message !== "string" ||
    typeof start?.line !== "number" ||
    typeof start.character !== "number" ||
    typeof end?.line !== "number" ||
    typeof end.character !== "number"
  ) {
    return null;
  }
  const severities: Record<number, number> = {
    1: monaco.MarkerSeverity.Error,
    2: monaco.MarkerSeverity.Warning,
    3: monaco.MarkerSeverity.Info,
    4: monaco.MarkerSeverity.Hint,
  };
  const marker: MonacoEditor.IMarkerData = {
    message: diagnostic.message,
    severity:
      typeof diagnostic.severity === "number"
        ? severities[diagnostic.severity] ?? monaco.MarkerSeverity.Info
        : monaco.MarkerSeverity.Error,
    startLineNumber: start.line + 1,
    startColumn: start.character + 1,
    endLineNumber: end.line + 1,
    endColumn: end.character + 1,
  };
  if (typeof diagnostic.source === "string") marker.source = diagnostic.source;
  if (typeof diagnostic.code === "string" || typeof diagnostic.code === "number") {
    marker.code = String(diagnostic.code);
  }
  return marker;
}

function errorMessage(cause: unknown): string {
  return cause instanceof Error ? cause.message : String(cause);
}

export async function openLspDocument(
  connection: StudioConnection,
  workspaceRoot: string,
  language: string,
  model: MonacoEditor.ITextModel,
  monaco: MonacoApi,
  onError: (message: string) => void,
): Promise<() => void> {
  const serverId = serverForLanguage(language);
  if (!serverId) return () => undefined;
  let manager = managers.get(connection);
  if (!manager) {
    manager = new LspClientManager(connection, onError);
    managers.set(connection, manager);
  }
  return manager.openDocument(serverId, language, workspaceRoot, model, monaco);
}
