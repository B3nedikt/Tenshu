package org.tenshu.file.impl

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.file.ContentId
import org.tenshu.core.file.File
import org.tenshu.core.file.GetStoredThumbnail
import org.tenshu.core.schema.propertyRef
import org.tenshu.core.time.clock
import org.tenshu.file.proxy.FileBytesPropertyWriter
import org.tenshu.file.proxy.ThumbnailCacheSidecars

/**
 * Resolves [File.Thumbnail] / [File.ThumbnailLarge] from the local [FileDatabase]
 * (C7, `sync-device-catch-up.md` §11.3) — the file-service twin of
 * `GetWorkoutStreamsImplementation`, which does the same for `Workout.Streams`.
 *
 * ⚠ **Local-only, and that is the contract.** `null` means "no thumbnail blob on this
 * device right now": a non-image file, a file uploaded before thumbnails existed, or —
 * the new case since C7 — a synced file whose thumbnail has not been fetched from the
 * content plane yet. Every caller already treats `null` as "no stored thumbnail", and
 * `GetPreview` falls back to the other class or to nothing. Fetching is the OnDemand
 * cycle's job, driven from a client terminal; blocking a preview render on the network
 * here would trade the join cost C7 removed for a stall on every row.
 *
 * The blob is keyed by the file's `contentId`, so it survives a move for the same
 * reason the file's own bytes do — see [FileBytesPropertyWriter.thumbnailPath].
 */
internal class GetStoredThumbnailImplementation(
    private val fileDatabase: FileDatabase,
) : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        val file = caller as? Composite
            ?: (params?.get(GetStoredThumbnail.Params.FILE) as? Composite)
            ?: error("Parameter ${GetStoredThumbnail.Params.FILE} is required")

        val thumbnailClass = (params?.get(GetStoredThumbnail.Params.THUMBNAIL_CLASS) as? TString)?.get()
            ?: return null as T

        // `contentId` is a value property, so it is read through the property resolver
        // rather than off the composite — the same path `FileBytesPropertyWriter` uses,
        // which is what keeps the read and the write addressing the same blob.
        val contentId = (executionContext.resolveProperty(file, propertyRef(File.ContentId.id)) as? TString)
            ?.get()?.takeIf { it.isNotEmpty() } ?: return null as T

        val path = FileBytesPropertyWriter.thumbnailPath(contentId, thumbnailClass)
        val bytes = fileDatabase.readBytes(path) ?: return null as T
        // ⚠⚠ The keeper's ceiling evicts coldest-first, and without this the order would be
        // least-recently-*fetched*: a rung opened every day would stay the coldest thing on the
        // device and be evicted and pulled again forever (`drive-xxlarge-thumbnail-rung.md` §4.7).
        // Only the evictable classes are stamped, and at most once per interval, so a list row's
        // `LIST` read still costs exactly one read.
        ThumbnailCacheSidecars.stampRead(
            fileDatabase = fileDatabase,
            fileContentId = contentId,
            thumbnailClass = thumbnailClass,
            nowMs = executionContext.context.clock().nowEpochMillis(),
        )
        return bytes as T
    }
}
