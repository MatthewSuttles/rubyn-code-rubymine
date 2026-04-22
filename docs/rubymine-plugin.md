# Rubyn for RubyMine — Full Documentation

## Table of Contents

1. [Prerequisites](#prerequisites)
2. [Installation](#installation)
3. [Getting Started Walkthrough](#getting-started-walkthrough)
4. [Feature Deep-Dive](#feature-deep-dive)
   - [Chat Panel (Tool Window)](#chat-panel-tool-window)
   - [Editor Actions](#editor-actions)
   - [Inline Diff Viewer](#inline-diff-viewer)
   - [Status Bar Widget](#status-bar-widget)
   - [Notifications](#notifications)
5. [Configuration Reference](#configuration-reference)
6. [Permission Modes](#permission-modes)
7. [Keyboard Shortcuts](#keyboard-shortcuts)
8. [Troubleshooting](#troubleshooting)
9. [Developer Setup](#developer-setup)

---

## Prerequisites

### rubyn-code gem

The plugin is a thin IDE layer — it does not run AI models itself. It delegates
all inference to a local **rubyn-code** agent process that it spawns on project
open.

- **Ruby ≥ 3.1** must be on your `PATH` (or reachable from your project Ruby version manager)
- **rubyn-code gem** must be installed:

  ```sh
  gem install rubyn-code
  ```

  Or add it to your `Gemfile`:

  ```ruby
  gem "rubyn-code", require: false
  ```

  then `bundle install`.

- **API key** from [rubyn.ai](https://rubyn.ai). Run `rubyn-code auth` once after
  installing the gem and follow the prompt.

### IDE

| IDE | Minimum version |
|---|---|
| RubyMine | 2024.3 |
| IntelliJ IDEA Ultimate (with Ruby plugin) | 2024.3 |

The Ruby plugin (`org.jetbrains.plugins.ruby`) must be active.

---

## Installation

### From the JetBrains Marketplace (recommended)

1. Open **Settings** (`⌘,` / `Ctrl+Alt+S`).
2. Go to **Plugins → Marketplace**.
3. Search for **Rubyn**.
4. Click **Install** and restart the IDE when prompted.

### From a local `.zip`

1. Download the `.zip` from the
   [Releases](https://github.com/MatthewSuttles/rubyn-code-rubymine/releases) page.
2. Open **Settings → Plugins**.
3. Click the gear icon → **Install Plugin from Disk…**
4. Select the downloaded `.zip`.
5. Restart the IDE.

---

## Getting Started Walkthrough

### Step 1 — Install and authenticate rubyn-code

```sh
gem install rubyn-code
rubyn-code auth        # follow the prompt; stores key at ~/.rubyn/credentials
```

### Step 2 — Install the plugin

Follow the [Installation](#installation) section above.

### Step 3 — Open a Ruby or Rails project

Open any project that contains Ruby files. The plugin activates only when the
Ruby plugin detects a Ruby project.

### Step 4 — Verify the executable path

Open **Settings → Tools → Rubyn**.

- If `rubyn-code` is on your `PATH`, the **Executable path** field can be left blank.
- If you use a version manager (rbenv, rvm, asdf), the gem may not be on the
  system `PATH`. In that case, set the absolute path explicitly, e.g.:

  ```
  /Users/yourname/.rbenv/shims/rubyn-code
  ```

Click **Apply**.

### Step 5 — Open the chat panel

Press **`⌘⇧R`** (macOS) or **`Ctrl+Shift+R`** (Windows/Linux), or click the
**Rubyn** icon in the right sidebar. The tool window opens and rubyn-code starts
in the background.

The status bar widget in the bottom-right corner will show **● Rubyn** once the
agent is running.

### Step 6 — Send your first prompt

Type in the chat input and press **Return**. Try:

```
Explain this project's folder structure.
```

Or open a Ruby file, select a method, and press **`⌘⇧E`** to explain it directly.

---

## Feature Deep-Dive

### Chat Panel (Tool Window)

The Rubyn tool window is a JCEF-hosted webview that renders the same UI as
the standalone Rubyn desktop app. It docks to the right side of the IDE by
default and can be undocked, floated, or moved like any other JetBrains tool
window.

**Capabilities:**

- Free-form conversation with context about the open project
- Automatic inclusion of the active file, current selection, open tabs, and
  cursor position in every request
- Conversation history persisted per project session
- Syntax highlighting in code blocks

**Opening the panel:**

- Press the assigned shortcut (default `⌘⇧R` / `Ctrl+Shift+R`)
- Click **Tools → Rubyn → Open Chat**
- Click the status bar widget
- Use any editor action — the panel opens automatically before sending the prompt

### Editor Actions

All actions are available via:

- **Tools → Rubyn** main menu
- Right-click **context menu → Rubyn** in any editor

Actions that require a selection (`Explain`, `Refactor`) are greyed out in the
menu when nothing is selected.

#### Explain Selection (`⌘⇧E` / `Ctrl+Shift+E`)

Sends the selected text to Rubyn with an explanation prompt. If nothing is
selected, the current file is sent instead.

#### Refactor with Rubyn (`⌘⇧F` / `Ctrl+Shift+F`)

Sends the selected code with a refactoring prompt. Requires a non-empty
selection. The agent's response appears in the chat panel; proposed file edits
open the inline diff viewer.

#### Review with Rubyn

Sends the selection for a code review focused on correctness, performance,
and Ruby/Rails conventions. No keyboard shortcut by default — available from
the menu.

#### Generate Specs

Sends the current file with a prompt to generate RSpec specs. Rubyn loads
related models, factories, and routes automatically before generating.

#### Review Pull Request…

Prompts for a branch name and sends a PR review request. Rubyn reads the git
diff between the branch and `main` and returns a structured review.

#### Select Model…

Opens a quick picker to change the active AI provider and model without going
into Settings. The selection is persisted to plugin settings.

#### Open Chat (`⌘⇧R` / `Ctrl+Shift+R`)

Focuses or opens the tool window. Available at all times when a Ruby project
is open.

### Inline Diff Viewer

When rubyn-code proposes edits to one or more files, the plugin opens the IDE's
built-in diff viewer for each file with two additional toolbar buttons:

| Button | Icon | Effect |
|---|---|---|
| **Accept Edit** | ✓ (green checkmark) | Writes the proposed content to disk |
| **Reject Edit** | ✗ (red cross) | Discards the proposed change |

The diff viewer uses the standard IntelliJ diff panel — you can edit the right
side manually before accepting.

### Status Bar Widget

A small widget displayed in the IDE status bar (bottom-right area, after the
**Position** indicator).

| Display | Meaning |
|---|---|
| `● Rubyn` | rubyn-code is running and connected |
| `○ Rubyn` | rubyn-code is stopped |
| `● Rubyn  $0.02` | Running, with session cost shown |

Clicking the widget opens the Rubyn tool window. If the agent is stopped,
clicking it also triggers a start attempt.

### Notifications

The plugin surfaces the following IDE balloon notifications:

| Notification | Severity | Cause |
|---|---|---|
| **rubyn-code not found** | Error | Executable missing from PATH and configured path |
| **Not authenticated** | Warning | rubyn-code started but no API key found |
| **Process crashed** | Error | rubyn-code exited unexpectedly |
| **Failed to start** | Error | OS-level process launch failure |
| **Ruby version warning** | Warning | Project Ruby < 3.1 |
| **Bridge disconnected** | Error | JSON-RPC connection lost after retries |

Actionable notifications include **Open Settings** or **Restart** buttons where
relevant.

---

## Configuration Reference

Open **Settings → Tools → Rubyn**.

### General

| Field | Default | Notes |
|---|---|---|
| Executable path | _(blank)_ | Leave blank to search `PATH`. Supports file browser. |

The field validates on **Apply**: the path must be absolute, the file must
exist, and it must be executable. A warning is shown if the path is blank and
`rubyn-code` cannot be found on `PATH`.

### Provider & Model

| Field | Default | Notes |
|---|---|---|
| Provider | `anthropic` | One of `anthropic`, `openai`, `google` |
| Model | `claude-sonnet-4-5` | Dropdown is populated dynamically based on Provider |

**Available models:**

| Provider | Models |
|---|---|
| `anthropic` | `claude-opus-4-5`, `claude-sonnet-4-5`, `claude-haiku-4-5` |
| `openai` | `gpt-4o`, `gpt-4o-mini`, `o1`, `o3-mini` |
| `google` | `gemini-2.0-flash`, `gemini-2.0-pro` |

### Budget & Limits

| Field | Default | Notes |
|---|---|---|
| Token budget | `0` | Maximum tokens per session. `0` = unlimited. |
| Cost budget | `$0.00` | Maximum USD cost per session. `0.00` = unlimited. |
| Permission mode | `default` | See [Permission Modes](#permission-modes) below. |

---

## Permission Modes

The permission mode is passed to rubyn-code at startup and controls how the
agent handles actions that modify files or run commands.

| Mode | Behaviour |
|---|---|
| `default` | The agent asks for confirmation before writing files or running shell commands. |
| `acceptEdits` | File edits are applied automatically. Shell commands and other actions still prompt. |
| `bypassPermissions` | All actions proceed without any confirmation prompt. **Use with caution.** |
| `planOnly` | The agent produces plans and explanations but never writes files or runs commands. |

> Changing the permission mode takes effect the next time rubyn-code is started
> (i.e., after a restart or project reopen).

---

## Keyboard Shortcuts

Default bindings for all keymaps (macOS uses `⌘` where Windows/Linux use `Ctrl`):

| Action | macOS | Windows / Linux |
|---|---|---|
| Open Chat | `⌘⇧R` | `Ctrl+Shift+R` |
| Explain Selection | `⌘⇧E` | `Ctrl+Shift+E` |
| Refactor with Rubyn | `⌘⇧F` | `Ctrl+Shift+F` |

### ⚠️ `⌘⇧R` Collision in RubyMine

RubyMine (and IntelliJ IDEA) assign `⌘⇧R` / `Ctrl+Shift+R` to **Replace in
Files** by default. When the Rubyn plugin is installed, the IDE may show a
conflict warning.

**Resolution options:**

**Option A — Reassign Rubyn to a different shortcut**

1. Open **Settings → Keymap**.
2. Search for **Rubyn: Open Chat**.
3. Right-click → **Add Keyboard Shortcut**.
4. Choose an unoccupied binding (e.g. `⌘⇧Y`).
5. Remove the original `⌘⇧R` binding from the Rubyn action.

**Option B — Override Replace in Files**

If you rarely use Replace in Files from the keyboard:

1. Open **Settings → Keymap**.
2. Search for **Replace in Files**.
3. Remove the `⌘⇧R` binding or reassign it.

The Rubyn binding then takes precedence.

**Option C — Use the menu or sidebar**

If neither action needs a shortcut, simply use **Tools → Rubyn → Open Chat**
or click the tool window icon in the sidebar.

### Customising Shortcuts

All Rubyn actions are listed in **Settings → Keymap** under the **Rubyn** group.
They can be rebound or cleared like any other IDE action.

---

## Troubleshooting

### `rubyn-code` gem not found

**Symptom:** A red balloon notification: *"rubyn-code not found…"* appears on
project open.

**Causes and fixes:**

1. The gem is not installed.
   ```sh
   gem install rubyn-code
   # or
   bundle install  # if it's in your Gemfile
   ```

2. The gem is installed but not on the system `PATH` (common with rbenv/rvm/asdf).

   Find the gem binary:
   ```sh
   which rubyn-code
   # or
   rbenv which rubyn-code
   ```

   Then set that path explicitly in **Settings → Tools → Rubyn → Executable path**.

3. You are using bundler isolation (`bundle exec`). The shim may not be on PATH
   without `bundle exec`. Set the full path in settings.

---

### Authentication failure

**Symptom:** A yellow balloon notification: *"Rubyn is not authenticated…"*

**Fix:**

```sh
rubyn-code auth
```

Follow the prompt. Your API key is stored at `~/.rubyn/credentials` with `0600`
permissions. The plugin does not manage API keys — it reads only the executable
path from settings.

After authenticating, click **Restart** in the notification or reopen the project.

---

### JCEF unavailable — blank tool window

**Symptom:** The Rubyn tool window opens but shows nothing (blank white panel),
or an error about JCEF.

**Cause:** The IDE is running without JCEF support. This can happen in certain
remote development or headless configurations, or with some Linux distributions
that do not ship with the full JetBrains Runtime.

**Fixes:**

1. **Switch to the JetBrains Runtime (JBR)**

   Open **Help → Find Action → Switch Boot JDK / Runtime**, select the bundled
   JetBrains Runtime, and restart the IDE.

2. **Check remote development setup**

   JCEF is not supported in the remote backend. The plugin must be installed
   on the **local** IDE client (Gateway), not the remote host.

3. **Check Linux dependencies**

   On some Linux distributions JCEF requires:
   ```sh
   sudo apt install libgbm1 libasound2
   ```

---

### Process crash / Rubyn keeps restarting

**Symptom:** Repeated *"Rubyn process crashed"* notifications.

**Diagnosis:**

1. Check the rubyn-code log file. It is written to:
   ```
   <IDE system directory>/rubyn-logs/rubyn-code-<project-name>.log
   ```
   On macOS this is typically:
   ```
   ~/Library/Caches/JetBrains/RubyMine<version>/rubyn-logs/
   ```
   On Linux:
   ```
   ~/.cache/JetBrains/RubyMine<version>/rubyn-logs/
   ```

2. Look for errors like `LoadError`, missing gems, or stack traces.

**Common fixes:**

- The gem may have missing native extension dependencies. Try running
  `rubyn-code --ide --dir /tmp` in a terminal to see the raw error output.
- Update the gem: `gem update rubyn-code`
- Check that your Ruby version is ≥ 3.1.

The plugin attempts up to **3 automatic restarts** with exponential back-off
(2 s, 4 s, 8 s). After the third failure it stops retrying. Click **Restart**
in the notification to try again manually.

---

### Ruby plugin missing

**Symptom:** The Rubyn tool window does not appear in the sidebar at all, or
the plugin fails to load with an error about `org.jetbrains.plugins.ruby`.

**Cause:** The Rubyn plugin depends on the JetBrains Ruby plugin. IntelliJ IDEA
Community Edition does not include it; IntelliJ IDEA **Ultimate** is required,
or use RubyMine.

**Fix:**

- Switch to **IntelliJ IDEA Ultimate** (or RubyMine).
- Ensure the **Ruby** plugin is enabled in **Settings → Plugins → Installed**.

---

## Developer Setup

This section is for contributors building the plugin from source. For the full
contribution workflow see [CONTRIBUTING.md](../CONTRIBUTING.md).

### Requirements

- JDK 21 (the `gradlew` wrapper downloads the right toolchain automatically
  via Gradle's `jvmToolchain` configuration; no manual install needed in most
  cases)
- Git
- An internet connection on first build (Gradle downloads RubyMine SDK ~1 GB)

### Clone and build

```sh
git clone https://github.com/MatthewSuttles/rubyn-code-rubymine.git
cd rubyn-code-rubymine
./gradlew buildPlugin
```

The distributable `.zip` is written to `build/distributions/`.

### Run in a sandboxed IDE

```sh
./gradlew runIde
```

This downloads RubyMine 2024.3 (on first run) and launches a sandboxed instance
with the plugin installed. Open any Ruby project in that IDE instance to develop
against a real project.

### Run tests

```sh
./gradlew test
```

All Kotlin unit tests are under `src/test/kotlin/`. They use `MockK` and the
IntelliJ Platform test framework.

### JCEF debugging

When iterating on the webview UI (`webview-source/`), attach Chrome DevTools:

1. Start the sandboxed IDE with `./gradlew runIde`.
2. Open the Rubyn tool window.
3. In a Chromium-based browser, navigate to:
   ```
   chrome://inspect
   ```
4. Click **Configure…** and add `localhost:9222`.
5. The Rubyn webview appears under **Remote Target**. Click **inspect**.

DevTools has full access to the webview DOM, console, and network.

> The remote debugging port `9222` is set in `build.gradle.kts` via the
> `runIde.jvmArgs` block. Change it if the port conflicts.

### Project layout

```
rubyn-code-rubymine/
├── src/
│   ├── main/
│   │   ├── kotlin/com/rubyn/        # Plugin Kotlin source
│   │   │   ├── actions/             # Editor actions (refactor, explain, etc.)
│   │   │   ├── bridge/              # JSON-RPC 2.0 over stdio
│   │   │   ├── context/             # Context and project detection
│   │   │   ├── diff/                # Inline diff viewer
│   │   │   ├── notifications/       # RubynNotifier
│   │   │   ├── services/            # Process and project services
│   │   │   ├── settings/            # Settings panel, state, configurable
│   │   │   ├── startup/             # RubynStartupActivity
│   │   │   ├── statusbar/           # Status bar widget
│   │   │   └── toolwindow/          # JCEF tool window factory
│   │   └── resources/
│   │       ├── META-INF/
│   │       │   ├── plugin.xml       # Plugin descriptor
│   │       │   └── pluginIcon.svg
│   │       ├── icons/               # Action and tool window SVG icons
│   │       ├── messages/
│   │       │   └── RubynBundle.properties  # All user-facing strings
│   │       └── protocol.json        # JSON-RPC schema
│   └── test/
│       └── kotlin/com/rubyn/        # Unit tests (mirror src layout)
├── webview-source/                  # TypeScript/Vite webview UI
├── build.gradle.kts
├── gradle.properties                # Plugin version, platform version
└── settings.gradle.kts
```
