package app.tenshu.drive.domain

import app.tenshu.drive.data.ContentTransferModel
import app.tenshu.drive.data.SchemaFenceModel
import app.tenshu.ingest.backup.StorageStatusModel
import app.tenshu.drive.data.SyncHealthStatusModel
import app.tenshu.reachability.UnreachableReason
import app.tenshu.reachability.UpdateTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pure decision for the sync-status banner (ADR 0019 + connectivity signal
 * `drive-connectivity-service.md`, schema pull fence `core-schema-evolution.md` §7.3).
 * Priority: Disconnected > Locked > NeedsUpdate > LocalOnly > NeedsAttention > Offline >
 * *pass in flight* >
 * Deferred > Syncing > none. Unknown storage/health ⇒ no banner (optimistic, mirrors
 * `StorageGateTest`).
 */
class SyncBannerTest {

    private fun storage(
        hasStorage: Boolean,
        usedBytes: Long = 0L,
        limitBytes: Long = 1_000L,
        relayRejected: Boolean = false,
    ) = StorageStatusModel(
        hasStorage = hasStorage,
        usedBytes = usedBytes,
        limitBytes = limitBytes,
        relayRejected = relayRejected,
    )

    private fun fence(fenced: Boolean, vararg serviceIds: String) =
        SchemaFenceModel(fenced = fenced, serviceIds = serviceIds.toList())

    private fun health(
        online: Boolean = true,
        syncing: Boolean = false,
        pending: Int = 0,
        needsAttention: Boolean = false,
        deferred: Boolean = false,
        locked: Boolean = false,
        deviceUnrecognized: Boolean = false,
        relayRetrying: Boolean = false,
    ) = SyncHealthStatusModel(
        isOnline = online,
        isSyncing = syncing,
        pendingBlockCount = pending,
        lastSyncedAtMillis = 0L,
        needsAttention = needsAttention,
        deferred = deferred,
        locked = locked,
        deviceUnrecognized = deviceUnrecognized,
        relayRetrying = relayRetrying,
    )

    @Test
    fun proxyUnreachable_outranksEverything() {
        // Every other input is a row read FROM the proxy, so while the channel is down they are
        // stale or absent and no state derived from them is trustworthy — including Locked, which
        // is a claim about the proxy that requires having heard from it.
        assertEquals(
            SyncBannerState.Disconnected,
            SyncBanner.evaluate(
                storage(hasStorage = true),
                health(locked = true),
                fence(fenced = true, "tenshu/sync/service"),
                proxyConnected = false,
            ),
        )
    }

    @Test
    fun proxyUnreachable_bannersEvenWhenNothingHasBeenDerivedYet() {
        // What this state exists for: with no signals in, a null verdict would leave Drive
        // rendering an empty file list with no explanation at all.
        assertEquals(
            SyncBannerState.Disconnected,
            SyncBanner.evaluate(storage = null, health = null, proxyConnected = false),
        )
    }

    @Test
    fun proxyReachable_isTheDefault_soExistingBehaviourIsUnchanged() {
        assertNull(SyncBanner.evaluate(storage(hasStorage = true), health()))
    }

    @Test
    fun unknownStorage_noBanner() {
        assertNull(
            SyncBanner.evaluate(storage = null, health = health()),
            "unknown storage must not flash a banner",
        )
    }

    @Test
    fun noSubscription_localOnly_evenWhenOffline() {
        // LocalOnly outranks connectivity: no subscription is the account-wide reason.
        assertEquals(
            SyncBannerState.LocalOnly,
            SyncBanner.evaluate(storage(hasStorage = false), health(online = false)),
        )
    }

    @Test
    fun subscribedAndOnlineAndIdle_noBanner() {
        assertNull(SyncBanner.evaluate(storage(hasStorage = true), health()))
    }

    @Test
    fun unknownHealth_noTransientBanner() {
        assertNull(
            SyncBanner.evaluate(storage(hasStorage = true), health = null),
            "no health signal yet ⇒ none of the transient states",
        )
    }

    @Test
    fun subscribedButRelayUnreachable_offline() {
        assertEquals(
            SyncBannerState.Offline,
            SyncBanner.evaluate(storage(hasStorage = true), health(online = false)),
        )
    }

    @Test
    fun onlineWithFilesUploading_syncingCarriesTheProgress() {
        assertEquals(
            SyncBannerState.Syncing(completed = 4, total = 12, bytesLeft = 45_000L),
            SyncBanner.evaluate(
                storage(hasStorage = true),
                health(online = true),
                content = ContentTransferModel(
                    deferred = false,
                    pendingFileCount = 8,
                    pendingBytes = 45_000L,
                    passTotal = 12,
                    passCompleted = 4,
                ),
            ),
        )
    }

    @Test
    fun theDenominatorIsThePassTotal_notTheShrinkingQueue() {
        // The queue counts DOWN as files finish, so using it as the denominator would render
        // "3 of 9", "4 of 8" — a progress indicator whose denominator chases its numerator.
        val state = SyncBanner.evaluate(
            storage(hasStorage = true),
            health(online = true),
            content = ContentTransferModel(
                deferred = false,
                pendingFileCount = 9,
                passTotal = 12,
                passCompleted = 3,
            ),
        )
        assertEquals(SyncBannerState.Syncing(completed = 3, total = 12), state)
    }

