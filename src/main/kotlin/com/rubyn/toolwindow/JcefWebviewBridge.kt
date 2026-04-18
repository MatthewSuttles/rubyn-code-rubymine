package com.rubyn.toolwindow

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.logger
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.util.concurrent.CopyOnWriteArrayList

private val LOG = logger<JcefWebviewBridge>()

/**
 * Two-way communication bridge between the Kotlin side and the JCEF webview.
 *
 * ## JS → Kotlin
 * The webview calls `window.rubynJcef.postMessage(jsonString)` to send a message.
 * This is wired via a [JBCefJSQuery] injected as `window.rubynJcef` on every page load.
 *
 * ## Kotlin → JS
 * Callers invoke [send] with a JSON string. If the browser is ready the JS
 * expression `window.rubynReceive(jsonString)` is executed immediately.
 * Messages sent before the browser finishes loading are queued and flushed
 * once [CefLoadHandlerAdapter.onLoadEnd] fires.
 *
 * ## Threading
 * [send] is thread-safe — it may be called from any thread. The CEF load
 * handler fires on a CEF I/O thread; all JS execution is delegated to CEF's
 * own scheduling and is therefore safe to call without additional locking.
 *
 * ## Disposal
 * [dispose] releases the [JBCefJSQuery] and clears all listeners. The
 * [JBCefBrowser] itself is owned by [RubynChatPanel] and disposed there.
 *
 * @param browser The [JBCefBrowser] whose JS context this bridge targets.
 */
class JcefWebviewBridge(private val browser: JBCefBrowser) : Disposable {

    // ── Message queue (pre-ready messages) ────────────────────────────────

    private val pendingMessages = CopyOnWriteArrayList<String>()

    @Volatile
    private var browserReady = false

    @Volatile
    private var disposed = false

    // ── Inbound listeners (JS → Kotlin) ───────────────────────────────────

    private val inboundListeners = CopyOnWriteArrayList<(String) -> Unit>()

    // ── JBCefJSQuery (JS → Kotlin channel) ───────────────────────────────

    private val jsQuery: JBCefJSQuery = JBCefJSQuery.create(browser).also { query ->
        query.addHandler { json ->
            if (json != null) {
                LOG.debug("JcefWebviewBridge ← JS: $json")
                inboundListeners.forEach { it(json) }
            }
            null // no JS return value needed
        }
    }

    // ── CEF load handler ──────────────────────────────────────────────────

    private val loadHandler = object : CefLoadHandlerAdapter() {
        override fun onLoadEnd(browser: CefBrowser, frame: CefFrame?, httpStatusCode: Int) {
            if (!frame!!.isMain) return

            // Inject window.rubynJcef so the webview can call postMessage.
            injectHostObject()

            browserReady = true
            flushQueue()
        }
    }

    init {
        browser.jbCefClient.addLoadHandler(loadHandler, browser.cefBrowser)
    }

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Registers a listener that receives JSON strings sent by the webview via
     * `window.rubynJcef.postMessage(json)`.
     *
     * Thread-safe. Listeners are called on the CEF I/O thread.
     *
     * @return An unsubscribe function. Call it to remove this listener.
     */
    fun onMessage(listener: (String) -> Unit): () -> Unit {
        inboundListeners.add(listener)
        return { inboundListeners.remove(listener) }
    }

    /**
     * Sends a JSON string to the webview by calling `window.rubynReceive(json)`.
     *
     * If the browser is not yet ready the message is queued and delivered once
     * [onLoadEnd] fires. Thread-safe.
     */
    fun send(json: String) {
        if (disposed) {
            LOG.debug("JcefWebviewBridge.send() called after dispose — dropping")
            return
        }

        if (!browserReady) {
            LOG.debug("JcefWebviewBridge: browser not ready — queuing message")
            pendingMessages.add(json)
            return
        }

        executeReceive(json)
    }

    // ── Disposal ──────────────────────────────────────────────────────────

    override fun dispose() {
        if (disposed) return
        disposed = true

        browser.jbCefClient.removeLoadHandler(loadHandler, browser.cefBrowser)
        inboundListeners.clear()
        pendingMessages.clear()
        jsQuery.dispose()

        LOG.debug("JcefWebviewBridge disposed")
    }

    // ── Internal helpers ──────────────────────────────────────────────────

    /**
     * Injects `window.rubynJcef = { postMessage(json) { ... } }` into the
     * browser context so the webview bundle can call it.
     *
     * The [JBCefJSQuery.inject] call produces the JS fragment that routes the
     * call back through the native query channel.
     */
    private fun injectHostObject() {
        val injectionJs = """
            (function() {
                window.rubynJcef = {
                    postMessage: function(json) {
                        ${jsQuery.inject("json")}
                    }
                };
            })();
        """.trimIndent()

        browser.cefBrowser.executeJavaScript(injectionJs, browser.cefBrowser.url, 0)
        LOG.debug("JcefWebviewBridge: window.rubynJcef injected")
    }

    /**
     * Calls `window.rubynReceive(json)` in the browser if the function exists.
     *
     * The guard prevents crashes when the webview bundle hasn't wired the
     * receiver yet (e.g. if a message races the page load).
     */
    private fun executeReceive(json: String) {
        val escaped = json.replace("\\", "\\\\").replace("'", "\\'")
        val js = "if (typeof window.rubynReceive === 'function') { window.rubynReceive('$escaped'); }"
        browser.cefBrowser.executeJavaScript(js, browser.cefBrowser.url, 0)
        LOG.debug("JcefWebviewBridge → JS: $json")
    }

    /**
     * Flushes all messages queued before the browser was ready, in order.
     */
    private fun flushQueue() {
        val snapshot = pendingMessages.toList()
        pendingMessages.clear()
        if (snapshot.isNotEmpty()) {
            LOG.debug("JcefWebviewBridge: flushing ${snapshot.size} queued message(s)")
        }
        snapshot.forEach { executeReceive(it) }
    }
}
