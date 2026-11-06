package org.tenshu.core.place

import org.tenshu.core.Service
import org.tenshu.core.collection.list.TList
import org.tenshu.core.schema.ServiceSchema
import org.tenshu.core.schema.service
import org.tenshu.core.schema.serviceDataRelation
import org.tenshu.core.schema.typeOf

/**
 * The Place service (`docs/proposals/timeline-services.md` §3) — the store of canonical
 * [Place] POIs. Reusable beyond Timeline (anything that records "where"); geocoding /
 * cross-provider enrichment is a per-platform capability behind a seam (§3.3), not part
 * of this synced surface.
 */
interface PlaceService : Service {

    override val schema: ServiceSchema get() = Schema

    /** Every known place. */
    val places: TList<Place>

    /**
     * Every known network → place binding (§9.3). Consumed by Wi-Fi place recognition, which
     * hydrates an in-memory map from it — the capture-time lookup is synchronous and cannot read a
     * service (§9.3).
     */
    val networkFingerprints: TList<PlaceNetworkFingerprint>

    /**
     * Every recorded place merge (§9.4) — "this place is really that one". Read alongside [places]
     * so the projector can resolve each row to its surviving root.
     */
    val merges: TList<PlaceMerge>

    /**
     * Every recorded place role — "this one is home, that one is work". Read alongside [places] by
     * anything that wants to treat the user's own anchors differently from the places they merely
     * passed through.
     */
    val roles: TList<PlaceRole>

    companion object {
        const val ID = "tenshu/place/service"

        // `order` opts each relation into generic maintenance
        // (`core-relation-system.md` §9.2.1) — the proxy derives the element type,
        // the array key and the element's own key from what is already here, so
        // `:place-service-proxy` registers no observer of its own.
        val Schema: ServiceSchema = service(
            id = ID,
            // v1 is the release baseline (`core-schema-evolution.md` Appendix A).
            // ⚠ `Place` rides as an op parameter and so cannot gain a field
            // (`SchemaMigrationRegistry.verify`); see `PlaceMerge` / `PlaceRole`.
            version = 1,
            relations = listOf(
                serviceDataRelation(
                    id = Relations.PLACES,
                    type = TList.typeOf(Place.Schema),
                    maintained = true,
                ),
                serviceDataRelation(
                    id = Relations.NETWORK_FINGERPRINTS,
                    type = TList.typeOf(PlaceNetworkFingerprint.Schema),
                    maintained = true,
                ),
                serviceDataRelation(
                    id = Relations.MERGES,
                    type = TList.typeOf(PlaceMerge.Schema),
                    maintained = true,
                ),
                serviceDataRelation(
                    id = Relations.ROLES,
                    type = TList.typeOf(PlaceRole.Schema),
                    maintained = true,
                ),
            )
        )
    }

    object Relations {
        const val PLACES = "$ID/places"
        const val NETWORK_FINGERPRINTS = "$ID/networkFingerprints"
        const val MERGES = "$ID/merges"
        const val ROLES = "$ID/roles"
    }
}
