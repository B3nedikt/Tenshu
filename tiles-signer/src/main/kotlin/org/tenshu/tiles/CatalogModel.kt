package org.tenshu.tiles

import kotlinx.serialization.Serializable

/**
 * The checked-in region-definition file (`deploy/tiles/regions.json`) — the human-authored input
 * to the pipeline. `bbox` is `[west, south, east, north]` (the `pmtiles extract` clip). Borrowed
 * boundary set per D7.
 */
@Serializable
data class RegionDefinitionsFile(
    val buildDate: String,
    val schemaVersion: Int,
    val tileSchemaVersion: Int,
    val regions: List<RegionDef>,
)

@Serializable
data class RegionDef(
    val id: String,
    val name: String,
    val bbox: List<Double>,
)

/**
 * The public `catalog.json` the client fetches (`maps-region-delivery.md` D3). **Field names must
 * match `RegionCatalogJson.kt` in `:map-region-service-proxy`** (`parseRegionCatalog`) — this is
 * the wire contract. Kept compact (no pretty-print) so the exact bytes written are the exact bytes
 * signed + verified.
 */
@Serializable
data class CatalogDoc(
    val schemaVersion: Int,
    val buildDate: String,
    val regions: List<CatalogRegion>,
)

@Serializable
data class CatalogRegion(
    val id: String,
    val name: String,
    val coverage: String,
    val byteSize: Long,
    val sha256: String,
    val tileSchemaVersion: Int,
    val path: String,
    /**
     * The region's transit-line overlay (§33 L3), or blank when it has none.
     *
     * ⚠⚠ **The overlay needs no signature of its own.** `catalog.json` is signed as bytes, so an
     * `overlaySha256` inside it is already covered — the artefact's integrity comes from the same
     * chain as a tile bundle's, and adding a second detached signature would be a second thing to
     * rotate for no extra guarantee.
     *
     * ⚠ Defaulted rather than nullable, and additive rather than a schema bump: an older client
     * parses this catalogue and ignores the fields (`ignoreUnknownKeys`), and a newer client reading
     * an older catalogue sees the defaults. Neither direction needs a flag day.
     *
     * ⚠ Blank is the **ordinary** case, not a failure — plenty of regions have no rail at all.
     */
    val overlayPath: String = "",
    val overlayByteSize: Long = 0,
    val overlaySha256: String = "",
    /** [app.tenshu.vectortile.TransitLineIndex.FORMAT_VERSION] the artefact was written at. */
    val overlayFormatVersion: Int = 0,
)

/**
 * Precomputed bundle digests — `deploy/tiles/build/hashes.json`, written by `tiles-build.sh`
 * alongside the extracts.
 *
 * This is what lets the **signing key stay off the build machine** (D11). Signing needs a bundle's
 * *size and hash*, not its bytes; for the `global` region the bytes are 15–100 GB that will never
 * be on the maintainer's laptop. So the machine with the disk emits this file (a few hundred bytes,
 * public — it is a subset of what `catalog.json` publishes anyway), and the machine with the key
 * signs from it. Without this the two halves could not be separated and the release key would have
 * to travel to whatever rented box happened to hold the planet.
 */
@Serializable
data class BundleDigestFile(
    val buildDate: String,
    val bundles: Map<String, BundleDigest>,
    /**
     * Overlay digests by region id, for the same D11 reason as [bundles]: the machine that built the
     * artefact need not be the machine that holds the release key.
     *
     * ⚠ Defaulted, so a `hashes.json` written before overlays existed still parses.
     */
    val overlays: Map<String, BundleDigest> = emptyMap(),
)

@Serializable
data class BundleDigest(
    val byteSize: Long,
    val sha256: String,
)
