package com.rubyn.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.wm.ToolWindowManager
import com.rubyn.RubynBundle

/**
 * Action: open (or focus) the Rubyn tool window.
 *
 * Bound to ctrl+shift+R in plugin.xml.
 * Full wiring to the bridge is implemented in Task 12 (Editor Actions).
 */
class OpenChatAction : AnAction(
    RubynBundle.lazyMessage("action.open.chat")
) {

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        ToolWindowManager.getInstance(project)
            .getToolWindow("Rubyn")
            ?.activate(null)
    }

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = event.project != null
    }
}
