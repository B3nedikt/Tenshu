package org.tenshu.core.mapregion

import org.tenshu.core.Composite
import org.tenshu.core.TString
import org.tenshu.core.collection.list.TList
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.number.TInteger
import org.tenshu.core.number.toTInteger
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.int
import org.tenshu.core.schema.list
import org.tenshu.core.schema.string
import org.tenshu.core.toTString

/**
 * The return value of [Catalog]: the verified, schema-filtered region listing the picker
 * renders (`docs/proposals/maps-region-delivery.md` D3). The proxy fetches `catalog.json` +
 * its detached signature, verifies the signature against the pinned release key, filters the
 * regions to those whose [RegionEntry.tileSchemaVersion] the client's style can render, and
 * returns this composite — so the app never sees the raw JSON or an unverified entry.
 *
 * [catalogSchemaVersion]/[buildDate] are catalog-level metadata; [regions] is the embedded
 * list of [RegionEntry] (a `list(of = …)` field, materialised over the wire like `Folder.files`).
 */
interface RegionCatalog : Composite {

    override val schema: CompositeSchema get() = Schema

    /** The catalog document's own schema version. */
    val catalogSchemaVersion: TInteger
        get() = getField(Fields.CATALOG_SCHEMA_VERSION)

    /** The build stamp this catalog points at (e.g. `2026-07`). */
    val buildDate: TString
        get() = getField(Fields.BUILD_DATE)

    /** The advertised regions, already filtered to renderable tile-schema versions. */
    val regions: TList<RegionEntry>
        get() = getField(Fields.REGIONS)

    companion object {
        const val ID = "tenshu/mapRegion/regionCatalog"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            fields = {
                int(Fields.CATALOG_SCHEMA_VERSION)
                string(Fields.BUILD_DATE)
                list(id = Fields.REGIONS, of = RegionEntry.Schema)
            }
        )

        fun create(
            context: TContext,
            catalogSchemaVersion: Int,
            buildDate: String,
            regions: List<RegionEntry>,
        ): RegionCatalog = context.create(
            type = ID,
            fields = listOf(
                catalogSchemaVersion.toLong().toTInteger(context),
                buildDate.toTString(context),
                regions,
            )
        )
    }

    object Fields {
        const val CATALOG_SCHEMA_VERSION = "$ID/catalogSchemaVersion"
        const val BUILD_DATE = "$ID/buildDate"
        const val REGIONS = "$ID/regions"
    }
}
