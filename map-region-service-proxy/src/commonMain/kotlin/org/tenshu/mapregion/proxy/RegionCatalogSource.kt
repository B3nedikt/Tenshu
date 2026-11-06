package org.tenshu.mapregion.proxy

/**
 * The fetch-and-verify seam behind [Catalog]. The production implementation (slice 2b) does a
 * plain HTTPS GET of `catalog.json` + its detached signature from the CDN, verifies the
 * signature against the pinned release key (via [RegionCatalogVerifier]), and parses the JSON
 * into [RegionCatalogData]. Kept behind an interface so the operation implementation and its
 * tests are independent of the network + JSON stack.
 *
 * A verification or transport failure throws (surfaced as a typed `TResult.Failure` by the
 * error-channel catch-all) — never a silently-empty catalog.
 */
interface RegionCatalogSource {
    suspend fun load(): RegionCatalogData
}
