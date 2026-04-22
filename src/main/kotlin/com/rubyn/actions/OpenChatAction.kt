package com.rubyn.actions

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.wm.ToolWindowManager
import com.rubyn.RubynBundle

/**
 * Action: open (or focus) the Rubyn tool window.
 *
 * Bound to Ctrl+Shift+R (Cmd+Shift+R on macOS) in plugin.xml.
 * Available whenever a Ruby project is open — no editor selection required.
 */
class OpenChatAction : AbstractRubynAction(
    RubynBundle.message("action.open.chat")
) {

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        ToolWindowManager.getInstance(project)
            .getToolWindow("Rubyn")
            ?.activate(null)
    }

    // No extra update guard needed — base class already checks for Ruby project.
}
