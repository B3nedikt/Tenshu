package org.tenshu.sync.proxy

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Plain proxy-side figures the [SyncScheduler] reports at tick boundaries, from
 * which [initSyncHealth] derives the single-row `SyncHealthStatus` relation
 * (`docs/proposals/drive-connectivity-service.md`).
 *
 * `online` starts optimistically `true` so the Drive banner never flashes
 * "Offline" before the first relay attempt — it flips only after a *confirmed*
 * relay failure (§6 Q2: relay reachability, not link state).
 */
data class SyncHealthFigures(
    val online: Boolean = true,
    val isSyncing: Boolean = false,
    val pendingBlockCount: Int = 0,
    val lastSyncedAtMillis: Long = 0L,
    /**
     * The relay is rejecting this device's writes with `402` (entitlement enforcement) — the
     * "needs attention on this device" case (Track A, `enforcement-followup-tracks.md`). Distinct
     * from [online]: the relay is reachable (pulls succeed), but writes are blocked until the device
     * obtains a credential. Surfaces as [org.tenshu.sync.SyncHealthStatus.CONNECTIVITY_NEEDS_ATTENTION]
     * so Drive renders a distinct banner instead of an indefinite "Syncing…".
     */
    val entitlementBlocked: Boolean = false,
    /**
     * The relay is rejecting this device's calls with `401` — it does not accept this device's
     * identity, most often because it holds no registration for it (a restore from a backup
     * predating this device's admission, an operator data wipe, a different relay host, or the
     * device having been revoked from another device).
     *
     * Distinct from [online]: the relay answered, so it is reachable — reporting this as offline
     * points the user at their network and hides the one thing they can act on. Distinct from
     * [entitlementBlocked] too, and outranks it: an unrecognised device's writes are refused before
     * entitlement is ever consulted. Surfaces as
     * [org.tenshu.sync.SyncHealthStatus.CONNECTIVITY_DEVICE_UNRECOGNIZED].
     *
     * A device holding the master identity repairs this itself (`SyncScheduler.recoverRegistration`)
     * and the flag clears within a backoff window; it therefore *persists* mainly on a pairing
     * joiner, which structurally cannot re-admit itself and needs re-pairing.
     */
    val deviceUnrecognized: Boolean = false,
    /**
     * The relay refused this device's file **content** with `507` — the owner is at their storage
     * quota. Surfaces as [org.tenshu.sync.SyncHealthStatus.CONNECTIVITY_STORAGE_FULL].
     *
     * ⚠⚠ **This is the relay-side twin of `StorageStatusModel.isFull`, and it exists because the
     * client-side test can be false while the relay refuses everything.** `isFull` asks
     * `remainingBytes <= 0`; a realm with a *sliver* free — a few hundred bytes against a 5 GiB
     * tier — passes that test while rejecting every 2 MiB chunk. The client-side figure also lags:
     * usage re-fetches on a 60 s backstop, so bytes consumed by **another of the owner's devices**
     * are invisible to it for up to that long. The relay is the only
     * authority on whether a write fits, so its refusal has to be a first-class signal rather than
     * something inferred from figures that can disagree with it.
     *
     * ⚠ Distinct from [entitlementBlocked]: a `402` means *this device* lacks a credential and is
     * fixed on this device; a `507` means the *account* is out of room and is fixed by freeing
     * bytes or upgrading, from anywhere. Metadata sync is unaffected by either.
     */
    val storageRejected: Boolean = false,
    /**
     * File **content** is withheld by the user's `WifiOnly` policy on a metered network while
     * uploads wait — a benign, self-inflicted *deferral*, surfaced as
     * [org.tenshu.sync.SyncHealthStatus.CONNECTIVITY_DEFERRED] so Drive shows "Waiting for
     * Wi-Fi" rather than an error (`sync-metered-deferred-status.md` §2).
     *
     * ⚠ Keyed on [deferredContentCount], **not** [pendingBlockCount] — the metadata queue is
     * never metered-gated (`sync-metered-plane-split.md`), so it cannot be
     * deferred by this policy, and it counts every app's writes rather than files.
     */
    val deferred: Boolean = false,
    /**
     * The content plane is waiting out refused writes — several content `PUT`s in a row answered
     * `5xx`/`429` or failed in transport — with files queued behind it
     * (`control-background-work-off-fgs.md` §5.7). Surfaced as
     * [org.tenshu.sync.SyncHealthStatus.CONNECTIVITY_RELAY_RETRYING], so a banner reading
     * *"Uploading 0 of 12"* says why it is not moving.
     */
    val contentRetrying: Boolean = false,
    /**
     * Files the metered content gate is holding (`sync-metered-plane-split.md` §8) — the banner's
     * "N", carried to the `ContentTransferStatus` row by [initContentTransferStatus].
     */
    val deferredContentCount: Int = 0,
    /** Total bytes those files will transfer; best-effort, see `ContentTransferStatus.pendingBytes`. */
    val deferredContentBytes: Long = 0L,
    /**
     * Files in the upload pass currently running, `0` when none is — the denominator of
     * *"Uploading 3 of 12"* (`drive-upload-progress.md` P2). Fixed for the duration of a pass, so
     * it cannot shrink under the user the way [deferredContentCount] does.
     */
    val uploadPassTotal: Int = 0,
    /** Files resolved so far in the current pass; `0` when none is running. */
    val uploadPassCompleted: Int = 0,
    /**
     * The realm is sealed on this device (`SyncManager.getUnlockStatus().isLocked`): no derived
     * keys, so the sync loop cannot run and `currentOwnerId` is null — which collapses the storage
     * figures to `hasStorage = false`. Surfaced as
     * [org.tenshu.sync.SyncHealthStatus.CONNECTIVITY_LOCKED] so Drive says "unlock" instead of
     * mis-reading the absent entitlement as "no subscription" (see that constant's KDoc).
     */
    val locked: Boolean = false,
    /**
     * **Positive evidence that the relay answered recently** — `null` until a scheduler tick has
     * reported anything at all.
     *
     * ⚠⚠ Deliberately NOT [online], and the difference is the whole point. [online] is a *banner*
     * verdict: optimistic before the first attempt, and smoothed across
     * [RelayReachability.CONFIRM_MS] so one bad tick never flickers the UI. A *fence* that reads it
     * therefore keeps saying "online" for a full 20 seconds after the link actually dropped —
     * exactly the window in which a write reaches nobody. `RelayReachability`'s own KDoc already
     * warns that callers needing the real transition must not use the smoothed verdict; a safety
     * fence is such a caller.
     *
     * The three states are distinct and none collapses safely into another:
     *  - `true`  — a relay call succeeded inside [RelayReachability.EVIDENCE_TTL_MS];
     *  - `false` — the scheduler is running and the last attempt failed, or its evidence expired;
     *  - `null`  — no tick has reported yet (no scheduler in this process, or it hasn't ticked).
     *
     * `null` must not be read as "offline": a process that legitimately runs no scheduler would
     * then fence *every* risky move forever. Callers fall back to [online] there.
     */
    val relayConfirmedReachable: Boolean? = null,
    /**
     * Whether this realm syncs through a relay at all — `null` until a tick reports.
     *
     * ⚠⚠ **Not the same question as [relayConfirmedReachable], and conflating them breaks
     * local-only.** A never-funded realm is never registered on the relay, so `SyncScheduler`
     * makes no relay request whatsoever: no attempt is ever recorded, reachability stays unknown
     * for the life of the process, and anything reading unknown-as-offline fences for ever.
     *
     * The move fence uses it to stay **inert** there rather than pessimistic. That is not a
     * weakening: D3 exists because a *peer* can delete the old holder while the new one is
     * unobservable, and an unregistered realm has no peers to do it.
     */
    val relaySyncConfigured: Boolean? = null,
)

