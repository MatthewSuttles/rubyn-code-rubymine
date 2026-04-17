package com.rubyn.startup

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.rubyn.RubynBundle
import com.rubyn.services.RubynProjectService

private val LOG = logger<RubynStartupActivity>()

/**
 * Runs once per project open, after the project is fully initialized.
 *
 * Responsibilities:
 *   1. Detect whether this is a Ruby/Rails project.
 *   2. If so, lazily initialize [RubynProjectService] to warm up the
 *      bridge connection and process lifecycle.
 *   3. If not, skip initialization silently to avoid resource waste.
 *
 * This runs on a background thread — never touch the EDT here.
 */
class RubynStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        if (!isRubyProject(project)) {
            LOG.info(RubynBundle.message("startup.rubyn.non.ruby.project"))
            return
        }

        LOG.info(RubynBundle.message("startup.rubyn.initializing"))

        // Accessing the service here triggers its init block, which wires up
        // the process lifecycle and bridge connection lazily.
        project.getService(RubynProjectService::class.java)
            ?.onProjectOpened()
    }

    /**
     * Returns true when the project contains at least one Gemfile or .gemspec,
     * which is a reliable signal that this is a Ruby project.
     *
     * This is intentionally lightweight — we just check for marker files rather
     * than consulting the full module SDK, which may not be ready yet.
     */
    private fun isRubyProject(project: Project): Boolean {
        val baseDir = project.basePath ?: return false
        val markers = listOf("Gemfile", "Gemfile.lock", ".gemspec")
        return markers.any { marker ->
            java.io.File(baseDir, marker).exists()
        }
    }
}
