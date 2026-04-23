/**
 * Chat.tsx — main chat panel.
 *
 * Layout:
 *   ┌───────────────────────────────┐
 *   │ Session indicator (top bar)   │
 *   ├───────────────────────────────┤
 *   │ Message list (scrollable)     │
 *   ├───────────────────────────────┤
 *   │ Slash command menu (overlay)  │
 *   │ Input area                    │
 *   └───────────────────────────────┘
 *
 * Talks to the host via host.send / host.on. Message protocol:
 *   Outbound → { type: "sendMessage", sessionId, text }
 *   Inbound  ← { type: "streamChunk",   sessionId, messageId, delta, done }
 *   Inbound  ← { type: "newMessage",    sessionId, message: ChatMessage }
 *   Inbound  ← { type: "sessionList",   sessions: Session[] }
 *   Inbound  ← { type: "toolCall",      sessionId, message: ChatMessage }
 *   Inbound  ← { type: "toolResult",    sessionId, toolCallId, status }
 */

import React, { useCallback, useEffect, useRef, useState } from "react";
import Message from "./Message";
import SlashCommandMenu, { useSlashCommandKeyboard } from "./SlashCommandMenu";
import { host } from "./host";
import type { InboundMessage } from "./host";
import type { ChatMessage, Session } from "./types";
import { BUILTIN_SLASH_COMMANDS } from "./types";

