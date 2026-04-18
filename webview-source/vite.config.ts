import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Single-file bundle consumed by both VS Code webview and IntelliJ JCEF.
// Everything is inlined — no asset imports that require a separate fetch.
export default defineConfig({
  plugins: [react()],
  build: {
    outDir: "dist",
    // Single JS entry point; CSS is injected into the JS bundle via style tags
    // so JCEF doesn't need to load a separate stylesheet.
    cssCodeSplit: false,
    rollupOptions: {
      output: {
        // No hashed filenames — hosts reference "rubyn-webview.js" by name.
        entryFileNames: "rubyn-webview.js",
        chunkFileNames: "rubyn-webview-[name].js",
        assetFileNames: "rubyn-webview[extname]",
        // Inline all assets (fonts, small images) so the bundle is truly self-contained.
        inlineDynamicImports: false,
      },
    },
  },
  // Allow shiki's wasm-backed highlighter to be bundled without dynamic imports
  // breaking the JCEF sandbox.
  optimizeDeps: {
    include: ["shiki"],
  },
});
