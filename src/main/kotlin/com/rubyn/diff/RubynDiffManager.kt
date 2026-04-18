package com.rubyn.diff

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.rubyn.RubynBundle
import com.rubyn.services.RubynProjectService
import com.rubyn.settings.RubynSettingsService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.nio.charset.StandardCharsets

private val LOG = logger<RubynDiffManager>()

/** How long (ms) the BYPASS flash highlight stays visible before auto-accept. */
private const val BYPASS_FLASH_MS = 300L

/**
 * Project-level service that presents proposed file edits to the user.
 *
 * Registered in plugin.xml as a project service. Obtain via:
 *   project.getService(RubynDiffManager::class.java)
 *
 * ## Permission modes
 * The service reads [RubynSettingsState.permissionMode] for each edit:
 *
 * | Mode                | Behaviour                                              |
 * |---------------------|--------------------------------------------------------|
 * | `default`           | Opens a [SimpleDiffRequest] diff viewer. User manually |
 * |                     | clicks Accept or Reject in the diff toolbar.           |
 * | `acceptEdits`       | Silently applies the edit without opening a viewer.    |
 * | `bypassPermissions` | Applies the edit after a 300 ms "flash" highlight to   |
 * |                     | give the user a moment to notice the change.           |
 *
 * ## Multiple edits
 * Each call to [presentEdit] opens a separate diff tab. IntelliJ's built-in
 * diff framework stacks them as separate tool window tabs.
 *
 * ## Accept / Reject wiring
 * - Accept: applies the change via [WriteCommandAction] (undoable via Ctrl+Z).
 *   For [ProposedEdit.Create] it writes the new file. For [ProposedEdit.Delete]
 *   it deletes the file. For [ProposedEdit.Modify] it overwrites the content.
 *   Notifies [RubynProjectService.acceptEdit] so the bridge is informed.
 * - Reject: discards the change and notifies [RubynProjectService.rejectEdit].
 *
 * The diff viewer's toolbar actions ([AcceptEditAction] / [RejectEditAction])
 * call [acceptEdit] / [rejectEdit] on this service.
 */