    /**
     * With the content plane waiting out refused writes, the banner must not sit at "Uploading 0 of
     * 12" with no reason. It is still uploading — the files will go — so the reason rides on
     * [SyncBannerState.Syncing], in a pass or between passes.
     */
    @Test
    fun aRelayFailingUploads_isTheReasonOnTheUploadingBanner() {
        val inAPass = SyncBanner.evaluate(
            storage(hasStorage = true),
            health(online = true, relayRetrying = true),
            content = ContentTransferModel(deferred = false, pendingFileCount = 12, passTotal = 12),
        )
        assertEquals(SyncBannerState.Syncing(completed = 0, total = 12, relayRetrying = true), inAPass)
        val betweenPasses = SyncBanner.evaluate(
            storage(hasStorage = true),
            health(online = true, relayRetrying = true),
            content = ContentTransferModel(deferred = false, pendingFileCount = 12),
        )
        assertEquals(SyncBannerState.Syncing(completed = 0, total = 12, relayRetrying = true), betweenPasses)
    }

    @Test
    fun withoutAReportedPass_theDenominatorFallsBackToTheQueue() {
        // A Control that reports no pass, or the tick boundary between passes: degrade to a plain count rather
        // than rendering "3 of 0".
        val state = SyncBanner.evaluate(
            storage(hasStorage = true),
            health(online = true),
            content = ContentTransferModel(deferred = false, pendingFileCount = 5),
        )
        assertEquals(SyncBannerState.Syncing(completed = 0, total = 5), state)
    }

    @Test
    fun metadataBacklogAlone_showsNoBanner() {
        // `drive-upload-progress.md` §1: the banner must not key on `pendingBlockCount` — the
        // realm-wide metadata queue that every app writes to, so on a multi-app device it is
        // approximately never zero, and a banner on it reports plumbing the user has no stake in
        // ("Syncing your changes…" for ever). 42 blocks queued and no files transferring must
        // render NOTHING, so that "no banner" means "nothing is happening".
        assertNull(
            SyncBanner.evaluate(
                storage(hasStorage = true),
                health(online = true, pending = 42),
                content = ContentTransferModel(deferred = false, pendingFileCount = 0),
            ),
        )
    }

    @Test
    fun metadataBacklogWithNoContentSignalYet_showsNoBanner() {
        // Same claim with the content row absent (proxy has not published one yet): the fallback
        // must be "nothing", never a banner conjured from the block count.
        assertNull(
            SyncBanner.evaluate(storage(hasStorage = true), health(online = true, pending = 42)),
        )
    }

    @Test
    fun offlineWithFilesUploading_offlineWins() {
        // Offline outranks Syncing: no point saying "uploading" when the relay is down.
        assertEquals(
            SyncBannerState.Offline,
            SyncBanner.evaluate(
                storage(hasStorage = true),
                health(online = false),
                content = ContentTransferModel(deferred = false, pendingFileCount = 3),
            ),
        )
    }

    @Test
    fun storageFull_saysSo_withTheFigures() {
        // ⚠⚠ Over quota (10.88 GiB used against a 5 GiB free plan), auto-backup is gated to
        // Paused(OUT_OF_SPACE) — and without an `isFull` branch Drive's Home screen would show
        // NOTHING. A user whose uploads have silently stopped is owed the reason.
        assertEquals(
            SyncBannerState.StorageFull(usedBytes = 11_684_489_630L, limitBytes = 5_368_709_120L),
            SyncBanner.evaluate(
                storage(hasStorage = true, usedBytes = 11_684_489_630L, limitBytes = 5_368_709_120L),
                health(),
            ),
        )
    }

    @Test
    fun storageFull_showsBeforeTheProxyHasReported() {
        // Fullness is derived from the storage figures alone, so it is valid — and the most useful
        // thing to say — on a device whose proxy has not reported yet. The same reasoning that puts
        // the fence verdict above the storage guard.
        assertEquals(
            SyncBannerState.StorageFull(usedBytes = 2_000L, limitBytes = 1_000L),
            SyncBanner.evaluate(
                storage(hasStorage = true, usedBytes = 2_000L, limitBytes = 1_000L),
                health = null,
            ),
        )
    }

    @Test
    fun storageFull_whenTheRelayRefusedForSpace_thoughTheFiguresStillShowRoom() {
        // ⚠⚠ **836 bytes** free on a 5 GiB tier: `remainingBytes > 0`, so a figures-only `isFull`
        // is false and the banner would fall through to `Syncing` — "Uploading 297 of 606 · 31.8 Mb
        // left", unchanging, while every one of those 606 is refused with a 507. Chunks are 2 MiB;
        // the arithmetic cannot know that, and the relay does.
        assertEquals(
            SyncBannerState.StorageFull(
                usedBytes = 5_368_708_284L,
                limitBytes = 5_368_709_120L,
            ),
            SyncBanner.evaluate(
                storage(
                    hasStorage = true,
                    usedBytes = 5_368_708_284L,
                    limitBytes = 5_368_709_120L,
                    relayRejected = true,
                ),
                health(),
            ),
            "the relay's refusal must reach the banner even when the figures look fine",
        )
    }

