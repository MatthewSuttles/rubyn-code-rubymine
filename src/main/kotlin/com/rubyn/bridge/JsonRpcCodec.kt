package com.rubyn.bridge

import com.intellij.openapi.diagnostic.logger
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import java.util.concurrent.atomic.AtomicLong

private val LOG = logger<JsonRpcCodec>()

/**
 * Codec for JSON-RPC 2.0 messages over a newline-delimited stream.
 *
 * ## Encoding
 * Each message is serialized to a single JSON line (no embedded newlines).
 * [encodeRequest] and [encodeNotificationLine] return the raw line string
 * including the trailing newline; callers write it directly to stdin.
 *
 * ## Decoding
 * [decodeLine] parses a single line and returns the appropriate [RpcMessage]
 * subtype, or null if the line is malformed. Malformed input is logged at
 * DEBUG level and never thrown — the bridge stays alive.
 *
 * ## Thread safety
 * [nextId] uses an [AtomicLong] — safe to call from any thread concurrently.
 * The [json] instance is stateless and thread-safe by design.
 */
object JsonRpcCodec {

    // ── JSON configuration ────────────────────────────────────────────────

    /**
     * Lenient decoder: ignores unknown keys so rubyn-code can add fields
     * in future versions without breaking this plugin.
     *
     * Encoder uses default settings (compact, no pretty-print).
     */
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
        explicitNulls = false
    }

    // ── Request ID generator ──────────────────────────────────────────────

    private val idCounter = AtomicLong(0)

    /**
     * Returns a new, unique request ID. Thread-safe; monotonically increasing.
     */
    fun nextId(): Long = idCounter.incrementAndGet()

    // ── Encoding ──────────────────────────────────────────────────────────

    /**
     * Encodes a typed params object into a JSON-RPC 2.0 request line.
     *
     * The returned string ends with `\n` so it can be written directly to
     * the process stdin without additional framing.
     */
    inline fun <reified P : Any> encodeRequest(
        id: Long,
        method: String,
        params: P,
    ): String {
        val paramsElement: JsonElement = json.encodeToJsonElement(params)
        val request = RpcRequest(id = id, method = method, params = paramsElement)
        return json.encodeToString(request) + "\n"
    }

    /**
     * Encodes a request with no parameters.
     */
    fun encodeRequest(id: Long, method: String): String {
        val request = RpcRequest(id = id, method = method)
        return json.encodeToString(request) + "\n"
    }

    /**
     * Encodes a typed params object into a JSON-RPC 2.0 notification line
     * (no id — no response expected).
     */
    inline fun <reified P : Any> encodeNotificationLine(
        method: String,
        params: P,
    ): String {
        val paramsElement: JsonElement = json.encodeToJsonElement(params)
        val notification = RpcNotification(method = method, params = paramsElement)
        return json.encodeToString(notification) + "\n"
    }

    // ── Decoding ──────────────────────────────────────────────────────────

    /**
     * Parses [line] into an [RpcMessage].
     *
     * Returns null on any parse failure — the bridge should log the raw line
     * and continue reading. Does not throw.
     */
    fun decodeLine(line: String): RpcMessage? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null

        return try {
            json.decodeFromString(RpcMessageSerializer, trimmed)
        } catch (e: Exception) {
            LOG.debug("JsonRpcCodec: failed to decode line: $trimmed — ${e.message}")
            null
        }
    }

    /**
     * Decodes the [params] field of a notification into a typed object.
     *
     * Returns null when [params] is null or the decoding fails.
     * Called by the bridge's notification dispatcher.
     */
    fun <T> decodeParams(params: JsonElement?, deserializer: kotlinx.serialization.DeserializationStrategy<T>): T? {
        if (params == null) return null
        return try {
            json.decodeFromJsonElement(deserializer, params)
        } catch (e: Exception) {
            LOG.debug("JsonRpcCodec: failed to decode params: ${e.message}")
            null
        }
    }
}
