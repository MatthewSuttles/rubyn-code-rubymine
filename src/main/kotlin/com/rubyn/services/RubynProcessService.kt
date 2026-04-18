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
import java.io.File
import java.io.FileWriter
import java.nio.charset.Charset
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
 */
@Service(Service.Level.PROJECT)
class RubynProcessService(private val project: Project) : Disposable {

    // ── State ─────────────────────────────────────────────────────────────

    private val _isRunning = MutableStateFlow(false)

    /**
     * Emits `true` while the rubyn-code process is alive, `false` otherwise.
     * Observers (e.g. the status bar widget) should collect this flow.
     */
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    @Volatile
    private var processHandler: OSProcessHandler? = null

    @Volatile
    private var executablePath: String? = null

    @Volatile
    private var authErrorDetected = false

    private val restartAttempts = AtomicInteger(0)

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "rubyn-process-scheduler-${project.name}").also { it.isDaemon = true }
    }

    @Volatile
    private var pendingRestart: ScheduledFuture<*>? = null

    @Volatile
    private var disposed = false

    // ── Log file ──────────────────────────────────────────────────────────

    /**
     * Stderr from rubyn-code is written here. The file lives in the IDE log
     * directory so it survives across sessions without cluttering the project.
     */
    private val logFile: File by lazy {
        val logDir = File(System.getProperty("idea.system.path", System.getProperty("java.io.tmpdir")), "rubyn-logs")
        logDir.mkdirs()
        File(logDir, "rubyn-code-${sanitizeProjectName(project.name)}.log")
    }

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Starts the rubyn-code process if it is not already running.
     *
     * Runs pre-flight checks first. Notifies the user and returns early if any
     * check fails. Thread-safe — safe to call from any thread.
     */
    @Synchronized
    fun start() {
        if (disposed) return
        if (_isRunning.value) {
            LOG.info("RubynProcessService.start() called but process is already running")
            return
        }

        LOG.info("RubynProcessService: starting rubyn-code for project '${project.name}'")

        val executable = resolveExecutable() ?: run {
            LOG.warn("rubyn-code executable not found — notifying user")
            RubynNotifier.gemNotFound(project)
            return
        }

        if (!checkRubyVersion()) {
            // checkRubyVersion shows the notification internally
            return
        }

        executablePath = executable
        authErrorDetected = false
        spawnProcess(executable)
    }

    /**
     * Stops the process gracefully (SIGTERM → wait → SIGKILL).
     * Cancels any pending auto-restart. Thread-safe.
     */
    @Synchronized
    fun stop() {
        cancelPendingRestart()
        restartAttempts.set(0)
        terminateProcess()
    }

    /**
     * Stops then starts the process. Thread-safe.
     */
    @Synchronized
    fun restart() {
        LOG.info("RubynProcessService.restart() called for project '${project.name}'")
        cancelPendingRestart()
        restartAttempts.set(0)
        authErrorDetected = false
        terminateProcess()
        val executable = executablePath ?: run {
            start()
            return
        }
        spawnProcess(executable)
    }

    // ── Disposal ──────────────────────────────────────────────────────────

    override fun dispose() {
        disposed = true
        LOG.info("RubynProcessService disposing for project '${project.name}'")
        cancelPendingRestart()
        scheduler.shutdownNow()
        terminateProcess()
    }

    // ── Pre-flight checks ─────────────────────────────────────────────────

    /**
     * Returns the absolute path to the rubyn-code executable or null if not found.
     *
     * Resolution order:
     *   1. `executablePath` from [RubynSettingsService] (if non-blank and the file exists).
     *   2. PATH search for `rubyn-code`.
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

        val pathDirs = System.getenv("PATH")?.split(File.pathSeparator) ?: emptyList()
        for (dir in pathDirs) {
            val candidate = File(dir, "rubyn-code")
            if (candidate.isFile && candidate.canExecute()) {
                LOG.info("Found rubyn-code on PATH: ${candidate.absolutePath}")
                return candidate.absolutePath
            }
        }

        LOG.warn("rubyn-code not found on PATH: $pathDirs")
        return null
    }

    /**
     * Returns true if the Ruby version in the project meets the minimum requirement.
     * Shows a warning notification and returns false when the version is too old.
     * Returns true (permissive) if the version cannot be determined.
     */
    private fun checkRubyVersion(): Boolean {
        val rubyBinary = findRubyBinary() ?: return true // can't check — proceed

        return try {
            val result = ProcessBuilder(rubyBinary, "--version")
                .redirectErrorStream(true)
                .start()
                .also { it.waitFor(5, TimeUnit.SECONDS) }
                .inputStream
                .bufferedReader()
                .readLine() ?: return true

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
        val candidates = listOf("ruby")
        val pathDirs = System.getenv("PATH")?.split(File.pathSeparator) ?: emptyList()
        for (name in candidates) {
            for (dir in pathDirs) {
                val f = File(dir, name)
                if (f.isFile && f.canExecute()) return f.absolutePath
            }
        }
        return null
    }

    // ── Process spawning ──────────────────────────────────────────────────

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
     * Sends SIGTERM and waits [GRACEFUL_STOP_TIMEOUT_MS] for the process to
     * exit before force-destroying it. Sets [_isRunning] to false.
     */
    private fun terminateProcess() {
        val handler = processHandler ?: return
        processHandler = null
        _isRunning.value = false

        if (handler.isProcessTerminated) return

        LOG.info("Terminating rubyn-code process…")
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

    private fun scheduleRestart() {
        if (disposed) return
        if (authErrorDetected) {
            LOG.info("Auth error detected — suppressing auto-restart")
            return
        }

        val attempt = restartAttempts.incrementAndGet()
        if (attempt > MAX_RESTART_ATTEMPTS) {
            LOG.warn("rubyn-code crashed $attempt times — giving up auto-restart")
            return
        }

        val delayMs = BASE_RESTART_DELAY_MS * (1L shl (attempt - 1)) // 2s, 4s, 8s
        LOG.info("Scheduling rubyn-code restart (attempt $attempt/$MAX_RESTART_ATTEMPTS) in ${delayMs}ms")

        pendingRestart = scheduler.schedule({
            if (!disposed) {
                val exe = executablePath
                if (exe != null) {
                    synchronized(this) { spawnProcess(exe) }
                } else {
                    synchronized(this) { start() }
                }
            }
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun cancelPendingRestart() {
        pendingRestart?.cancel(false)
        pendingRestart = null
    }

    // ── Stderr logging ────────────────────────────────────────────────────

    private fun appendToLogFile(text: String) {
        try {
            FileWriter(logFile, /* append = */ true).use { it.write(text) }
        } catch (e: Exception) {
            LOG.debug("Failed to write to rubyn log file: ${e.message}")
        }
    }

    private fun detectAuthError(line: String): Boolean {
        val lower = line.lowercase()
        return lower.contains("auth") && (lower.contains("error") || lower.contains("invalid") ||
            lower.contains("missing") || lower.contains("unauthorized") || lower.contains("forbidden"))
    }

    // ── Process listener ──────────────────────────────────────────────────

    private inner class ProcessLifecycleListener : ProcessAdapter() {

        override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
            val text = event.text ?: return

            if (ProcessOutputType.isStderr(outputType)) {
                appendToLogFile(text)
                LOG.debug("[rubyn-code stderr] ${text.trimEnd()}")

                if (!authErrorDetected && detectAuthError(text)) {
                    authErrorDetected = true
                    LOG.warn("Auth error detected in rubyn-code stderr: ${text.trimEnd()}")
                    ApplicationManager.getApplication().invokeLater {
                        RubynNotifier.notAuthenticated(project)
                    }
                }
            }
        }

        override fun processTerminated(event: ProcessEvent) {
            val exitCode = event.exitCode
            LOG.info("rubyn-code exited with code $exitCode for project '${project.name}'")

            _isRunning.value = false

            if (disposed) return

            if (exitCode != 0) {
                LOG.warn("rubyn-code exited unexpectedly (code=$exitCode)")
                ApplicationManager.getApplication().invokeLater {
                    RubynNotifier.processCrashed(project)
                }
                scheduleRestart()
            } else {
                // Clean exit — reset counter so next explicit start gets full retries
                restartAttempts.set(0)
            }
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────

    private fun processHandlerPid(handler: OSProcessHandler): Long? =
        runCatching { handler.process.pid() }.getOrNull()

    private fun sanitizeProjectName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(64)
}
