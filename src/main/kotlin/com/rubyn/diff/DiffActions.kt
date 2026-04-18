package com.rubyn.diff

import com.intellij.diff.tools.util.DiffDataKeys
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbAware
import com.rubyn.RubynBundle
import com.rubyn.icons.RubynIcons

private val LOG = logger<AcceptEditAction>()

/**
 * Toolbar action shown in the Rubyn diff viewer: accepts the proposed edit.
 *
 * When clicked:
 *  1. Retrieves the [ProposedEdit] stored on the diff request via [ProposedEditDiffContext.KEY].
 *  2. Delegates to [RubynDiffManager.acceptEdit] which runs a [WriteCommandAction]
 *     (undoable) and notifies the bridge.
 *
 * Registered in plugin.xml under the `Diff.ViewerToolbar` group so it appears
 * in the diff window's toolbar alongside IntelliJ's built-in diff actions.
 *
 * Implements [DumbAware] so it remains enabled while indices are rebuilding.
 */
class AcceptEditAction : AnAction(
    RubynBundle.message("diff.action.accept"),
    RubynBundle.message("diff.action.accept.description"),
    RubynIcons.AcceptEdit,
), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val edit = resolveEdit(e)
        e.presentation.isEnabledAndVisible = edit != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val edit = resolveEdit(e) ?: run {
            LOG.warn("AcceptEditAction: no ProposedEdit in diff context")
            return
        }
        val project = e.project ?: run {
            LOG.warn("AcceptEditAction: no project in action event")
            return
        }
        project.getService(RubynDiffManager::class.java)?.acceptEdit(edit)
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun resolveEdit(e: AnActionEvent): ProposedEdit? {
        // The diff viewer exposes its DiffContext via DiffDataKeys.DIFF_CONTEXT.
        val context = e.getData(DiffDataKeys.DIFF_CONTEXT) ?: return null
        return context.getUserData(ProposedEditDiffContext.KEY)
    }
}

/**
 * Toolbar action shown in the Rubyn diff viewer: rejects the proposed edit.
 *
 * When clicked:
 *  1. Retrieves the [ProposedEdit] from [ProposedEditDiffContext.KEY].
 *  2. Delegates to [RubynDiffManager.rejectEdit] which notifies the bridge
 *     and discards the change (no disk modification).
 *
 * Registered in plugin.xml under the `Diff.ViewerToolbar` group.
 * Implements [DumbAware] so it remains enabled while indices rebuild.
 */
class RejectEditAction : AnAction(
    RubynBundle.message("diff.action.reject"),
    RubynBundle.message("diff.action.reject.description"),
    RubynIcons.RejectEdit,
), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val edit = resolveEdit(e)
        e.presentation.isEnabledAndVisible = edit != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val edit = resolveEdit(e) ?: run {
            LOG.warn("RejectEditAction: no ProposedEdit in diff context")
            return
        }
        val project = e.project ?: run {
            LOG.warn("RejectEditAction: no project in action event")
            return
        }
        project.getService(RubynDiffManager::class.java)?.rejectEdit(edit)
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun resolveEdit(e: AnActionEvent): ProposedEdit? {
        val context = e.getData(DiffDataKeys.DIFF_CONTEXT) ?: return null
        return context.getUserData(ProposedEditDiffContext.KEY)
    }
}
