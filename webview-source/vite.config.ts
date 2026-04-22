import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Single-file bundle consumed by both VS Code webview and IntelliJ JCEF.
// Everything is inlined — no asset imports that require a separate fetch.
//
// Shiki is imported via createHighlighterCore + createJavaScriptRegexEngine +
// per-language @shikijs/langs/<lang> imports. These paths contain zero dynamic
// imports, so Rollup produces a small, deterministic dist with no lazy chunks.
// This is required for JCEF compatibility: the sandbox cannot issue secondary
// network fetches for dynamically-imported grammar chunks.
export default defineConfig({
  plugins: [react()],
  // Use relative paths so JCEF file:// loading resolves assets correctly.
  base: "./",
  build: {
    outDir: "dist",
    // CSS injected into JS bundle so JCEF loads a single file.
    cssCodeSplit: false,
    rollupOptions: {
      output: {
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
