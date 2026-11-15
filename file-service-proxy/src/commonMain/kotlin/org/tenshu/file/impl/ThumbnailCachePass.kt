package org.tenshu.file.impl

import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.optionalFieldValue
import org.tenshu.core.file.ContentId
import org.tenshu.core.file.File
import org.tenshu.core.file.Folder
import org.tenshu.core.file.sync.FileSyncPreferences
import org.tenshu.core.file.sync.SyncPolicy
import org.tenshu.core.file.sync.SyncRuleSet
import org.tenshu.core.schema.isSubtypeOf
import org.tenshu.core.schema.propertyRef
import org.tenshu.core.time.clock
import org.tenshu.file.proxy.FileBytesPropertyWriter
import org.tenshu.file.proxy.ThumbnailCacheSidecars
import org.tenshu.file.proxy.ThumbnailClasses
import org.tenshu.file.proxy.readFileSyncRuleSet
import org.tenshu.file.proxy.readLongDeviceSetting
import org.tenshu.sync.common.SyncBlockEmitterHolder

/**
 * Where the thumbnail-cache pass differs by platform (`drive-xxlarge-thumbnail-rung.md` D-20, §4.7).
 * [NATIVE] is the offline keeper's behaviour, unchanged; [WEB] is the browser's.
 */
class ThumbnailCachePolicy(
    /** The ceiling when this device has stored no `MaxThumbnailBytes` row. */
    val defaultMaxBytes: Long,
    /**
     * ⚠ Leave out a rung the relay has not taken yet. Native evicts it (a byte holder's backfill mints
     * it again); a browser runs no backfill, so in a web-only realm that class would be gone for good.
     */
    val keepPendingPublish: Boolean,
    /**
     * How a blob with no recency sorts. Native: last (a platform that cannot report a time must not
     * read as maximally cold). Web: first — `IndexedDbFileDatabase` reports no write time, so a rung
     * with no `.read` stamp is one this browser minted at upload and never looked at.
     */
    val unknownRecencyIsCold: Boolean,
) {
    companion object {
        val NATIVE = ThumbnailCachePolicy(
            defaultMaxBytes = FileSyncPreferences.DEFAULT_MAX_THUMBNAIL_BYTES,
            keepPendingPublish = false,
            unknownRecencyIsCold = false,
        )

        /**
         * ⚠⚠ 64 MiB, not native's 1 GiB: `IndexedDbFileDatabase.open` loads every stored byte into
         * the page's memory, so this ceiling is RAM. ~130–450 opened photographs (0.14–0.5 MB a rung).
         * Raise it only once the JS store reads through instead of hydrating eagerly.
         */
        val WEB = ThumbnailCachePolicy(
            defaultMaxBytes = WEB_DEFAULT_MAX_THUMBNAIL_BYTES,
            keepPendingPublish = true,
            unknownRecencyIsCold = true,
        )

        const val WEB_DEFAULT_MAX_THUMBNAIL_BYTES: Long = 64L * 1024 * 1024
    }
}

/**
 * The thumbnail-cache pass on its own: walks the file tree and evicts stored classes above `LARGE`
 * over this device's `MaxThumbnailBytes` (else [policy]'s default), coldest first. The web runtime
 * runs this alone; the offline keeper runs the same pass after its other two halves.
 */
suspend fun ExecutionContext.evictThumbnailCache(
    fileDatabase: FileDatabase,
    deviceId: String,
    syncBlockEmitterHolder: SyncBlockEmitterHolder?,
    policy: ThumbnailCachePolicy,
) {
    val rootId = DataId.StringDataId.create(context = context, type = File.ID, keyValue = "/")
    val paths = mutableListOf<String>()
    collectFilePaths(this, rootId, paths)
    evictThumbnailCache(fileDatabase, deviceId, syncBlockEmitterHolder, readFileSyncRuleSet(deviceId), paths, policy)
}

/**
 * Drops a thumbnail class that was pulled only to be drawn once (a web grid tile scales it down and
 * keeps the small copy): the blob and its read stamp. ⚠ No `.evicted` sentinel — the relay holds the
 * class (it was just pulled from there), and nothing on the web reads the sentinel.
 * ⚠⚠ Only for a class the caller itself pulled: never one this device minted.
 */
fun dropPulledThumbnail(fileDatabase: FileDatabase, contentId: String, thumbnailClass: String) {
    runCatching { fileDatabase.deleteFile(FileBytesPropertyWriter.thumbnailPath(contentId, thumbnailClass)) }
    runCatching { fileDatabase.deleteFile(ThumbnailCacheSidecars.readStampPath(contentId, thumbnailClass)) }
}

internal suspend fun ExecutionContext.evictThumbnailCache(
    fileDatabase: FileDatabase,
    deviceId: String,
    syncBlockEmitterHolder: SyncBlockEmitterHolder?,
    ruleSet: SyncRuleSet,
    paths: List<String>,
    policy: ThumbnailCachePolicy,
) {
    val executionContext = this
    val maxThumbnailBytes = readLongDeviceSetting(
        deviceId = deviceId,
        preferenceId = FileSyncPreferences.MaxThumbnailBytes.id,
        fallback = policy.defaultMaxBytes,
    )
    val minFreeSpaceBytes = readLongDeviceSetting(
        deviceId = deviceId,
        preferenceId = FileSyncPreferences.MinFreeSpaceBytes.id,
        fallback = FileSyncPreferences.MinFreeSpaceBytes.default,
    )
    val emitter = syncBlockEmitterHolder?.fileContentEmitter

    val cached = mutableListOf<CachedThumbnail>()
    val evictableClasses = ThumbnailClasses.ALL.filter { ThumbnailCacheSidecars.isEvictable(it.id) }
    for (path in paths) {
        // ⚠⚠ A pinned file's classes are pinned too. The rung follows its own ceiling rather than
        // the file's policy (§4.7), but "keep this offline" cannot mean "and blank its picture":
        // the hub pins `/` and runs this pass every backup, so without this it would trim the one
        // archive that exists to hold everything.
        if (ruleSet.effectivePolicy(path) == SyncPolicy.KEEP_OFFLINE) continue
        val dataId = DataId.StringDataId.create(context = context, type = File.ID, keyValue = path)
        val contentId = contentIdOf(executionContext, dataId) ?: continue
        for (thumbnailClass in evictableClasses) {
            val blobPath = FileBytesPropertyWriter.thumbnailPath(contentId, thumbnailClass.id)
            val size = fileDatabase.fileSize(blobPath) ?: continue
            if (policy.keepPendingPublish && emitter?.hasQueuedContentFor(dataId, thumbnailClass.property.id) == true) continue
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
        unknownRecencyIsCold = policy.unknownRecencyIsCold,
    )
    val nowMs = context.clock().nowEpochMillis()
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
        val dataId = DataId.StringDataId.create(context = context, type = File.ID, keyValue = blob.filePath)
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
internal fun contentIdOf(executionContext: ExecutionContext, dataId: DataId): String? = runCatching {
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
internal fun collectFilePaths(
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
