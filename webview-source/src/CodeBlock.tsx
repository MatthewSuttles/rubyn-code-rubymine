/**
 * CodeBlock.tsx — syntax-highlighted code with Copy and Apply buttons.
 *
 * Uses shiki for highlighting. Supported languages:
 * ruby, javascript, typescript, html, erb, yaml, sql, shellscript, json, markdown.
 *
 * Import strategy for JCEF compatibility:
 *   - createHighlighterCore from "shiki/core" (no dynamic imports)
 *   - createJavaScriptRegexEngine from "shiki/engine/javascript" (no wasm, no dynamic imports)
 *   - Per-language grammar files from "@shikijs/langs/<lang>" (no dynamic imports)
 *   - Theme from "@shikijs/themes/github-dark" (no dynamic imports)
 *
 * This avoids the 200+ dynamic chunk imports that shiki's default entry point
 * emits, which would fail in the JCEF sandbox.
 *
 * A single Highlighter instance is created at module scope and shared across
 * all code block renders to avoid repeated initialisation cost.
 *
 * The Apply button sends an "applyEdit" message to the host; VS Code / JCEF
 * handles the actual file write. Only shown when a filePath prop is provided.
 */

import React, { useEffect, useRef, useState } from "react";
import { createHighlighterCore } from "shiki/core";
import { createJavaScriptRegexEngine } from "shiki/engine/javascript";
import langRuby from "@shikijs/langs/ruby";
import langJavascript from "@shikijs/langs/javascript";
import langTypescript from "@shikijs/langs/typescript";
import langHtml from "@shikijs/langs/html";
import langErb from "@shikijs/langs/erb";
import langYaml from "@shikijs/langs/yaml";
import langSql from "@shikijs/langs/sql";
import langShellscript from "@shikijs/langs/shellscript";
import langJson from "@shikijs/langs/json";
import langMarkdown from "@shikijs/langs/markdown";
import themeGithubDark from "@shikijs/themes/github-dark";
import { host } from "./host";

// Single shared highlighter — created once, reused for every code block render.
// Stored at module scope so concurrent renders share the same initialisation.
let highlighterPromise: ReturnType<typeof createHighlighterCore> | null = null;

function getHighlighter() {
  if (!highlighterPromise) {
    highlighterPromise = createHighlighterCore({
      langs: [
        langRuby,
        langJavascript,
        langTypescript,
        langHtml,
        langErb,
        langYaml,
        langSql,
        langShellscript,
        langJson,
        langMarkdown,
      ],
      themes: [themeGithubDark],
      engine: createJavaScriptRegexEngine(),
    });
  }
  return highlighterPromise;
}

const SUPPORTED_LANGS = new Set([
  "ruby", "javascript", "typescript", "html", "erb",
  "yaml", "sql", "shellscript", "json", "markdown",
]);

function normaliseLang(raw: string | undefined): string {
  if (!raw) return "text";
  const lower = raw.toLowerCase();
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

    if (language === "text") {
      setHtml(null);
      return () => {
        cancelled.current = true;
      };
    }

    getHighlighter()
      .then((hl) => {
        if (cancelled.current) return;
        const result = hl.codeToHtml(code, {
          lang: language,
          theme: "github-dark",
        });
        if (!cancelled.current) setHtml(result);
      })
      .catch(() => {
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
