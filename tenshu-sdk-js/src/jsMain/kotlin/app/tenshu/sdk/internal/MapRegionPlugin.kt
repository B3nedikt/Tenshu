package app.tenshu.sdk.internal

import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.security.NobleEd25519Backend
import org.tenshu.core.mapregion.Catalog
import org.tenshu.core.mapregion.StreamRegionRange
import org.tenshu.mapregion.client.mapRegionServiceClientModule
import org.tenshu.mapregion.common.mapRegionServiceCommonModule
import org.tenshu.mapregion.proxy.FetchingRegionCatalogSource
import org.tenshu.mapregion.proxy.InMemoryStreamingRegionCache
import org.tenshu.mapregion.proxy.InstalledRegionData
import org.tenshu.mapregion.proxy.RegionCatalogData
import org.tenshu.mapregion.proxy.RegionCatalogSource
import org.tenshu.mapregion.proxy.RegionCatalogVerificationException
import org.tenshu.mapregion.proxy.RegionCatalogVerifier
import org.tenshu.mapregion.proxy.RegionDownloadProgressHolder
import org.tenshu.mapregion.proxy.RegionDownloader
import org.tenshu.mapregion.proxy.RegionEntryData
import org.tenshu.mapregion.proxy.RegionRangeFetcher
import org.tenshu.mapregion.proxy.RegionStore
import org.tenshu.mapregion.proxy.StreamingRegionSource
import org.tenshu.mapregion.proxy.mapRegionServiceProxyImplementationFactory
import org.tenshu.mapregion.proxy.TILES_HOST_URL
import org.tenshu.mapregion.proxy.tilesManifestPublicKey

/**
 * ⚠⚠ **The ONE seam between the web runtime and the tile host** (`maps-region-delivery.md` §Web scope
 * W.1). Every byte the browser would read from `tiles.service.tenshu.app` — `catalog.json`, its
 * signature, and every streamed range — goes through [fetch]. Decided (W.1, 2026-10-28): CORS on the
 * R2 bucket for the apex origins only, so the shipped transport is [FetchTileTransport] on
 * `TILES_HOST_URL`. A test plugs a fixture in through [MapRegionPlugin.useTransport].
 *
 * [path] is tile-host-relative (`catalog.json`, `regions/2026-09/it-toscana.pmtiles`). [offset] and
 * [length] are both null for a whole-object read and both set for a ranged one; an implementation
 * returns exactly the bytes asked for (shorter only at EOF) or throws — never an empty array for a
 * failure.
 */
internal fun interface WebTileTransport {
    suspend fun fetch(path: String, offset: Long?, length: Int?): ByteArray
}

/**
 * A failure this surface classifies itself. The [kind] is a catalogue key (`error_map_…`) and leads the
 * message, because the operation layer's catch-all carries an arbitrary exception only as text
 * (`InternalError.internalDetail`): [MapRegionErrors.kindOf] reads it back from there.
 */
internal class MapRegionWebException(val kind: String, detail: String) : Exception("$kind: $detail")

/** The catalogue keys the map-region web surface answers with. Mirrored in `web/sdk/src/mapRegion.ts`. */
internal object MapRegionErrors {
    /** No transport is configured — the shipped state until §Web scope W.1 is answered. */
    const val TRANSPORT_UNCONFIGURED = "error_map_transport_unconfigured"

    /** The transport is configured and the fetch failed (network, HTTP status, short body). */
    const val TRANSPORT_FAILED = "error_map_transport_failed"

    /** `catalog.json` did not verify against the pinned key (T-MR-02). Fails closed. */
    const val CATALOG_UNVERIFIED = "error_map_catalog_unverified"

    /** The region is not in the verified catalog. */
    const val UNKNOWN_REGION = "error_map_unknown_region"

    /** An offset/length or coordinate the surface refuses before reaching the runtime. */
    const val BAD_REQUEST = "error_map_bad_request"

    /** Anything else. */
    const val UNAVAILABLE = "error_map_unavailable"

    private val KIND = Regex("error_map_[a-z_]+")

