package app.tenshu.drive.domain

import app.tenshu.ingest.backup.SyncedFolderController
import app.tenshu.reachability.UnreachableReason
import app.tenshu.reachability.UpdateTarget
import app.tenshu.drive.data.ContentTransferModel
import app.tenshu.drive.data.SchemaFenceModel
import app.tenshu.ingest.backup.StorageStatusModel
import app.tenshu.drive.data.SyncHealthStatusModel

/**
 * The persistent, account-wide sync-status banner shown above the file list
 * (local-first reframe, ADR 0019; connectivity signal
 * `docs/proposals/drive-connectivity-service.md`). A pure function of the three
 * signals the Drive client observes over IPC: the storage-provision status, the
 * proxy's sync-health status, its schema pull-fence verdict, and how much file
 * content the metered gate is holding.
 *
 * States, in priority order:
 *  - [Disconnected] — Control cannot be reached at all, so every signal below is stale or
 *    absent and none of them can be trusted to describe anything.
 *  - [Locked] — Control's realm is sealed on this device; nothing syncs until the
 *    user unlocks. Outranks [LocalOnly] because a locked proxy *also* reports
 *    `hasStorage = false` (see [Locked]).
 *  - [DeviceUnrecognized] — the relay holds no registration for this device, so nothing
 *    syncs until it is re-admitted. Reachable, so never [Offline].
 *  - [NeedsUpdate] — this device's schema is behind the realm, so the proxy has
 *    stopped pulling (`core-schema-evolution.md` §7.3).
 *  - [LocalOnly] — no storage subscription, so files never reach the relay
 *    (account-wide + persistent → a global banner, not a per-row badge).
 *  - [NeedsAttention] — the relay is reachable but rejecting this device's
 *    writes (`402`, entitlement enforcement); the device needs a write credential.
 *  - [Offline] — storage is bound but the relay was unreachable on the last
 *    attempt; changes are queued locally and will sync when connectivity returns.
 *  - [Deferred] — the user's `WifiOnly` policy is holding file uploads on a metered
 *    network (`sync-metered-plane-split.md` §8). Above [Offline]: it names the cause
 *    and offers an action where "Offline" names only a symptom.
 *  - [Syncing] — files are uploading. Reported either because a pass is in flight (which
 *    outranks [Deferred]: bytes moving beats "waiting", including during a forced
 *    over-cellular drain) or because content is queued with the gate open.
 */
sealed interface SyncBannerState {

    /**
     * Drive cannot reach Control (`core-client-resubscription.md` R4).
     *
     * **Why this outranks even [Locked].** Every other state here is derived from a row Drive
     * reads *from the proxy*. While the channel is down those rows cannot arrive, so what the
     * evaluator sees is indistinguishable from "not derived yet" — and it therefore showed **no
     * banner at all** while the file list rendered empty. The app was asserting the user has no
     * files at a moment when it could not ask. "Locked" is a claim about the proxy's state, and
     * making it requires having heard from the proxy.
     *
     * Recovery is automatic and needs no action from the user — the transport rebinds and the
     * subscriptions retry (R1/R1b) — so this state carries no button. It exists to stop the UI
     * from lying while that happens.
     */
    data object Disconnected : SyncBannerState

    /**
     * Control's realm is locked on this device: the proxy holds no derived keys, so nothing
     * syncs and no file content can be read.
     *
     * **Why this outranks [LocalOnly].** Locking nulls `SyncManagerImpl.currentOwnerId`, which
     * makes the entitlement unreadable, which collapses the storage figures to
     * `hasStorage = false` — the *exact* input [LocalOnly] keys on. Before this state existed a
     * sealed realm therefore rendered as "Local only — set up storage", telling the user to buy
     * storage they already have when the real (and only) fix is to unlock Control.
     */
    data object Locked : SyncBannerState

