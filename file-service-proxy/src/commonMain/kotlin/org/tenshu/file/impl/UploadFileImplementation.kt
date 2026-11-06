package org.tenshu.file.impl

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.tenshu.core.common.util.bestEffort
import org.tenshu.core.Data
import org.tenshu.core.TByteArray
import org.tenshu.core.TResult
import org.tenshu.core.TString
import org.tenshu.core.TenshuException
import org.tenshu.core.number.TInteger
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.ContentChangedFileModification
import org.tenshu.core.common.util.generateId
import org.tenshu.core.common.util.wholeFileHashOf
import org.tenshu.core.file.ContentHash
import org.tenshu.core.file.ContentId
import org.tenshu.core.file.ContentModifiedAt
import org.tenshu.core.file.CreatedAt
import org.tenshu.core.file.CreatedFileModification
import org.tenshu.core.file.Data
import org.tenshu.core.file.File
import org.tenshu.core.file.CameraMake
import org.tenshu.core.file.CameraModel
import org.tenshu.core.file.DurationMs
import org.tenshu.core.file.Orientation
import org.tenshu.core.file.PixelHeight
import org.tenshu.core.file.PixelWidth
import org.tenshu.core.file.readMediaMetadata
import org.tenshu.core.file.ExifLocation
import org.tenshu.core.file.CaptureLngE7
import org.tenshu.core.file.CaptureLatE7
import org.tenshu.core.file.GetPreview
import org.tenshu.file.proxy.FileBytesPropertyWriter
import org.tenshu.file.proxy.ThumbnailClass
import org.tenshu.file.proxy.ThumbnailClasses
import org.tenshu.core.file.LastModifiedAt
import org.tenshu.core.file.Size
import org.tenshu.file.proxy.MotionThumbnailRenderer
import org.tenshu.file.proxy.ThumbnailLadderRenderer
import org.tenshu.core.file.system.AlreadyExistsError
import org.tenshu.core.file.system.UploadFile
import org.tenshu.core.schema.propertyRef
import org.tenshu.core.time.UnixInstant
import org.tenshu.core.time.clock
import org.tenshu.core.toTByteArray
import org.tenshu.storage.proxy.StorageCapacityHolder
import org.tenshu.storage.proxy.ensureWithinQuota
import org.tenshu.sync.common.SyncBlockEmitterHolder

