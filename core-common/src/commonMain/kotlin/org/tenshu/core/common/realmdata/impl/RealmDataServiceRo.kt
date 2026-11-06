package org.tenshu.core.common.realmdata.impl

import org.tenshu.core.common.impl.ServiceRo
import org.tenshu.core.core.TComponent
import org.tenshu.core.core.TContext
import org.tenshu.core.realmdata.RealmDataService
import org.tenshu.core.request.RequestChain

/**
 * Client handle for [RealmDataService]. The service declares no relations
 * (see its KDoc — it is a verb receiver), so this is the bare [ServiceRo]:
 * the only thing reached through it is `findOperation(DeleteRealmData.ID)`.
 */
internal class RealmDataServiceRo(
    override val requestChain: RequestChain,
    context: TContext,
) : RealmDataService, ServiceRo(requestChain, context) {

    override val components: Map<String, TComponent>
        get() = context.components
}
