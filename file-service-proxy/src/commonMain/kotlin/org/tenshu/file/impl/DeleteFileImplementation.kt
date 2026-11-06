package org.tenshu.file.impl

import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.optionalFieldValue
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.getDataIdHash
import org.tenshu.core.file.File
import org.tenshu.core.file.Folder
import org.tenshu.core.file.notifications.FileDeletedNotification
import org.tenshu.core.file.notifications.FolderDeletedNotification
import org.tenshu.core.file.system.DeleteFile
import org.tenshu.core.notifications.Notification
import org.tenshu.core.notifications.NotificationService
import org.tenshu.core.schema.isSubtypeOf
import org.tenshu.core.time.clock
import org.tenshu.sync.common.TombstoneEmitter
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

internal class DeleteFileImplementation(
    private val fileDatabase: FileDatabase,
    private val tombstoneEmitter: TombstoneEmitter? = null,
) : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val path: TString = params.read(DeleteFile.Params.PATH)

        val parentPath = path.get()
            .substringBeforeLast("/")
            .takeIf { it.isNotEmpty() }
            ?: "/"

        val parentDataId = DataId.StringDataId.create(
            context = executionContext.context,
            type = File.ID,
            keyValue = parentPath
        )

        val arrayDataId = DataId.EntityFieldDataId.create(
            context = executionContext.context,
            type = TList.ID,
            parentEntity = parentDataId as DataId.EntityDataId,
            fieldRef = Folder.Fields.FILES
        )

        val dbRef = ArrayDatabaseReference(
            arrayId = arrayDataId,
            isEntity = false,
            elementSchemaId = File.ID
        )

        // 1. Remove from parent array
        val targetDataId = DataId.StringDataId.create(
            context = executionContext.context,
            type = File.ID,
            keyValue = path.get()
        )

        executionContext.database.removeFromArray(
            reference = dbRef,
            dataId = targetDataId as DataId.EntityDataId,
        )

        // 2. Perform recursive deletion from database — entities AND their bytes.
        //
        // ⚠ CR-2 B6: the walk no longer collects the content it orphans. The relay frees content on
        // its own clock once no edge names it (B5's sweep), and this tombstone's cascade is what
        // removes the edges — so no device records, decides or names bytes to free.
        val removedEntities = mutableListOf<DataId>()
        val wasFolder = deleteRecursively(executionContext, targetDataId, removedEntities)

        // 3. Backstop for the top-level path: `deleteRecursively` deletes the bytes
        // of every *entity* it walks, so this only matters when the entity is
        // already gone from the DB but its plaintext isn't. `deleteFile` is a no-op
        // when there is nothing there.
        fileDatabase.deleteFile(path.get())

        // 4. Notify. `Notification` is `Syncable`; saving via `ExecutionContext.saveData`
        // fires `SyncObserver` (outbound block) and `NotificationRelationMaintainer`
        // (local relation membership). The prior `addDataToArray` path bypassed
        // `SyncObserver` so notifications never propagated to other realm devices.
        //
        // A folder gets `FolderDeletedNotification` — a registered subtype of
        // `FileDeletedNotification` with the same fields, so no stored or wire shape changes and a
        // peer that matches only `FileDeletedNotification` still reads it — and Control's feed
        // titles it "Folder Deleted" instead of "File Deleted".
        val now = executionContext.context.clock().nowEpochMillis()
        @OptIn(ExperimentalUuidApi::class)
        val uuid = Uuid.random().toString()
        executionContext.saveData(
            if (wasFolder) {
                FolderDeletedNotification.create(
                    context = executionContext.context,
                    uuid = uuid,
                    timestamp = now,
                    path = path.get()
                )
            } else {
                FileDeletedNotification.create(
                    context = executionContext.context,
                    uuid = uuid,
                    timestamp = now,
                    path = path.get()
                )
            }
        )

        // Emit a tombstone for the deleted file. Receivers short-circuit any merge for this
        // entity at HLC ≤ tombstone.hlc; see `docs/adr/0006-sync-tombstones.md`
        // Recursive child deletions stay local-only — one tombstone per delete *operation*,
        // not per cascaded entity.
        // ⚠⚠ …but the content plane's undeclare follows tombstones, so the entities the cascade
        // removed are named with this one, in the same call, or their edges stand for ever (§9.14).
        tombstoneEmitter?.emitWithCascade(
            targetEntityId = targetDataId,
            targetEntityType = File.ID,
            cascadedEntityIds = removedEntities.filter { it != targetDataId },
        )

        return null as T
    }

    /** @return whether [dataId] was a folder — the caller names the notification by it. */
    private fun deleteRecursively(
        executionContext: ExecutionContext,
        dataId: DataId,
        removedEntities: MutableList<DataId>,
    ): Boolean {
        val data = executionContext.database.getData(dataId, asEntity = true)
        val isFolder = data is Entity<*> && executionContext.schemaProvider.getSchema(data.type.get())
            ?.isSubtypeOf(Folder.ID) == true
        if (isFolder) {
            @Suppress("UNCHECKED_CAST")
            val folderEntity = data as Entity<Folder>
            val filesField = folderEntity.getFieldValue<Data>(Folder.Fields.FILES)
            val filesSequence = executionContext.resolveList(filesField) ?: emptySequence()

            filesSequence.forEach { child ->
                val childId = executionContext.getDataId(child)
                if (childId != null) {
                    deleteRecursively(executionContext, childId, removedEntities)
                }
            }

            // Delete the files array object as well
            val arrayDataId = DataId.EntityFieldDataId.create(
                context = executionContext.context,
                type = TList.ID,
                parentEntity = dataId as DataId.EntityDataId,
                fieldRef = Folder.Fields.FILES
            )
            executionContext.deleteData(arrayDataId)
        } else if (data is Entity<*>) {
            // A leaf file: drop its plaintext too. Deleting the entity alone left
            // every descendant's bytes on disk forever — and bytes, not entities,
            // are what fills a device up.
            runCatching { data.optionalFieldValue<TString>(File.Field.PATH)?.get() }
                .getOrNull()
                ?.let { fileDatabase.deleteFile(it) }
        }
        executionContext.deleteData(dataId)
        removedEntities.add(dataId)
        return isFolder
    }
}
