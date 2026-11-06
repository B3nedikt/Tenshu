package org.tenshu.inference.proxy.catalog

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.isSuccess

/** Where a catalog comes from. A seam so tests need no network and no key material. */
fun interface ModelCatalogSource {
    /** The verified catalog, or `null` when there is nothing this client can trust or use. */
    suspend fun load(): ModelCatalogData?
}

/**
 * The production source (IN-2b, IN-D10 / `maps-region-delivery.md` D3/D6): a plain HTTPS GET of
 * `catalog.json` and its detached signature from the CDN — public metadata, so unauthenticated —
 * verified against the pinned key before a single field is read.
 *
 * ⚠⚠ Returns `null` rather than throwing on a failed check, unlike its region twin. The difference
 * is what the caller can do: a missing basemap catalog blocks a download the user just asked for
 * and deserves an error, whereas the model catalog is polled in the background, and a proxy that
 * threw on every refresh against an untrusted CDN would fill the log and change nothing. What
 * matters — that nothing untrusted is *installed* — is the same either way.
 *
 * ⚠ The [HttpClient] is injected with the platform engine at the DI site, so this module stays
 * engine-free.
 */
class HttpModelCatalogSource(
    private val httpClient: HttpClient,
    private val baseUrl: String,
    private val verifier: ModelCatalogVerifier,
) : ModelCatalogSource {

    /**
     * ⚠⚠ **Unconfigured is answered without a request.** A client with a blank [baseUrl] or a blank
     * pin could not trust any answer it received, so asking is pure cost: a DNS failure per refresh
     * in the log, forever, that reads like a network fault rather than "this is not set up yet".
     *
     * ⚠ It is a **cost** guard, not the safety one. [ModelCatalogVerifier] refuses a blank pin by
     * itself, and that is what makes this safe; removing this line would waste requests, not admit
     * an untrusted catalog.
     */
    private val configured: Boolean
        get() = baseUrl.isNotBlank() && verifier.hasPinnedKey

    override suspend fun load(): ModelCatalogData? = runCatching {
        if (!configured) return null
        val catalogBytes = fetch(CATALOG_PATH) ?: return null
        val signature = fetch(SIGNATURE_PATH) ?: return null
        verifier.verifyAndParse(catalogBytes, signature)
    }.getOrNull()

    /**
     * ⚠⚠ **The status is checked, not assumed.** Ktor does not throw on a 404 by default, so
     * without this the *error page's bytes* are handed to the verifier as if they were content —
     * and a CDN that serves the document while 404-ing the signature is exactly what an attacker
     * who can delete one file would arrange. The signature check would still refuse it in
     * production, but a path whose safety rests on a fake being honest is not one to leave open.
     */
    private suspend fun fetch(path: String): ByteArray? {
        val response = httpClient.get("$baseUrl/$path")
        if (!response.status.isSuccess()) return null
        return response.body()
    }

    internal companion object {
        /**
         * ⚠⚠ **Must be the names `scripts/models-publish.sh` uploads.** A mismatch is a 404 on every
         * device after a publish that succeeded, with nothing in any log to say why —
         * `PublishedCatalogPathsTest` reads the script and fails when the two disagree.
         */
        const val CATALOG_PATH = "catalog.json"
        const val SIGNATURE_PATH = "catalog.json.sig"
    }
}
