package org.tenshu.identity.proxy.executor

import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.collection.list.SimpleTList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.relation.relationReference
import org.tenshu.identity.AwaitedAcceptance
import org.tenshu.identity.GetRealmInvitations
import org.tenshu.identity.IdentityService

/** [GetRealmInvitations]: this owner's [AwaitedAcceptance] rows for one realm. */
internal class GetRealmInvitationsImplementation : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        val realmId = (params?.get(GetRealmInvitations.Params.REALM_ID) as? TString)?.get()
            ?: error("GetRealmInvitations requires realmId")
        val rows = executionContext
            .getArrayData(executionContext.relationReference(IdentityService.Relations.AWAITED_ACCEPTANCES))
            ?.toList().orEmpty()
            .filter { (it as? AwaitedAcceptance)?.realmId?.get() == realmId }
        return SimpleTList(rows) as T
    }
}
