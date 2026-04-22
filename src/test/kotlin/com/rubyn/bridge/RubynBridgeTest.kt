package com.rubyn.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.TimeUnit

/**
 * Unit tests for [RubynBridge].
 *
 * Uses in-process pipes to simulate the rubyn-code stdio channel — no real
 * process is spawned. Tests cover:
 *   - Request/response correlation by ID
 *   - Notification routing to SharedFlow
 *   - Dispose cancels pending futures with [BridgeDisposedException]
 *   - Malformed lines are skipped — bridge stays alive
 *   - notifyAgent is fire-and-forget (no future registered)
 *   - dispose is idempotent
 *   - cancelPrompt sends a request (returns a future)
 *   - resolveToolApproval sends a request with approval boolean
 */
class RubynBridgeTest {

    /**
     * Constructs a [RubynBridge] wired to in-process pipes.
     *
     * Returns Triple(bridge, agentWrite, agentRead) where:
     *   agentWrite — the [PipedOutputStream] the "agent" writes responses into
     *                (data flows: agentWrite → pluginReadEnd → bridge reader)
     *   agentRead  — the [PipedInputStream]  the "agent" reads requests from
     *                (data flows: bridge writer → pluginWriteEnd → agentRead)
     */
    private fun buildBridge(): Triple<RubynBridge, PipedOutputStream, PipedInputStream> {
        val pluginWriteEnd = PipedOutputStream()   // plugin writes requests here
        val agentReadEnd   = PipedInputStream(pluginWriteEnd) // agent reads from here

        val agentWriteEnd  = PipedOutputStream()   // agent writes responses here
        val pluginReadEnd  = PipedInputStream(agentWriteEnd)  // bridge reads from here

        val bridge = RubynBridge(stdin = pluginWriteEnd, stdout = pluginReadEnd)
        return Triple(bridge, agentWriteEnd, agentReadEnd)
    }

    // ── Request/response correlation ──────────────────────────────────────

    @Test
    fun `request completes with RpcResponse on matching reply`() {
        val (bridge, agentWrite, agentRead) = buildBridge()

        try {
            val future = bridge.listSessions()
            Thread.sleep(50)

            // Read what the bridge wrote to discover the assigned ID
            val requestLine = agentRead.bufferedReader().readLine()
            assertNotNull("Bridge should have sent a request", requestLine)

            val requestDecoded = JsonRpcCodec.decodeLine(requestLine!!) as? RpcRequest
            assertNotNull("Should be an RpcRequest", requestDecoded)
            val requestId = requestDecoded!!.id

            // Reply with a matching response
            val responseLine = """{"jsonrpc":"2.0","id":$requestId,"result":{"sessions":[]}}""" + "\n"
            agentWrite.write(responseLine.toByteArray(Charsets.UTF_8))
            agentWrite.flush()

            val response = future.get(2, TimeUnit.SECONDS)
            assertNotNull("Future should complete with a response", response)
            assertEquals(requestId, response.id)
        } finally {
            bridge.dispose()
            runCatching { agentWrite.close() }
            runCatching { agentRead.close() }
        }
    }

    @Test
    fun `error response completes future exceptionally with RpcException`() {
        val (bridge, agentWrite, agentRead) = buildBridge()

        try {
            val future = bridge.listSessions()
            Thread.sleep(50)

            val requestLine = agentRead.bufferedReader().readLine()
            val requestDecoded = JsonRpcCodec.decodeLine(requestLine!!) as? RpcRequest
            val requestId = requestDecoded!!.id

            val errorLine = """{"jsonrpc":"2.0","id":$requestId,"error":{"code":-32600,"message":"Bad request"}}""" + "\n"
            agentWrite.write(errorLine.toByteArray(Charsets.UTF_8))
            agentWrite.flush()

            try {
                future.get(2, TimeUnit.SECONDS)
                throw AssertionError("Future should have completed exceptionally")
            } catch (e: java.util.concurrent.ExecutionException) {
                assertTrue("Cause should be RpcException", e.cause is RpcException)
                val rpcEx = e.cause as RpcException
                assertEquals(-32600, rpcEx.code)
                assertEquals("Bad request", rpcEx.message)
            }
        } finally {
            bridge.dispose()
            runCatching { agentWrite.close() }
            runCatching { agentRead.close() }
        }
    }

    @Test
    fun `multiple concurrent requests are correlated independently`() {
        val (bridge, agentWrite, agentRead) = buildBridge()

        try {
            val f1 = bridge.listSessions()
            val f2 = bridge.shutdown()
            Thread.sleep(80)

            val reader = agentRead.bufferedReader()
            val line1 = reader.readLine()!!
            val line2 = reader.readLine()!!

            val req1 = JsonRpcCodec.decodeLine(line1) as RpcRequest
            val req2 = JsonRpcCodec.decodeLine(line2) as RpcRequest

            // Reply in reverse order — responses must still go to the right future
            val resp2 = """{"jsonrpc":"2.0","id":${req2.id},"result":{"ok":true}}""" + "\n"
            val resp1 = """{"jsonrpc":"2.0","id":${req1.id},"result":{"sessions":[]}}""" + "\n"
            agentWrite.write(resp2.toByteArray(Charsets.UTF_8)); agentWrite.flush()
            Thread.sleep(30)
            agentWrite.write(resp1.toByteArray(Charsets.UTF_8)); agentWrite.flush()

            val r1 = f1.get(2, TimeUnit.SECONDS)
            val r2 = f2.get(2, TimeUnit.SECONDS)

            assertEquals(req1.id, r1.id)
            assertEquals(req2.id, r2.id)
        } finally {
            bridge.dispose()
            runCatching { agentWrite.close() }
            runCatching { agentRead.close() }
        }
    }