    @Test
    fun sliverOfSpace_withNoRelayRefusal_isNotYetReportedAsFull() {
        // The negative that keeps the rule honest: "nearly full" is not the trigger, "the relay said
        // no" is. Otherwise this becomes a guessed headroom threshold that hides working uploads.
        assertNull(
            SyncBanner.evaluate(
                storage(
                    hasStorage = true,
                    usedBytes = 5_368_708_284L,
                    limitBytes = 5_368_709_120L,
                ),
                health(),
            ),
        )
    }

    @Test
    fun storageFull_outranksNeedsAttention() {
        // ⚠ These are the SAME condition from two ends: `isFull` is the client-side verdict before
        // a write is attempted, `needsAttention` is the relay's 402 after one was. When the realm
        // fills, both go true — and the one that names the cause with checkable numbers is more
        // useful than the one that says "needs attention on this device".
        assertEquals(
            SyncBannerState.StorageFull(usedBytes = 2_000L, limitBytes = 1_000L),
            SyncBanner.evaluate(
                storage(hasStorage = true, usedBytes = 2_000L, limitBytes = 1_000L),
                health(needsAttention = true),
            ),
        )
    }

    @Test
    fun noSubscription_staysLocalOnly_ratherThanFull() {
        // ⚠ A device with no storage cannot be *full* of it. `isFull` already guards on
        // `hasStorage`, but the ordering has to agree: "get storage" and "your storage is full" are
        // different sentences and lead to different places.
        assertEquals(
            SyncBannerState.LocalOnly,
            SyncBanner.evaluate(
                storage(hasStorage = false, usedBytes = 5_000L, limitBytes = 0L),
                health(),
            ),
        )
    }

    @Test
    fun roomLeft_noBanner() {
        // The negative case, and the one that would catch an off-by-one in `remainingBytes`: a
        // realm with room must say nothing at all.
        assertEquals(
            null,
            SyncBanner.evaluate(
                storage(hasStorage = true, usedBytes = 999L, limitBytes = 1_000L),
                health(),
            ),
        )
    }

    @Test
    fun entitlementBlocked_needsAttention() {
        assertEquals(
            SyncBannerState.NeedsAttention,
            SyncBanner.evaluate(storage(hasStorage = true), health(needsAttention = true)),
        )
    }

    @Test
    fun needsAttention_outranksOfflineAndSyncing() {
        // The relay is reachable but blocking this device's writes: "needs attention" is the
        // actionable truth even with a backlog, and outranks a stale offline flag.
        assertEquals(
            SyncBannerState.NeedsAttention,
            SyncBanner.evaluate(
                storage(hasStorage = true),
                health(online = false, pending = 3, needsAttention = true),
            ),
        )
    }

    @Test
    fun noSubscription_localOnly_outranksNeedsAttention() {
        // LocalOnly is account-wide and still the top reason.
        assertEquals(
            SyncBannerState.LocalOnly,
            SyncBanner.evaluate(storage(hasStorage = false), health(needsAttention = true)),
        )
    }

    @Test
    fun deviceUnrecognized_isNotReportedAsOffline() {
        // What this state exists for. The relay answered — with a 401 — so the device is
        // demonstrably online, but nothing will ever sync until it is re-admitted. Rendering that as
        // "Offline — will sync when you reconnect" is two lies in one line, shown for as long as the
        // device keeps being rejected.
        assertEquals(
            SyncBannerState.DeviceUnrecognized,
            SyncBanner.evaluate(storage(hasStorage = true), health(deviceUnrecognized = true)),
        )
    }

    @Test
    fun deviceUnrecognized_outranksNeedsAttentionFenceAndLocalOnly() {
        // Writes from an unrecognised device are refused before entitlement is consulted, so a 402
        // message would name a consequence; the fence verdict and the storage figures are both read
        // from a relay that is refusing this device, so neither can be fresher than the 401s.
        assertEquals(
            SyncBannerState.DeviceUnrecognized,
            SyncBanner.evaluate(
                storage = storage(hasStorage = false),
                health = health(online = false, needsAttention = true, deferred = true, deviceUnrecognized = true),
                fence = fence(fenced = true, "tenshu/file/service"),
            ),
        )
    }

    @Test
    fun locked_outranksDeviceUnrecognized() {
        // A sealed realm cannot even attempt the relay, so the 401 reading is stale by construction
        // — and unlocking is a prerequisite for the re-pairing this state would ask for.
        assertEquals(
            SyncBannerState.Locked,
            SyncBanner.evaluate(
                storage(hasStorage = true),
                health(locked = true, deviceUnrecognized = true),
            ),
        )
    }

    @Test
    fun recognizedDevice_showsNoUnrecognizedBanner() {
        // The flag is the only input to this state, so a healthy device must never reach it — this is
        // the guard against a latched proxy flag telling a working device it was removed.
        assertEquals(
            null,
            SyncBanner.evaluate(storage(hasStorage = true), health(deviceUnrecognized = false)),
        )
    }

