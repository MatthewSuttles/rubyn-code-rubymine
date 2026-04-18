package com.rubyn.toolwindow

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBPanel
import com.intellij.ui.treeStructure.SimpleTree
import com.intellij.util.ui.JBUI
import com.rubyn.RubynBundle
import com.rubyn.services.RubynProjectService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

private val LOG = logger<RubynSessionsPanel>()
private val DATE_FMT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm").withZone(ZoneId.systemDefault())

/**
 * Sessions tab panel — shows a tree of past sessions with Resume, Export, and
 * Delete actions.
 *
 * ## Structure
 * ```
 * ┌─────────────────────────────────┐
 * │ Sessions (tree, scrollable)     │
 * │   ▼ Active                      │
 * │       Session 2025-04-17 10:30  │
 * │   ▼ Previous                    │
 * │       Session 2025-04-16 08:00  │
 * ├─────────────────────────────────┤
 * │ [Resume]  [Export…]  [Delete]   │
 * └─────────────────────────────────┘
 * ```
 *
 * ## Data source
 * Sessions are loaded via [RubynProjectService.listSessions] on tab open and
 * after any Resume/Delete action. Errors are logged; the tree is left empty.
 *
 * ## Threading
 * All bridge calls run on [Dispatchers.IO] via the coroutine scope.
 * Tree mutations always happen on the EDT via [Dispatchers.Main].
 *
 * @param project    The current IDE project.
 * @param disposable Parent disposable — cancels the coroutine scope on disposal.
 */
