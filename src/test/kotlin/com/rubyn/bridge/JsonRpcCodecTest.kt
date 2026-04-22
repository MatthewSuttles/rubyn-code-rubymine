package com.rubyn.bridge

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [JsonRpcCodec].
 *
 * Tests cover:
 *   - Request encoding (typed params, no-params)
 *   - Notification encoding
 *   - Round-trip decode for every [RpcMessage] variant
 *   - Malformed / empty / blank input returns null
 *   - ID counter is strictly monotonically increasing
 *   - Unknown keys are tolerated (lenient decoder)
 *   - Schema validation: required fields present
 */
class JsonRpcCodecTest {

    // ── ID generation ─────────────────────────────────────────────────────

    @Test
    fun `nextId is monotonically increasing`() {
        val a = JsonRpcCodec.nextId()
        val b = JsonRpcCodec.nextId()
        val c = JsonRpcCodec.nextId()
        assertTrue("IDs must be strictly increasing", a < b)
        assertTrue("IDs must be strictly increasing", b < c)
    }

    @Test
    fun `nextId is positive`() {
        val id = JsonRpcCodec.nextId()
        assertTrue("ID must be positive", id > 0)
    }

    // ── Request encoding ──────────────────────────────────────────────────

    @Test
    fun `encodeRequest with params produces valid JSON-RPC 2_0 line`() {
        val id = JsonRpcCodec.nextId()
        val params = InitializeParams(workspacePath = "/my/project", extensionVersion = "0.1.0")
        val line = JsonRpcCodec.encodeRequest(id, RpcMethod.INITIALIZE, params)

        assertTrue("Line must end with newline", line.endsWith("\n"))

        val decoded = JsonRpcCodec.decodeLine(line) as? RpcRequest
        assertNotNull("Should decode to RpcRequest", decoded)
        assertEquals("2.0", decoded!!.jsonrpc)
        assertEquals(id, decoded.id)
        assertEquals(RpcMethod.INITIALIZE, decoded.method)
        assertNotNull("params should be present", decoded.params)
    }

    @Test
    fun `encodeRequest without params produces valid JSON-RPC 2_0 line`() {
        val id = JsonRpcCodec.nextId()
        val line = JsonRpcCodec.encodeRequest(id, RpcMethod.SHUTDOWN)

        assertTrue("Line must end with newline", line.endsWith("\n"))

        val decoded = JsonRpcCodec.decodeLine(line) as? RpcRequest
        assertNotNull("Should decode to RpcRequest", decoded)
        assertEquals(id, decoded!!.id)
        assertEquals(RpcMethod.SHUTDOWN, decoded.method)
        assertNull("params should be absent for no-param request", decoded.params)
    }

    @Test
    fun `encodeNotificationLine produces valid JSON-RPC 2_0 notification`() {
        val params = PromptCancelParams(sessionId = "sess-1")
        val line = JsonRpcCodec.encodeNotificationLine(RpcMethod.PROMPT_CANCEL, params)

        assertTrue("Line must end with newline", line.endsWith("\n"))

        val decoded = JsonRpcCodec.decodeLine(line) as? RpcNotification
        assertNotNull("Should decode to RpcNotification", decoded)
        assertEquals("2.0", decoded!!.jsonrpc)
        assertEquals(RpcMethod.PROMPT_CANCEL, decoded.method)
    }

    // ── Round-trip decode for every message type ──────────────────────────

    @Test
    fun `round-trip RpcRequest with session resume`() {
        val id = 42L
        val params = SessionResumeParams(sessionId = "s1")
        val line = JsonRpcCodec.encodeRequest(id, RpcMethod.SESSION_RESUME, params)
        val decoded = JsonRpcCodec.decodeLine(line) as? RpcRequest

        assertNotNull(decoded)
        assertEquals(42L, decoded!!.id)
        assertEquals(RpcMethod.SESSION_RESUME, decoded.method)
    }

