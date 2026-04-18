package com.rubyn.actions

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.rubyn.RubynBundle

/**
 * Action: send the current editor selection (or whole file) to Rubyn with an
 * "explain" prompt.
 *
 * Bound to Ctrl+Shift+E (Cmd+Shift+E on macOS) in plugin.xml.
 *
 * Behaviour:
 *   - Selection present → explains the selected text.
 *   - No selection     → explains the entire active file (sends file path in context).
 *
 * Available in any Ruby file regardless of selection state.
 */
class ExplainCodeAction : AbstractRubynAction(
    RubynBundle.message("action.explain.code")
) {

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val context = buildEditorContext(event) ?: return

        val prompt = if (!context.selectedText.isNullOrEmpty()) {
            RubynBundle.message("prompt.explain.selection", context.selectedText)
        } else {
            val filePath = context.filePath ?: return
            RubynBundle.message("prompt.explain.file", filePath)
        }

        launchPrompt(project, prompt, context)
    }

    override fun update(event: AnActionEvent) {
        super.update(event)
        if (!event.presentation.isEnabledAndVisible) return

        // Requires an active editor (selection or whole-file both acceptable).
        val hasEditor = event.getData(CommonDataKeys.EDITOR) != null
        event.presentation.isEnabledAndVisible = hasEditor
    }
}
