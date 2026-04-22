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
import com.rubyn.bridge.NotificationMethod
import com.rubyn.bridge.PromptCancelParams
import com.rubyn.bridge.PromptSendParams
import com.rubyn.bridge.ReviewRequestParams
import com.rubyn.bridge.RpcNotification
import com.rubyn.bridge.RpcResponse
import com.rubyn.bridge.RubynBridge
import com.rubyn.bridge.SessionCostParams
import com.rubyn.bridge.SessionResetParams
import com.rubyn.bridge.SessionResumeParams
import com.rubyn.bridge.StreamTextParams
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import java.util.UUID
import java.util.concurrent.CompletableFuture

private val LOG = logger<RubynProjectService>()

/** Plugin version sent in the initialize handshake. */
private const val CLIENT_VERSION = "0.1.0"

/** Reconnect back-off delays in milliseconds (1 s -> 3 s -> 10 s). */
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
 */
@Service(Service.Level.PROJECT)
class RubynProjectService(private val project: Project) : Disposable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── StateFlows ────────────────────────────────────────────────────────

    private val _sessionId = MutableStateFlow<String?>(null)
    val sessionId: StateFlow<String?> = _sessionId.asStateFlow()

    private val _agentStatus = MutableStateFlow(AgentStatus.IDLE)
    val agentStatus: StateFlow<AgentStatus> = _agentStatus.asStateFlow()

    private val _sessionCost = MutableStateFlow(SessionCost.ZERO)
    val sessionCost: StateFlow<SessionCost> = _sessionCost.asStateFlow()

    private val _pendingApprovals = MutableStateFlow<List<PendingToolApproval>>(emptyList())
    val pendingApprovals: StateFlow<List<PendingToolApproval>> = _pendingApprovals.asStateFlow()

    private val _pendingEdits = MutableStateFlow<List<PendingFileEdit>>(emptyList())
    val pendingEdits: StateFlow<List<PendingFileEdit>> = _pendingEdits.asStateFlow()

    private val _streamText = MutableSharedFlow<StreamTextParams>(extraBufferCapacity = 256)

    /**
     * Streaming text deltas from rubyn-code.
     * Subscribe here for incremental rendering (e.g. in RubynChatPanel).
     */
    val streamText: SharedFlow<StreamTextParams> = _streamText.asSharedFlow()

    private val _streamDone = MutableSharedFlow<StreamTextParams>(extraBufferCapacity = 64)

    /**
     * Emitted when a streaming response completes (final=true).
     */
    val streamDone: SharedFlow<StreamTextParams> = _streamDone.asSharedFlow()

    // ── Bridge state ──────────────────────────────────────────────────────

    @Volatile private var bridge: RubynBridge? = null
    @Volatile private var reconnectAttempts = 0
    @Volatile private var reconnectJob: Job? = null
    @Volatile private var disposed = false

    init {
        val saved = PropertiesComponent.getInstance(project).getValue(PROP_SESSION_ID)
        if (!saved.isNullOrBlank()) {
            _sessionId.value = saved
            LOG.info("RubynProjectService: restored session ID $saved for project '${project.name}'")
        }
    }

    fun onProjectOpened() {
        LOG.info("RubynProjectService: project opened -- ${project.name}")
        ensureRunning()
    }

    // ── Public API ────────────────────────────────────────────────────────

    fun ensureRunning() {
        if (disposed) return
        if (bridge != null) return
        scope.launch { connectBridge() }
    }

    fun submitPrompt(text: String, context: EditorContextParams? = null) {
        val currentBridge = bridge ?: run {
            LOG.warn("submitPrompt: bridge not connected")
            return
        }
        val sid = ensureSessionId()
        val params = PromptSendParams(
            text = text,
            sessionId = sid,
            context = context,
        )
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { currentBridge.sendPrompt(params).get() }
            }.onFailure { LOG.warn("submitPrompt failed: ${it.message}") }
        }
    }

    fun cancelActivePrompt() {
        val currentBridge = bridge ?: return
        val sid = _sessionId.value ?: return
        val params = PromptCancelParams(sessionId = sid)
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { currentBridge.cancelPrompt(params).get() }
            }.onFailure { LOG.warn("cancelActivePrompt failed: ${it.message}") }
        }
    }

    fun submitReview(focus: String? = null) {
        val currentBridge = bridge ?: run {
            LOG.warn("submitReview: bridge not connected")
            return
        }
        val sid = ensureSessionId()
        val params = ReviewRequestParams(
            sessionId = sid,
            focus = focus,
        )
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    currentBridge.requestReview(params).get()
                }
            }.onFailure { LOG.warn("submitReview failed: ${it.message}") }
        }
    }

    fun approveToolUse(toolCallId: String) {
        val currentBridge = bridge ?: return
        removePendingApproval(toolCallId)
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    currentBridge.resolveToolApproval(ToolApprovalParams(requestId = toolCallId, approved = true)).get()
                }
            }.onFailure { LOG.warn("approveToolUse failed: ${it.message}") }
        }
    }

    fun denyToolUse(toolCallId: String) {
        val currentBridge = bridge ?: return
        removePendingApproval(toolCallId)
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    currentBridge.resolveToolApproval(ToolApprovalParams(requestId = toolCallId, approved = false)).get()
                }
            }.onFailure { LOG.warn("denyToolUse failed: ${it.message}") }
        }
    }

    fun acceptEdit(editId: String) {
        val currentBridge = bridge ?: return
        removePendingEdit(editId)
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    currentBridge.resolveFileEdit(FileEditApprovalParams(editId = editId, accepted = true)).get()
                }
            }.onFailure { LOG.warn("acceptEdit failed: ${it.message}") }
        }
    }

    fun rejectEdit(editId: String) {
        val currentBridge = bridge ?: return
        removePendingEdit(editId)
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    currentBridge.resolveFileEdit(FileEditApprovalParams(editId = editId, accepted = false)).get()
                }
            }.onFailure { LOG.warn("rejectEdit failed: ${it.message}") }
        }
    }

    fun selectModel(model: String) {
        val settings = RubynSettingsService.getInstance().settings()
        RubynSettingsService.getInstance().applySettings(settings.copy(model = model))
        LOG.info("RubynProjectService: model changed to $model")
    }

    /**
     * Requests the session list from rubyn-code.
     * Call [CompletableFuture.get] on [Dispatchers.IO].
     * The result can be decoded as [com.rubyn.bridge.SessionListResult].
     */
    fun listSessions(): CompletableFuture<RpcResponse> {
        val currentBridge = bridge ?: run {
            val failed = CompletableFuture<RpcResponse>()
            failed.completeExceptionally(IllegalStateException("bridge not connected"))
            return failed
        }
        return currentBridge.listSessions()
    }

    /**
     * Resumes an existing session: switches the active ID and sends session/resume.
     */
    fun resumeSession(sessionId: String) {
        val currentBridge = bridge ?: run {
            LOG.warn("resumeSession: bridge not connected")
            return
        }
        _sessionId.value = sessionId
        PropertiesComponent.getInstance(project).setValue(PROP_SESSION_ID, sessionId)
        LOG.info("RubynProjectService: resuming session $sessionId")

        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    currentBridge.resumeSession(
                        SessionResumeParams(sessionId = sessionId)
                    ).get()
                }
            }.onFailure { LOG.warn("resumeSession failed: ${it.message}") }
        }
    }

    // ── Bridge connection ─────────────────────────────────────────────────

    private suspend fun connectBridge() {
        if (disposed) return

        val processService = project.getService(RubynProcessService::class.java) ?: run {
            LOG.error("RubynProjectService: RubynProcessService not available")
            return
        }

        withContext(Dispatchers.IO) { processService.start() }
        delay(200)

        val streams = processService.getProcessStreams() ?: run {
            LOG.warn("RubynProjectService: process streams unavailable after start")
            scheduleReconnect()
            return
        }

        val newBridge = RubynBridge(streams.stdin, streams.stdout)
        bridge = newBridge

        val initResult = runCatching {
            withContext(Dispatchers.IO) {
                newBridge.initialize(
                    InitializeParams(
                        workspacePath = project.basePath ?: "",
                        extensionVersion = CLIENT_VERSION,
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

        launchSession(newBridge)

        scope.launch {
            newBridge.notifications.collect { notification ->
                handleNotification(notification)
            }
        }

        updateOnMain { _agentStatus.value = AgentStatus.IDLE }
    }

    private suspend fun launchSession(activeBridge: RubynBridge) {
        val sid = ensureSessionId()

        runCatching {
            withContext(Dispatchers.IO) {
                activeBridge.resetSession(SessionResetParams(sessionId = sid)).get()
            }
        }.onFailure {
            LOG.warn("RubynProjectService: session/reset failed: ${it.message}")
        }

        LOG.info("RubynProjectService: session active -- $sid")
    }

    // ── Reconnect ─────────────────────────────────────────────────────────

    private fun scheduleReconnect() {
        if (disposed) return

        val attempt = reconnectAttempts
        if (attempt >= MAX_RECONNECT_ATTEMPTS) {
            LOG.warn("RubynProjectService: max reconnect attempts ($MAX_RECONNECT_ATTEMPTS) reached -- giving up")
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
            if (!disposed && bridge == null) connectBridge()
        }
    }

    // ── Notification dispatch ─────────────────────────────────────────────

    private suspend fun handleNotification(notification: RpcNotification) {
        when (notification.method) {
            NotificationMethod.AGENT_STATUS -> {
                val params = decodeParams<AgentStatusParams>(notification) ?: return
                updateOnMain { _agentStatus.value = AgentStatus.fromString(params.status) }
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
                if (params.requiresApproval) {
                    val approval = PendingToolApproval(
                        toolCallId = params.requestId,
                        toolName = params.tool,
                        args = params.args.toString(),
                    )
                    updateOnMain { _pendingApprovals.value = _pendingApprovals.value + approval }
                }
            }

            NotificationMethod.FILE_EDIT, NotificationMethod.FILE_CREATE -> {
                val params = decodeParams<FileEditParams>(notification) ?: return
                val isCreate = notification.method == NotificationMethod.FILE_CREATE
                val edit = PendingFileEdit(
                    editId = params.editId,
                    filePath = params.path,
                    before = if (isCreate) "" else params.content,
                    after = params.content,
                )
                updateOnMain { _pendingEdits.value = _pendingEdits.value + edit }

                val proposedEdit = if (isCreate) {
                    com.rubyn.diff.ProposedEdit.Create(
                        editId = params.editId,
                        filePath = params.path,
                        after = params.content,
                    )
                } else {
                    com.rubyn.diff.ProposedEdit.Modify(
                        editId = params.editId,
                        filePath = params.path,
                        before = params.content,
                        after = params.content,
                    )
                }
                project.getService(com.rubyn.diff.RubynDiffManager::class.java)
                    ?.presentEdit(proposedEdit)
            }

            NotificationMethod.STREAM_TEXT -> {
                val params = decodeParams<StreamTextParams>(notification) ?: return
                _streamText.tryEmit(params)
                if (params.final) {
                    _streamDone.tryEmit(params)
                }
            }

            NotificationMethod.AGENT_ERROR -> {
                LOG.warn("RubynProjectService: agent error -- ${notification.params}")
                updateOnMain { _agentStatus.value = AgentStatus.ERROR }
            }

            else -> {
                LOG.debug("RubynProjectService: unhandled notification '${notification.method}'")
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun ensureSessionId(): String {
        val existing = _sessionId.value
        if (!existing.isNullOrBlank()) return existing

        val newId = UUID.randomUUID().toString()
        _sessionId.value = newId
        PropertiesComponent.getInstance(project).setValue(PROP_SESSION_ID, newId)
        LOG.info("RubynProjectService: new session ID generated -- $newId")
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

    private fun updateOnMain(block: () -> Unit) {
        scope.launch(Dispatchers.Main) { block() }
    }

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

enum class AgentStatus {
    IDLE, THINKING, STREAMING, REVIEWING, WAITING_APPROVAL, ERROR;

    companion object {
        private val LOG = logger<AgentStatus>()

        fun fromString(value: String): AgentStatus = when (value) {
            "idle"             -> IDLE
            "thinking"         -> THINKING
            "streaming"        -> STREAMING
            "reviewing"        -> REVIEWING
            "waiting_approval" -> WAITING_APPROVAL
            "done"             -> IDLE
            "cancelled"        -> IDLE
            "error"            -> ERROR
            else               -> {
                LOG.warn("AgentStatus: unknown value '$value' -- defaulting to IDLE")
                IDLE
            }
        }
    }
}

data class SessionCost(
    val inputTokens: Int,
    val outputTokens: Int,
    val costUsd: Double,
) {
    companion object {
        val ZERO = SessionCost(inputTokens = 0, outputTokens = 0, costUsd = 0.0)
    }
}

data class PendingToolApproval(
    val toolCallId: String,
    val toolName: String,
    val args: String,
)

data class PendingFileEdit(
    val editId: String,
    val filePath: String,
    val before: String,
    val after: String,
)
