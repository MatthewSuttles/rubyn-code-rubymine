package com.rubyn.services

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.rubyn.notifications.RubynNotifier
import com.rubyn.settings.RubynSettingsService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.nio.charset.Charset
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

private val LOG = logger<RubynProcessService>()

/** Maximum number of automatic restart attempts after an unexpected crash. */
private const val MAX_RESTART_ATTEMPTS = 3

/** Base delay (ms) for the first restart attempt. Doubles on each retry. */
private const val BASE_RESTART_DELAY_MS = 2_000L

/** How long (ms) to wait for the process to exit gracefully before force-killing. */
private const val GRACEFUL_STOP_TIMEOUT_MS = 5_000L

/** Minimum Ruby version required by rubyn-code. */
private const val MIN_RUBY_MAJOR = 3
private const val MIN_RUBY_MINOR = 1

/**
 * Per-project service managing the rubyn-code child process lifecycle.
 *
 * Registered as a project-level service in plugin.xml — one instance per open
 * project. Access via:
 *   project.getService(RubynProcessService::class.java)
 *
 * ## Lifecycle
 * Call [start] to spawn the process. The service automatically restarts on
 * unexpected exits (up to [MAX_RESTART_ATTEMPTS]) with exponential back-off.
 * Call [stop] for a graceful shutdown. [dispose] is called by the platform
 * when the project closes; it performs a graceful stop and waits for exit.
 *
 * ## Pre-flight checks
 * Before spawning, [start] verifies:
 * 1. The rubyn-code executable is present (configured path, then PATH search).
 * 2. The project Ruby version meets the minimum requirement.
 *
 * Auth errors detected in stderr output trigger [RubynNotifier.notAuthenticated]
 * and suppress further auto-restart.
 *
 * ## Thread safety
 * The intrinsic lock guards state mutation only — it is never held during blocking
 * I/O ([handler.waitFor], [checkRubyVersion], [logWriter.close]).
 *
 * Pattern for operations that both mutate state AND do blocking work:
 *   1. Acquire lock, snapshot/mutate state, release lock.
 *   2. Do blocking I/O outside the lock.
 *
 * This eliminates the deadlock where [processTerminated] (called by the OSProcessHandler
 * notifier thread while the handler holds its own internal monitor) tries to acquire
 * this lock while [stop]/[restart]/[dispose] hold this lock and block inside
 * [handler.waitFor].
 *
 * ## Intentional vs. unexpected exit detection
 * [stop], [restart], and [dispose] all call [detachHandler] under the lock, which
 * sets [processHandler] to null before the process actually exits. [processTerminated]
 * checks [processHandler] == null to detect that the exit was intentional and skips
 * crash notifications and auto-restart scheduling.
 */
@Service(Service.Level.PROJECT)
class RubynProcessService(private val project: Project) : Disposable {

    // ── State (all guarded by intrinsic lock) ─────────────────────────────

    private val _isRunning = MutableStateFlow(false)

