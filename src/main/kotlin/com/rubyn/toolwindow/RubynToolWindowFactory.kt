package com.rubyn.toolwindow

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.rubyn.RubynBundle

/**
 * Factory that creates the Rubyn tool window.
 *
 * Produces two tabs:
 *   - **Chat** — [RubynChatPanel]: JCEF webview bridge or Swing fallback.
 *   - **Sessions** — [RubynSessionsPanel]: tree view of past sessions with
 *     Resume, Export, and Delete actions.
 *
 * Marked [DumbAware] so the window is accessible even while the IDE is indexing.
 *
 * Both panels receive the content [com.intellij.openapi.util.Disposable] as their
 * parent so their resources are released automatically when the tool window is
 * torn down.
 */
class RubynToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val contentFactory = ContentFactory.getInstance()

        // ── Chat tab ──────────────────────────────────────────────────────

        val chatContent = contentFactory.createContent(
            /* component   = */ null,
            /* displayName = */ RubynBundle.message("toolwindow.tab.chat"),
            /* isLockable  = */ false,
        )
        // Build the panel *after* createContent so we have the disposable.
        val chatPanel = RubynChatPanel(project, chatContent)
        chatContent.component = chatPanel
        toolWindow.contentManager.addContent(chatContent)

        // ── Sessions tab ──────────────────────────────────────────────────

        val sessionsContent = contentFactory.createContent(
            /* component   = */ null,
            /* displayName = */ RubynBundle.message("toolwindow.tab.sessions"),
            /* isLockable  = */ false,
        )
        val sessionsPanel = RubynSessionsPanel(project, sessionsContent)
        sessionsContent.component = sessionsPanel
        toolWindow.contentManager.addContent(sessionsContent)
    }
}
