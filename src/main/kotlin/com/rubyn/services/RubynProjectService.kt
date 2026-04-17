package com.rubyn.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project

private val LOG = logger<RubynProjectService>()

/**
 * Per-project coordinator that owns the bridge, session state, and prompt
 * lifecycle for the Rubyn plugin.
 *
 * Registered as a project-level service in plugin.xml. Accessed via:
 *   project.getService(RubynProjectService::class.java)
 *
 * Full implementation lives in Task 3 (RubynProjectService).
 * This stub satisfies the plugin.xml registration so the plugin loads cleanly.
 */
@Service(Service.Level.PROJECT)
class RubynProjectService(private val project: Project) : Disposable {

    /**
     * Called by [com.rubyn.startup.RubynStartupActivity] after the project is
     * open and confirmed to be a Ruby project.
     *
     * TODO (Task 3): wire up RubynProcessService and RubynBridge here.
     */
    fun onProjectOpened() {
        LOG.info("RubynProjectService initialized for project: ${project.name}")
    }

    override fun dispose() {
        LOG.info("RubynProjectService disposing for project: ${project.name}")
    }
}
