# Changelog

All notable changes to the Rubyn RubyMine plugin are documented here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
This project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

---

## [0.1.0] — 2024-12-01

### Added

- **Gradle project scaffold** — IntelliJ Platform Gradle Plugin 2.x build
  targeting RubyMine 2024.3+ and IntelliJ IDEA Ultimate 2024.3+.

- **Plugin manifest & bootstrap** — `plugin.xml` descriptor, `pluginIcon.svg`,
  `RubynBundle.properties` for all user-facing strings, `RubynPlugin.kt`
  application component, and `RubynStartupActivity` for project-open
  initialization.

- **Project-level service (`RubynProjectService`)** — per-project lifecycle
  coordinator. Wires together the process service, bridge, and context provider.
  Exposes `ensureRunning()` for notification action callbacks.

- **Settings service & configurable** — persistent `RubynSettingsState`
  (executable path, provider, model, token/cost budgets, permission mode),
  `RubynSettingsService` application-level service, and a Kotlin UI DSL v2
  settings panel at **Settings → Tools → Rubyn**. Supported providers:
  Anthropic, OpenAI, Google.

- **Process manager service (`RubynProcessService`)** — spawns and supervises
  the rubyn-code child process. Pre-flight checks for executable presence and
  Ruby ≥ 3.1 version requirement. Exponential back-off restart (up to 3
  attempts, delays 2 s / 4 s / 8 s). Auth error detection from stderr.
  Graceful SIGTERM → wait → SIGKILL teardown on project close.

- **JSON-RPC 2.0 bridge (`RubynBridge`)** — stdin/stdout framing with a
  dedicated reader thread, pending-request futures with 30 s timeout, and a
  `SharedFlow` for inbound server notifications.

- **Tool window with JCEF webview** — dockable Rubyn panel on the right
  sidebar. Hosts the shared webview UI via `JBCefBrowser`. Available as soon
  as a Ruby project is open.

- **Inline diff viewer** — `ProposedEdit`, `RubynDiffManager`, and
  Accept/Reject toolbar actions (`AcceptEditAction`, `RejectEditAction`) added
  to the IDE diff viewer toolbar. Accepting writes the proposed content to disk.

- **Context provider** — `ContextProvider` captures active file, selection,
  open tabs, cursor line, and workspace path inside a read action for safe
  background-thread use. `ProjectDetector` determines whether the current
  project is Ruby-based.

- **Status bar widget** — `RubynStatusBarWidget` displays agent running state
  and session cost. Collects `RubynProcessService.isRunning` `StateFlow`.
  Click opens the tool window.

- **Shared webview UI** — TypeScript/Vite `webview-source/` package. Chat
  component, host adapter for bidirectional IDE ↔ webview messaging, syntax
  highlighting via Highlight.js.

- **Editor actions** — `AbstractRubynAction` base class with context assembly
  and bridge dispatch. Six concrete actions: `OpenChatAction`,
  `ExplainCodeAction`, `RefactorCodeAction`, `ReviewCodeAction`,
  `GenerateSpecsAction`, `ReviewPRAction`. All registered in the **Tools →
  Rubyn** menu and editor context menu. Keyboard shortcuts: `⌘⇧R` (Open
  Chat), `⌘⇧E` (Explain), `⌘⇧F` (Refactor).

- **Notifications & error handling** — `RubynNotifier` with typed methods for
  all error scenarios: gem not found, not authenticated, process crashed, failed
  to start, Ruby version warning, bridge disconnected. Action buttons on
  relevant notifications (Open Settings, Restart).

- **CI/CD with GitHub Actions** — `build.yml` workflow runs on every push and
  PR: compiles, runs tests, and verifies the plugin with IntelliJ Plugin
  Verifier against RubyMine 2024.3, 2025.1, and IDEA Ultimate 2024.3.
  `release.yml` builds and publishes the signed plugin to the JetBrains
  Marketplace on version tag push.

- **Kotlin unit tests** — test suites covering `JsonRpcCodec`, `RubynBridge`,
  `ProjectDetector`, `ProposedEdit`, `RubynDiffManager`, and
  `RubynProcessService`. Uses `MockK` and the IntelliJ Platform test framework.

[Unreleased]: https://github.com/MatthewSuttles/rubyn-code-rubymine/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/MatthewSuttles/rubyn-code-rubymine/releases/tag/v0.1.0
