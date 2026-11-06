package org.tenshu.file.proxy

import org.tenshu.core.common.proxy.FileDatabase
import org.tenshu.core.common.proxy.db.ObjectStore
import org.tenshu.core.common.proxy.executor.OperationImplementation
import org.tenshu.core.file.GetData
import org.tenshu.core.file.GetStoredThumbnail
import org.tenshu.core.file.share.RevokeShareLink
import org.tenshu.core.file.share.ShareLink
import org.tenshu.core.file.system.CreateFolder
import org.tenshu.core.file.system.DeleteFile
import org.tenshu.core.TString
import org.tenshu.core.entity.DataId
import org.tenshu.core.file.ContentId as FileContentIdProperty
import org.tenshu.core.file.File
import org.tenshu.core.schema.propertyRef
import org.tenshu.core.file.system.EvictFileBytes
import org.tenshu.core.file.system.OfflineKeeper
import org.tenshu.core.file.system.BackfillThumbnailClasses
import org.tenshu.core.file.FileCategoryOf
import org.tenshu.core.file.system.HasLocalData
import org.tenshu.core.file.system.ListPlacements
import org.tenshu.core.file.system.PhotosCapturedBetween
import org.tenshu.core.file.system.PhotosNear
import org.tenshu.core.file.system.ListSharedFolder
import org.tenshu.core.file.system.MountSharedRealm
import org.tenshu.core.file.system.MoveFile
import org.tenshu.core.file.system.EnsureThumbnailClass
import org.tenshu.core.file.system.ReadSharedFile
import org.tenshu.core.file.system.ReplaceFile
import org.tenshu.core.file.system.ReplaceFileBg
import org.tenshu.core.file.system.ShareFolderIntoRealm
import org.tenshu.core.file.system.UploadFile
import org.tenshu.core.file.system.UploadFileBg
import org.tenshu.file.impl.CreateFolderImplementation
import org.tenshu.file.impl.ListPlacementsImplementation
import org.tenshu.file.impl.PhotosCapturedBetweenImplementation
import org.tenshu.file.impl.PhotosNearImplementation
import org.tenshu.file.impl.ListSharedFolderImplementation
import org.tenshu.file.impl.MountSharedRealmImplementation
import org.tenshu.file.impl.EnsureThumbnailClassImplementation
import org.tenshu.file.impl.ReadSharedFileImplementation
import org.tenshu.file.impl.ShareFolderIntoRealmImplementation
import org.tenshu.file.impl.DeleteFileImplementation
import org.tenshu.file.impl.EvictFileBytesImplementation
import org.tenshu.file.impl.OfflineKeeperImplementation
import org.tenshu.file.impl.BackfillThumbnailClassesImplementation
import org.tenshu.file.impl.FileCategoryOfImplementation
import org.tenshu.file.impl.HasLocalDataImplementation
import org.tenshu.file.impl.GetDataImplementation
import org.tenshu.file.impl.GetStoredThumbnailImplementation
import org.tenshu.file.impl.MoveFileImplementation
import org.tenshu.file.impl.MoveNeedsLocalBytes
import org.tenshu.file.impl.ReplaceFileImplementation
import org.tenshu.file.impl.UploadFileImplementation
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.file.impl.share.RevokeShareLinkImplementation
import org.tenshu.file.impl.share.ShareLinkImplementation
import org.tenshu.storage.proxy.StorageCapacityHolder
import org.tenshu.sync.common.ShareRelayClient
import org.tenshu.sync.common.SyncBlockEmitterHolder
import org.tenshu.sync.common.TombstoneEmitter

