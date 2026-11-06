package org.tenshu.place.client.impl

import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.impl.ServiceRo
import org.tenshu.core.core.TComponent
import org.tenshu.core.core.TContext
import org.tenshu.core.place.Place
import org.tenshu.core.place.PlaceMerge
import org.tenshu.core.place.PlaceNetworkFingerprint
import org.tenshu.core.place.PlaceRole
import org.tenshu.core.place.PlaceService
import org.tenshu.core.request.RequestChain

internal class PlaceServiceRo(
    override val requestChain: RequestChain,
    context: TContext,
) : PlaceService, ServiceRo(requestChain, context) {

    override val components: Map<String, TComponent>
        get() = context.components

    override val places: TList<Place>
        get() = serviceRelation(PlaceService.Relations.PLACES, Place.Schema)

    override val networkFingerprints: TList<PlaceNetworkFingerprint>
        get() = serviceRelation(
            PlaceService.Relations.NETWORK_FINGERPRINTS,
            PlaceNetworkFingerprint.Schema,
        )

    override val merges: TList<PlaceMerge>
        get() = serviceRelation(PlaceService.Relations.MERGES, PlaceMerge.Schema)

    override val roles: TList<PlaceRole>
        get() = serviceRelation(PlaceService.Relations.ROLES, PlaceRole.Schema)
}
