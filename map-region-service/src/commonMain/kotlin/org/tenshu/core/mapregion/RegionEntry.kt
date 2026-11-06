package org.tenshu.core.mapregion

import org.tenshu.core.Composite
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.number.TInteger
import org.tenshu.core.number.toTInteger
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.int
import org.tenshu.core.schema.string
import org.tenshu.core.toTString

/**
 * One downloadable offline basemap region, as advertised by the signed catalog
 * (`docs/proposals/maps-region-delivery.md` D3). This is *catalog metadata* the app renders as a
 * region-picker row — it is **not** a synced entity: the catalog is fetched fresh + verified
 * proxy-side per [Catalog] call (there is no per-device convergence to do, and gating it buys
 * nothing, D3).
 *
 * The fields mirror `catalog.json`'s per-region shape: our stable [regionId], a display
 * [name], a coarse [coverage] polygon string (for "which region am I looking at" UX, not
 * rendering), the [byteSize] shown honestly in the picker, the [sha256] the proxy verifies the
 * downloaded bundle against (R4), the [tileSchemaVersion] the client filters on so a schema-
 * bumping build isn't picked up by a style that can't render it (D3, load-bearing), and the
 * storage [path] of the immutable dated build.
 *
 * **[byteSize] is a Long-backed [TInteger]** — the DSL has no separate `long` kind (`int` maps
 * to `TInteger`, which is Long-backed); convert at the edge via `Long.toTInteger` / `.get()`.
 */
interface RegionEntry : Composite {

    override val schema: CompositeSchema get() = Schema

    /** Our stable region id (e.g. `de-bayern`). */
    val regionId: TString
        get() = getField(Fields.REGION_ID)

    /** Human-readable name for the picker. */
    val name: TString
        get() = getField(Fields.NAME)

    /** Coarse coverage polygon (GeoJSON/bbox string) — for UX framing, not rendering. */
    val coverage: TString
        get() = getField(Fields.COVERAGE)

    /** Bundle size in bytes (Long-backed). */
    val byteSize: TInteger
        get() = getField(Fields.BYTE_SIZE)

    /** Hex sha256 of the bundle — verified after download (R4). */
    val sha256: TString
        get() = getField(Fields.SHA256)

    /** Protomaps tile schema version; the client filters `<= supported` (D3). */
    val tileSchemaVersion: TInteger
        get() = getField(Fields.TILE_SCHEMA_VERSION)

    /** Storage path of the immutable dated build (e.g. `regions/2026-07/de-bayern.pmtiles`). */
    val path: TString
        get() = getField(Fields.PATH)

    companion object {
        const val ID = "tenshu/mapRegion/regionEntry"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            fields = {
                string(Fields.REGION_ID)
                string(Fields.NAME)
                string(Fields.COVERAGE)
                int(Fields.BYTE_SIZE)
                string(Fields.SHA256)
                int(Fields.TILE_SCHEMA_VERSION)
                string(Fields.PATH)
            }
        )

        fun create(
            context: TContext,
            regionId: String,
            name: String,
            coverage: String,
            byteSize: Long,
            sha256: String,
            tileSchemaVersion: Int,
            path: String,
        ): RegionEntry = context.create(
            type = ID,
            fields = listOf(
                regionId.toTString(context),
                name.toTString(context),
                coverage.toTString(context),
                byteSize.toTInteger(context),
                sha256.toTString(context),
                tileSchemaVersion.toLong().toTInteger(context),
                path.toTString(context),
            )
        )
    }

    object Fields {
        const val REGION_ID = "$ID/regionId"
        const val NAME = "$ID/name"
        const val COVERAGE = "$ID/coverage"
        const val BYTE_SIZE = "$ID/byteSize"
        const val SHA256 = "$ID/sha256"
        const val TILE_SCHEMA_VERSION = "$ID/tileSchemaVersion"
        const val PATH = "$ID/path"
    }
}
