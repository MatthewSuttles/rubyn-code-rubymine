package com.rubyn.statusbar

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.rubyn.RubynBundle
import com.rubyn.icons.RubynIcons

/**
 * Factory that registers the Rubyn status bar widget with the IDE.
 *
 * The widget shows the current agent state and session cost, and opens
 * the Rubyn tool window on click. Full implementation in Task 10
 * (Status Bar Widget). This stub satisfies the plugin.xml registration.
 */
class RubynStatusBarWidgetFactory : StatusBarWidgetFactory {

    override fun getId(): String = "com.rubyn.statusbar.RubynStatusBarWidgetFactory"

    override fun getDisplayName(): String = RubynBundle.message("settings.rubyn.title")

    override fun isAvailable(project: Project): Boolean = true

    override fun createWidget(project: Project): StatusBarWidget = RubynStatusBarWidget(project)

    override fun disposeWidget(widget: StatusBarWidget) {
        widget.dispose()
    }

    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}