    /**
     * Emits `true` while the rubyn-code process is alive, `false` otherwise.
     * Observers (e.g. the status bar widget) should collect this flow.
     */
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private var processHandler: OSProcessHandler? = null
    private var executablePath: String? = null
    private var authErrorDetected = false
    private var restartAttempts = 0
    private var pendingRestart: ScheduledFuture<*>? = null
    private var disposed = false

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "rubyn-process-scheduler-${project.name}").also { it.isDaemon = true }
    }

    // ── Log file ──────────────────────────────────────────────────────────

    /**
     * Stderr from rubyn-code is written here. The file lives in the IDE log
     * directory so it survives across sessions without cluttering the project.
     *
     * Kept open for the lifetime of the service to avoid per-line FileWriter
     * allocation. Access is confined to the OSProcessHandler notifier thread
     * via [appendToLogFile]; no synchronization needed for writes.
     *
     * Nullable so that [dispose] can skip closing it when the log was never
     * opened — avoids opening a file just to immediately close it.
     */
    private var logWriter: BufferedWriter? = null

    private fun getOrOpenLogWriter(): BufferedWriter {
        logWriter?.let { return it }
        val logDir = File(System.getProperty("idea.system.path", System.getProperty("java.io.tmpdir")), "rubyn-logs")
        logDir.mkdirs()
        val logFile = File(logDir, "rubyn-code-${sanitizeProjectName(project.name)}.log")
        return BufferedWriter(FileWriter(logFile, /* append = */ true)).also { logWriter = it }
    }

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Starts the rubyn-code process if it is not already running.
     *
     * Pre-flight checks ([resolveExecutable], [checkRubyVersion]) run outside
     * the lock because they may block. State is re-checked under the lock before
     * spawning to handle concurrent calls safely.
     *
     * Thread-safe — safe to call from any thread, including the pooled thread
     * used by notification action buttons.
     */
    fun start() {
        // Phase 1: check whether we should proceed — under lock, no blocking I/O.
        synchronized(this) {
            if (disposed) return
            if (_isRunning.value) {
                LOG.info("start() called but process is already running")
                return
            }
        }

        LOG.info("RubynProcessService: starting rubyn-code for project '${project.name}'")

        // Phase 2: pre-flight checks — outside lock, may block up to 5s.
        val executable = resolveExecutable() ?: run {
            LOG.warn("rubyn-code executable not found — notifying user")
            RubynNotifier.gemNotFound(project)
            return
        }

        if (!checkRubyVersion()) {
            // checkRubyVersion shows the notification internally.
            return
        }

        // Phase 3: re-acquire lock, re-check state, then spawn.
        synchronized(this) {
            if (disposed || _isRunning.value) return
            executablePath = executable
            authErrorDetected = false
            spawnProcess(executable)
        }
    }

    /**
     * Stops the process gracefully (SIGTERM → wait → SIGKILL).
     * Cancels any pending auto-restart. Thread-safe.
     */
    fun stop() {
        // Snapshot the handler under lock; blocking wait happens outside.
        val handler: OSProcessHandler?
        synchronized(this) {
            cancelPendingRestart()
            restartAttempts = 0
            handler = detachHandler()
        }
        awaitTermination(handler)
    }

    /**
     * Returns the stdin and stdout streams of the running process, or null if
     * the process is not currently alive.
     *
     * The caller (typically [com.rubyn.bridge.RubynBridge]) owns these streams
     * for reading/writing — do not close them directly; dispose the bridge and
     * then call [stop] to tear down the process cleanly.
     *
     * Thread-safe — snaps the handler under the lock.
     */
    fun getProcessStreams(): ProcessStreams? {
        val handler = synchronized(this) { processHandler } ?: return null
        if (handler.isProcessTerminated) return null
        return ProcessStreams(
            stdin = handler.process.outputStream,
            stdout = handler.process.inputStream,
        )
    }

    /**
     * Stops then starts the process. Thread-safe.
     *
     * Must be dispatched off the EDT when called in response to user interaction
     * (e.g. a notification action button) because [checkRubyVersion] can block
     * up to 5 seconds waiting for `ruby --version` to complete.
     */
    fun restart() {
        LOG.info("RubynProcessService.restart() called for project '${project.name}'")

        // Phase 1: tear down current process state under lock — no blocking I/O.
        val handler: OSProcessHandler?
        synchronized(this) {
            cancelPendingRestart()
            restartAttempts = 0
            authErrorDetected = false
            handler = detachHandler()
        }

        // Phase 2: wait for termination outside lock.
        awaitTermination(handler)

        // Phase 3: start() handles its own locking and preflight.
        start()
    }

    // ── Disposal ──────────────────────────────────────────────────────────

    override fun dispose() {
        LOG.info("RubynProcessService disposing for project '${project.name}'")

        // Phase 1: mark disposed, cancel scheduler work, snapshot handler — all under lock.
        val handler: OSProcessHandler?
        synchronized(this) {
            disposed = true
            cancelPendingRestart()
            scheduler.shutdownNow()
            handler = detachHandler()
        }

        // Phase 2: terminate the process. If we're on the EDT (which happens when
        // the user closes the project window), we must NOT block — IntelliJ flags
        // synchronous process waits on the EDT as a SEVERE error and it can deadlock.
        // Fire-and-forget on a pooled thread instead; the OS will reap the child.
        if (ApplicationManager.getApplication().isDispatchThread) {
            ApplicationManager.getApplication().executeOnPooledThread {
                awaitTermination(handler)
                runCatching { logWriter?.close() }
            }
        } else {
            awaitTermination(handler)
            runCatching { logWriter?.close() }
        }
    }

    // ── Pre-flight checks ─────────────────────────────────────────────────

    /**
     * Returns the absolute path to the rubyn-code executable or null if not found.
     *
     * Resolution order:
     *   1. executablePath from [RubynSettingsService] (if non-blank and the file exists).
     *   2. PATH search for rubyn-code.
     *   3. `which`/`command -v` via the user's login shell (picks up .zshrc/.bashrc paths).
     *   4. Common Ruby tool-chain locations (rbenv, asdf, chruby, RVM, Homebrew, system gem).
     *
     * Steps 3–4 are necessary because macOS GUI apps (launched from Dock/Spotlight)
     * inherit a minimal PATH that excludes ~/.rbenv/shims, ~/.asdf/shims, etc.
     *
     * Does not require the lock — reads only from settings and the filesystem.
     */
    private fun resolveExecutable(): String? {
        val configured = RubynSettingsService.getInstance().settings().executablePath.trim()
        if (configured.isNotBlank()) {
            val f = File(configured)
            if (f.isFile && f.canExecute()) {
                LOG.info("Using configured rubyn-code path: $configured")
                return configured
            }
            LOG.warn("Configured path '$configured' not found or not executable — falling back to PATH")
        }

        // 1. Standard JVM PATH search.
        val pathDirs = System.getenv("PATH")?.split(File.pathSeparator) ?: emptyList()
        for (dir in pathDirs) {
            val candidate = File(dir, "rubyn-code")
            if (candidate.isFile && candidate.canExecute()) {
                LOG.info("Found rubyn-code on PATH: ${candidate.absolutePath}")
                return candidate.absolutePath
            }
        }

        // 2. Ask the user's login shell — picks up .zshrc/.bashrc PATH additions.
        resolveViaLoginShell()?.let { return it }

        // 3. Probe well-known Ruby tool-chain directories.
        val home = System.getProperty("user.home") ?: ""
        val wellKnownDirs = listOf(
            // rbenv
            "$home/.rbenv/shims",
            // asdf
            "$home/.asdf/shims",
            // chruby — shims aren't used, but gems land here
            "$home/.gem/ruby/bin",
            // RVM
            "$home/.rvm/bin",
            "$home/.rvm/gems/default/bin",
            // Homebrew (Apple Silicon + Intel)
            "/opt/homebrew/bin",
            "/usr/local/bin",
            // System gem bin (Linux)
            "/usr/local/lib/ruby/gems/bin",
            "$home/.local/share/gem/ruby/bin",
        )
        for (dir in wellKnownDirs) {
            val candidate = File(dir, "rubyn-code")
            if (candidate.isFile && candidate.canExecute()) {
                LOG.info("Found rubyn-code in well-known location: ${candidate.absolutePath}")
                return candidate.absolutePath
            }
        }

        // 4. Glob rbenv/asdf versioned directories (e.g. ~/.rbenv/versions/3.3.0/bin).
        listOf("$home/.rbenv/versions", "$home/.asdf/installs/ruby").forEach { base ->
            val versionsDir = File(base)
            if (versionsDir.isDirectory) {
                versionsDir.listFiles()
                    ?.sortedDescending() // highest version first
                    ?.forEach { versionDir ->
                        val candidate = File(versionDir, "bin/rubyn-code")
                        if (candidate.isFile && candidate.canExecute()) {
                            LOG.info("Found rubyn-code in versioned dir: ${candidate.absolutePath}")
                            return candidate.absolutePath
                        }
                    }
            }
        }

        LOG.warn("rubyn-code not found on PATH ($pathDirs) or well-known locations")
        return null
    }

    /**
     * Runs `command -v rubyn-code` in the user's login shell to resolve the
     * executable through the full shell environment (.zshrc, .bashrc, etc.).
     *
     * Returns the resolved path or null if the command fails or times out.
     */
    private fun resolveViaLoginShell(): String? {
        return try {
            val shell = System.getenv("SHELL") ?: "/bin/zsh"
            val process = ProcessBuilder(shell, "-l", "-c", "command -v rubyn-code")
                .redirectErrorStream(true)
                .start()

            val finished = process.waitFor(5, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                LOG.debug("Login shell lookup timed out")
                return null
            }
            if (process.exitValue() != 0) return null

            val path = process.inputStream.use { it.bufferedReader().readLine()?.trim() }
            if (!path.isNullOrBlank()) {
                val f = File(path)
                if (f.isFile && f.canExecute()) {
                    LOG.info("Found rubyn-code via login shell: $path")
                    return path
                }
            }
            null
        } catch (e: Exception) {
            LOG.debug("Login shell lookup failed: ${e.message}")
            null
        }
    }

    /**
     * Returns true if the Ruby version in the project meets the minimum requirement.
     * Shows a warning notification and returns false when the version is too old.
     * Returns true (permissive) if the version cannot be determined.
     *
     * **Blocking** — may wait up to 5 seconds. Must NOT be called on the EDT and
     * must NOT be called while holding the intrinsic lock.
     */
    private fun checkRubyVersion(): Boolean {
        val rubyBinary = findRubyBinary() ?: return true // can't check — proceed

        return try {
            val process = ProcessBuilder(rubyBinary, "--version")
                .redirectErrorStream(true)
                .start()

            val finished = process.waitFor(5, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                LOG.info("ruby --version timed out — skipping version check")
                return true
            }

            val result = process.inputStream.use { it.bufferedReader().readLine() } ?: return true

            // "ruby 3.2.2 (2023-03-30 revision e51014f9c0) [x86_64-linux]"
            val versionString = result.removePrefix("ruby ").substringBefore(" ")
            val parts = versionString.split(".")
            val major = parts.getOrNull(0)?.toIntOrNull() ?: return true
            val minor = parts.getOrNull(1)?.toIntOrNull() ?: return true

            val meetsRequirement = major > MIN_RUBY_MAJOR ||
                (major == MIN_RUBY_MAJOR && minor >= MIN_RUBY_MINOR)

            if (!meetsRequirement) {
                LOG.warn("Ruby version too old: $versionString (need >= $MIN_RUBY_MAJOR.$MIN_RUBY_MINOR)")
                ApplicationManager.getApplication().invokeLater {
                    RubynNotifier.rubyVersionWarning(project, versionString)
                }
            }

            meetsRequirement
        } catch (e: Exception) {
            LOG.info("Could not check Ruby version: ${e.message}")
            true // non-blocking — proceed even if check fails
        }
    }

    private fun findRubyBinary(): String? {
        val pathDirs = System.getenv("PATH")?.split(File.pathSeparator) ?: emptyList()
        for (dir in pathDirs) {
            val f = File(dir, "ruby")
            if (f.isFile && f.canExecute()) return f.absolutePath
        }
        // Fall back to login shell lookup — same reason as resolveExecutable.
        return try {
            val shell = System.getenv("SHELL") ?: "/bin/zsh"
            val process = ProcessBuilder(shell, "-l", "-c", "command -v ruby")
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(5, TimeUnit.SECONDS)
            if (!finished) { process.destroyForcibly(); return null }
            if (process.exitValue() != 0) return null
            val path = process.inputStream.use { it.bufferedReader().readLine()?.trim() }
            if (!path.isNullOrBlank() && File(path).let { it.isFile && it.canExecute() }) path else null
        } catch (_: Exception) { null }
    }

    // ── Process spawning ──────────────────────────────────────────────────

    /**
     * Spawns the process and attaches the lifecycle listener.
     *
     * Caller must hold the intrinsic lock. Non-blocking beyond initial process
     * creation (OSProcessHandler.startNotify returns immediately).
     */
    private fun spawnProcess(executable: String) {
        val projectDir = project.basePath ?: run {
            LOG.warn("Project has no base path — cannot start rubyn-code")
            return
        }

        val commandLine = GeneralCommandLine(executable)
            .withParameters("--ide", "--dir", projectDir)
            .withWorkDirectory(projectDir)
            .withCharset(Charset.forName("UTF-8"))

        val handler = try {
            OSProcessHandler(commandLine)
        } catch (e: Exception) {
            LOG.error("Failed to start rubyn-code: ${e.message}", e)
            ApplicationManager.getApplication().invokeLater {
                RubynNotifier.processFailedToStart(project)
            }
            return
        }

        handler.addProcessListener(ProcessLifecycleListener())
        processHandler = handler
        handler.startNotify()

        _isRunning.value = true
        LOG.info("rubyn-code started (pid=${processHandlerPid(handler)})")
    }

    // ── Process termination ───────────────────────────────────────────────

    /**
     * Removes the current handler from state and clears [_isRunning].
     * Returns the handler so the caller can call [awaitTermination] outside the lock.
     *
     * Setting [processHandler] to null here is the signal that [processTerminated]
     * uses to detect an intentional shutdown — it skips crash notifications and
     * auto-restart when it sees [processHandler] == null.
     *
     * Caller must hold the intrinsic lock.
     */
    private fun detachHandler(): OSProcessHandler? {
        val handler = processHandler ?: return null
        processHandler = null
        _isRunning.value = false
        return handler
    }

    /**
     * Sends SIGTERM and waits [GRACEFUL_STOP_TIMEOUT_MS] for the process to exit
     * before force-destroying it.
     *
     * Must be called **outside** the intrinsic lock. [processTerminated] fires on
     * the OSProcessHandler notifier thread and tries to acquire this lock; if we
     * hold the lock here and block on [handler.waitFor], we deadlock.
     *
     * Safe to call with a null handler — returns immediately.
     */
    private fun awaitTermination(handler: OSProcessHandler?) {
        if (handler == null || handler.isProcessTerminated) return

        LOG.info("Terminating rubyn-code process...")
        try {
            handler.destroyProcess()
            if (!handler.waitFor(GRACEFUL_STOP_TIMEOUT_MS)) {
                LOG.warn("rubyn-code did not exit within ${GRACEFUL_STOP_TIMEOUT_MS}ms — force-killing")
                handler.process.destroyForcibly()
            }
        } catch (e: Exception) {
            LOG.warn("Error while terminating rubyn-code: ${e.message}", e)
            runCatching { handler.process.destroyForcibly() }
        }

        LOG.info("rubyn-code terminated")
    }

    // ── Auto-restart ──────────────────────────────────────────────────────

    /**
     * Schedules an automatic restart after the appropriate back-off delay.
     *
     * Must be called while holding the intrinsic lock. The scheduler callback
     * re-acquires the lock before touching any state, and calls [spawnProcess]
     * without any blocking I/O — no deadlock risk.
     *
     * When [executablePath] is null (unlikely after a crash), the callback
     * dispatches [start] on a pooled thread so that preflight checks run outside
     * both the scheduler thread and this lock.
     */
    private fun scheduleRestart() {
        if (disposed) return
        if (authErrorDetected) {
            LOG.info("Auth error detected — suppressing auto-restart")
            return
        }

        restartAttempts++
        val attempt = restartAttempts
        if (attempt > MAX_RESTART_ATTEMPTS) {
            LOG.warn("rubyn-code crashed $attempt times — giving up auto-restart")
            return
        }

        val delayMs = BASE_RESTART_DELAY_MS * (1L shl (attempt - 1)) // 2s, 4s, 8s
        LOG.info("Scheduling rubyn-code restart (attempt $attempt/$MAX_RESTART_ATTEMPTS) in ${delayMs}ms")

        pendingRestart = scheduler.schedule({
            val exeSnapshot: String?
            synchronized(this) {
                if (disposed || _isRunning.value) return@schedule
                exeSnapshot = executablePath
                if (exeSnapshot != null) {
                    spawnProcess(exeSnapshot)
                    return@schedule
                }
            }
            // executablePath was null — must run start() outside the lock because
            // start() acquires the lock itself and calls blocking pre-flight checks.
            ApplicationManager.getApplication().executeOnPooledThread { start() }
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun cancelPendingRestart() {
        pendingRestart?.cancel(false)
        pendingRestart = null
    }

    // ── Stderr logging ────────────────────────────────────────────────────

    /**
     * Writes [text] to the persistent log file.
     *
     * Called exclusively from the OSProcessHandler notifier thread. Swallows
     * I/O errors silently — the IDE diagnostic log captures the failure.
     */
    private fun appendToLogFile(text: String) {
        try {
            getOrOpenLogWriter().let { writer ->
                writer.write(text)
                writer.flush()
            }
        } catch (e: Exception) {
            LOG.debug("Failed to write to rubyn log file: ${e.message}")
        }
    }

    private fun detectAuthError(line: String): Boolean {
        val lower = line.lowercase()
        return lower.contains("unauthorized") ||
            lower.contains("forbidden") ||
            (lower.contains("authentication") && lower.contains("failed")) ||
            lower.contains("auth error")
    }

    // ── Process listener ──────────────────────────────────────────────────

    private inner class ProcessLifecycleListener : ProcessAdapter() {

        override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
            val text = event.text ?: return

            if (ProcessOutputType.isStderr(outputType)) {
                appendToLogFile(text)
                LOG.debug("[rubyn-code stderr] ${text.trimEnd()}")

                // Double-checked read of authErrorDetected — the outer read is
                // unsynchronized (cheap fast path); the inner write is under lock.
                if (!authErrorDetected && detectAuthError(text)) {
                    val firstDetection: Boolean
                    synchronized(this@RubynProcessService) {
                        firstDetection = !authErrorDetected
                        if (firstDetection) authErrorDetected = true
                    }
                    if (firstDetection) {
                        LOG.warn("Auth error detected in rubyn-code stderr: ${text.trimEnd()}")
                        ApplicationManager.getApplication().invokeLater {
                            RubynNotifier.notAuthenticated(project)
                        }
                    }
                }
            }
        }

        override fun processTerminated(event: ProcessEvent) {
            val exitCode = event.exitCode
            LOG.info("rubyn-code exited with code $exitCode for project '${project.name}'")

            // Acquire lock only for state mutation. No blocking I/O inside this block.
            synchronized(this@RubynProcessService) {
                _isRunning.value = false

                // If processHandler is null, stop()/restart()/dispose() detached it
                // intentionally before this callback fired. The exit was expected —
                // skip crash notifications and auto-restart to avoid spurious alerts.
                if (processHandler == null) {
                    LOG.info("rubyn-code exit was intentional (handler already detached) — no restart")
                    return
                }

                // Unexpected exit: clear the handler and decide whether to restart.
                processHandler = null

                if (disposed) return

                if (exitCode != 0) {
                    LOG.warn("rubyn-code exited unexpectedly (code=$exitCode)")
                    // Notification dispatched off-lock via invokeLater — safe.
                    ApplicationManager.getApplication().invokeLater {
                        RubynNotifier.processCrashed(project)
                    }
                    scheduleRestart()
                } else {
                    // Clean exit — reset counter so next explicit start gets full retries.
                    restartAttempts = 0
                }
            }
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────

    private fun processHandlerPid(handler: OSProcessHandler): Long? =
        runCatching { handler.process.pid() }.getOrNull()

    private fun sanitizeProjectName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(64)
}

// ── Process stream handle ─────────────────────────────────────────────────────

/**
 * Pair of I/O streams for the running rubyn-code process.
 *
 * [stdin] is the process's input — write JSON-RPC requests here.
 * [stdout] is the process's output — read JSON-RPC responses/notifications here.
 */
data class ProcessStreams(
    val stdin: java.io.OutputStream,
    val stdout: java.io.InputStream,
)
