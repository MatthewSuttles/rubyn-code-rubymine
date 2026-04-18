package com.rubyn.actions

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.rubyn.RubynBundle

/**
 * Action: send the current editor selection to Rubyn with a "refactor" prompt.
 *
 * Bound to Ctrl+Shift+F (Cmd+Shift+F on macOS) in plugin.xml.
 * Disabled when there is no active selection.
 */
class RefactorCodeAction : AbstractRubynAction(
    RubynBundle.message("action.refactor.code")
) {

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val editor = event.getData(CommonDataKeys.EDITOR) ?: return
        val selectedText = editor.selectionModel.selectedText ?: return

        val context = buildEditorContext(event)
        val prompt = RubynBundle.message("prompt.refactor.code", selectedText)

        launchPrompt(project, prompt, context)
    }

    override fun update(event: AnActionEvent) {
        super.update(event)
        if (!event.presentation.isEnabledAndVisible) return

        val hasSelection = event.getData(CommonDataKeys.EDITOR)
            ?.selectionModel?.hasSelection() == true
        event.presentation.isEnabledAndVisible = hasSelection
    }
}
