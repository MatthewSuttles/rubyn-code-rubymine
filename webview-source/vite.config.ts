import { defineConfig, type Plugin } from "vite";
import react from "@vitejs/plugin-react";

/**
 * Make built HTML compatible with JCEF file:// loading.
 *
 * Two problems:
 * 1. Vite adds `crossorigin` to <script> and <link> tags. Chromium treats
 *    file:// as origin "null", so any `crossorigin` triggers a CORS check
 *    that immediately fails — the resource never loads.
 * 2. Vite emits `<script type="module" ...>`. ES modules *always* require
 *    CORS (even without an explicit `crossorigin` attribute), so file://
 *    pages cannot load them. We strip `type="module"` so the browser treats
 *    the script as a classic script.
 *
 * Together these two changes allow the IIFE bundle to load via file:// in
 * JCEF without any CORS issues.
 */
function jcefCompat(): Plugin {
  return {
    name: "jcef-compat",
    enforce: "post",
    transformIndexHtml(html) {
      return html
        .replace(/ crossorigin/g, "")
        .replace(/ type="module"/g, "")
        // Move <script> from <head> to end of <body> so the DOM (#root) exists
        // when the IIFE executes. With type="module" removed, the script no
        // longer auto-defers and would otherwise run before <body> is parsed.
        .replace(
          /(<script\b[^>]*src="[^"]*rubyn-webview\.js"[^>]*><\/script>)\s*/,
          (match, scriptTag) => {
            // Remove from current position (in <head>)
            return "";
          }
        )
        .replace(
          "</body>",
          (match) => {
            return `<script defer src="./rubyn-webview.js"></script>\n</body>`;
          }
        );
    },
  };
}

// Single-file bundle consumed by both VS Code webview and IntelliJ JCEF.
// Everything is inlined — no asset imports that require a separate fetch.
//
// Shiki is imported via createHighlighterCore + createJavaScriptRegexEngine +
// per-language @shikijs/langs/<lang> imports. These paths contain zero dynamic
// imports, so Rollup produces a small, deterministic dist with no lazy chunks.
// This is required for JCEF compatibility: the sandbox cannot issue secondary
// network fetches for dynamically-imported grammar chunks.
export default defineConfig({
  plugins: [react(), jcefCompat()],
  // Use relative paths so JCEF file:// loading resolves assets correctly.
  base: "./",
  build: {
    outDir: "dist",
    // CSS injected into JS bundle so JCEF loads a single file.
    cssCodeSplit: false,
    rollupOptions: {
      output: {
        // IIFE format so the built HTML uses a plain <script> tag instead of
        // <script type="module">. ES modules always require CORS (even without
        // an explicit crossorigin attribute), and Chromium blocks CORS on
        // file:// URLs (origin is "null"). IIFE avoids this entirely.
        format: "iife",
        // No hashed filenames — hosts reference "rubyn-webview.js" by name.
        entryFileNames: "rubyn-webview.js",
        chunkFileNames: "rubyn-webview-[name].js",
        assetFileNames: "rubyn-webview[extname]",
      },
    },
  },
  optimizeDeps: {
    include: [
      "shiki/core",
      "shiki/engine/javascript",
      "@shikijs/langs/ruby",
      "@shikijs/langs/javascript",
      "@shikijs/langs/typescript",
      "@shikijs/langs/html",
      "@shikijs/langs/erb",
      "@shikijs/langs/yaml",
      "@shikijs/langs/sql",
      "@shikijs/langs/shellscript",
      "@shikijs/langs/json",
      "@shikijs/langs/markdown",
      "@shikijs/themes/github-dark",
    ],
  },
});