    @Test
    fun `round-trip RpcResponse with result`() {
        val json = """{"jsonrpc":"2.0","id":7,"result":{"status":"ok"}}"""
        val decoded = JsonRpcCodec.decodeLine(json) as? RpcResponse

        assertNotNull(decoded)
        assertEquals(7L, decoded!!.id)
        assertNotNull(decoded.result)
    }

    @Test
    fun `round-trip RpcResponse with null result`() {
        val json = """{"jsonrpc":"2.0","id":8,"result":null}"""
        val decoded = JsonRpcCodec.decodeLine(json) as? RpcResponse

        assertNotNull(decoded)
        assertEquals(8L, decoded!!.id)
    }

    @Test
    fun `round-trip RpcErrorResponse`() {
        val json = """{"jsonrpc":"2.0","id":3,"error":{"code":-32600,"message":"Invalid Request"}}"""
        val decoded = JsonRpcCodec.decodeLine(json) as? RpcErrorResponse

        assertNotNull(decoded)
        assertEquals(3L, decoded!!.id)
        assertEquals(-32600, decoded.error.code)
        assertEquals("Invalid Request", decoded.error.message)
    }

    @Test
    fun `round-trip RpcNotification`() {
        val params = AgentStatusParams(sessionId = "s1", status = "thinking")
        val line = JsonRpcCodec.encodeNotificationLine(NotificationMethod.AGENT_STATUS, params)
        val decoded = JsonRpcCodec.decodeLine(line) as? RpcNotification

        assertNotNull(decoded)
        assertEquals(NotificationMethod.AGENT_STATUS, decoded!!.method)
    }

    @Test
    fun `round-trip stream-text notification`() {
        val params = StreamTextParams(sessionId = "s1", text = "Hello")
        val line = JsonRpcCodec.encodeNotificationLine(NotificationMethod.STREAM_TEXT, params)
        val decoded = JsonRpcCodec.decodeLine(line) as? RpcNotification

        assertNotNull(decoded)
        assertEquals(NotificationMethod.STREAM_TEXT, decoded!!.method)
        assertNotNull(decoded.params)
    }

    @Test
    fun `round-trip stream-text final notification`() {
        val params = StreamTextParams(sessionId = "s1", text = "Full text", final = true)
        val line = JsonRpcCodec.encodeNotificationLine(NotificationMethod.STREAM_TEXT, params)
        val decoded = JsonRpcCodec.decodeLine(line) as? RpcNotification

        assertNotNull(decoded)
        assertEquals(NotificationMethod.STREAM_TEXT, decoded!!.method)
    }

    @Test
    fun `round-trip InitializeParams`() {
        val id = JsonRpcCodec.nextId()
        val params = InitializeParams(workspacePath = "/home/user/project", extensionVersion = "1.0.0")
        val line = JsonRpcCodec.encodeRequest(id, RpcMethod.INITIALIZE, params)
        val decoded = JsonRpcCodec.decodeLine(line) as? RpcRequest

        assertNotNull(decoded)
        val decodedParams = JsonRpcCodec.decodeParams(
            decoded!!.params,
            InitializeParams.serializer()
        )
        assertNotNull(decodedParams)
        assertEquals("1.0.0", decodedParams!!.extensionVersion)
        assertEquals("/home/user/project", decodedParams.workspacePath)
    }

    @Test
    fun `round-trip PromptSendParams with context`() {
        val id = JsonRpcCodec.nextId()
        val params = PromptSendParams(
            text = "Explain this",
            sessionId = "sess",
            context = EditorContextParams(
                activeFile = "/app/foo.rb",
                selection = SelectionContext(
                    startLine = 1,
                    endLine = 5,
                    text = "def foo; end",
                ),
            )
        )
        val line = JsonRpcCodec.encodeRequest(id, RpcMethod.PROMPT_SEND, params)
        val decoded = JsonRpcCodec.decodeLine(line) as? RpcRequest
        assertNotNull(decoded)

        val decodedParams = JsonRpcCodec.decodeParams(decoded!!.params, PromptSendParams.serializer())
        assertNotNull(decodedParams)
        assertEquals("sess", decodedParams!!.sessionId)
        assertEquals("Explain this", decodedParams.text)
        assertNotNull(decodedParams.context)
        assertEquals("/app/foo.rb", decodedParams.context!!.activeFile)
    }

