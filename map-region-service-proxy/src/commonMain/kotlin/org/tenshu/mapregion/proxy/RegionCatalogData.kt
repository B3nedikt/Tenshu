package org.tenshu.mapregion.proxy

import kotlinx.serialization.Serializable

/**
 * Proxy-internal plain data for the region catalog and the local install index. These are the
 * shapes the [RegionCatalogSource] (fetch + verify + parse) and [RegionStore] (local index)
 * seams speak; the operation implementations map them to the wire composites ([RegionCatalog]
 * etc). Kept as plain data classes — they never cross the IPC wire, so House Rule #4 (schema-
 * first) does not apply; the wire types in `:map-region-service` are the schema-first surface.
 *
 * [InstalledRegionData] is `@Serializable` because it doubles as the persisted install-index
 * record (`DefaultRegionStore` JSON-encodes the list into `SecureStorageDelegate`).
 */
data class RegionCatalogData(
    val schemaVersion: Int,
    val buildDate: String,
    val regions: List<RegionEntryData>,
)

data class RegionEntryData(
    val regionId: String,
    val name: String,
    val coverage: String,
    val byteSize: Long,
    val sha256: String,
    val tileSchemaVersion: Int,
    val path: String,
    /**
     * The region's transit-line overlay (§33 L3), or blank when it has none.
     *
     * ⚠⚠ No signature of its own: `catalog.json` is signed as bytes, so [overlaySha256] sitting
     * inside it is already covered by the same chain that protects a tile bundle.
     *
     * ⚠ Blank is the ordinary case — plenty of regions have no rail — so a consumer must treat
     * absence as "this region cannot name a line", never as an error.
     */
    val overlayPath: String = "",
    val overlayByteSize: Long = 0,
    val overlaySha256: String = "",
    /** `TransitLineIndex.FORMAT_VERSION` the artefact was written at; `0` when there is none. */
    val overlayFormatVersion: Int = 0,
)

@Serializable
data class InstalledRegionData(
    val regionId: String,
    val name: String,
    val localPath: String,
    val byteSize: Long,
    val sha256: String,
    val buildDate: String,
)
