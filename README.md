<p align="center">
  <img src="docs/RubynLogo.png" alt="Rubyn" width="200" />
</p>

<h1 align="center">Rubyn for RubyMine</h1>

<p align="center">
  <strong>AI pair programmer for Ruby and Rails — without leaving your IDE</strong>
</p>

<p align="center">
  <a href="https://plugins.jetbrains.com/plugin/rubyn"><img src="https://img.shields.io/jetbrains/plugin/v/rubyn?label=marketplace" alt="Marketplace version" /></a>
  <a href="https://plugins.jetbrains.com/plugin/rubyn"><img src="https://img.shields.io/jetbrains/plugin/d/rubyn?label=downloads" alt="Downloads" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-blue.svg" alt="License: MIT" /></a>
</p>

---

## What it does

Rubyn connects RubyMine to [**rubyn-code**](https://github.com/MatthewSuttles/rubyn-code), an agentic coding assistant that understands your entire
codebase. Ask questions, request edits, and review proposed changes as inline diffs —
without switching windows or copy-pasting.

- **Chat panel** — full conversational interface in a dockable tool window
- **Editor actions** — refactor, explain, review, generate specs, or open chat from
  the editor with one keypress
- **Inline diff viewer** — proposed file edits appear as IDE diffs; accept or reject
  individual changes with toolbar buttons
- **Status bar widget** — shows agent state (running / stopped) and session cost at a glance
- **Multi-provider** — Anthropic Claude, OpenAI, and Google Gemini
- **Crash recovery** — automatic restart with exponential back-off; auth failures
  surface as actionable notifications

---

## Quick Start

### 1. Install rubyn-code

```sh
gem install rubyn-code
```

Requires **Ruby 4.0+**. See the [rubyn-code README](https://github.com/MatthewSuttles/rubyn-code#install) for rbenv/rvm setup instructions.

### 2. Authenticate

Rubyn Code reads your Claude Code OAuth token from the macOS Keychain automatically — just make sure you've logged into Claude Code once (`claude` in your terminal).

Alternatively, set an API key via environment variable:

```sh
export ANTHROPIC_API_KEY=sk-ant-...
```

See the [rubyn-code authentication docs](https://github.com/MatthewSuttles/rubyn-code#authentication) for all provider options.

### 3. Install the plugin

Search for **Rubyn** in **Settings → Plugins → Marketplace**, or install from disk
via **Install Plugin from Disk…** using the `.zip` from the
[Releases](https://github.com/MatthewSuttles/rubyn-code-rubymine/releases) page.

### 4. Configure

Open **Settings → Tools → Rubyn** and verify the executable path. If `rubyn-code`
is on your `PATH`, the plugin finds it automatically.

### 5. Open a Ruby project and start chatting

Press **`⌘⇧R`** (macOS) / **`Ctrl+Shift+R`** (Windows/Linux) or click the
**Rubyn** tool window in the right sidebar.

---

## Features

### Chat Panel

A dockable tool window that opens on the right side of the IDE. Type prompts,
attach context with `@filename`, and browse conversation history. The webview
UI is the same one used by the Rubyn VS Code extension and desktop app.

### Editor Actions

Available from **Tools → Rubyn** and from the right-click editor context menu:

| Action | Shortcut | Description |
|---|---|---|
| Open Chat | `⌘⇧R` / `Ctrl+Shift+R` | Focus or open the Rubyn tool window |
| Explain Selection | `⌘⇧E` / `Ctrl+Shift+E` | Explain the selected code or current file |
| Refactor with Rubyn | `⌘⇧F` / `Ctrl+Shift+F` | Refactor the selected code |
| Review with Rubyn | _(menu only)_ | Review selected code for issues |
| Generate Specs | _(menu only)_ | Generate RSpec specs for the current file |
| Review Pull Request… | _(menu only)_ | Review a PR by branch name |
| Select Model… | _(menu only)_ | Switch AI provider/model |

> **Note:** `⌘⇧R` conflicts with RubyMine's built-in **Run** shortcut.
> See [Keyboard Shortcuts](docs/rubymine-plugin.md#keyboard-shortcuts) in the
> full documentation for resolution options.

### Inline Diff Viewer

When rubyn-code proposes a file edit, it appears as an IDE diff viewer.
**Accept Edit** and **Reject Edit** buttons are added to the diff toolbar —
accept writes the change to disk; reject discards it.

### Status Bar Widget

A small widget in the IDE status bar shows:

- `● Rubyn` — agent is running
- `○ Rubyn` — agent is stopped
- Session token/cost when available

Click the widget to open the chat panel.

---

## Configuration

Open **Settings → Tools → Rubyn**.

| Setting | Default | Description |
|---|---|---|
| Executable path | _(auto)_ | Absolute path to `rubyn-code`. Leave blank to use `PATH`. |
| Provider | `anthropic` | AI provider: `anthropic`, `openai`, or `google` |
| Model | `claude-opus-4-6` | Model identifier within the selected provider |
| Token budget | `0` (unlimited) | Maximum tokens per session |
| Cost budget | `0.00` (unlimited) | Maximum USD cost per session |
| Permission mode | `default` | One of `default`, `acceptEdits`, `bypassPermissions`, `planOnly` |

### Permission Modes

| Mode | Behaviour |
|---|---|
| `default` | Agent requests confirmation for file writes |
| `acceptEdits` | File edits are accepted automatically; other actions still prompt |
| `bypassPermissions` | All actions proceed without confirmation |
| `planOnly` | Agent produces plans but never writes files |

---

## Compatibility

| IDE | Minimum version |
|---|---|
| RubyMine | 2024.3 |
| IntelliJ IDEA Ultimate (with Ruby plugin) | 2024.3 |

The plugin is also verified against **RubyMine 2025.1** and **IntelliJ IDEA Ultimate 2024.3**.

---

## Full Documentation

→ [docs/rubymine-plugin.md](docs/rubymine-plugin.md) — installation walkthrough,
feature deep-dive, config reference, troubleshooting, and dev setup.

---

## License

MIT — see [LICENSE](LICENSE).
