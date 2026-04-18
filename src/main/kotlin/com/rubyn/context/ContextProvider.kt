package com.rubyn.context

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

private val LOG = logger<ContextProvider>()

/**
 * Captures editor and project state for use in rubyn-code prompt construction.
 *
 * All reads from the editor model go through [ApplicationManager.getApplication().runReadAction]
 * so this service is safe to call from any thread without EDT violations.
 *
 * Access via:
 *   project.service<ContextProvider>().getActiveContext()
 */
@Service(Service.Level.PROJECT)
class ContextProvider(private val project: Project) {

    /**
     * Returns a snapshot of the current editor state.
     *
     * Runs entirely inside a read action so callers on background threads are safe.
     * Returns null when there is no active editor (e.g., the project just opened
     * with no file tabs).
     */
    fun getActiveContext(): EditorContext? {
        var context: EditorContext? = null

        ApplicationManager.getApplication().runReadAction {
            context = buildContext()
        }

        return context
    }

    // ── private ──────────────────────────────────────────────────────────────

    private fun buildContext(): EditorContext? {
        val fileEditorManager = FileEditorManager.getInstance(project)
        val editor: Editor = fileEditorManager.selectedTextEditor ?: return null
        val activeFile: VirtualFile = fileEditorManager.selectedFiles.firstOrNull() ?: return null

        val selectionModel = editor.selectionModel
        val selection: String? = if (selectionModel.hasSelection()) {
            selectionModel.selectedText
        } else {
            null
        }

        val cursorLine: Int = editor.caretModel.logicalPosition.line + 1 // 1-based

        val openFiles: List<String> = fileEditorManager.openFiles.map { it.path }

        val workspacePath: String = project.basePath ?: ""

        val language: String = activeFile.fileType.name

        return EditorContext(
            activeFile = activeFile.path,
            selection = selection,
            openFiles = openFiles,
            workspacePath = workspacePath,
            language = language,
            cursorLine = cursorLine,
        )
    }
}

/**
 * Immutable snapshot of the editor state at a point in time.
 *
 * @property activeFile      Absolute path of the file currently open in the editor.
 * @property selection       The currently selected text, or null if nothing is selected.
 * @property openFiles       Absolute paths of all currently open file tabs.
 * @property workspacePath   The project's base directory on disk.
 * @property language        IntelliJ file-type name for the active file (e.g. "Ruby", "YAML").
 * @property cursorLine      1-based line number where the primary caret is positioned.
 */
data class EditorContext(
    val activeFile: String,
    val selection: String?,
    val openFiles: List<String>,
    val workspacePath: String,
    val language: String,
    val cursorLine: Int,
)
