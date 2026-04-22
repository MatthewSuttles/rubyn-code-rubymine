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
  // In JCEF, window.rubynJcef is injected via onLoadEnd which may fire AFTER
  // this module initialises. Return "browser" for now; the Host constructor
  // will poll and upgrade to "jcef" once the object appears.
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
  kind: HostKind;
  private readonly vsCode: VsCodeApi | null;
  private readonly handlers: Set<MessageHandler> = new Set();
  private readonly outboundQueue: OutboundMessage[] = [];

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
    }

    // Always wire rubynReceive — JCEF injects window.rubynJcef via
    // executeJavaScript after the page loads, and then calls rubynReceive
    // to push messages. We must be ready to receive even before we detect
    // the JCEF host object.
    window.rubynReceive = (json: string) => {
      try {
        // If we haven't upgraded to jcef yet, do it now.
        if (this.kind !== "jcef" && this.kind !== "vscode") {
          this.upgradeToJcef();
        }
        this.dispatch(JSON.parse(json) as InboundMessage);
      } catch {
        console.error("[rubyn] Failed to parse inbound message:", json);
      }
    };

    // If we detected "browser" mode, poll for late JCEF injection.
    if (this.kind === "browser") {
      this.pollForJcef();
    }
  }

  /**
   * Poll for window.rubynJcef which is injected by the Kotlin side after
   * onLoadEnd fires. Check every 50ms for up to 5 seconds.
   */
  private pollForJcef(): void {
    let attempts = 0;
    const maxAttempts = 100; // 50ms * 100 = 5 seconds
    const timer = setInterval(() => {
      attempts++;
      if (typeof (window as unknown as Record<string, unknown>).rubynJcef === "object") {
        clearInterval(timer);
        this.upgradeToJcef();
      } else if (attempts >= maxAttempts) {
        clearInterval(timer);
        console.debug("[rubyn:host] JCEF bridge not detected after 5s — staying in browser mode");
      }
    }, 50);
  }

  /**
   * Upgrade from "browser" mode to "jcef" once window.rubynJcef is available.
   * Flushes any outbound messages that were queued while waiting.
   */
  private upgradeToJcef(): void {
    if (this.kind === "jcef") return;
    this.kind = "jcef";
    console.debug("[rubyn:host] Upgraded to JCEF mode");

    // Flush any messages that were queued while we were in browser mode.
    const queued = this.outboundQueue.splice(0);
    for (const msg of queued) {
      this.send(msg);
    }
  }

  /** Send a message to the host extension. */
  send(msg: OutboundMessage): void {
    if (this.kind === "vscode" && this.vsCode) {
      this.vsCode.postMessage(msg);
    } else if (this.kind === "jcef" && window.rubynJcef) {
      window.rubynJcef.postMessage(JSON.stringify(msg));
    } else if (this.kind === "browser") {
      // Bridge not ready yet — queue and it'll flush when JCEF is detected.
      console.debug("[rubyn:host] send (queued) →", msg);
      this.outboundQueue.push(msg);
    } else {
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
