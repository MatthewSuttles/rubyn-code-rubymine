/**
 * CodeBlock.tsx — syntax-highlighted code with Copy and Apply buttons.
 *
 * Uses shiki for highlighting. Supported languages:
 * ruby, javascript, typescript, html, erb, yaml, sql, shell, json, markdown.
 *
 * The Apply button sends an "applyEdit" message to the host; VS Code / JCEF
 * handles the actual file write. The button is only shown when the code block
 * carries a path attribute (injected by the assistant).
 */

import React, { useEffect, useRef, useState } from "react";
import { codeToHtml } from "shiki";
import { host } from "./host";

// Languages we support for highlighting.
const SUPPORTED_LANGS = new Set([
  "ruby", "javascript", "typescript", "html", "erb",
  "yaml", "sql", "shellscript", "json", "markdown",
  // Aliases that shiki accepts
  "sh", "bash", "ts", "js", "md", "yml",
]);

function normaliseLang(raw: string | undefined): string {
  if (!raw) return "text";
  const lower = raw.toLowerCase();
  // Map common aliases to shiki canonical names
  if (lower === "sh" || lower === "bash") return "shellscript";
  if (lower === "yml") return "yaml";
  if (lower === "md") return "markdown";
  if (lower === "js") return "javascript";
  if (lower === "ts") return "typescript";
  return SUPPORTED_LANGS.has(lower) ? lower : "text";
}

interface Props {
  code: string;
  lang?: string;
  /** Optional file path — shows "Apply" button when present. */
  filePath?: string;
}

const CodeBlock: React.FC<Props> = ({ code, lang, filePath }) => {
  const [html, setHtml] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);
  const [applied, setApplied] = useState(false);
  const cancelled = useRef(false);

  useEffect(() => {
    cancelled.current = false;
    const language = normaliseLang(lang);

    codeToHtml(code, {
      lang: language,
      // Use the bundled themes — no network fetch required.
      theme: "github-dark",
    })
      .then((result) => {
        if (!cancelled.current) setHtml(result);
      })
      .catch(() => {
        // Fallback: plain pre-formatted text.
        if (!cancelled.current) setHtml(null);
      });

    return () => {
      cancelled.current = true;
    };
  }, [code, lang]);

  const handleCopy = () => {
    navigator.clipboard.writeText(code).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    });
  };

  const handleApply = () => {
    if (!filePath) return;
    host.send({ type: "applyEdit", filePath, content: code });
    setApplied(true);
    setTimeout(() => setApplied(false), 2000);
  };

  return (
    <div className="rubyn-code-block">
      <div className="rubyn-code-block__toolbar">
        {lang && <span className="rubyn-code-block__lang">{lang}</span>}
        {filePath && (
          <span className="rubyn-code-block__filepath" title={filePath}>
            {filePath.split("/").pop()}
          </span>
        )}
        <div className="rubyn-code-block__actions">
          {filePath && (
            <button
              className="rubyn-code-block__btn"
              onClick={handleApply}
              title={`Apply to ${filePath}`}
            >
              {applied ? "✓ Applied" : "Apply"}
            </button>
          )}
          <button className="rubyn-code-block__btn" onClick={handleCopy} title="Copy code">
            {copied ? "✓ Copied" : "Copy"}
          </button>
        </div>
      </div>

      {html ? (
        <div
          className="rubyn-code-block__highlighted"
          // shiki output is trusted — it's generated from the code string we control.
          dangerouslySetInnerHTML={{ __html: html }}
        />
      ) : (
        <pre className="rubyn-code-block__plain">
          <code>{code}</code>
        </pre>
      )}
    </div>
  );
};

export default CodeBlock;
