/**
 * ToolApproval.tsx — inline approval card for tool calls.
 *
 * Shows the tool name + args, an optional mini diff, and Approve / Deny
 * buttons. Once a decision is made the card becomes read-only.
 */

import React, { useState } from "react";
import type { ToolCallPayload } from "./types";

interface Props {
  toolCall: ToolCallPayload;
  onApprove: () => void;
  onDeny: () => void;
}

function formatArgs(args: Record<string, unknown>): string {
  try {
    return JSON.stringify(args, null, 2);
  } catch {
    return String(args);
  }
}

/** Minimal unified diff renderer — shows removed/added lines coloured. */
function MiniDiff({ before, after, path }: { before: string; after: string; path: string }) {
  const beforeLines = before.split("\n");
  const afterLines = after.split("\n");

  // Simple LCS-free diff: show removed then added lines.
  // Good enough for short diffs; a full LCS diff is overkill here.
  const removed = beforeLines.filter((l) => !afterLines.includes(l));
  const added = afterLines.filter((l) => !beforeLines.includes(l));

  if (removed.length === 0 && added.length === 0) return null;

  return (
    <div className="rubyn-tool-approval__diff">
      <div className="rubyn-tool-approval__diff-path">{path}</div>
      <pre className="rubyn-tool-approval__diff-body">
        {removed.map((line, i) => (
          <div key={`r${i}`} className="rubyn-diff-line rubyn-diff-line--removed">
            {"- "}{line}
          </div>
        ))}
        {added.map((line, i) => (
          <div key={`a${i}`} className="rubyn-diff-line rubyn-diff-line--added">
            {"+ "}{line}
          </div>
        ))}
      </pre>
    </div>
  );
}

const ToolApproval: React.FC<Props> = ({ toolCall, onApprove, onDeny }) => {
  const [decided, setDecided] = useState<"approved" | "denied" | null>(
    toolCall.status === "pending" ? null : toolCall.status
  );

  const handleApprove = () => {
    setDecided("approved");
    onApprove();
  };

  const handleDeny = () => {
    setDecided("denied");
    onDeny();
  };

  const statusLabel =
    decided === "approved"
      ? "Approved"
      : decided === "denied"
      ? "Denied"
      : null;

  return (
    <div className={`rubyn-tool-approval rubyn-tool-approval--${decided ?? "pending"}`}>
      <div className="rubyn-tool-approval__header">
        <span className="rubyn-tool-approval__icon">⚙</span>
        <span className="rubyn-tool-approval__name">{toolCall.name}</span>
        {statusLabel && (
          <span className={`rubyn-tool-approval__status rubyn-tool-approval__status--${decided}`}>
            {statusLabel}
          </span>
        )}
      </div>

      <details className="rubyn-tool-approval__args-details">
        <summary>Arguments</summary>
        <pre className="rubyn-tool-approval__args">{formatArgs(toolCall.args)}</pre>
      </details>

      {toolCall.diff && (
        <MiniDiff
          before={toolCall.diff.before}
          after={toolCall.diff.after}
          path={toolCall.diff.path}
        />
      )}

      {!decided && (
        <div className="rubyn-tool-approval__actions">
          <button
            className="rubyn-tool-approval__btn rubyn-tool-approval__btn--deny"
            onClick={handleDeny}
          >
            Deny
          </button>
          <button
            className="rubyn-tool-approval__btn rubyn-tool-approval__btn--approve"
            onClick={handleApprove}
          >
            Approve
          </button>
        </div>
      )}
    </div>
  );
};

export default ToolApproval;
