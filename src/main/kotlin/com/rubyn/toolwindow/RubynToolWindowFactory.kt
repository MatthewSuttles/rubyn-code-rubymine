package com.rubyn.toolwindow

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.rubyn.RubynBundle
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Factory that creates the Rubyn tool window panel.
 *
 * Marked [DumbAware] so the window is accessible even while the IDE is
 * indexing. Full JCEF webview implementation in Task 7 (Tool Window).
 * This stub creates a placeholder panel so the plugin loads cleanly.
 */
class RubynToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val contentFactory = ContentFactory.getInstance()

        // TODO (Task 7): replace with JCEF webview panel
        val placeholder = JPanel().apply { add(JLabel("Rubyn — coming in Task 7")) }
        val content = contentFactory.createContent(
            placeholder,
            RubynBundle.message("toolwindow.rubyn.title"),
            /* isLockable = */ false
        )
        toolWindow.contentManager.addContent(content)
    }
}
