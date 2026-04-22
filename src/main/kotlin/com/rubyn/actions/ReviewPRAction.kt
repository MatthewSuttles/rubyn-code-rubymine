package com.rubyn.actions

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages
import com.rubyn.RubynBundle

/**
 * Action: ask Rubyn to review a pull request by branch name.
 *
 * Shows a simple input dialog where the user types (or pastes) the branch name
 * they want reviewed. On confirmation, submits a review prompt to the bridge.
 *
 * Available in any Ruby project — no editor selection required.
 */
class ReviewPRAction : AbstractRubynAction(
    RubynBundle.message("action.review.pr")
) {

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return

        val branch = Messages.showInputDialog(
            project,
            RubynBundle.message("dialog.review.pr.message"),
            RubynBundle.message("dialog.review.pr.title"),
            null,
        )?.trim()

        if (branch.isNullOrEmpty()) return

        val context = buildEditorContext(event)
        val prompt = RubynBundle.message("prompt.review.pr", branch)
        launchPrompt(project, prompt, context)
    }

    // No additional update guard — base class Ruby-project check is sufficient.
}
