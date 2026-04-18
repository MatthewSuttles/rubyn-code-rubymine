package com.rubyn.statusbar

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.AnimatedIcon
import com.intellij.util.Consumer
import com.rubyn.RubynBundle
import com.rubyn.icons.RubynIcons
import com.rubyn.services.AgentStatus
import com.rubyn.services.RubynProjectService
import com.rubyn.services.SessionCost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.awt.event.MouseEvent
import javax.swing.Icon

private val LOG = logger<RubynStatusBarWidget>()

/**
 * Status bar widget showing Rubyn agent state, session cost, and providing
 * one-click access to the chat tool window.
 *
 * ## Presentation
 * Implements both [StatusBarWidget.IconPresentation] (left-edge icon that
 * reflects agent activity state) and [StatusBarWidget.MultipleTextValuesPresentation]
 * (text label that changes with agent status).
 *
 * ## State subscription
 * Subscribes to [RubynProjectService.agentStatus] and [RubynProjectService.sessionCost]
 * StateFlows. On each emission the widget updates its cached fields and requests a
 * status bar repaint via [StatusBar.updateWidget].
 *
 * ## Icon states
 * | Agent status        | Icon                              |
 * |---------------------|-----------------------------------|
 * | IDLE                | [RubynIcons.StatusBarIcon]        |
 * | THINKING            | [AnimatedIcon.Default]            |
 * | STREAMING           | [AnimatedIcon.Default]            |
 * | WAITING_APPROVAL    | [AnimatedIcon.Default]            |
 * | ERROR               | [AllIcons.General.Error]          |
 *
 * ## Lifecycle
 * Created by [RubynStatusBarWidgetFactory]. [install] starts the coroutine that
 * drives state updates. [dispose] cancels the scope and clears the status bar ref.
 */
class RubynStatusBarWidget(private val project: Project) :
    StatusBarWidget,
    StatusBarWidget.IconPresentation,
    StatusBarWidget.MultipleTextValuesPresentation {

    companion object {
        const val WIDGET_ID = "com.rubyn.statusbar.RubynStatusBarWidget"
    }

    // -- Coroutine scope ---------------------------------------------------

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observerJob: Job? = null

    // -- Cached display state ----------------------------------------------

    @Volatile private var currentStatus: AgentStatus = AgentStatus.IDLE
    @Volatile private var currentCost: SessionCost = SessionCost.ZERO
    @Volatile private var statusBar: StatusBar? = null

    // -- StatusBarWidget ---------------------------------------------------

    override fun ID(): String = WIDGET_ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        startObserving()
        LOG.debug("RubynStatusBarWidget installed for project '${project.name}'")
    }

    override fun dispose() {
        observerJob?.cancel()
        observerJob = null
        scope.cancel()
        statusBar = null
        LOG.debug("RubynStatusBarWidget disposed for project '${project.name}'")
    }

    // -- IconPresentation --------------------------------------------------

    override fun getIcon(): Icon = iconFor(currentStatus)

    // -- MultipleTextValuesPresentation ------------------------------------

    override fun getSelectedValue(): String = labelFor(currentStatus)

    override fun getTooltipText(): String = buildTooltip(currentStatus, currentCost)

    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer { _ ->
        openToolWindow()
    }

    // -- Private helpers ---------------------------------------------------

    /**
     * Starts a coroutine that collects [agentStatus] and [sessionCost] flows
     * from [RubynProjectService], updating the cached fields and requesting a
     * widget repaint on each emission.
     *
     * If the project service is unavailable (e.g. project closing), the widget
     * stays on its initial IDLE state -- no crash.
     */
    private fun startObserving() {
        val service = project.getService(RubynProjectService::class.java) ?: run {
            LOG.warn("RubynStatusBarWidget: RubynProjectService unavailable -- widget will be static")
            return
        }

        observerJob = scope.launch {
            service.agentStatus
                .combine(service.sessionCost) { status, cost -> status to cost }
                .collect { (status, cost) ->
                    currentStatus = status
                    currentCost = cost
                    requestRepaint()
                }
        }
    }

    /**
     * Asks the status bar to redraw this widget. Must run on the EDT -- the
     * coroutine scope is already on [Dispatchers.Main], so this is safe.
     */
    private fun requestRepaint() {
        statusBar?.updateWidget(WIDGET_ID)
    }

    /**
     * Opens the Rubyn tool window. Dispatched on the EDT.
     */
    private fun openToolWindow() {
        ApplicationManager.getApplication().invokeLater {
            ToolWindowManager.getInstance(project)
                .getToolWindow("Rubyn")
                ?.activate(null)
        }
    }

    // -- Display helpers ---------------------------------------------------

    private fun iconFor(status: AgentStatus): Icon = when (status) {
        AgentStatus.IDLE             -> RubynIcons.StatusBarIcon
        AgentStatus.THINKING         -> AnimatedIcon.Default.INSTANCE
        AgentStatus.STREAMING        -> AnimatedIcon.Default.INSTANCE
        AgentStatus.WAITING_APPROVAL -> AnimatedIcon.Default.INSTANCE
        AgentStatus.ERROR            -> AllIcons.General.Error
    }

    private fun labelFor(status: AgentStatus): String = when (status) {
        AgentStatus.IDLE             -> RubynBundle.message("statusbar.rubyn.label.idle")
        AgentStatus.THINKING         -> RubynBundle.message("statusbar.rubyn.label.thinking")
        AgentStatus.STREAMING        -> RubynBundle.message("statusbar.rubyn.label.writing")
        AgentStatus.WAITING_APPROVAL -> RubynBundle.message("statusbar.rubyn.label.waiting")
        AgentStatus.ERROR            -> RubynBundle.message("statusbar.rubyn.label.error")
    }

    private fun buildTooltip(status: AgentStatus, cost: SessionCost): String {
        val statusLine = RubynBundle.message("statusbar.rubyn.tooltip.status", labelFor(status))
        val tokenLine  = RubynBundle.message(
            "statusbar.rubyn.tooltip.tokens",
            cost.inputTokens,
            cost.outputTokens,
        )
        val costLine = RubynBundle.message(
            "statusbar.rubyn.tooltip.cost",
            "%.4f".format(cost.costUsd),
        )
        return "$statusLine\n$tokenLine\n$costLine"
    }
}
