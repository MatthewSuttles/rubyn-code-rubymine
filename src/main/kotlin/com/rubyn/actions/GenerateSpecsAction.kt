package com.rubyn.actions

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.rubyn.RubynBundle

/**
 * Action: ask Rubyn to generate RSpec specs for the current Ruby file.
 *
 * Requirements:
 *   - The active file must be a Ruby file (.rb extension).
 *   - No selection required — the whole file is sent as context.
 *
 * Disabled for non-Ruby files and when no editor is open.
 */
class GenerateSpecsAction : AbstractRubynAction(
    RubynBundle.message("action.generate.specs")
) {

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val context = buildEditorContext(event) ?: return

        val prompt = RubynBundle.message("prompt.generate.specs", file.path)
        launchPrompt(project, prompt, context)
    }

    override fun update(event: AnActionEvent) {
        super.update(event)
        if (!event.presentation.isEnabledAndVisible) return

        val file = event.getData(CommonDataKeys.VIRTUAL_FILE)
        val isRubyFile = file?.extension?.equals("rb", ignoreCase = true) == true
        event.presentation.isEnabledAndVisible = isRubyFile
    }
}
