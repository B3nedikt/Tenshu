package org.tenshu.file.impl

import org.tenshu.core.Data
import org.tenshu.core.collection.list.SimpleTList
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.file.FileService
import org.tenshu.core.file.placement.Placement
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.relation.relationReference

/**
 * Phase 3c.2b of `docs/proposals/drive-shared-realm-file-model.md`: returns the
 * member's flat collection of [Placement] mounts — the realm-wide array the
 * `PlacementRelationMaintainer` derives, keyed by
 * `RelationDataId(TList.ID, FileService.Relations.PLACEMENTS)`. The read surface for
 * the Drive *Shared* / *Shared with me* sections; section grouping (by
 * `SharedRealmMembership` role) is a UI concern (Phase 3d).
 */
internal class ListPlacementsImplementation : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        // Derived from the relation's declared spec (§9.2 / D4).
        val placementsRef = executionContext.relationReference(FileService.Relations.PLACEMENTS)
        val placements = executionContext.getArrayData(placementsRef)?.toList() ?: emptyList()
        return SimpleTList(placements) as T
    }
}
