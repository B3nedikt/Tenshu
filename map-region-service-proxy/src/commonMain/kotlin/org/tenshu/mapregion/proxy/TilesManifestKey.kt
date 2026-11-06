package org.tenshu.mapregion.proxy

/**
 * The tiles catalog signing key's PUBLIC half — the client-pinned trust anchor for offline
 * map-region delivery (`maps-region-delivery.md` §9 / D3). The private half signs `catalog.json`
 * offline (`tiles-signer keygen`); `RegionCatalogVerifier` checks the detached signature against
 * this key via `Ed25519Backend`. Pinned in source on purpose (compiled into the client, never
 * fetched). Rotating the key means re-signing every published build and shipping a new pin.
 *
 * ⚠ Lives here, beside the verifier, so Control's DI and the web runtime (`MapRegionPlugin`) pin
 * the **same** spelling — a second copy of a trust anchor is one that can silently diverge.
 */
const val TILES_MANIFEST_PUBLIC_KEY_HEX: String =
    "e6d7adf0f403c33905e514e404e6578f4f2b990e272ba6339f0f9c7a977f43e1"

/** Decodes [TILES_MANIFEST_PUBLIC_KEY_HEX] to the raw 32-byte Ed25519 public key (no stdlib opt-in). */
fun tilesManifestPublicKey(): ByteArray =
    ByteArray(TILES_MANIFEST_PUBLIC_KEY_HEX.length / 2) { i ->
        TILES_MANIFEST_PUBLIC_KEY_HEX.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }

/**
 * The tile host every client reads catalogs and region archives from (`maps-region-delivery.md` D1/D6):
 * Control's `tilesHostUrl` and the web runtime's tile transport both take it from here, so it is
 * spelled once.
 */
const val TILES_HOST_URL: String = "https://tiles.service.tenshu.app"