    @Test
    fun locked_outranksLocalOnly_whenStorageZeroedByTheLock() {
        // What this state exists for. A locked proxy nulls `currentOwnerId`, so it
        // cannot read the entitlement and reports hasStorage = false — byte-identical to
        // "never subscribed". Without the Locked state, a sealed realm renders as
        // "Local only — set up storage", sending the user to buy storage they already own.
        assertEquals(
            SyncBannerState.Locked,
            SyncBanner.evaluate(storage(hasStorage = false), health(locked = true)),
        )
    }

    @Test
    fun locked_outranksEveryTransientState() {
        // While sealed the sync loop never reaches the relay, so connectivity/backlog/402 are
        // all meaningless — unlocking is the only action that changes anything.
        assertEquals(
            SyncBannerState.Locked,
            SyncBanner.evaluate(
                storage(hasStorage = true),
                health(online = false, pending = 7, needsAttention = true, deferred = true, locked = true),
            ),
        )
    }

    @Test
    fun locked_bannersEvenBeforeStorageIsKnown() {
        // Locked is evaluated ahead of the `storage == null` optimism guard: it is authoritative
        // on its own and is the one state the user can act on, so it must not wait for figures
        // that a locked proxy will never produce.
        assertEquals(
            SyncBannerState.Locked,
            SyncBanner.evaluate(storage = null, health = health(locked = true)),
        )
    }

    @Test
    fun unlockedWithSubscription_noLockedBanner() {
        // Guard against the flag going sticky: a healthy unlocked realm shows no banner at all.
        assertNull(SyncBanner.evaluate(storage(hasStorage = true), health(locked = false)))
    }

    @Test
    fun deferredWithFilesWaiting_carriesTheFileCount() {
        // WifiOnly on metered: file uploads withheld by policy → the benign "Waiting for Wi-Fi —
        // N files" state. The count comes from the CONTENT row, not the block backlog.
        assertEquals(
            SyncBannerState.Deferred(pendingCount = 5),
            SyncBanner.evaluate(
                storage(hasStorage = true),
                health(online = true, deferred = true),
                content = ContentTransferModel(deferred = true, pendingFileCount = 5),
            ),
        )
    }

    @Test
    fun deferredCountIgnoresTheBlockBacklog() {
        // `sync-metered-plane-split.md` §0: `pendingBlockCount` is
        // the realm-wide metadata queue — every app's Syncable writes, not Drive files — so it
        // must not reach the banner. Here 42 blocks are queued and ONE file is waiting: the
        // banner must say 1.
        assertEquals(
            SyncBannerState.Deferred(pendingCount = 1),
            SyncBanner.evaluate(
                storage(hasStorage = true),
                health(online = true, pending = 42, deferred = true),
                content = ContentTransferModel(deferred = true, pendingFileCount = 1),
            ),
        )
    }

    @Test
    fun aPassInFlightOutranksDeferred() {
        // During a forced "Upload over cellular" drain the metered gate is still closed, so the
        // proxy still reports deferred = true — but bytes ARE moving. Saying "waiting for Wi-Fi"
        // about an upload the user just started would be a lie about the bytes.
        assertEquals(
            SyncBannerState.Syncing(completed = 1, total = 3, bytesLeft = 900L),
            SyncBanner.evaluate(
                storage(hasStorage = true),
                health(online = true, deferred = true),
                content = ContentTransferModel(
                    deferred = true,
                    pendingFileCount = 2,
                    pendingBytes = 900L,
                    passTotal = 3,
                    passCompleted = 1,
                ),
            ),
        )
    }

    // --- Drive's own folder-upload bar owns the story while it is on screen
    // (`drive-upload-progress.md` §11).

    @Test
    fun folderUploadActive_suppressesTheTransferBanner() {
        // Both surfaces would otherwise narrate the same upload in different numbers: the folder
        // bar with the real batch denominator ("3 of 12"), the banner with whatever single file is
        // in flight ("1 of 1"). The bar wins — it also carries conflict, pre-flight and cancel.
        assertNull(
            SyncBanner.evaluate(
                storage(hasStorage = true),
                health(online = true),
                content = ContentTransferModel(
                    deferred = false,
                    pendingFileCount = 1,
                    passTotal = 1,
                ),
                folderUploadActive = true,
            ),
        )
    }

    @Test
    fun folderUploadActive_stillShowsWaitingForWiFi() {
        // The one transfer message that is COMPLEMENTARY rather than redundant. On metered the
        // folder bar runs to "12 of 12" — its walk finished, metadata written, content journalled —
        // while not one byte has left the device. Hiding the reason there is the worst outcome.
        assertEquals(
            SyncBannerState.Deferred(pendingCount = 12, bytesLeft = 4_000L),
            SyncBanner.evaluate(
                storage(hasStorage = true),
                health(online = true, deferred = true),
                content = ContentTransferModel(
                    deferred = true,
                    pendingFileCount = 12,
                    pendingBytes = 4_000L,
                ),
                folderUploadActive = true,
            ),
        )
    }

