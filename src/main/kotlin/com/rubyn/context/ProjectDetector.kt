package com.rubyn.context

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.AsyncFileListener
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

private val LOG = logger<ProjectDetector>()

/**
 * Detects Ruby/Rails project characteristics and exposes them via a [StateFlow].
 *
 * The detection result is cached and automatically invalidated when Gemfile.lock
 * changes on disk — rubygem additions/removals will refresh the state without a
 * full IDE restart.
 *
 * Access via:
 *   project.service<ProjectDetector>().projectInfo
 *   project.service<ProjectDetector>().getProjectInfo()
 *
 * Registered as a project-level service in plugin.xml.
 */
@Service(Service.Level.PROJECT)
class ProjectDetector(private val project: Project) : Disposable {

    private val _projectInfo = MutableStateFlow(RubyProjectInfo.UNKNOWN)

    /** Live view of the detected project characteristics. Updates when Gemfile.lock changes. */
    val projectInfo: StateFlow<RubyProjectInfo> = _projectInfo.asStateFlow()

    private val fileListener = GemfileLockListener()

    init {
        VirtualFileManager.getInstance().addAsyncFileListener(fileListener, this)
        refresh()
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Returns true when the project contains a Gemfile or a .rubyn-code marker. */
    fun isRubyProject(): Boolean {
        val base = project.basePath ?: return false
        return File(base, "Gemfile").exists()
            || File(base, ".rubyn-code").exists()
            || hasRubyModuleType()
    }

    /**
     * Returns the current [RubyProjectInfo] snapshot.
     *
     * This is the same value as [projectInfo].value. Safe to call from any thread.
     */
    fun getProjectInfo(): RubyProjectInfo = _projectInfo.value

    // ── Internals ─────────────────────────────────────────────────────────────

    /**
     * Re-reads all detection markers and publishes a fresh [RubyProjectInfo].
     * Called at service init and whenever Gemfile.lock changes.
     */
    private fun refresh() {
        val base = project.basePath
        if (base == null) {
            _projectInfo.value = RubyProjectInfo.UNKNOWN
            return
        }

        val gemfilePath = File(base, "Gemfile")
        val gemfileLockPath = File(base, "Gemfile.lock")

        val gems = parseGemfileLock(gemfileLockPath)

        val isRails = gems.contains("rails") || File(base, "config/application.rb").exists()

        val railsVersion: String? = if (isRails) gems["rails"] else null

        val rubyVersion: String? = readRubyVersion(base)

        val testFramework: TestFramework = when {
            gems.containsKey("rspec-core") || gems.containsKey("rspec-rails") -> TestFramework.RSPEC
            gems.containsKey("minitest") -> TestFramework.MINITEST
            else -> TestFramework.UNKNOWN
        }

        val hasRubynMd = File(base, "RUBYN.md").exists() || File(base, ".rubyn.md").exists()

        val info = RubyProjectInfo(
            isRubyProject = gemfilePath.exists() || File(base, ".rubyn-code").exists(),
            isRails = isRails,
            rubyVersion = rubyVersion,
            railsVersion = railsVersion,
            testFramework = testFramework,
            hasRubynMd = hasRubynMd,
            gemfileGems = gems,
        )

        LOG.debug("ProjectDetector refreshed for '${project.name}': isRails=$isRails testFramework=$testFramework")
        _projectInfo.value = info
    }

    /**
     * Parses Gemfile.lock for installed gem names and versions.
     *
     * Only reads the GEM section (lines between "GEM" and the next blank
     * line after the "specs:" block). Returns an empty map when the file
     * doesn't exist or is unreadable.
     */
    private fun parseGemfileLock(lockFile: File): Map<String, String> {
        if (!lockFile.exists()) return emptyMap()

        val gems = mutableMapOf<String, String>()

        try {
            var inSpecs = false
            lockFile.forEachLine { line ->
                when {
                    line.trim() == "specs:" -> inSpecs = true
                    inSpecs && line.isBlank() -> inSpecs = false
                    inSpecs -> {
                        // Spec lines look like: "    gem-name (1.2.3)"
                        val match = Regex("""^\s{4}(\S+)\s+\(([^)]+)\)""").find(line)
                        if (match != null) {
                            gems[match.groupValues[1]] = match.groupValues[2]
                        }
                    }
                }
            }
        } catch (e: Exception) {
            LOG.warn("Failed to parse Gemfile.lock at ${lockFile.path}", e)
        }

        return gems
    }

    /**
     * Reads the Ruby version from .ruby-version or .tool-versions, returning
     * null when neither file is present.
     */
    private fun readRubyVersion(basePath: String): String? {
        val rubyVersion = File(basePath, ".ruby-version")
        if (rubyVersion.exists()) {
            return rubyVersion.readText().trim().removePrefix("ruby-")
        }

        // Fallback: parse asdf .tool-versions
        val toolVersions = File(basePath, ".tool-versions")
        if (toolVersions.exists()) {
            val rubyLine = toolVersions.readLines()
                .firstOrNull { line ->
                    val parts = line.trim().split(Regex("\\s+"))
                    parts.size >= 2 && parts[0] == "ruby"
                }
            if (rubyLine != null) {
                return rubyLine.trim().split(Regex("\\s+"))[1]
            }
        }

        return null
    }

    /**
     * Checks whether the IntelliJ module system has a Ruby SDK configured.
     * Falls back gracefully when the Ruby plugin class is not on the classpath
     * (e.g., in unit test environments without the full RubyMine distribution).
     */
    private fun hasRubyModuleType(): Boolean {
        return try {
            val moduleTypeClass = Class.forName("org.jetbrains.plugins.ruby.ruby.RubyModuleType")
            val moduleManager = com.intellij.openapi.module.ModuleManager.getInstance(project)
            moduleManager.modules.any { module ->
                val moduleType = com.intellij.openapi.module.ModuleType.get(module)
                moduleTypeClass.isInstance(moduleType)
            }
        } catch (e: ClassNotFoundException) {
            LOG.debug("RubyModuleType not on classpath — skipping module type check", e)
            false
        }
    }

    override fun dispose() {
        LOG.info("ProjectDetector disposing for project: ${project.name}")
    }

    // ── Inner listener ────────────────────────────────────────────────────────

    /**
     * Listens for Gemfile.lock changes and triggers a [refresh] so that
     * [projectInfo] always reflects the current gem set without an IDE restart.
     */
    private inner class GemfileLockListener : AsyncFileListener {

        override fun prepareChange(events: List<VFileEvent>): AsyncFileListener.ChangeApplier? {
            val base = project.basePath ?: return null
            // VFileEvent.path uses system-dependent separators and is a file system path,
            // not a VFS URL, so a canonical comparison is safe here.
            val gemfileLockPath = File(base, "Gemfile.lock").canonicalPath

            val relevant = events.any { event ->
                try {
                    File(event.path).canonicalPath == gemfileLockPath
                } catch (_: Exception) {
                    false
                }
            }

            if (!relevant) return null

            return object : AsyncFileListener.ChangeApplier {
                override fun afterVfsChange() {
                    LOG.debug("Gemfile.lock changed — invalidating ProjectDetector cache")
                    refresh()
                }
            }
        }
    }
}

/**
 * Detected characteristics of the project.
 *
 * @property isRubyProject   True when a Gemfile or .rubyn-code marker is present.
 * @property isRails         True when the "rails" gem is in Gemfile.lock or config/application.rb exists.
 * @property rubyVersion     Version string from .ruby-version or .tool-versions, or null if absent.
 * @property railsVersion    Version of the "rails" gem from Gemfile.lock, or null if not a Rails project.
 * @property testFramework   Detected test library (RSPEC, MINITEST, or UNKNOWN).
 * @property hasRubynMd      True when RUBYN.md or .rubyn.md exists in the project root.
 * @property gemfileGems     Map of gem name → version from Gemfile.lock.
 */
data class RubyProjectInfo(
    val isRubyProject: Boolean,
    val isRails: Boolean,
    val rubyVersion: String?,
    val railsVersion: String?,
    val testFramework: TestFramework,
    val hasRubynMd: Boolean,
    val gemfileGems: Map<String, String>,
) {
    companion object {
        /** Sentinel value used before detection has run. */
        val UNKNOWN = RubyProjectInfo(
            isRubyProject = false,
            isRails = false,
            rubyVersion = null,
            railsVersion = null,
            testFramework = TestFramework.UNKNOWN,
            hasRubynMd = false,
            gemfileGems = emptyMap(),
        )
    }
}

/** Test framework detected in the project. */
enum class TestFramework {
    RSPEC,
    MINITEST,
    UNKNOWN,
}
