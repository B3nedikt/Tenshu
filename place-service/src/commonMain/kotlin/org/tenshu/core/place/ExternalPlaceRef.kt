package org.tenshu.core.place

import org.tenshu.core.Composite
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.Entity
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string

/**
 * An **external reference** from a canonical [Place] to the same real-world place as
 * identified by an external provider (`docs/proposals/timeline-services.md` §3): one
 * `{provider, externalId}` pair — "this place, as known by provider X."
 *
 * The load-bearing rule: an external ref is *decoration*, never a key. [Place.placeId] is
 * the only primary key; refs are optional foreign keys we may store per the provider's
 * terms. Anchor on Overture **GERS** where available (it already bridges
 * OSM/Foursquare/Meta/Microsoft/Esri); Google `place_id` / Apple `MKMapItem` ids ride as
 * isolated foreign keys whose *content* stays in their walled surfaces (their ToS forbids
 * storing the human-readable place content, and forbids rendering it off their native
 * basemaps — see §3.1).
 *
 * Modelled as an embedded composite (not a `map<provider, id>`) so a ref can grow to carry
 * more than the id later (a match confidence, a captured-at) without a migration.
 */
interface ExternalPlaceRef : Composite {

    override val schema: CompositeSchema get() = Schema

    /** The provider namespace — `gers` / `osm` / `fsq` / `google` / `apple`. */
    val provider: TString
        get() = getField(Fields.PROVIDER)

    /** The provider-specific external id (opaque; interpreted per [provider]). */
    val externalId: TString
        get() = getField(Fields.EXTERNAL_ID)

    companion object {
        const val ID = "tenshu/place/externalRef"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            fields = {
                string(Fields.PROVIDER)
                string(Fields.EXTERNAL_ID)
            }
        )

        fun create(context: TContext, provider: String, externalId: String): ExternalPlaceRef =
            context.create(type = ID, fields = listOf(provider, externalId))
    }

    object Fields {
        const val PROVIDER = "$ID/provider"
        const val EXTERNAL_ID = "$ID/externalId"
    }
}

val Entity<out ExternalPlaceRef>.provider: TString
    get() = getFieldValue(ExternalPlaceRef.Fields.PROVIDER)

val Entity<out ExternalPlaceRef>.externalId: TString
    get() = getFieldValue(ExternalPlaceRef.Fields.EXTERNAL_ID)
