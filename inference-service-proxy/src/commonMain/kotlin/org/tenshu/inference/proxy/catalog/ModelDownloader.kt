package org.tenshu.inference.proxy.catalog

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.tenshu.core.common.proxy.BackgroundLiveness
import org.tenshu.core.common.util.Sha256Digest

/**
 * Fetches a model named by the **verified** catalog into the [ModelStore] (IN-2b, the 1b slice).
 *
 * Behind an interface so IN-2c's `DownloadModel` op — the thing that will actually call this — and
 * its tests are independent of the network stack.
 *
 * ⚠⚠ **Nothing calls this yet, and that is the slice boundary, not an oversight.** IN-D3 keeps
 * inference off by default and IN-D10 requires the user to see what a model costs before it is
 * fetched, so an automatic download on first launch would be the wrong feature. The trigger is
 * IN-2c's screen (§19.2, step 1c); until it exists this is reachable machinery with no caller.
 */
interface ModelDownloader {

    /**
     * Downloads [modelId] and returns the installed record.
     *
     * ⚠ Throws rather than returning null, unlike [ModelCatalogSource.load]: this runs because a
     * user asked for it, and "it did not happen" without a reason is not something a screen can
     * render.
     */
    suspend fun download(modelId: String): InstalledModelData

    /**
     * Is a download for [modelId] running **in this proxy right now**?
     *
     * ⚠ Exists so a progress stream can tell "the paired [download] has not registered its entry
     * *yet*" from "it never will" — an absent progress entry looks identical in both cases, and the
     * wrong reading hangs the stream forever. [DefaultModelDownloader] registers only after
     * `catalogSource.load()`, a network fetch that can legitimately take tens of seconds.
     */
    suspend fun isDownloading(modelId: String): Boolean

    /**
     * Removes [modelId] from the store, **with no download of that model left to bring it
     * back**: runs [remove], the bare removal, inside [changes].
     *
     * ⚠⚠ The downloader runs the wrapper, not the caller, so that whatever a downloader must
     * ask before the store changes is asked **inside** the wrapper, after its wait, and not
     * left to each caller to remember. [remove] is the store's removal and nothing else.
     *
     * ⚠⚠ For a downloader whose downloads outlive the process: a journalled download of the
     * model is ended first (its record, then its transfers), and no other step of the
     * downloader runs until the removal has returned. A download that cannot be ended (its
     * journal cannot be read) throws, and [remove] is **not** run: a model removed beside a
     * download that then installs it is not removed.
     *
     * ⚠ The default ends nothing: it runs [remove] inside [changes], which is what the
     * in-process downloader has always amounted to. Its downloads die with the process and are
     * not ended by a removal (`tech-debt.md`).
     */
    suspend fun <T> withoutDownloadOf(modelId: String, changes: ModelStoreChanges, remove: suspend () -> T): T =
        changes.changing(modelId, remove)
}

/**
 * How much of an artifact is held in memory at once.
 *
 * ⚠⚠ The whole point of the streamed path. The pinned pair is ~383 MB (§21.1) and Control's
 * Android heap is 256 MB — `control-android-store-oom` is what materialising one costs.
 */
internal const val ModelDownloadChunkBytes: Int = 1 shl 20 // 1 MiB

/**
 * Production [ModelDownloader] — **streamed** (memory is O(chunk), not O(model)), **resumable**,
 * **background-live**, and **digest-gated**.
 *
 * Each chunk is (a) fed to an incremental [Sha256Digest], (b) appended to the store's staging file,
 * and (c) ticked into [progress]. Only once the streamed digest matches the **signed catalog's**
 * figure is staging promoted by an atomic rename ([ModelStore.commitStaged]), so a failed or
 * half-finished download can never be loaded, and the integrity gate is over exactly the bytes that
 * landed on disk.
 *
 * ⚠ Lifetime is decoupled from the caller: a 280 MB tower over a phone connection is minutes, and
 * the download must survive the app backgrounding. Work runs on the injected process-lifetime
 * [scope], and while any download is in flight this raises [BackgroundLiveness] under its own key.
 * A repeated [download] of the same model **joins** the running job rather than starting a second
 * fetch.
 *
 * ⭐ This is `DefaultRegionDownloader` with the model catalog's shapes substituted, deliberately:
 * every ⚠ in it was paid for once already, and the two differences that matter are called out at
 * their sites.
 */