class RubynSessionsPanel(
    private val project: Project,
    private val disposable: Disposable,
) : JBPanel<RubynSessionsPanel>(BorderLayout()) {

    // ── Coroutine scope ───────────────────────────────────────────────────

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ── Service ───────────────────────────────────────────────────────────

    private val service: RubynProjectService? =
        project.getService(RubynProjectService::class.java)

    // ── Tree model ────────────────────────────────────────────────────────

    private val root = DefaultMutableTreeNode("Sessions")
    private val treeModel = DefaultTreeModel(root)
    private val tree = SimpleTree(treeModel).apply {
        selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        isRootVisible = false
        showsRootHandles = true
        border = JBUI.Borders.empty(4)
    }

    // ── Buttons ───────────────────────────────────────────────────────────

    private val resumeBtn = JButton(RubynBundle.message("sessions.action.resume")).apply {
        isEnabled = false
        addActionListener { resumeSelected() }
    }

    private val exportBtn = JButton(RubynBundle.message("sessions.action.export")).apply {
        isEnabled = false
        addActionListener { exportSelected() }
    }

    private val deleteBtn = JButton(RubynBundle.message("sessions.action.delete")).apply {
        isEnabled = false
        addActionListener { deleteSelected() }
    }

    // ── Init ──────────────────────────────────────────────────────────────

    init {
        border = JBUI.Borders.empty()

        // Scroll pane around the tree.
        val scroll: JScrollPane = ScrollPaneFactory.createScrollPane(tree, true)
        add(scroll, BorderLayout.CENTER)

        // Button bar at the bottom.
        val buttonBar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 4)).apply {
            border = BorderFactory.createMatteBorder(1, 0, 0, 0, JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground())
            add(resumeBtn)
            add(exportBtn)
            add(deleteBtn)
        }
        add(buttonBar, BorderLayout.SOUTH)

        // Enable/disable buttons on selection change.
        tree.addTreeSelectionListener { updateButtonState() }

        // Load sessions on first display.
        loadSessions()

        Disposer.register(disposable) { scope.cancel() }
    }

    // ── Session loading ───────────────────────────────────────────────────

    /**
     * Fetches the session list from rubyn-code and populates the tree.
     */
    fun loadSessions() {
        val svc = service ?: run {
            showEmptyTree("Service not available")
            return
        }

        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    svc.listSessions().get()
                }
            }

            val sessions = result.getOrElse { ex ->
                LOG.warn("RubynSessionsPanel: failed to load sessions: ${ex.message}")
                null
            }

            if (sessions == null) {
                showEmptyTree("Could not load sessions")
                return@launch
            }

            // Parse the result — sessions come back as SessionListResult.
            val sessionList = runCatching {
                val element = sessions.result ?: return@runCatching emptyList()
                kotlinx.serialization.json.Json.decodeFromJsonElement(
                    com.rubyn.bridge.SessionListResult.serializer(),
                    element
                ).sessions
            }.getOrElse {
                LOG.warn("RubynSessionsPanel: failed to parse session list: ${it.message}")
                emptyList()
            }

            populateTree(sessionList)
        }
    }

    // ── Tree population ───────────────────────────────────────────────────

    private fun populateTree(sessions: List<com.rubyn.bridge.SessionInfo>) {
        root.removeAllChildren()

        if (sessions.isEmpty()) {
            val empty = DefaultMutableTreeNode(SessionNode.Empty)
            root.add(empty)
            treeModel.reload()
            updateButtonState()
            return
        }

        val (active, previous) = sessions.partition { it.active }

        if (active.isNotEmpty()) {
            val activeGroup = DefaultMutableTreeNode("Active")
            active.forEach { info ->
                activeGroup.add(DefaultMutableTreeNode(SessionNode.Session(info)))
            }
            root.add(activeGroup)
        }

        if (previous.isNotEmpty()) {
            val prevGroup = DefaultMutableTreeNode("Previous")
            previous.forEach { info ->
                prevGroup.add(DefaultMutableTreeNode(SessionNode.Session(info)))
            }
            root.add(prevGroup)
        }

        treeModel.reload()

        // Expand all group nodes.
        for (i in 0 until tree.rowCount) {
            tree.expandRow(i)
        }

        updateButtonState()
    }

    private fun showEmptyTree(reason: String) {
        root.removeAllChildren()
        root.add(DefaultMutableTreeNode(SessionNode.Empty))
        treeModel.reload()
        LOG.debug("RubynSessionsPanel: empty tree — $reason")
    }

    // ── Button state ──────────────────────────────────────────────────────

    private fun updateButtonState() {
        val selected = selectedSessionNode() != null
        resumeBtn.isEnabled = selected
        exportBtn.isEnabled = selected
        deleteBtn.isEnabled = selected
    }

    private fun selectedSessionNode(): SessionNode.Session? {
        val path: TreePath = tree.selectionPath ?: return null
        val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return null
        return node.userObject as? SessionNode.Session
    }

    // ── Actions ───────────────────────────────────────────────────────────

    private fun resumeSelected() {
        val node = selectedSessionNode() ?: return
        val svc = service ?: return

        LOG.info("RubynSessionsPanel: resuming session ${node.info.sessionId}")
        // Resuming a session means switching the active session in the project service.
        // We do this by starting the chosen session ID.
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    svc.resumeSession(node.info.sessionId)
                }
            }.onFailure {
                LOG.warn("RubynSessionsPanel: resume failed: ${it.message}")
            }
        }
    }

    private fun exportSelected() {
        val node = selectedSessionNode() ?: return

        val descriptor = FileSaverDescriptor(
            RubynBundle.message("sessions.export.dialog.title"),
            RubynBundle.message("sessions.export.dialog.description"),
            "json",
        )
        val dialog = FileChooserFactory.getInstance()
            .createSaveFileDialog(descriptor, project)

        val wrapper = dialog.save(null, "session-${node.info.sessionId}.json") ?: return

        scope.launch {
            runCatching {
                val svc = service ?: return@launch
                val content = withContext(Dispatchers.IO) {
                    svc.exportSession(node.info.sessionId)
                }
                withContext(Dispatchers.IO) {
                    wrapper.getVirtualFile(true)?.setBinaryContent(content.toByteArray(Charsets.UTF_8))
                }
                LOG.info("RubynSessionsPanel: exported session ${node.info.sessionId}")
            }.onFailure {
                LOG.warn("RubynSessionsPanel: export failed: ${it.message}")
                withContext(Dispatchers.Main) {
                    Messages.showErrorDialog(
                        project,
                        "Export failed: ${it.message}",
                        "Rubyn Export Error",
                    )
                }
            }
        }
    }

    private fun deleteSelected() {
        val node = selectedSessionNode() ?: return

        val confirm = Messages.showYesNoDialog(
            project,
            RubynBundle.message("sessions.delete.confirm", node.info.label),
            RubynBundle.message("sessions.delete.title"),
            Messages.getQuestionIcon(),
        )
        if (confirm != Messages.YES) return

        val svc = service ?: return

        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    svc.deleteSession(node.info.sessionId)
                }
                loadSessions()
                LOG.info("RubynSessionsPanel: deleted session ${node.info.sessionId}")
            }.onFailure {
                LOG.warn("RubynSessionsPanel: delete failed: ${it.message}")
            }
        }
    }
}

// ── Node types ────────────────────────────────────────────────────────────────

/**
 * Sealed hierarchy for tree node user objects.
 */
sealed class SessionNode {

    /** Placeholder when no sessions exist. */
    object Empty : SessionNode() {
        override fun toString() = "No sessions yet"
    }

    /** A real session entry. */
    data class Session(val info: com.rubyn.bridge.SessionInfo) : SessionNode() {
        override fun toString(): String {
            val ts = runCatching {
                DATE_FMT.format(Instant.parse(info.createdAt))
            }.getOrElse { info.createdAt }
            return if (info.active) "● ${info.label}  ($ts)" else "${info.label}  ($ts)"
        }
    }
}