    @Test
    fun folderUploadActive_doesNotSuppressTheActionableStates() {
        // Suppression is scoped to the transfer story. A locked realm, a schema fence or a missing
        // subscription are all reasons the upload the bar is narrating cannot actually finish.
        assertEquals(
            SyncBannerState.Locked,
            SyncBanner.evaluate(
                storage(hasStorage = true),
                health(locked = true),
                folderUploadActive = true,
            ),
        )
        assertEquals(
            SyncBannerState.LocalOnly,
            SyncBanner.evaluate(
                storage(hasStorage = false),
                health(online = true),
                folderUploadActive = true,
            ),
        )
    }

    @Test
    fun deferred_outranksOffline() {
        // With Offline on top, the deferred state is unreachable on a metered device: `isOnline`
        // flaps because every live scheduler instance writes the same health row
        // (`sync-metered-plane-split.md` §9c). It is also the better message: both are true of an
        // offline device with queued uploads, but this one names the cause and carries an action.
        val state = SyncBanner.evaluate(
            storage(hasStorage = true),
            health(online = false, syncing = true, pending = 7, deferred = true),
            content = ContentTransferModel(deferred = true, pendingFileCount = 7),
        )
        assertEquals(SyncBannerState.Deferred(pendingCount = 7), state)
    }

    @Test
    fun needsAttention_outranksDeferred() {
        // A write-credential problem is actionable and trumps a mere policy deferral.
        assertEquals(
            SyncBannerState.NeedsAttention,
            SyncBanner.evaluate(
                storage(hasStorage = true),
                health(online = false, pending = 3, needsAttention = true, deferred = true),
            ),
        )
    }

    @Test
    fun rawIsSyncingWithoutBacklog_noBanner() {
        // The flickery isSyncing flag alone must not raise a banner
        // (`drive-connectivity-service.md` §6 Q3) — only a real backlog does.
        assertNull(
            SyncBanner.evaluate(storage(hasStorage = true), health(online = true, syncing = true, pending = 0)),
        )
    }

    // --- schema pull fence (core-schema-evolution.md §7.3) ---

    @Test
    fun schemaFenced_needsUpdate_carryingTheOffendingServices() {
        assertEquals(
            SyncBannerState.NeedsUpdate(listOf("tenshu/sync/service")),
            SyncBanner.evaluate(
                storage(hasStorage = true),
                health(online = true),
                fence(fenced = true, "tenshu/sync/service"),
            ),
        )
    }

    @Test
    fun needsUpdate_outranksEveryOutboundState() {
        // Every other state describes the OUTBOUND side; the fence says the inbound side is
        // deliberately stopped, which is why this device's list can disagree with the user's
        // other devices. "Syncing…" here would be an outright lie in the useful direction:
        // pushes really are draining (push-allowed, §7.3) while pulls are fenced.
        assertEquals(
            SyncBannerState.NeedsUpdate(listOf("tenshu/fit/service")),
            SyncBanner.evaluate(
                storage(hasStorage = false),
                health(online = false, pending = 5, needsAttention = true, deferred = true),
                fence(fenced = true, "tenshu/fit/service"),
            ),
        )
    }

    @Test
    fun locked_outranksNeedsUpdate() {
        // An unlock is a prerequisite for anything at all — including noticing that the
        // device is behind. (A locked proxy holds no keys, so its epoch read is moot.)
        assertEquals(
            SyncBannerState.Locked,
            SyncBanner.evaluate(
                storage(hasStorage = true),
                health(locked = true),
                fence(fenced = true, "tenshu/sync/service"),
            ),
        )
    }

    @Test
    fun needsUpdate_bannersEvenBeforeStorageIsKnown() {
        // Evaluated ahead of the `storage == null` optimism guard: the verdict comes from
        // the realm epoch alone, so it is valid on a device whose storage figures have not
        // arrived — or never will, because it has no subscription.
        assertEquals(
            SyncBannerState.NeedsUpdate(emptyList()),
            SyncBanner.evaluate(storage = null, health = null, fence = fence(fenced = true)),
        )
    }

    @Test
    fun notFenced_leavesTheOtherStatesAlone() {
        assertNull(
            SyncBanner.evaluate(storage(hasStorage = true), health(), fence(fenced = false)),
            "a cleared fence must not shadow the ordinary healthy no-banner case",
        )
        assertEquals(
            SyncBannerState.Syncing(completed = 0, total = 3),
            SyncBanner.evaluate(
                storage(hasStorage = true),
                health(online = true),
                fence(fenced = false),
                content = ContentTransferModel(deferred = false, pendingFileCount = 3),
            ),
        )
    }

    @Test
    fun unknownFence_behavesExactlyAsBeforeTheSignalExisted() {
        // `null` = the proxy has not evaluated yet (or a repository that does not supply
        // the signal at all — the FilesRepository default). Must be indistinguishable from
        // no fence at all, which is what keeps every fake and non-syncing repo working.
        assertEquals(
            SyncBannerState.LocalOnly,
            SyncBanner.evaluate(storage(hasStorage = false), health(), fence = null),
        )
    }

    // ---- A running auto-backup owns the figures (no jumping denominator) ----

