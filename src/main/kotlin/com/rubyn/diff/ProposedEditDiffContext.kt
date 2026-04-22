package com.rubyn.diff

import com.intellij.openapi.util.Key

/**
 * Holds the [Key] used to attach a [ProposedEdit] to a [com.intellij.diff.requests.SimpleDiffRequest].
 *
 * [RubynDiffManager] sets this key when it builds the request. The toolbar
 * actions ([AcceptEditAction], [RejectEditAction]) read it back from the
 * [com.intellij.diff.DiffContext] to know which edit they are operating on.
 */
object ProposedEditDiffContext {
    @JvmField
    val KEY: Key<ProposedEdit> = Key.create("rubyn.proposed_edit")
}
