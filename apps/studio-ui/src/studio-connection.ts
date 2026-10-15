import { decode, encode } from "@msgpack/msgpack";
import type { WorkspaceEntry } from "./studio-store";

type Packet = Record<string, unknown>;
type Listener = (packet: Packet) => void;

export type Welcome = {
  pty_sessions: Array<{
    session_id: string;
    shell: string;
    cwd: string;
    cols: number;
    rows: number;
  }>;
};

export type FsEvent = {
  kind: string;
  paths: string[];
};

export class VfsRequestError extends Error {
  constructor(
    message: string,
    readonly code: string,
  ) {
    super(message);
    this.name = "VfsRequestError";
  }
}

function record(value: unknown): Packet | null {
  return typeof value === "object" && value !== null && !Array.isArray(value)
    ? (value as Packet)
    : null;
}

function unwrapVariant(value: unknown): [string, unknown] | null {
  const variants = record(value);
  if (!variants) return null;
  const [name, payload] = Object.entries(variants)[0] ?? [];
  return name ? [name, payload] : null;
}

type PendingRequest = {
  resolve: (value: Packet) => void;
  reject: (error: Error) => void;
  timer: number;
};

export class StudioConnection {
  private socket: WebSocket | null = null;
  private ready = false;
  private requestId = 0;
  private readonly pending = new Map<number, PendingRequest>();
  private readonly listeners = new Set<Listener>();

  onPacket(listener: Listener): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  isReady(): boolean {
    return this.ready && this.socket?.readyState === WebSocket.OPEN;
  }

  connect(url: string, token: string): Promise<Welcome> {
    if (this.socket && this.socket.readyState < WebSocket.CLOSING) {
      this.socket.close();
    }
    this.ready = false;
    const socket = new WebSocket(url);
    socket.binaryType = "arraybuffer";
    this.socket = socket;

    return new Promise<Welcome>((resolve, reject) => {
      let settled = false;
      const handshakeTimer = window.setTimeout(() => {
        if (!settled) {
          settled = true;
          socket.close();
          reject(new Error("Daemon authentication timed out."));
        }
      }, 6000);
      const fail = (error: Error) => {
        if (settled) return;
        settled = true;
        window.clearTimeout(handshakeTimer);
        reject(error);
      };

      socket.onopen = () => {
        socket.send(
          encode({
            System: {
              Hello: {
                protocol_version: 1,
                auth_token: token,
                client_name: "avf-studio",
              },
            },
          }),
        );
      };
      socket.onmessage = (event: MessageEvent<ArrayBuffer>) => {
        try {
          if (!(event.data instanceof ArrayBuffer)) {
            throw new Error("Daemon sent a non-binary websocket frame.");
          }
          const packet = record(decode(new Uint8Array(event.data)));
          if (!packet) throw new Error("Daemon sent an invalid protocol packet.");
          const system = unwrapVariant(packet.System);
          if (system?.[0] === "Welcome") {
            this.ready = true;
            if (!settled) {
              settled = true;
              window.clearTimeout(handshakeTimer);
              resolve(system[1] as Welcome);
            }
          } else if (system?.[0] === "Rejected") {
            const rejection = record(system[1]);
            fail(new Error(`Daemon rejected the connection: ${JSON.stringify(rejection?.reason ?? rejection)}`));
            socket.close();
          } else if (system?.[0] === "ProtocolError") {
            const detail = record(system[1]);
            throw new Error(String(detail?.message ?? "Daemon protocol error."));
          }
          this.dispatch(packet);
        } catch (cause) {
          const error = cause instanceof Error ? cause : new Error(String(cause));
          fail(error);
          for (const listener of this.listeners) {
            listener({ ClientError: { message: error.message } });
          }
        }
      };
      socket.onerror = () => fail(new Error("Websocket connection failed."));
      socket.onclose = () => {
        if (this.socket !== socket) {
          fail(new Error("Websocket connection closed."));
          return;
        }
        this.ready = false;
        this.socket = null;
        const error = new Error("Websocket connection closed.");
        for (const [, request] of this.pending) {
          window.clearTimeout(request.timer);
          request.reject(error);
        }
        this.pending.clear();
        fail(error);
        for (const listener of this.listeners) listener({ Disconnected: {} });
      };
    });
  }

