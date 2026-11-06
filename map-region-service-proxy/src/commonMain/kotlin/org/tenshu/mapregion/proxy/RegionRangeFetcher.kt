package org.tenshu.mapregion.proxy

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders

/**
 * Fetches a byte range of a region bundle from the tile host over a plain HTTPS ranged GET — the
 * remote leg of the streamed-default delivery (`maps-region-delivery.md` D10). Behind an interface
 * so [StreamingRegionSource] and its tests are independent of the network stack.
 *
 * Region bundles are **public plaintext** (ODbL map data), so the transfer carries no user data and
 * no app-layer encryption; integrity of a streamed tile rests on TLS + the signed catalog pinning
 * *which* object is ranged into (§5 streamed-integrity delta — unlike an offline download, an
 * individual streamed range is not sha-verified).
 */
fun interface RegionRangeFetcher {
    /**
     * [length] bytes at [offset] of the region bundle at CDN-relative [path]. Returns the covering
     * bytes (possibly shorter at EOF). The caller ([StreamingRegionSource]) resolves [path] from the
     * verified catalog and aligns [offset]/[length] to cache chunks.
     */
    suspend fun fetchRange(path: String, offset: Long, length: Int): ByteArray
}

/**
 * Production [RegionRangeFetcher] — an HTTP `Range: bytes=start-end` GET against the tile host
 * (`$baseUrl/$path`). A range-honouring origin/CDN answers `206 Partial Content` with just the
 * window; the body bytes are returned as-is (the [StreamingRegionSource] never asks past EOF).
 */
class HttpRegionRangeFetcher(
    private val httpClient: HttpClient,
    private val baseUrl: String,
) : RegionRangeFetcher {

    override suspend fun fetchRange(path: String, offset: Long, length: Int): ByteArray {
        val end = offset + length - 1
        return httpClient.get("$baseUrl/$path") {
            header(HttpHeaders.Range, "bytes=$offset-$end")
        }.body()
    }
}
