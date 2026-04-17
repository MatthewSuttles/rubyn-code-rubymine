package com.rubyn.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.rubyn.RubynBundle

/**
 * Action: send the current editor selection to Rubyn with a "refactor" prompt.
 *
 * Full bridge wiring is implemented in Task 12 (Editor Actions).
 */
class RefactorCodeAction : AnAction(
    RubynBundle.lazyMessage("action.refactor.code")
) {

    override fun actionPerformed(event: AnActionEvent) {
        val editor = event.getData(CommonDataKeys.EDITOR) ?: return
        val selectedText = editor.selectionModel.selectedText ?: return
        // TODO (Task 12): send selectedText to RubynProjectService with refactor prompt
    }

    override fun update(event: AnActionEvent) {
        val editor = event.getData(CommonDataKeys.EDITOR)
        val hasSelection = editor?.selectionModel?.hasSelection() == true
        event.presentation.isEnabledAndVisible = hasSelection
    }
}
