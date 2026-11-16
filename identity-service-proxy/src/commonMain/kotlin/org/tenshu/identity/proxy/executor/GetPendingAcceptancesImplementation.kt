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
import org.tenshu.identity.OutboundAcceptance
import org.tenshu.identity.GetPendingAcceptances
import org.tenshu.identity.IdentityService

/** [GetPendingAcceptances]: this member's [OutboundAcceptance] rows. */
internal class GetPendingAcceptancesImplementation : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        val rows = executionContext
            .getArrayData(executionContext.relationReference(IdentityService.Relations.OUTBOUND_ACCEPTANCES))
            ?.toList().orEmpty()
            .filterIsInstance<OutboundAcceptance>()
            // ⚠ The realm and the flag only: the mailbox address and the sealed bytes stay in the proxy — an app
            // holding the address could write into that mailbox slot.
            .map {
                OutboundAcceptance.create(
                    executionContext.context, it.realmId.get(), address = "", sealedAcceptance = ByteArray(0),
                    needsAccount = it.needsAccount.get(),
                )
            }
        return SimpleTList(rows) as T
    }
}