/**
 * The relay-reachability predicate a **safety fence** should use — as opposed to
 * [SyncHealthFigures.online], which exists to drive a banner.
 *
 * One function rather than three inlined lambdas because the three platform modules wiring the move
 * fence have no way to notice each other drifting: a fence that silently reverts to the optimistic
 * signal on one platform loses bytes only on that platform, only when offline, and reports nothing.
 *
 * ⚠⚠ Unknown (`null`) **fences**, and must not fall back to [SyncHealthFigures.online]. An earlier
 * revision did fall back, reasoning that a process running no scheduler would otherwise defer every
 * risky move for ever. But `online` defaults to `true` optimistically, so that fallback reported a
 * device as safe during the whole startup window — including a device that started with no network
 * at all — and a move made there bypassed the fence and was never replayable. Every runtime that
 * wires `isOffline` also runs `initSyncService`, and so has a scheduler that will report within a
 * tick; the fallback protected a case that does not arise and opened one that does.
 */
fun SyncHealthFigures.relayUnreachableForFencing(): Boolean {
    // Sync not configured ⇒ the fence is inert, not pessimistic. No relay, no peers, no hazard —
    // and fencing here would defer every content-bearing move in local-only mode for ever.
    if (relaySyncConfigured == false) return false
    return relayConfirmedReachable != true
}

