/**
 * Message.tsx — renders a single chat message.
 *
 * User messages: plain text, right-aligned.
 * Assistant messages: react-markdown with GFM; code blocks use CodeBlock.tsx.
 * Streaming: when isStreaming=true, content may be partial — a blinking cursor
 * is appended and the component re-renders as new delta arrives.
 * Tool messages: delegated to ToolApproval.tsx.
 */

import React from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import type { Components } from "react-markdown";
import CodeBlock from "./CodeBlock";
import ToolApproval from "./ToolApproval";
import type { ChatMessage } from "./types";

interface Props {
  message: ChatMessage;
  onToolApprove: (toolCallId: string) => void;
  onToolDeny: (toolCallId: string) => void;
}

// react-markdown component overrides
const markdownComponents: Components = {
  // Override fenced code blocks → CodeBlock with shiki highlighting.
  // react-markdown passes className="language-<lang>" on <code> elements
  // inside <pre> blocks.
  code({ className, children, ...rest }) {
    const match = /language-(\w+)/.exec(className ?? "");
    const lang = match?.[1];
    const codeStr = String(children).replace(/\n$/, "");

    // Inline code (no lang, not inside a <pre>) — render as <code>.
    if (!match && !(rest as Record<string, unknown>).node) {
      return <code className="rubyn-inline-code">{codeStr}</code>;
    }

    // Fenced block — pull optional file path from a leading comment convention:
    // ```ruby path=/app/models/user.rb
    const firstLine = codeStr.split("\n")[0] ?? "";
    const pathMatch = /\bpath=(\S+)/.exec(firstLine);
    const filePath = pathMatch?.[1];
    const actualCode = filePath ? codeStr.split("\n").slice(1).join("\n") : codeStr;

    return <CodeBlock code={actualCode} lang={lang} filePath={filePath} />;
  },
};

const Message: React.FC<Props> = ({ message, onToolApprove, onToolDeny }) => {
  if (message.role === "tool" && message.toolCall) {
    return (
      <div className={`rubyn-message rubyn-message--tool`}>
        <ToolApproval
          toolCall={message.toolCall}
          onApprove={() => onToolApprove(message.toolCall!.id)}
          onDeny={() => onToolDeny(message.toolCall!.id)}
        />
      </div>
    );
  }

  const isUser = message.role === "user";
  const displayContent = message.isStreaming
    ? (message.content ?? "") + (message.streamingDelta ?? "")
    : message.content;

  return (
    <div className={`rubyn-message rubyn-message--${message.role}`}>
      <div className="rubyn-message__bubble">
        {isUser ? (
          <p className="rubyn-message__user-text">{displayContent}</p>
        ) : (
          <div className="rubyn-message__assistant-content">
            <ReactMarkdown
              remarkPlugins={[remarkGfm]}
              components={markdownComponents}
            >
              {displayContent}
            </ReactMarkdown>
            {message.isStreaming && (
              <span className="rubyn-message__cursor" aria-hidden="true" />
            )}
          </div>
        )}
      </div>
      <div className="rubyn-message__meta">
        {new Date(message.timestamp).toLocaleTimeString([], {
          hour: "2-digit",
          minute: "2-digit",
        })}
      </div>
    </div>
  );
};

export default Message;
