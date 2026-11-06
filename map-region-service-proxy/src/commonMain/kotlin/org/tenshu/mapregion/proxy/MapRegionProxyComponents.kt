package org.tenshu.mapregion.proxy

import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import org.tenshu.core.common.delegate.SecureStorageDelegate
import org.tenshu.core.common.proxy.BackgroundLiveness
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.common.security.Ed25519Backend

/**
 * The proxy-side region-delivery seams, wired for production (`maps-region-delivery.md` D5/D6):
 * the [HttpRegionCatalogSource] (fetch + Ed25519-verify), the [DefaultRegionStore] (index in
 * `SecureStorageDelegate` + bytes via `FileDatabase`), and the [DefaultRegionDownloader] (fetch +
 * sha256 gate → store). The DI site constructs these once from the already-injected primitives via
 * [mapRegionComponents] and hands them to `mapRegionServiceProxyImplementationFactory`.
 */
data class MapRegionProxyComponents(
    val catalogSource: RegionCatalogSource,
    val store: RegionStore,
    val downloader: RegionDownloader,
    /** The streamed-default range source (D10): serves ranges without a full download. */
    val streamingSource: StreamingRegionSource,
    /** Device-local, in-memory download progress the `downloadProgress` stream op pushes from. */
    val progressHolder: RegionDownloadProgressHolder,
    /** Proxy-side reverse-geocode egress (P3, `timeline-movement-capture.md` §11.3); null → disabled. */
    val reverseGeocoder: ProxyReverseGeocoderEgress? = null,
    /**
     * The transit-line overlay tier (§33 L4).
     *
     * ⚠⚠ Assembled **here** rather than left for the factory to default, because the factory has no
     * `RegionRangeFetcher` and would silently build a tier that always answers `""`. This track has
     * shipped built-but-unwired machinery before (compaction, retention P3); one field here is what
     * stops `linesServing` becoming another.
     */
    val transitLines: TransitLineOverlays? = null,
)

// ⚠ `TileDecompressor` is deliberately NOT a field here (M9-4). These components are assembled in
// `commonMain` DI, which by definition cannot supply a JVM-only `java.util.zip` implementation — the
// field would be permanently null and read as "gzip unavailable" on every platform. It is passed
// straight to `mapRegionServiceProxyImplementationFactory` from each platform's `PlatformProxyModule`
// instead, which is the only place that knows what its platform can actually do.

/**
 * Assembles [MapRegionProxyComponents] from injected primitives. The catalog verifier reuses the
 * platform [Ed25519Backend] (`Ed25519Backend::verify`) against the pinned [manifestPublicKey] — one
 * signature stack (D3). [tilesHostUrl] is the CDN base (e.g. `https://tiles.service.tenshu.app`);
 * [regionsDir] is a logical prefix under the `FileDatabase` root for the downloaded bundles.
 *
 * Until Phase 1 publishes a signed catalog + the real release key is pinned, [manifestPublicKey]
 * is a placeholder — `catalog()` then fails verification (surfaces as `TResult.Failure`), which is
 * the honest pre-deploy state.
 */
fun mapRegionComponents(
    httpClient: HttpClient,
    storageDelegate: SecureStorageDelegate,
    fileDatabase: FileDatabase,
    ed25519: Ed25519Backend,
    tilesHostUrl: String,
    manifestPublicKey: ByteArray,
    /**
     * A process-lifetime scope the download runs on, so it survives the app backgrounding (the RPC
     * scope would be torn down). Kept alive meanwhile by [backgroundLiveness] (the shared FGS source).
     */
    scope: CoroutineScope,
    backgroundLiveness: BackgroundLiveness,
    regionsDir: String = "map-regions",
    /**
     * Reverse-geocode provider endpoint (P3, §11.3). Defaults to komoot's public Photon (consent-gated
     * on the app side); a self-hosted instance swaps in by config. `null` disables venue naming.
     */
    geocodingEndpoint: String? = PhotonGeocoder.DEFAULT_ENDPOINT,
): MapRegionProxyComponents {
    val verifier = RegionCatalogVerifier(
        verifier = { publicKey, message, signature -> ed25519.verify(publicKey, message, signature) },
        publicKey = manifestPublicKey,
    )
    val catalogSource = HttpRegionCatalogSource(httpClient, tilesHostUrl, verifier)
    val store = DefaultRegionStore(storageDelegate, fileDatabase, regionsDir)
    val progressHolder = RegionDownloadProgressHolder()
    val downloader = DefaultRegionDownloader(
        httpClient = httpClient,
        baseUrl = tilesHostUrl,
        catalogSource = catalogSource,
        store = store,
        scope = scope,
        liveness = backgroundLiveness,
        progress = progressHolder,
    )
    val rangeFetcher = HttpRegionRangeFetcher(httpClient, tilesHostUrl)
    val streamingSource = StreamingRegionSource(
        catalogSource = catalogSource,
        store = store,
        fetcher = rangeFetcher,
    )
    val reverseGeocoder = geocodingEndpoint?.let { PhotonGeocoder(httpClient, it) }
    return MapRegionProxyComponents(
        catalogSource, store, downloader, streamingSource, progressHolder, reverseGeocoder,
        // §33 L4: the same fetcher the streamed tiles use — same host, same accountability.
        transitLines = CatalogTransitLineOverlays(catalogSource, rangeFetcher),
    )
}