    // ── Notification routing ──────────────────────────────────────────────

    @Test
    fun `notifications are emitted to SharedFlow`() {
        val (bridge, agentWrite, _) = buildBridge()
        val received = mutableListOf<RpcNotification>()
        val scope = CoroutineScope(Dispatchers.IO)
        var collectJob: Job? = null

        try {
            collectJob = scope.launch {
                bridge.notifications.collect { received.add(it) }
            }
            Thread.sleep(50)

            val notifLine = """{"jsonrpc":"2.0","method":"agent/status","params":{"status":"thinking"}}""" + "\n"
            agentWrite.write(notifLine.toByteArray(Charsets.UTF_8))
            agentWrite.flush()

            Thread.sleep(150)

            assertEquals("Should have received one notification", 1, received.size)
            assertEquals("agent/status", received[0].method)
        } finally {
            collectJob?.cancel()
            scope.cancel()
            bridge.dispose()
            runCatching { agentWrite.close() }
        }
    }

    @Test
    fun `multiple notifications are all routed to SharedFlow`() {
        val (bridge, agentWrite, _) = buildBridge()
        val received = mutableListOf<RpcNotification>()
        val scope = CoroutineScope(Dispatchers.IO)
        var collectJob: Job? = null

        try {
            collectJob = scope.launch {
                bridge.notifications.collect { received.add(it) }
            }
            Thread.sleep(50)

            repeat(3) { i ->
                val line = """{"jsonrpc":"2.0","method":"stream/text","params":{"session_id":"s1","message_id":"m1","delta":"chunk$i"}}""" + "\n"
                agentWrite.write(line.toByteArray(Charsets.UTF_8))
                agentWrite.flush()
            }

            Thread.sleep(200)

            assertEquals("Should have received 3 notifications", 3, received.size)
            received.forEach { assertEquals("stream/text", it.method) }
        } finally {
            collectJob?.cancel()
            scope.cancel()
            bridge.dispose()
            runCatching { agentWrite.close() }
        }
    }

    // ── Malformed input ───────────────────────────────────────────────────

    @Test
    fun `malformed lines are skipped and bridge continues processing`() {
        val (bridge, agentWrite, agentRead) = buildBridge()

        try {
            // Flood bridge with garbage
            val garbage = "not json\n{broken\n\n   \n"
            agentWrite.write(garbage.toByteArray(Charsets.UTF_8))
            agentWrite.flush()
            Thread.sleep(50)

            // Bridge should still function — send a real request and get a reply
            val future = bridge.listSessions()
            Thread.sleep(50)

            val requestLine = agentRead.bufferedReader().readLine()
            val requestDecoded = JsonRpcCodec.decodeLine(requestLine!!) as? RpcRequest
            assertNotNull(requestDecoded)

            val responseLine = """{"jsonrpc":"2.0","id":${requestDecoded!!.id},"result":{"sessions":[]}}""" + "\n"
            agentWrite.write(responseLine.toByteArray(Charsets.UTF_8))
            agentWrite.flush()

            val response = future.get(2, TimeUnit.SECONDS)
            assertNotNull("Bridge should still function after malformed input", response)
        } finally {
            bridge.dispose()
            runCatching { agentWrite.close() }
            runCatching { agentRead.close() }
        }
    }

    @Test
    fun `response for unknown id is silently dropped`() {
        val (bridge, agentWrite, _) = buildBridge()

        try {
            Thread.sleep(30)
            val strayResponse = """{"jsonrpc":"2.0","id":999999,"result":{"data":"orphan"}}""" + "\n"
            agentWrite.write(strayResponse.toByteArray(Charsets.UTF_8))
            agentWrite.flush()
            Thread.sleep(100)
            // If bridge crashed it would close the pipe and the above would throw — test passes if silent
        } finally {
            bridge.dispose()
            runCatching { agentWrite.close() }
        }
    }

    // ── Dispose ───────────────────────────────────────────────────────────

