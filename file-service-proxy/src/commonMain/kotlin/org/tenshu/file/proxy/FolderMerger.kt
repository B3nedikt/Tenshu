package org.tenshu.file.proxy

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.get
import org.tenshu.core.file.CreatedAt
import org.tenshu.core.file.File
import org.tenshu.core.file.Folder
import org.tenshu.core.file.LastModifiedAt
import org.tenshu.core.file.Size
import org.tenshu.sync.common.SemanticMerger
import org.tenshu.sync.common.resolveListField

class FolderMerger : SemanticMerger {
    override val typeId: String = Folder.ID

    override fun merge(executionContext: ExecutionContext, existing: Data, incoming: Data): Data {
        val existingFiles = executionContext.resolveListField(existing, Folder.Fields.FILES)
            .filterIsInstance<File>()
        val incomingFiles = executionContext.resolveListField(incoming, Folder.Fields.FILES)
            .filterIsInstance<File>()

        val mergedFilesMap = mutableMapOf<String, File>()

        existingFiles.forEach { mergedFilesMap[getPath(it)] = it }
        incomingFiles.forEach { file ->
            val path = getPath(file)
            val existingFile = mergedFilesMap[path]
            if (existingFile == null) {
                mergedFilesMap[path] = file
            } else {
                if (getLastModified(file) > getLastModified(existingFile)) {
                    mergedFilesMap[path] = file
                }
            }
        }

        return Entity.create<Folder>(
            context = executionContext.context,
            composite = Folder.create(
                context = executionContext.context,
                path = getPath(incoming),
                files = mergedFilesMap.values.toList(),
            ),
        ) {
            set(File.Size, getFileSize(incoming))
            set(File.LastModifiedAt, maxOf(getLastModified(existing), getLastModified(incoming)))
            // createdAt is immutable: keep the EARLIEST creation time across the
            // two folder states (a later device re-creating the same folder must
            // not move the creation date forward). Absent on legacy folders.
            listOfNotNull(getCreatedAt(existing), getCreatedAt(incoming)).minOrNull()
                ?.let { set(File.CreatedAt, it) }
        }
    }

    /**
     * Drop children whose entity is dead (tombstoned and gone from the store) — the
     * removal semantics [merge]'s union cannot express. See
     * [SemanticMerger.purgeDeadMembers] for the failure this closes: without it a peer
     * that writes to a folder before it learns one of the folder's children was deleted
     * re-adds that child, and the row lists forever pointing at nothing.
     *
     * ⚠ A child's `DataId` is keyed under `File.ID` whatever its concrete type —
     * `Folder` is a subtype and `DeleteFileImplementation` tombstones both under
     * `File.ID`, so one lookup covers files and subfolders alike.
     *
     * Returns [data] untouched when nothing is dead, which is the overwhelmingly common
     * case: rebuilding is not free, and the rebuild keeps only the three value
     * properties [merge] itself keeps.
     */
    override fun purgeDeadMembers(
        executionContext: ExecutionContext,
        data: Data,
        isDead: (DataId) -> Boolean,
    ): Data {
        val files = executionContext.resolveListField(data, Folder.Fields.FILES)
            .filterIsInstance<File>()
        if (files.isEmpty()) return data

        val live = files.filterNot { child ->
            isDead(
                DataId.StringDataId.create(
                    context = executionContext.context,
                    type = File.ID,
                    keyValue = getPath(child),
                )
            )
        }
        if (live.size == files.size) return data

        return Entity.create<Folder>(
            context = executionContext.context,
            composite = Folder.create(
                context = executionContext.context,
                path = getPath(data),
                files = live,
            ),
        ) {
            set(File.Size, getFileSize(data))
            set(File.LastModifiedAt, getLastModified(data))
            getCreatedAt(data)?.let { set(File.CreatedAt, it) }
        }
    }

    private fun getPath(data: Data): String {
        return if (data is Entity<*>) {
            data.getFieldValue<TString>(File.Field.PATH).get()
        } else {
            (data as Composite).getField<TString>(File.Field.PATH).get()
        }
    }

    // size + lastModifiedAt are mutable value properties (§3.3), present only on
    // entities. A plain composite child (or unset property) falls back to 0,
    // which keeps the per-path conflict tie-break total — in production, folder
    // children are entities that carry the property.
    private fun getFileSize(data: Data): Long =
        (data as? Entity<*>)?.get(File.Size)?.get() ?: 0L

    private fun getLastModified(data: Data): Long =
        (data as? Entity<*>)?.get(File.LastModifiedAt)?.get() ?: 0L

    // Nullable (not 0-fallback like size/lastModified): absent createdAt must
    // not win the `minOrNull` tie-break against a real timestamp.
    private fun getCreatedAt(data: Data): Long? =
        (data as? Entity<*>)?.get(File.CreatedAt)?.get()
}