    @Test
    fun backupRunning_theDrainsTotalReplacesThePassTotal() {
        // "20 of 400" must not jump to 500 mid-backup. The pass total is a snapshot of the upload
        // queue taken when the pass began, and during a backup the drain feeds that queue faster
        // than the relay drains it — so the NEXT pass snapshots a bigger number. The drain
        // enumerated all 400 before uploading anything.
        // ⚠ Every figure here differs between the two sources on purpose: a fixture where the pass
        // and the drain agree would pass with the substitution removed.
        val state = SyncBanner.evaluate(
            storage(hasStorage = true),
            health(online = true),
            content = ContentTransferModel(
                deferred = false,
                pendingFileCount = 480,
                pendingBytes = 900_000L,
                passTotal = 500,
                passCompleted = 20,
            ),
            backupProgress = BackupProgress(completed = 60, total = 400),
        )
        assertEquals(
            SyncBannerState.Syncing(completed = 60, total = 400, bytesLeft = 900_000L, stoppableBackup = true),
            state,
        )
    }

    @Test
    fun backupRunning_theDenominatorHoldsWhileTheQueueGrowsUnderIt() {
        // Two ticks of the same drain: the queue-derived figures move from 400 to 500 while the
        // drain's own total does not. Without
        // the substitution these two evaluations return different denominators.
        fun evaluateAt(passTotal: Int, queued: Int) = SyncBanner.evaluate(
            storage(hasStorage = true),
            health(online = true),
            content = ContentTransferModel(
                deferred = false,
                pendingFileCount = queued,
                passTotal = passTotal,
                passCompleted = 20,
            ),
            backupProgress = BackupProgress(completed = 60, total = 400),
        )
        assertEquals(
            (evaluateAt(passTotal = 400, queued = 380) as SyncBannerState.Syncing).total,
            (evaluateAt(passTotal = 500, queued = 480) as SyncBannerState.Syncing).total,
            "the denominator must not move because the importer got ahead of the uploader",
        )
        assertEquals(400, (evaluateAt(passTotal = 500, queued = 480) as SyncBannerState.Syncing).total)
    }

    @Test
    fun backupRunning_withNoPassReported_stillUsesTheDrainsFigures() {
        // The tick boundary between passes, where the fallback denominator is the LIVE queue —
        // exactly the figure that grows under a running backup, so it is the worst of the three.
        val state = SyncBanner.evaluate(
            storage(hasStorage = true),
            health(online = true),
            content = ContentTransferModel(deferred = false, pendingFileCount = 480),
            backupProgress = BackupProgress(completed = 60, total = 400),
        )
        assertEquals(SyncBannerState.Syncing(completed = 60, total = 400, stoppableBackup = true), state)
    }

    // ---- `drive-upload-progress.md` §14.4: "· N left" is the backup's bytes, not the file in flight ----

    @Test
    fun backupRunning_theByteFigureAddsTheRunsUnsentBytesToTheJournalsInFlightOnes() {
        // The bytes beside the count ("250 of 2271") are the backup's, not the size of the current
        // file. Neither loop holds the whole answer on its own — the run knows what it
        // has NOT handed off, the journal knows what has been handed off but not yet uploaded — so
        // the banner adds them. ⚠ Both inputs are non-zero on purpose: a fixture where either is
        // zero passes with the composition removed.
        val state = SyncBanner.evaluate(
            storage(hasStorage = true),
            health(online = true),
            content = ContentTransferModel(
                deferred = false,
                pendingFileCount = 480,
                pendingBytes = 1_000_000L,
                passTotal = 500,
                passCompleted = 20,
            ),
            backupProgress = BackupProgress(completed = 60, total = 400, remainingBytes = 14_000_000L),
        )
        assertEquals(
            15_000_000L,
            (state as SyncBannerState.Syncing).bytesLeft,
            "the run's 14 MB still to hand off, plus the journal's 1 MB already in flight",
        )
    }

    @Test
    fun backupRunning_thatCannotSizeItsItems_fallsBackToTheJournalAlone() {
        // A provider with no cheap per-item size (iOS PhotoKit) must fall back to the journal's
        // figure alone — a known-imperfect number rather than a new wrong one.
        val state = SyncBanner.evaluate(
            storage(hasStorage = true),
            health(online = true),
            content = ContentTransferModel(
                deferred = false,
                pendingFileCount = 480,
                pendingBytes = 1_000_000L,
                passTotal = 500,
                passCompleted = 20,
            ),
            backupProgress = BackupProgress(completed = 60, total = 400, remainingBytes = null),
        )
        assertEquals(1_000_000L, (state as SyncBannerState.Syncing).bytesLeft)
    }

    @Test
    fun backupRunning_composesTheByteFigureOnEveryTransferBranch() {
        // ⚠ There are three branches that build a `Syncing`, and each builds its own. A
        // composition applied to only the pass-reported one would look right in a demo and be
        // absent on a phone at the tick boundary.
        val queueFallback = SyncBanner.evaluate(
            storage(hasStorage = true),
            health(online = true),
            content = ContentTransferModel(
                deferred = false,
                pendingFileCount = 480,
                pendingBytes = 1_000_000L,
            ),
            backupProgress = BackupProgress(completed = 60, total = 400, remainingBytes = 14_000_000L),
        )
        assertEquals(15_000_000L, (queueFallback as SyncBannerState.Syncing).bytesLeft)

        // The uploader has momentarily caught up with the importer: nothing queued, run still on.
        val emptyQueue = SyncBanner.evaluate(
            storage(hasStorage = true),
            health(online = true),
            content = ContentTransferModel(deferred = false, pendingFileCount = 0, pendingBytes = 0L),
            backupProgress = BackupProgress(completed = 60, total = 400, remainingBytes = 14_000_000L),
        )
        assertEquals(14_000_000L, (emptyQueue as SyncBannerState.Syncing).bytesLeft)
    }

