/**
 * host.ts — unified host API for VS Code webview and IntelliJ JCEF.
 *
 * Both hosts inject a global before loading this bundle:
 *   - VS Code: window.acquireVsCodeApi() → { postMessage, getState, setState }
 *   - JCEF:    window.rubynJcef        → { postMessage }
 *
 * All UI components talk exclusively to this module. They never reference
 * window.acquireVsCodeApi or window.rubynJcef directly.
 */

// ── Types ─────────────────────────────────────────────────────────────────────

export type HostKind = "vscode" | "jcef" | "browser";

export interface OutboundMessage {
  type: string;
  [key: string]: unknown;
}

export interface InboundMessage {
  type: string;
  [key: string]: unknown;
}

type MessageHandler = (msg: InboundMessage) => void;

// ── Host detection ────────────────────────────────────────────────────────────

function detectHost(): HostKind {
  if (typeof (window as unknown as Record<string, unknown>).acquireVsCodeApi === "function") {
    return "vscode";
  }
  if (typeof (window as unknown as Record<string, unknown>).rubynJcef === "object") {
    return "jcef";
  }
  return "browser";
}

// ── VS Code adapter ───────────────────────────────────────────────────────────

interface VsCodeApi {
  postMessage(msg: unknown): void;
  getState(): unknown;
  setState(state: unknown): void;
}

declare global {
  interface Window {
    acquireVsCodeApi?: () => VsCodeApi;
    rubynJcef?: {
      postMessage(json: string): void;
    };
    // JCEF calls this function to push messages into the webview.
    rubynReceive?: (json: string) => void;
  }
}

// ── Host singleton ────────────────────────────────────────────────────────────

class Host {
  readonly kind: HostKind;
  private readonly vsCode: VsCodeApi | null;
  private readonly handlers: Set<MessageHandler> = new Set();

  constructor() {
    this.kind = detectHost();

    if (this.kind === "vscode") {
      this.vsCode = window.acquireVsCodeApi!();
    } else {
      this.vsCode = null;
    }

    // Wire inbound messages from both hosts.
    if (this.kind === "vscode") {
      window.addEventListener("message", (event: MessageEvent) => {
        this.dispatch(event.data as InboundMessage);
      });
    } else {
      // JCEF calls window.rubynReceive(jsonString) to push a message in.
      window.rubynReceive = (json: string) => {
        try {
          this.dispatch(JSON.parse(json) as InboundMessage);
        } catch {
          console.error("[rubyn] Failed to parse inbound message:", json);
        }
      };
    }
  }

  /** Send a message to the host extension. */
  send(msg: OutboundMessage): void {
    if (this.kind === "vscode" && this.vsCode) {
      this.vsCode.postMessage(msg);
    } else if (this.kind === "jcef" && window.rubynJcef) {
      window.rubynJcef.postMessage(JSON.stringify(msg));
    } else {
      // Browser dev mode — log to console.
      console.debug("[rubyn:host] send →", msg);
    }
  }

  /** Register a listener for messages coming from the host. */
  on(handler: MessageHandler): () => void {
    this.handlers.add(handler);
    return () => this.handlers.delete(handler);
  }

  /** VS Code persisted state helpers (no-op in JCEF). */
  getState<T>(): T | undefined {
    return (this.vsCode?.getState() as T | undefined) ?? undefined;
  }

  setState(state: unknown): void {
    this.vsCode?.setState(state);
  }

  private dispatch(msg: InboundMessage): void {
    this.handlers.forEach((h) => h(msg));
  }
}

export const host = new Host();
