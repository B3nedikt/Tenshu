package org.tenshu.file.test

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.proxy.OnDemandResolution
import org.tenshu.core.core.TComponent
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.core.time.Clock
import org.tenshu.file.proxy.fileServiceProxyImplementationFactory
import org.tenshu.file.proxy.initFileService
import org.tenshu.notifications.proxy.initNotificationService
import org.tenshu.sync.common.ShareRelayClient
import org.tenshu.sync.common.SyncBlockEmitterHolder
import org.tenshu.sync.common.TombstoneEmitter

fun createFileProxy(
    coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    // When set, write ops stamp + emit FileModification history
    // (docs/proposals/file-service-history.md Phase 2). Default null keeps the
    // pre-history behaviour for tests that don't exercise the modification log.
    deviceId: String? = null,
    // Override the proxy clock — e.g. a FakeClock the test advances per write so
    // each modification gets a distinct timestamp (distinct compositeKey). Needed
    // by retention tests that must produce many distinct rows for one file.
    clock: Clock? = null,
    // Mode-A link sharing (docs/file-sharing.md). When both are set,
    // `ShareLink` re-encrypts + uploads to the (fake) relay and `RevokeShareLink`
    // issues the DELETE; default null keeps the local-only path.
    shareRelayClient: ShareRelayClient? = null,
    cryptoManager: SyncCryptoManager? = null,
    // Two-plane content-emit holder (docs/adr/0017-file-content-two-plane-channel.md). When set, content-
    // emitting ops (UploadFile, ShareFolderIntoRealm) route bytes through its
    // `fileContentEmitter`; default null keeps the structure-only path for tests
    // without sync. Lets a test inject a fake emitter to assert emit wiring.
    syncBlockEmitterHolder: SyncBlockEmitterHolder? = null,
    // Tombstone (`DeleteBlock`) sink for delete/move ops (`DeleteFile`, `MoveFile`).
    // In production it's `SyncObserver`; a test injects a recording fake to assert a
    // move emits an old-path tombstone (file-content-addressing.md §3.3.3.A). Default
    // null keeps the local-only path (no relay tombstone).
    tombstoneEmitter: TombstoneEmitter? = null,
    // Default to the in-memory store and a REAL reentrant lock: the default
    // scope is `Dispatchers.Default`, so the file/notification observers write
    // from worker threads beside the test (`TestDatabaseLock` says why no-op is
    // wrong here). Inject the production pair (`SqliteObjectStore` +
    // `ReentrantLockDatabaseLock`) to exercise persistent-store behaviour
    // (drive-folder-listing-consistency.md §4).
    objectStore: org.tenshu.core.common.proxy.db.ObjectStore =
        org.tenshu.core.common.proxy.db.InMemoryObjectStore(),
    lock: org.tenshu.core.common.proxy.db.DatabaseLock =
        org.tenshu.core.common.TestDatabaseLock(),
    // The OnDemand byte-fetch component (`File.Data` is `Syncable(OnDemand)`).
    // In production it's `:sync-service-proxy`'s `OnDemandResolutionImpl`, which
    // pulls the file's chunks off the relay; a test injects a fake to exercise
    // the ops that must drive a fetch when the bytes aren't on local disk
    // (`ShareLink`, `ReadSharedFile`, `OfflineKeeper`). Default null = no
    // resolver, i.e. the pre-sync behaviour where absent bytes stay absent.
    onDemandResolution: OnDemandResolution? = null,
    // M4 (photos-video-support.md §4.4): the `MOTION`-class encoder. Default null mints nothing,
    // which is what every platform but Android does; a test injects a fake to assert the upload
    // path's mint + emit wiring without a codec.
    motionThumbnailRenderer: org.tenshu.file.proxy.MotionThumbnailRenderer? = null,
    // P1a (media-processing-pipeline-sweep.md §4.1): the one-decode still-ladder renderer. Default null
    // renders rung by rung through `GetPreview` — Android's and iOS's behaviour today.
    thumbnailLadderRenderer: org.tenshu.file.proxy.ThumbnailLadderRenderer? = null,
    // The Tier-2 quota gate's figures (`ensureWithinQuota`). ⚠ Default null is the no-op gate, so a
    // test that never sets one cannot see what upload or replace does on a full realm.
    storageCapacityHolder: org.tenshu.storage.proxy.StorageCapacityHolder? = null,
    // X-4 (`drive-xxlarge-thumbnail-rung.md` §4.6): the view-mint metered gate. ⚠ The default never
    // refuses — matching production's unwired platforms — so a test that wants to see the refusal
    // must say so, and one that does not is unaffected.
    meteredNetwork: org.tenshu.file.proxy.MeteredNetworkSignal =
        org.tenshu.file.proxy.MeteredNetworkSignal.Unknown,
    // CR-2 B6 D3: `MoveFile`'s offline signal and its acknowledged probe. Default null reads as
    // online — production's unwired default — so no move is refused unless a test says so.
    isOffline: (() -> Boolean)? = null,
    confirmWritable: (suspend () -> Boolean?)? = null,
) =
    TestProxy(
        schemaRegistry = createFileTestRegistry(),
        coroutineScope = coroutineScope,
        additionalComponents = buildMap<String, TComponent> {
            clock?.let { put(Clock.COMPONENT_ID, it) }
            onDemandResolution?.let { put(OnDemandResolution.ID, it) }
        },
        objectStore = objectStore,
        lock = lock,
        implementationFactoryOverride = { id, proxy ->
            fileServiceProxyImplementationFactory(
                id = id,
                fileDatabase = proxy.fileDatabase,
                deviceId = deviceId,
                shareRelayClient = shareRelayClient,
                cryptoManager = cryptoManager,
                syncBlockEmitterHolder = syncBlockEmitterHolder,
                tombstoneEmitter = tombstoneEmitter,
                motionThumbnailRenderer = motionThumbnailRenderer,
                thumbnailLadderRenderer = thumbnailLadderRenderer,
                storageCapacityHolder = storageCapacityHolder,
                // W-2: the same store the proxy's database runs on, so the thumbnail backfill can
                // enumerate the rows the test saved.
                objectStore = objectStore,
                meteredNetwork = meteredNetwork,
                isOffline = isOffline,
                confirmWritable = confirmWritable,
            )
        }
    ).apply {
        // ⚠⚠ The `fileDatabase` is load-bearing, not decoration. `DeletedFolderSubtreeReaper` drops
        // a deleted subtree's plaintext through `fileDatabase?.deleteFile(...)`, so a harness that
        // omitted it registered a reaper whose byte-drop was a silent no-op — every test exercising
        // a folder-delete cascade was measuring the null branch. Found 2026-09-06 while checking
        // whether an ingested tombstone frees a peer's disk.
        executionContext.initFileService(fileDatabase = fileDatabase)
        // `CreateFolderImplementation` / `DeleteFileImplementation` write
        // `Notification` entities via `executionContext.saveData`. The relation
        // membership in `NotificationService.Relations.NOTIFICATIONS` is
        // derived locally by `NotificationRelationMaintainer`; without
        // `initNotificationService` here, notifications land in the local DB
        // but stay invisible to anything querying the relation (which is
        // what `CreateFolderNotificationUnitTest` and
        // `NotificationStreamingTest` do).
        executionContext.initNotificationService()
    }