    /**
     * This device's schema is **behind the realm's epoch** for at least one service, so
     * the proxy has paused pulling rather than garble blocks it cannot parse
     * (`core-schema-evolution.md` §7.3, the pull-fenced/push-allowed contract). Local
     * reads and pushes keep working — the device is read-only-stale, not bricked — and
     * the only fix is updating this device's apps.
     *
     * **Why this outranks [LocalOnly] and everything below it.** Every state below
     * describes the *outbound* side (nothing reaching the relay); this one says the
     * *inbound* side is deliberately stopped, which is why the file list can silently
     * disagree with the user's other devices. It is also the one condition where a
     * plausible-looking "Syncing…" would be an outright lie: pushes really are
     * draining while pulls are fenced. Only [Locked] outranks it, because an unlock is
     * a prerequisite for anything at all.
     *
     * [serviceIds] carries the offending services for diagnostics; see
     * [SchemaFenceModel] for why they are not shown to the user in v1.
     */
    data class NeedsUpdate(val serviceIds: List<String>) : SyncBannerState

    /**
     * **Skew B, app ↔ proxy** (`core-schema-skew-surface.md` SK-1): the proxy answered and
     * *refused* — this app's session was rejected because the two sides disagree on a schema
     * version — so nothing loads at all.
     *
     * ⚠⚠ **The state Drive never had, and its absence was silent.** `wireSchemaAnnounce`'s verdict
     * was computed and discarded, so a refused session left `proxyConnected` true, no storage
     * figures and no health row — and [SyncBanner.evaluate] fell through both `null` guards to *no
     * banner at all*. An empty file list and nothing to explain it, in the app whose whole job is
     * showing you your files.
     *
     * ⚠⚠ **The app to name is frequently not Drive.** On Android and desktop one Control-owned
     * proxy serves every app, so [UpdateTarget.CONTROL] is the common answer; telling the user to
     * update Drive would leave them stuck after doing exactly what was asked.
     *
     * **Why it outranks everything below.** It is the same argument [NeedsUpdate] makes, one step
     * stronger: a fenced device still reads locally and still pushes, while a refused session reads
     * nothing. Every figure below it is therefore absent or stale, and any state derived from them
     * is a guess presented as fact.
     */
    data class NeedsAppUpdate(val update: UpdateTarget) : SyncBannerState

    /** No storage subscription — files are local-only (not backed up / synced). */
    data object LocalOnly : SyncBannerState

    /**
     * Storage is bound but **full** — the realm is at or over its limit, so writes are gated before
     * anything is attempted.
     *
     * ⚠⚠ **This is the quiet twin of [NeedsAttention], and its absence was a real bug.** Both mean
     * "your storage is the reason nothing is happening", but they are reached from opposite ends:
     * [NeedsAttention] fires when the *relay rejects* a write (402), while this fires client-side,
     * from `StorageStatusModel.isFull`, *before we ever try*. Only the noisier path had a banner, so
     * a device sitting over quota showed nothing at all. Measured 2026-08-16: 10.88 GiB used against
     * a 5 GiB free plan, auto-backup correctly gated to `Paused(OUT_OF_SPACE)`, and Drive's Home
     * screen said not one word — the whole reason the feature was reported as broken.
     *
     * ⚠ Carries the **figures**, not just the fact. "Storage full" is indistinguishable from a bug
     * when the user believes they have space; "10.9 GB of 5 GB used" is checkable. Both come off
     * [StorageStatusModel], which the banner already reads.
     *
     * ⚠ Nothing is being deleted, and the UI should say so: `retention-quota-model.md` P3
     * reclamation is code-complete but **INERT** (both flags off, counsel outstanding). "Am I losing
     * my photos" is the actual anxiety here, and it is the one question the banner can answer
     * outright.
     */
    data class StorageFull(val usedBytes: Long, val limitBytes: Long) : SyncBannerState

    /**
     * The relay is reachable but rejecting this device's writes (`402`, entitlement enforcement):
     * storage "needs attention on this device" (Track A, `enforcement-followup-tracks.md`). A
     * distinct, actionable state instead of an indefinite "Syncing…" — the device is entitled at the
     * account level but this device can't obtain a write credential yet.
     */
    data object NeedsAttention : SyncBannerState