function generateId(): string {
  return `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
}

const Chat: React.FC = () => {
  // ── State ────────────────────────────────────────────────────────────────
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [sessions, setSessions] = useState<Session[]>([]);
  const [activeSessionId, setActiveSessionId] = useState<string | null>(null);
  const [input, setInput] = useState("");
  const [slashFilter, setSlashFilter] = useState("");
  const [slashVisible, setSlashVisible] = useState(false);
  const [slashIndex, setSlashIndex] = useState(0);
  const [agentStatus, setAgentStatus] = useState<string>("idle");

  const messagesEndRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);

  // ── Auto-scroll ───────────────────────────────────────────────────────────
  const scrollToBottom = useCallback(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: "smooth" });
  }, []);

  useEffect(scrollToBottom, [messages, scrollToBottom]);

  // ── Host message handling ─────────────────────────────────────────────────
  //
  // IMPORTANT: This effect registers ONCE on mount (empty deps []).
  // We use a ref for activeSessionId so the handler closure always reads
  // the latest value without re-registering (which would cause double
  // message processing).
  const activeSessionRef = useRef<string | null>(activeSessionId);
  activeSessionRef.current = activeSessionId;

  useEffect(() => {
    const unsub = host.on((msg: InboundMessage) => {
      switch (msg.type) {
        // The Kotlin side pushes the real session ID so the webview can
        // match it against inbound streamChunk / toolCall messages.
        case "sessionId": {
          const sid = msg.sessionId as string;
          setActiveSessionId(sid);
          break;
        }

        case "sessionList": {
          const incoming = msg.sessions as Session[];
          setSessions(incoming);
          setActiveSessionId((prev) => {
            if (prev) return prev;
            return incoming.length > 0 ? incoming[0]!.id : prev;
          });
          break;
        }

        case "newMessage": {
          const m = msg.message as ChatMessage;
          if (msg.sessionId !== activeSessionRef.current) break;
          setMessages((prev) => {
            // Avoid duplicates
            if (prev.some((p) => p.id === m.id)) return prev;
            return [...prev, m];
          });
          break;
        }

        case "streamChunk": {
          if (msg.sessionId !== activeSessionRef.current) break;
          const { messageId, delta, done } = msg as unknown as {
            messageId: string;
            delta: string;
            done: boolean;
          };
          // Clear thinking indicator as soon as content starts streaming.
          setAgentStatus(done ? "idle" : "streaming");
          setMessages((prev) => {
            const exists = prev.some((m) => m.id === messageId);
            const list = exists
              ? prev
              : [
                  ...prev,
                  {
                    id: messageId,
                    role: "assistant" as const,
                    content: "",
                    timestamp: new Date().toISOString(),
                    isStreaming: true,
                  },
                ];
            return list.map((m) => {
              if (m.id !== messageId) return m;
              const updatedContent = (m.streamingDelta ?? m.content ?? "") + delta;
              return {
                ...m,
                content: done ? updatedContent : m.content,
                streamingDelta: done ? undefined : updatedContent,
                isStreaming: !done,
              };
            });
          });
          break;
        }

        case "toolCall": {
          if (msg.sessionId !== activeSessionRef.current) break;
          const m = msg.message as ChatMessage;
          setAgentStatus("waiting_approval");
          setMessages((prev) => {
            // Deduplicate: if a tool call with this ID already exists, update
            // it in place rather than appending a duplicate entry.
            if (prev.some((p) => p.id === m.id)) {
              return prev.map((p) => (p.id === m.id ? m : p));
            }
            return [...prev, m];
          });
          break;
        }

        case "agentStatus": {
          const status = msg.status as string;
          setAgentStatus(status);
          break;
        }

        case "toolResult": {
          const { toolCallId, status } = msg as unknown as {
            toolCallId: string;
            status: "approved" | "denied";
          };
          setMessages((prev) =>
            prev.map((m) => {
              if (!m.toolCall || m.toolCall.id !== toolCallId) return m;
              return { ...m, toolCall: { ...m.toolCall, status } };
            })
          );
          break;
        }
      }
    });

    // Request initial session list on mount.
    host.send({ type: "getSessions" });

    return unsub;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []); // Mount once — uses activeSessionRef for latest session ID

  // ── Slash command state ───────────────────────────────────────────────────
  const handleInputChange = (e: React.ChangeEvent<HTMLTextAreaElement>) => {
    const val = e.target.value;
    setInput(val);

    if (val.startsWith("/") && !val.includes(" ")) {
      setSlashFilter(val.slice(1));
      setSlashVisible(true);
      setSlashIndex(0);
    } else {
      setSlashVisible(false);
    }
  };

  const filteredCommands = BUILTIN_SLASH_COMMANDS.filter((c) =>
    c.name.toLowerCase().startsWith(slashFilter.toLowerCase())
  );

  const confirmSlashCommand = useCallback(() => {
    const cmd = filteredCommands[slashIndex];
    if (!cmd) return;
    setInput(`/${cmd.name} `);
    setSlashVisible(false);
    inputRef.current?.focus();
  }, [filteredCommands, slashIndex]);

  const { handleKeyDown: slashKeyDown } = useSlashCommandKeyboard({
    visible: slashVisible,
    total: filteredCommands.length,
    selectedIndex: slashIndex,
    onIndexChange: setSlashIndex,
    onConfirm: confirmSlashCommand,
    onDismiss: () => setSlashVisible(false),
  });

  // ── Send message ──────────────────────────────────────────────────────────
  const sendMessage = useCallback(() => {
    const text = input.trim();
    if (!text) return;

    const id = generateId();
    const sessionId = activeSessionId ?? "";

    // Optimistic local message.
    const msg: ChatMessage = {
      id,
      role: "user",
      content: text,
      timestamp: new Date().toISOString(),
    };

    setMessages((prev) => [...prev, msg]);
    setInput("");
    setSlashVisible(false);
    setAgentStatus("thinking"); // Show thinking dots immediately

    host.send({ type: "sendMessage", sessionId, text, messageId: id });
  }, [input, activeSessionId]);

  const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    // Let slash menu intercept first.
    slashKeyDown(e);
    if (e.defaultPrevented) return;

    // Enter (without shift) → send.
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      sendMessage();
    }
  };

  // ── Tool approval round-trip ──────────────────────────────────────────────
  const handleToolApprove = (toolCallId: string) => {
    host.send({ type: "approveToolCall", toolCallId, sessionId: activeSessionId });
  };

  const handleToolDeny = (toolCallId: string) => {
    host.send({ type: "denyToolCall", toolCallId, sessionId: activeSessionId });
  };

  // ── Session switch ────────────────────────────────────────────────────────
  const handleSessionChange = (e: React.ChangeEvent<HTMLSelectElement>) => {
    setActiveSessionId(e.target.value);
    setMessages([]);
    host.send({ type: "getMessages", sessionId: e.target.value });
  };

  // ── Active session label ──────────────────────────────────────────────────
  const activeSession = sessions.find((s) => s.id === activeSessionId);

  // ── Render ────────────────────────────────────────────────────────────────
  return (
    <div className="rubyn-chat">
      {/* Session header */}
      <div className="rubyn-chat__header">
        <span className="rubyn-chat__header-title">Rubyn</span>
        <div className="rubyn-chat__header-status">
          <span className={`rubyn-status-dot rubyn-status-dot--${agentStatus}`} />
          <span className="rubyn-chat__status-label">{agentStatus}</span>
        </div>
        <button
          className="rubyn-chat__new-session-btn"
          title="New session"
          onClick={() => host.send({ type: "newSession" })}
        >
          +
        </button>
      </div>

      {/* Message list */}
      <div className="rubyn-chat__messages" role="log" aria-live="polite">
        {messages.length === 0 && (
          <div className="rubyn-chat__empty">
            <p>Start a conversation with Rubyn.</p>
            <p className="rubyn-chat__hint">Type <code>/</code> for commands.</p>
          </div>
        )}
        {messages.map((msg) => (
          <Message
            key={msg.id}
            message={msg}
            onToolApprove={handleToolApprove}
            onToolDeny={handleToolDeny}
          />
        ))}
        {agentStatus === "thinking" && (
          <div className="rubyn-thinking">
            <div className="rubyn-thinking__dots">
              <span className="rubyn-thinking__dot" />
              <span className="rubyn-thinking__dot" />
              <span className="rubyn-thinking__dot" />
            </div>
          </div>
        )}
        <div ref={messagesEndRef} />
      </div>

      {/* Input area */}
      <div className="rubyn-chat__input-wrapper">
        {slashVisible && filteredCommands.length > 0 && (
          <SlashCommandMenu
            commands={BUILTIN_SLASH_COMMANDS}
            filter={slashFilter}
            selectedIndex={slashIndex}
            onSelect={(cmd) => {
              setInput(`/${cmd.name} `);
              setSlashVisible(false);
              inputRef.current?.focus();
            }}
            onDismiss={() => setSlashVisible(false)}
            onIndexChange={setSlashIndex}
          />
        )}
        <div className="rubyn-chat__input-row">
          <textarea
            ref={inputRef}
            className="rubyn-chat__input"
            value={input}
            onChange={handleInputChange}
            onKeyDown={handleKeyDown}
            placeholder="Message Rubyn... (/ for commands)"
            rows={1}
            aria-label="Message input"
          />
          <button
            className="rubyn-chat__send-btn"
            onClick={sendMessage}
            disabled={!input.trim()}
            aria-label="Send message"
          >
            ↑
          </button>
        </div>
      </div>
    </div>
  );
};

export default Chat;