/**
 * Mutable holder the [SyncScheduler] writes to and [initSyncHealth] observes —
 * the sync-domain analogue of `StorageCapacityHolder`, but *written by the
 * scheduler* (a `MutableStateFlow` so the derivation collects changes) rather
 * than fed by an external provider flow. Single proxy writer; reads are cheap
 * StateFlow snapshots.
 *
 * Lives in `:sync-service-proxy` (the writer's module). It carries no
 * control-shared types, so the dependency direction stays clean (control-shared
 * depends on sync-service-proxy, never the reverse).
 */
class SyncHealthHolder {

    private val figures = MutableStateFlow(SyncHealthFigures())

    fun observe(): Flow<SyncHealthFigures> = figures

    /** Current snapshot — for tests / synchronous reads. */
    fun current(): SyncHealthFigures = figures.value

    /**
     * Seed the sealed/unsealed state *before* the first scheduler tick.
     *
     * The `SyncHealthStatus` row is **persisted**, so on a cold start it still holds whatever the
     * previous process last wrote — e.g. `connectivity = online` from a session that was unlocked.
     * The storage figures have no such lag: `initEntitlementStorageReader` seeds them synchronously,
     * so a sealed cold start reports `hasStorage = false` immediately. Without this seed the two
     * disagree for the whole gap until the first tick, and Drive reads "subscribed? no; locked? no"
     * — i.e. it renders the misleading "Local only — set up storage" precisely during startup, which
     * is when a sealed realm is most likely to be observed (an OEM process kill re-seals constantly;
     * ColorOS does this to Control routinely).
     */
    fun seedLocked(locked: Boolean) {
        figures.update { it.copy(locked = locked) }
    }

    /**
     * Publish mid-pass upload progress (`drive-upload-progress.md` P2). Separate from
     * [onTickComplete] because a pass spans many files *within* one tick — the tick boundary is far
     * too coarse for a progress bar. `total == 0` ends the pass.
     */
    fun onUploadProgress(completed: Int, total: Int) {
        figures.update { it.copy(uploadPassCompleted = completed, uploadPassTotal = total) }
    }

    /** A tick has begun: a drain/pull is now in flight. */
    fun markSyncing() {
        figures.update { it.copy(isSyncing = true) }
    }

