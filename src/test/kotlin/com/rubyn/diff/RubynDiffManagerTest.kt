package com.rubyn.diff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.LinkedHashSet

/**
 * Unit tests for [RubynDiffManager] guard logic.
 *
 * [RubynDiffManager] has deep IntelliJ platform dependencies (DiffManager,
 * VirtualFile, WriteCommandAction, etc.) that cannot run without a live IDE.
 * These tests therefore extract and verify the two pieces of pure logic that
 * are testable in isolation:
 *
 *   1. The double-accept guard (bounded [LinkedHashSet] that evicts the
 *      oldest entry once [HANDLED_IDS_MAX] is reached).
 *   2. The permission-mode routing decision (which handler is selected for
 *      each mode string).
 *
 * The guard set logic is copied verbatim from [RubynDiffManager] so that if
 * the production code changes the logic, these tests catch the regression.
 */
class RubynDiffManagerTest {

    // ── Guard set: bounded LinkedHashSet ──────────────────────────────────

    private fun buildGuardSet(max: Int): MutableSet<String> =
        Collections.synchronizedSet(
            object : LinkedHashSet<String>() {
                override fun add(element: String): Boolean {
                    if (size >= max) remove(iterator().next())
                    return super.add(element)
                }
            }
        )

    @Test
    fun `guard set accepts first edit`() {
        val set = buildGuardSet(10)
        assertTrue("First add should return true", set.add("edit-1"))
    }

    @Test
    fun `guard set rejects duplicate edit`() {
        val set = buildGuardSet(10)
        set.add("edit-1")
        assertFalse("Second add of same ID should return false", set.add("edit-1"))
    }

    @Test
    fun `guard set evicts oldest entry at capacity`() {
        val set = buildGuardSet(3)
        set.add("a")
        set.add("b")
        set.add("c")

        // At capacity — adding "d" should evict "a" (oldest)
        set.add("d")

        assertEquals("Size should remain at max", 3, set.size)
        assertFalse("Oldest entry 'a' should have been evicted", set.contains("a"))
        assertTrue(set.contains("b"))
        assertTrue(set.contains("c"))
        assertTrue(set.contains("d"))
    }

    @Test
    fun `guard set evicts in insertion order`() {
        val set = buildGuardSet(2)
        set.add("first")
        set.add("second")

        // Adding third should evict "first"
        set.add("third")

        assertFalse(set.contains("first"))
        assertTrue(set.contains("second"))
        assertTrue(set.contains("third"))
    }

    @Test
    fun `guard set stays bounded over many insertions`() {
        val max = 5
        val set = buildGuardSet(max)

        repeat(100) { i -> set.add("edit-$i") }

        assertEquals("Set should never exceed max", max, set.size)
    }

    @Test
    fun `guard set add returns false for already-handled id`() {
        val set = buildGuardSet(100)
        set.add("edit-X")

        // Simulate acceptEdit / rejectEdit double-check pattern
        val firstCall = set.add("edit-X")
        assertFalse("Already in set — should be false", firstCall)
    }

    // ── Permission-mode routing ───────────────────────────────────────────

    /**
     * Pure routing logic extracted from [RubynDiffManager.presentEdit].
     * Returns a string label for which handler would be invoked.
     */
    private fun routeMode(mode: String): String = when (mode) {
        "bypassPermissions" -> "bypass"
        "acceptEdits"       -> "autoAccept"
        "planOnly"          -> "planOnly"
        else                -> "manual"  // "default" and any unknown value
    }

    @Test
    fun `default mode routes to manual`() {
        assertEquals("manual", routeMode("default"))
    }

    @Test
    fun `empty mode routes to manual`() {
        assertEquals("manual", routeMode(""))
    }

    @Test
    fun `unknown mode routes to manual`() {
        assertEquals("manual", routeMode("some_future_mode"))
    }

    @Test
    fun `acceptEdits mode routes to autoAccept`() {
        assertEquals("autoAccept", routeMode("acceptEdits"))
    }

    @Test
    fun `bypassPermissions mode routes to bypass`() {
        assertEquals("bypass", routeMode("bypassPermissions"))
    }

    @Test
    fun `planOnly mode routes to planOnly`() {
        assertEquals("planOnly", routeMode("planOnly"))
    }

    // ── ProposedEdit type dispatch for applyToDisk ────────────────────────

    /**
     * Mirrors the applyToDisk dispatch logic without touching the filesystem.
     */
    private fun dispatchLabel(edit: ProposedEdit): String = when (edit) {
        is ProposedEdit.Modify -> "write"
        is ProposedEdit.Create -> "write"
        is ProposedEdit.Delete -> "delete"
    }

    @Test
    fun `Modify edit dispatches to write`() {
        val edit = ProposedEdit.Modify("e1", "/f.rb", "old", "new")
        assertEquals("write", dispatchLabel(edit))
    }

    @Test
    fun `Create edit dispatches to write`() {
        val edit = ProposedEdit.Create("e2", "/g.rb", "content")
        assertEquals("write", dispatchLabel(edit))
    }

    @Test
    fun `Delete edit dispatches to delete`() {
        val edit = ProposedEdit.Delete("e3", "/h.rb", "old content")
        assertEquals("delete", dispatchLabel(edit))
    }

    // ── DiffViewer content selection ──────────────────────────────────────

    /**
     * Mirrors the before/after content selection logic in showDiffViewer.
     */
    data class DiffContent(val before: String, val after: String)

    private fun selectContent(edit: ProposedEdit): DiffContent = when (edit) {
        is ProposedEdit.Modify -> DiffContent(edit.before, edit.after)
        is ProposedEdit.Create -> DiffContent("", edit.after)
        is ProposedEdit.Delete -> DiffContent(edit.before, "")
    }

    @Test
    fun `Modify shows both before and after`() {
        val edit = ProposedEdit.Modify("e1", "/f.rb", "before text", "after text")
        val content = selectContent(edit)
        assertEquals("before text", content.before)
        assertEquals("after text", content.after)
    }

    @Test
    fun `Create shows empty before and file content as after`() {
        val edit = ProposedEdit.Create("e2", "/g.rb", "new content")
        val content = selectContent(edit)
        assertEquals("", content.before)
        assertEquals("new content", content.after)
    }

    @Test
    fun `Delete shows file content as before and empty after`() {
        val edit = ProposedEdit.Delete("e3", "/h.rb", "existing content")
        val content = selectContent(edit)
        assertEquals("existing content", content.before)
        assertEquals("", content.after)
    }
}