    /**
     * The relay does not recognise this device (`401`): it holds no registration for it — a restore
     * from a backup predating this device's admission, an operator data wipe, a different relay
     * host — or this device was revoked from another of the user's devices. Nothing syncs, in
     * either direction, until it is re-admitted.
     *
     * **Why this is not [Offline].** The relay answered; it is reachable. "Offline" points the user
     * at their network and implies the queue will drain once signal returns, when in fact it never
     * will. Measured against the live relay on 2026-08-17: a device in this state emitted ~8k
     * rejected requests/hour for five hours while no UI anywhere said a word.
     *
     * **Why it outranks [NeedsAttention] and [LocalOnly].** An unrecognised device's writes are
     * refused before entitlement is consulted, so a 402-shaped message would name a consequence
     * rather than the cause; and the storage figures it would otherwise be read against cannot be
     * trusted to have arrived from a relay that is rejecting this device's reads too.
     *
     * A device holding the master identity repairs this itself within a backoff window
     * (`SyncScheduler.recoverRegistration`), so what the user actually sees here is the joiner case
     * — where the only fix is pairing this device again, which is the action the banner offers.
     */
    data object DeviceUnrecognized : SyncBannerState

    /** Storage bound but the relay is unreachable — changes are queued locally. */
    data object Offline : SyncBannerState

    /**
     * File content is queued to upload and the gate is open, so it is going out now.
     *
     * ⚠ **Files, not blocks.** This used to key on `SyncHealthStatusModel.pendingBlockCount` — the
     * realm-wide metadata queue, which every app writes to — so it was effectively always on and
     * told the user nothing they could act on or verify. It now derives from the same content
     * signal [Deferred] does, differing only in *why* the queue is not moving: [Deferred] means a
     * policy is holding it, this means it is draining.
     *
     * Consequently **no banner once again means nothing is happening**, which is the property that
     * makes the banner worth reading at all.
     *
     * [completed] of [total] is the pass's progress and [bytesLeft] what is still to transfer, so
     * the banner can say *"Uploading 3 of 12 · 45 MB left"* (P2). ⚠ [bytesLeft] is composed from
     * two loops during a backup — see [remainingBytesFor], and do not reduce it back to the
     * proxy's `pendingBytes`, which is the file in flight rather than the backup. ⚠ [total] is the pass's fixed
     * denominator, **not** the live queue size: the queue counts down as files finish, so using it
     * would render "3 of 9", "4 of 8" — a progress indicator whose denominator chases its numerator.
     * `total == 0` means the proxy has not reported a pass yet (an older Control, or the tick
     * boundary between passes); the renderer falls back to the queue size for the denominator, so
     * the banner degrades to a plain count rather than to "3 of 0".
     *
     * ⚠⚠ **While an auto-backup drain is running, both figures come from the drain instead**
     * ([BackupProgress]). The pass denominator is only fixed *within* a pass: it is a snapshot of
     * the upload queue taken when the pass begins, and the backup importer feeds that queue far
     * faster than the relay drains it, so each new pass snapshots a bigger number and the user
     * watches "20 of 400" become "20 of 500". The drain knows the honest total up front — see
     * [BackupProgress].
     */
    data class Syncing(
        val completed: Int = 0,
        val total: Int = 0,
        val bytesLeft: Long = 0L,
        /**
         * These figures come from an auto-backup **drain**, so the banner can offer **Stop**
         * (ST-2b, `drive-upload-stop-and-reclamation.md` §3.8).
         *
         * ⚠⚠ Not "an upload is in flight" — specifically "a *backup* is in flight". A plain
         * upload the user started by hand has no pause switch and no queue to resume: offering
         * Stop there would either do nothing or mean something different from what it means five
         * minutes earlier during a backup. One word, two meanings, is how a control stops being
         * trustworthy.
         */
        val stoppableBackup: Boolean = false,
        /**
         * The relay is failing these uploads, so they wait a while before trying again
         * (`control-background-work-off-fgs.md` §5.7) — the banner says so beside the figures.
         *
         * ⚠ A reason on this state, not a state of its own: the files ARE queued to upload and will,
         * and without it *"Uploading 0 of 12"* sat unexplained for up to 15 minutes.
         */
        val relayRetrying: Boolean = false,
    ) : SyncBannerState

