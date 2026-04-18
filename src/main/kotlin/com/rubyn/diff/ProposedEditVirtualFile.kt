package com.rubyn.diff

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileSystem
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

private val LOG = logger<ProposedEditVirtualFile>()

/**
 * An in-memory [VirtualFile] that backs a rubyn-proposed:// URI.
 *
 * Each [ProposedEdit] gets its own instance of this class so that IntelliJ's
 * diff infrastructure has a concrete [VirtualFile] to work with when building
 * [com.intellij.diff.contents.DocumentDiffContent] instances.
 *
 * The content exposed here is read-only from the diff viewer's perspective —
 * actual disk writes go through [RubynDiffManager.acceptEdit] via a
 * [WriteCommandAction], not through the VFS write path.
 *
 * @param edit         The proposed change this file represents.
 * @param showAfter    When true, this file represents the "after" side (proposed
 *                     content). When false it represents the "before" side
 *                     (current on-disk content).
 * @param fileSystem   The owning [ProposedEditFileSystem].
 */
class ProposedEditVirtualFile(
    val edit: ProposedEdit,
    private val showAfter: Boolean,
    private val fileSystem: ProposedEditFileSystem,
) : VirtualFile() {

    // ── Content ───────────────────────────────────────────────────────────

    private val rawContent: String by lazy {
        when (edit) {
            is ProposedEdit.Modify -> if (showAfter) edit.after else edit.before
            is ProposedEdit.Create -> if (showAfter) edit.after else edit.before
            is ProposedEdit.Delete -> if (showAfter) edit.after else edit.before
        }
    }

    // ── VirtualFile contract ──────────────────────────────────────────────

    override fun getName(): String {
        val base = java.io.File(edit.filePath).name
        val side = if (showAfter) "after" else "before"
        return "$base.$side.rubyn"
    }

    override fun getFileSystem(): VirtualFileSystem = fileSystem

    override fun getPath(): String {
        val side = if (showAfter) "after" else "before"
        return "rubyn-proposed://${edit.editId}/$side/${edit.filePath}"
    }

    override fun isWritable(): Boolean = false

    override fun isDirectory(): Boolean = false

    override fun isValid(): Boolean = true

    override fun getParent(): VirtualFile? = null

    override fun getChildren(): Array<VirtualFile> = VirtualFile.EMPTY_ARRAY

    override fun getOutputStream(requestor: Any?, newModificationStamp: Long, newTimeStamp: Long): OutputStream {
        // Read-only — writes are rejected.
        throw UnsupportedOperationException("ProposedEditVirtualFile is read-only")
    }

    override fun contentsToByteArray(): ByteArray =
        rawContent.toByteArray(StandardCharsets.UTF_8)

    override fun getTimeStamp(): Long = 0L

    override fun getLength(): Long = rawContent.toByteArray(StandardCharsets.UTF_8).size.toLong()

    override fun refresh(asynchronous: Boolean, recursive: Boolean, postRunnable: Runnable?) {
        postRunnable?.run()
    }

    override fun getInputStream(): InputStream =
        ByteArrayInputStream(rawContent.toByteArray(StandardCharsets.UTF_8))
}
