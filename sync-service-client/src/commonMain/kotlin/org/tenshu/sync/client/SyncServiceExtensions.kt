package org.tenshu.sync.client

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import org.tenshu.core.collection.list.flow
import org.tenshu.core.collection.list.getAll
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TenshuClient
import org.tenshu.core.getOrNull
import org.tenshu.sync.ContentTransferStatus
import org.tenshu.sync.ContentUploadProgress
import org.tenshu.sync.RuntimeIdentity
import org.tenshu.sync.SchemaFenceStatus
import org.tenshu.sync.SyncCatchUpHeartbeat
import org.tenshu.sync.SyncHealthStatus
import org.tenshu.sync.SyncService
import org.tenshu.sync.common.CatchUpHeartbeatTiming
import org.tenshu.sync.common.CatchUpBeat
import org.tenshu.sync.common.catchUpHeartbeatLiveness
import org.tenshu.sync.common.wallClockMillis

val TenshuClient.syncService: SyncService
    get() = getService(SyncService.ID)

/**
 * Reactive sync-health read (`docs/proposals/drive-connectivity-service.md` §2):
 * the single [SyncHealthStatus] projected from the single-row
 * [SyncService.syncHealth] relation. `null` until the proxy has derived a status.
 * Drive subscribes to this to render the transient banner states (Offline /
 * Syncing). Mirrors `StorageService.status()`.
 */
fun SyncService.syncHealth(): Flow<SyncHealthStatus?> =
    syncHealthStatuses.flow().map { result ->
        result.getOrNull()?.toList()?.firstOrNull()
    }

/**
 * Just [SyncHealthStatus.connectivity] from [syncHealth], `null` until known — what every app's sync
 * strip reads (`suite-sync-status.md` SS-1, `app.tenshu.reachability.evaluateSuiteSyncProblem`).
 */
fun SyncService.syncConnectivity(): Flow<String?> =
    // Distinct: the row is rewritten whenever its other figures move.
    syncHealth().map { it?.connectivity?.get() }.distinctUntilChanged()

/**
 * Reactive schema pull-fence read (`core-schema-evolution.md` §7.3): the
 * single [SchemaFenceStatus] projected from the single-row
 * [SyncService.schemaFenceStatuses] relation. `null` until the proxy has evaluated
 * the fence (its first sync tick after unlock).
 *
 * Read by every app's "update to keep syncing" banner and by Control's own home
 * banner (through `TenshuSyncRepository.schemaFence`). Control's tray, OS
 * notification and desktop log read the in-process `SyncHealthHolder` instead.
 */
fun SyncService.schemaFence(): Flow<SchemaFenceStatus?> =
    schemaFenceStatuses.flow().map { result ->
        result.getOrNull()?.toList()?.firstOrNull()
    }

/**
 * Reactive read of the [RuntimeIdentity] of the binary hosting this proxy, for a client that
 * does **not** know who hosts it — an app talking to Control over IPC on Android and desktop
 * (`core-schema-skew-surface.md` SK-2). Ask [RuntimeIdentity.isControlHosted] of the result.
 *
 * `null` means *"unknown"*, never *"it is me"*. It is returned when no host declared itself, and
 * also when the store holds **more than one** writer's row: that is a shared iOS replica, where
 * any one row is as likely to be a sibling's as this runtime's. A runtime that knows its
 * own host id reads with the `hostAppId` overload instead.
 */
fun SyncService.runtimeIdentity(): Flow<RuntimeIdentity?> =
    runtimeIdentities.flow().map { result ->
        result.getOrNull()?.toList()?.singleOrNull()
    }

/**
 * The [RuntimeIdentity] row written by the runtime hosted by [hostAppId] — how an iOS route-1 app
 * reads its **own** runtime's identity over a replica that several apps write to.
 *
 * ⚠⚠ Pass the same id the runtime was installed under (`named("hostAppId")`, the route-1
 * `clientId`), exactly as [catchUp] takes it. A second spelling reads nobody's row and reports
 * `null` — the honest unknown, not a wrong app.
 */
fun SyncService.runtimeIdentity(hostAppId: String): Flow<RuntimeIdentity?> =
    runtimeIdentities.flow().map { result ->
        result.getOrNull()?.toList()?.firstOrNull { it.key.get() == hostAppId }
    }

/**
 * Reactive deferred-content read (`sync-metered-plane-split.md` §8): the single
 * [ContentTransferStatus] projected from the single-row
 * [SyncService.contentTransferStatuses] relation. `null` until the proxy's first tick.
 *
 * This is the figure Drive's *"Waiting for Wi-Fi. N files"* banner reports. It deliberately
 * does **not** come from [SyncHealthStatus.pendingBlockCount], which counts the realm-wide
 * metadata queue (every app's writes, not files) and is not metered-gated at all.
 */
