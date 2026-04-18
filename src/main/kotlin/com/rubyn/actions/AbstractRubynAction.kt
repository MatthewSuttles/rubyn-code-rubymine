package com.rubyn.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.rubyn.bridge.EditorContextParams
import com.rubyn.context.ContextProvider
import com.rubyn.context.ProjectDetector
import com.rubyn.services.RubynProjectService

private val LOG = logger<AbstractRubynAction>()

/**
 * Base class for all Rubyn editor actions.
 *
 * Provides convenience helpers for:
 *   - Looking up [RubynProjectService] and [ContextProvider] for a project.
 *   - Checking whether the active project is a Ruby project via [ProjectDetector].
 *   - Building [EditorContextParams] from the current editor state.
 *   - Submitting a prompt to the bridge and optionally activating the tool window.
 *
 * Subclasses override [actionPerformed] to provide action-specific logic.
 * The default [update] implementation disables the action for non-Ruby projects;
 * subclasses may further narrow availability (e.g., requiring a selection).
 *
 * Implements [DumbAware] so actions remain available during indexing.
 */
abstract class AbstractRubynAction(text: String) : AnAction(text), DumbAware {

    // ── Service accessors ─────────────────────────────────────────────────

    /**
     * Returns the [RubynProjectService] for [project], or null if unavailable.
     */
    protected fun getProjectService(project: Project): RubynProjectService? =
        project.getService(RubynProjectService::class.java)

    /**
     * Returns the [ContextProvider] for [project], or null if unavailable.
     */
    protected fun getContextProvider(project: Project): ContextProvider? =
        project.getService(ContextProvider::class.java)

    /**
     * Returns true when [project] is detected as a Ruby project.
     */
    protected fun isRubyProject(project: Project): Boolean =
        project.getService(ProjectDetector::class.java)?.isRubyProject() == true

    // ── Prompt helpers ────────────────────────────────────────────────────

    /**
     * Builds [EditorContextParams] from the current editor state in [event],
     * or returns null when no editor is active.
     */
    protected fun buildEditorContext(event: AnActionEvent): EditorContextParams? {
        val editor = event.getData(CommonDataKeys.EDITOR) ?: return null
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE)

        val selection = editor.selectionModel.selectedText
        val cursorLine = editor.caretModel.logicalPosition.line + 1 // 1-based

        return EditorContextParams(
            filePath = file?.path,
            selectedText = selection,
            language = file?.fileType?.name,
            cursorLine = cursorLine,
        )
    }

    /**
     * Submits [prompt] to the bridge and activates the Rubyn tool window.
     *
     * Safe to call from any thread — delegates to [RubynProjectService.submitPrompt]
     * which handles threading internally.
     *
     * @param project The current project.
     * @param prompt  The prompt text to send.
     * @param context Optional editor context to attach to the prompt.
     */
    protected fun launchPrompt(
        project: Project,
        prompt: String,
        context: EditorContextParams? = null,
    ) {
        val service = getProjectService(project) ?: run {
            LOG.warn("launchPrompt: RubynProjectService not available for project '${project.name}'")
            return
        }

        activateToolWindow(project)
        service.submitPrompt(text = prompt, context = context)
    }

    // ── Availability ──────────────────────────────────────────────────────

    /**
     * Disables the action when there is no open project or the project is not a
     * Ruby project.
     *
     * Subclasses should call super and then apply further conditions, e.g.:
     * ```kotlin
     * override fun update(event: AnActionEvent) {
     *     super.update(event)
     *     if (!event.presentation.isEnabledAndVisible) return
     *     val hasSelection = event.getData(CommonDataKeys.EDITOR)
     *         ?.selectionModel?.hasSelection() == true
     *     event.presentation.isEnabledAndVisible = hasSelection
     * }
     * ```
     */
    override fun update(event: AnActionEvent) {
        val project = event.project
        event.presentation.isEnabledAndVisible = project != null && isRubyProject(project)
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private fun activateToolWindow(project: Project) {
        val toolWindowManager = ToolWindowManager.getInstance(project)
        toolWindowManager.getToolWindow("Rubyn")?.activate(null)
    }
}
