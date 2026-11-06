package org.tenshu.file.impl

import kotlinx.coroutines.CancellationException
import org.tenshu.core.Data
import org.tenshu.core.TBoolean
import org.tenshu.core.TByteArray
import org.tenshu.core.TString
import org.tenshu.core.number.TInteger
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.common.proxy.onDemandResolution
import org.tenshu.core.entity.DataId
import org.tenshu.core.file.ContentId
import org.tenshu.core.file.Data
import org.tenshu.core.file.File
import org.tenshu.core.file.Size
import org.tenshu.core.file.FilePath
import org.tenshu.core.file.GetPreview
import org.tenshu.core.file.ThumbnailPreferences
import org.tenshu.core.file.fileCategoryForName
import org.tenshu.core.file.system.EnsureThumbnailClass
import org.tenshu.core.schema.propertyRef
import org.tenshu.core.time.clock
import org.tenshu.file.proxy.FileBytesPropertyWriter
import org.tenshu.file.proxy.MotionThumbnailRenderer
import org.tenshu.file.proxy.MeteredNetworkSignal
import org.tenshu.file.proxy.ThumbnailCacheSidecars
import org.tenshu.file.proxy.ThumbnailClassBackfill
import org.tenshu.file.proxy.ThumbnailClass
import org.tenshu.file.proxy.ThumbnailClasses
import org.tenshu.storage.proxy.StorageCapacityHolder
import org.tenshu.storage.proxy.ensureWithinQuota
import org.tenshu.sync.common.ContentResolvability
import org.tenshu.sync.common.SyncBlockEmitterHolder

/**
 * Mint on view (`drive-xxlarge-thumbnail-rung.md` §4.6, X-4) — the operation behind
 * [EnsureThumbnailClass]. For a photograph whose byte holder is offline or gone, this is the only
 * route to a rung, and it is expensive: the last resort downloads the whole original.
 *
 * The order of the gates **is** the design, so it is spelled out rather than left to the reader:
 *
 * 1. **already here** — a stat, like the backfill's;
 * 2. **already declined** at this quality — the backfill's sentinel, so a frame this device cannot
 *    fit under the cap is not re-rendered on every open;
 * 3. **pull it** — some other device may have published it, which is far cheaper than minting.
 *    ⚠⚠ A *failed* read must not fall through to the mint: that would turn a flaky relay into a
 *    6–18 MB download and a duplicate of a rung that already exists;
 * 4. **quota, before the download** — at or near quota the relay refuses the rung, so minting would
 *    spend the original's bytes for nothing;
 * 5. **metered** — unless the user asked for this picture specifically;
 * 6. **the original**, on demand;
 * 7. **render and store**, which publishes inline (D-19).
 *
 * ⚠ Steps 4 and 5 are before 6 for the same reason: they are the two that make the download
 * pointless or unwanted, and the download is the expensive part.
 */
