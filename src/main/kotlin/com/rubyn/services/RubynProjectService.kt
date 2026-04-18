package com.rubyn.services

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.rubyn.bridge.AgentStatusParams
import com.rubyn.bridge.EditorContextParams
import com.rubyn.bridge.FileEditApprovalParams
import com.rubyn.bridge.FileEditParams
import com.rubyn.bridge.InitializeParams
import com.rubyn.bridge.JsonRpcCodec
import com.rubyn.bridge.NotificationMethod
import com.rubyn.bridge.PromptCancelParams
import com.rubyn.bridge.PromptSendParams
import com.rubyn.bridge.ReviewRequestParams
import com.rubyn.bridge.RpcMethod
import com.rubyn.bridge.RpcNotification
import com.rubyn.bridge.RubynBridge
import com.rubyn.bridge.SessionCostParams
import com.rubyn.bridge.SessionStartParams
import com.rubyn.bridge.ToolApprovalParams
import com.rubyn.bridge.ToolUseParams
import com.rubyn.notifications.RubynNotifier
import com.rubyn.settings.RubynSettingsService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import java.util.UUID

private val LOG = logger<RubynProjectService>()

/** Plugin version sent in the initialize handshake. */
private const val CLIENT_VERSION = "0.1.0"

/** Reconnect back-off delays in milliseconds (1 s → 3 s → 10 s). */
private val RECONNECT_DELAYS_MS = longArrayOf(1_000L, 3_000L, 10_000L)

/** Maximum automatic reconnect attempts after an unexpected bridge failure. */
private const val MAX_RECONNECT_ATTEMPTS = 3

/** PropertiesComponent key under which the active session ID is persisted. */
private const val PROP_SESSION_ID = "rubyn.session_id"

/**
 * Per-project coordinator that owns the bridge, session state, and prompt
 * lifecycle for the Rubyn plugin.
 *
 * Registered as a project-level service in plugin.xml — one instance per open
 * project. Access via:
 *   project.getService(RubynProjectService::class.java)
 *
 * ## Responsibilities
 * - Owns the [RubynBridge] instance and its lifecycle.
 * - Surfaces reactive state via [StateFlow]s consumed by the tool window,
 *   status bar widget, and editor actions.
 * - Translates bridge [RpcNotification]s into StateFlow updates.
 * - Submits prompts, reviews, and approval decisions to the bridge.
 * - Reconnects automatically after bridge failures (exponential back-off,
 *   up to [MAX_RECONNECT_ATTEMPTS] attempts: 1 s → 3 s → 10 s).
 * - Persists the active session ID via [PropertiesComponent] so it survives
 *   IDE restarts.
 *
 * ## Threading model
 * - All StateFlow mutations run on [Dispatchers.Main] to make them safe for
 *   UI consumers that observe without additional threading concerns.
 * - All bridge I/O (initialize handshake, prompt submissions, approvals) runs
 *   on [Dispatchers.IO] via the [scope] supervisor.
 * - [ensureRunning] and all public mutation methods are safe to call from any thread.
 *
 * ## Disposal
 * [dispose] cancels the coroutine scope and tears down the bridge. Called
 * automatically by the platform when the project closes.
 */
@Service(Service.Level.PROJECT)
class RubynProjectService(private val project: Project) : Disposable {

    // ── Coroutine scope ───────────────────────────────────────────────────

    /**
     * Supervisor scope — a failed child coroutine does not cancel siblings.
     * Cancelled in [dispose].
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── StateFlows ────────────────────────────────────────────────────────

    private val _sessionId = MutableStateFlow<String?>(null)

    /**
     * The active session ID, or null if no session has been started.
     * Persisted across IDE restarts via [PropertiesComponent].
     */
    val sessionId: StateFlow<String?> = _sessionId.asStateFlow()

    private val _agentStatus = MutableStateFlow(AgentStatus.IDLE)

    /**
     * Current agent status, driven by [NotificationMethod.AGENT_STATUS]
     * notifications from rubyn-code.
     */
    val agentStatus: StateFlow<AgentStatus> = _agentStatus.asStateFlow()

    private val _sessionCost = MutableStateFlow(SessionCost.ZERO)

    /**
     * Cumulative token and cost counters for the current session.
     * Updated on each [NotificationMethod.SESSION_COST] notification.
     */
    val sessionCost: StateFlow<SessionCost> = _sessionCost.asStateFlow()

