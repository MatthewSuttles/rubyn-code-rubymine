package com.rubyn.diff

/**
 * A file-system change proposed by the rubyn-code agent.
 *
 * The sealed hierarchy mirrors the three kinds of edit the agent can request:
 *   - [Modify]  — an existing file whose content will change.
 *   - [Create]  — a new file that does not yet exist on disk.
 *   - [Delete]  — an existing file that will be removed.
 *
 * Each variant carries the information needed to display the diff, accept the
 * change via [WriteCommandAction] (undoable), and notify the bridge of the
 * decision.
 *
 * ## Hunk data
 * [Modify] exposes a [hunks] list for structured diff rendering. Each [Hunk]
 * describes one contiguous changed region in the file. The [DiffManager] uses
 * these to build a [SimpleDiffRequest] showing a before/after comparison.
 *
 * ## Identity
 * [editId] is the unique string assigned by the rubyn-code agent and echoed
 * back in approval/denial notifications. It is the primary key used to
 * correlate user decisions with pending edits in [RubynProjectService].
 */
sealed class ProposedEdit {

    /** Bridge-assigned unique identifier for this edit. */
    abstract val editId: String

    /** Absolute path to the file being modified, created, or deleted. */
    abstract val filePath: String

    // ── Variants ──────────────────────────────────────────────────────────

    /**
     * An edit that modifies the content of an existing file.
     *
     * @param editId    Bridge-assigned unique ID.
     * @param filePath  Absolute path to the file on disk.
     * @param before    The file's full content before the edit.
     * @param after     The file's full content after the edit.
     * @param hunks     Parsed unified-diff hunks for structured display.
     *                  May be empty if hunk parsing fails — the diff viewer
     *                  falls back to a simple before/after comparison.
     */
    data class Modify(
        override val editId: String,
        override val filePath: String,
        val before: String,
        val after: String,
        val hunks: List<Hunk> = emptyList(),
    ) : ProposedEdit()

    /**
     * An edit that creates a new file.
     *
     * [before] is always an empty string so the diff viewer can show a pure
     * "addition" — everything in [after] is new.
     *
     * @param editId    Bridge-assigned unique ID.
     * @param filePath  Absolute path where the new file will be written.
     * @param after     Full content of the new file.
     */
    data class Create(
        override val editId: String,
        override val filePath: String,
        val after: String,
    ) : ProposedEdit() {
        val before: String get() = ""
    }

    /**
     * An edit that deletes an existing file.
     *
     * [after] is always an empty string so the diff viewer can show a pure
     * "deletion" — everything in [before] is being removed.
     *
     * @param editId    Bridge-assigned unique ID.
     * @param filePath  Absolute path to the file that will be deleted.
     * @param before    Full content of the file before deletion.
     */
    data class Delete(
        override val editId: String,
        override val filePath: String,
        val before: String,
    ) : ProposedEdit() {
        val after: String get() = ""
    }
}

// ── Hunk data ─────────────────────────────────────────────────────────────────

/**
 * A contiguous changed region within a [ProposedEdit.Modify].
 *
 * Mirrors a single unified-diff `@@ … @@` block. The diff viewer uses this to
 * scroll directly to each changed region rather than rendering the whole file.
 *
 * @param beforeStartLine  1-based start line in the [ProposedEdit.Modify.before] content.
 * @param beforeLineCount  Number of lines in the [before] side of this hunk.
 * @param afterStartLine   1-based start line in the [ProposedEdit.Modify.after] content.
 * @param afterLineCount   Number of lines in the [after] side of this hunk.
 * @param lines            Raw unified-diff lines for this hunk (context, +, −).
 */
data class Hunk(
    val beforeStartLine: Int,
    val beforeLineCount: Int,
    val afterStartLine: Int,
    val afterLineCount: Int,
    val lines: List<HunkLine>,
)

/**
 * A single line within a [Hunk].
 *
 * @param kind    Whether this line is context, added, or removed.
 * @param content The raw line text (without the leading `+`/`-`/` ` character).
 */
data class HunkLine(
    val kind: HunkLineKind,
    val content: String,
)

/**
 * The kind of a [HunkLine] within a unified-diff hunk.
 */
enum class HunkLineKind {
    /** Lines present in both before and after (context lines, no change). */
    CONTEXT,

    /** Lines added in the after version (`+` lines). */
    ADDED,

    /** Lines removed from the before version (`-` lines). */
    REMOVED,
}
