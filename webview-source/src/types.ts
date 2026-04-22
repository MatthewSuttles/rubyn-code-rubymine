/**
 * Shared types for messages, sessions, and UI state.
 */

// ── Session ───────────────────────────────────────────────────────────────────

export interface Session {
  id: string;
  title: string;
  updatedAt: string; // ISO-8601
  messageCount: number;
}

// ── Messages ──────────────────────────────────────────────────────────────────

export type MessageRole = "user" | "assistant" | "tool";

export interface StreamChunk {
  delta: string;
  done: boolean;
}

export interface ChatMessage {
  id: string;
  role: MessageRole;
  /** Full content once streaming is complete. */
  content: string;
  /** Partial text while streaming; undefined when not streaming. */
  streamingDelta?: string;
  isStreaming?: boolean;
  timestamp: string; // ISO-8601
  toolCall?: ToolCallPayload;
}

// ── Tool calls ────────────────────────────────────────────────────────────────

export type ToolApprovalStatus = "pending" | "approved" | "denied";

export interface FileDiff {
  path: string;
  before: string;
  after: string;
}

export interface ToolCallPayload {
  id: string;
  name: string;
  args: Record<string, unknown>;
  status: ToolApprovalStatus;
  diff?: FileDiff;
}

// ── Slash commands ────────────────────────────────────────────────────────────

export interface SlashCommand {
  name: string;       // e.g. "refactor"
  description: string;
}

export const BUILTIN_SLASH_COMMANDS: SlashCommand[] = [
  { name: "refactor",  description: "Refactor selected code" },
  { name: "explain",   description: "Explain selected code" },
  { name: "review",    description: "Review for bugs and style" },
  { name: "test",      description: "Generate tests for selection" },
  { name: "fix",       description: "Fix the error or bug" },
  { name: "docs",      description: "Write documentation" },
  { name: "model",     description: "Switch AI model" },
  { name: "clear",     description: "Clear current session" },
  { name: "new",       description: "Start a new session" },
];
