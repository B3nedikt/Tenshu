package org.tenshu.file.impl

import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.TenshuException
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.CreatedAt
import org.tenshu.core.file.CreatedFileModification
import org.tenshu.core.file.File
import org.tenshu.core.file.Folder
import org.tenshu.core.file.LastModifiedAt
import org.tenshu.core.file.Size
import org.tenshu.core.file.notifications.FolderAddedNotification
import org.tenshu.core.file.system.AlreadyExistsError
import org.tenshu.core.file.system.CreateFolder
import org.tenshu.core.notifications.Notification
import org.tenshu.core.notifications.NotificationService
import org.tenshu.core.time.clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

internal class CreateFolderImplementation(
    private val deviceId: String? = null
) : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val path: TString = params.read(CreateFolder.Params.PATH)

        val result = createFolder(executionContext, path.get())
        if (result is TResult.Failure<*>) {
            throw TenshuException(result.error)
        }
        return (result as TResult.Success<*>).value as T
    }

    internal fun createFolder(executionContext: ExecutionContext, path: String): TResult<Data?> {

        val dataId = DataId.StringDataId.create(
            context = executionContext.context,
            type = File.ID,
            keyValue = path
        )
        if (executionContext.database.getData(dataId) != null) {
            throw TenshuException(
                AlreadyExistsError.create(
                    context = executionContext.context,
                    message = "Folder already exists at $path"
                )
            )
        }

        // …and somewhere to hang it. Without this, `/Photos/a/b` with no `/Photos/a` wrote a
        // folder that no listing could show (see `requireParentFolder`).
        executionContext.requireParentFolder(path)

        val now = executionContext.context.clock().nowEpochMillis()
        val folderEntity = Entity.create<Folder>(
            context = executionContext.context,
            composite = Folder.create(executionContext.context, path, files = emptyList()),
        ) {
            set(File.Size, 0L)
            set(File.LastModifiedAt, now)
            set(File.CreatedAt, now)
        }

        // Single `executionContext.saveData` is sufficient — both the local
        // parent-folder `FILES` membership (via `ParentFilesRelationMaintainer`)
        // and the outbound `SyncBlock` (via `SyncObserver`) are derived from
        // this one observer-triggering save. The previous explicit
        // `addDataToArray` was a workaround for the case where observers
        // didn't fire on `ProxyGateway`-wrapped contexts; that's fixed at
        // the `ExecutionContext` layer (shared `observerRegistry`), so the
        // workaround now produces duplicate entries.
        executionContext.saveData(folderEntity)

        // `Notification` is `Syncable`; `executionContext.saveData` fires
        // `SyncObserver` (outbound block) and `NotificationRelationMaintainer`
        // (local relation membership) symmetrically on host + joiner. The
        // earlier `addDataToArray` path went through `Database.saveData`,
        // bypassing `SyncObserver` so notifications never propagated.
        @OptIn(ExperimentalUuidApi::class)
        executionContext.saveData(
            FolderAddedNotification.create(
                context = executionContext.context,
                uuid = Uuid.random().toString(),
                timestamp = now,
                path = path
            )
        )

        // Emit-side history hook (docs/proposals/file-service-history.md §4.1):
        // a folder is a File-typed entity; its first record is a Created event.
        // Co-emitted in the same drain as the folder's own SyncBlock. No-op when
        // deviceId is absent (test harnesses / pre-sync wirings).
        if (deviceId != null) {
            executionContext.saveData(
                CreatedFileModification.create(
                    context = executionContext.context,
                    fileId = path,
                    timestamp = now,
                    deviceId = deviceId,
                )
            )
        }

        return TResult.Success.create<Data?>(context = executionContext.context, value = null)
    }
}