internal class EnsureThumbnailClassImplementation(
    private val fileDatabase: FileDatabase,
    private val syncBlockEmitterHolder: SyncBlockEmitterHolder? = null,
    private val storageCapacityHolder: StorageCapacityHolder? = null,
    private val meteredNetwork: MeteredNetworkSignal = MeteredNetworkSignal.Unknown,
    motionThumbnailRenderer: MotionThumbnailRenderer? = null,
) : AsyncOperationImplementation {

    private val minter = ThumbnailClassMinter(fileDatabase, syncBlockEmitterHolder, motionThumbnailRenderer)

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val path: FilePath = params.read(EnsureThumbnailClass.Params.PATH)
        val thumbnailClass: TString = params.read(EnsureThumbnailClass.Params.THUMBNAIL_CLASS)
        val userRequested: Boolean =
            (params?.get(EnsureThumbnailClass.Params.USER_REQUESTED) as? TBoolean)?.get() ?: false

        return ensure(executionContext, path.get(), thumbnailClass.get(), userRequested) as T
    }

    private suspend fun ensure(
        executionContext: ExecutionContext,
        filePath: String,
        thumbnailClass: String,
        userRequested: Boolean,
    ): TByteArray? {
        // ⚠ Only a declarable class may be asked for. `LIST` and `LARGE` are minted at upload for
        // every file and are never absent in a way a download could fix.
        if (thumbnailClass !in ThumbnailPreferences.DECLARABLE_CLASSES) return null
        val rung = ThumbnailClasses.forClass(thumbnailClass) as? ThumbnailClass.Still ?: return null

        val dataId = DataId.StringDataId.create(
            context = executionContext.context,
            type = File.ID,
            keyValue = filePath,
        )
        val row = executionContext.database.getData(dataId) ?: return null
        val contentId = runCatching {
            (executionContext.resolveProperty(row, propertyRef(File.ContentId.id)) as? TString)
        }.getOrNull()
            ?.get()
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        val blobPath = FileBytesPropertyWriter.thumbnailPath(contentId, thumbnailClass)

        // 1. Already here. ⚠ Stat first, like every sibling: a `readBytes` miss logs a warning on
        // Android, and a miss is the *common* case for an op that exists to fetch absent classes.
        if (fileDatabase.fileSize(blobPath) != null) {
            served(executionContext, contentId, thumbnailClass)?.let { return it }
        }

        // 2. Already declined at this quality — a stat, and no network.
        if (declined(contentId, thumbnailClass)) return null

        // 3. Someone else may have published it — far cheaper than minting.
        pull(executionContext, dataId, rung)
        if (fileDatabase.fileSize(blobPath) != null) {
            served(executionContext, contentId, thumbnailClass)?.let { return it }
        }

        // ⚠⚠ **The pull not landing it is not an absence**, and this is the only place that can tell
        // the difference. `OnDemandResolution.resolve` answers `Success` whether or not it fetched
        // anything — for a content-addressed thumbnail it *cannot* answer `Failure` at all, because
        // `ContentBlobStore.getChunk` collapses a missing blob and a failed request into the same
        // null (`ContentResolvability`'s own KDoc). So a 503 or a captive portal would otherwise read
        // as "nobody ever minted this" and spend a whole original proving it.
        // ⚠ Only a definite ABSENT licenses the mint. UNKNOWN is an unreachable relay, and
        // RESOLVABLE means the rung is there and this device simply failed to get it — a retry lands
        // it for nothing. Same rule, and the same reasoning, as X-2b's eviction sentinel.
        if (!confirmedAbsentOnTheRelay(executionContext, dataId, contentId, thumbnailClass)) return null

        // Nothing to render from is not a failure to report, it is simply not possible here.
        if (!rung.canCarry(fileCategoryForName(filePath.substringAfterLast('/')), filePath, minter.canRenderMotion)) {
            return null
        }

        // 4. Quota, before the download.
        if (!hasRoomFor(executionContext, rung)) return null

        // 5. Metered, unless the user asked for this picture.
        if (!userRequested && meteredNetwork.isMetered()) return null

        // 6. ⚠⚠ **How big is the thing we are about to fetch?** The gates above bound the *rung*
        // (1 MiB against the relay's quota) and the network it travels over, and neither says
        // anything about the download itself. `canCarry` admits video and PDF for every still rung,
        // and the viewer has no video branch — so a one-second look at a 4K clip would fetch the
        // whole clip, hundreds of megabytes, to render a 2048 px poster frame. ⚠ The doc's
        // "6–18 MB" is a description of phone photographs, not a limit anything enforced.
        // ⚠ `null` size means the row does not say; treated as too big, because the alternative is
        // an unbounded fetch on a guess.
        // ⚠ Only when a transfer would actually happen. A 90 MB scan this device already holds costs
        // nothing to render from, and refusing it would be a size limit on *rendering*, which is not
        // what this bounds.
        if (fileDatabase.fileSize(filePath) == null) {
            val originalBytes =
                (executionContext.resolveProperty(row, propertyRef(File.Size.id)) as? TInteger)?.get()
            if (originalBytes == null || originalBytes > MAX_ORIGINAL_BYTES_FOR_A_VIEW_MINT) return null
        }

        // 7. The original.
        if (!originalOnDisk(executionContext, dataId, filePath)) return null

        // 8. Render and store — `store` publishes inline, which is D-19.
        val rendered = minter.renderStill(
            executionContext = executionContext,
            filePath = filePath,
            px = rung.px,
            format = GetPreview.Params.FORMAT_JPEG,
            maxBytes = rung.maxBytes,
        )
        val bytes = when (rendered) {
            is ThumbnailClassMinter.Render.Bytes -> rendered.bytes
            // ⚠ A decline is recorded, so the next open is a stat rather than another download.
            ThumbnailClassMinter.Render.Declined -> {
                recordDecline(contentId, thumbnailClass)
                return null
            }
            is ThumbnailClassMinter.Render.Failed -> return null
        }
        minter.store(
            executionContext = executionContext,
            dataId = dataId,
            contentId = contentId,
            propertyId = rung.property.id,
            thumbnailClass = thumbnailClass,
            thumbnail = bytes,
        )
        return served(executionContext, contentId, thumbnailClass)
    }

    /**
     * Read a class out and **stamp it read**, so the keeper's ceiling sees it as warm.
     *
     * ⚠⚠ Without the stamp a rung this op serves on every open looks *cold* to X-2a's coldest-first
     * sweep — `lastAccessMs` falls back to the blob's write time — so the classes a viewer actually
     * uses are evicted before ones nothing has ever opened. `GetStoredThumbnailImplementation` does
     * the same thing for the same reason.
     */
    private fun served(
        executionContext: ExecutionContext,
        contentId: String,
        thumbnailClass: String,
    ): TByteArray? {
        val bytes = fileDatabase.readBytes(
            FileBytesPropertyWriter.thumbnailPath(contentId, thumbnailClass)
        ) ?: return null
        ThumbnailCacheSidecars.stampRead(
            fileDatabase = fileDatabase,
            fileContentId = contentId,
            thumbnailClass = thumbnailClass,
            nowMs = executionContext.context.clock().nowEpochMillis(),
        )
        return bytes
    }

    /** Best-effort: the rung lands on disk or it does not, and [confirmedAbsentOnTheRelay] decides. */
    private suspend fun pull(
        executionContext: ExecutionContext,
        dataId: DataId,
        rung: ThumbnailClass.Still,
    ) {
        val resolver = executionContext.context.onDemandResolution() ?: return
        try {
            resolver.resolve(
                executionContext = executionContext,
                entityId = dataId,
                propertyId = rung.property.id,
                params = null,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // Swallowed deliberately: the question "is it on the relay" is asked separately, and
            // asked of something that can actually answer it.
        }
    }

    /**
     * Whether the relay **answered**, and answered that this rung is not there.
     *
     * ⚠ `false` for UNKNOWN as well as RESOLVABLE: an emitter that cannot tell must not be read as
     * "the relay lost it", or an offline device downloads its whole library one original at a time.
     */
    private suspend fun confirmedAbsentOnTheRelay(
        executionContext: ExecutionContext,
        dataId: DataId,
        contentId: String,
        thumbnailClass: String,
    ): Boolean {
        val emitter = syncBlockEmitterHolder?.fileContentEmitter ?: return false
        val resolvability = emitter.contentResolvability(
            targetEntityId = dataId,
            contentId = FileBytesPropertyWriter.derivedThumbnailContentId(contentId, thumbnailClass),
        )
        return resolvability == ContentResolvability.ABSENT
    }

    private suspend fun originalOnDisk(
        executionContext: ExecutionContext,
        dataId: DataId,
        filePath: String,
    ): Boolean {
        if (fileDatabase.fileSize(filePath) != null) return true
        val resolver = executionContext.context.onDemandResolution() ?: return false
        try {
            resolver.resolve(
                executionContext = executionContext,
                entityId = dataId,
                propertyId = File.Data.id,
                params = null,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            return false
        }
        return fileDatabase.fileSize(filePath) != null
    }

    private fun declined(contentId: String, thumbnailClass: String): Boolean {
        val sentinel = BackfillThumbnailClassesImplementation.thumbnailDeclinePath(contentId, thumbnailClass)
        return fileDatabase.fileSize(sentinel) != null &&
            fileDatabase.readBytes(sentinel)?.get()?.decodeToString() == ThumbnailClassBackfill.MINT_FORMAT
    }

    private fun recordDecline(contentId: String, thumbnailClass: String) {
        val sentinel = BackfillThumbnailClassesImplementation.thumbnailDeclinePath(contentId, thumbnailClass)
        runCatching {
            fileDatabase.writeRawByteArray(sentinel, ThumbnailClassBackfill.MINT_FORMAT.encodeToByteArray())
        }.onFailure {
            // ⚠ Named, not swallowed: an unwritten decline has no other symptom than re-rendering
            // this frame on every open for ever (the backfill's twin says the same).
            println("EnsureThumbnailClass -> could not record the decline for $contentId/$thumbnailClass: $it")
        }
    }

    private companion object {
        /**
         * ⚠ The largest original a *view* will fetch. Every photograph **§2** measured is far under
         * it — 6.7, 9.5 and 2.2 MB, and the 18 MB DSLR frame of §1 fits with room to spare — while a
         * video or a scanned volume does not, which is the point:
         * a person waiting a second for a picture has not agreed to a hundred-megabyte download, and
         * the class this mint produces is 2048 px whatever it was rendered from.
         *
         * ⚠⚠ Not a quota and not a cache bound — both of those exist elsewhere. This bounds the
         * *transfer* a single glance can start.
         */
        const val MAX_ORIGINAL_BYTES_FOR_A_VIEW_MINT: Long = 64L * 1024 * 1024
    }

    private fun hasRoomFor(executionContext: ExecutionContext, rung: ThumbnailClass.Still): Boolean = try {
        storageCapacityHolder.ensureWithinQuota(executionContext, rung.maxBytes.toLong())
        true
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        false
    }
}
