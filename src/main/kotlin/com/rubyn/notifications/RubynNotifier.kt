package com.rubyn.notifications

import com.intellij.ide.BrowserUtil
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.rubyn.RubynBundle
import com.rubyn.services.RubynProcessService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val GROUP_ID = "Rubyn"
private const val WELCOME_SHOWN_KEY = "com.rubyn.welcomeMessage.shown"
private const val DOCS_URL = "https://rubyn.dev/docs"

/**
 * Centralized user-facing notifications for the Rubyn plugin.
 *
 * All notifications are routed through the registered "Rubyn" NotificationGroup
 * (displayType=BALLOON, registered in plugin.xml). Each typed method maps to a
 * distinct notification scenario with appropriate severity and action buttons.
 *
 * Terminal action support is guarded by a feature-detection check so the plugin
 * loads cleanly even when the terminal bundled plugin is absent.
 *
 * Usage:
 *   RubynNotifier.gemNotFound(project)
 *   RubynNotifier.connectionRestored(project)
 */
object RubynNotifier {

    // ── Private helpers ──────────────────────────────────────────────────────

    private fun group() = NotificationGroupManager.getInstance()
        .getNotificationGroup(GROUP_ID)

    /** Action: open the Rubyn settings configurable. */
    private fun openSettingsAction(project: Project): NotificationAction =
        NotificationAction.createSimpleExpiring(
            RubynBundle.message("notification.action.open.settings")
        ) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, "Rubyn")
        }

    /**
     * Action: activate the Terminal tool window.
     *
     * Returns null when the terminal bundled plugin is not present so callers
     * can skip adding the action rather than receiving a ClassNotFoundException.
     */
    private fun openTerminalActionFor(project: Project): NotificationAction? {
        val terminalPluginPresent = try {
            Class.forName("org.jetbrains.plugins.terminal.TerminalToolWindowManager")
            true
        } catch (_: ClassNotFoundException) {
            false
        }
        if (!terminalPluginPresent) return null

        return NotificationAction.createSimpleExpiring(
            RubynBundle.message("notification.action.open.terminal")
        ) {
            ToolWindowManager.getInstance(project)
                .getToolWindow("Terminal")
                ?.activate(null)
        }
    }

    // ── Public notification methods ──────────────────────────────────────────

    /**
     * Error: rubyn-code gem not found on PATH or in the project bundle.
     *
     * Actions: Open Settings | Open Terminal | Install (opens docs)
     */
    fun gemNotFound(project: Project) {
        val notification = group().createNotification(
            RubynBundle.message("notification.rubyn.gem.not.found.title"),
            RubynBundle.message("notification.rubyn.gem.not.found.content"),
            NotificationType.ERROR
        )
        notification.addAction(openSettingsAction(project))
        openTerminalActionFor(project)?.let { notification.addAction(it) }
        notification.addAction(
            NotificationAction.createSimpleExpiring(
                RubynBundle.message("notification.action.install.gem")
            ) {
                BrowserUtil.browse(DOCS_URL)
            }
        )
        Notifications.Bus.notify(notification, project)
    }

    /**
     * Warning: rubyn-code started but API key / authentication is missing.
     *
     * Actions: Open Terminal (to run `rubyn auth`)
     */
    fun notAuthenticated(project: Project) {
        val notification = group().createNotification(
            RubynBundle.message("notification.rubyn.not.authenticated.title"),
            RubynBundle.message("notification.rubyn.not.authenticated.content"),
            NotificationType.WARNING
        )
        openTerminalActionFor(project)?.let { notification.addAction(it) }
        Notifications.Bus.notify(notification, project)
    }

    /**
     * Error: rubyn-code process exited unexpectedly while the IDE was open.
     *
     * Actions: Restart — dispatched on a pooled thread so [RubynProcessService.restart]
     * can call [RubynProcessService.checkRubyVersion] without blocking the EDT.
     */
    fun processCrashed(project: Project) {
        val notification = group().createNotification(
            RubynBundle.message("notification.rubyn.process.crashed.title"),
            RubynBundle.message("notification.rubyn.process.crashed.content"),
            NotificationType.ERROR
        )
        notification.addAction(
            NotificationAction.createSimpleExpiring(
                RubynBundle.message("notification.action.restart")
            ) {
                // restart() may block up to 5s on ruby --version; must not run on EDT.
                ApplicationManager.getApplication().executeOnPooledThread {
                    project.getService(RubynProcessService::class.java)?.restart()
                }
            }
        )
        Notifications.Bus.notify(notification, project)
    }

    /**
     * Error: rubyn-code process could not be launched (bad path, missing binary, etc.).
     *
     * Actions: Open Settings
     */
    fun processFailedToStart(project: Project) {
        val notification = group().createNotification(
            RubynBundle.message("notification.rubyn.process.failed.to.start.title"),
            RubynBundle.message("notification.rubyn.process.failed.to.start.content"),
            NotificationType.ERROR
        )
        notification.addAction(openSettingsAction(project))
        Notifications.Bus.notify(notification, project)
    }

    /**
     * Warning: spending limit reached for the current session or billing period.
     *
     * Actions: Open Settings (to adjust budget)
     */
    fun budgetExceeded(project: Project) {
        val notification = group().createNotification(
            RubynBundle.message("notification.rubyn.budget.exceeded.title"),
            RubynBundle.message("notification.rubyn.budget.exceeded.content"),
            NotificationType.WARNING
        )
        notification.addAction(openSettingsAction(project))
        Notifications.Bus.notify(notification, project)
    }

    /**
     * Warning: bridge lost connection; a reconnect attempt is in progress.
     */
    fun connectionLost(project: Project) {
        val notification = group().createNotification(
            RubynBundle.message("notification.rubyn.connection.lost.title"),
            RubynBundle.message("notification.rubyn.connection.lost.content"),
            NotificationType.WARNING
        )
        Notifications.Bus.notify(notification, project)
    }

    /**
     * Info: bridge reconnected successfully.
     * Auto-dismissed after 3 seconds — no user action required.
     */
    fun connectionRestored(project: Project) {
        val notification = group().createNotification(
            RubynBundle.message("notification.rubyn.connection.restored.title"),
            RubynBundle.message("notification.rubyn.connection.restored.content"),
            NotificationType.INFORMATION
        )
        Notifications.Bus.notify(notification, project)

        val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "rubyn-notification-dismiss").also { it.isDaemon = true }
        }
        scheduler.schedule({
            ApplicationManager.getApplication().invokeLater { notification.expire() }
            scheduler.shutdown()
        }, 3L, TimeUnit.SECONDS)
    }

    /**
     * Warning: project Ruby version is below the rubyn-code minimum.
     *
     * @param detectedVersion the version string reported by `ruby --version`.
     */
    fun rubyVersionWarning(project: Project, detectedVersion: String) {
        val notification = group().createNotification(
            RubynBundle.message("notification.rubyn.ruby.version.warning.title"),
            RubynBundle.message("notification.rubyn.ruby.version.warning.content", detectedVersion),
            NotificationType.WARNING
        )
        Notifications.Bus.notify(notification, project)
    }

    /**
     * Error: the bridge failed to reconnect after the maximum number of attempts.
     *
     * The user must intervene manually. Actions: Retry — calls [RubynProjectService.ensureRunning]
     * on a pooled thread so it doesn't block the EDT.
     */
    fun bridgeDisconnected(project: Project) {
        val notification = group().createNotification(
            RubynBundle.message("notification.rubyn.bridge.disconnected.title"),
            RubynBundle.message("notification.rubyn.bridge.disconnected.content"),
            NotificationType.ERROR
        )
        notification.addAction(
            NotificationAction.createSimpleExpiring(
                RubynBundle.message("notification.action.restart")
            ) {
                ApplicationManager.getApplication().executeOnPooledThread {
                    project.getService(com.rubyn.services.RubynProjectService::class.java)
                        ?.ensureRunning()
                }
            }
        )
        Notifications.Bus.notify(notification, project)
    }

    /**
     * Info: first-run welcome message with a link to documentation.
     *
     * Includes a "Don't show again" action that persists the dismissal via
     * [PropertiesComponent] (application-level, survives project re-open).
     * This is a no-op if the user has already dismissed it.
     */
    fun welcomeMessage(project: Project) {
        if (PropertiesComponent.getInstance().isTrueValue(WELCOME_SHOWN_KEY)) return

        val notification = group().createNotification(
            RubynBundle.message("notification.rubyn.welcome.title"),
            RubynBundle.message("notification.rubyn.welcome.content"),
            NotificationType.INFORMATION
        )
        notification.addAction(
            NotificationAction.createSimpleExpiring(
                RubynBundle.message("notification.action.view.docs")
            ) {
                BrowserUtil.browse(DOCS_URL)
            }
        )
        notification.addAction(
            NotificationAction.createSimpleExpiring(
                RubynBundle.message("notification.action.dont.show.again")
            ) {
                PropertiesComponent.getInstance().setValue(WELCOME_SHOWN_KEY, true)
            }
        )
        Notifications.Bus.notify(notification, project)
    }
}
