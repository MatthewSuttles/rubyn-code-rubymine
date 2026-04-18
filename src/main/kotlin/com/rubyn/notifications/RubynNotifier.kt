package com.rubyn.notifications

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.rubyn.RubynBundle
import com.rubyn.services.RubynProcessService

private const val GROUP_ID = "Rubyn"

/**
 * Centralized user-facing notifications for the Rubyn plugin.
 *
 * All notifications are routed through the registered "Rubyn" NotificationGroup
 * (displayType=BALLOON, registered in plugin.xml). Each typed method maps to a
 * distinct notification scenario with appropriate severity and action buttons.
 *
 * Full notification set (welcome, connection lost/restored, budget, etc.) is
 * implemented in Task 13. This file covers the subset required by
 * [RubynProcessService]: gem not found, auth errors, version warnings, and
 * process lifecycle notifications.
 */
object RubynNotifier {

    // ── Private helpers ───────────────────────────────────────────────────

    private fun group() = NotificationGroupManager.getInstance()
        .getNotificationGroup(GROUP_ID)

    private fun openSettingsAction(project: Project) =
        com.intellij.notification.NotificationAction.createSimpleExpiring(
            RubynBundle.message("notification.action.open.settings")
        ) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, "Rubyn")
        }

    // ── Notifications ─────────────────────────────────────────────────────

    /**
     * Error: rubyn-code executable not found on PATH or at the configured path.
     *
     * Actions: Open Settings
     */
    fun gemNotFound(project: Project) {
        val notification = group().createNotification(
            RubynBundle.message("notification.rubyn.gem.not.found.title"),
            RubynBundle.message("notification.rubyn.gem.not.found.content"),
            NotificationType.ERROR
        )
        notification.addAction(openSettingsAction(project))
        Notifications.Bus.notify(notification, project)
    }

    /**
     * Warning: rubyn-code started but API key / authentication is missing.
     *
     * No restart action — user must run `rubyn auth` and then restart manually.
     */
    fun notAuthenticated(project: Project) {
        val notification = group().createNotification(
            RubynBundle.message("notification.rubyn.not.authenticated.title"),
            RubynBundle.message("notification.rubyn.not.authenticated.content"),
            NotificationType.WARNING
        )
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
            com.intellij.notification.NotificationAction.createSimpleExpiring(
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
     * Error: rubyn-code process could not be launched (missing binary, bad path, etc.).
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
            com.intellij.notification.NotificationAction.createSimpleExpiring(
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
}

// NOTE: bridgeDisconnected added by Task 3
