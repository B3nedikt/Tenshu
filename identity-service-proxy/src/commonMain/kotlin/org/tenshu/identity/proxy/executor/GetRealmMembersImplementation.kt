package org.tenshu.identity.proxy.executor

import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.collection.list.SimpleTList
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.identity.GetRealmMembers
import org.tenshu.identity.IdentityService
import org.tenshu.identity.Member
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.relation.relationReference

/**
 * GR-3 `getRealmMembers(realmId)`: returns the in-realm [Member] roster for one shared
 * realm — the [IdentityService.Relations.MEMBERS] array (derived by
 * `MemberRelationMaintainer`) filtered to the requested `realmId`. Mirrors
 * `GetSharedRealmMembershipsImplementation`.
 */
internal class GetRealmMembersImplementation : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val realmId = (params?.get(GetRealmMembers.Params.REALM_ID) as? TString)?.get()
            ?: error("GetRealmMembers requires realmId")
        // Derived from the relation's declared spec — reader and writer can no
        // longer disagree about `isEntity` / `elementSchemaId` (§9.2 / D4).
        val membersRef = executionContext.relationReference(IdentityService.Relations.MEMBERS)
        val members = executionContext.getArrayData(membersRef)?.toList().orEmpty()
            .filter { (it as? Member)?.realmId?.get() == realmId }
        return SimpleTList(members) as T
    }
}
