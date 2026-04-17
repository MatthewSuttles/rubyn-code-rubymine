package com.rubyn.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project

private val LOG = logger<RubynProcessService>()

/**
 * Per-project service managing the rubyn-code child process lifecycle.
 *
 * Registered as a project-level service in plugin.xml. Full implementation
 * lives in Task 5 (RubynProcessService). This stub satisfies the plugin.xml
 * registration so the plugin loads cleanly.
 */
@Service(Service.Level.PROJECT)
class RubynProcessService(private val project: Project) : Disposable {

    override fun dispose() {
        LOG.info("RubynProcessService disposing for project: ${project.name}")
    }
}