    @Test
    fun `round-trip FileEditParams`() {
        val params = FileEditParams(
            editId = "edit-1",
            path = "/app/models/user.rb",
            content = "class User\n  validates :name\nend",
            type = "update",
        )
        val line = JsonRpcCodec.encodeNotificationLine(NotificationMethod.FILE_EDIT, params)
        val decoded = JsonRpcCodec.decodeLine(line) as? RpcNotification
        assertNotNull(decoded)

        val decodedParams = JsonRpcCodec.decodeParams(decoded!!.params, FileEditParams.serializer())
        assertNotNull(decodedParams)
        assertEquals("edit-1", decodedParams!!.editId)
        assertEquals("/app/models/user.rb", decodedParams.path)
    }

    @Test
    fun `round-trip SessionCostParams`() {
        val params = SessionCostParams(
            sessionId = "s1",
            inputTokens = 1000,
            outputTokens = 500,
            costUsd = 0.015,
        )
        val line = JsonRpcCodec.encodeNotificationLine(NotificationMethod.SESSION_COST, params)
        val decoded = JsonRpcCodec.decodeLine(line) as? RpcNotification
        val decodedParams = JsonRpcCodec.decodeParams(decoded!!.params, SessionCostParams.serializer())

        assertNotNull(decodedParams)
        assertEquals(1000, decodedParams!!.inputTokens)
        assertEquals(500, decodedParams.outputTokens)
        assertEquals(0.015, decodedParams.costUsd, 0.0001)
    }

    @Test
    fun `round-trip ToolApprovalParams`() {
        val params = ToolApprovalParams(requestId = "tc-1", approved = true)
        val line = JsonRpcCodec.encodeNotificationLine(RpcMethod.APPROVE_TOOL_USE, params)
        val decoded = JsonRpcCodec.decodeLine(line) as? RpcNotification
        val decodedParams = JsonRpcCodec.decodeParams(decoded!!.params, ToolApprovalParams.serializer())

        assertNotNull(decodedParams)
        assertEquals("tc-1", decodedParams!!.requestId)
        assertEquals(true, decodedParams.approved)
    }

    // ── Malformed / bad input ─────────────────────────────────────────────

    @Test
    fun `decodeLine returns null for empty string`() {
        assertNull(JsonRpcCodec.decodeLine(""))
    }

    @Test
    fun `decodeLine returns null for blank string`() {
        assertNull(JsonRpcCodec.decodeLine("   "))
    }

    @Test
    fun `decodeLine returns null for plain text`() {
        assertNull(JsonRpcCodec.decodeLine("not json at all"))
    }

    @Test
    fun `decodeLine returns null for truncated JSON`() {
        assertNull(JsonRpcCodec.decodeLine("""{"jsonrpc":"2.0","id":1"""))
    }

    @Test
    fun `decodeLine does not throw on any malformed input`() {
        val inputs = listOf(
            "null",
            "true",
            "{}",
            """{"jsonrpc":"2.0"}""",
        )
        for (input in inputs) {
            try {
                JsonRpcCodec.decodeLine(input)
            } catch (e: Exception) {
                throw AssertionError("decodeLine threw on input: $input", e)
            }
        }
    }

    // ── Schema validation: required fields ────────────────────────────────

    @Test
    fun `RpcRequest has jsonrpc 2_0 field`() {
        val id = JsonRpcCodec.nextId()
        val line = JsonRpcCodec.encodeRequest(id, "test/method")
        // encodeDefaults=true: jsonrpc "2.0" is explicitly included in encoded JSON
        // (required by JSON-RPC 2.0 spec and the rubyn-code CLI).
        assertTrue("jsonrpc field must be present in encoded JSON", line.contains("\"jsonrpc\":\"2.0\""))
        val decoded = JsonRpcCodec.decodeLine(line) as? RpcRequest
        assertNotNull("Should decode to RpcRequest", decoded)
        assertEquals("Decoded jsonrpc must be 2.0", "2.0", decoded!!.jsonrpc)
    }