    /**
     * A tick has finished. [online] is `null` when no relay call was attempted
     * this tick (pre-pairing / nothing to do) — connectivity then keeps its
     * last-known value rather than being reset. [lastSyncedAtMillis] is likewise
     * only advanced (never cleared) on a successful pull.
     */
    fun onTickComplete(
        online: Boolean?,
        pendingBlockCount: Int,
        lastSyncedAtMillis: Long?,
        entitlementBlocked: Boolean = false,
        deviceUnrecognized: Boolean = false,
        storageRejected: Boolean = false,
        deferred: Boolean = false,
        contentRetrying: Boolean = false,
        locked: Boolean = false,
        deferredContentCount: Int = 0,
        deferredContentBytes: Long = 0L,
        relayConfirmedReachable: Boolean = false,
        relaySyncConfigured: Boolean = false,
    ) {
        figures.update {
            it.copy(
                online = online ?: it.online,
                isSyncing = false,
                pendingBlockCount = pendingBlockCount,
                lastSyncedAtMillis = lastSyncedAtMillis ?: it.lastSyncedAtMillis,
                entitlementBlocked = entitlementBlocked,
                deviceUnrecognized = deviceUnrecognized,
                storageRejected = storageRejected,
                deferred = deferred,
                contentRetrying = contentRetrying,
                locked = locked,
                deferredContentCount = deferredContentCount,
                deferredContentBytes = deferredContentBytes,
                // Overwritten every tick, never carried forward the way `online` is: this figure's
                // meaning is "recently", so a stale value is a wrong value.
                relayConfirmedReachable = relayConfirmedReachable,
                relaySyncConfigured = relaySyncConfigured,
                // A tick boundary means no pass is mid-flight: the drain runs to completion inside
                // drainAndPull, so leaving stale pass figures here would strand the banner at
                // "3 of 12" forever once the queue emptied.
                uploadPassTotal = 0,
                uploadPassCompleted = 0,
            )
        }
    }

    /**
     * The schema pull fence's aggregate verdict (`SchemaPullFence`), as the sorted fenced service
     * ids: `null` until the fence has been evaluated in this process, empty when it is clear.
     *
     * ⚠ Kept **off** [SyncHealthFigures] on purpose. Every figures change re-derives the
     * `SyncHealthStatus` row and the coarse device status, and neither carries the fence, so a
     * fence change there would only churn them. This is the in-process read for Control's own
     * surfaces (the desktop tray, the OS notification and the desktop log) that need it without a
     * relation round trip; clients read the persisted `SchemaFenceStatus` row instead.
     *
     * ⚠ `null` is "not yet known", never "fine" (`core-schema-skew-surface.md` §7 Q3): it is what
     * a cold process reports before its first tick, and a locked realm never ticks at all.
     */
    private val schemaFence = MutableStateFlow<List<String>?>(null)

    fun observeSchemaFence(): StateFlow<List<String>?> = schemaFence

    /** Written by the fence on a verdict change only, so this never ticks at the poll cadence. */
    fun onSchemaFence(fencedServiceIds: List<String>) {
        schemaFence.value = fencedServiceIds
    }
}

/**
 * The move fence's acknowledged round-trip ([org.tenshu.sync.common.SyncRelay.probeWrite]), shared by all three platform modules for the reason
 * [relayUnreachableForFencing] is: three inlined copies have no way to notice each other drifting.
 *
 * `true` a push would land now; `false` it would not; `null` could not ask (a relay that predates the
 * probe) — the fence then keeps its cached verdict rather than deferring every move.
 */
suspend fun org.tenshu.sync.common.SyncRelay.confirmWritableForFencing(health: SyncHealthFigures): Boolean? {
    // ⚠⚠ No relay configured ⇒ nothing to ask and nothing to fence. A never-funded
    // local-only realm is never registered, so the probe would be refused and every content-bearing
    // move deferred — with no peer that could delete the old holder. The same configured-vs-reachable
    // distinction [relayUnreachableForFencing] keeps. `null` (no tick yet) still asks.
    if (health.relaySyncConfigured == false) return null
    return when (probeWrite()) {
        org.tenshu.sync.common.WriteProbe.ACCEPTED -> true
        org.tenshu.sync.common.WriteProbe.REFUSED -> false
        org.tenshu.sync.common.WriteProbe.UNSUPPORTED -> null
    }
}
