package com.rubyn.bridge

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// ── JSON-RPC 2.0 envelope types ───────────────────────────────────────────────

/**
 * Top-level JSON-RPC 2.0 message. The bridge reads raw lines from stdout,
 * then delegates to [RpcMessageSerializer] to resolve the concrete type.
 */
@Serializable(with = RpcMessageSerializer::class)
sealed class RpcMessage

/**
 * A request sent from the plugin to rubyn-code. Carries a numeric [id] that
 * the bridge uses to correlate the response.
 */
@Serializable
data class RpcRequest(
    val jsonrpc: String = "2.0",
    val id: Long,
    val method: String,
    val params: JsonElement? = null,
) : RpcMessage()

/**
 * A successful response from rubyn-code. [id] matches the originating request.
 */
@Serializable
data class RpcResponse(
    val jsonrpc: String = "2.0",
    val id: Long,
    val result: JsonElement? = null,
) : RpcMessage()

/**
 * An error response from rubyn-code. [id] matches the originating request,
 * or is -1 when the error is not associated with any request.
 */
@Serializable
data class RpcErrorResponse(
    val jsonrpc: String = "2.0",
    val id: Long = -1,
    val error: RpcError,
) : RpcMessage()

/**
 * A one-way notification from rubyn-code. Has no [id] and requires no reply.
 */
@Serializable
data class RpcNotification(
    val jsonrpc: String = "2.0",
    val method: String,
    val params: JsonElement? = null,
) : RpcMessage()

/**
 * Error payload inside [RpcErrorResponse].
 */
@Serializable
data class RpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null,
)

// ── Polymorphic deserializer ──────────────────────────────────────────────────

/**
 * Resolves the concrete [RpcMessage] subtype from a raw JSON object by
 * inspecting the standard JSON-RPC 2.0 discriminator fields.
 *
 * Rules:
 *   - Has "id" + "method"          → [RpcRequest]
 *   - Has "id" + "result"          → [RpcResponse]
 *   - Has "id" + "error"           → [RpcErrorResponse]
 *   - Has "method" (no "id")       → [RpcNotification]
 *   - Anything else                → [RpcNotification] (fallback, method="unknown")
 */
object RpcMessageSerializer : JsonContentPolymorphicSerializer<RpcMessage>(RpcMessage::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<RpcMessage> {
        val obj = element.jsonObject
        val hasId = "id" in obj
        val hasMethod = "method" in obj
        val hasResult = "result" in obj
        val hasError = "error" in obj

        return when {
            hasId && hasMethod -> RpcRequest.serializer()
            hasId && hasResult -> RpcResponse.serializer()
            hasId && hasError  -> RpcErrorResponse.serializer()
            hasMethod          -> RpcNotification.serializer()
            else               -> RpcNotification.serializer()
        }
    }
}

// ── Method constants ──────────────────────────────────────────────────────────

/**
 * Method names for all JSON-RPC requests sent by the plugin (outbound).
 */
object RpcMethod {
    // Lifecycle
    const val INITIALIZE    = "initialize"
    const val SHUTDOWN      = "shutdown"

    // Session management
    const val SESSION_START = "session/start"
    const val SESSION_END   = "session/end"
    const val SESSION_LIST  = "session/list"

    // Prompt / interaction
    const val PROMPT_SEND   = "prompt/send"
    const val PROMPT_CANCEL = "prompt/cancel"

    // Code review
    const val REVIEW_REQUEST = "review/request"

    // Tool approval responses (outbound from plugin)
    const val TOOL_APPROVE = "tool/approve"
    const val TOOL_DENY    = "tool/deny"

    // File edit approval responses
    const val FILE_EDIT_APPROVE = "file/edit/approve"
    const val FILE_EDIT_DENY    = "file/edit/deny"

    // Session management (extended)
    const val SESSION_EXPORT = "session/export"
    const val SESSION_DELETE = "session/delete"
}

/**
 * Notification method names pushed by rubyn-code (inbound, no reply needed).
 */
object NotificationMethod {
    // Streaming text deltas
    const val STREAM_TEXT   = "stream/text"
    const val STREAM_DONE   = "stream/done"

    // Tool use
    const val TOOL_USE      = "tool/use"

    // File edits proposed by the agent
    const val FILE_EDIT     = "file/edit"

    // Agent lifecycle
    const val AGENT_STATUS  = "agent/status"

    // Session cost update
    const val SESSION_COST  = "session/cost"

    // Error from the agent (not associated with a pending request)
    const val AGENT_ERROR   = "agent/error"
}

// ── Typed parameter / result data classes ────────────────────────────────────

/**
 * Params for [RpcMethod.INITIALIZE]. Sent once after the process starts.
 */
@Serializable
data class InitializeParams(
    @SerialName("client_version") val clientVersion: String,
    @SerialName("project_dir")    val projectDir: String,
)

/**
 * Result from [RpcMethod.INITIALIZE].
 */
@Serializable
data class InitializeResult(
    @SerialName("agent_version") val agentVersion: String,
    val capabilities: AgentCapabilities,
)