    /**
     * The user's own `WifiOnly` policy is holding [pendingCount] **files** back from upload on a
     * metered network — a benign "Waiting for Wi-Fi" state, NOT an error
     * (`sync-metered-deferred-status.md` §2). Metadata keeps syncing throughout, so the file list
     * stays current; only the bytes wait.
     *
     * ⚠ [pendingCount] counts **files**, from the `ContentTransferStatus` row — not the
     * `pendingBlockCount` it used to read. That was the realm-wide metadata queue (every app's
     * `Syncable` writes), so it was non-zero on any multi-app device and the banner claimed files
     * were waiting when they had all uploaded (`sync-metered-plane-split.md` §0).
     *
     * ⚠ Ranks **above** [Offline]. P1b briefly inverted this on the theory that the plane split
     * made `isOnline` trustworthy; the on-device pass (2026-08-02) disproved it — across two
     * metered windows the banner only ever read "Offline" and this state was unreachable, because
     * the health row is written by every live scheduler instance and sessions churn, so the value
     * flaps between writers. It is also the better message on a genuinely offline device: both
     * statements are true, but this one names the cause and offers an action.
     */
    data class Deferred(val pendingCount: Int, val bytesLeft: Long = 0L) : SyncBannerState

    /**
     * The Synced-Folders auto-backup is holding [pendingCount] new files back because of the
     * user's own policy — Wi-Fi only on mobile data, or charging only on battery.
     *
     * ⚠ Not [Deferred], though both can read "waiting for Wi-Fi". [Deferred] is the *proxy*
     * holding bytes it has already been handed; this is the *app* not having handed anything
     * over yet, so the proxy's content row is empty and — before this state — the file list said
     * nothing at all while a day of photos sat on the phone. It ranks below every other state:
     * each of them is either a harder stop or bytes actually moving.
     */
    data class BackupWaiting(val cause: BackupWaitCause, val pendingCount: Int) : SyncBannerState
}

/** Which of the user's two backup switches is holding the backup — see [SyncBannerState.BackupWaiting]. */
enum class BackupWaitCause { WIFI, CHARGING }

/** A paused auto-backup with something to upload, as the banner sees it. */
data class BackupWaiting(val cause: BackupWaitCause, val pendingCount: Int)

/**
 * A Synced-Folders auto-backup **run** in flight, as the banner sees it: `completed` of [total]
 * media items handed to the upload data plane since the backup engine was last idle.
 *
 * ⚠ A run, not a drain (`drive-upload-progress.md` §14.3). A burst of media events produces
 * several drains, each with its own honest snapshot, and reporting them one at a time made the
 * count restart with a fresh denominator part-way through a backup the user sees as one thing —
 * measured on device as "19 of 20" becoming "5 of 60". `SyncedFolderController` now sums the
 * drains of a run, so the numerator never goes backwards and the denominator grows only when
 * files genuinely arrive.
 *
 * **Why the banner prefers these figures over the proxy's pass figures.** The pass denominator is
 * the size of the upload queue at the moment the pass began, and during a backup there are two
 * loops on that queue: the drain imports items (local write + journalled content intent, fast) while
 * the proxy uploads them (network, slow). The importer stays ahead, so the queue *grows* under the
 * uploader and every subsequent pass snapshots a larger denominator — the reported "20 of 400
 * jumps to 500, then settles once the scan finishes". The drain, by contrast, enumerates all
 * selected buckets before it uploads anything, so its `total` is known up front and never moves.
 *
 * ⚠ [completed] counts items *handed off*, not bytes landed at the relay, so it leads the actual
 * upload — at the end of a drain the banner can read "400 of 400" with bytes still in flight.
 * That is what `bytesLeft` is for: [remainingBytes] covers what this run has not handed off yet
 * and the proxy's journal covers what it has, so the two compose into the whole remainder
 * ([remainingBytesFor]). When the drain finishes the banner falls back to the proxy's own pass
 * figures, which by then have no producer racing them.
 */
data class BackupProgress(
    val completed: Int,
    val total: Int,
    /**
     * Bytes the run has still to hand to the upload data plane, or `null` when it cannot say
     * (`drive-upload-progress.md` §14.4). Half of the banner's byte figure; the other half is the
     * proxy's `pendingBytes` — see [remainingBytesFor].
     */
    val remainingBytes: Long? = null,
)

