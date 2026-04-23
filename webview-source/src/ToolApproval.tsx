/**
 * ToolApproval.tsx — inline approval card for tool calls.
 *
 * Compact Claude-Code-style display: shows tool name + a one-line summary
 * of what it's doing, with expandable details and Approve / Deny buttons.
 *
 * decided state is derived from props first, then from local user action.
 * This ensures host-side status updates (e.g. the bridge resolves a pending
 * call externally) are always reflected in the UI without requiring a remount.
 */

import React, { useState } from "react";
import type { ToolCallPayload } from "./types";

interface Props {
  toolCall: ToolCallPayload;
  onApprove: () => void;
  onDeny: () => void;
}

/** Return a human-readable one-line summary of what the tool is doing. */
function toolSummary(name: string, args: Record<string, unknown>): string {
  switch (name) {
    case "bash":
    case "shell":
      return String(args.command ?? args.cmd ?? "").slice(0, 120);
    case "file_edit":
    case "edit":
      return args.path ? `Edit ${args.path}` : "Edit file";
    case "file_write":
    case "write":
      return args.path ? `Write ${args.path}` : "Write file";
    case "file_read":
    case "read":
      return args.path ? `Read ${args.path}` : "Read file";
    case "glob":
      return args.pattern ? `Find ${args.pattern}` : "Find files";
    case "grep":
    case "search":
      return args.pattern ? `Search: ${args.pattern}` : "Search files";
    default: {
      // Generic: show first arg value as summary
      const vals = Object.values(args);
      if (vals.length > 0) {
        const first = String(vals[0]).slice(0, 100);
        return first;
      }
      return name;
    }
  }
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
  const [localDecision, setLocalDecision] = useState<"approved" | "denied" | null>(null);

  const propDecided =
    toolCall.status !== "pending" ? (toolCall.status as "approved" | "denied") : null;

  const decided = propDecided ?? localDecision;

  const handleApprove = () => {
    setLocalDecision("approved");
    onApprove();
  };

  const handleDeny = () => {
    setLocalDecision("denied");
    onDeny();
  };

  const summary = toolSummary(toolCall.name, toolCall.args);
  const statusIcon = decided === "approved" ? "✓" : decided === "denied" ? "✗" : "●";
  const statusClass = decided ?? "pending";

  return (
    <div className={`rubyn-tool rubyn-tool--${statusClass}`}>
      {/* Compact header: icon + tool name + summary */}
      <div className="rubyn-tool__header">
        <span className={`rubyn-tool__status-dot rubyn-tool__status-dot--${statusClass}`}>
          {statusIcon}
        </span>
        <span className="rubyn-tool__name">{toolCall.name}</span>
        <span className="rubyn-tool__summary">{summary}</span>
      </div>

      {/* Expandable details */}
      <details className="rubyn-tool__details">
        <summary className="rubyn-tool__details-toggle">Details</summary>
        <pre className="rubyn-tool__args">{formatArgs(toolCall.args)}</pre>
      </details>

      {/* Diff if present */}
      {toolCall.diff && (
        <MiniDiff
          before={toolCall.diff.before}
          after={toolCall.diff.after}
          path={toolCall.diff.path}
        />
      )}

      {/* Approve / Deny actions */}
      {!decided && (
        <div className="rubyn-tool__actions">
          <button
            className="rubyn-tool__btn rubyn-tool__btn--deny"
            onClick={handleDeny}
          >
            Deny
          </button>
          <button
            className="rubyn-tool__btn rubyn-tool__btn--approve"
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
