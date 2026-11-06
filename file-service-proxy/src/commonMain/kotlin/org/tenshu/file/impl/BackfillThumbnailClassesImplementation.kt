package org.tenshu.file.impl

import kotlinx.coroutines.CancellationException
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.db.ObjectStore
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.entity.DataId
import org.tenshu.core.file.ContentId
import org.tenshu.core.file.File
import org.tenshu.core.file.GetPreview
import org.tenshu.core.file.ThumbnailPreferences
import org.tenshu.core.file.fileCategoryForName
import org.tenshu.core.schema.propertyRef
import org.tenshu.file.proxy.FileBytesPropertyWriter
import org.tenshu.file.proxy.MotionThumbnailRenderer
import org.tenshu.file.proxy.ThumbnailClassBackfill
import org.tenshu.core.time.clock
import org.tenshu.sync.common.ContentResolvability
import org.tenshu.file.proxy.ThumbnailCacheSidecars
import org.tenshu.file.proxy.ThumbnailClass
import org.tenshu.file.proxy.ThumbnailClasses
import org.tenshu.file.proxy.decodeRow
import org.tenshu.file.proxy.pagesOfType
import org.tenshu.file.proxy.wantedThumbnailClassesInRealm
import org.tenshu.file.proxy.writeThumbnailBackfillMarker
import org.tenshu.storage.proxy.StorageCapacityHolder
import org.tenshu.storage.proxy.ensureWithinQuota
import org.tenshu.sync.common.SyncBlockEmitterHolder

/**
 * [org.tenshu.core.file.system.BackfillThumbnailClasses]'s proxy side: wires the pure
 * [ThumbnailClassBackfill] to the store, the file database, the quota gate and the one mint
 * (`drive-synced-thumbnails.md` §15.3 D-6).
 *
 * - **Enumeration is schema-driven and bounded** — `File` rows by type scan, never a walk of
 *   Drive's folder tree from `/` (`content-plane-refcount.md` §9.6b). ⚠ Folders are their own type
 *   (`Folder.ID`), so a scan of [File.ID] yields leaf files only.
 * - **`present(file)` is a stat**, one per declarable class, against
 *   [FileBytesPropertyWriter.thumbnailPath]; **`HasLocalData` is a stat** on the file's own path;
 *   a recorded decline is a stat on [thumbnailDeclinePath].
 * - **The mint is the upload's** — [ThumbnailClassMinter]: `GetPreview` with `GENERATE_FRESH` for a
 *   still, the [MotionThumbnailRenderer] seam for a clip, and the same write + `emitFileContent`.
 * - **Gating:** `XLARGE` for what `GetPreview` can render (an image, a video's poster, a PDF page);
 *   `MOTION` for a video, and only on a platform with a renderer wired (Android, §4.4).
 *
 * No-op without a [deviceId] (test / pre-sync wirings — there is no row to key a marker on) or
 * without an [objectStore] (a narrowed harness); a store that cannot enumerate reports itself so
 * and the marker is never written.
 */
