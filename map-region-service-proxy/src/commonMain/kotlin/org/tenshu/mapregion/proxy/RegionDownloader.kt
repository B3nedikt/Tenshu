package org.tenshu.mapregion.proxy

import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import org.tenshu.core.common.proxy.BackgroundLiveness
import org.tenshu.core.common.util.Sha256Digest

/**
 * Downloads a region bundle into the [RegionStore] (`maps-region-delivery.md` §4). Resolves the
 * region in the verified catalog, fetches the bundle over plain HTTPS from the tile host,
 * verifies its sha256 against the manifest (R4), and lands it in the store. Behind an interface
 * so the [org.tenshu.core.mapregion.DownloadRegion] op and its tests are independent of the network stack.
 */
interface RegionDownloader {
    suspend fun download(regionId: String): InstalledRegionData

    /**
     * Is a download for [regionId] running **in this proxy right now**?
     *
     * Exists so the `downloadProgress` stream can tell "the paired [download] call has not registered
     * its progress entry *yet*" from "it never will". Those look identical from the progress holder
     * alone — an absent entry — but they need opposite handling, and the wrong one hangs the stream
     * forever (`core-client-resubscription.md`, R3 audit):
     *
     *  - **in flight** → the entry is merely late. [DefaultRegionDownloader] registers it only *after*
     *    `catalogSource.load()`, a network fetch that can legitimately take tens of seconds, so the
     *    stream must keep waiting however long that takes. A wall-clock timeout here would cut off
     *    healthy downloads on a slow link.
     *  - **not in flight** → nothing is coming. Either the proxy restarted (its in-memory state died
     *    with it, taking the download with it — staging files are dropped, not resumed), or the
     *    download failed before registering. Either way the stream must END so the UI can stop
     *    showing a progress bar that will never move.
     */
    suspend fun isDownloading(regionId: String): Boolean
}

/** How much of a bundle is held in memory at once — the whole point of the streamed path (§7.1 3e). */
internal const val DownloadChunkBytes: Int = 1 shl 20 // 1 MiB

/**
 * Production [RegionDownloader] — **streamed** (memory is O(chunk), not O(region)), **background-live**,
 * and **progress-emitting**.
 *
 * The body is read a chunk at a time; each chunk is immediately (a) fed to an incremental [Sha256Digest],
 * (b) appended to the store's staging file, and (c) ticked into the device-local [progress] holder. Only
 * once the streamed digest matches the signed catalog's is the staging file promoted by an atomic rename
 * ([RegionStore.commitStaged]) — a failed or half-finished download can never be served, and the integrity
 * gate (R4) is over exactly the bytes that landed on disk.
 *
 * **Lifetime is decoupled from the client call (§7.1, the download-UX slice).** A real region is hundreds
 * of MB / minutes; the download must survive the app backgrounding. So the work runs on the injected
 * process-lifetime [scope] (not the per-request scope), and while any download is in flight this raises
 * [BackgroundLiveness] under its own key — keeping the proxy's Android foreground service up exactly like
 * the sync bulk drain, so `ProxyService.onDestroy` (which cancels the session) doesn't fire mid-fetch. A
 * repeated [download] of the same region **joins** the running job rather than starting a second fetch, so
 * a client that reconnects after backgrounding awaits the same download it kicked off.
 */