    /** The kind a failure's text leads with, or [UNAVAILABLE]. */
    fun kindOf(text: String?): String = text?.let { KIND.find(it)?.value } ?: UNAVAILABLE
}

/** The shipped transport: refuses, with a kind the UI can say something true about. */
internal object UnconfiguredTileTransport : WebTileTransport {
    override suspend fun fetch(path: String, offset: Long?, length: Int?): ByteArray =
        throw MapRegionWebException(
            MapRegionErrors.TRANSPORT_UNCONFIGURED,
            "no tile transport is configured for the web runtime (maps-region-delivery.md §Web scope W.1)",
        )
}

/**
 * The verified catalog, read through the transport, kept for the runtime's life once it loads.
 *
 * Memoised because every map mount asks for it and the catalog changes quarterly (D8); a failure is
 * **not** memoised, so a transport that comes up later is picked up by the next call.
 * The verification is native's (`FetchingRegionCatalogSource`, the code under `HttpRegionCatalogSource`),
 * so a signature failure is the same exception, reported here as [MapRegionErrors.CATALOG_UNVERIFIED].
 */
internal class WebRegionCatalogSource(
    private val transport: WebTileTransport,
    verifier: RegionCatalogVerifier,
) : RegionCatalogSource {
    private val inner = FetchingRegionCatalogSource(fetch = { path -> transport.fetch(path, null, null) }, verifier = verifier)
    private var loaded: RegionCatalogData? = null

    override suspend fun load(): RegionCatalogData {
        loaded?.let { return it }
        val data = try {
            inner.load()
        } catch (e: RegionCatalogVerificationException) {
            throw MapRegionWebException(MapRegionErrors.CATALOG_UNVERIFIED, e.message ?: "")
        }
        loaded = data
        return data
    }
}

/** A streamed range through the transport. A transport that is not ours to classify becomes TRANSPORT_FAILED. */
internal class WebRegionRangeFetcher(private val transport: WebTileTransport) : RegionRangeFetcher {
    override suspend fun fetchRange(path: String, offset: Long, length: Int): ByteArray {
        val bytes = try {
            transport.fetch(path, offset, length)
        } catch (e: MapRegionWebException) {
            throw e
        } catch (e: Throwable) {
            throw MapRegionWebException(MapRegionErrors.TRANSPORT_FAILED, e.message ?: e.toString())
        }
        // ⚠ The caller already clamps [length] to the catalog's byteSize, so a short body is a broken
        // response, never EOF. Cached, it would read as a truncated archive: a blank map, no error.
        if (bytes.size != length) {
            throw MapRegionWebException(
                MapRegionErrors.TRANSPORT_FAILED,
                "short range: asked $length bytes at $offset of $path, got ${bytes.size}",
            )
        }
        return bytes
    }
}

/**
 * The web has no region store: region **download** is out of scope on web (`web-app-suite.md` §2,
 * Maps row; §Web scope W.3). So nothing is ever installed, `StreamingRegionSource` always streams, and
 * the D9 cap has nothing to count. The writes are unreachable — no download op is mapped — and throw
 * rather than pretend.
 */
internal object NoRegionStore : RegionStore {
    override fun installed(): List<InstalledRegionData> = emptyList()
    override fun readRange(regionId: String, offset: Long, length: Int): ByteArray? = null
    override fun remove(regionId: String): Boolean = false
    override fun add(entry: RegionEntryData, buildDate: String, bytes: ByteArray): InstalledRegionData = noDownloads()
    override fun writeStaged(regionId: String, offset: Long, bytes: ByteArray): Unit = noDownloads()
    override fun beginStaging(regionId: String, expectedSha256: String): Long = noDownloads()
    override fun readStaged(regionId: String, offset: Long, length: Int): ByteArray? = noDownloads()
    override fun commitStaged(entry: RegionEntryData, buildDate: String, byteSize: Long): InstalledRegionData = noDownloads()
    override fun abortStaging(regionId: String): Unit = noDownloads()

    private fun noDownloads(): Nothing = error("region download is not available on web")
}

private object NoRegionDownloader : RegionDownloader {
    override suspend fun download(regionId: String) = error("region download is not available on web")
    override suspend fun isDownloading(regionId: String): Boolean = false
}