  close(): void {
    this.ready = false;
    this.socket?.close();
    this.socket = null;
    const error = new Error("Websocket connection closed.");
    for (const [, request] of this.pending) {
      window.clearTimeout(request.timer);
      request.reject(error);
    }
    this.pending.clear();
  }

  send(packet: unknown): void {
    if (!this.isReady()) throw new Error("Daemon is not connected.");
    this.socket!.send(encode(packet));
  }

  async requestVfs<T extends Packet>(
    variant: string,
    fields: Packet,
  ): Promise<T> {
    if (!this.isReady()) throw new Error("Daemon is not connected.");
    const reqId = ++this.requestId;
    const response = new Promise<Packet>((resolve, reject) => {
      const timer = window.setTimeout(() => {
        this.pending.delete(reqId);
        reject(new Error(`VFS request ${variant} timed out.`));
      }, 15000);
      this.pending.set(reqId, { resolve, reject, timer });
    });
    try {
      this.send({ Vfs: { [variant]: { req_id: reqId, ...fields } } });
    } catch (cause) {
      const pending = this.pending.get(reqId);
      if (pending) {
        window.clearTimeout(pending.timer);
        this.pending.delete(reqId);
      }
      throw cause;
    }
    const result = await response;
    const error = record(result.VfsError);
    if (error) {
      throw new VfsRequestError(
        String(error.message ?? "VFS request failed."),
        String(error.code ?? "Io"),
      );
    }
    return result as T;
  }

  ping(timestamp: number): void {
    this.send({ System: { Ping: { timestamp } } });
  }

  sendPty(sessionId: string, data: unknown): void {
    this.send({ Pty: { session_id: sessionId, data } });
  }

  private dispatch(packet: Packet): void {
    const vfsVariant = unwrapVariant(packet.Vfs);
    if (vfsVariant) {
      const payload = record(vfsVariant[1]);
      const reqId = payload?.req_id;
      if (typeof reqId === "number") {
        const pending = this.pending.get(reqId);
        if (pending) {
          window.clearTimeout(pending.timer);
          this.pending.delete(reqId);
          pending.resolve({ [vfsVariant[0]]: payload });
        }
      }
    }
    for (const listener of this.listeners) listener(packet);
  }
}

export function asFsEntries(value: unknown): WorkspaceEntry[] {
  if (!Array.isArray(value)) throw new Error("Daemon returned an invalid directory listing.");
  return value.map((entry) => {
    const item = record(entry);
    if (
      typeof item?.name !== "string" ||
      typeof item.path !== "string" ||
      typeof item.is_dir !== "boolean"
    ) {
      throw new Error("Daemon returned an invalid file entry.");
    }
    return {
      name: item.name,
      path: item.path,
      is_dir: item.is_dir,
      is_symlink: item.is_symlink === true,
      size: typeof item.size === "number" ? item.size : 0,
      modified_ts: typeof item.modified_ts === "number" ? item.modified_ts : 0,
    };
  });
}

export function packetVariant(packet: Packet, group: "Pty" | "System"): [string, Packet] | null {
  const envelope = record(packet[group]);
  if (!envelope) return null;
  if (group === "Pty") {
    const data = unwrapVariant(envelope.data);
    return data ? [data[0], { ...envelope, data: data[1] }] : null;
  }
  const data = unwrapVariant(envelope);
  return data ? [data[0], record(data[1]) ?? {}] : null;
}

export function packetFsEvent(packet: Packet): FsEvent | null {
  const variant = unwrapVariant(packet.Vfs);
  if (variant?.[0] !== "FsEvent") return null;
  const payload = record(variant[1]);
  if (typeof payload?.kind !== "string" || !Array.isArray(payload.paths)) return null;
  return {
    kind: payload.kind,
    paths: payload.paths.filter((path): path is string => typeof path === "string"),
  };
}