    @Test
    fun backupRunning_withAnEmptyQueue_keepsTheBannerUp() {
        // The uploader momentarily caught up with the importer. The run IS in progress and its
        // figures are honest, so the banner must not blink off between files.
        assertEquals(
            SyncBannerState.Syncing(completed = 120, total = 400, stoppableBackup = true),
            SyncBanner.evaluate(
                storage(hasStorage = true),
                health(online = true),
                content = ContentTransferModel(deferred = false, pendingFileCount = 0),
                backupProgress = BackupProgress(completed = 120, total = 400),
            ),
        )
    }

    @Test
    fun backupRunning_doesNotOutrankTheStatesThatExplainWhyNothingMoves() {
        // ⚠ The substitution changes the NUMBERS, not the priority. A drain busily importing into
        // a queue the metered gate is holding must still read "waiting for Wi-Fi" — that names the
        // cause and carries an action, where "Uploading 60 of 400" would be an outright lie about
        // bytes that are parked.
        assertEquals(
            SyncBannerState.Deferred(pendingCount = 60, bytesLeft = 4_000L),
            SyncBanner.evaluate(
                storage(hasStorage = true),
                health(online = true, deferred = true),
                content = ContentTransferModel(
                    deferred = true,
                    pendingFileCount = 60,
                    pendingBytes = 4_000L,
                ),
                backupProgress = BackupProgress(completed = 60, total = 400),
            ),
        )
        assertEquals(
            SyncBannerState.Offline,
            SyncBanner.evaluate(
                storage(hasStorage = true),
                health(online = false),
                content = ContentTransferModel(deferred = false, pendingFileCount = 60),
                backupProgress = BackupProgress(completed = 60, total = 400),
            ),
        )
        assertEquals(
            SyncBannerState.Locked,
            SyncBanner.evaluate(
                storage(hasStorage = true),
                health(locked = true),
                backupProgress = BackupProgress(completed = 60, total = 400),
            ),
        )
    }

    @Test
    fun noBackupRunning_leavesEveryFigureExactlyAsItWas() {
        // `null` = between drains, or a platform that never wires the signal (the default). Must be
        // indistinguishable from no backup signal at all — the queue's own figures.
        assertEquals(
            SyncBannerState.Syncing(completed = 3, total = 12),
            SyncBanner.evaluate(
                storage(hasStorage = true),
                health(online = true),
                content = ContentTransferModel(
                    deferred = false,
                    pendingFileCount = 9,
                    passTotal = 12,
                    passCompleted = 3,
                ),
                backupProgress = null,
            ),
        )
        assertNull(
            SyncBanner.evaluate(
                storage(hasStorage = true),
                health(online = true),
                content = ContentTransferModel(deferred = false, pendingFileCount = 0),
                backupProgress = null,
            ),
            "an idle device with no backup running must still show no banner at all",
        )
    }

    // --- The banner's Stop is offered for a BACKUP, never for a plain upload ----------
    // (`drive-upload-stop-and-reclamation.md`)

    /**
     * ⚠⚠ The distinction the whole flag exists for. A hand-started upload puts the banner in
     * exactly the same [SyncBannerState.Syncing] state, and it has no pause switch and no queue to
     * resume — so a Stop there would either do nothing or mean something different from what it
     * means during a backup. One word, two meanings, is how a control stops being trustworthy.
     */
    @Test
    fun aPlainUpload_offersNoStop() {
        val state = SyncBanner.evaluate(
            storage = storage(hasStorage = true),
            health = health(online = true),
            content = ContentTransferModel(
                deferred = false,
                pendingFileCount = 12,
                pendingBytes = 45_000L,
                passTotal = 12,
                passCompleted = 4,
            ),
            backupProgress = null,
        )
        assertEquals(
            false,
            (state as SyncBannerState.Syncing).stoppableBackup,
            "no drain is running, so there is nothing this Stop could pause",
        )
    }

