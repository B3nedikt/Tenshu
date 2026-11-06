package org.tenshu.mapregion.proxy

/**
 * Verifies a detached signature over the raw `catalog.json` bytes against the pinned Tenshu
 * release public key (`maps-region-delivery.md` D3/R4 — a CDN/bucket compromise cannot redirect
 * clients to tampered bundles). Pure: it takes a [SignatureVerifier] SAM, so production wires in
 * `Ed25519Backend::verify` (`core-common`) while tests inject a deterministic fake — no crypto
 * backend dependency leaks into `commonTest` (whose iOS/Android/JVM actuals differ).
 */
class RegionCatalogVerifier(
    private val verifier: SignatureVerifier,
    private val publicKey: ByteArray,
) {

    /** True iff [signature] is a valid signature over [catalogBytes] under the pinned key. */
    fun isAuthentic(catalogBytes: ByteArray, signature: ByteArray): Boolean =
        verifier.verify(publicKey, catalogBytes, signature)
}

/**
 * Narrow signature-check seam matching `Ed25519Backend::verify` — `(publicKey, message,
 * signature) -> Boolean`, non-throwing. Kept local to avoid a `:sync-service-common` dependency
 * just for its `Ed25519SignatureVerifier`.
 */
fun interface SignatureVerifier {
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean
}

/**
 * The Protomaps tile schema version the `:ui-map` runtime style speaks (v4, per
 * `OfflineBasemap.android.kt`). The catalog is filtered to regions the client can render, so a
 * future schema-bumping build isn't picked up by a style that would render blank (D3).
 */
internal const val SUPPORTED_TILE_SCHEMA_VERSION: Int = 4

/** Keeps only the regions whose tile schema the client can render (`<= supported`, D3). */
internal fun renderableRegions(
    regions: List<RegionEntryData>,
    supportedTileSchema: Int = SUPPORTED_TILE_SCHEMA_VERSION,
): List<RegionEntryData> = regions.filter { it.tileSchemaVersion <= supportedTileSchema }
