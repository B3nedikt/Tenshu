package org.tenshu.hub.management.common

import org.tenshu.core.common.module.composite
import org.tenshu.core.common.module.service
import org.tenshu.core.common.module.tModule
import org.tenshu.core.common.module.trait
import org.tenshu.core.common.proxy.StringDataIdProviderImpl
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.hub.management.HubCommand
import org.tenshu.hub.management.HubManagementService
import org.tenshu.hub.management.HubStatus
import org.tenshu.hub.management.common.impl.HubCommandDto
import org.tenshu.hub.management.common.impl.HubCommandRo
import org.tenshu.hub.management.common.impl.HubStatusDto
import org.tenshu.hub.management.common.impl.HubStatusRo

/**
 * Schema registration for the hub-management service (hub proposal §8,
 * as-built H4). Both composites are `Syncable(Eager)` NEW types — additive,
 * no existing row reshaped, so no `TENSHU_STORAGE_FORMAT_VERSION` bump
 * (the `MovementTrace` precedent).
 */
val hubManagementServiceCommonModule = tModule {

    composite(
        interfaceClass = HubStatus::class,
        dataSchema = HubStatus.Schema,
        dtoFactory = ::HubStatusDto,
        roFactory = { chain, ctx ->
            HubStatusRo(
                requestChain = chain,
                context = ctx,
                schema = HubStatus.Schema,
            )
        }
    )

    composite(
        interfaceClass = HubCommand::class,
        dataSchema = HubCommand.Schema,
        dtoFactory = ::HubCommandDto,
        roFactory = { chain, ctx ->
            HubCommandRo(
                requestChain = chain,
                context = ctx,
                schema = HubCommand.Schema,
            )
        }
    )

    service(HubManagementService.Schema)

    trait(
        dataSchema = HubStatus.Schema,
        traitSchema = HubStatus.DataIdProviderSchema,
        traitFactory = { context, schema ->
            StringDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )

    trait(
        dataSchema = HubCommand.Schema,
        traitSchema = HubCommand.DataIdProviderSchema,
        traitFactory = { context, schema ->
            StringDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )

}