fun SyncService.contentTransfer(): Flow<ContentTransferStatus?> =
    contentTransferStatuses.flow().map { result ->
        result.getOrNull()?.toList()?.firstOrNull()
    }

/**
 * Reactive upload-pass progress (`drive-upload-progress.md` P2): the single
 * [ContentUploadProgress] projected from [SyncService.contentUploadProgresses]. `null` until a pass
 * has been reported — the banner then falls back to a plain count rather than rendering "3 of 0".
 */
fun SyncService.contentUploadProgress(): Flow<ContentUploadProgress?> =
    contentUploadProgresses.flow().map { result ->
        result.getOrNull()?.toList()?.firstOrNull()
    }

/**
 * Whether the runtime hosted by [hostAppId] is pulling data in
 * (`ios-sync-activity-indicator.md` §3.1). `false` until that runtime has written its row.
 *
 * ⚠⚠ **One writer's row, by design.** The relation holds a row per runtime on the store; this
 * reads the caller's own and ignores the rest. A row another process left `true` when it was
 * killed cannot be told from a live one, so it is never a reason to say "Syncing…" here.
 *
 * ⚠ The whole relation is read and filtered, on purpose: it holds at most one row per installed
 * first-party app, and a change to a sibling's row is dropped by the `distinctUntilChanged` every
 * caller applies to the boolean.
 */
fun SyncService.catchUp(hostAppId: String): Flow<Boolean> =
    syncCatchUpStatuses.flow().map { result ->
        result.getOrNull()?.toList()
            ?.firstOrNull { it.key.get() == hostAppId }
            ?.catchingUp?.get()
            ?: false
    }

/**
 * Whether **any** runtime on this store is pulling data in, as far as its heartbeat says
 * (`ios-sync-activity-indicator.md` §12): `true` while some writer's `SyncCatchUpHeartbeat` row
 * reads `catchingUp` with a beat younger than the staleness bound, and `false` once none does —
 * **as time passes, with no new write** (`catchUpHeartbeatLiveness`).
 *
 * ⚠ A timer only while something is fresh: with nothing fresh the store costs no timer. While
 * something is, the rows are re-read once per beat interval, because on the shared iOS replica a
 * sibling process's write does not reach this process's listeners (§12.3).
 *
 * ⚠ The rows are compared before they are judged, so a change that leaves every stamp as it was
 * does not restart the judgement.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun SyncService.anyCatchUpHeartbeat(
    nowMillis: () -> Long = wallClockMillis,
    timing: CatchUpHeartbeatTiming = CatchUpHeartbeatTiming.Default,
): Flow<Boolean> {
    val beats = syncCatchUpHeartbeats.flow().map { result -> result.getOrNull()?.toList().orEmpty().toBeats() }
    return beats
        .distinctUntilChanged()
        .flatMapLatest { initial ->
            catchUpHeartbeatLiveness(
                initial = initial,
                nowMillis = nowMillis,
                staleAfterMs = timing.staleAfterMs,
                recheckMs = timing.intervalMs,
                // ⚠ `getAll`, not `flow().first()`: a stream emits its snapshot row by row, so its
                // first emission can be a partial list.
                reread = { syncCatchUpHeartbeats.getAll().getOrNull()?.toList().orEmpty().toBeats() },
            )
        }
        .distinctUntilChanged()
}

private fun List<SyncCatchUpHeartbeat>.toBeats(): List<CatchUpBeat> =
    filter { it.catchingUp.get() }
        .map { CatchUpBeat(writer = it.key.get(), atMillis = it.beatAtMillis.get()) }
        .sortedBy { it.writer }

/**
 * What a sync-activity surface shows as *"Syncing…"* on a store several apps may share
 * (`ios-sync-activity-indicator.md` §12): this runtime's own row ([catchUp]) **or** any
 * writer's fresh heartbeat ([anyCatchUpHeartbeat]).
 *
 * The own row keeps its meaning for the own app — no timing is involved in it. The heartbeat half
 * is what shows a **sibling's** pull, which the own row is blind to by design; a killed sibling's
 * row stops counting by itself after `timing.staleAfterMs`. On Android and desktop there is one
 * runtime per store, so both halves describe the same writer.
 */
fun SyncService.catchUpOnThisStore(
    hostAppId: String,
    nowMillis: () -> Long = wallClockMillis,
    timing: CatchUpHeartbeatTiming = CatchUpHeartbeatTiming.Default,
): Flow<Boolean> =
    combine(catchUp(hostAppId), anyCatchUpHeartbeat(nowMillis, timing)) { own, any -> own || any }
        .distinctUntilChanged()
