package com.rubyn.toolwindow

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.rubyn.services.AgentStatus
import com.rubyn.services.RubynProjectService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import java.awt.BorderLayout
import java.awt.Font
import java.beans.PropertyChangeListener
import java.io.File
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.UIManager

private val LOG = logger<RubynChatPanel>()

/**
 * Chat panel displayed in the "Chat" tab of the Rubyn tool window.
 *
 * ## Rendering strategy
 * When JCEF is available ([JBCefApp.isSupported]) this panel embeds a
 * [JBCefBrowser] pointing at the pre-built webview bundle bundled as a
 * plugin resource. A [JcefWebviewBridge] wires two-way JSON messaging
 * between the webview and the Kotlin side.
 *
 * When JCEF is unavailable (e.g. IDE launched with `-Djcef.sandbox.disable`
 * or on unsupported platforms) a plain Swing fallback message is shown instead
 * so the plugin still loads cleanly.
 *
 * ## Theme sync
 * A [UIManager] property-change listener detects Look-and-Feel changes and
 * posts `{ type: "themeChange", theme: "dark"|"light" }` to the webview
 * within ≤ 200 ms (the next Swing event tick after the LAF change fires).
 *
 * ## Service wiring
 * [RubynProjectService] StateFlows are forwarded to the webview as JSON
 * messages. Outbound messages from the webview are dispatched back to the
 * appropriate service methods.
 *
 * @param project    The current IDE project.
 * @param disposable Parent [Disposable] (the tool window content). All children
 *                   are registered here so they are torn down with the window.
 */
