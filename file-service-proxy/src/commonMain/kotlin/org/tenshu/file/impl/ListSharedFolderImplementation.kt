package org.tenshu.file.impl

import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.collection.list.SimpleTList
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.entity.DataId
import org.tenshu.core.file.shared.SharedFile
import org.tenshu.core.file.system.ListSharedFolder

/**
 * Phase 3c of `docs/proposals/drive-shared-realm-file-model.md`: lists the children
 * of folder `parentFileId` in shared realm `realmId` — the per-parent `SharedFile`
 * array the `SharedFileRelationMaintainer` derives, keyed on the parent's DataId
 * (an `EntityPropertyDataId` under `SharedFile.CHILDREN_RELATION_ID`). The browse
 * UI drills by passing a child folder's `fileId` as the next `parentFileId`
 * (`SharedFile.ROOT_PARENT` is the realm root).
 */
internal class ListSharedFolderImplementation : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val realmId: TString = params.read(ListSharedFolder.Params.REALM_ID)
        val parentFileId: TString = params.read(ListSharedFolder.Params.PARENT_FILE_ID)

        val childrenRef = ArrayDatabaseReference(
            arrayId = DataId.EntityPropertyDataId.create(
                context = executionContext.context,
                type = TList.ID,
                parentEntity = DataId.StringDataId.create(
                    context = executionContext.context,
                    type = SharedFile.ID,
                    keyValue = SharedFile.compositeKey(realmId.get(), parentFileId.get()),
                ),
                propertyId = SharedFile.CHILDREN_RELATION_ID,
            ),
            isEntity = false,
            elementSchemaId = SharedFile.ID,
        )
        val children = executionContext.getArrayData(childrenRef)?.toList() ?: emptyList()
        return SimpleTList(children) as T
    }
}
