package org.tenshu.file.impl

import kotlinx.coroutines.CancellationException
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.onDemandResolution
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.optionalFieldValue
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.Data
import org.tenshu.core.file.File
import org.tenshu.core.file.Folder
import org.tenshu.core.file.sync.FileSyncPreferences
import org.tenshu.core.file.sync.SyncPolicy
import org.tenshu.core.file.sync.SyncRuleSet
import org.tenshu.core.file.ContentId
import org.tenshu.core.schema.isSubtypeOf
import org.tenshu.core.schema.propertyRef
import org.tenshu.core.time.clock
import org.tenshu.file.proxy.FileBytesPropertyWriter
import org.tenshu.file.proxy.ThumbnailCacheSidecars
import org.tenshu.file.proxy.ThumbnailClasses
import org.tenshu.sync.common.SyncBlockEmitterHolder
import org.tenshu.file.proxy.readFileSyncRuleSet
import org.tenshu.file.proxy.readLongDeviceSetting

/**
 * The keeper's pure download-selection decision, split out so it's unit-testable
 * without a proxy: the paths whose effective policy is [SyncPolicy.KEEP_OFFLINE]
 * but whose bytes are **absent** locally. [files] is `(path, hasLocalBytes)`.
 */
internal fun selectKeepOfflineDownloads(
    ruleSet: SyncRuleSet,
    files: List<Pair<String, Boolean>>,
    /**
     * ⚠⚠ The download half's network rule (`control-background-work-off-fgs.md` §5.6). Kept-offline
     * content is fetched on an unmetered network only, so a metered one selects nothing. The rule
     * lives here, not as the schedule's `NETWORK_UNMETERED` constraint, which would hold back the
     * eviction half too: on Android WorkManager is the item's only driver, and a mostly-cellular
     * device would never evict. The schedule asks for no network.
     */
    metered: Boolean = false,
): List<String> = if (metered) emptyList() else files
    .filter { (path, hasLocalBytes) ->
        !hasLocalBytes && ruleSet.effectivePolicy(path) == SyncPolicy.KEEP_OFFLINE
    }
    .map { it.first }

/**
 * The offline keeper's proxy-side reconcile (`OfflineKeeper`,
 * `drive-offline-online.md` §3.2 background half). **Two halves, one tree walk:**
 *
 *  1. **Download** — every file whose effective policy is [SyncPolicy.KEEP_OFFLINE]
 *     but whose bytes are absent locally gets its OnDemand fetch driven.
 *  2. **Evict** — content over the device's local-cache ceiling or under its
 *     free-disk floor is dropped coldest-first (`retention-quota-model.md` Face B,
 *     `drive-offline-online.md` §4.4).
 *  3. **Evict thumbnails** — the same decision over a second, much smaller plane: the stored
 *     thumbnail classes a byte holder mints and a client pulls (`drive-xxlarge-thumbnail-rung.md`
 *     D-20). ⚠ Its own ceiling, not the file's rules: a rung is cheap, re-fetchable and belongs to
 *     no folder, so it follows neither the file's pin nor its policy.
 *
 * ⚠ The two cannot fight: the download half only ever fetches `KEEP_OFFLINE`
 * content, and the evict half never touches it. Nothing downloaded in step 1 is a
 * candidate in step 2, so the ordering is safe by construction rather than by luck.
 *
 * Self-driving and param-less: reads the device's [org.tenshu.core.file.sync.SyncRuleSet]
 * off the DB, walks the file tree, and materialises the missing `KEEP_OFFLINE`
 * bytes via `OnDemandResolution.resolve` (the same fetch-and-persist a client
 * `getRemoteFile` triggers, minus the round-trip). Best-effort per file — one
 * failed fetch (offline, decode error) is skipped, not fatal. No-op without a
 * [deviceId] (test / pre-sync wirings); an absent `OnDemandResolution` component
 * skips only the download half — the ceiling still applies, since eviction needs
 * nothing but the file database.
 */