    private val _pendingApprovals = MutableStateFlow<List<PendingToolApproval>>(emptyList())

    /**
     * Tool calls awaiting explicit user approval.
     * Populated on [NotificationMethod.TOOL_USE], cleared when approved/denied.
     */
    val pendingApprovals: StateFlow<List<PendingToolApproval>> = _pendingApprovals.asStateFlow()

    private val _pendingEdits = MutableStateFlow<List<PendingFileEdit>>(emptyList())

    /**
     * File edits proposed by the agent, awaiting user acceptance/rejection.
     * Populated on [NotificationMethod.FILE_EDIT], cleared when accepted/denied.
     */
    val pendingEdits: StateFlow<List<PendingFileEdit>> = _pendingEdits.asStateFlow()

    // ── Bridge state ──────────────────────────────────────────────────────

    @Volatile private var bridge: RubynBridge? = null
    @Volatile private var reconnectAttempts = 0
    @Volatile private var reconnectJob: Job? = null
    @Volatile private var disposed = false

    // ── Startup ───────────────────────────────────────────────────────────

    init {
        // Restore persisted session ID if present.
        val saved = PropertiesComponent.getInstance(project).getValue(PROP_SESSION_ID)
        if (!saved.isNullOrBlank()) {
            _sessionId.value = saved
            LOG.info("RubynProjectService: restored session ID $saved for project '${project.name}'")
        }
    }

    /**
     * Called by [com.rubyn.startup.RubynStartupActivity] after the project is
     * open and confirmed to be a Ruby project.
     *
     * Delegates to [ensureRunning] — idempotent and safe to call multiple times.
     */
    fun onProjectOpened() {
        LOG.info("RubynProjectService: project opened — ${project.name}")
        ensureRunning()
    }

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Ensures the rubyn-code process is running and the bridge is connected.
     *
     * Idempotent — safe to call when already connected. Spawns I/O work on
     * [Dispatchers.IO] via the coroutine scope; returns immediately.
     *
     * Thread-safe — may be called from any thread.
     */
    fun ensureRunning() {
        if (disposed) return
        if (bridge != null) return

        scope.launch {
            connectBridge()
        }
    }

