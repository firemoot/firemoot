import { type Handler, TypedEmitter } from "./emitter.js";
import { ClientFrames, decodeFrame, type HelloFrame, type ServerFrame } from "./events.js";

/** A minimal WebSocket abstraction so a fake transport can be injected in tests. */
export interface Socket {
  send(data: string): void;
  close(): void;
  onopen: (() => void) | null;
  onmessage: ((data: string) => void) | null;
  onclose: (() => void) | null;
  onerror: ((error: unknown) => void) | null;
}

export type SocketFactory = (url: string) => Socket;

/** Wraps the platform `WebSocket` (global in browsers and Node 22+). */
export const defaultSocketFactory: SocketFactory = (url) => {
  const ws = new WebSocket(url);
  const socket: Socket = {
    send: (data) => ws.send(data),
    close: () => ws.close(),
    onopen: null,
    onmessage: null,
    onclose: null,
    onerror: null,
  };
  ws.onopen = () => socket.onopen?.();
  ws.onmessage = (event: MessageEvent) =>
    socket.onmessage?.(typeof event.data === "string" ? event.data : String(event.data));
  ws.onclose = () => socket.onclose?.();
  ws.onerror = (event) => socket.onerror?.(event);
  return socket;
};

export interface ReconnectPolicy {
  baseDelayMs: number;
  maxDelayMs: number;
  maxRetries: number;
}

const DEFAULT_POLICY: ReconnectPolicy = {
  baseDelayMs: 500,
  maxDelayMs: 15_000,
  maxRetries: Number.POSITIVE_INFINITY,
};

export interface ConnectionConfig {
  /** Resolves the connect URL (incl. a fresh token) on every attempt. */
  urlProvider: () => string | Promise<string>;
  /** The current `{ cid: lastSeq }` map to resubscribe on every (re)connect. */
  subscriptions: () => Record<string, number>;
  socketFactory?: SocketFactory;
  reconnect?: Partial<ReconnectPolicy>;
  /** Jitter source in [0,1); injectable for deterministic tests. */
  random?: () => number;
}

export type ConnectionStatus = "idle" | "connecting" | "connected" | "reconnecting" | "closed";

export interface ConnectionEvents {
  connected: { connectionId: string; hello: HelloFrame };
  disconnected: { willReconnect: boolean };
  reconnecting: { attempt: number; delayMs: number };
  frame: ServerFrame;
  error: { error: unknown };
  status: ConnectionStatus;
}

/**
 * The WebSocket connection lifecycle (SPEC.md §5): connect, wait for `hello`,
 * resubscribe watched channels with their resume seq, and on an unexpected close
 * reconnect with exponential backoff + jitter, refreshing the URL/token each
 * attempt. The transport is injectable so the whole FSM is unit-testable without
 * a real socket.
 */
export class Connection {
  private readonly emitter = new TypedEmitter<ConnectionEvents>();
  private readonly factory: SocketFactory;
  private readonly policy: ReconnectPolicy;
  private readonly random: () => number;

  private socket: Socket | null = null;
  private currentStatus: ConnectionStatus = "idle";
  private attempt = 0;
  private userClosed = false;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private settle: { resolve: () => void; reject: (error: unknown) => void } | null = null;

  constructor(private readonly config: ConnectionConfig) {
    this.factory = config.socketFactory ?? defaultSocketFactory;
    this.policy = { ...DEFAULT_POLICY, ...config.reconnect };
    this.random = config.random ?? Math.random;
  }

  get status(): ConnectionStatus {
    return this.currentStatus;
  }

  on<K extends keyof ConnectionEvents>(type: K, handler: Handler<ConnectionEvents[K]>): () => void {
    return this.emitter.on(type, handler);
  }

  off<K extends keyof ConnectionEvents>(type: K, handler: Handler<ConnectionEvents[K]>): void {
    this.emitter.off(type, handler);
  }

  /** Opens the connection, resolving on the first `hello` (rejecting if retries are exhausted). */
  connect(): Promise<void> {
    if (this.currentStatus === "connected") return Promise.resolve();
    this.userClosed = false;
    this.attempt = 0;
    const promise = new Promise<void>((resolve, reject) => {
      this.settle = { resolve, reject };
    });
    void this.openSocket();
    return promise;
  }

  /** Sends a raw frame; returns false if not currently connected. */
  send(data: string): boolean {
    if (this.currentStatus !== "connected" || !this.socket) return false;
    this.socket.send(data);
    return true;
  }

  /** Closes intentionally; no reconnect follows. */
  close(): void {
    this.userClosed = true;
    if (this.reconnectTimer !== null) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    const socket = this.socket;
    this.socket = null;
    socket?.close();
    this.setStatus("closed");
    this.reject(new Error("connection closed"));
  }

  private setStatus(status: ConnectionStatus): void {
    if (this.currentStatus === status) return;
    this.currentStatus = status;
    this.emitter.emit("status", status);
  }

  private async openSocket(): Promise<void> {
    this.setStatus(this.attempt === 0 ? "connecting" : "reconnecting");
    let url: string;
    try {
      url = await Promise.resolve(this.config.urlProvider());
    } catch (error) {
      this.emitter.emit("error", { error });
      this.onClose();
      return;
    }
    if (this.userClosed) return;
    const socket = this.factory(url);
    this.socket = socket;
    socket.onopen = null;
    socket.onmessage = (raw) => this.onMessage(raw);
    socket.onclose = () => this.onClose();
    socket.onerror = (error) => this.emitter.emit("error", { error });
  }

  private onMessage(raw: string): void {
    const frame = decodeFrame(raw);
    if (frame === null) return;
    if (frame.type === "hello") {
      this.attempt = 0;
      this.setStatus("connected");
      this.resubscribe();
      this.emitter.emit("connected", { connectionId: frame.connectionId, hello: frame });
      this.resolve();
      return;
    }
    this.emitter.emit("frame", frame);
  }

  private resubscribe(): void {
    const subscriptions = this.config.subscriptions();
    if (Object.keys(subscriptions).length > 0) {
      this.socket?.send(ClientFrames.subscribe(subscriptions));
    }
  }

  private onClose(): void {
    this.socket = null;
    if (this.userClosed) {
      this.setStatus("closed");
      return;
    }
    const willReconnect = this.attempt < this.policy.maxRetries;
    this.emitter.emit("disconnected", { willReconnect });
    if (willReconnect) {
      this.scheduleReconnect();
    } else {
      this.setStatus("closed");
      this.reject(new Error("connection failed after exhausting retries"));
    }
  }

  private scheduleReconnect(): void {
    this.attempt += 1;
    const exponential = this.policy.baseDelayMs * 2 ** (this.attempt - 1);
    const capped = Math.min(this.policy.maxDelayMs, exponential);
    const delayMs = Math.round(capped * (0.5 + this.random() * 0.5));
    this.setStatus("reconnecting");
    this.emitter.emit("reconnecting", { attempt: this.attempt, delayMs });
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      void this.openSocket();
    }, delayMs);
  }

  private resolve(): void {
    const settle = this.settle;
    this.settle = null;
    settle?.resolve();
  }

  private reject(error: unknown): void {
    const settle = this.settle;
    this.settle = null;
    settle?.reject(error);
  }
}