    /** And a running drain does offer it, whichever branch produced the state. */
    @Test
    fun aBackupDrain_offersStop_fromEveryBranchThatCanReportIt() {
        val storage = storage(hasStorage = true)
        val progress = BackupProgress(completed = 5, total = 40)

        // Branch 1: a pass is being reported.
        val withPass = SyncBanner.evaluate(
            storage = storage,
            health = health(online = true),
            content = ContentTransferModel(
                deferred = false,
                pendingFileCount = 30, pendingBytes = 1L, passTotal = 30, passCompleted = 5,
            ),
            backupProgress = progress,
        )
        // Branch 2: no pass reported yet, but files are queued.
        val queuedOnly = SyncBanner.evaluate(
            storage = storage,
            health = health(online = true),
            content = ContentTransferModel(
                deferred = false,
                pendingFileCount = 30, pendingBytes = 1L, passTotal = 0, passCompleted = 0,
            ),
            backupProgress = progress,
        )
        // Branch 3: the queue is momentarily empty because the uploader kept up with the importer.
        val emptyQueue = SyncBanner.evaluate(
            storage = storage,
            health = health(online = true),
            content = ContentTransferModel(
                deferred = false,
                pendingFileCount = 0, pendingBytes = 0L, passTotal = 0, passCompleted = 0,
            ),
            backupProgress = progress,
        )

        listOf("with a pass" to withPass, "queued only" to queuedOnly, "empty queue" to emptyQueue)
            .forEach { (label, state) ->
                assertEquals(
                    true,
                    (state as? SyncBannerState.Syncing)?.stoppableBackup,
                    "a running drain must offer Stop — branch: $label",
                )
            }
    }

    // ---- The announce refusal (`core-schema-skew-surface.md`) ------------------

    /**
     * ⚠⚠ **What this state is for.** A proxy that answers and *refuses* leaves `proxyConnected`
     * true, no storage figures and no health row — so without a skew input the chain falls through
     * both `null` guards and returns **no banner at all**. An empty file list with nothing to
     * explain it, in the app whose whole job is showing you your files.
     */
    @Test
    fun aRefusedSessionWithNoOtherSignal_showsABannerInsteadOfNothing() {
        val before = SyncBanner.evaluate(storage = null, health = null)
        assertNull(before, "with no skew input there is no banner — the case this test contrasts with")

        val after = SyncBanner.evaluate(
            storage = null,
            health = null,
            skew = UnreachableReason.Skew(UpdateTarget.CONTROL),
        )

        assertEquals(SyncBannerState.NeedsAppUpdate(UpdateTarget.CONTROL), after)
    }

    /**
     * ⚠⚠ The app to name is frequently **not** Drive: on Android and desktop one Control-owned
     * proxy serves every app, so telling the user to update Drive would leave them stuck after
     * doing exactly what the banner asked. The target is carried, never assumed.
     */
    @Test
    fun theUpdateTargetIsCarriedThrough_notGuessed() {
        val state = SyncBanner.evaluate(
            storage = storage(hasStorage = true),
            health = health(),
            skew = UnreachableReason.Skew(UpdateTarget.THIS_APP),
        )

        assertEquals(SyncBannerState.NeedsAppUpdate(UpdateTarget.THIS_APP), state)
    }

    /**
     * ⚠ Outranks `Locked`: an unlock cannot help a session the proxy will not admit, and every
     * figure below is absent or stale while it refuses.
     */
    @Test
    fun aRefusedSessionOutranksALockedRealm() {
        val state = SyncBanner.evaluate(
            storage = storage(hasStorage = true),
            health = health(locked = true),
            skew = UnreachableReason.Skew(UpdateTarget.CONTROL),
        )

        assertEquals(SyncBannerState.NeedsAppUpdate(UpdateTarget.CONTROL), state)
    }

    /**
     * ⚠⚠ But **not** `Disconnected`. With the channel down the refusal verdict is itself stale —
     * it describes a session that is no longer being attempted — so the honest statement is the
     * one about the channel.
     */
    @Test
    fun aDeadChannelStillOutranksAStaleRefusalVerdict() {
        val state = SyncBanner.evaluate(
            storage = storage(hasStorage = true),
            health = health(),
            proxyConnected = false,
            skew = UnreachableReason.Skew(UpdateTarget.CONTROL),
        )

        assertEquals(SyncBannerState.Disconnected, state)
    }

    /** ⚠ No skew wired ⇒ byte-for-byte the verdict without that input, which is what makes adoption safe. */
    @Test
    fun withoutASkewSourceNothingChanges() {
        assertEquals(
            SyncBanner.evaluate(storage = storage(hasStorage = false), health = health()),
            SyncBanner.evaluate(storage = storage(hasStorage = false), health = health(), skew = null),
        )
    }

    /** A backup held by policy is named with its count, rather than nothing while photos wait for Wi-Fi. */
    @Test
    fun aBackupHeldByPolicy_isNamedWithItsCount() {
        assertEquals(
            SyncBannerState.BackupWaiting(BackupWaitCause.WIFI, 12),
            SyncBanner.evaluate(
                storage(hasStorage = true), health(), fence = null,
                backupWaiting = BackupWaiting(BackupWaitCause.WIFI, 12),
            ),
        )
    }

    @Test
    fun aPausedBackupWithNothingNew_showsNoBanner() {
        assertNull(
            SyncBanner.evaluate(
                storage(hasStorage = true), health(), fence = null,
                backupWaiting = BackupWaiting(BackupWaitCause.WIFI, 0),
            ),
        )
    }

    /** Offline is the harder stop; the backup's own pause must not paper over it. */
    @Test
    fun aHeldBackupRanksBelowOffline() {
        assertEquals(
            SyncBannerState.Offline,
            SyncBanner.evaluate(
                storage(hasStorage = true), health(online = false), fence = null,
                backupWaiting = BackupWaiting(BackupWaitCause.CHARGING, 3),
            ),
        )
    }
}