    @Test
    fun `RpcRequest has id and method fields`() {
        val id = 999L
        val line = JsonRpcCodec.encodeRequest(id, "test/method")
        assertTrue("id field should be present", line.contains("\"id\""))
        assertTrue("method field should be present", line.contains("\"method\""))
    }

    @Test
    fun `RpcNotification does not have id field`() {
        val params = AgentStatusParams(sessionId = "s1", status = "idle")
        val line = JsonRpcCodec.encodeNotificationLine(NotificationMethod.AGENT_STATUS, params)
        val obj = JsonRpcCodec.json.parseToJsonElement(line.trim()) as JsonObject
        assertTrue("notification must not have 'id' field", !obj.containsKey("id"))
    }

    // ── Lenient decoder: unknown keys are tolerated ───────────────────────

    @Test
    fun `decodeLine tolerates extra unknown fields in response`() {
        val json = """{"jsonrpc":"2.0","id":1,"result":{"data":"ok"},"extra_field":"ignored"}"""
        val decoded = JsonRpcCodec.decodeLine(json)
        assertNotNull("Should not fail on unknown fields", decoded)
        assertTrue("Should decode as RpcResponse", decoded is RpcResponse)
    }

    @Test
    fun `decodeLine tolerates extra unknown fields in notification`() {
        val json = """{"jsonrpc":"2.0","method":"stream/text","params":{"sessionId":"s1","text":"hi"},"future_field":true}"""
        val decoded = JsonRpcCodec.decodeLine(json)
        assertNotNull("Should not fail on unknown fields", decoded)
        assertTrue("Should decode as RpcNotification", decoded is RpcNotification)
    }

    // ── RpcMessageSerializer discriminator logic ──────────────────────────

    @Test
    fun `discriminator selects RpcRequest for id+method`() {
        val json = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}"""
        val decoded = JsonRpcCodec.decodeLine(json)
        assertTrue("Has id+method -> RpcRequest", decoded is RpcRequest)
    }

    @Test
    fun `discriminator selects RpcResponse for id+result`() {
        val json = """{"jsonrpc":"2.0","id":2,"result":{}}"""
        val decoded = JsonRpcCodec.decodeLine(json)
        assertTrue("Has id+result -> RpcResponse", decoded is RpcResponse)
    }

    @Test
    fun `discriminator selects RpcErrorResponse for id+error`() {
        val json = """{"jsonrpc":"2.0","id":3,"error":{"code":-32700,"message":"Parse error"}}"""
        val decoded = JsonRpcCodec.decodeLine(json)
        assertTrue("Has id+error -> RpcErrorResponse", decoded is RpcErrorResponse)
    }

    @Test
    fun `discriminator selects RpcNotification for method without id`() {
        val json = """{"jsonrpc":"2.0","method":"agent/status","params":{"sessionId":"s1","status":"idle"}}"""
        val decoded = JsonRpcCodec.decodeLine(json)
        assertTrue("Has method+no id -> RpcNotification", decoded is RpcNotification)
    }

    // ── decodeParams ──────────────────────────────────────────────────────

    @Test
    fun `decodeParams returns null for null element`() {
        val result = JsonRpcCodec.decodeParams(null, AgentStatusParams.serializer())
        assertNull(result)
    }

    @Test
    fun `decodeParams deserializes AgentStatusParams correctly`() {
        val params = AgentStatusParams(sessionId = "abc", status = "streaming")
        val line = JsonRpcCodec.encodeNotificationLine(NotificationMethod.AGENT_STATUS, params)
        val notification = JsonRpcCodec.decodeLine(line) as RpcNotification
        val decoded = JsonRpcCodec.decodeParams(notification.params, AgentStatusParams.serializer())

        assertNotNull(decoded)
        assertEquals("streaming", decoded!!.status)
        assertEquals("abc", decoded.sessionId)
    }
}
