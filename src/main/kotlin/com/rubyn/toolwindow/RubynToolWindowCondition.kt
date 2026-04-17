package com.rubyn.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Condition

/**
 * Condition controlling whether the Rubyn tool window is registered for a
 * given project.  Implements [Condition] as required by the
 * `conditionClass` attribute on the `<toolWindow>` extension point.
 *
 * Always returns true so the window appears in every project.  A future
 * refinement can narrow this to Ruby projects once the module SDK is
 * reliably available at tool-window registration time.
 */
class RubynToolWindowCondition : Condition<Project> {

    override fun value(project: Project): Boolean = true
}
