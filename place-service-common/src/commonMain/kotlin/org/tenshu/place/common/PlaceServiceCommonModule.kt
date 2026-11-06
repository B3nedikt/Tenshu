package org.tenshu.place.common

import org.tenshu.core.common.module.composite
import org.tenshu.core.common.module.service
import org.tenshu.core.common.module.tModule
import org.tenshu.core.common.module.trait
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.place.ExternalPlaceRef
import org.tenshu.core.place.Place
import org.tenshu.core.place.PlaceDataIdProvider
import org.tenshu.core.place.PlaceMerge
import org.tenshu.core.place.PlaceMergeDataIdProvider
import org.tenshu.core.place.PlaceNetworkFingerprint
import org.tenshu.core.place.PlaceNetworkFingerprintDataIdProvider
import org.tenshu.core.place.PlaceRole
import org.tenshu.core.place.PlaceRoleDataIdProvider
import org.tenshu.core.place.PlaceService
import org.tenshu.place.common.impl.ExternalPlaceRefDto
import org.tenshu.place.common.impl.PlaceDataIdProviderImpl
import org.tenshu.place.common.impl.PlaceDto
import org.tenshu.place.common.impl.PlaceMergeDataIdProviderImpl
import org.tenshu.place.common.impl.PlaceMergeDto
import org.tenshu.place.common.impl.PlaceNetworkFingerprintDataIdProviderImpl
import org.tenshu.place.common.impl.PlaceNetworkFingerprintDto
import org.tenshu.place.common.impl.PlaceRoleDataIdProviderImpl
import org.tenshu.place.common.impl.PlaceRoleDto

val placeServiceCommonModule = tModule {

    service(PlaceService.Schema)

    composite(
        interfaceClass = ExternalPlaceRef::class,
        dataSchema = ExternalPlaceRef.Schema,
        dtoFactory = ::ExternalPlaceRefDto
    )

    composite(
        interfaceClass = Place::class,
        dataSchema = Place.Schema,
        dtoFactory = ::PlaceDto
    )

    trait(
        dataSchema = Place.Schema,
        traitSchema = PlaceDataIdProvider.Schema,
        traitFactory = { context, schema ->
            PlaceDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )

    composite(
        interfaceClass = PlaceNetworkFingerprint::class,
        dataSchema = PlaceNetworkFingerprint.Schema,
        dtoFactory = ::PlaceNetworkFingerprintDto
    )

    trait(
        dataSchema = PlaceNetworkFingerprint.Schema,
        traitSchema = PlaceNetworkFingerprintDataIdProvider.Schema,
        traitFactory = { context, schema ->
            PlaceNetworkFingerprintDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )

    composite(
        interfaceClass = PlaceMerge::class,
        dataSchema = PlaceMerge.Schema,
        dtoFactory = ::PlaceMergeDto
    )

    trait(
        dataSchema = PlaceMerge.Schema,
        traitSchema = PlaceMergeDataIdProvider.Schema,
        traitFactory = { context, schema ->
            PlaceMergeDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )

    composite(
        interfaceClass = PlaceRole::class,
        dataSchema = PlaceRole.Schema,
        dtoFactory = ::PlaceRoleDto
    )

    trait(
        dataSchema = PlaceRole.Schema,
        traitSchema = PlaceRoleDataIdProvider.Schema,
        traitFactory = { context, schema ->
            PlaceRoleDataIdProviderImpl(context, schema as StringDataIdProviderSchema)
        }
    )
}