@Service(Service.Level.PROJECT)
class RubynDiffManager(private val project: Project) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Presents [edit] to the user according to the current permission mode.
     *
     * Safe to call from any thread — all UI work is dispatched to the EDT.
     */
    fun presentEdit(edit: ProposedEdit) {
        val mode = RubynSettingsService.getInstance().settings().permissionMode
        LOG.info("RubynDiffManager: presentEdit editId=${edit.editId} mode=$mode path=${edit.filePath}")

        when (mode) {
            "bypassPermissions" -> handleBypass(edit)
            "acceptEdits"       -> handleAutoAccept(edit)
            else                -> handleManual(edit)   // "default"
        }
    }

    /**
     * Writes the edit to disk and notifies the bridge of acceptance.
     *
     * Called from [AcceptEditAction] when the user clicks Accept in the diff toolbar,
     * or automatically by [handleBypass] / [handleAutoAccept].
     *
     * Runs on the EDT inside a [WriteCommandAction] so the change is undoable.
     */
    fun acceptEdit(edit: ProposedEdit) {
        LOG.info("RubynDiffManager: acceptEdit editId=${edit.editId}")
        ApplicationManager.getApplication().invokeLater {
            WriteCommandAction.runWriteCommandAction(
                project,
                RubynBundle.message("diff.command.accept"),
                null,
                {
                    applyToDisk(edit)
                }
            )
            project.getService(RubynProjectService::class.java)?.acceptEdit(edit.editId)
        }
    }

    /**
     * Discards the edit and notifies the bridge of rejection.
     *
     * Called from [RejectEditAction] when the user clicks Reject in the diff toolbar.
     * No disk changes are made.
     */
    fun rejectEdit(edit: ProposedEdit) {
        LOG.info("RubynDiffManager: rejectEdit editId=${edit.editId}")
        project.getService(RubynProjectService::class.java)?.rejectEdit(edit.editId)
    }

    // ── Permission-mode handlers ──────────────────────────────────────────

    /**
     * Default mode: opens a [SimpleDiffRequest] in the diff viewer. The user
     * sees the before/after comparison and accepts or rejects manually via the
     * [AcceptEditAction] / [RejectEditAction] toolbar buttons.
     */
    private fun handleManual(edit: ProposedEdit) {
        ApplicationManager.getApplication().invokeLater {
            showDiffViewer(edit)
        }
    }

    /**
     * `acceptEdits` mode: applies the change immediately without any UI.
     */
    private fun handleAutoAccept(edit: ProposedEdit) {
        acceptEdit(edit)
    }

    /**
     * `bypassPermissions` mode: briefly opens the diff viewer (300 ms flash)
     * then auto-accepts. The flash gives the user a moment to notice the change
     * before it is committed.
     */
    private fun handleBypass(edit: ProposedEdit) {
        ApplicationManager.getApplication().invokeLater {
            showDiffViewer(edit)
        }
        scope.launch {
            delay(BYPASS_FLASH_MS)
            acceptEdit(edit)
        }
    }

    // ── Diff viewer ───────────────────────────────────────────────────────

    /**
     * Opens a [SimpleDiffRequest] showing the before/after content of [edit].
     *
     * Each edit gets its own diff tab. The request title includes the file name
     * so tabs are easy to distinguish when multiple edits arrive concurrently.
     *
     * [AcceptEditAction] and [RejectEditAction] are injected into the diff
     * viewer's toolbar via the [ProposedEditDiffContext].
     *
     * Must be called on the EDT.
     */
    private fun showDiffViewer(edit: ProposedEdit) {
        val factory = DiffContentFactory.getInstance()

        val (beforeContent, afterContent) = when (edit) {
            is ProposedEdit.Modify -> {
                val before = factory.create(project, edit.before, guessFileType(edit.filePath))
                val after  = factory.create(project, edit.after,  guessFileType(edit.filePath))
                before to after
            }
            is ProposedEdit.Create -> {
                val before = factory.createEmpty()
                val after  = factory.create(project, edit.after, guessFileType(edit.filePath))
                before to after
            }
            is ProposedEdit.Delete -> {
                val before = factory.create(project, edit.before, guessFileType(edit.filePath))
                val after  = factory.createEmpty()
                before to after
            }
        }

        val title = RubynBundle.message("diff.title", File(edit.filePath).name)
        val request = SimpleDiffRequest(title, beforeContent, afterContent,
            RubynBundle.message("diff.content.before"),
            RubynBundle.message("diff.content.after"))

        // Attach the edit to the request context so toolbar actions can retrieve it.
        request.putUserData(ProposedEditDiffContext.KEY, edit)

        DiffManager.getInstance().showDiff(project, request)
    }

    // ── Disk writes ───────────────────────────────────────────────────────

    /**
     * Applies [edit] to the local file system.
     *
     * Must be called inside a [WriteCommandAction] to ensure the change is
     * tracked by IntelliJ's undo manager.
     */
    private fun applyToDisk(edit: ProposedEdit) {
        try {
            when (edit) {
                is ProposedEdit.Modify -> writeFileContent(edit.filePath, edit.after)
                is ProposedEdit.Create -> writeFileContent(edit.filePath, edit.after)
                is ProposedEdit.Delete -> deleteFile(edit.filePath)
            }
        } catch (e: Exception) {
            LOG.warn("RubynDiffManager: applyToDisk failed for ${edit.filePath}: ${e.message}", e)
        }
    }

    /**
     * Writes [content] to [absolutePath], creating parent directories as needed.
     *
     * Refreshes the VFS after the write so IntelliJ picks up the change without
     * requiring a manual "Reload from Disk".
     */
    private fun writeFileContent(absolutePath: String, content: String) {
        val file = File(absolutePath)
        file.parentFile?.mkdirs()

        // Prefer writing through the VFS when the file already exists so that
        // IntelliJ's document model stays in sync with the disk version.
        val vf: VirtualFile? = LocalFileSystem.getInstance().findFileByPath(absolutePath)
        if (vf != null) {
            VfsUtil.saveText(vf, content)
        } else {
            // New file — write to disk then refresh into the VFS.
            file.writeText(content, StandardCharsets.UTF_8)
            LocalFileSystem.getInstance().refreshAndFindFileByPath(absolutePath)
        }
        LOG.info("RubynDiffManager: wrote ${content.length} chars to $absolutePath")
    }

    /**
     * Deletes the file at [absolutePath] via the VFS.
     *
     * Logs a warning if the file is not found — the agent may have already
     * deleted it or the path may be stale.
     */
    private fun deleteFile(absolutePath: String) {
        val vf = LocalFileSystem.getInstance().findFileByPath(absolutePath)
        if (vf == null) {
            LOG.warn("RubynDiffManager: deleteFile — file not found: $absolutePath")
            return
        }
        vf.delete(this)
        LOG.info("RubynDiffManager: deleted $absolutePath")
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /**
     * Guesses the [com.intellij.openapi.fileTypes.FileType] from the file
     * extension so that the diff viewer syntax-highlights the content correctly.
     */
    private fun guessFileType(filePath: String): com.intellij.openapi.fileTypes.FileType {
        val name = File(filePath).name
        return com.intellij.openapi.fileTypes.FileTypeManager.getInstance()
            .getFileTypeByFileName(name)
    }
}