/**
 * @param deviceId the proxy's own stable device id (`SyncManager.currentDeviceId`),
 *   stamped onto the [org.tenshu.core.file.FileModification] emitted by every
 *   write op (docs/proposals/file-service-history.md §2.4). `null`
 *   (test harnesses / pre-sync wirings) makes the modification emit a silent
 *   no-op, mirroring the nullable [syncBlockEmitterHolder] / [tombstoneEmitter].
 * @param shareRelayClient relay HTTP client for Mode-A link sharing
 *   (`docs/file-sharing.md`). `null` (test harnesses /
 *   pre-sync wirings) makes `ShareLink` save only the local `Share` entity and
 *   `RevokeShareLink` skip the relay `DELETE` — the *My shares* projection +
 *   Eager multi-device convergence still work; the minted URL resolves to a
 *   relay 410 until a real client is wired. Mirrors the nullable
 *   [syncBlockEmitterHolder] content-plane plumbing.
 * @param cryptoManager AEAD backend used to re-encrypt share chunks under the
 *   per-share `K_share`. `null` disables relay upload alongside
 *   [shareRelayClient] (both are needed to encrypt + push).
 */
fun fileServiceProxyImplementationFactory(
    id: String,
    fileDatabase: FileDatabase,
    tombstoneEmitter: TombstoneEmitter? = null,
    syncBlockEmitterHolder: SyncBlockEmitterHolder? = null,
    deviceId: String? = null,
    shareRelayClient: ShareRelayClient? = null,
    cryptoManager: SyncCryptoManager? = null,
    storageCapacityHolder: StorageCapacityHolder? = null,
    /**
     * The platform's `MOTION`-class encoder (`photos-video-support.md` §4.4), handed to the
     * upload/replace ops. Null (the default; every platform but Android) mints no motion class and
     * video tiles keep their static poster. ⚠ On Android this factory is reached for the in-memory
     * `UploadFile`/`ReplaceFile` ops *as well as* the descriptor-backed ones in the Android factory,
     * so both calls must pass it — `MotionThumbnailRendererWiringTest`.
     */
    motionThumbnailRenderer: MotionThumbnailRenderer? = null,
    /**
     * The platform's one-decode still-ladder renderer (`media-processing-pipeline-sweep.md` §4.1),
     * handed to the upload/replace ops. Null (the default; Android and iOS) renders each
     * rung through `GetPreview` — every rung still minted, each from its own full decode. ⚠ Silent when
     * omitted, like [motionThumbnailRenderer]: `ThumbnailLadderRendererWiringTest` audits desktop.
     */
    thumbnailLadderRenderer: ThumbnailLadderRenderer? = null,
    /**
     * The store the thumbnail backfill (`drive-synced-thumbnails.md` §15.3) enumerates `File`
     * rows and every device's wanted-classes row from. Null (the default; a narrowed harness)
     * makes the backfill a no-op — ⚠ silently, like the two seams above it, so
     * `ThumbnailBackfillWiringTest` audits that the production modules pass it.
     */
    objectStore: ObjectStore? = null,
    /**
     * Whether this device is on a metered connection, for the view-mint gate
     * (`drive-xxlarge-thumbnail-rung.md` §4.6). ⚠ The default never refuses, so an unwired proxy
     * mints on view on any network — see [MeteredNetworkSignal], which explains why `canPublishContentNow()` cannot answer
     * this and why the default fails open. ⚠⚠ Audited by `MintOnViewWiringTest`, because a seam that
     * silently defaults is how a 6–18 MB download over cellular ships unnoticed.
     */
    meteredNetwork: MeteredNetworkSignal = MeteredNetworkSignal.Unknown,
    /**
     * Whether this device's writes currently reach the relay — the connectivity half of
     * `content-plane-cr2-b6-spec.md` D3 (§3). `MoveFile` refuses an offline move of content this device
     * does not hold ([org.tenshu.file.impl.MoveNeedsLocalBytes]).
     *
     * ⚠⚠ Null reads as **online**, so an unwired proxy never refuses a move. The opposite default
     * would refuse every move of an undownloaded file on any runtime that forgot to pass it.
     *
     * ⚠ Supplied from `SyncHealthHolder.current().online`, which the scheduler writes from
     * `RelayReachability.isOnline()`. It is deliberately not `caughtUp` — see the call site.
     */
    isOffline: (() -> Boolean)? = null,
    /**
     * The acknowledged relay round-trip a move that looks online asks before moving bytes it does
     * not hold — see [org.tenshu.file.impl.MoveNeedsLocalBytes]. ⚠ Null
     * keeps the cached verdict alone; audited by `MoveFenceWiringTest` beside [isOffline].
     */
    confirmWritable: (suspend () -> Boolean?)? = null,
): OperationImplementation? {
    return when (id) {
        CreateFolder.ID -> CreateFolderImplementation(deviceId)
        DeleteFile.ID -> DeleteFileImplementation(fileDatabase, tombstoneEmitter)
        // Make-online-only (drive-offline-online.md §2): drop local bytes,
        // keep the synced entity + metadata. No tombstone — must not converge
        // into a delete on other realm devices.
        // ⚠ The upload journal: an unsent upload's bytes are the only copy, so the explicit
        // gesture refuses them exactly as the offline keeper's sweep skips them.
        EvictFileBytes.ID -> EvictFileBytesImplementation(
            fileDatabase = fileDatabase,
            syncBlockEmitterHolder = syncBlockEmitterHolder,
        )
        // Offline keeper (drive-offline-online.md §3.2 background half): the
        // download-only reconcile, dispatched headless by the scheduler.

        OfflineKeeper.ID -> OfflineKeeperImplementation(
            fileDatabase = fileDatabase,
            deviceId = deviceId,
            syncBlockEmitterHolder = syncBlockEmitterHolder,
            // §5.6: the download half refuses a metered network itself, because the schedule
            // asks for none (so the evict half runs on cellular too).
            meteredNetwork = meteredNetwork,
        )
        // Thumbnail-class backfill (drive-synced-thumbnails.md §15.3): mints
        // `wanted(realm) − present(file)` on the byte holder, dispatched headless by the
        // scheduler behind idle + charging + unmetered.
        BackfillThumbnailClasses.ID -> BackfillThumbnailClassesImplementation(
            fileDatabase = fileDatabase,
            objectStore = objectStore,
            deviceId = deviceId,
            syncBlockEmitterHolder = syncBlockEmitterHolder,
            storageCapacityHolder = storageCapacityHolder,
            motionThumbnailRenderer = motionThumbnailRenderer,
        )
        // True byte-presence probe (drive-offline-online.md §3): O(1) fileSize,
        // answered per visible row, never eagerly across a listing.
        HasLocalData.ID -> HasLocalDataImplementation(fileDatabase)
        FileCategoryOf.ID -> FileCategoryOfImplementation()
        // ⚠⚠ An offline move of content this device does not hold is refused with
        // `MoveNeedsLocalBytesError` — the one case the sync repair cannot cover
        // (`content-plane-cr2-b6-spec.md` D3).
        MoveFile.ID -> MoveFileImplementation(
            fileDatabase = fileDatabase,
            tombstoneEmitter = tombstoneEmitter,
            deviceId = deviceId,
            // ⚠⚠ **NOT `caughtUp`.** That signal's own contract forbids the reading: *"read it as
            // 'has everything', never as 'is online'"* — it is an INBOUND property, and a device
            // connected but mid-backlog is `caughtUp = false` while perfectly able to push. The
            // question here is outbound: will this move reach the relay before a peer can free
            // what it names?
            //
            // ⚠ `isOffline` is null in harnesses and unwired runtimes, which reads as ONLINE: an
            // unwired proxy never refuses.
            localBytesGuard = { ctx, source ->
                MoveNeedsLocalBytes.refuseIfUnsafe(
                    ctx, fileDatabase, source,
                    offline = isOffline?.invoke() ?: false,
                    confirmWritable = confirmWritable,
                )
            },
        )
        UploadFile.ID -> UploadFileImplementation(
            fileDatabase, syncBlockEmitterHolder, deviceId, storageCapacityHolder,
            motionThumbnailRenderer = motionThumbnailRenderer,
            thumbnailLadderRenderer = thumbnailLadderRenderer,
        )
        ReplaceFile.ID -> ReplaceFileImplementation(
            fileDatabase, syncBlockEmitterHolder, deviceId, storageCapacityHolder,
            motionThumbnailRenderer = motionThumbnailRenderer,
            thumbnailLadderRenderer = thumbnailLadderRenderer,
        )
        // Background (cancellable Layer-2) variants reuse the same impls
        // verbatim — only the op's trait set (and thus the client-side
        // interactor) differs. See docs/proposals/drive-folder-upload.md §3a.
        UploadFileBg.ID -> UploadFileImplementation(
            fileDatabase, syncBlockEmitterHolder, deviceId, storageCapacityHolder,
            motionThumbnailRenderer = motionThumbnailRenderer,
            thumbnailLadderRenderer = thumbnailLadderRenderer,
        )
        ReplaceFileBg.ID -> ReplaceFileImplementation(
            fileDatabase, syncBlockEmitterHolder, deviceId, storageCapacityHolder,
            motionThumbnailRenderer = motionThumbnailRenderer,
            thumbnailLadderRenderer = thumbnailLadderRenderer,
        )
        GetData.ID -> GetDataImplementation(fileDatabase)
        GetStoredThumbnail.ID -> GetStoredThumbnailImplementation(fileDatabase)
        // Mode A — link sharing (docs/file-sharing.md).
        // ShareLink re-encrypts + uploads to the relay when a
        // `shareRelayClient` + `cryptoManager` are wired (production proxy),
        // and degrades to local-only otherwise; RevokeShareLink propagates the
        // `DELETE` to the relay.
        ShareLink.ID -> ShareLinkImplementation(
            fileDatabase = fileDatabase,
            deviceId = deviceId,
            shareRelayClient = shareRelayClient,
            cryptoManager = cryptoManager,
            storageCapacityHolder = storageCapacityHolder,
        )
        RevokeShareLink.ID -> RevokeShareLinkImplementation(shareRelayClient)
        // Share a folder into a shared realm = mint a SharedFile per descendant
        // (docs/proposals/drive-shared-realm-file-model.md §6) + fan the
        // files' bytes into the realm's content store via the realm-aware
        // FileContentSyncEmitter. Realm bootstrap is the separate
        // SyncService.createSharedRealm(); the emit holder is the only sync-side
        // collaborator (null in test harnesses without sync ⇒ structure only).
        ShareFolderIntoRealm.ID -> ShareFolderIntoRealmImplementation(syncBlockEmitterHolder, storageCapacityHolder)
        // Shared-realm read path (drive-shared-realm-file-model.md §6):
        // list a realm's SharedFile children (the maintainer's array) + read a shared
        // file's bytes (OnDemand fetch + realm-aware reassembly → realm byte-path).
        ListSharedFolder.ID -> ListSharedFolderImplementation()
        ReadSharedFile.ID -> ReadSharedFileImplementation(fileDatabase)
        // Mint on view (`drive-xxlarge-thumbnail-rung.md`). Every gate is here rather than in the caller — a UI check cannot
        // stop another caller, or a network that turned metered after it looked (§4.6).
        EnsureThumbnailClass.ID -> EnsureThumbnailClassImplementation(
            fileDatabase = fileDatabase,
            syncBlockEmitterHolder = syncBlockEmitterHolder,
            storageCapacityHolder = storageCapacityHolder,
            meteredNetwork = meteredNetwork,
            motionThumbnailRenderer = motionThumbnailRenderer,
        )
        // The connector (drive-shared-realm-file-model.md §6, D3): mount a
        // shared realm into the personal FS by saving a `Placement`. Pure personal-
        // realm write — no sync collaborator needed.
        MountSharedRealm.ID -> MountSharedRealmImplementation()
        // List the member's mounts: reads the flat, maintained PLACEMENTS array.
        ListPlacements.ID -> ListPlacementsImplementation()
        PhotosCapturedBetween.ID -> PhotosCapturedBetweenImplementation()
        PhotosNear.ID -> PhotosNearImplementation()
        else -> null
    }
}
