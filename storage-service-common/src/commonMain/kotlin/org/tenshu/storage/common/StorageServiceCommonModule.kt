package org.tenshu.storage.common

import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.impl.relation.create
import org.tenshu.core.common.module.composite
import org.tenshu.core.common.module.service
import org.tenshu.core.common.module.serviceDataRelation
import org.tenshu.core.common.module.tModule
import org.tenshu.core.common.module.trait
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.relation.ServiceDataRelation
import org.tenshu.core.schema.typeOf
import org.tenshu.storage.CapacityResult
import org.tenshu.storage.InsufficientStorageError
import org.tenshu.storage.NoStorageError
import org.tenshu.storage.StorageService
import org.tenshu.storage.StorageStatus
import org.tenshu.storage.StorageStatusDataIdProvider
import org.tenshu.storage.common.impl.CapacityResultDto
import org.tenshu.storage.common.impl.CapacityResultRo
import org.tenshu.storage.common.impl.InsufficientStorageErrorDto
import org.tenshu.storage.common.impl.NoStorageErrorDto
import org.tenshu.storage.common.impl.StorageStatusDataIdProviderImpl
import org.tenshu.storage.common.impl.StorageStatusDto
import org.tenshu.storage.common.impl.StorageStatusRo

val storageServiceCommonModule = tModule {

    composite(
        interfaceClass = StorageStatus::class,
        dataSchema = StorageStatus.Schema,
        dtoFactory = ::StorageStatusDto,
        roFactory = { chain, ctx ->
            StorageStatusRo(
                requestChain = chain,
                context = ctx,
                schema = StorageStatus.Schema,
            )
        },
    )

    // Tier-2 typed errors (storage-capacity-gating.md §3). Registered so they
    // cross the proxy↔client wire and dispatch structurally on the client.
    composite(
        interfaceClass = NoStorageError::class,
        dataSchema = NoStorageError.Schema,
        dtoFactory = ::NoStorageErrorDto,
    )
    composite(
        interfaceClass = InsufficientStorageError::class,
        dataSchema = InsufficientStorageError.Schema,
        dtoFactory = ::InsufficientStorageErrorDto,
    )

    // Tier-3 CheckCapacity op return type (storage-capacity-gating.md §2).
    composite(
        interfaceClass = CapacityResult::class,
        dataSchema = CapacityResult.Schema,
        dtoFactory = ::CapacityResultDto,
        roFactory = { chain, ctx ->
            CapacityResultRo(
                requestChain = chain,
                context = ctx,
                schema = CapacityResult.Schema,
            )
        },
    )

    service(StorageService.Schema)

    trait(
        dataSchema = StorageStatus.Schema,
        traitSchema = StorageStatusDataIdProvider.Schema,
        traitFactory = { context, schema ->
            StorageStatusDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        },
    )

    serviceDataRelation(
        fromId = StorageService.ID,
        targetId = StorageService.Relations.STATUSES,
        relation = ServiceDataRelation.create(
            from = StorageService.ID,
            to = StorageService.Relations.STATUSES,
            type = TList.typeOf(StorageStatus.Schema),
        ),
    )
}