class RubynChatPanel(
    private val project: Project,
    private val disposable: Disposable,
) : JBPanel<RubynChatPanel>(BorderLayout()) {

    // ── Coroutine scope ───────────────────────────────────────────────────

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ── JCEF components (null when JCEF unavailable) ──────────────────────

    private val browser: JBCefBrowser?
    private val jcefBridge: JcefWebviewBridge?

    // ── Project service ───────────────────────────────────────────────────

    private val service: RubynProjectService? =
        project.getService(RubynProjectService::class.java)

    // ── Pending-approval tracking (diff-based to avoid duplicate sends) ───

    // Tracks the set of toolCallIds that have already been sent to the webview
    // as "pending" tool-call messages. Used to diff successive emissions from
    // pendingApprovals so we only push new additions and notify on removals.
    private val sentApprovalIds = mutableSetOf<String>()

    // ── Init ──────────────────────────────────────────────────────────────

    init {
        border = JBUI.Borders.empty()

        if (JBCefApp.isSupported()) {
            val b = JBCefBrowser()
            Disposer.register(disposable, b)
            browser = b
            jcefBridge = JcefWebviewBridge(b)
            Disposer.register(disposable, jcefBridge)

            add(b.component, BorderLayout.CENTER)

            loadWebview()
            wireInbound()
            subscribeServiceFlows()
            installThemeListener()
        } else {
            browser = null
            jcefBridge = null
            add(buildFallback(), BorderLayout.CENTER)
            LOG.warn("RubynChatPanel: JCEF unavailable — showing fallback message")
        }

        Disposer.register(disposable) { scope.cancel() }
    }

    // ── Webview load ──────────────────────────────────────────────────────

    /**
     * Loads the bundled webview HTML into the JCEF browser.
     *
     * The resource lives at `META-INF/rubyn-webview/index.html` inside the
     * plugin jar. JCEF (Chromium) cannot load `jar:` URLs, so we extract the
     * webview bundle to a temp directory and load via `file://` instead.
     *
     * When the resource is absent (early development builds) a minimal inline
     * stub is loaded instead.
     */
    private fun loadWebview() {
        val resourceBase = "META-INF/rubyn-webview"
        val indexResource: URL? = javaClass.classLoader.getResource("$resourceBase/index.html")

        if (indexResource == null) {
            browser!!.loadHTML(STUB_HTML)
            LOG.warn("RubynChatPanel: webview resource not found — loaded stub HTML")
            return
        }

        // JCEF (Chromium) cannot handle jar: URLs. Extract the webview bundle
        // to a temp directory and load via file:// protocol.
        try {
            val tempDir = Files.createTempDirectory("rubyn-webview-").toFile()
            tempDir.deleteOnExit()

            // Extract all known webview files from the jar into the temp dir.
            val webviewFiles = listOf("index.html", "rubyn-webview.js", "rubyn-webview.css")
            for (filename in webviewFiles) {
                val stream = javaClass.classLoader.getResourceAsStream("$resourceBase/$filename")
                if (stream != null) {
                    val target = File(tempDir, filename)
                    stream.use { input ->
                        Files.copy(input, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }
                    target.deleteOnExit()
                }
            }

            val indexFile = File(tempDir, "index.html")
            if (indexFile.exists()) {
                val fileUrl = indexFile.toURI().toURL().toExternalForm()
                browser!!.loadURL(fileUrl)
                LOG.info("RubynChatPanel: loaded webview from $fileUrl")
            } else {
                browser!!.loadHTML(STUB_HTML)
                LOG.warn("RubynChatPanel: failed to extract webview — loaded stub HTML")
            }
        } catch (e: Exception) {
            LOG.error("RubynChatPanel: webview extraction failed", e)
            browser!!.loadHTML(STUB_HTML)
        }
    }

    // ── Inbound messages (JS → Kotlin) ────────────────────────────────────

    /**
     * Registers the bridge listener that routes webview messages to service calls.
     */
    private fun wireInbound() {
        jcefBridge!!.onMessage { json -> handleWebviewMessage(json) }
    }

    private fun handleWebviewMessage(json: String) {
        val svc = service ?: return

        val map = runCatching {
            Json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(json)
        }.getOrElse {
            LOG.warn("RubynChatPanel: malformed webview message: $json")
            return
        }

        val type = map["type"]?.let {
            runCatching { Json.decodeFromJsonElement<String>(it) }.getOrNull()
        } ?: return

        when (type) {
            "sendMessage" -> {
                val text = map["text"]?.let {
                    runCatching { Json.decodeFromJsonElement<String>(it) }.getOrNull()
                } ?: return
                svc.submitPrompt(text)
            }

            "approveToolCall" -> {
                val id = map["toolCallId"]?.let {
                    runCatching { Json.decodeFromJsonElement<String>(it) }.getOrNull()
                } ?: return
                svc.approveToolUse(id)
            }

            "denyToolCall" -> {
                val id = map["toolCallId"]?.let {
                    runCatching { Json.decodeFromJsonElement<String>(it) }.getOrNull()
                } ?: return
                svc.denyToolUse(id)
            }

            "getSessions" -> sendSessionList()

            "newSession" -> {
                LOG.debug("RubynChatPanel: newSession requested — handled by project service on next prompt")
            }

            else -> {
                LOG.debug("RubynChatPanel: unknown webview message type '$type'")
            }
        }
    }

    // ── Service → webview (Kotlin → JS) ──────────────────────────────────

    /**
     * Observes [RubynProjectService] StateFlows and forwards updates to the
     * webview as JSON messages.
     *
     * All dynamic string values embedded in hand-rolled JSON are passed through
     * [escapeJson] to prevent broken JSON when values contain quotes, newlines,
     * or other special characters.
     */
    private fun subscribeServiceFlows() {
        val svc = service ?: return

        svc.agentStatus
            .onEach { status -> sendToWebview(agentStatusJson(status)) }
            .launchIn(scope)

        svc.sessionCost
            .onEach { cost ->
                // inputTokens, outputTokens, and costUsd are numeric — no escaping needed.
                sendToWebview(
                    """{"type":"sessionCost","inputTokens":${cost.inputTokens},""" +
                        """"outputTokens":${cost.outputTokens},"costUsd":${cost.costUsd}}"""
                )
            }
            .launchIn(scope)

        svc.pendingApprovals
            .onEach { approvals ->
                // Diff against the previously-sent set so we only push new additions
                // and removals rather than re-broadcasting the entire list every time.
                val currentIds = approvals.map { it.toolCallId }.toSet()

                val added   = approvals.filter { it.toolCallId !in sentApprovalIds }
                val removed = sentApprovalIds - currentIds

                // Notify the webview about newly-added approvals.
                added.forEach { approval ->
                    sendToWebview(
                        """{"type":"toolCall","message":{"id":"${escapeJson(approval.toolCallId)}",""" +
                            """"role":"tool","content":"","timestamp":"${java.time.Instant.now()}",""" +
                            """"toolCall":{"id":"${escapeJson(approval.toolCallId)}",""" +
                            """"name":"${escapeJson(approval.toolName)}",""" +
                            """"args":{},"status":"pending"}}}"""
                    )
                }

                // Notify the webview about resolved (approved/denied) approvals so it
                // can remove their UI elements.
                removed.forEach { id ->
                    sendToWebview("""{"type":"toolCallResolved","toolCallId":"${escapeJson(id)}"}""")
                }

                sentApprovalIds.clear()
                sentApprovalIds.addAll(currentIds)
            }
            .launchIn(scope)

        svc.streamText
            .onEach { chunk ->
                sendToWebview(
                    """{"type":"streamChunk","sessionId":"${escapeJson(chunk.sessionId)}",""" +
                        """"messageId":"${escapeJson(chunk.messageId)}",""" +
                        """"delta":"${escapeJson(chunk.delta)}","done":false}"""
                )
            }
            .launchIn(scope)

        svc.streamDone
            .onEach { done ->
                sendToWebview(
                    """{"type":"streamChunk","sessionId":"${escapeJson(done.sessionId)}",""" +
                        """"messageId":"${escapeJson(done.messageId)}","delta":"","done":true}"""
                )
            }
            .launchIn(scope)
    }

    // ── Theme listener ────────────────────────────────────────────────────

    /**
     * Installs a [UIManager] property-change listener for Look-and-Feel
     * changes and sends the current theme immediately on install.
     *
     * Theme updates reach the webview within ≤ 200 ms (next Swing event tick).
     */
    private fun installThemeListener() {
        val lafListener = PropertyChangeListener { event ->
            if (event.propertyName == "lookAndFeel") {
                SwingUtilities.invokeLater { sendTheme() }
            }
        }
        UIManager.addPropertyChangeListener(lafListener)
        Disposer.register(disposable) {
            UIManager.removePropertyChangeListener(lafListener)
        }

        // Send initial theme right away.
        sendTheme()
    }

    private fun sendTheme() {
        val theme = if (!JBColor.isBright()) "dark" else "light"
        sendToWebview("""{"type":"themeChange","theme":"$theme"}""")
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun sendToWebview(json: String) {
        jcefBridge?.send(json)
    }

    private fun sendSessionList() {
        val sid = service?.sessionId?.value ?: return
        sendToWebview(
            """{"type":"sessionList","sessions":[{"id":"${escapeJson(sid)}","label":"Session",""" +
                """"createdAt":"${java.time.Instant.now()}","active":true}]}"""
        )
    }

    private fun agentStatusJson(status: AgentStatus): String {
        val s = when (status) {
            AgentStatus.IDLE             -> "idle"
            AgentStatus.THINKING         -> "thinking"
            AgentStatus.STREAMING        -> "streaming"
            AgentStatus.WAITING_APPROVAL -> "waiting_approval"
            AgentStatus.ERROR            -> "error"
        }
        return """{"type":"agentStatus","status":"$s"}"""
    }

    /** Escapes a string for embedding inside a JSON string literal. */
    private fun escapeJson(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")

    // ── Fallback UI ───────────────────────────────────────────────────────

    private fun buildFallback(): JPanel {
        val panel = JBPanel<Nothing>(BorderLayout())
        panel.background = UIUtil.getPanelBackground()
        panel.border = JBUI.Borders.empty(24)

        val label = JBLabel(
            "<html><b>Rubyn chat requires JCEF</b><br><br>" +
                "This IDE build does not support the JCEF browser component.<br>" +
                "Please use a JetBrains IDE distribution that includes JCEF<br>" +
                "(the default JBR runtime).</html>"
        )
        label.font = label.font.deriveFont(Font.PLAIN, JBUI.scaleFontSize(13f).toFloat())
        label.foreground = UIUtil.getLabelForeground()
        panel.add(label, BorderLayout.NORTH)

        return panel
    }

    companion object {
        /** Inline stub shown when the webview bundle resource is missing. */
        private val STUB_HTML = """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="UTF-8"/>
              <style>
                body { margin: 0; padding: 24px; font-family: system-ui, sans-serif;
                       background: #1e1e1e; color: #d4d4d4; }
                p    { margin: 0 0 8px; }
              </style>
            </head>
            <body>
              <p><strong>Rubyn</strong></p>
              <p>Webview bundle not found — run the webview build task first.</p>
            </body>
            </html>
        """.trimIndent()
    }
}