/**
 * The banner's *"· N left"*: what is still to transfer, from **both** loops that hold some of it.
 *
 * ⚠⚠ The proxy's `pendingBytes` alone is the file in flight, not the backup. It is summed from the
 * deferred-content journal, and since journal-first (P4a) an entry is written and drained almost
 * immediately, so on a fast link the journal holds roughly whatever is uploading right now. Beside
 * an honest *"250 of 2271"* that read as a per-file size, which is how it was reported
 * (`drive-upload-progress.md` §14.3 recorded it as *"the byte figure is noise"*, measured at
 * `0b left` while 1 MB files were going out).
 *
 * The missing half is the run's own: Drive enumerated every item before uploading any of them, so
 * it knows the bytes of everything it has **not yet handed off**. The journal holds the bytes
 * handed off but **not yet uploaded**. A file leaves the first set exactly as it enters the second,
 * so the sum is the whole remainder and double-counts nothing — modulo the sub-second window of
 * the local write itself, and the scheduler tick that re-sums the journal.
 *
 * ⚠ Falls back to the journal alone when the run cannot size its items (a provider with no cheap
 * size, no run at all — an ordinary hand-picked upload). That is the pre-§14.4 figure exactly: a
 * known-imperfect number rather than a new wrong one.
 */
internal fun remainingBytesFor(backup: BackupProgress?, content: ContentTransferModel?): Long {
    val journal = content?.pendingBytes ?: 0L
    return backup?.remainingBytes?.let { it + journal } ?: journal
}

object SyncBanner {

