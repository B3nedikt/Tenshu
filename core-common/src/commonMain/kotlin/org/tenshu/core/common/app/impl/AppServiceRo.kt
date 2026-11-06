package org.tenshu.core.common.app.impl

import org.tenshu.core.app.AppGrant
import org.tenshu.core.app.AppRegistration
import org.tenshu.core.app.AppService
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.impl.ServiceRo
import org.tenshu.core.core.TComponent
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain

internal class AppServiceRo(
    override val requestChain: RequestChain,
    context: TContext,
) : AppService, ServiceRo(requestChain, context) {

    override val components: Map<String, TComponent>
        get() = context.components

    override val appRegistrations: TList<AppRegistration>
        get() = serviceRelation(AppService.Relations.APP_REGISTRATIONS, AppRegistration.Schema)

    override val appGrants: TList<AppGrant>
        get() = serviceRelation(AppService.Relations.APP_GRANTS, AppGrant.Schema)
}
