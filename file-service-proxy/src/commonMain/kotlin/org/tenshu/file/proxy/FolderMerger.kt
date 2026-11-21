package org.tenshu.file.proxy

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.common.proxy.WriteOrigin
import org.tenshu.core.common.util.compare
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.get
import org.tenshu.core.file.CreatedAt
import org.tenshu.core.file.File
import org.tenshu.core.file.Folder
import org.tenshu.core.file.LastModifiedAt
import org.tenshu.core.file.Size
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.sync.common.SemanticMerger
import org.tenshu.sync.common.SubtreeReapOrdering
import org.tenshu.sync.common.resolveListField

class FolderMerger(
    /**
     * Where a reaped leaf's plaintext lives ([reapMembersDeletedAt]). `null` leaves the bytes behind —
     * kept only for the merge-only callers (tests, the JS runtime, whose bytes are not in a
     * `FileDatabase`); every proxy passes its bound one, as `DeletedFolderSubtreeReaper` gets it.
     */
    private val fileDatabase: FileDatabase? = null,
) : SemanticMerger {
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

    /**
     * The folder was deleted at [point] and re-created after it: reap what it held at [point].
     *
     * Per member of the stored `FILES` list, by its version on this device ([SubtreeReapOrdering.versionOf]):
     *  - **≤ [point]** — written before the delete. Its reap point is recorded first (so a re-delivered
     *    block for it at or below [point] is refused for good), it leaves the list, and its row is
     *    deleted; for a subfolder, `DeletedFolderSubtreeReaper` then reaps its subtree at the same point.
     *  - **> [point]** — written after the delete: kept. A kept subfolder is walked the same way, since a
     *    re-created subfolder can still list members from before the delete.
     *  - **no version** (a placeholder: listed, but its own block not yet applied) — **kept**. ⚠⚠ Unlike
     *    a deleted folder's reap, a placeholder here is as likely a member of the *new* folder whose block
     *    has not arrived (a joiner backfills newest first, so a folder's latest listing precedes its
     *    children's blocks), and reaping it would hide a file created after the delete. If its block is
     *    from before the delete, the ingest gate refuses it by this folder's tombstone
     *    (`SyncProcessor.diedWithAContainer`).
     *
     * Reads and edits the list row in place, at the database level: the folder row itself is not
     * rewritten, so nothing is emitted (ingest-side work, like the rest of the tombstone path).
     */
    override fun reapMembersDeletedAt(
        executionContext: ExecutionContext,
        data: Data,
        point: HybridLogicalClock,
        ordering: SubtreeReapOrdering,
    ) {
        val folderId = executionContext.getDataId(data) as? DataId.EntityDataId ?: return
        reapUnder(executionContext, folderId, point, ordering)
    }

    private fun reapUnder(
        executionContext: ExecutionContext,
        folderId: DataId.EntityDataId,
        point: HybridLogicalClock,
        ordering: SubtreeReapOrdering,
    ) {
        val listRef = ArrayDatabaseReference(
            arrayId = DataId.EntityFieldDataId.create(
                context = executionContext.context,
                type = TList.ID,
                parentEntity = folderId,
                fieldRef = Folder.Fields.FILES,
            ),
            isEntity = false,
            elementSchemaId = File.ID,
        )
        // Snapshot: the loop below edits the list it walks. A leaf has no list row and ends here.
        val members = runCatching { executionContext.database.getArrayData(listRef)?.toList() }
            .getOrNull() ?: return
        members.forEach { member ->
            val memberId = executionContext.getDataId(member) as? DataId.StringDataId ?: return@forEach
            val version = ordering.versionOf(memberId)
            when {
                version == null -> Unit
                version.compare(point) > 0 -> reapUnder(executionContext, memberId, point, ordering)
                else -> {
                    ordering.recordReap(memberId, point)
                    // Ingest origin: this replays a peer's delete, and must not broadcast as a new edit.
                    executionContext.database.removeFromArray(
                        reference = listRef,
                        dataId = memberId,
                        origin = WriteOrigin.SYNC_INGEST,
                    )
                    // Bytes first, as `DeletedFolderSubtreeReaper` does: after the row is gone nothing
                    // else knows the path, and this delete is not ingest-origin, so no orphaned-bytes
                    // record is made either. A no-op for a folder (its subtree's leaves are the reaper's).
                    fileDatabase?.deleteFile(memberId.keyValue.get())
                    // Default origin, as `DeletedFolderSubtreeReaper` deletes a reaped member.
                    executionContext.deleteData(memberId)
                }
            }
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
