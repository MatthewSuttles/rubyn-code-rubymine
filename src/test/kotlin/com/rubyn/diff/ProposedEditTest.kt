package com.rubyn.diff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ProposedEdit] sealed class hierarchy and [Hunk] data model.
 *
 * These tests cover the data model itself — no IntelliJ platform required.
 * Tests for [RubynDiffManager] dispatch logic are in [RubynDiffManagerTest].
 *
 * Covers:
 *   - Modify variant: before/after content, hunks
 *   - Create variant: synthetic empty before, after content
 *   - Delete variant: before content, synthetic empty after
 *   - Identity: editId and filePath are accessible on sealed base
 *   - Equality (data class)
 *   - Hunk data model: line kinds
 */
class ProposedEditTest {

    // ── Modify ────────────────────────────────────────────────────────────

    @Test
    fun `Modify holds before and after content`() {
        val edit = ProposedEdit.Modify(
            editId = "edit-1",
            filePath = "/app/models/user.rb",
            before = "class User; end",
            after = "class User\n  validates :name\nend",
        )

        assertEquals("edit-1", edit.editId)
        assertEquals("/app/models/user.rb", edit.filePath)
        assertEquals("class User; end", edit.before)
        assertEquals("class User\n  validates :name\nend", edit.after)
    }

    @Test
    fun `Modify hunks default to empty list`() {
        val edit = ProposedEdit.Modify(
            editId = "e",
            filePath = "/app/foo.rb",
            before = "old",
            after = "new",
        )

        assertTrue("hunks should default empty", edit.hunks.isEmpty())
    }

    @Test
    fun `Modify with hunks stores them`() {
        val hunk = Hunk(
            beforeStartLine = 1, beforeLineCount = 1,
            afterStartLine = 1, afterLineCount = 2,
            lines = listOf(
                HunkLine(HunkLineKind.REMOVED, "old line"),
                HunkLine(HunkLineKind.ADDED, "new line 1"),
                HunkLine(HunkLineKind.ADDED, "new line 2"),
            )
        )
        val edit = ProposedEdit.Modify(
            editId = "e",
            filePath = "/f.rb",
            before = "old",
            after = "new",
            hunks = listOf(hunk),
        )

        assertEquals(1, edit.hunks.size)
        assertEquals(3, edit.hunks[0].lines.size)
        assertEquals(HunkLineKind.REMOVED, edit.hunks[0].lines[0].kind)
        assertEquals(HunkLineKind.ADDED, edit.hunks[0].lines[1].kind)
        assertEquals(HunkLineKind.ADDED, edit.hunks[0].lines[2].kind)
    }

    // ── Create ────────────────────────────────────────────────────────────

    @Test
    fun `Create has empty before and supplied after`() {
        val edit = ProposedEdit.Create(
            editId = "new-1",
            filePath = "/app/services/my_service.rb",
            after = "class MyService; end",
        )

        assertEquals("new-1", edit.editId)
        assertEquals("", edit.before)
        assertEquals("class MyService; end", edit.after)
    }

    @Test
    fun `Create editId and filePath accessible from sealed base`() {
        val edit: ProposedEdit = ProposedEdit.Create(
            editId = "base-access",
            filePath = "/foo.rb",
            after = "content",
        )

        assertEquals("base-access", edit.editId)
        assertEquals("/foo.rb", edit.filePath)
    }

    // ── Delete ────────────────────────────────────────────────────────────

    @Test
    fun `Delete has supplied before and empty after`() {
        val edit = ProposedEdit.Delete(
            editId = "del-1",
            filePath = "/app/old_file.rb",
            before = "class OldFile; end",
        )

        assertEquals("del-1", edit.editId)
        assertEquals("/app/old_file.rb", edit.filePath)
        assertEquals("class OldFile; end", edit.before)
        assertEquals("", edit.after)
    }

    // ── Sealed type dispatch ──────────────────────────────────────────────

    @Test
    fun `sealed when expression covers all variants`() {
        val edits: List<ProposedEdit> = listOf(
            ProposedEdit.Modify("e1", "/f.rb", "old", "new"),
            ProposedEdit.Create("e2", "/g.rb", "content"),
            ProposedEdit.Delete("e3", "/h.rb", "old content"),
        )

        val labels = edits.map { edit ->
            when (edit) {
                is ProposedEdit.Modify -> "modify"
                is ProposedEdit.Create -> "create"
                is ProposedEdit.Delete -> "delete"
            }
        }

        assertEquals(listOf("modify", "create", "delete"), labels)
    }

    // ── Data class equality ───────────────────────────────────────────────

    @Test
    fun `Modify equality by value`() {
        val a = ProposedEdit.Modify("e1", "/f.rb", "before", "after")
        val b = ProposedEdit.Modify("e1", "/f.rb", "before", "after")
        assertEquals(a, b)
    }

    @Test
    fun `Modify inequality when editId differs`() {
        val a = ProposedEdit.Modify("e1", "/f.rb", "before", "after")
        val b = ProposedEdit.Modify("e2", "/f.rb", "before", "after")
        assertNotEquals(a, b)
    }

    @Test
    fun `Create equality by value`() {
        val a = ProposedEdit.Create("c1", "/g.rb", "content")
        val b = ProposedEdit.Create("c1", "/g.rb", "content")
        assertEquals(a, b)
    }

    @Test
    fun `Delete equality by value`() {
        val a = ProposedEdit.Delete("d1", "/h.rb", "old")
        val b = ProposedEdit.Delete("d1", "/h.rb", "old")
        assertEquals(a, b)
    }

    // ── Hunk model ────────────────────────────────────────────────────────

    @Test
    fun `Hunk stores correct line counts`() {
        val hunk = Hunk(
            beforeStartLine = 5, beforeLineCount = 3,
            afterStartLine = 5, afterLineCount = 4,
            lines = emptyList(),
        )

        assertEquals(5, hunk.beforeStartLine)
        assertEquals(3, hunk.beforeLineCount)
        assertEquals(5, hunk.afterStartLine)
        assertEquals(4, hunk.afterLineCount)
    }

    @Test
    fun `HunkLine stores kind and content`() {
        val line = HunkLine(kind = HunkLineKind.CONTEXT, content = "  unchanged line")

        assertEquals(HunkLineKind.CONTEXT, line.kind)
        assertEquals("  unchanged line", line.content)
    }

    @Test
    fun `HunkLineKind has three values`() {
        val kinds = HunkLineKind.values()
        assertEquals(3, kinds.size)
        assertTrue(HunkLineKind.CONTEXT in kinds)
        assertTrue(HunkLineKind.ADDED in kinds)
        assertTrue(HunkLineKind.REMOVED in kinds)
    }
}