    /**
     * Submits a user prompt to rubyn-code for the current session.
     *
     * If the bridge is not yet connected the call is dropped and a warning is
     * logged — the UI should reflect the disconnected state.
     *
     * @param text    The prompt text entered by the user.
     * @param context Optional editor context attached to the prompt.
     */
    fun submitPrompt(text: String, context: EditorContextParams? = null) {
        val currentBridge = bridge ?: run {
            LOG.warn("submitPrompt: bridge not connected")
            return
        }
        val sid = ensureSessionId()

        val params = PromptSendParams(
            sessionId = sid,
            messageId = UUID.randomUUID().toString(),
            text = text,
            context = context,
        )

        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { currentBridge.sendPrompt(params).get() }
            }.onFailure { LOG.warn("submitPrompt failed: ${it.message}") }
        }
    }

    /**
     * Cancels the active prompt on the current session. Fire-and-forget.
     */
    fun cancelActivePrompt() {
        val currentBridge = bridge ?: return
        val sid = _sessionId.value ?: return

        val params = PromptCancelParams(
            sessionId = sid,
            messageId = UUID.randomUUID().toString(),
        )

        scope.launch {
            runCatching { currentBridge.cancelPrompt(params) }
                .onFailure { LOG.warn("cancelActivePrompt failed: ${it.message}") }
        }
    }

    /**
     * Submits a code-review request for the given file.
     *
     * @param filePath Absolute path to the file being reviewed.
     * @param content  Full file content to review.
     * @param focus    Optional focus instruction (e.g. "security", "performance").
     */
    fun submitReview(filePath: String, content: String, focus: String? = null) {
        val currentBridge = bridge ?: run {
            LOG.warn("submitReview: bridge not connected")
            return
        }
        val sid = ensureSessionId()

        val params = ReviewRequestParams(
            sessionId = sid,
            filePath = filePath,
            content = content,
            focus = focus,
        )

        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    currentBridge.request(
                        method = RpcMethod.REVIEW_REQUEST,
                        params = params,
                        encode = { id, method, p -> JsonRpcCodec.encodeRequest(id, method, p) },
                    ).get()
                }
            }.onFailure { LOG.warn("submitReview failed: ${it.message}") }
        }
    }

    /**
     * Approves a pending tool call identified by [toolCallId].
     *
     * Removes the entry from [pendingApprovals] and sends the approval downstream.
     */
    fun approveToolUse(toolCallId: String) {
        val currentBridge = bridge ?: return
        val sid = _sessionId.value ?: return

        removePendingApproval(toolCallId)

        scope.launch {
            runCatching {
                currentBridge.approveToolCall(ToolApprovalParams(toolCallId, sid))
            }.onFailure { LOG.warn("approveToolUse failed: ${it.message}") }
        }
    }

    /**
     * Denies a pending tool call identified by [toolCallId].
     *
     * Removes the entry from [pendingApprovals] and sends the denial downstream.
     */
    fun denyToolUse(toolCallId: String) {
        val currentBridge = bridge ?: return
        val sid = _sessionId.value ?: return

        removePendingApproval(toolCallId)

        scope.launch {
            runCatching {
                currentBridge.denyToolCall(ToolApprovalParams(toolCallId, sid))
            }.onFailure { LOG.warn("denyToolUse failed: ${it.message}") }
        }
    }

    /**
     * Accepts a proposed file edit identified by [editId].
     *
     * Removes the entry from [pendingEdits] and sends the approval downstream.
     */
    fun acceptEdit(editId: String) {
        val currentBridge = bridge ?: return
        val sid = _sessionId.value ?: return

        removePendingEdit(editId)

        scope.launch {
            runCatching {
                currentBridge.approveFileEdit(FileEditApprovalParams(editId, sid))
            }.onFailure { LOG.warn("acceptEdit failed: ${it.message}") }
        }
    }

    /**
     * Rejects a proposed file edit identified by [editId].
     *
     * Removes the entry from [pendingEdits] and sends the denial downstream.
     */
    fun rejectEdit(editId: String) {
        val currentBridge = bridge ?: return
        val sid = _sessionId.value ?: return

        removePendingEdit(editId)

        scope.launch {
            runCatching {
                currentBridge.denyFileEdit(FileEditApprovalParams(editId, sid))
            }.onFailure { LOG.warn("rejectEdit failed: ${it.message}") }
        }
    }

    /**
     * Selects a model for future prompts by persisting it in settings.
     *
     * Does not restart the process — the model takes effect on the next prompt.
     */
    fun selectModel(model: String) {
        val settings = RubynSettingsService.getInstance().settings()
        RubynSettingsService.getInstance().applySettings(settings.copy(model = model))
        LOG.info("RubynProjectService: model changed to $model")
    }

    // ── Bridge connection ─────────────────────────────────────────────────

    /**
     * Connects the bridge: starts the process, wires I/O streams, sends the
     * initialize handshake, starts the session, and subscribes to notifications.
     *
     * Runs on [Dispatchers.IO]. On any failure, schedules a reconnect attempt.
     */
    private suspend fun connectBridge() {
        if (disposed) return

        val processService = project.getService(RubynProcessService::class.java) ?: run {
            LOG.error("RubynProjectService: RubynProcessService not available")
            return
        }

        // Start the process (idempotent).
        withContext(Dispatchers.IO) {
            processService.start()
        }

        // Brief pause to let the process boot before grabbing streams.
        delay(200)

        val streams = processService.getProcessStreams() ?: run {
            LOG.warn("RubynProjectService: process streams unavailable after start")
            scheduleReconnect()
            return
        }

        val newBridge = RubynBridge(streams.stdin, streams.stdout)
        bridge = newBridge

        // Initialize handshake — must succeed before any other requests.
        val initResult = runCatching {
            withContext(Dispatchers.IO) {
                newBridge.initialize(
                    InitializeParams(
                        clientVersion = CLIENT_VERSION,
                        projectDir = project.basePath ?: "",
                    )
                ).get()
            }
        }

        if (initResult.isFailure) {
            LOG.warn("RubynProjectService: initialize failed: ${initResult.exceptionOrNull()?.message}")
            newBridge.dispose()
            bridge = null
            scheduleReconnect()
            return
        }

        LOG.info("RubynProjectService: bridge connected for project '${project.name}'")
        reconnectAttempts = 0

        // Start or resume the session.
        launchSession(newBridge)

        // Subscribe to inbound notifications — runs until bridge or scope is cancelled.
        scope.launch {
            newBridge.notifications.collect { notification ->
                handleNotification(notification)
            }
        }

        // Signal idle now that we're up.
        updateOnMain { _agentStatus.value = AgentStatus.IDLE }
    }

    /**
     * Sends session/start and persists the session ID.
     *
     * Reuses a saved session ID when available so the agent can resume history.
     */
    private suspend fun launchSession(activeBridge: RubynBridge) {
        val sid = ensureSessionId()
        val settings = RubynSettingsService.getInstance().settings()

        runCatching {
            withContext(Dispatchers.IO) {
                activeBridge.startSession(
                    SessionStartParams(
                        sessionId = sid,
                        model = settings.model.ifBlank { null },
                    )
                ).get()
            }
        }.onFailure {
            LOG.warn("RubynProjectService: session/start failed: ${it.message}")
        }

        LOG.info("RubynProjectService: session active — $sid")
    }

    // ── Reconnect logic ───────────────────────────────────────────────────

    /**
     * Schedules a reconnect attempt after the appropriate back-off delay.
     *
     * Back-off schedule (0-indexed attempt number):
     *   0 → 1 s
     *   1 → 3 s
     *   2 → 10 s
     *
     * After [MAX_RECONNECT_ATTEMPTS] consecutive failures the service gives up,
     * sets [AgentStatus.ERROR], and posts a user-visible notification.
     */
    private fun scheduleReconnect() {
        if (disposed) return

        val attempt = reconnectAttempts
        if (attempt >= MAX_RECONNECT_ATTEMPTS) {
            LOG.warn("RubynProjectService: max reconnect attempts ($MAX_RECONNECT_ATTEMPTS) reached — giving up")
            updateOnMain { _agentStatus.value = AgentStatus.ERROR }
            ApplicationManager.getApplication().invokeLater {
                RubynNotifier.bridgeDisconnected(project)
            }
            return
        }

        reconnectAttempts++
        val delayMs = RECONNECT_DELAYS_MS.getOrElse(attempt) { RECONNECT_DELAYS_MS.last() }
        LOG.info(
            "RubynProjectService: reconnect attempt ${attempt + 1}/$MAX_RECONNECT_ATTEMPTS " +
                "in ${delayMs}ms for project '${project.name}'"
        )

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delayMs)
            if (!disposed && bridge == null) {
                connectBridge()
            }
        }
    }

    // ── Notification dispatch ─────────────────────────────────────────────

    /**
     * Routes an inbound [RpcNotification] to the appropriate StateFlow update.
     *
     * All StateFlow mutations are dispatched to [Dispatchers.Main].
     */
    private suspend fun handleNotification(notification: RpcNotification) {
        when (notification.method) {
            NotificationMethod.AGENT_STATUS -> {
                val params = decodeParams<AgentStatusParams>(notification) ?: return
                val status = AgentStatus.fromString(params.status)
                updateOnMain { _agentStatus.value = status }
            }

            NotificationMethod.SESSION_COST -> {
                val params = decodeParams<SessionCostParams>(notification) ?: return
                updateOnMain {
                    _sessionCost.value = SessionCost(
                        inputTokens = params.inputTokens,
                        outputTokens = params.outputTokens,
                        costUsd = params.costUsd,
                    )
                }
            }

            NotificationMethod.TOOL_USE -> {
                val params = decodeParams<ToolUseParams>(notification) ?: return
                val approval = PendingToolApproval(
                    toolCallId = params.toolCallId,
                    toolName = params.name,
                    args = params.args.toString(),
                )
                updateOnMain {
                    _pendingApprovals.value = _pendingApprovals.value + approval
                }
            }

            NotificationMethod.FILE_EDIT -> {
                val params = decodeParams<FileEditParams>(notification) ?: return
                val edit = PendingFileEdit(
                    editId = params.editId,
                    filePath = params.diff.path,
                    before = params.diff.before,
                    after = params.diff.after,
                )
                updateOnMain {
                    _pendingEdits.value = _pendingEdits.value + edit
                }
            }

            NotificationMethod.STREAM_TEXT,
            NotificationMethod.STREAM_DONE -> {
                // Streaming deltas are consumed directly by the tool window.
                // No StateFlow update needed here.
            }

            NotificationMethod.AGENT_ERROR -> {
                LOG.warn("RubynProjectService: agent error — ${notification.params}")
                updateOnMain { _agentStatus.value = AgentStatus.ERROR }
            }

            else -> {
                LOG.debug("RubynProjectService: unhandled notification '${notification.method}'")
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /**
     * Returns the current session ID, generating and persisting a new UUID when
     * none exists yet.
     */
    private fun ensureSessionId(): String {
        val existing = _sessionId.value
        if (!existing.isNullOrBlank()) return existing

        val newId = UUID.randomUUID().toString()
        _sessionId.value = newId
        PropertiesComponent.getInstance(project).setValue(PROP_SESSION_ID, newId)
        LOG.info("RubynProjectService: new session ID generated — $newId")
        return newId
    }

    private fun removePendingApproval(toolCallId: String) {
        updateOnMain {
            _pendingApprovals.value = _pendingApprovals.value.filter { it.toolCallId != toolCallId }
        }
    }

    private fun removePendingEdit(editId: String) {
        updateOnMain {
            _pendingEdits.value = _pendingEdits.value.filter { it.editId != editId }
        }
    }

    /**
     * Dispatches [block] on [Dispatchers.Main] inside the service scope.
     *
     * All StateFlow mutations go through here so UI collectors never need
     * to switch contexts themselves.
     */
    private fun updateOnMain(block: () -> Unit) {
        scope.launch(Dispatchers.Main) { block() }
    }

    /**
     * Decodes [notification] params into [T]. Returns null and logs a warning on
     * failure — the service never crashes on a malformed notification.
     */
    private inline fun <reified T> decodeParams(notification: RpcNotification): T? {
        val element = notification.params ?: run {
            LOG.debug("RubynProjectService: notification '${notification.method}' has no params")
            return null
        }
        return runCatching { Json.decodeFromJsonElement<T>(element) }
            .onFailure {
                LOG.warn(
                    "RubynProjectService: failed to decode '${notification.method}' params: ${it.message}"
                )
            }
            .getOrNull()
    }

    // ── Disposal ──────────────────────────────────────────────────────────

    override fun dispose() {
        if (disposed) return
        disposed = true

        LOG.info("RubynProjectService disposing for project '${project.name}'")

        reconnectJob?.cancel()
        reconnectJob = null

        val currentBridge = bridge
        bridge = null
        currentBridge?.dispose()

        scope.cancel()
    }
}

// ── Domain types ──────────────────────────────────────────────────────────────

/**
 * Agent activity state, derived from [NotificationMethod.AGENT_STATUS] notifications.
 */
enum class AgentStatus {
    IDLE, THINKING, STREAMING, WAITING_APPROVAL, ERROR;

    companion object {
        private val LOG = logger<AgentStatus>()

        fun fromString(value: String): AgentStatus = when (value) {
            "idle"             -> IDLE
            "thinking"         -> THINKING
            "streaming"        -> STREAMING
            "waiting_approval" -> WAITING_APPROVAL
            else               -> {
                LOG.warn("AgentStatus: unknown value '$value' — defaulting to IDLE")
                IDLE
            }
        }
    }
}

/**
 * Cumulative token usage and cost for the active session.
 */
data class SessionCost(
    val inputTokens: Int,
    val outputTokens: Int,
    val costUsd: Double,
) {
    companion object {
        val ZERO = SessionCost(inputTokens = 0, outputTokens = 0, costUsd = 0.0)
    }
}

/**
 * A tool call from the agent that requires explicit user approval before execution.
 *
 * @property toolCallId Unique ID used to correlate the approval/denial back to the agent.
 * @property toolName   Human-readable tool name (e.g. "bash", "file_write").
 * @property args       JSON-encoded argument string, for display in the approval UI.
 */
data class PendingToolApproval(
    val toolCallId: String,
    val toolName: String,
    val args: String,
)

/**
 * A file edit proposed by the agent, awaiting user acceptance.
 *
 * @property editId    Unique ID used to correlate acceptance/rejection back to the agent.
 * @property filePath  Absolute path to the file being modified.
 * @property before    File content before the edit.
 * @property after     File content after the edit.
 */
data class PendingFileEdit(
    val editId: String,
    val filePath: String,
    val before: String,
    val after: String,
)