class DefaultModelDownloader(
    private val httpClient: HttpClient,
    private val baseUrl: String,
    private val catalogSource: ModelCatalogSource,
    private val store: ModelStore,
    private val scope: CoroutineScope,
    private val liveness: BackgroundLiveness,
    private val progress: ModelDownloadProgressHolder,
    private val chunkBytes: Int = ModelDownloadChunkBytes,
) : ModelDownloader {

    // A supervisor child of the injected scope: a failing download is exposed via its own `await()`
    // but must NOT cancel the scope, nor a sibling download. Still a child, so a process-scope
    // cancel stops downloads.
    private val downloadScope =
        CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))

    private val mutex = Mutex()
    private val inFlight = mutableMapOf<String, Deferred<InstalledModelData>>()

    override suspend fun download(modelId: String): InstalledModelData =
        startOrJoin(modelId).await()

    override suspend fun isDownloading(modelId: String): Boolean =
        mutex.withLock { inFlight.containsKey(modelId) }

    private suspend fun startOrJoin(modelId: String): Deferred<InstalledModelData> =
        mutex.withLock {
            inFlight[modelId]?.let { return@withLock it }

            val deferred = downloadScope.async { runDownload(modelId) }
            inFlight[modelId] = deferred
            liveness.setActive(true, BackgroundLiveness.MODEL_DOWNLOAD)

            deferred.invokeOnCompletion {
                // Off the download's own scope so it can re-take the mutex; lowers liveness only
                // once NO model download remains, so an overlapping second one keeps the FGS up.
                downloadScope.launch {
                    mutex.withLock {
                        inFlight.remove(modelId)
                        liveness.setActive(inFlight.isNotEmpty(), BackgroundLiveness.MODEL_DOWNLOAD)
                    }
                }
            }
            deferred
        }

    @OptIn(ExperimentalStdlibApi::class)
    private suspend fun runDownload(modelId: String): InstalledModelData {
        // ⚠⚠ The FIRST difference from the region twin: `load()` returns null rather than throwing
        // (§11.3), because the model catalog is also polled in the background. Here it is not being
        // polled — a user asked — so a null must become an error rather than an empty catalog that
        // reads as "no such model" and blames the wrong thing.
        val catalog = catalogSource.load()
            ?: throw ModelCatalogUnavailableException(
                "No trusted model catalog: it could not be fetched, or the pinned key refused it.",
            )
        val entry = catalog.models.firstOrNull { it.modelId == modelId }
            ?: throw UnknownModelException("No model '$modelId' in the verified catalog.")

        try {
            // ⚠⚠ The SECOND difference: a region is one bundle, a checkpoint is N files. Progress
            // is over the whole checkpoint — a user installing SigLIP is waiting for ~383 MB, not
            // for three bars — so the total is the entry's and `written` accumulates across
            // artifacts. It is published once, before the first GET, already carrying whatever an
            // earlier attempt had staged; a 0 here would be a bar that snaps to empty and
            // re-climbs ground already paid for.
            val resume = entry.artifacts.associate { it.role to prepare(entry, it) }
            progress.start(modelId, entry.byteSize, resume.values.sumOf { it.staged })
            var completed = 0L

            val landed = mutableMapOf<String, Long>()
            for (artifact in entry.artifacts) {
                val state = resume.getValue(artifact.role)
                // ⚠⚠ An artifact an earlier attempt finished is NOT fetched again. Because
                // artifacts commit together, an interrupt can leave one fully staged, and asking
                // for its tail is a `Range` starting at the end — which real servers answer 416.
                landed[artifact.role] = if (state.complete) {
                    progress.update(modelId, completed + state.staged)
                    state.staged
                } else {
                    fetchArtifact(entry, artifact, state, completed)
                }
                completed += landed.getValue(artifact.role)
            }

            // ⚠⚠ Only now, and for every artifact at once: until this line the device holds staged
            // bytes and no index record, so a crash re-downloads rather than leaving a checkpoint
            // whose towers are from different exports (§22.2).
            return store.commitStaged(entry, landed)
        } finally {
            // Always stop tracking — success or failure — which ends the progress stream.
            progress.finish(modelId)
        }
    }

    /** What an earlier attempt left for [artifact], and the digest replayed over it. */
    @OptIn(ExperimentalStdlibApi::class)
    private fun prepare(entry: ModelEntryData, artifact: ModelArtifactData): ResumeState {
        val modelId = entry.modelId
        val role = artifact.role
        var staged = store.beginStaging(modelId, role, artifact.sha256)
        var digest = Sha256Digest()

        fun restart(): ResumeState {
            store.abortStaging(modelId, role)
            return ResumeState(store.beginStaging(modelId, role, artifact.sha256), Sha256Digest(), false)
        }

        if (staged > 0L && !rehydrate(modelId, role, staged, digest)) {
            // The staged bytes could not be re-read, so the running digest cannot be reconstructed
            // over them. Drop them rather than commit to a sha we cannot compute.
            return restart()
        }

        // ⚠⚠ Already finished? Only the digest may answer that. Byte count alone would install
        // bytes that never passed the gate — a truncation that happens to land on the advertised
        // length, or a resumed attempt against different content whose marker survived.
        if (staged > 0L && artifact.byteSize > 0L && staged >= artifact.byteSize) {
            val actual = digest.digest().toHexString()
            if (actual == artifact.sha256) return ResumeState(staged, Sha256Digest(), complete = true)
            // Full length and wrong: those bytes can never become this artifact.
            return restart()
        }
        return ResumeState(staged, digest, complete = false)
    }

    private class ResumeState(
        val staged: Long,
        val digest: Sha256Digest,
        /** ⚠ Staged in full **and digest-verified** — nothing left to fetch. */
        val complete: Boolean = false,
    )

    /** Streams one artifact into staging, gated on its own digest. Returns the byte count. */
    @OptIn(ExperimentalStdlibApi::class)
    private suspend fun fetchArtifact(
        entry: ModelEntryData,
        artifact: ModelArtifactData,
        state: ResumeState,
        alreadyCompleted: Long,
    ): Long {
        val modelId = entry.modelId
        var resumeFrom = state.staged
        var digest = state.digest
        var written = resumeFrom

        try {
            httpClient.prepareGet("$baseUrl/${artifact.path}") {
                if (resumeFrom > 0L) header(HttpHeaders.Range, "bytes=$resumeFrom-")
            }.execute { response: HttpResponse ->
                if (!response.status.isSuccess()) {
                    throw ModelDownloadException(
                        "Download failed for '$modelId' artifact '${artifact.role}': ${response.status}.",
                    )
                }
                // ⚠⚠ A range request answered 200 rather than 206 carries the WHOLE artifact, not
                // the tail — appending it silently produces a corrupt file that only the sha gate
                // catches, after paying for the entire download. Servers may ignore `Range`
                // legitimately, so this is a supported path: drop what was staged and start over.
                if (resumeFrom > 0L && response.status != HttpStatusCode.PartialContent) {
                    store.abortStaging(modelId, artifact.role)
                    store.beginStaging(modelId, artifact.role, artifact.sha256)
                    digest = Sha256Digest()
                    written = 0L
                    resumeFrom = 0L
                    progress.update(modelId, alreadyCompleted)
                }

                // prepareGet + execute keeps the body streamed; the buffer is the memory bound.
                val channel = response.bodyAsChannel()
                val buffer = ByteArray(chunkBytes)
                while (true) {
                    val read = channel.readAvailable(buffer, 0, buffer.size)
                    if (read <= 0) break // -1 = end of body
                    digest.update(buffer, read)
                    // Copy: the buffer is reused on the next read, and a FileDatabase is free to
                    // retain what it is handed (the in-memory one does).
                    store.writeStaged(modelId, artifact.role, written, buffer.copyOf(read))
                    written += read
                    progress.update(modelId, alreadyCompleted + written)
                }
            }

            val actualSha = digest.digest().toHexString()
            if (actualSha != artifact.sha256) {
                throw ModelIntegrityException(
                    "sha256 mismatch for '$modelId' artifact '${artifact.role}' " +
                        "(expected ${artifact.sha256}, got $actualSha).",
                )
            }
        } catch (e: ModelIntegrityException) {
            // The staged bytes are demonstrably wrong. They must not survive to be resumed onto,
            // or every later attempt inherits the corruption and fails the same gate forever.
            store.abortStaging(modelId, artifact.role)
            throw e
        }
        // ⚠ Every other failure — a dropped connection, a killed process, a cancelled job — KEEPS
        // the staging file, which is what makes the next attempt a `Range` request for the tail
        // rather than a fresh download of the whole checkpoint. Those bytes are unverified, but
        // they are recorded against this content's digest and the sha gate still stands between
        // them and the installed artifact.
        return written
    }

    /**
     * Replays [stagedBytes] of one artifact's staging file through [digest], so a resumed
     * download's running sha256 covers the whole file and the integrity gate still means what it
     * says. Returns false when it could not be read back in full — the caller then starts clean.
     */
    private fun rehydrate(
        modelId: String,
        role: String,
        stagedBytes: Long,
        digest: Sha256Digest,
    ): Boolean {
        var offset = 0L
        while (offset < stagedBytes) {
            val want = minOf(chunkBytes.toLong(), stagedBytes - offset).toInt()
            val bytes = store.readStaged(modelId, role, offset, want)
            if (bytes == null || bytes.isEmpty()) return false
            digest.update(bytes, bytes.size)
            offset += bytes.size
        }
        return offset == stagedBytes
    }
}

/**
 * No catalog this client can trust.
 *
 * ⚠⚠ A blank pin, a blank `named("modelCatalogUrl")`, or a URL with nothing published at it all land
 * here, deliberately: a device reports *why* nothing can be downloaded rather than showing an empty
 * list that looks like "no models are published".
 */
class ModelCatalogUnavailableException(message: String) : Exception(message)

/** The requested model id is not present in the verified catalog. */
class UnknownModelException(message: String) : Exception(message)

/** The CDN refused or failed the artifact GET. */
class ModelDownloadException(message: String) : Exception(message)

/** The downloaded artifact's sha256 does not match the signed catalog's (IN-D10). */
class ModelIntegrityException(message: String) : Exception(message)
