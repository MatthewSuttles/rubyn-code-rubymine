package com.rubyn.bridge

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.logger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private val LOG = logger<RubynBridge>()

/** How long (ms) to wait for a response before completing the future exceptionally. */
private const val REQUEST_TIMEOUT_MS = 30_000L

/**
 * JSON-RPC 2.0 bridge over the rubyn-code stdio channel.
 *
 * ## Threading model
 * A single dedicated reader thread consumes stdout line-by-line and dispatches:
 *   - Responses → pending futures in [pendingRequests]
 *   - Notifications → [_notifications] SharedFlow
 *
 * Write calls acquire [writeLock] so multiple callers (UI thread, pooled threads)
 * can safely interleave requests.
 *
 * ## Disposal
 * Call [dispose] to stop the reader thread, cancel all pending futures, and
 * close the writer. After disposal any [request] call completes exceptionally.
 *
 * ## Malformed input
 * Lines that cannot be decoded are logged at DEBUG and skipped. The bridge
 * never crashes on bad input.
 *
 * @param stdin  The process's stdin stream (plugin writes requests here).
 * @param stdout The process's stdout stream (plugin reads responses/notifications here).
 */
class RubynBridge(
    stdin: OutputStream,
    stdout: InputStream,
) : Disposable {

    // ── I/O ───────────────────────────────────────────────────────────────

    private val writer = BufferedWriter(OutputStreamWriter(stdin, Charsets.UTF_8))
    private val reader = BufferedReader(InputStreamReader(stdout, Charsets.UTF_8))
    private val writeLock = Any()

    // ── Pending requests ──────────────────────────────────────────────────

    /**
     * Map from request ID → future that will be completed when the corresponding
     * response arrives (or times out, or the bridge is disposed).
     */
    private val pendingRequests = ConcurrentHashMap<Long, CompletableFuture<RpcResponse>>()

    // ── Notification flow ─────────────────────────────────────────────────

    private val _notifications = MutableSharedFlow<RpcNotification>(
        extraBufferCapacity = 64,
    )

    /**
     * All inbound [RpcNotification] messages from rubyn-code.
     *
     * Collectors (e.g. the project service, tool window) subscribe here.
     * The flow never closes — collectors see new items as long as the bridge
     * is alive. After [dispose] the flow simply stops emitting.
     */
    val notifications: SharedFlow<RpcNotification> = _notifications.asSharedFlow()

    // ── Reader thread ─────────────────────────────────────────────────────

    @Volatile
    private var disposed = false

    private val readerExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "rubyn-bridge-reader").also { it.isDaemon = true }
    }

    init {
        readerExecutor.submit { readLoop() }
    }

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Sends a typed request to rubyn-code and returns a future that resolves
     * when the response arrives.
     *
     * The future completes exceptionally with [TimeoutException] after
     * [REQUEST_TIMEOUT_MS] milliseconds, or with [BridgeDisposedException]
     * if [dispose] is called before the response arrives.
     *
     * Thread-safe — may be called from any thread.
     */
    fun <P : Any> request(method: String, params: P, encode: (Long, String, P) -> String): CompletableFuture<RpcResponse> {
        val id = JsonRpcCodec.nextId()
        val line = encode(id, method, params)
        return sendAndRegister(id, line)
    }

    /**
     * Sends a request with no parameters.
     */
    fun request(method: String): CompletableFuture<RpcResponse> {
        val id = JsonRpcCodec.nextId()
        val line = JsonRpcCodec.encodeRequest(id, method)
        return sendAndRegister(id, line)
    }

    /**
     * Sends a one-way notification to rubyn-code (no response expected).
     * Thread-safe.
     */
    fun notifyAgent(line: String) {
        if (disposed) {
            LOG.debug("RubynBridge.notifyAgent() called after dispose — dropping")
            return
        }
        writeLine(line)
    }

    // ── Disposal ──────────────────────────────────────────────────────────

    override fun dispose() {
        if (disposed) return
        disposed = true

        LOG.info("RubynBridge disposing — cancelling ${pendingRequests.size} pending request(s)")

        readerExecutor.shutdownNow()

        // Complete all pending futures exceptionally so callers are unblocked.
        val exception = BridgeDisposedException("RubynBridge was disposed")
        pendingRequests.values.forEach { it.completeExceptionally(exception) }
        pendingRequests.clear()

        runCatching { writer.close() }
        runCatching { reader.close() }
    }

    // ── Helper methods for common requests ───────────────────────────────

    /**
     * Sends the [InitializeParams] request and returns the future result.
     * Call once after the process starts, before any other requests.
     */
    fun initialize(params: InitializeParams): CompletableFuture<RpcResponse> {
        val id = JsonRpcCodec.nextId()
        val line = JsonRpcCodec.encodeRequest(id, RpcMethod.INITIALIZE, params)
        return sendAndRegister(id, line)
    }

    /**
     * Sends [PromptSendParams] and returns the ack future.
     * The actual streaming response arrives via [notifications].
     */
    fun sendPrompt(params: PromptSendParams): CompletableFuture<RpcResponse> {
        val id = JsonRpcCodec.nextId()
        val line = JsonRpcCodec.encodeRequest(id, RpcMethod.PROMPT_SEND, params)
        return sendAndRegister(id, line)
    }

    /**
     * Cancels an in-progress prompt. Fire-and-forget (no response expected).
     */
    fun cancelPrompt(params: PromptCancelParams) {
        notifyAgent(JsonRpcCodec.encodeNotificationLine(RpcMethod.PROMPT_CANCEL, params))
    }

    /**
     * Requests a session list.
     */
    fun listSessions(): CompletableFuture<RpcResponse> = request(RpcMethod.SESSION_LIST)

    /**
     * Starts a new session.
     */
    fun startSession(params: SessionStartParams): CompletableFuture<RpcResponse> {
        val id = JsonRpcCodec.nextId()
        val line = JsonRpcCodec.encodeRequest(id, RpcMethod.SESSION_START, params)
        return sendAndRegister(id, line)
    }

    /**
     * Approves a pending tool call.
     */
    fun approveToolCall(params: ToolApprovalParams) {
        notifyAgent(JsonRpcCodec.encodeNotificationLine(RpcMethod.TOOL_APPROVE, params))
    }

    /**
     * Denies a pending tool call.
     */
    fun denyToolCall(params: ToolApprovalParams) {
        notifyAgent(JsonRpcCodec.encodeNotificationLine(RpcMethod.TOOL_DENY, params))
    }

    /**
     * Approves a proposed file edit.
     */
    fun approveFileEdit(params: FileEditApprovalParams) {
        notifyAgent(JsonRpcCodec.encodeNotificationLine(RpcMethod.FILE_EDIT_APPROVE, params))
    }

    /**
     * Denies a proposed file edit.
     */
    fun denyFileEdit(params: FileEditApprovalParams) {
        notifyAgent(JsonRpcCodec.encodeNotificationLine(RpcMethod.FILE_EDIT_DENY, params))
    }

    /**
     * Sends the shutdown request.
     */
    fun shutdown(): CompletableFuture<RpcResponse> = request(RpcMethod.SHUTDOWN)

    // ── Internal ──────────────────────────────────────────────────────────

    private fun sendAndRegister(id: Long, line: String): CompletableFuture<RpcResponse> {
        val future = CompletableFuture<RpcResponse>()

        if (disposed) {
            future.completeExceptionally(BridgeDisposedException("RubynBridge is disposed"))
            return future
        }

        pendingRequests[id] = future

        // Register a timeout that fires if rubyn-code doesn't respond in time.
        future.orTimeout(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .exceptionally { ex ->
                if (ex is TimeoutException) {
                    pendingRequests.remove(id)
                    LOG.warn("RubynBridge: request $id timed out after ${REQUEST_TIMEOUT_MS}ms")
                }
                null
            }

        writeLine(line)
        return future
    }

    private fun writeLine(line: String) {
        if (disposed) return
        try {
            synchronized(writeLock) {
                writer.write(line)
                writer.flush()
            }
        } catch (e: Exception) {
            if (!disposed) {
                LOG.warn("RubynBridge: failed to write to stdin: ${e.message}")
            }
        }
    }

    private fun readLoop() {
        LOG.info("RubynBridge: reader thread started")
        try {
            var line = reader.readLine()
            while (line != null && !disposed) {
                handleLine(line)
                line = reader.readLine()
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            if (!disposed) {
                LOG.warn("RubynBridge: reader thread terminated unexpectedly: ${e.message}", e)
            }
        } finally {
            LOG.info("RubynBridge: reader thread exiting")
        }
    }

    private fun handleLine(line: String) {
        val message = JsonRpcCodec.decodeLine(line)
        if (message == null) {
            LOG.debug("RubynBridge: dropping malformed line: $line")
            return
        }

        when (message) {
            is RpcResponse -> {
                val future = pendingRequests.remove(message.id)
                if (future != null) {
                    future.complete(message)
                } else {
                    LOG.debug("RubynBridge: received response for unknown id=${message.id}")
                }
            }

            is RpcErrorResponse -> {
                val future = pendingRequests.remove(message.id)
                if (future != null) {
                    future.completeExceptionally(
                        RpcException(message.error.code, message.error.message)
                    )
                } else {
                    LOG.warn("RubynBridge: rpc error (id=${message.id}): ${message.error.message}")
                }
            }

            is RpcNotification -> {
                // Emit on the SharedFlow — non-blocking, drops if buffer is full.
                val emitted = _notifications.tryEmit(message)
                if (!emitted) {
                    LOG.warn("RubynBridge: notification buffer full — dropping ${message.method}")
                }
            }

            is RpcRequest -> {
                // rubyn-code shouldn't be sending us requests, but be tolerant.
                LOG.debug("RubynBridge: received unexpected request from agent: ${message.method}")
            }
        }
    }
}

// ── Exceptions ────────────────────────────────────────────────────────────────

/**
 * Thrown when a request is made after the bridge has been disposed.
 */
class BridgeDisposedException(message: String) : Exception(message)

/**
 * Thrown when rubyn-code returns an error response for a request.
 */
class RpcException(
    val code: Int,
    override val message: String,
) : Exception("JSON-RPC error $code: $message")
