package org.tenshu.mapregion.proxy

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Parses the `catalog.json` document (`maps-region-delivery.md` D3) into [RegionCatalogData].
 * The JSON is the public wire shape served by the CDN; kept separate from the schema-DSL wire
 * types so the on-disk catalog format can evolve independently of the IPC surface. Unknown keys
 * are ignored so a newer catalog (extra fields) still parses on an older client.
 */
private val catalogJson = Json { ignoreUnknownKeys = true }

internal fun parseRegionCatalog(json: String): RegionCatalogData {
    val doc = catalogJson.decodeFromString(CatalogJson.serializer(), json)
    return RegionCatalogData(
        schemaVersion = doc.schemaVersion,
        buildDate = doc.buildDate,
        regions = doc.regions.map { region ->
            RegionEntryData(
                regionId = region.id,
                name = region.name,
                coverage = region.coverage,
                byteSize = region.byteSize,
                sha256 = region.sha256,
                tileSchemaVersion = region.tileSchemaVersion,
                path = region.path,
                overlayPath = region.overlayPath,
                overlayByteSize = region.overlayByteSize,
                overlaySha256 = region.overlaySha256,
                overlayFormatVersion = region.overlayFormatVersion,
            )
        },
    )
}

@Serializable
private data class CatalogJson(
    @SerialName("schemaVersion") val schemaVersion: Int,
    @SerialName("buildDate") val buildDate: String,
    @SerialName("regions") val regions: List<RegionJson> = emptyList(),
)

@Serializable
private data class RegionJson(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String,
    @SerialName("coverage") val coverage: String = "",
    @SerialName("byteSize") val byteSize: Long,
    @SerialName("sha256") val sha256: String,
    @SerialName("tileSchemaVersion") val tileSchemaVersion: Int,
    @SerialName("path") val path: String,
    // §33 L3. ⚠ Defaulted, so a catalogue published before overlays existed still parses — which is
    // the half `ignoreUnknownKeys` does NOT cover (it handles new fields on an old client, not
    // missing fields on a new one).
    @SerialName("overlayPath") val overlayPath: String = "",
    @SerialName("overlayByteSize") val overlayByteSize: Long = 0,
    @SerialName("overlaySha256") val overlaySha256: String = "",
    @SerialName("overlayFormatVersion") val overlayFormatVersion: Int = 0,
)