class DefaultRegionDownloader(
    private val httpClient: HttpClient,
    private val baseUrl: String,
    private val catalogSource: RegionCatalogSource,
    private val store: RegionStore,
    private val scope: CoroutineScope,
    private val liveness: BackgroundLiveness,
    private val progress: RegionDownloadProgressHolder,
    private val chunkBytes: Int = DownloadChunkBytes,
) : RegionDownloader {

    // A supervisor child of the injected scope: a failing download is exposed via its own `await()` but
    // must NOT cancel the scope (nor a sibling download). Still a child, so a process-scope cancel stops
    // downloads. All download jobs and their cleanup run here.
    private val downloadScope =
        CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))

    private val mutex = Mutex()
    private val inFlight = mutableMapOf<String, Deferred<InstalledRegionData>>()

    override suspend fun download(regionId: String): InstalledRegionData =
        startOrJoin(regionId).await()

    override suspend fun isDownloading(regionId: String): Boolean =
        mutex.withLock { inFlight.containsKey(regionId) }

    /** Start a fresh background download for [regionId], or return the one already running for it. */
    private suspend fun startOrJoin(regionId: String): Deferred<InstalledRegionData> =
        mutex.withLock {
            inFlight[regionId]?.let { return@withLock it }

            val deferred = downloadScope.async { runDownload(regionId) }
            inFlight[regionId] = deferred
            liveness.setActive(true, BackgroundLiveness.MAP_REGION_DOWNLOAD)

            deferred.invokeOnCompletion {
                // Off the download's own scope so it can re-take the mutex; lowers liveness only once
                // NO map download remains, so an overlapping second download keeps the FGS up.
                downloadScope.launch {
                    mutex.withLock {
                        inFlight.remove(regionId)
                        liveness.setActive(inFlight.isNotEmpty(), BackgroundLiveness.MAP_REGION_DOWNLOAD)
                    }
                }
            }
            deferred
        }

    @OptIn(ExperimentalStdlibApi::class)
    private suspend fun runDownload(regionId: String): InstalledRegionData {
        val catalog = catalogSource.load()
        val entry = catalog.regions.firstOrNull { it.regionId == regionId }
            ?: throw UnknownRegionException("No region '$regionId' in the catalog.")

        try {
            // Reusable bytes from an earlier attempt at *this same content* (0 = start clean).
            var resumeFrom = store.beginStaging(regionId, entry.sha256)
            var digest = Sha256Digest()
            if (resumeFrom > 0L && !rehydrate(regionId, resumeFrom, digest)) {
                // The staged bytes could not be re-read, so the running digest cannot be reconstructed
                // over them and they are unusable. Drop them rather than commit to a sha we cannot compute.
                store.abortStaging(regionId)
                resumeFrom = store.beginStaging(regionId, entry.sha256)
                digest = Sha256Digest()
            }
            var written = resumeFrom
            // ⚠⚠ Published only NOW, and already carrying `written` — so the bar picks up where it
            // stopped instead of snapping to 0 and re-climbing.
            //
            // This used to be `progress.start(regionId, entry.byteSize)` above the resume
            // computation, followed by a corrective `if (written > 0L) progress.update(...)`. That
            // is not the same thing: `start` publishes into a StateFlow that the app observes over
            // IPC, so a resumed download really did emit 0 and then 8 — the exact snap-to-zero this
            // comment has always claimed to prevent. `resumedProgressStartsFromTheStagedBytesNotZero`
            // was written to catch it and could not, because its collector subscribed too late to
            // see the 0 (docs/tech-debt.md, row 17).
            //
            // ⚠ Starting here rather than before `beginStaging` means no progress entry exists while
            // staging is set up. That is correct: an entry means "in flight with this much done",
            // and until the resume point is known, how much is done is not yet a fact.
            progress.start(regionId, entry.byteSize, written)

            try {
                httpClient.prepareGet("$baseUrl/${entry.path}") {
                    if (resumeFrom > 0L) header(HttpHeaders.Range, "bytes=$resumeFrom-")
                }.execute { response: HttpResponse ->
                    if (!response.status.isSuccess()) {
                        throw RegionDownloadException(
                            "Download failed for region '$regionId': ${response.status}.",
                        )
                    }
                    // ⚠⚠ A range request that comes back 200 rather than 206 carries the WHOLE bundle,
                    // not the tail — appending it would silently produce a corrupt file that only the sha
                    // gate catches, after paying for the entire download. Servers may ignore `Range`
                    // legitimately, so this is a supported path, not an error: drop what was staged and
                    // take the body from the top.
                    if (resumeFrom > 0L && response.status != HttpStatusCode.PartialContent) {
                        store.abortStaging(regionId)
                        store.beginStaging(regionId, entry.sha256)
                        digest = Sha256Digest()
                        written = 0L
                        progress.update(regionId, 0L)
                    }

                    // prepareGet + execute keeps the body streamed; the buffer is the memory bound.
                    val channel = response.bodyAsChannel()
                    val buffer = ByteArray(chunkBytes)
                    while (true) {
                        val read = channel.readAvailable(buffer, 0, buffer.size)
                        if (read <= 0) break // -1 = end of body
                        digest.update(buffer, read)
                        // Copy: the buffer is reused on the next read, and a FileDatabase is free to retain
                        // what it is handed (the in-memory one does).
                        store.writeStaged(regionId, written, buffer.copyOf(read))
                        written += read
                        progress.update(regionId, written)
                    }
                }

                val actualSha = digest.digest().toHexString()
                if (actualSha != entry.sha256) {
                    throw RegionBundleIntegrityException(
                        "sha256 mismatch for region '$regionId' (expected ${entry.sha256}, got $actualSha).",
                    )
                }
            } catch (e: RegionBundleIntegrityException) {
                // The staged bytes are demonstrably wrong. They must not survive to be resumed onto,
                // or every later attempt inherits the corruption and fails the same gate forever.
                store.abortStaging(regionId)
                throw e
            } catch (e: Throwable) {
                // ⚠ Everything else — a dropped connection, a killed process, a cancelled job — KEEPS the
                // staging file, which is what makes the next attempt a `Range` request for the tail
                // instead of a fresh hundreds-of-MB download. The bytes are unverified, but they are
                // recorded against this content's digest, and the sha gate still stands between them and
                // the canonical bundle: a corrupt partial costs one wasted resume, then a clean restart.
                throw e
            }

            return store.commitStaged(entry, catalog.buildDate, written)
        } finally {
            // Always stop tracking — success or failure — which ends the progress stream.
            progress.finish(regionId)
        }
    }

    /**
     * Replays [stagedBytes] of [regionId]'s staging file through [digest], so a resumed download's
     * running sha256 covers the whole bundle and the integrity gate still means what it says. Returns
     * false when the file could not be read back in full — the caller then starts clean.
     */
    private fun rehydrate(regionId: String, stagedBytes: Long, digest: Sha256Digest): Boolean {
        var offset = 0L
        while (offset < stagedBytes) {
            val want = minOf(chunkBytes.toLong(), stagedBytes - offset).toInt()
            val bytes = store.readStaged(regionId, offset, want)
            if (bytes == null || bytes.isEmpty()) return false
            digest.update(bytes, bytes.size)
            offset += bytes.size
        }
        return offset == stagedBytes
    }
}

/** The requested region id is not present in the (verified) catalog. */
internal class UnknownRegionException(message: String) : Exception(message)

/** The tile host refused or failed the bundle GET. */
internal class RegionDownloadException(message: String) : Exception(message)

/** The downloaded bundle's sha256 does not match the manifest (R4). */
internal class RegionBundleIntegrityException(message: String) : Exception(message)
