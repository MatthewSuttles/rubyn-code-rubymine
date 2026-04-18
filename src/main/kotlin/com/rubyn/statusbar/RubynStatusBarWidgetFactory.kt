package com.rubyn.statusbar

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.rubyn.RubynBundle
import com.rubyn.context.ProjectDetector

/**
 * Registers the [RubynStatusBarWidget] with the IDE.
 *
 * [isAvailable] returns true only for Ruby projects (as detected by
 * [ProjectDetector.isRubyProject]). This keeps the widget out of the status
 * bar for unrelated projects, satisfying the AC: "visible for Ruby projects only".
 *
 * The factory is registered in plugin.xml under the
 * `com.intellij.statusBarWidgetFactory` extension point.
 */
class RubynStatusBarWidgetFactory : StatusBarWidgetFactory {

    override fun getId(): String = RubynStatusBarWidget.WIDGET_ID

    override fun getDisplayName(): String = RubynBundle.message("settings.rubyn.title")

    /**
     * Returns true only when [ProjectDetector] identifies the project as a
     * Ruby project. The status bar framework calls this before creating the
     * widget, so non-Ruby projects never have a widget instance.
     */
    override fun isAvailable(project: Project): Boolean {
        val detector = project.getService(ProjectDetector::class.java) ?: return false
        return detector.isRubyProject()
    }

    override fun createWidget(project: Project): StatusBarWidget = RubynStatusBarWidget(project)

    override fun disposeWidget(widget: StatusBarWidget) {
        widget.dispose()
    }

    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}
