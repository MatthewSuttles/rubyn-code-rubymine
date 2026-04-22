# Contributing to Rubyn for RubyMine

Thanks for taking an interest in contributing. This document covers everything
you need to go from zero to a running development environment and explains how
to submit changes.

---

## Table of Contents

1. [Development Environment](#development-environment)
2. [Building](#building)
3. [Running the Plugin (`gradlew runIde`)](#running-the-plugin-gradlew-runide)
4. [Running Tests](#running-tests)
5. [JCEF Debugging with `chrome://inspect`](#jcef-debugging-with-chromeinspect)
6. [Code Conventions](#code-conventions)
7. [PR Guidelines](#pr-guidelines)

---

## Development Environment

### Required tools

| Tool | Version | Notes |
|---|---|---|
| JDK | 21 | Gradle downloads the correct toolchain automatically via `jvmToolchain`. You may also install manually — `sdkman`, `brew install openjdk@21`, or the JetBrains Runtime work. |
| Git | Any recent | — |
| Node.js | ≥ 20 | Only needed if you are modifying `webview-source/` |
| pnpm / npm | Any | Same — webview only |

You do **not** need RubyMine installed locally. `./gradlew runIde` downloads a
sandboxed IDE automatically.

### Clone

```sh
git clone https://github.com/MatthewSuttles/rubyn-code-rubymine.git
cd rubyn-code-rubymine
```

### Verify the build tools

```sh
./gradlew --version   # should print Gradle version and JVM info
```

If Gradle cannot find a compatible JDK, set `JAVA_HOME`:

```sh
export JAVA_HOME=/path/to/jdk-21
./gradlew --version
```

---

## Building

```sh
./gradlew buildPlugin
```

The distributable `.zip` is written to `build/distributions/`. This archive
can be installed in any compatible IDE via **Settings → Plugins → Install
Plugin from Disk…**

To compile without producing the distribution:

```sh
./gradlew compileKotlin
```

---

## Running the Plugin (`gradlew runIde`)

```sh
./gradlew runIde
```

On first run this downloads the RubyMine SDK (~1 GB). Subsequent runs use the
cached SDK.

A sandboxed RubyMine instance opens with the plugin installed. Open any Ruby
project inside it to develop against real IDE APIs.

### Useful `runIde` properties

Set these in `gradle.properties` or pass as `-P` flags:

| Property | Default | Effect |
|---|---|---|
| `platformVersion` | `2024.3` | RubyMine version to run against |
| `javaVersion` | `21` | JVM version used for the plugin JVM toolchain |

To test against a different RubyMine version temporarily:

```sh
./gradlew runIde -PplatformVersion=2025.1
```

### Hot reload

IntelliJ Platform does not support hot-reload of Kotlin code. After changing
any `.kt` file, stop the sandboxed IDE and run `./gradlew runIde` again.

Webview changes (`webview-source/`) can be reloaded without restarting the
sandboxed IDE — rebuild the webview bundle and reload the tool window in the
sandbox:

```sh
cd webview-source && npm run build
# then in the sandboxed IDE: Tools → Rubyn → Reload Webview (or close/reopen the project)
```

---

## Running Tests

```sh
./gradlew test
```

Tests live in `src/test/kotlin/com/rubyn/` and mirror the main source layout.
They use:

- **MockK** for mocking
- **IntelliJ Platform test framework** (`BasePlatformTestCase` and friends)

To run a single test class:

```sh
./gradlew test --tests "com.rubyn.bridge.JsonRpcCodecTest"
```

CI runs the full test suite on every push and PR. See
`.github/workflows/build.yml` for the matrix configuration.

---

## JCEF Debugging with `chrome://inspect`

The Rubyn tool window is a JCEF-hosted browser. When iterating on the webview
UI you can attach Chrome DevTools for full DOM/JS/network inspection.

### Steps

1. **Enable remote debugging**

   The `runIde` task already passes the required JVM argument:

   ```
   -Dide.browser.jcef.debug.port=9222
   ```

   This is set in `build.gradle.kts` inside the `runIde` block. Change the
   port there if 9222 is in use on your machine.

2. **Start the sandboxed IDE**

   ```sh
   ./gradlew runIde
   ```

3. **Open the Rubyn tool window** in the sandboxed IDE.

4. **Open DevTools in Chromium**

   In any Chromium-based browser (Chrome, Edge, Brave) navigate to:

   ```
   chrome://inspect
   ```

5. **Configure the remote target**

   Click **Configure…** and add:

   ```
   localhost:9222
   ```

   Click **Done**.

6. **Inspect the webview**

   Under **Remote Target**, you will see a target named something like
   `rubyn-chat` or the webview page title. Click **inspect** to open a
   full DevTools panel.

### Notes

- DevTools has access to the full webview — DOM, console, network, storage,
  and breakpoints in the bundled JS.
- The webview source maps are included in development builds (`npm run dev`
  inside `webview-source/`). Production builds (`npm run build`) are minified
  but source maps are still emitted.
- JCEF debugging only works when running via `./gradlew runIde`. A locally
  installed IDE instance requires the same JVM argument passed to it via
  **Help → Edit Custom VM Options**.

---

## Code Conventions

### Kotlin

- Match the style of the file you are editing. The codebase uses standard
  Kotlin conventions with no auto-formatter enforced at the repo level, but
  please keep line length ≤ 120 characters and use 4-space indentation.
- Every public method must have a KDoc comment. Internal helpers that are not
  self-evident should have one too.
- Explicit over clever. Use readable conditionals rather than chained
  `let`/`also`/`run` chains when a plain `if` is clearer.
- Handle edge cases. Null inputs, missing services, and unexpected states
  should be caught at the boundary and converted to a log message + a safe
  default or an early return. Do not let `NullPointerException` or
  `ClassCastException` surface to the user.

### User-facing strings

All user-visible text lives in `src/main/resources/messages/RubynBundle.properties`.
Do not hard-code strings in Kotlin source. Use `RubynBundle.message("key")`.

### Tests

Write a test for every new public method and every error path. Prefer narrow
unit tests over broad integration tests for service logic. Use MockK for
dependencies; avoid using the real file system or real processes in unit tests.

---

## PR Guidelines

1. **Branch naming** — use `feat/`, `fix/`, `docs/`, `test/`, or `chore/`
   prefixes followed by a short slug, e.g. `feat/select-model-action`.

2. **One concern per PR** — keep PRs focused. A PR that adds a feature should
   not also refactor unrelated code.

3. **Pass CI before requesting review** — the build, test, and plugin verifier
   steps must all be green. Fix failures before marking the PR ready.

4. **PR description** — include:
   - What the change does and why
   - Any non-obvious design decisions
   - Testing notes (how to manually verify, or why the automated tests are sufficient)

5. **Changelog** — add an entry under `[Unreleased]` in `CHANGELOG.md` for
   any user-visible change (new feature, bug fix, behavioural change).
   Internal refactors and test-only changes do not need a changelog entry.

6. **Commit messages** — use the imperative mood in the subject line
   (`Add model selector action`, not `Added model selector action`). Keep the
   subject ≤ 72 characters. Add a body if context is needed.

7. **Review turnaround** — a maintainer will review open PRs within 2 business
   days. Respond to review comments within a week to keep the PR from going stale.