/**
 * `:map-region-service` on web (`web-app-suite.md` §4, W-MAP) — the streamed-region READ path the
 * shared map renderer (`@tenshu/map-kit`) draws through. Until W-MAP it was an F7 sync-only entry in
 * `SyncedServicePlugins.kt`; it carries the same schema and still no mergers (the service has no data
 * relations, so it has nothing to merge).
 *
 * The factory maps exactly two ops, both served by the native `commonMain` implementations:
 *  - [Catalog] — `catalog.json` + signature through [WebTileTransport], verified against the **pinned**
 *    key (`tilesManifestPublicKey`, the one Control pins) with the JS Ed25519 backend, filtered to tile
 *    schema ≤ v4. Fails closed.
 *  - [StreamRegionRange] — `StreamingRegionSource`, the class Control runs: chunk-aligned ranges from
 *    the transport, cached in memory.
 *
 * ⚠⚠ **No background work in a tab** (`NoBackgroundWorkInATabTest`): no `initialize`, no download, no
 * `downloadProgress`, no `prefetchLowZoom` (D10's first-touch warm-up is native-only, §Web scope W.5),
 * no catalog refresh, no update check. Nothing here does anything until a map asks for bytes.
 *
 * ⚠ The cache is **memory only and tab-sized**: [CACHE_CHUNKS] × 256 KiB = 16 MiB, against native's
 * 256 MiB. A persistent cache would compete for quota with the realm identity on the same origin, and
 * browsers evict an origin whole (`drive-web-parity.md` §1.2d; §Web scope W.3).
 */
internal object MapRegionPlugin : JsRuntimePlugin {
    override val id: String = "mapRegion"

    override val schemaConfigs = listOf(mapRegionServiceCommonModule, mapRegionServiceClientModule)

    /** 64 × `StreamingChunkBytes` (256 KiB) = 16 MiB. */
    private const val CACHE_CHUNKS = 64

    private class Wiring(val catalog: RegionCatalogSource, val streaming: StreamingRegionSource)

    // W.1, decided: CORS on the R2 bucket for the apex origins only (FetchTileTransport).
    private var transport: WebTileTransport = FetchTileTransport(TILES_HOST_URL)
    private var publicKey: ByteArray = tilesManifestPublicKey()
    private var wiring: Wiring? = null

    /** Built on first use, never at registration: constructing it starts nothing either way. */
    private fun wiring(): Wiring = wiring ?: run {
        val catalog = WebRegionCatalogSource(
            transport,
            RegionCatalogVerifier(
                verifier = { key, message, signature -> NobleEd25519Backend().verify(key, message, signature) },
                publicKey = publicKey,
            ),
        )
        val streaming = StreamingRegionSource(
            catalogSource = catalog,
            store = NoRegionStore,
            fetcher = WebRegionRangeFetcher(transport),
            cache = InMemoryStreamingRegionCache(maxChunks = CACHE_CHUNKS),
        )
        Wiring(catalog, streaming).also { wiring = it }
    }

    /** The verified catalog source the facade's lookups read (the same one [Catalog] serves). */
    internal val catalogSource: RegionCatalogSource get() = wiring().catalog

    /**
     * Plugs a transport in — the maintainer's W.1 decision, or a test fixture. Drops the memoised catalog
     * and the chunk cache, since both came from the previous transport. [key] defaults to the pinned
     * release key; only a test signs with anything else.
     */
    internal fun useTransport(next: WebTileTransport, key: ByteArray = tilesManifestPublicKey()) {
        transport = next
        publicKey = key
        wiring = null
    }

    override fun operationFactory(ctx: RuntimeContext) = OperationImplementationFactory { opId ->
        when (opId) {
            Catalog.ID, StreamRegionRange.ID -> {
                val w = wiring()
                mapRegionServiceProxyImplementationFactory(
                    id = opId,
                    catalogSource = w.catalog,
                    regionStore = NoRegionStore,
                    downloader = NoRegionDownloader,
                    streamingSource = w.streaming,
                    progressHolder = RegionDownloadProgressHolder(),
                )
            }
            else -> null
        }
    }
}