internal class OfflineKeeperImplementation(
    private val fileDatabase: FileDatabase,
    private val deviceId: String?,
    /**
     * The content-emit journal, read-only: [org.tenshu.sync.common.FileContentSyncEmitter.hasQueuedContentFor]
     * is how both sweeps learn that bytes have not reached the relay yet.
     *
     * ⚠⚠ Null — a narrowed harness, a pre-sync wiring — reads as "nothing is owed", which is a
     * fail-open. It is sound here only because a proxy with no content emitter has no relay to owe
     * bytes to: nothing journalled them, so nothing is waiting to be sent. Production always passes
     * the holder (`FileServiceProxyImplementationFactory`), and `initSyncService` fills it before
     * the scheduler can dispatch this operation.
     *
     * ⚠ Its other effect, on the thumbnail plane: a null emitter records every eviction as
     * published, so the sentinel is the only thing a byte holder consults and the class is not
     * re-minted.
     */
    private val syncBlockEmitterHolder: SyncBlockEmitterHolder? = null,
    /** Whether the current network is metered: the download half waits for an unmetered one. */
    private val meteredNetwork: org.tenshu.file.proxy.MeteredNetworkSignal = org.tenshu.file.proxy.MeteredNetworkSignal.Unknown,
) : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        val device = deviceId ?: return null as T
        val ruleSet = executionContext.readFileSyncRuleSet(device)

        val rootId = DataId.StringDataId.create(
            context = executionContext.context,
            type = File.ID,
            keyValue = "/",
        )
        val paths = mutableListOf<String>()
        collectFilePaths(executionContext, rootId, paths)

        val resolver = executionContext.context.onDemandResolution()
        if (resolver != null) {
            val files = paths.map { it to (fileDatabase.fileSize(it) != null) }
            for (path in selectKeepOfflineDownloads(ruleSet, files, metered = meteredNetwork.isMetered())) {
                try {
                    resolver.resolve(
                        executionContext = executionContext,
                        entityId = DataId.StringDataId.create(
                            context = executionContext.context,
                            type = File.ID,
                            keyValue = path,
                        ),
                        propertyId = File.Data.id,
                        params = null,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    // Best-effort: a single failed fetch (offline, decode) is skipped.
                }
            }
        }

        evictOverCeiling(executionContext, device, ruleSet, paths)
        evictThumbnailsOverCeiling(executionContext, device, ruleSet, paths)
        return null as T
    }

    /**
     * The evict half (`retention-quota-model.md` Face B). Runs *after* the download
     * half so a pin fetched in this same pass is already visible as pinned-and-local
     * — it is exempt either way, but the free-disk floor sees the disk as it now is.
     */
    private suspend fun evictOverCeiling(
        executionContext: ExecutionContext,
        deviceId: String,
        ruleSet: SyncRuleSet,
        paths: List<String>,
    ) {
        val maxLocalBytes = executionContext.readLongDeviceSetting(
            deviceId = deviceId,
            preferenceId = FileSyncPreferences.MaxLocalBytes.id,
            fallback = FileSyncPreferences.MaxLocalBytes.default,
        )
        val minFreeSpaceBytes = executionContext.readLongDeviceSetting(
            deviceId = deviceId,
            preferenceId = FileSyncPreferences.MinFreeSpaceBytes.id,
            fallback = FileSyncPreferences.MinFreeSpaceBytes.default,
        )

        val emitter = syncBlockEmitterHolder?.fileContentEmitter
        val cached = mutableListOf<CachedFile>()
        for (path in paths) {
            val size = fileDatabase.fileSize(path) ?: continue
            // ⚠⚠ **Bytes this device still owes the relay are not cache.** Deleting them loses the
            // only copy: the journalled emit names a `filePath` that no longer exists, so it can
            // never be sent, and the `File` row is left pointing at content that is nowhere — the
            // `retention-quota-model.md` §2 confusion between durable content and the delivery log.
            // The thumbnail sweep below asks the same question.
            //
            // ⚠⚠ **Not insurance against the ceiling alone.** The cache ceiling is off by default,
            // but the *free-disk floor* beside it is on by default at 1 GiB, and
            // `selectCacheEvictions` takes `max(overCeiling, overFloor)` — so on any device under a
            // gigabyte free, this sweep reaches files whose only copy is local.
            //
            // ⚠⚠ Asked as `hasQueuedContentFor`, never `queuedContentBytesFor(...) > 0`: the byte
            // figure is a quota answer and reads `0` for a journalled intent that weighs nothing —
            // an empty file, or one whose path no longer sizes — which is precisely the row this
            // guard exists to spare.
            //
            // ⚠ Excluded from the **candidate set**, so these bytes are not counted toward the
            // ceiling either (`FileSyncPreferences.MaxLocalBytes`): counting bytes eviction may not
            // spend makes the ceiling unsatisfiable, and a sweep that cannot satisfy it evicts
            // everything it *is* allowed to touch, forever. The device therefore holds `pinned +
            // unsent + at most the ceiling`; bounding the total is the free-disk floor's job.
            val dataId = DataId.StringDataId.create(
                context = executionContext.context,
                type = File.ID,
                keyValue = path,
            )
            if (emitter?.hasQueuedContentFor(dataId, File.Data.id) == true) continue
            cached += CachedFile(
                path = path,
                sizeBytes = size,
                lastModifiedMs = fileDatabase.lastModifiedMs(path),
            )
        }

        val evictions = selectCacheEvictions(
            ruleSet = ruleSet,
            cached = cached,
            maxLocalBytes = maxLocalBytes,
            availableSpaceBytes = fileDatabase.availableSpaceBytes(),
            minFreeSpaceBytes = minFreeSpaceBytes,
        )
        if (evictions.isEmpty()) return

        evictions.forEach { path ->
            fileDatabase.deleteFile(path)
        }
    }

    /**
     * The thumbnail plane's ceiling (`drive-xxlarge-thumbnail-rung.md` §4.7, D-20). Runs last, so
     * the free-disk floor sees the disk after both halves above have spent what they will.
     *
     * ⚠⚠ **The candidates are derived, not enumerated.** A rung blob has no `File` row by design, and
     * `FileDatabase` cannot list a directory on any platform, so the sweep walks the same file paths
     * the halves above did and asks each one's `contentId` × the evictable classes — the shape
     * `BackfillThumbnailClassesImplementation.presentClasses` already uses. A blob whose file is gone
     * is therefore invisible here; that is `DeleteFile`'s leak to close, not this sweep's.
     */
    private suspend fun evictThumbnailsOverCeiling(
        executionContext: ExecutionContext,
        deviceId: String,
        ruleSet: SyncRuleSet,
        paths: List<String>,
    ) {
        val maxThumbnailBytes = executionContext.readLongDeviceSetting(
            deviceId = deviceId,
            preferenceId = FileSyncPreferences.MaxThumbnailBytes.id,
            fallback = FileSyncPreferences.MaxThumbnailBytes.default,
        )
        val minFreeSpaceBytes = executionContext.readLongDeviceSetting(
            deviceId = deviceId,
            preferenceId = FileSyncPreferences.MinFreeSpaceBytes.id,
            fallback = FileSyncPreferences.MinFreeSpaceBytes.default,
        )

        val cached = mutableListOf<CachedThumbnail>()
        val evictableClasses = ThumbnailClasses.ALL.filter { ThumbnailCacheSidecars.isEvictable(it.id) }
        for (path in paths) {
            // ⚠⚠ A pinned file's classes are pinned too. The rung follows its own ceiling rather than
            // the file's policy (§4.7), but "keep this offline" cannot mean "and blank its picture":
            // the hub pins `/` and runs this pass every backup, so without this it would trim the one
            // archive that exists to hold everything.
            if (ruleSet.effectivePolicy(path) == SyncPolicy.KEEP_OFFLINE) continue
            val dataId = DataId.StringDataId.create(
                context = executionContext.context,
                type = File.ID,
                keyValue = path,
            )
            val contentId = contentIdOf(executionContext, dataId) ?: continue
            for (thumbnailClass in evictableClasses) {
                val blobPath = FileBytesPropertyWriter.thumbnailPath(contentId, thumbnailClass.id)
                val size = fileDatabase.fileSize(blobPath) ?: continue
                cached += CachedThumbnail(
                    path = blobPath,
                    filePath = path,
                    contentId = contentId,
                    thumbnailClass = thumbnailClass.id,
                    propertyId = thumbnailClass.property.id,
                    sizeBytes = size,
                    // The read stamp when something has opened it here, else the write time: a blob
                    // this device pulled and never looked at is colder than one it renders daily.
                    lastAccessMs = ThumbnailCacheSidecars.readStampMs(fileDatabase, contentId, thumbnailClass.id)
                        ?: fileDatabase.lastModifiedMs(blobPath),
                )
            }
        }
        if (cached.isEmpty()) return

        val evictions = selectThumbnailEvictions(
            cached = cached,
            maxThumbnailBytes = maxThumbnailBytes,
            availableSpaceBytes = fileDatabase.availableSpaceBytes(),
            minFreeSpaceBytes = minFreeSpaceBytes,
        )
        val nowMs = executionContext.context.clock().nowEpochMillis()
        val emitter = syncBlockEmitterHolder?.fileContentEmitter
        for (blob in evictions) {
            runCatching { fileDatabase.deleteFile(blob.path) }
            // ⚠⚠ Checked, not assumed: `FileDatabase.deleteFile` returns `Unit`, its interface default
            // is a no-op and both file-backed impls discard `File.delete()`'s result, so a `runCatching`
            // around it proves nothing. A blob still here must not be marked evicted — that would tell
            // the backfill it is safely on the relay while this device silently freed nothing.
            if (fileDatabase.fileSize(blob.path) != null) continue
            runCatching {
                fileDatabase.deleteFile(ThumbnailCacheSidecars.readStampPath(blob.contentId, blob.thumbnailClass))
            }
            val dataId = DataId.StringDataId.create(
                context = executionContext.context,
                type = File.ID,
                keyValue = blob.filePath,
            )
            // Bytes this device still owes the relay leave no sentinel: nothing published them, so a
            // byte holder should mint the class again rather than believe it is safe there.
            //
            // ⚠ The *existence* of the entry, not its weight — `queuedContentBytesFor` is a quota
            // figure that answers `0` for a live intent whose file is empty or no longer sizes, and
            // reading that as "published" stamps the sentinel over bytes the relay never took. Milder
            // than the file plane's version of this mistake (a missing class, not a lost file) but
            // the same misreading, so it asks the same question.
            val publishPending = emitter?.hasQueuedContentFor(dataId, blob.propertyId) == true
            if (!publishPending) {
                // ⚠⚠ Without this the backfill reads the class as never minted and re-renders it on
                // the next tick, refilling the cache over its own ceiling. A failed write is not
                // fatal — it costs one re-mint, where a failed *delete* above would cost the space.
                runCatching {
                    fileDatabase.writeRawByteArray(
                        filePath = ThumbnailCacheSidecars.evictedPath(blob.contentId, blob.thumbnailClass),
                        bytes = nowMs.toString().encodeToByteArray(),
                    )
                }
            }
        }
    }

    /** The file row's `contentId` — the blob address's other half. */
    private fun contentIdOf(executionContext: ExecutionContext, dataId: DataId): String? = runCatching {
        val row = executionContext.database.getData(dataId) ?: return null
        (executionContext.resolveProperty(row, propertyRef(File.ContentId.id)) as? TString)
            ?.get()
            ?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    /**
     * Depth-first collect of every **leaf file** path under [dataId] (folders
     * excluded). Mirrors `DeleteFileImplementation.deleteRecursively`'s tree walk:
     * a folder is a `File` subtype carrying a `Folder.Fields.FILES` array.
     */
    private fun collectFilePaths(
        executionContext: ExecutionContext,
        dataId: DataId,
        out: MutableList<String>,
    ) {
        val data = executionContext.database.getData(dataId, asEntity = true) ?: return
        if (data !is Entity<*>) return
        val isFolder = executionContext.schemaProvider.getSchema(data.type.get())
            ?.isSubtypeOf(Folder.ID) == true
        if (isFolder) {
            val filesField = data.getFieldValue<Data>(Folder.Fields.FILES)
            val children = executionContext.resolveList(filesField) ?: emptySequence()
            children.forEach { child ->
                executionContext.getDataId(child)?.let { collectFilePaths(executionContext, it, out) }
            }
        } else {
            runCatching { data.optionalFieldValue<TString>(File.Field.PATH)?.get() }
                .getOrNull()
                ?.let { out += it }
        }
    }
}
