package org.tenshu.identity.proxy.executor

import org.tenshu.core.Data
import org.tenshu.core.collection.list.SimpleTList
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.identity.IdentityService
import org.tenshu.identity.SharedRealmMembership
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.relation.relationReference

/**
 * Phase 3d.2b of `docs/proposals/drive-shared-realm-file-model.md`: returns the
 * member's flat collection of [SharedRealmMembership] rows — the realm-wide array
 * the `SharedRealmMembershipRelationMaintainer` derives, keyed by
 * `RelationDataId(TList.ID, IdentityService.Relations.SHARED_REALM_MEMBERSHIPS)`.
 * Mirrors `ListPlacementsImplementation`; the read surface for the Drive *Shared
 * with me* (inbound) section (callers split by `role`).
 */
internal class GetSharedRealmMembershipsImplementation : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        // Derived from the relation's declared spec (§9.2 / D4).
        val membershipsRef =
            executionContext.relationReference(IdentityService.Relations.SHARED_REALM_MEMBERSHIPS)
        val memberships = executionContext.getArrayData(membershipsRef)?.toList() ?: emptyList()
        return SimpleTList(memberships) as T
    }
}