internal class UploadFileImplementation(
    private val fileDatabase: FileDatabase,
    private val syncBlockEmitterHolder: SyncBlockEmitterHolder? = null,
    private val deviceId: String? = null,
    private val storageCapacityHolder: StorageCapacityHolder? = null,
    /**
     * Mints the `MOTION` class for a video (`photos-video-support.md` §4.4). Null — the default,
     * and every platform but Android — mints nothing and the tile keeps its static poster.
     * ⚠⚠ A silent failure mode: a production site that omits it compiles
     * and uploads happily with no motion class, which is why `MotionThumbnailRendererWiringTest`
     * audits the Android module's two factory calls.
     */
    private val motionThumbnailRenderer: MotionThumbnailRenderer? = null,
    /**
     * Renders the whole still ladder from one decode of the bytes in hand
     * (`media-processing-pipeline-sweep.md` §4.1). Null — a platform without one — renders rung by
     * rung through `GetPreview`. ⚠ Same silent shape as the two seams above: an unwired site still mints
     * every rung, only four times slower, which `ThumbnailLadderRendererWiringTest` audits on desktop.
     */
    private val thumbnailLadderRenderer: ThumbnailLadderRenderer? = null,
) : AsyncOperationImplementation {

    /**
     * The one mint (render, write, emit) — shared with the backfill so a class minted after
     * the fact is byte-for-byte and edge-for-edge the class an upload would have minted
     * (`drive-synced-thumbnails.md` §15.3: *no second implementation*).
     */
    private val minter = ThumbnailClassMinter(
        fileDatabase, syncBlockEmitterHolder, motionThumbnailRenderer, thumbnailLadderRenderer,
    )

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val parentPath: TString = params.read(UploadFile.Params.PARENT_PATH)
        val fileName: TString = params.read(UploadFile.Params.FILE_NAME)
        val data: TByteArray = params.read(UploadFile.Params.DATA)
        // Optional — present when the client could read the source file's own date
        // (e.g. desktop `File.lastModified()`); seeds File.CreatedAt and is
        // File.ContentModifiedAt, else `now`.
        val sourceModifiedAt: Long? =
            (params?.get(UploadFile.Params.SOURCE_MODIFIED_AT) as? TInteger)?.get()

        // The caller's coordinate, when it has one the bytes do not carry (a Takeout
        // photo's sidecar). Both or neither — see the client Ro.
        val captureLatE7 = (params?.get(UploadFile.Params.CAPTURE_LAT_E7) as? TInteger)?.get()
        val captureLngE7 = (params?.get(UploadFile.Params.CAPTURE_LNG_E7) as? TInteger)?.get()

        val result = uploadFile(
            executionContext,
            parentPath.get(),
            fileName.get(),
            data.get(),
            sourceModifiedAt = sourceModifiedAt,
            captureLocation = if (captureLatE7 != null && captureLngE7 != null) {
                ExifLocation(latE7 = captureLatE7, lngE7 = captureLngE7)
            } else {
                null
            },
        )
        if (result is TResult.Failure<*>) {
            throw TenshuException(result.error)
        }
        return (result as TResult.Success<*>).value as T
    }

    /**
     * @param replaceExisting when `false` (upload) a pre-existing file at the
     *   target path is rejected with [AlreadyExistsError]. When `true`
     *   (overwrite / [ReplaceFileImplementation]) the existing entity is
     *   updated **in place**: same `dataId`, a fresh data property block, and
     *   bumped metadata. This is deliberately *not* delete-then-recreate —
     *   a `DeleteFile` would emit a tombstone, and recreating the same
     *   path-derived `dataId` within the tombstone retention window races
     *   that tombstone on paired devices (the overwrite could be GC'd away).
     *   In-place replace is an ordinary last-writer-wins property merge. See
     *   `docs/proposals/drive-folder-upload.md`.
     * @param sourceModifiedAt the last-modified wall-clock time (epoch millis) of
     *   the *source* file being imported, when the caller can determine it (e.g.
     *   the Android proxy `fstat`s the picked file's descriptor). Seeds
     *   [File.CreatedAt] on a brand-new file so the creation date reflects the
     *   file's own timestamp rather than the import moment — a photo copied in
     *   today keeps its original date. `null` (desktop/generic upload, or an
     *   unstat-able content stream) falls back to `now`. It is also
     *   [File.ContentModifiedAt] — on a new file and on an overwrite alike, since
     *   an overwrite's bytes carry their own date. It never
     *   reaches `LastModifiedAt`, which stays the import `now`: that one is "last
     *   touched in the realm", the key of the recent-activity feed.
     */
    internal suspend fun uploadFile(
        executionContext: ExecutionContext,
        parentPath: String,
        fileName: String,
        data: ByteArray,
        replaceExisting: Boolean = false,
        sourceModifiedAt: Long? = null,
        /**
         * Where the photograph was taken, when the *caller* holds a coordinate the bytes do not
         * carry (a Takeout photo's JSON sidecar). `null` falls back to parsing the bytes below.
         */
        captureLocation: ExifLocation? = null,
    ): TResult<Data?> {

        val parentPathSegment = parentPath.removeSuffix("/")

        val filePath =
            if (parentPathSegment.isEmpty()) "/$fileName" else "$parentPathSegment/$fileName"

        val dataId = DataId.StringDataId.create(
            context = executionContext.context,
            type = File.ID,
            keyValue = filePath
        )
        val existing = executionContext.database.getData(dataId)
        val alreadyExisted = existing != null
        if (!replaceExisting && alreadyExisted) {
            throw TenshuException(
                AlreadyExistsError.create(
                    context = executionContext.context,
                    message = "File already exists at $filePath"
                )
            )
        }

        // The destination folder has to exist. Callers that build a tree create it top-down
        // first (`TenshuFilesRepository.ensureFolderChain`, the folder-upload loop); one that
        // does not would write a file into a folder with no row, which no listing could show.
        executionContext.requireParentFolder(filePath)

        val fileSize = data.size.toLong()

        // `contentHash` is the whole-file hash-chain computed ONCE here over the in-memory upload bytes
        // (file-content-addressing.md §8.4 one-hash-pass). The emitter reads it back through the
        // ExternalPropertyWriter port instead of re-hashing the file it is already streaming to chunk.
        val contentHash = wholeFileHashOf(data)

        // ⚠⚠ An overwrite with the bytes the file already holds is a no-op. Every content write below
        // mints a fresh random contentId and emits every chunk under a fresh CEK and nonce, so "save
        // with no changes" would otherwise re-upload the whole file and orphan the old copy.
        // ⚠ "Holds" means a ContentHash, a ContentId AND these exact bytes on this device, compared, not
        // sized: a row synced from a peer has both ids while the local file is absent or still the
        // previous version, and an overwrite from there is how the new bytes land (or content the
        // relay lost gets re-seeded).
        // ⚠⚠ And no unsent-content marker for that ContentId: a write interrupted between saving the
        // row and journalling its bytes leaves row and bytes matching with nothing uploaded, and
        // retrying the same overwrite is what finishes it.
        // ⚠⚠ Keeping the contentId also means trusting that the relay still has it, so the no-op asks
        // (`ensureContentOnRelay`) and re-emits under the same id when it cannot confirm. An unchanged
        // save is one way a device holding the bytes puts back content the relay lost.
        val keptContentId = if (replaceExisting && existing != null) {
            heldContentId(executionContext, existing, filePath, contentHash, data)
        } else {
            null
        }
        if (keptContentId != null) {
            syncBlockEmitterHolder?.fileContentEmitter
                ?.ensureContentOnRelay(dataId, File.Data.id, filePath, keptContentId)
            return TResult.Success.create<Data?>(context = executionContext.context, value = null)
        }

        // Tier-2 quota gate (ADR 0019 local-first reframe): upload is a *local*
        // operation, so a missing subscription does NOT block it — the bytes are
        // written locally and the personal-realm sync gate holds the block until a
        // subscription binds. Only a subscribed user over their paid quota is
        // blocked (InsufficientStorageError). No-op when storage isn't wired/known
        // (test harnesses, pre-derivation).
        // ⚠⚠ An overwrite is gated exactly like an upload, on its full size. It does NOT reuse the old
        // bytes' room: its new chunks are emitted before the superseded content is reclaimed
        // (best-effort, and never on iOS, whose sweep runs dry), so ungated it queues writes the relay
        // can only refuse.
        // ⚠ Read before the new bytes are written: the journal measures an entry by the file at its path.
        val supersededQueuedBytes = if (replaceExisting && existing != null) {
            syncBlockEmitterHolder?.fileContentEmitter?.queuedContentBytesFor(dataId, File.Data.id) ?: 0L
        } else {
            0L
        }
        storageCapacityHolder.ensureWithinQuota(executionContext, fileSize, releasedQueuedBytes = supersededQueuedBytes)

        val lastModified = executionContext.context.clock().nowEpochMillis()

        // createdAt is immutable: on a brand-new file seed it from the source
        // file's own last-modified time when known ([sourceModifiedAt] — e.g. an
        // imported photo keeps its capture-era date), else `now`; on an in-place
        // overwrite preserve the original. Value properties aren't inline on the
        // stored entity, so read it back through the property resolver (the same
        // path Select uses) rather than `entity.get(...)`, which returns null here.
        val createdAt: Long =
            if (existing != null) {
                (executionContext.resolveProperty(existing, propertyRef(File.CreatedAt.id)) as? UnixInstant)
                    ?.get() ?: lastModified
            } else {
                sourceModifiedAt ?: lastModified
            }

        // The content's own date. Unlike `createdAt` this follows the bytes, so an overwrite
        // takes the new source's — or `now` where the writer could not say, which is then true:
        // the content did change just now.
        val contentModifiedAt: Long = sourceModifiedAt ?: lastModified

        // An in-place overwrite is the one path that orphans content without a delete —
        // `File.ContentId` is reassigned below and the previous id loses its last reference with
        // nothing recording it. ⚠ Read before the new entity is saved; afterwards the old id is
        // unrecoverable, which is the same constraint that forces `DeleteFileImplementation` to
        // collect `orphanedContent` up front.
        val supersededContentId: String? = if (replaceExisting && existing != null) {
            (executionContext.resolveProperty(existing, propertyRef(File.ContentId.id)) as? TString)
                ?.get()
                ?.takeIf { it.isNotBlank() }
        } else {
            null
        }

        val bytes = data.toTByteArray(executionContext.context)
        // ⚠⚠ Ownership of this content's emit starts HERE — before the bytes and the row are
        // written, not at the emit inside. `emitFileContentNow` re-resolves the current path and
        // row, so a deferred-content drain landing between those writes and the emit publishes
        // THIS version under an older journal entry and clears it; the emit below then publishes
        // the same version again under a fresh CEK and nonce. Two chunk sets, one manifest address
        // (a manifest's ref is derived from the contentId, so both emits write the same one), and
        // whichever set the surviving manifest does not name can never be freed — reclamation
        // resolves a content's chunks through its manifest.
        //
        // ⚠ Re-entrant: the `emitFileContent` inside is this owner's own emit and does not
        // re-acquire. A null emitter (no sync wired) runs it unowned, as does the interface
        // default — an emitter with no journal has no drain to race.
        val contentEmitter = syncBlockEmitterHolder?.fileContentEmitter
        val ownedUpload: suspend () -> Pair<String, List<Pair<ThumbnailClass.Still, ByteArray?>>> = {
            fileDatabase.writeByteArray(filePath = filePath, bytes = bytes)

            // Content addressing (file-content-addressing.md §3.1): mint a fresh
            // random contentId for THIS content write — upload and overwrite both mint a new
            // one (new bytes ⇒ new content identity; a stale contentId would re-PUT its
            // immutable manifest as a no-op and strand the new bytes). Move/rename/copy
            // instead *preserve* the contentId (MoveFileImplementation), which is what makes
            // relocation pure metadata. Unchanged bytes never reach this line on an overwrite
            // (the early return above).
            val contentId = generateId()

            // ⚠⚠ Set BEFORE `saveData(fileEntity)`, cleared only once the `File.Data` hand-off below has
            // returned, which is when the emitter's durable journal owns the retry. Until then the row can
            // name content nothing will upload, and [heldContentId] must not call it unchanged.
            val unsentMarker = unsentContentMarkerPath(contentId)
            fileDatabase.writeRawByteArray(unsentMarker, UNSENT_MARKER_BYTES)

            // Synced thumbnails (`drive-synced-thumbnails.md` §10.2, §11, §12): every still rung of the
            // ladder, rendered from the just-written local bytes, in table order (`ThumbnailClasses`). Each
            // is its own OnDemand content-plane blob, emitted below after the entity. Best-effort: a
            // failure (no `GetPreview` wired, a non-image, a decode error, over the cap after the quality
            // step-down) leaves that class unset, and the resolver falls back to another class or the
            // category icon. Thumbnailing never fails the upload.
            //
            // ⚠⚠ JPEG at every rung, never the absent-format default (PNG at these sizes), which is
            // lossless and measured ~9× larger on photographic content (median 20.0 KB vs 2.3 KB);
            // these bytes count against quota. Lossless WebP is only ~26 % below PNG and the JVM
            // has no WebP writer, so JPEG takes the whole win on every platform. Cost: no alpha.
            //
            // ⚠ Rendered HERE, at upload, on the one device holding the bytes — never on demand (§12.5
            // D-1): the device that wants a large class is usually not the one that holds the file, and
            // routing that demand between them would sync an access-pattern signal §10.3 forbids.
            //
            // ⚠ One decode for the whole ladder where the platform wires a ladder renderer
            // ([ThumbnailClassMinter.renderStills]); rung by rung through `GetPreview` where it does not.
            val stills: List<Pair<ThumbnailClass.Still, ByteArray?>> = renderThumbnails(
                executionContext, filePath = filePath, data = data,
                rungs = ThumbnailClasses.STILLS, format = GetPreview.Params.FORMAT_JPEG,
            )
            // ⚠ The motion class is minted further down, after the row and the file's own bytes have
            // landed — see the comment at its `emitThumbnailClass`.

            // One parse of the uploaded bytes for everything the file says about itself —
            // dimensions, orientation, camera, video length, and the coordinate.
            // ⚠ Once, not once per field: the walk is the cost, and asking it five times would parse
            // the file five times for the same answer.
            val metadata = readMediaMetadata(data)

            // The caller's coordinate wins: it comes from a source the bytes do not carry.
            val location = captureLocation ?: metadata.location

            val fileEntity = Entity.create<File>(
                context = executionContext.context,
                composite = File.create(executionContext.context, filePath),
            ) {
                set(File.Size, fileSize)
                set(File.LastModifiedAt, lastModified)
                set(File.CreatedAt, createdAt)
                set(File.ContentModifiedAt, contentModifiedAt)
                set(File.ContentId, contentId)
                set(File.ContentHash, contentHash)
                // Two scalars, together or not at all. Most files have neither — a document has
                // no coordinate, and neither has a photo whose GPS the platform stripped on the way in.
                location?.let {
                    set(File.CaptureLatE7, it.latE7)
                    set(File.CaptureLngE7, it.lngE7)
                }
                // Each set only where the file actually said something. Absent means "did not
                // say" everywhere downstream — writing a default here would turn every unreadable
                // container into a confident, wrong claim about the photo.
                metadata.pixelWidth?.let { set(File.PixelWidth, it.toLong()) }
                metadata.pixelHeight?.let { set(File.PixelHeight, it.toLong()) }
                metadata.orientation?.let { set(File.Orientation, it.toLong()) }
                metadata.cameraMake?.let { set(File.CameraMake, it) }
                metadata.cameraModel?.let { set(File.CameraModel, it) }
                metadata.durationMs?.let { set(File.DurationMs, it) }
                // ⚠ The thumbnails are NOT `set` on the entity — they are OnDemand
                // content-plane blobs, written + emitted below. Set here, they would be
                // ~97 % of every File row's bytes.
            }

            // Single `executionContext.saveData` covers local relation membership
            // (`FileRelationMembership.kt`) + outbound sync (`SyncObserver`). The
            // `ExecutionContext` shares the observer list across `withEvaluationScope`,
            // so an explicit `addDataToArray` as well would produce duplicate entries.
            // ⚠⚠ NonCancellable from the row to the journal entry (`drive-synced-folders-phase2.md`
            // §4.1b). Once the row is saved it names content that only the journal will ever upload,
            // so a cancellation landing in between — a user's Stop, an OS stop of a backup job, any
            // wire cancel of the op — would leave a row with no bytes behind it, which every later
            // upload of the same file answers with AlreadyExists: the photo is skipped for ever. The
            // inline chunk upload after this block stays cancellable; the journal owns its retry.
            withContext(NonCancellable) {
                executionContext.saveData(fileEntity)

                // `Notification` is `Syncable`; saving via `ExecutionContext.saveData`
                // fires `SyncObserver` (outbound block) and the notification relation
                // membership — the same path `CreateFolder`/`DeleteFile` use. Only a *new*
                // file is an "Added" event; an in-place overwrite (`replaceExisting`) is a
                // content change and records a modification below instead. This is what
                // surfaces an upload in Control Home's recent-events feed. A folder/batch upload fans out one
                // `UploadFile` op per file; the coalescer folds uploads within the burst
                // window into ONE notification row with a bumped `count` (instead of one
                // synced row per file — see `FileAddedNotificationCoalescer`).
                if (!alreadyExisted) {
                    FileAddedNotificationCoalescer.recordFileAdded(
                        executionContext = executionContext,
                        path = filePath,
                        nowMillis = lastModified,
                    )
                }

                // Emit-side history hook (docs/proposals/file-service-history.md §4.1):
                // a brand-new file records Created; an in-place overwrite of an existing
                // file records ContentChanged. Co-emitted in the same drain as the
                // file's own block. No-op without a deviceId (test/pre-sync wirings).
                if (deviceId != null) {
                    val modification = if (alreadyExisted) {
                        ContentChangedFileModification.create(
                            context = executionContext.context,
                            fileId = filePath,
                            timestamp = lastModified,
                            deviceId = deviceId,
                            // Content locator: the file path. The real manifest ref
                            // (hash-of-ciphertext from the two-plane emitter) is not
                            // surfaced by FileContentSyncEmitter.emitFileContent; it is
                            // owed to "restore to version" (§2.2), its first consumer.
                            manifestId = filePath,
                        )
                    } else {
                        CreatedFileModification.create(
                            context = executionContext.context,
                            fileId = filePath,
                            timestamp = lastModified,
                            deviceId = deviceId,
                        )
                    }
                    executionContext.saveData(modification)
                }

                // Push the bytes through sync. The file content takes the
                // **two-plane bounded-memory path** (docs/adr/0017-file-content-two-plane-channel.md):
                // chunk → per-chunk AEAD → content-plane PUT → ONE small manifest block on
                // the ordered drain. ⚠ Never the whole file in one `SyncBlock`: a backlog of
                // full-file blocks is materialised every drain and OOM-kills Control on a
                // large-photo folder upload.
                //
                // The bytes are already persisted in `FileDatabase` above, so
                // the emitter reads them back one CHUNK at a time — no second
                // whole-file copy is kept. Peak heap is O(CHUNK), independent of
                // file size and backlog depth.
                //
                // Fallback: if no content emitter is wired (test harnesses
                // without a relay content store), fall back to the single-block
                // emit so non-content paths and the receiver-side
                // `FileBytesPropertyWriter` keep working. Null holder = no sync
                // at all → no-op.
                //
                // Ordering (ADR 0015 / Facet A): `saveData(fileEntity)` above only
                // *enqueues* the entity block onto the async emit consumer, whereas
                // the byte/manifest emit below runs inline. Without a barrier the
                // byte block would reach `pendingBlocks` — and tick the HLC — before
                // the entity block, so the joiner receives bytes before the parent
                // `File` and `FileBytesPropertyWriter` drops them (entity-before-bytes
                // invariant; the proactive push never retries). Drain the entity emit
                // first so the entity block is HLC-ordered ahead of the bytes.
                // ⚠ Bounded: under NonCancellable a wedged emit consumer would otherwise make this
                // upload — and the user's Stop with it — unstoppable. On a timeout the journal below
                // still records the intent; the ordering it protects is a delivery nicety, the
                // intent is the bytes.
                withTimeoutOrNull(EMIT_IDLE_BOUND_MS) { syncBlockEmitterHolder?.emitter?.awaitEmitIdle() }
                // The intent, durable before the span ends — see the comment above this block.
                syncBlockEmitterHolder?.fileContentEmitter?.journalFileContent(
                    targetEntityId = dataId,
                    propertyId = File.Data.id,
                    filePath = filePath,
                )
            }

            val fileContentEmitter = syncBlockEmitterHolder?.fileContentEmitter
            if (fileContentEmitter != null) {
                fileContentEmitter.emitFileContent(
                    targetEntityId = dataId,
                    propertyId = File.Data.id,
                    filePath = filePath
                )
            } else {
                syncBlockEmitterHolder?.emitter?.emitPropertyBlock(
                    targetEntityId = dataId,
                    propertyId = File.Data.id,
                    value = bytes
                )
            }
            fileDatabase.deleteFile(unsentMarker)
            contentId to stills
        }
        val (contentId, stills) =
            if (contentEmitter != null) {
                contentEmitter.withContentEmitOwnership(dataId, File.Data.id) { ownedUpload() }
            } else {
                ownedUpload()
            }

        // ⚠⚠ The parked intents this overwrite superseded — retired HERE, as soon as the new
        // `File.Data` intent is durable, and not after the thumbnail work below: the motion mint
        // rethrows cancellation, and a background overwrite cancelled there, or a process exit in
        // that interval, would skip the cleanup — behind a closed gate nothing else retires them.
        // The journal is keyed on content, so the new intents do not replace the old ones. Every
        // rung too: their ids derive from the file's, so a new file content
        // is a new id for each, whether or not new rungs are ever minted. Best-effort, because the
        // drain retires any left behind the next time it runs.
        if (supersededContentId != null && supersededContentId != contentId) {
            bestEffort("UploadFile -> retiring the intents it superseded") {
                contentEmitter?.discardSupersededContent(
                    targetEntityId = dataId,
                    superseded = listOf(supersededContentId to File.Data.id) +
                        ThumbnailClasses.ALL.map { rung ->
                            FileBytesPropertyWriter.derivedThumbnailContentId(supersededContentId, rung.id) to
                                rung.property.id
                        },
                )
            }
        }

        // Each thumbnail class is its own content-plane blob under its own derived
        // content id, so the two never collide with each other or with `File.Data` at
        // `manifestContentRef` (FileBytesPropertyWriter.resolveContentId). Emitted after
        // the entity for the same entity-before-bytes reason as the file's own bytes.
        for ((rung, thumbnail) in stills) {
            emitThumbnailClass(
                executionContext, dataId, contentId,
                propertyId = rung.property.id,
                thumbnailClass = rung.id,
                thumbnail = thumbnail,
            )
        }
        // The motion class (`photos-video-support.md` §4): a ~3 s silent 256 px H.264 loop of a
        // *video*, so a grid on a byte-less device can move without fetching the original (§3 —
        // House Rule #1 leaves no server-side rendition to stream, so the device holding the bytes
        // mints the derivative, exactly as it does the stills). ⚠⚠ Not through `GetPreview`:
        // a clip is not a bitmap and must never join that ladder (§4.1) — it has its own platform
        // seam, wired only where a platform can encode (Android; §4.4). Same best-effort
        // contract as the stills: anything that is not a stored blob is `null`, and the tile stays
        // a static poster.
        //
        // ⚠⚠ Minted HERE, after `saveData` and the `File.Data` emit, unlike the stills above —
        // §14.10. A clip costs 0.55–1.3 s at 1080p and is unmeasured at 4K, and nothing about the
        // row or the bytes depends on it; minting it first would delay the entity every peer is
        // waiting on by exactly that much, for a derivative only a grid ever reads. The mint checks
        // for cancellation before it starts, so a cancelled `UploadFileBg` spends no second on a
        // clip nobody will store — it cannot interrupt one in progress, which is what the budget
        // in the renderer is for.
        val thumbnailMotion: ByteArray? = renderMotionThumbnail(fileName = fileName, data = data)
        emitThumbnailClass(
            executionContext, dataId, contentId,
            propertyId = ThumbnailClasses.MOTION.property.id,
            thumbnailClass = ThumbnailClasses.MOTION.id,
            thumbnail = thumbnailMotion,
        )

        return TResult.Success.create<Data?>(context = executionContext.context, value = null)
    }

    /**
     * The `File.ContentId` to keep when [existing] already holds exactly [data], else null: both
     * `File.ContentHash` and `File.ContentId` must be present, the hash must equal [contentHash], no
     * write of that content may still be unsent from this device, and this device's store must hold
     * the same bytes at [filePath].
     */
    private fun heldContentId(
        executionContext: ExecutionContext,
        existing: Data,
        filePath: String,
        contentHash: String,
        data: ByteArray,
    ): String? {
        val storedHash = (executionContext.resolveProperty(existing, propertyRef(File.ContentHash.id)) as? TString)
            ?.get()
        val storedContentId = (executionContext.resolveProperty(existing, propertyRef(File.ContentId.id)) as? TString)
            ?.get()
        val held = !storedHash.isNullOrBlank() &&
            !storedContentId.isNullOrBlank() &&
            storedHash == contentHash &&
            fileDatabase.fileSize(unsentContentMarkerPath(storedContentId)) == null &&
            localBytesEqual(filePath, data)
        return if (held) storedContentId else null
    }

    /**
     * Streams the local file at [filePath] against [data], [COMPARE_CHUNK_BYTES] at a time, stopping at
     * the first difference. `fileSize` and `readBytesRange` are O(1) and positional on every platform
     * store, so this costs one read of a file the caller already holds in memory, and only runs once the
     * hash has matched.
     */
    private fun localBytesEqual(filePath: String, data: ByteArray): Boolean {
        if (fileDatabase.fileSize(filePath) != data.size.toLong()) return false
        var offset = 0
        while (offset < data.size) {
            val length = minOf(COMPARE_CHUNK_BYTES, data.size - offset)
            val chunk = fileDatabase.readBytesRange(filePath, offset.toLong(), length) ?: return false
            if (chunk.size != length) return false
            for (i in 0 until length) {
                if (chunk[i] != data[offset + i]) return false
            }
            offset += length
        }
        return true
    }

    /**
     * Write one rendered thumbnail class to the local [FileDatabase] and emit it onto the
     * content plane — [ThumbnailClassMinter.store]. A `null` [thumbnail] (non-image, over-cap,
     * no `GetPreview` wired) or a missing content emitter simply means this class has no blob,
     * and the `GetPreview` resolver falls back to the other class or the category icon.
     */
    private suspend fun emitThumbnailClass(
        executionContext: ExecutionContext,
        dataId: DataId,
        contentId: String,
        propertyId: String,
        thumbnailClass: String,
        thumbnail: ByteArray?,
    ) {
        // This content id was minted by this upload (an unchanged overwrite returned before
        // reaching here), so its rungs cannot be on the relay yet — no need to ask first.
        minter.store(executionContext, dataId, contentId, propertyId, thumbnailClass, thumbnail, freshContent = true)
    }

    /**
     * Renders every synced thumbnail *class* in [rungs] for the just-uploaded file —
     * [ThumbnailClassMinter.renderStills] in [format], collapsed to the upload's contract: per rung, the
     * encoded bytes, or `null` for anything that shouldn't carry a thumbnail (non-image /
     * un-renderable, over the rung's cap, no `GetPreview` implementation wired). Thumbnailing is
     * strictly best-effort: it must never fail the upload, so every non-bytes outcome is `null` here.
     */
    private suspend fun renderThumbnails(
        executionContext: ExecutionContext,
        filePath: String,
        data: ByteArray,
        rungs: List<ThumbnailClass.Still>,
        format: String,
    ): List<Pair<ThumbnailClass.Still, ByteArray?>> =
        rungs.zip(minter.renderStills(executionContext, filePath, data, rungs, format)) { rung, render ->
            rung to (render as? ThumbnailClassMinter.Render.Bytes)?.bytes
        }

    /**
     * Mints the `MOTION` class for the just-uploaded bytes — [ThumbnailClassMinter.renderMotion]
     * under [MAX_MOTION_THUMBNAIL_BYTES] — or `null` for everything that should not carry one (a
     * non-video, no renderer on this platform, a declined or over-cap clip, a renderer that threw).
     * Best-effort like [renderThumbnails]: a thumbnail never fails an upload.
     */
    private suspend fun renderMotionThumbnail(fileName: String, data: ByteArray): ByteArray? =
        (minter.renderMotion(fileName, data, ThumbnailClasses.MOTION.maxBytes) as? ThumbnailClassMinter.Render.Bytes)?.bytes

    internal companion object {
        /**
         * Upper bound on the stored `LIST` thumbnail, which must stay small; an over-cap
         * render is dropped (the file shows its category icon on byte-less devices).
         * A 100 px render comfortably fits; a pathological image is excluded.
         */
        const val MAX_THUMBNAIL_BYTES = 64 * 1024

        /**
         * Upper bound on the stored `LARGE` (JPEG) thumbnail (~256 px JPEG comfortably
         * fits 48 KB). An over-cap render is dropped — the resolver falls back to `LIST`.
         */
        const val MAX_LARGE_THUMBNAIL_BYTES = 48 * 1024

        /**
         * Upper bound on the stored `XLARGE` blob. ⚠ Larger than the other two caps *on purpose* —
         * this class exists to be the alternative to shipping a multi-megabyte original, so a cap
         * tight enough to reject ordinary photos would defeat it. 1024 px q80 measured ~139 KB for
         * an ordinary photograph; the cap is there to refuse a noise-like frame (~1.5 MB+).
         *
         * ⚠⚠ **256 KB refuses real pictures** (`drive-synced-thumbnails.md` §17): a detailed frame —
         * a screenshot, a page of text — is ~500 KB at q80 and still ~310 KB at q50, so it would be
         * declined, the resolver would fall back to `LARGE`, and every byte-less device would draw it
         * 256 px wide for good. 512 KB admits that frame at the minter's first or second quality
         * step ([ThumbnailClassMinter.STEP_DOWN_QUALITIES]) and costs an ordinary photograph nothing.
         */
        const val MAX_XLARGE_THUMBNAIL_BYTES = 512 * 1024

        /**
         * Upper bound on the stored `XXLARGE` blob — ⭐ **locked** against all four
         * encoders (`drive-xxlarge-thumbnail-rung.md` §2.1, D-13).
         *
         * ⚠⚠ **It holds because of the step-down, not on its own.** iOS encodes ~1.65x heavier at
         * the same nominal quality, so a detailed frame that fits at q80 on Android, the browser and
         * the JVM fits at q65 there. Every real photograph measured is admitted on every platform;
         * what 1 MB refuses is a noise-like frame, which is what a cap is for.
         *
         * ⚠ 2x `MAX_XLARGE_THUMBNAIL_BYTES` for 4x the pixels: the rung's bytes are set by its own
         * edge, not the source's, so a DSLR frame and a phone snap land in the same band.
         */
        const val MAX_XXLARGE_THUMBNAIL_BYTES = 1024 * 1024

        /**
         * Upper bound on the stored `MOTION` clip — ⭐ **locked** at `photos-video-support.md` §12.5
         * from seven real sources spanning a 67x range of source bitrate: 36.0 dB mean / 32.8 dB
         * worst-frame PSNR against a near-lossless reference on the two hardest of them, at 256 px /
         * 3 s / 12 fps. ⚠ The bitrate is *derived* from this (`MotionClipPlan.targetBitrateBps`),
         * not chosen, because a bitrate-targeted encoder makes bytes a property of the settings
         * alone (§12.1) — so unlike the still caps this one is met by construction, and the
         * drop below is a backstop for a failed encode rather than the expected path (§12.2).
         *
         * Sits sensibly in the ladder: `LIST` 64 KB · `LARGE` 48 KB · **`MOTION` 96 KB** · `XLARGE`
         * 512 KB — a video's motion preview costs well under a photo's hero still.
         *
         * ⚠ AVFoundation numbers; the Android `MediaCodec` re-run is owed (§13.4) — the constant is
         * not yet confirmed by a device pass.
         */
        const val MAX_MOTION_THUMBNAIL_BYTES = 96 * 1024

        /** Window for [localBytesEqual]: bounds the extra heap of an unchanged-overwrite check. */
        const val COMPARE_CHUNK_BYTES = 1024 * 1024

        private val UNSENT_MARKER_BYTES = byteArrayOf(1)

        /** The most the non-cancellable save → journal span waits for the emit consumer to drain. */
        private const val EMIT_IDLE_BOUND_MS = 30_000L

        /**
         * A local sidecar present while a write of [contentId] has saved its row but not yet handed
         * `File.Data` to the emitter. Keyed by content, so a move or rename carries it along.
         */
        fun unsentContentMarkerPath(contentId: String): String = "/.unsent-content/$contentId"
    }
}
