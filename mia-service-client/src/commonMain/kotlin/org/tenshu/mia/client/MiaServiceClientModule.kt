package org.tenshu.mia.client

import org.tenshu.core.common.module.operation
import org.tenshu.core.common.module.service
import org.tenshu.core.common.module.tModule
import org.tenshu.mia.AskMia
import org.tenshu.mia.ConfirmMiaAction
import org.tenshu.mia.MiaService
import org.tenshu.mia.client.impl.AskMiaRo
import org.tenshu.mia.client.impl.ConfirmMiaActionRo
import org.tenshu.mia.client.impl.MiaServiceRo

/**
 * Client-side registration for Mia's query layer (`mia-query-layer.md` MQ-1).
 *
 * ⚠ Registering [AskMia] here is not optional even though the proxy holds the
 * implementation: the *client* builds the callstack, so without an RO factory the call
 * cannot be composed at all. The composites the op returns are registered in
 * `miaServiceCommonModule` — see its KDoc for why that split is load-bearing.
 */
val miaServiceClientModule = tModule {

    operation(
        interfaceClass = AskMia::class,
        protocolSchema = AskMia.Schema,
        roFactory = ::AskMiaRo,
    )

    operation(
        interfaceClass = ConfirmMiaAction::class,
        protocolSchema = ConfirmMiaAction.Schema,
        roFactory = ::ConfirmMiaActionRo,
    )

    service(
        interfaceClass = MiaService::class,
        serviceSchema = MiaService.Schema,
        roFactory = ::MiaServiceRo,
    )
}
