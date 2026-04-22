/**
 * main.tsx — webview entry point.
 *
 * Mounts the Chat component into #root. Both VS Code and JCEF load this
 * bundle and provide a <div id="root"> in their respective HTML shells.
 */

import React from "react";
import { createRoot } from "react-dom/client";
import Chat from "./Chat";
import "./styles.css";

const rootEl = document.getElementById("root");
if (!rootEl) {
  throw new Error("[rubyn] No #root element found in the document.");
}

createRoot(rootEl).render(
  <React.StrictMode>
    <Chat />
  </React.StrictMode>
);