    /**
     * Pure decision. `null` [storage] (not yet derived) ⇒ no banner — optimistic,
     * mirroring [StorageGate]'s unknown handling, so the banner never flashes
     * before the storage signal arrives. Likewise a `null` [health] leaves the
     * transient states off until the proxy has reported.
     *
     * The transfer states are driven by the **content** signal (files queued to upload), NOT by
     * [SyncHealthStatusModel.pendingBlockCount] and NOT by the raw
     * [SyncHealthStatusModel.isSyncing] flag. `isSyncing` flips true for the brief moment of each
     * hot tick and would make the banner flicker (`drive-connectivity-service.md` §6 Q3);
     * `pendingBlockCount` is the realm-wide *metadata* queue, which is never idle on a multi-app
     * device and is not something a user can act on (`drive-upload-progress.md` §1). Both fields
     * are retained on the model — `isSyncing` for a future inline spinner, `pendingBlockCount` for
     * diagnostics — but neither reaches the banner.
     */
    fun evaluate(
        storage: StorageStatusModel?,
        health: SyncHealthStatusModel?,
        fence: SchemaFenceModel? = null,
        content: ContentTransferModel? = null,
        /**
         * A client-orchestrated folder upload is on screen (`drive-upload-progress.md` P6).
         * Drive's own `FolderUploadStatusBar` already reports that operation per file, with the
         * real batch denominator plus conflict, pre-flight and cancel — so the banner must not
         * narrate the same upload beside it in different numbers.
         *
         * ⚠ Suppresses only the *transfer* state. Everything else still shows, and [Deferred] in
         * particular is **complementary rather than redundant**: on a metered network the folder
         * bar runs to "12 of 12" (its walk finished — metadata written, content journalled) while
         * not one byte has left the device. Hiding the reason for that would be the worst outcome
         * of the whole track.
         */
        folderUploadActive: Boolean = false,
        /**
         * Whether Drive can reach Control at all (`core-client-resubscription.md` R4). Defaults to
         * `true` so existing callers and tests are unaffected — and because it is the honest
         * answer wherever client and proxy share a process.
         */
        proxyConnected: Boolean = true,
        /**
         * The auto-backup drain currently running, if any — its figures replace the proxy's pass
         * figures in [SyncBannerState.Syncing] for as long as it runs. See [BackupProgress] for why
         * the queue-derived denominator is the wrong one to show during a backup.
         *
         * ⚠ **Substitutes the numbers, not the priority.** Unlike `folderUploadActive` (which
         * stands the transfer state *down* because Drive's own bar is on screen saying the same
         * thing), a backup drain has no on-screen bar in the file list — suppressing here would
         * trade a jumping denominator for no feedback at all. So [Deferred], [Offline] and
         * everything above them still outrank a running backup exactly as before: when the metered
         * gate is holding the bytes, "waiting for Wi-Fi" remains the truthful message even though
         * the drain is busy importing.
         */
        backupProgress: BackupProgress? = null,
        /**
         * The announce refusal, when there is one (`core-schema-skew-surface.md` SK-1b, ADR 0025).
         *
         * ⚠ Defaults to `null`, which is the pre-SK-1 behaviour exactly — every existing caller and
         * test is unaffected, and a wiring site that has not been updated simply never shows the
         * state rather than showing a wrong one.
         *
         * ⚠⚠ Deliberately **not** wired to the reachability engine's `Transport` or `Fenced`: Drive
         * already derives both from its own inputs ([proxyConnected] and [fence]), and two paths to
         * one banner state would be two sources of truth for one fact.
         */
        skew: UnreachableReason.Skew? = null,
        /**
         * The auto-backup's own policy pause, when it is holding files back
         * ([SyncBannerState.BackupWaiting]). Defaults to `null` — the pre-existing behaviour.
         */
        backupWaiting: BackupWaiting? = null,
    ): SyncBannerState? = when {
        // Before everything, including [Locked]: with the channel down, every input below is
        // stale or absent, so any state derived from them is a guess presented as fact.
        !proxyConnected -> SyncBannerState.Disconnected
        // Directly below [Disconnected] and above everything else, for the same reason it is: a
        // refused session answers no read, so every input below is absent or stale. ⚠ Above
        // [Locked] too — an unlock cannot help a session the proxy will not admit.
        skew != null -> SyncBannerState.NeedsAppUpdate(skew.update)
        // Checked first among the proxy-derived states, and deliberately *before* the
        // `storage == null` guard: a locked realm
        // is the one state that explains away every other signal (the zeroed storage figures
        // included), and it is the only one the user can act on. See [SyncBannerState.Locked].
        health?.locked == true -> SyncBannerState.Locked
        // Also *before* the storage guard, and for the same reason: the fence verdict is
        // derived from the realm epoch alone, so it is valid (and the most useful thing to
        // say) even on a device whose storage figures have not arrived — or never will,
        // because it has no subscription. A fenced device is not pulling, so any state
        // below that implies "up to date apart from X" would be misleading.
        // Also before the storage guard, and before the fence: a relay that refuses this device's
        // identity is serving it nothing at all, so neither the storage figures nor an epoch-derived
        // fence verdict can be fresher than the moment the 401s started — and unlike either of
        // those, this one names something the user can act on. See [SyncBannerState.DeviceUnrecognized].
        health?.deviceUnrecognized == true -> SyncBannerState.DeviceUnrecognized
        fence?.fenced == true -> SyncBannerState.NeedsUpdate(fence.serviceIds)
        storage == null -> null
        !storage.hasStorage -> SyncBannerState.LocalOnly
        // After [LocalOnly] — "no subscription" is the more fundamental fact, and a device with no
        // storage cannot be *full* of it — but deliberately BEFORE the `health == null` guard and
        // before [NeedsAttention], for two different reasons.
        //
        // Before `health == null`: fullness is derived from the storage figures alone, so it is
        // valid (and the most useful thing to say) on a device whose proxy has not reported yet —
        // the same reasoning the fence verdict above is placed on.
        //
        // ⚠ Before [NeedsAttention]: when the realm is full the relay will also start refusing
        // writes, so both are true at once and they are the SAME condition seen from two ends. The
        // one that names the cause with checkable numbers beats the one that says "needs attention".
        // ⚠ `isFull` now answers "can bytes land", from the figures OR the relay's own `507`
        // refusal — deliberately one predicate rather than an extra `health.storageRejected` check
        // here, so the banner, `evaluateBackupGate` and `StorageGate` cannot drift apart on what
        // "full" means. See [StorageStatusModel.isFull] for the two cases the figures miss.
        storage.isFull -> SyncBannerState.StorageFull(
            usedBytes = storage.usedBytes,
            limitBytes = storage.limitBytes,
        )
        health == null -> null
        // NeedsAttention outranks Offline/Syncing: the relay is reachable but blocking this
        // device's writes, so "needs attention" is the actionable truth, not "Syncing…" (Track A).
        health.needsAttention -> SyncBannerState.NeedsAttention
        // Deferred outranks Offline. ⚠ P1b flipped this the other way and the on-device pass
        // (2026-08-02) showed the flip was wrong: in BOTH metered windows the banner read
        // "Offline" and the deferred state — the whole point of the slice — was never reachable.
        // `isOnline` is not dependable enough to gate it on: the row is written by every live
        // scheduler instance, and sessions churn, so the value flaps between writers.
        //
        // It is also the better message even when `isOnline` is right. Both statements are true
        // of an offline device with queued uploads, but "waiting for Wi-Fi. N files" names the
        // cause, is specific to what the user is looking at, and carries an action; "Offline"
        // names a symptom and carries none.
        //
        // Deferred keys on the CONTENT signal, not `health.pendingBlockCount` — see
        // [SyncBannerState.Deferred]. `health.deferred` is the proxy's own verdict (gate closed
        // AND files waiting); the count comes from the content row alongside it.
        // A pass in flight outranks "waiting": during a forced "Upload over cellular" drain the
        // metered gate is still closed, so `health.deferred` stays true while bytes are actually
        // moving — the banner would say "waiting for Wi-Fi" about an upload the user just started.
        // If something is transferring, say so, whatever the gate thinks.
        // Drive's folder-upload bar owns the story while it is up — see [folderUploadActive].
        folderUploadActive && health.deferred -> SyncBannerState.Deferred(
            pendingCount = content?.pendingFileCount ?: 0,
            bytesLeft = content?.pendingBytes ?: 0L,
        )
        folderUploadActive -> null
        (content?.passTotal ?: 0) > 0 -> SyncBannerState.Syncing(
            completed = backupProgress?.completed ?: content?.passCompleted ?: 0,
            total = backupProgress?.total ?: content?.passTotal ?: 0,
            bytesLeft = remainingBytesFor(backupProgress, content),
            stoppableBackup = backupProgress != null,
            relayRetrying = health.relayRetrying,
        )
        health.deferred -> SyncBannerState.Deferred(
            pendingCount = content?.pendingFileCount ?: 0,
            bytesLeft = content?.pendingBytes ?: 0L,
        )
        !health.isOnline -> SyncBannerState.Offline
        // ⚠ Keys on FILES queued for upload, not `health.pendingBlockCount`. That field is the
        // realm-wide metadata queue — every app's `Syncable` writes — so on a multi-app device it
        // is approximately never zero and the banner sat there permanently, reporting plumbing the
        // user has no stake in (`drive-upload-progress.md` §1). It is the same category error the
        // plane split already corrected for [SyncBannerState.Deferred]; this is its other half.
        //
        // Metadata sync is now deliberately invisible: it is kilobytes, it is never gated, and
        // there is nothing for the user to do about it. The banner speaks only about file
        // transfers, so "no banner" once again means "nothing is happening".
        (content?.pendingFileCount ?: 0) > 0 -> SyncBannerState.Syncing(
            completed = backupProgress?.completed ?: content?.passCompleted ?: 0,
            // Fall back to the queue size when no pass has been reported (older Control, or between
            // passes) so the banner degrades to a plain count instead of "3 of 0". ⚠ This is the
            // *live* queue, so it is exactly the figure that grows under a running backup — hence
            // the drain's own total taking precedence over both.
            total = backupProgress?.total
                ?: (content?.passTotal ?: 0).takeIf { it > 0 }
                ?: (content?.pendingFileCount ?: 0),
            bytesLeft = remainingBytesFor(backupProgress, content),
            stoppableBackup = backupProgress != null,
            relayRetrying = health.relayRetrying,
        )
        // A drain whose queue is momentarily empty — the uploader is keeping up with the importer.
        // Kept up rather than blinked off: the run *is* in progress, its figures are honest, and a
        // banner that flickers away between files is the same "no banner means nothing is
        // happening" property inverted (`drive-upload-progress.md` §1).
        backupProgress != null -> SyncBannerState.Syncing(
            completed = backupProgress.completed,
            total = backupProgress.total,
            bytesLeft = remainingBytesFor(backupProgress, content),
            stoppableBackup = true,
            relayRetrying = health.relayRetrying,
        )
        // Last, and only with something to say: a paused backup with nothing new is not news.
        backupWaiting != null && backupWaiting.pendingCount > 0 ->
            SyncBannerState.BackupWaiting(backupWaiting.cause, backupWaiting.pendingCount)
        else -> null
    }
}
