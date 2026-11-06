package org.tenshu.mapregion.proxy

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get

/**
 * The production [RegionCatalogSource] (`maps-region-delivery.md` D3/D6, the proxy ↔ tile-host
 * leg): a plain HTTPS GET of `catalog.json` + its detached signature `catalog.json.sig` from the
 * CDN (unauthenticated — the catalog is public metadata), verified against the pinned release key
 * via [RegionCatalogVerifier], then parsed. A signature failure throws (never a silently-empty
 * catalog). The [HttpClient] is injected with the platform engine at the DI site.
 */
class HttpRegionCatalogSource(
    httpClient: HttpClient,
    baseUrl: String,
    verifier: RegionCatalogVerifier,
) : RegionCatalogSource by FetchingRegionCatalogSource(
    fetch = { path -> httpClient.get("$baseUrl/$path").body() },
    verifier = verifier,
)

/**
 * The fetch → verify → parse sequence of [HttpRegionCatalogSource], over any byte [fetch] of a
 * tile-host-relative path. Split out for the web runtime (`maps-region-delivery.md` §Web scope W.2),
 * whose bytes arrive through a transport seam rather than a Ktor client: the verification and the
 * parse stay this one implementation on every platform.
 */
class FetchingRegionCatalogSource(
    private val fetch: suspend (path: String) -> ByteArray,
    private val verifier: RegionCatalogVerifier,
) : RegionCatalogSource {

    override suspend fun load(): RegionCatalogData {
        val catalogBytes = fetch(CATALOG_PATH)
        val signature = fetch(SIGNATURE_PATH)

        if (!verifier.isAuthentic(catalogBytes, signature)) {
            throw RegionCatalogVerificationException(
                "Region catalog signature verification failed against the pinned key.",
            )
        }

        return parseRegionCatalog(catalogBytes.decodeToString())
    }

    companion object {
        const val CATALOG_PATH = "catalog.json"
        const val SIGNATURE_PATH = "catalog.json.sig"
    }
}

/** Thrown when the fetched catalog fails signature verification (R4). */
class RegionCatalogVerificationException(message: String) : Exception(message)
