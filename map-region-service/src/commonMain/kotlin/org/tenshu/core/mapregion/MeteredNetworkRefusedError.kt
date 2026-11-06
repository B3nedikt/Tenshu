package org.tenshu.core.mapregion

import org.tenshu.core.TError
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string
import org.tenshu.core.toTString

/**
 * [DownloadRegion] refused to start because the device is on a **metered** network and the caller did
 * not pass `allowMetered` (`maps-region-delivery.md` D5 / §7.1 track 3g).
 *
 * ⚠⚠ **Typed, not a generic failure, because the caller has to be able to offer the override.** A
 * region is hundreds of megabytes; the honest response to "you're on mobile data" is to say so and let
 * the user decide, and a client cannot distinguish that from a dead connection if both arrive as the
 * same opaque error. This is the difference between *"Couldn't download that region"* and *"You're on
 * mobile data — Bayern is 400 MB. Download anyway?"*.
 *
 * Carries [regionId] and nothing else. A caller downloading **several** regions (Timeline's "keep this
 * trip's maps offline" spans a route) needs to know *which* one stopped — but it already rendered the
 * row, so it knows the size and the name. Restating those here would mean threading the catalog into
 * the executor to fill fields the caller can already answer.
 */
interface MeteredNetworkRefusedError : TError {

    override val schema: CompositeSchema get() = Schema

    val regionId: TString
        get() = getField(Fields.REGION_ID)

    companion object {
        const val ID = "${MapRegionService.ID}/error/meteredNetworkRefused"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = TError.Schema,
            fields = {
                string(Fields.REGION_ID)
            },
        )

        fun create(
            context: TContext,
            regionId: String,
        ): MeteredNetworkRefusedError = context.create(
            type = ID,
            fields = listOf(regionId.toTString(context)),
        )
    }

    object Fields {
        const val REGION_ID = "$ID/regionId"
    }
}
