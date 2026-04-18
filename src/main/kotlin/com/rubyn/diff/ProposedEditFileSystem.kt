package com.rubyn.diff

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileListener
import com.intellij.openapi.vfs.VirtualFileSystem

/**
 * Minimal read-only [VirtualFileSystem] for the `rubyn-proposed://` scheme.
 *
 * This VFS exists solely so that [ProposedEditVirtualFile] instances have a
 * valid [VirtualFileSystem] to reference. The actual diff content is rendered
 * from in-memory strings — no real filesystem operations are performed.
 *
 * Registered in plugin.xml as a `<vfsListener>` — or more precisely its
 * [ProposedEditVirtualFile] instances are created directly by [RubynDiffManager]
 * without going through a URI lookup, so this class is kept minimal.
 */
class ProposedEditFileSystem : VirtualFileSystem() {

    override fun getProtocol(): String = PROTOCOL

    override fun findFileByPath(path: String): VirtualFile? = null

    override fun refresh(asynchronous: Boolean) { /* no-op — in-memory only */ }

    override fun refreshAndFindFileByPath(path: String): VirtualFile? = null

    override fun addVirtualFileListener(listener: VirtualFileListener) { /* read-only */ }

    override fun removeVirtualFileListener(listener: VirtualFileListener) { /* read-only */ }

    override fun deleteFile(requestor: Any?, vFile: VirtualFile) {
        throw UnsupportedOperationException("ProposedEditFileSystem is read-only")
    }

    override fun moveFile(requestor: Any?, vFile: VirtualFile, newParent: VirtualFile) {
        throw UnsupportedOperationException("ProposedEditFileSystem is read-only")
    }

    override fun renameFile(requestor: Any?, vFile: VirtualFile, newName: String) {
        throw UnsupportedOperationException("ProposedEditFileSystem is read-only")
    }

    override fun createChildFile(requestor: Any?, vDir: VirtualFile, fileName: String): VirtualFile {
        throw UnsupportedOperationException("ProposedEditFileSystem is read-only")
    }

    override fun createChildDirectory(requestor: Any?, vDir: VirtualFile, dirName: String): VirtualFile {
        throw UnsupportedOperationException("ProposedEditFileSystem is read-only")
    }

    override fun copyFile(requestor: Any?, virtualFile: VirtualFile, newParent: VirtualFile, copyName: String): VirtualFile {
        throw UnsupportedOperationException("ProposedEditFileSystem is read-only")
    }

    override fun isReadOnly(): Boolean = true

    companion object {
        const val PROTOCOL = "rubyn-proposed"
    }
}