@Serializable
data class AgentCapabilities(
    val streaming: Boolean = true,
    @SerialName("tool_use")   val toolUse: Boolean = true,
    @SerialName("file_edits") val fileEdits: Boolean = true,
)

/**
 * Params for [RpcMethod.SESSION_START].
 */
@Serializable
data class SessionStartParams(
    @SerialName("session_id") val sessionId: String,
    val model: String? = null,
)

/**
 * Result from [RpcMethod.SESSION_LIST].
 */
@Serializable
data class SessionListResult(
    val sessions: List<SessionInfo>,
)

@Serializable
data class SessionInfo(
    @SerialName("session_id") val sessionId: String,
    val label: String,
    @SerialName("created_at") val createdAt: String,
    val active: Boolean,
)

/**
 * Params for [RpcMethod.PROMPT_SEND].
 */
@Serializable
data class PromptSendParams(
    @SerialName("session_id") val sessionId: String,
    @SerialName("message_id") val messageId: String,
    val text: String,
    val context: EditorContextParams? = null,
)

/**
 * Editor context attached to a prompt — selected code, file info, etc.
 */
@Serializable
data class EditorContextParams(
    @SerialName("file_path")     val filePath: String? = null,
    @SerialName("selected_text") val selectedText: String? = null,
    @SerialName("language")      val language: String? = null,
    @SerialName("cursor_line")   val cursorLine: Int? = null,
)

/**
 * Params for [RpcMethod.PROMPT_CANCEL].
 */
@Serializable
data class PromptCancelParams(
    @SerialName("session_id") val sessionId: String,
    @SerialName("message_id") val messageId: String,
)

/**
 * Params for [RpcMethod.REVIEW_REQUEST].
 */
@Serializable
data class ReviewRequestParams(
    @SerialName("session_id") val sessionId: String,
    @SerialName("file_path")  val filePath: String,
    val content: String,
    val focus: String? = null,
)

/**
 * Params for [RpcMethod.TOOL_APPROVE] / [RpcMethod.TOOL_DENY].
 */
@Serializable
data class ToolApprovalParams(
    @SerialName("tool_call_id") val toolCallId: String,
    @SerialName("session_id")   val sessionId: String,
)

/**
 * Params for [RpcMethod.FILE_EDIT_APPROVE] / [RpcMethod.FILE_EDIT_DENY].
 */
@Serializable
data class FileEditApprovalParams(
    @SerialName("edit_id")    val editId: String,
    @SerialName("session_id") val sessionId: String,
)

// ── Inbound notification param types ─────────────────────────────────────────

/**
 * Params for [NotificationMethod.STREAM_TEXT] — a streaming delta chunk.
 */
@Serializable
data class StreamTextParams(
    @SerialName("session_id")  val sessionId: String,
    @SerialName("message_id")  val messageId: String,
    val delta: String,
)

/**
 * Params for [NotificationMethod.STREAM_DONE] — streaming complete.
 */
@Serializable
data class StreamDoneParams(
    @SerialName("session_id") val sessionId: String,
    @SerialName("message_id") val messageId: String,
    val content: String,
)

/**
 * Params for [NotificationMethod.TOOL_USE] — agent wants to call a tool.
 */
@Serializable
data class ToolUseParams(
    @SerialName("session_id")   val sessionId: String,
    @SerialName("tool_call_id") val toolCallId: String,
    val name: String,
    val args: JsonElement,
    val diff: FileDiffPayload? = null,
)

/**
 * A proposed file edit — before/after diff payload.
 */
@Serializable
data class FileDiffPayload(
    val path: String,
    val before: String,
    val after: String,
)

/**
 * Params for [NotificationMethod.FILE_EDIT] — agent proposes a file change.
 */
@Serializable
data class FileEditParams(
    @SerialName("session_id") val sessionId: String,
    @SerialName("edit_id")    val editId: String,
    val diff: FileDiffPayload,
)

/**
 * Params for [NotificationMethod.AGENT_STATUS].
 */
@Serializable
data class AgentStatusParams(
    val status: String,  // "idle" | "thinking" | "streaming" | "waiting_approval"
    @SerialName("session_id") val sessionId: String? = null,
)

/**
 * Params for [NotificationMethod.SESSION_COST].
 */
@Serializable
data class SessionCostParams(
    @SerialName("session_id")    val sessionId: String,
    @SerialName("input_tokens")  val inputTokens: Int,
    @SerialName("output_tokens") val outputTokens: Int,
    @SerialName("cost_usd")      val costUsd: Double,
)

/**
 * Params for [NotificationMethod.AGENT_ERROR].
 */
@Serializable
data class AgentErrorParams(
    val message: String,
    val code: Int? = null,
    @SerialName("session_id") val sessionId: String? = null,
)

/**
 * Params for [RpcMethod.SESSION_EXPORT].
 */
@Serializable
data class SessionExportParams(
    @SerialName("session_id") val sessionId: String,
)

/**
 * Result from [RpcMethod.SESSION_EXPORT] — raw JSON transcript content.
 */
@Serializable
data class SessionExportResult(
    val content: String,
)

/**
 * Params for [RpcMethod.SESSION_DELETE].
 */
@Serializable
data class SessionDeleteParams(
    @SerialName("session_id") val sessionId: String,
)
