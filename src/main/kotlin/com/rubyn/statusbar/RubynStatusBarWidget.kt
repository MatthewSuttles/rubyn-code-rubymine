package com.rubyn.statusbar

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.util.Consumer
import com.rubyn.RubynBundle
import java.awt.event.MouseEvent

/**
 * Status bar widget showing Rubyn agent state.
 *
 * Full implementation in Task 10 (Status Bar Widget).
 * This stub satisfies the factory registration so the plugin loads cleanly.
 */
class RubynStatusBarWidget(private val project: Project) :
    StatusBarWidget,
    StatusBarWidget.TextPresentation {

    private var statusBar: StatusBar? = null

    override fun ID(): String = "com.rubyn.statusbar.RubynStatusBarWidget"

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
    }

    override fun dispose() {
        statusBar = null
    }

    // ── TextPresentation ──────────────────────────────────────────────────

    override fun getText(): String = RubynBundle.message("statusbar.rubyn.idle")

    override fun getTooltipText(): String = RubynBundle.message("statusbar.rubyn.tooltip")

    override fun getAlignment(): Float = 0f

    override fun getClickConsumer(): Consumer<MouseEvent>? = null
}
