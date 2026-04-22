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

    // Session management — names must match rubyn-code handler registry
    const val SESSION_RESET  = "session/reset"
    const val SESSION_RESUME = "session/resume"
    const val SESSION_LIST   = "session/list"
    const val SESSION_FORK   = "session/fork"

    // Prompt / interaction — CLI uses bare names, not namespaced
    const val PROMPT_SEND   = "prompt"
    const val PROMPT_CANCEL = "cancel"

    // Code review
    const val REVIEW_REQUEST = "review"

    // Tool approval (CLI: approve=true/false in params)
    const val APPROVE_TOOL_USE  = "approveToolUse"
    const val TOOL_APPROVE      = "approveToolUse"
    const val TOOL_DENY         = "approveToolUse"

    // File edit approval (CLI: accepted=true/false in params)
    const val ACCEPT_EDIT       = "acceptEdit"
    const val FILE_EDIT_APPROVE = "acceptEdit"
    const val FILE_EDIT_DENY    = "acceptEdit"

    // Configuration
    const val CONFIG_GET    = "config/get"
    const val CONFIG_SET    = "config/set"
    const val MODELS_LIST   = "models/list"
}

/**
 * Notification method names pushed by rubyn-code (inbound, no reply needed).
 */
object NotificationMethod {
    // Streaming text deltas
    const val STREAM_TEXT    = "stream/text"

    // Tool use / result
    const val TOOL_USE       = "tool/use"
    const val TOOL_RESULT    = "tool/result"

    // File edits proposed by the agent
    const val FILE_EDIT      = "file/edit"
    const val FILE_CREATE    = "file/create"

    // Agent lifecycle
    const val AGENT_STATUS   = "agent/status"

    // Session cost update
    const val SESSION_COST   = "session/cost"

    // Error from the agent (not associated with a pending request)
    const val AGENT_ERROR    = "agent/error"

    // Code review findings
    const val REVIEW_FINDING = "review/finding"

    // Config changed
    const val CONFIG_CHANGED = "config/changed"
}

// ── Typed parameter / result data classes ────────────────────────────────────

/**
 * Params for [RpcMethod.INITIALIZE]. Sent once after the process starts.
 * Field names match CLI's InitializeHandler expectations.
 */
@Serializable
data class InitializeParams(
    val workspacePath: String,
    val extensionVersion: String,
    val capabilities: JsonElement? = null,
)

/**
 * Result from [RpcMethod.INITIALIZE].
 */
@Serializable
data class InitializeResult(
    val serverVersion: String,
    val protocolVersion: String,
    val workspacePath: String,
    val capabilities: AgentCapabilities,
)

@Serializable
data class AgentCapabilities(
    val streaming: Boolean = true,
    val tools: Int = 0,
    val skills: Int = 0,
    val review: Boolean = false,
    val memory: Boolean = false,
    val teams: Boolean = false,
    val toolApproval: Boolean = false,
    val editApproval: Boolean = false,
)

/**
 * Params for [RpcMethod.SESSION_RESUME].
 */
@Serializable
data class SessionResumeParams(
    val sessionId: String,
)

/**
 * Params for [RpcMethod.SESSION_RESET].
 */
@Serializable
data class SessionResetParams(
    val sessionId: String,
)

/**
 * Result from [RpcMethod.SESSION_LIST].
 * Fields match CLI's SessionListHandler response.
 */
@Serializable
data class SessionListResult(
    val sessions: List<SessionInfo>,
)

@Serializable
data class SessionInfo(
    val id: String,
    val title: String,
    val updatedAt: String,
    val messageCount: Int,
)

/**
 * Params for [RpcMethod.PROMPT_SEND] ("prompt").
 * Fields match CLI's PromptHandler expectations.
 */
@Serializable
data class PromptSendParams(
    val text: String,
    val sessionId: String? = null,
    val context: EditorContextParams? = null,
)

/**
 * Editor context attached to a prompt — selected code, file info, etc.
 */
@Serializable
data class EditorContextParams(
    val workspacePath: String? = null,
    val activeFile: String? = null,
    val selection: SelectionContext? = null,
    val openFiles: List<String>? = null,
)

@Serializable
data class SelectionContext(
    val startLine: Int,
    val endLine: Int,
    val text: String,
)

/**
 * Params for [RpcMethod.PROMPT_CANCEL] ("cancel").
 * Field names match CLI's CancelHandler.
 */
@Serializable
data class PromptCancelParams(
    val sessionId: String,
)

/**
 * Params for [RpcMethod.REVIEW_REQUEST] ("review").
 * Field names match CLI's ReviewHandler.
 */
@Serializable
data class ReviewRequestParams(
    val sessionId: String? = null,
    val baseBranch: String? = null,
    val focus: String? = null,
)

/**
 * Params for [RpcMethod.APPROVE_TOOL_USE] ("approveToolUse").
 * CLI expects requestId + approved boolean.
 */
@Serializable
data class ToolApprovalParams(
    val requestId: String,
    val approved: Boolean,
)

/**
 * Params for [RpcMethod.ACCEPT_EDIT] ("acceptEdit").
 * CLI expects editId + accepted boolean.
 */
@Serializable
data class FileEditApprovalParams(
    val editId: String,
    val accepted: Boolean,
)

// ── Inbound notification param types ─────────────────────────────────────────

/**
 * Params for [NotificationMethod.STREAM_TEXT] — a streaming text chunk.
 * CLI sends: sessionId, text, final
 */
@Serializable
data class StreamTextParams(
    val sessionId: String,
    val text: String,
    val final: Boolean = false,
)

/**
 * Params for [NotificationMethod.TOOL_USE] — agent wants to call a tool.
 * CLI sends: requestId, tool, args, requiresApproval
 */
@Serializable
data class ToolUseParams(
    val requestId: String,
    val tool: String,
    val args: JsonElement,
    val requiresApproval: Boolean = false,
)

/**
 * Params for [NotificationMethod.TOOL_RESULT] — tool execution result.
 */
@Serializable
data class ToolResultParams(
    val requestId: String,
    val tool: String,
    val success: Boolean,
    val summary: String,
)

/**
 * Params for [NotificationMethod.FILE_EDIT] — agent proposes a file change.
 * CLI sends: editId, path, content, type
 */
@Serializable
data class FileEditParams(
    val editId: String,
    val path: String,
    val content: String,
    val type: String? = null,
)

/**
 * Params for [NotificationMethod.AGENT_STATUS].
 * CLI sends: sessionId, status, error (optional), summary (optional)
 */
@Serializable
data class AgentStatusParams(
    val sessionId: String,
    val status: String,  // "thinking" | "streaming" | "done" | "cancelled" | "error" | "reviewing"
    val error: String? = null,
    val summary: String? = null,
)

/**
 * Params for [NotificationMethod.SESSION_COST].
 */
@Serializable
data class SessionCostParams(
    val sessionId: String,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val costUsd: Double = 0.0,
)

/**
 * Params for [NotificationMethod.AGENT_ERROR].
 */
@Serializable
data class AgentErrorParams(
    val message: String,
    val code: Int? = null,
    val sessionId: String? = null,
)

/**
 * Params for [NotificationMethod.REVIEW_FINDING].
 */
@Serializable
data class ReviewFindingParams(
    val sessionId: String,
    val index: Int,
    val severity: String,
    val message: String,
    val file: String? = null,
    val line: Int? = null,
)