    @Test
    fun `dispose cancels all pending futures with BridgeDisposedException`() {
        val (bridge, agentWrite, _) = buildBridge()

        try {
            val f1 = bridge.listSessions()
            val f2 = bridge.shutdown()
            Thread.sleep(30)

            bridge.dispose()

            for (future in listOf(f1, f2)) {
                assertTrue("Future should be done after dispose", future.isDone)
                try {
                    future.get(1, TimeUnit.SECONDS)
                    throw AssertionError("Future should have thrown")
                } catch (e: java.util.concurrent.ExecutionException) {
                    assertTrue(
                        "Cause should be BridgeDisposedException but was ${e.cause?.javaClass?.simpleName}",
                        e.cause is BridgeDisposedException
                    )
                }
            }
        } finally {
            runCatching { agentWrite.close() }
        }
    }

    @Test
    fun `dispose is idempotent`() {
        val (bridge, agentWrite, _) = buildBridge()
        try {
            bridge.dispose()
            bridge.dispose() // must not throw
        } finally {
            runCatching { agentWrite.close() }
        }
    }

    @Test
    fun `request after dispose returns immediately exceptional`() {
        val (bridge, agentWrite, _) = buildBridge()

        try {
            bridge.dispose()
            val future = bridge.listSessions()

            assertTrue("Future should be immediately done", future.isDone)
            try {
                future.get(1, TimeUnit.SECONDS)
                throw AssertionError("Should have thrown")
            } catch (e: java.util.concurrent.ExecutionException) {
                assertTrue(e.cause is BridgeDisposedException)
            }
        } finally {
            runCatching { agentWrite.close() }
        }
    }

    @Test
    fun `notifyAgent after dispose is silently ignored`() {
        val (bridge, agentWrite, _) = buildBridge()
        try {
            bridge.dispose()
            // Must not throw
            bridge.notifyAgent("""{"jsonrpc":"2.0","method":"cancel","params":{}}""" + "\n")
        } finally {
            runCatching { agentWrite.close() }
        }
    }

    // ── Fire-and-forget (notification) ────────────────────────────────────

    @Test
    fun `cancelPrompt sends a request with correct method`() {
        val (bridge, agentWrite, agentRead) = buildBridge()

        try {
            val params = PromptCancelParams(sessionId = "s1")
            val future = bridge.cancelPrompt(params)
            Thread.sleep(50)

            val line = agentRead.bufferedReader().readLine()
            assertNotNull("Bridge should have written the request", line)

            val decoded = JsonRpcCodec.decodeLine(line!!)
            assertTrue("cancelPrompt must send an RpcRequest", decoded is RpcRequest)
            assertEquals(RpcMethod.PROMPT_CANCEL, (decoded as RpcRequest).method)

            // Reply so future completes
            val resp = """{"jsonrpc":"2.0","id":${decoded.id},"result":null}""" + "\n"
            agentWrite.write(resp.toByteArray(Charsets.UTF_8))
            agentWrite.flush()

            val response = future.get(2, TimeUnit.SECONDS)
            assertNotNull(response)
        } finally {
            bridge.dispose()
            runCatching { agentWrite.close() }
            runCatching { agentRead.close() }
        }
    }

    @Test
    fun `resolveToolApproval sends a request with correct method`() {
        val (bridge, agentWrite, agentRead) = buildBridge()

        try {
            val params = ToolApprovalParams(requestId = "tc-1", approved = true)
            val future = bridge.resolveToolApproval(params)
            Thread.sleep(50)

            val line = agentRead.bufferedReader().readLine()
            assertNotNull(line)
            val decoded = JsonRpcCodec.decodeLine(line!!)
            assertTrue("Should be an RpcRequest", decoded is RpcRequest)
            assertEquals(RpcMethod.APPROVE_TOOL_USE, (decoded as RpcRequest).method)

            // Reply so future completes
            val resp = """{"jsonrpc":"2.0","id":${decoded.id},"result":null}""" + "\n"
            agentWrite.write(resp.toByteArray(Charsets.UTF_8))
            agentWrite.flush()

            val response = future.get(2, TimeUnit.SECONDS)
            assertNotNull(response)
        } finally {
            bridge.dispose()
            runCatching { agentWrite.close() }
            runCatching { agentRead.close() }
        }
    }

    // ── initialize / sendPrompt convenience methods ───────────────────────

    @Test
    fun `initialize sends a request and registers a pending future`() {
        val (bridge, agentWrite, agentRead) = buildBridge()

        try {
            val future = bridge.initialize(InitializeParams(workspacePath = "/proj", extensionVersion = "1.0"))
            Thread.sleep(50)

            val requestLine = agentRead.bufferedReader().readLine()
            assertNotNull(requestLine)

            val req = JsonRpcCodec.decodeLine(requestLine!!) as? RpcRequest
            assertNotNull(req)
            assertEquals(RpcMethod.INITIALIZE, req!!.method)

            val resp = """{"jsonrpc":"2.0","id":${req.id},"result":{"agent_version":"0.1.0","capabilities":{}}}""" + "\n"
            agentWrite.write(resp.toByteArray(Charsets.UTF_8))
            agentWrite.flush()

            val response = future.get(2, TimeUnit.SECONDS)
            assertNotNull(response)
            assertEquals(req.id, response.id)
        } finally {
            bridge.dispose()
            runCatching { agentWrite.close() }
            runCatching { agentRead.close() }
        }
    }
}
