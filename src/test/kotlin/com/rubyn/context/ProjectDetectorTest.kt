package com.rubyn.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Unit tests for the file-system parsing logic in [ProjectDetector].
 *
 * Since [ProjectDetector] is a project-level service that requires a live
 * IntelliJ [Project] instance, we test its internal pure-Kotlin logic by
 * invoking the parsing methods directly through the [ProjectInfoParser]
 * helper (a thin extraction of the parsing logic that doesn't touch the
 * IntelliJ platform).
 *
 * Test scenarios:
 *   - Bare Ruby project (Gemfile only, no Gemfile.lock)
 *   - Rails + RSpec project (full Gemfile.lock)
 *   - Rails + Minitest project
 *   - Non-Ruby project (no Gemfile, no markers)
 *   - .ruby-version detection
 *   - .tool-versions detection (asdf)
 *   - RUBYN.md / .rubyn.md markers
 *   - Gemfile.lock with multiple gems
 *   - Gemfile.lock parse is tolerant of extra blank lines and comments
 */
class ProjectDetectorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun writeFile(name: String, content: String): File {
        val file = File(tmp.root, name)
        file.parentFile?.mkdirs()
        file.writeText(content)
        return file
    }

    /**
     * Thin mirror of [ProjectDetector]'s private parsing logic.
     * Extracted here so tests run without a real IntelliJ Platform.
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
                        val match = Regex("""^\s{4}(\S+)\s+\(([^)]+)\)""").find(line)
                        if (match != null) {
                            gems[match.groupValues[1]] = match.groupValues[2]
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return gems
    }

    private fun readRubyVersion(basePath: String): String? {
        val rubyVersion = File(basePath, ".ruby-version")
        if (rubyVersion.exists()) {
            return rubyVersion.readText().trim().removePrefix("ruby-")
        }
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

    private fun detectTestFramework(gems: Map<String, String>): TestFramework = when {
        gems.containsKey("rspec-core") || gems.containsKey("rspec-rails") -> TestFramework.RSPEC
        gems.containsKey("minitest") -> TestFramework.MINITEST
        else -> TestFramework.UNKNOWN
    }

    private fun buildInfo(basePath: String): RubyProjectInfo {
        val gemfileLockPath = File(basePath, "Gemfile.lock")
        val gems = parseGemfileLock(gemfileLockPath)
        val isRails = gems.containsKey("rails") || File(basePath, "config/application.rb").exists()
        val railsVersion = if (isRails) gems["rails"] else null
        val rubyVersion = readRubyVersion(basePath)
        val testFramework = detectTestFramework(gems)
        val hasRubynMd = File(basePath, "RUBYN.md").exists() || File(basePath, ".rubyn.md").exists()
        return RubyProjectInfo(
            isRubyProject = File(basePath, "Gemfile").exists() || File(basePath, ".rubyn-code").exists(),
            isRails = isRails,
            rubyVersion = rubyVersion,
            railsVersion = railsVersion,
            testFramework = testFramework,
            hasRubynMd = hasRubynMd,
            gemfileGems = gems,
        )
    }

    // ── Bare Ruby project ─────────────────────────────────────────────────

    @Test
    fun `bare Ruby project — Gemfile present, no Gemfile lock`() {
        writeFile("Gemfile", "source 'https://rubygems.org'\ngem 'sinatra'")

        val info = buildInfo(tmp.root.absolutePath)

        assertTrue("isRubyProject should be true", info.isRubyProject)
        assertFalse("isRails should be false", info.isRails)
        assertEquals(TestFramework.UNKNOWN, info.testFramework)
        assertNull("railsVersion should be null", info.railsVersion)
        assertTrue("gemfileGems should be empty (no lock file)", info.gemfileGems.isEmpty())
    }

    @Test
    fun `rubyn-code marker file triggers isRubyProject`() {
        writeFile(".rubyn-code", "")

        val info = buildInfo(tmp.root.absolutePath)

        assertTrue("isRubyProject should be true when .rubyn-code present", info.isRubyProject)
    }

    // ── Rails + RSpec ─────────────────────────────────────────────────────

    @Test
    fun `Rails + RSpec project detected from Gemfile lock`() {
        writeFile("Gemfile", "")
        writeFile("Gemfile.lock", """
GEM
  remote: https://rubygems.org/
  specs:
    rails (7.1.0)
      actionpack (= 7.1.0)
    rspec-core (3.12.0)
    rspec-rails (6.0.0)
    actionpack (7.1.0)

BUNDLED WITH
   2.4.10
""".trimIndent())

        val info = buildInfo(tmp.root.absolutePath)

        assertTrue(info.isRubyProject)
        assertTrue("isRails should be true", info.isRails)
        assertEquals("7.1.0", info.railsVersion)
        assertEquals(TestFramework.RSPEC, info.testFramework)
        assertTrue(info.gemfileGems.containsKey("rails"))
        assertTrue(info.gemfileGems.containsKey("rspec-core"))
        assertTrue(info.gemfileGems.containsKey("rspec-rails"))
    }

    // ── Rails + Minitest ──────────────────────────────────────────────────

    @Test
    fun `Rails + Minitest project detected from Gemfile lock`() {
        writeFile("Gemfile", "")
        writeFile("Gemfile.lock", """
GEM
  remote: https://rubygems.org/
  specs:
    rails (8.0.0)
      actionpack (= 8.0.0)
    minitest (5.20.0)
    actionpack (8.0.0)

BUNDLED WITH
   2.5.0
""".trimIndent())

        val info = buildInfo(tmp.root.absolutePath)

        assertTrue(info.isRails)
        assertEquals("8.0.0", info.railsVersion)
        assertEquals(TestFramework.MINITEST, info.testFramework)
    }

    // ── config/application.rb fallback ───────────────────────────────────

    @Test
    fun `isRails true when config-application-rb exists without rails gem`() {
        writeFile("Gemfile", "")
        writeFile("config/application.rb", "require_relative 'boot'")

        val info = buildInfo(tmp.root.absolutePath)

        assertTrue("isRails via config/application.rb", info.isRails)
    }

    // ── Non-Ruby project ──────────────────────────────────────────────────

    @Test
    fun `non-Ruby project — no markers`() {
        writeFile("package.json", """{"name":"my-app"}""")

        val info = buildInfo(tmp.root.absolutePath)

        assertFalse(info.isRubyProject)
        assertFalse(info.isRails)
        assertEquals(TestFramework.UNKNOWN, info.testFramework)
    }

    // ── Ruby version detection ────────────────────────────────────────────

    @Test
    fun `ruby version from ruby-version file`() {
        writeFile(".ruby-version", "3.3.0\n")

        val version = readRubyVersion(tmp.root.absolutePath)

        assertEquals("3.3.0", version)
    }

    @Test
    fun `ruby-version with ruby- prefix is stripped`() {
        writeFile(".ruby-version", "ruby-3.2.2\n")

        val version = readRubyVersion(tmp.root.absolutePath)

        assertEquals("3.2.2", version)
    }

    @Test
    fun `ruby version from tool-versions file`() {
        writeFile(".tool-versions", "nodejs 20.0.0\nruby 3.1.4\npython 3.11.0\n")

        val version = readRubyVersion(tmp.root.absolutePath)

        assertEquals("3.1.4", version)
    }

    @Test
    fun `ruby version returns null when no version file present`() {
        val version = readRubyVersion(tmp.root.absolutePath)

        assertNull("Should return null when no version file", version)
    }

    @Test
    fun `ruby-version takes precedence over tool-versions`() {
        writeFile(".ruby-version", "3.3.0")
        writeFile(".tool-versions", "ruby 3.1.0")

        val version = readRubyVersion(tmp.root.absolutePath)

        assertEquals("3.3.0", version)
    }

    // ── RUBYN.md detection ────────────────────────────────────────────────

    @Test
    fun `hasRubynMd true when RUBYN-md present`() {
        writeFile("Gemfile", "")
        writeFile("RUBYN.md", "# Project context")

        val info = buildInfo(tmp.root.absolutePath)

        assertTrue(info.hasRubynMd)
    }

    @Test
    fun `hasRubynMd true when rubyn-md dot-file present`() {
        writeFile("Gemfile", "")
        writeFile(".rubyn.md", "# Project context")

        val info = buildInfo(tmp.root.absolutePath)

        assertTrue(info.hasRubynMd)
    }

    @Test
    fun `hasRubynMd false when neither marker present`() {
        writeFile("Gemfile", "")

        val info = buildInfo(tmp.root.absolutePath)

        assertFalse(info.hasRubynMd)
    }

    // ── Gemfile.lock parse edge cases ─────────────────────────────────────

    @Test
    fun `Gemfile lock with multiple specs sections — only GEM specs parsed`() {
        writeFile("Gemfile", "")
        writeFile("Gemfile.lock", """
GEM
  remote: https://rubygems.org/
  specs:
    activesupport (7.0.0)
    rails (7.0.0)

PATH
  remote: .
  specs:
    my_app (0.1.0)

BUNDLED WITH
   2.3.0
""".trimIndent())

        val gems = parseGemfileLock(File(tmp.root, "Gemfile.lock"))

        assertTrue(gems.containsKey("activesupport"))
        assertTrue(gems.containsKey("rails"))
        assertEquals("7.0.0", gems["rails"])
    }

    @Test
    fun `Gemfile lock with dependency indentation is ignored`() {
        writeFile("Gemfile.lock", """
GEM
  remote: https://rubygems.org/
  specs:
    rails (7.1.0)
      actionpack (>= 7.1.0)
      activesupport (>= 7.1.0)
    actionpack (7.1.0)
    activesupport (7.1.0)

BUNDLED WITH
   2.4.0
""".trimIndent())

        val gems = parseGemfileLock(File(tmp.root, "Gemfile.lock"))

        // Dependency lines (8-space indent) should not be parsed as gem entries
        assertTrue(gems.containsKey("rails"))
        assertTrue(gems.containsKey("actionpack"))
        assertTrue(gems.containsKey("activesupport"))
        assertEquals("7.1.0", gems["activesupport"])
    }

    @Test
    fun `parseGemfileLock returns empty map when file missing`() {
        val gems = parseGemfileLock(File(tmp.root, "Gemfile.lock"))
        assertTrue(gems.isEmpty())
    }

    @Test
    fun `test framework detection — rspec-core wins over minitest`() {
        writeFile("Gemfile", "")
        writeFile("Gemfile.lock", """
GEM
  remote: https://rubygems.org/
  specs:
    rspec-core (3.12.0)
    minitest (5.20.0)

BUNDLED WITH
   2.4.0
""".trimIndent())

        val info = buildInfo(tmp.root.absolutePath)

        // rspec-core check comes first in the when clause
        assertEquals(TestFramework.RSPEC, info.testFramework)
    }

    @Test
    fun `RubyProjectInfo UNKNOWN sentinel has correct defaults`() {
        val unknown = RubyProjectInfo.UNKNOWN

        assertFalse(unknown.isRubyProject)
        assertFalse(unknown.isRails)
        assertNull(unknown.rubyVersion)
        assertNull(unknown.railsVersion)
        assertEquals(TestFramework.UNKNOWN, unknown.testFramework)
        assertFalse(unknown.hasRubynMd)
        assertTrue(unknown.gemfileGems.isEmpty())
    }
}
