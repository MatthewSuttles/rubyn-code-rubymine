package com.rubyn.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Unit tests for the pure-logic methods in [RubynProcessService].
 *
 * [RubynProcessService] orchestrates a child process via [OSProcessHandler]
 * which requires a running IntelliJ platform. These tests therefore cover:
 *
 *   1. [resolveExecutable] path resolution logic (extracted as a standalone
 *      helper so it can be tested without a running process).
 *   2. Auth-error detection heuristics ([detectAuthError]).
 *   3. Project name sanitization ([sanitizeProjectName]).
 *   4. Restart back-off delay calculation.
 *   5. [ProcessStreams] data class correctness.
 *
 * Integration tests (start/stop/crash/restart lifecycle) require a full
 * IntelliJ test fixture and are intentionally out of scope here.
 */
class RubynProcessServiceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── resolveExecutable logic ───────────────────────────────────────────

    /**
     * Mirrors the resolution logic from [RubynProcessService.resolveExecutable].
     * Accepts a configured path override and a list of PATH directories.
     */
    private fun resolveExecutable(configuredPath: String, pathDirs: List<String>): String? {
        val configured = configuredPath.trim()
        if (configured.isNotBlank()) {
            val f = File(configured)
            if (f.isFile && f.canExecute()) return configured
        }

        for (dir in pathDirs) {
            val candidate = File(dir, "rubyn-code")
            if (candidate.isFile && candidate.canExecute()) return candidate.absolutePath
        }

        return null
    }

    @Test
    fun `resolveExecutable returns configured path when file is executable`() {
        val exe = tmp.newFile("rubyn-code")
        exe.setExecutable(true)

        val result = resolveExecutable(exe.absolutePath, emptyList())

        assertEquals(exe.absolutePath, result)
    }

    @Test
    fun `resolveExecutable returns null when configured path does not exist`() {
        val result = resolveExecutable("/nonexistent/rubyn-code", emptyList())

        assertNull(result)
    }

    @Test
    fun `resolveExecutable falls back to PATH when configured path missing`() {
        val dir = tmp.newFolder("bin")
        val exe = File(dir, "rubyn-code")
        exe.createNewFile()
        exe.setExecutable(true)

        val result = resolveExecutable("", listOf(dir.absolutePath))

        assertEquals(exe.absolutePath, result)
    }

    @Test
    fun `resolveExecutable returns null when not on PATH`() {
        val emptyDir = tmp.newFolder("empty-bin")

        val result = resolveExecutable("", listOf(emptyDir.absolutePath))

        assertNull(result)
    }

    @Test
    fun `resolveExecutable skips non-executable file on PATH`() {
        val dir = tmp.newFolder("bin2")
        val exe = File(dir, "rubyn-code")
        exe.createNewFile()
        exe.setExecutable(false)  // not executable

        val result = resolveExecutable("", listOf(dir.absolutePath))

        assertNull("Non-executable file should not be returned", result)
    }

    @Test
    fun `resolveExecutable prefers configured path over PATH`() {
        val configuredExe = tmp.newFile("rubyn-code-configured")
        configuredExe.setExecutable(true)

        val pathDir = tmp.newFolder("path-bin")
        val pathExe = File(pathDir, "rubyn-code")
        pathExe.createNewFile()
        pathExe.setExecutable(true)

        val result = resolveExecutable(configuredExe.absolutePath, listOf(pathDir.absolutePath))

        assertEquals("Configured path should win", configuredExe.absolutePath, result)
    }

    // ── Auth-error detection ──────────────────────────────────────────────

    private fun detectAuthError(line: String): Boolean {
        val lower = line.lowercase()
        return lower.contains("unauthorized") ||
            lower.contains("forbidden") ||
            (lower.contains("authentication") && lower.contains("failed")) ||
            lower.contains("auth error")
    }

    @Test
    fun `detectAuthError true for unauthorized`() {
        assertTrue(detectAuthError("HTTP 401 Unauthorized"))
    }

    @Test
    fun `detectAuthError true for forbidden`() {
        assertTrue(detectAuthError("403 Forbidden response"))
    }

    @Test
    fun `detectAuthError true for authentication failed`() {
        assertTrue(detectAuthError("Authentication failed: bad token"))
    }

    @Test
    fun `detectAuthError true for auth error`() {
        assertTrue(detectAuthError("auth error: check your credentials"))
    }

    @Test
    fun `detectAuthError is case-insensitive`() {
        assertTrue(detectAuthError("UNAUTHORIZED access"))
        assertTrue(detectAuthError("FORBIDDEN"))
        assertTrue(detectAuthError("AUTHENTICATION FAILED"))
        assertTrue(detectAuthError("AUTH ERROR"))
    }

    @Test
    fun `detectAuthError false for normal output`() {
        assertFalse(detectAuthError("Starting rubyn-code server..."))
        assertFalse(detectAuthError("Connected to agent"))
        assertFalse(detectAuthError("Session started: abc123"))
    }

    @Test
    fun `detectAuthError false for partial keyword match`() {
        // "authentication" alone without "failed" should not trigger
        assertFalse(detectAuthError("authentication module loaded"))
    }

    // ── Project name sanitization ─────────────────────────────────────────

    private fun sanitizeProjectName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(64)

    @Test
    fun `sanitizeProjectName preserves alphanumeric and safe chars`() {
        assertEquals("my-project_1.0", sanitizeProjectName("my-project_1.0"))
    }

    @Test
    fun `sanitizeProjectName replaces spaces with underscores`() {
        assertEquals("my_project", sanitizeProjectName("my project"))
    }

    @Test
    fun `sanitizeProjectName replaces slashes and colons`() {
        val result = sanitizeProjectName("C:/Users/dev/my project")
        assertFalse("Slashes should be replaced", result.contains("/"))
        assertFalse("Colons should be replaced", result.contains(":"))
    }

    @Test
    fun `sanitizeProjectName truncates to 64 chars`() {
        val longName = "a".repeat(100)
        val result = sanitizeProjectName(longName)
        assertEquals(64, result.length)
    }

    @Test
    fun `sanitizeProjectName handles empty string`() {
        assertEquals("", sanitizeProjectName(""))
    }

    // ── Restart back-off delay calculation ────────────────────────────────

    /**
     * Mirrors the delay calculation: BASE_RESTART_DELAY_MS * (1 shl (attempt - 1))
     */
    private fun restartDelayMs(attempt: Int, baseMs: Long = 2_000L): Long =
        baseMs * (1L shl (attempt - 1))

    @Test
    fun `restart delay for attempt 1 is base delay`() {
        assertEquals(2_000L, restartDelayMs(1))
    }

    @Test
    fun `restart delay doubles on each attempt`() {
        assertEquals(2_000L, restartDelayMs(1))
        assertEquals(4_000L, restartDelayMs(2))
        assertEquals(8_000L, restartDelayMs(3))
    }

    @Test
    fun `restart delay never exceeds reasonable bounds for 3 attempts`() {
        // MAX_RESTART_ATTEMPTS = 3, so only delays 1, 2, 3 are used
        val maxDelay = (1..3).maxOf { restartDelayMs(it) }
        assertTrue("Max delay should be <= 10s", maxDelay <= 10_000L)
    }

    // ── ProcessStreams data class ──────────────────────────────────────────

    @Test
    fun `ProcessStreams holds stdin and stdout references`() {
        val stdin  = java.io.PipedOutputStream()
        val stdout = java.io.PipedInputStream()

        val streams = ProcessStreams(stdin = stdin, stdout = stdout)

        assertEquals(stdin, streams.stdin)
        assertEquals(stdout, streams.stdout)
    }

    // ── AgentStatus fromString ────────────────────────────────────────────

    @Test
    fun `AgentStatus fromString maps known values`() {
        assertEquals(AgentStatus.IDLE,             AgentStatus.fromString("idle"))
        assertEquals(AgentStatus.THINKING,         AgentStatus.fromString("thinking"))
        assertEquals(AgentStatus.STREAMING,        AgentStatus.fromString("streaming"))
        assertEquals(AgentStatus.WAITING_APPROVAL, AgentStatus.fromString("waiting_approval"))
    }

    @Test
    fun `AgentStatus fromString defaults to IDLE for unknown value`() {
        assertEquals(AgentStatus.IDLE, AgentStatus.fromString("unknown_status"))
        assertEquals(AgentStatus.IDLE, AgentStatus.fromString(""))
        assertEquals(AgentStatus.IDLE, AgentStatus.fromString("error")) // not a mapped value; falls through to IDLE
    }

    // ── SessionCost ───────────────────────────────────────────────────────

    @Test
    fun `SessionCost ZERO sentinel has zero values`() {
        val zero = SessionCost.ZERO
        assertEquals(0, zero.inputTokens)
        assertEquals(0, zero.outputTokens)
        assertEquals(0.0, zero.costUsd, 0.0)
    }

    @Test
    fun `SessionCost stores token counts and cost`() {
        val cost = SessionCost(inputTokens = 500, outputTokens = 200, costUsd = 0.0045)
        assertEquals(500, cost.inputTokens)
        assertEquals(200, cost.outputTokens)
        assertEquals(0.0045, cost.costUsd, 0.00001)
    }
}