internal class BackfillThumbnailClassesImplementation(
    private val fileDatabase: FileDatabase,
    private val objectStore: ObjectStore?,
    private val deviceId: String?,
    private val syncBlockEmitterHolder: SyncBlockEmitterHolder? = null,
    private val storageCapacityHolder: StorageCapacityHolder? = null,
    motionThumbnailRenderer: MotionThumbnailRenderer? = null,
    private val maxFilesPerRun: Int = ThumbnailClassBackfill.DEFAULT_MAX_FILES_PER_RUN,
    private val maxBytesPerRun: Long = ThumbnailClassBackfill.DEFAULT_MAX_BYTES_PER_RUN,
    private val report: (String) -> Unit = ::println,
) : AsyncOperationImplementation {

    private val minter = ThumbnailClassMinter(fileDatabase, syncBlockEmitterHolder, motionThumbnailRenderer)

    /** The last run's outcome, for tests. */
    var lastOutcome: ThumbnailClassBackfill.Outcome? = null
        private set

    /** Relay checks spent on eviction sentinels in the current run — see [MAX_REVALIDATIONS_PER_RUN]. */
    private var revalidationsThisRun = 0

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        val device = deviceId ?: return null as T
        val store = objectStore ?: return null as T
        // ⚠ Per *run*, and this is what makes that true: the production factory builds a fresh
        // implementation per dispatch, but nothing forces it to, and a memoised one would otherwise
        // believe every sentinel past the first 50 for good.
        revalidationsThisRun = 0
        lastOutcome = ThumbnailClassBackfill(
            wantedClasses = { executionContext.wantedThumbnailClassesInRealm(store) },
            candidates = { candidates(executionContext, store) },
            presentClasses = { candidate -> presentClasses(executionContext, candidate) },
            declinedClasses = ::declinedClasses,
            canMint = ::canMint,
            hasRoomFor = { thumbnailClass -> hasRoomFor(executionContext, thumbnailClass) },
            mint = { candidate, thumbnailClass -> mint(executionContext, candidate, thumbnailClass) },
            recordDecline = ::recordDecline,
            writeMarker = { marker -> executionContext.writeThumbnailBackfillMarker(device, marker) },
            canPublish = {
                syncBlockEmitterHolder?.fileContentEmitter?.canPublishContentNow() ?: true
            },
            maxFilesPerRun = maxFilesPerRun,
            maxBytesPerRun = maxBytesPerRun,
            report = report,
        ).run()
        return null as T
    }

    private fun candidates(
        executionContext: ExecutionContext,
        store: ObjectStore,
    ): Sequence<ThumbnailClassBackfill.Candidate>? {
        val pages = store.pagesOfType(File.ID) ?: return null
        return pages.flatMap { it.asSequence() }.mapNotNull { row ->
            val decoded = executionContext.decodeRow(store, row) ?: return@mapNotNull null
            // ⚠ A File's DataId IS its path (`File.ID` + the path as key), and `getDataId` is the
            // only route from a scanned row back to one — the row's hash is one-way. A row whose
            // type has no DataIdProvider is skipped rather than guessed at, as the schema-driven content walk does.
            val dataId = executionContext.getDataId(decoded) as? DataId.StringDataId ?: return@mapNotNull null
            val path = dataId.keyValue.get().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            // ⚠ `contentId` is a value property, read through the resolver off the stored row — the
            // same path `FileBytesPropertyWriter` and `GetStoredThumbnailImplementation` take, which
            // is what keeps this pass addressing the blob the upload wrote.
            val stored = executionContext.database.getData(dataId) as? File ?: return@mapNotNull null
            val contentId = runCatching {
                (executionContext.resolveProperty(stored, propertyRef(File.ContentId.id)) as? TString)?.get()
            }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            ThumbnailClassBackfill.Candidate(
                path = path,
                contentId = contentId,
                category = fileCategoryForName(path.substringAfterLast('/')),
                hasLocalBytes = fileDatabase.fileSize(path) != null,
            )
        }
    }

    private suspend fun presentClasses(
        executionContext: ExecutionContext,
        candidate: ThumbnailClassBackfill.Candidate,
    ): Set<String> {
        val present = mutableSetOf<String>()
        for (thumbnailClass in FileBytesPropertyWriter.THUMBNAIL_CLASSES) {
            val blobHere =
                fileDatabase.fileSize(FileBytesPropertyWriter.thumbnailPath(candidate.contentId, thumbnailClass)) != null
            if (blobHere || evictedDeliberately(executionContext, candidate, thumbnailClass)) {
                present += thumbnailClass
            }
        }
        return present
    }

    /**
     * ⚠⚠ **A class the keeper evicted counts as present** (`drive-xxlarge-thumbnail-rung.md` §4.7).
     * Presence is a local stat, so without this an eviction reads as *never minted*: this pass would
     * re-render, re-store and re-emit the class on the next tick, refilling the cache the ceiling
     * just made room in — up to the whole budget, every tick, for as long as the library is over.
     *
     * ⚠ The sentinel means "the relay has it", so it is only ever written for a blob whose bytes
     * were published (`ThumbnailCacheEviction`); an evicted blob that still owed the relay its bytes
     * leaves none, and this pass mints it again, which is what should happen.
     */
    private suspend fun evictedDeliberately(
        executionContext: ExecutionContext,
        candidate: ThumbnailClassBackfill.Candidate,
        thumbnailClass: String,
    ): Boolean {
        val writtenMs = ThumbnailCacheSidecars.evictedStampMs(fileDatabase, candidate.contentId, thumbnailClass)
            ?: return false
        // ⚠⚠ Only a device that could actually re-mint may clear a sentinel. Elsewhere — a desktop
        // holding a `MOTION` clip it pulled but cannot render — an ABSENT answer would drop the
        // sentinel, lose the class's "present" mark, and still mint nothing: strictly worse than
        // keeping it, and it would spend a revalidation slot to get there.
        if (!canMint(thumbnailClass, candidate)) return true
        val nowMs = executionContext.context.clock().nowEpochMillis()
        if (nowMs - writtenMs < ThumbnailCacheSidecars.EVICTED_REVALIDATE_INTERVAL_MS) return true

        // ⚠ Bounded per run: a library of evicted classes must not turn one pass into thousands of
        // relay round trips. What is not revalidated this time keeps its sentinel and waits a tick.
        if (revalidationsThisRun >= MAX_REVALIDATIONS_PER_RUN) return true
        val emitter = syncBlockEmitterHolder?.fileContentEmitter ?: return true
        revalidationsThisRun++

        val resolvability = emitter.contentResolvability(
            targetEntityId = fileDataId(executionContext, candidate.path),
            contentId = FileBytesPropertyWriter.derivedThumbnailContentId(candidate.contentId, thumbnailClass),
        )
        return when (resolvability) {
            // ⚠⚠ Only a definite "not here" clears it. An unreachable relay answering UNKNOWN must
            // never be read as "the relay lost it", or an offline device re-mints its whole library.
            ContentResolvability.ABSENT -> {
                ThumbnailCacheSidecars.clearEvicted(fileDatabase, candidate.contentId, thumbnailClass)
                report(
                    "ThumbnailClassBackfill -> the relay no longer has $thumbnailClass for " +
                        "${candidate.contentId}; it will be minted again",
                )
                false
            }
            ContentResolvability.RESOLVABLE -> {
                ThumbnailCacheSidecars.renewEvicted(fileDatabase, candidate.contentId, thumbnailClass, nowMs)
                true
            }
            ContentResolvability.UNKNOWN -> true
        }
    }

    /**
     * The classes a previous run recorded as declined for this file — a sentinel beside
     * the class's blob path.
     *
     * ⚠ **Called only for a file that is still missing a class** — the walk's own guard — so a
     * served library never stats a sentinel at all. Within such a file it checks every declarable
     * class rather than only the absent ones: one extra stat on a row that already has work, which
     * is not worth threading the absent set through the seam for.
     *
     * ⚠ The sentinel carries the mint format it was recorded under, and only a sentinel of the
     * *current* format counts: a bumped `MINT_FORMAT` (a new encoder, a fixed render) means every
     * earlier "no" is worth asking again, and the re-try overwrites the sentinel either way.
     */
    private fun declinedClasses(candidate: ThumbnailClassBackfill.Candidate): Set<String> =
        ThumbnailPreferences.DECLARABLE_CLASSES.filterTo(mutableSetOf()) { thumbnailClass ->
            val sentinel = thumbnailDeclinePath(candidate.contentId, thumbnailClass)
            // A stat first: the read happens only for a file that was actually declined.
            fileDatabase.fileSize(sentinel) != null &&
                fileDatabase.readBytes(sentinel)?.get()?.decodeToString() == ThumbnailClassBackfill.MINT_FORMAT
        }

    /**
     * ⚠⚠ **A write that fails is named, never swallowed.** The record is what makes a decline
     * terminal; without it the file is re-rendered on every tick and charged to the budget each
     * time, stalling the backfill — and since a decline does not withhold the marker, the run still
     * reports itself complete. So the failure has no other symptom than this line.
     *
     * ⚠ It is still not fatal: an unrecorded decline costs work, never correctness, and a run that
     * aborted here would strand the classes it had not reached yet.
     */
    private fun recordDecline(candidate: ThumbnailClassBackfill.Candidate, thumbnailClass: String) {
        runCatching {
            fileDatabase.writeRawByteArray(
                filePath = thumbnailDeclinePath(candidate.contentId, thumbnailClass),
                bytes = ThumbnailClassBackfill.MINT_FORMAT.encodeToByteArray(),
            )
        }.onFailure {
            report(
                "ThumbnailClassBackfill -> could NOT record the decline of $thumbnailClass for " +
                    "${candidate.contentId}; it will be rendered again next tick — $it",
            )
        }
    }

    /**
     * ⚠⚠ Read off the rung table, and a class the backfill does not own is never mintable. A
     * hand-written list here would skip a new backfilled rung without a sound
     * (`drive-xxlarge-thumbnail-rung.md` §3).
     */
    private fun canMint(thumbnailClass: String, candidate: ThumbnailClassBackfill.Candidate): Boolean {
        val rung = ThumbnailClasses.forClass(thumbnailClass)?.takeIf { it.backfilled } ?: return false
        return rung.canCarry(candidate.category, candidate.path, minter.canRenderMotion)
    }

    /**
     * The quota check for one blob of [thumbnailClass], at its cap rather than its likely size —
     * conservative on purpose, so a realm at the edge of its quota stops before the render, not
     * after it. `InsufficientStorageError` is the only stop; a harness with no storage wired
     * (`null` holder, `hasStorage == false`) is a no-op exactly as it is for an upload.
     */
    private fun hasRoomFor(executionContext: ExecutionContext, thumbnailClass: String): Boolean = try {
        storageCapacityHolder.ensureWithinQuota(executionContext, capFor(thumbnailClass).toLong())
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        false
    }

    private suspend fun mint(
        executionContext: ExecutionContext,
        candidate: ThumbnailClassBackfill.Candidate,
        thumbnailClass: String,
    ): ThumbnailClassBackfill.Mint {
        val rung = ThumbnailClasses.forClass(thumbnailClass)?.takeIf { it.backfilled }
            ?: return ThumbnailClassBackfill.Mint.Declined
        val rendered = when (rung) {
            is ThumbnailClass.Still -> minter.renderStill(
                executionContext, filePath = candidate.path,
                px = rung.px, format = GetPreview.Params.FORMAT_JPEG,
                maxBytes = rung.maxBytes,
            )
            is ThumbnailClass.Motion -> {
                // ⚠ The whole video is read into memory for the encoder, as the upload path holds
                // it anyway — one file at a time, released before the next.
                val bytes = fileDatabase.readBytes(candidate.path)?.get()
                    ?: return ThumbnailClassBackfill.Mint.Declined
                minter.renderMotion(
                    fileName = candidate.path.substringAfterLast('/'),
                    data = bytes,
                    maxBytes = rung.maxBytes,
                )
            }
        }
        val bytes = when (rendered) {
            is ThumbnailClassMinter.Render.Bytes -> rendered.bytes
            ThumbnailClassMinter.Render.Declined -> return ThumbnailClassBackfill.Mint.Declined
            is ThumbnailClassMinter.Render.Failed -> return ThumbnailClassBackfill.Mint.Failed
        }
        val stored = minter.store(
            executionContext = executionContext,
            dataId = fileDataId(executionContext, candidate.path),
            contentId = candidate.contentId,
            propertyId = rung.property.id,
            thumbnailClass = thumbnailClass,
            thumbnail = bytes,
        )
        return if (stored) ThumbnailClassBackfill.Mint.Stored(bytes.size) else ThumbnailClassBackfill.Mint.Failed
    }

    private fun fileDataId(executionContext: ExecutionContext, path: String): DataId =
        DataId.StringDataId.create(context = executionContext.context, type = File.ID, keyValue = path)

    /**
     * The class's own cap from the rung table, so a new rung is quota-checked at its own size. An
     * unknown class cannot reach here — [canMint] filters it first — and is checked at the largest
     * cap if it did.
     */
    private fun capFor(thumbnailClass: String): Int =
        ThumbnailClasses.forClass(thumbnailClass)?.maxBytes ?: ThumbnailClasses.ALL.maxOf { it.maxBytes }

    internal companion object {
        /**
         * Eviction sentinels revalidated against the relay in one pass.
         *
         * ⚠ Each is a manifest read plus a presence check per chunk, so an unbounded sweep over a
         * library of evicted classes would be a long burst of relay traffic on a schedule meant to be
         * invisible. 50 a day reaches 350 sentinels a week, and what waits keeps its sentinel — the
         * cost of deferring is a class staying evicted, which is what it already is.
         */
        const val MAX_REVALIDATIONS_PER_RUN = 50

        /**
         * Where a recorded decline lives: beside the class's own blob, keyed by the file's
         * `contentId` exactly as the blob is — so a re-upload's new content id starts clean, and
         * nothing that addresses `/.thumbnails/<contentId>/<class>` can mistake it for the blob.
         */
        fun thumbnailDeclinePath(fileContentId: String, thumbnailClass: String): String =
            FileBytesPropertyWriter.thumbnailPath(fileContentId, thumbnailClass) + ".declined"
    }
}
