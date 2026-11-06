@file:OptIn(ExperimentalJsExport::class, DelicateCoroutinesApi::class)

package app.tenshu.sdk

import app.tenshu.sdk.internal.JsRuntime
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.promise
import org.tenshu.sync.SyncHealthStatus
import org.tenshu.sync.client.syncHealth
import org.tenshu.sync.client.syncService
import kotlin.js.Promise

/**
 * One row describing whether this device can sync. `connectivity` is the discriminator; the rest
 * is detail the banner may show alongside it.
 *
 * ⚠ `connectivity` is deliberately the raw constant rather than a set of booleans: the states are
 * mutually exclusive and ordered by severity on the producing side, and flattening them into flags
 * invites a UI that renders two banners at once.
 */
@JsExport
class SyncHealthJs internal constructor(
    /** `online` · `offline` · `needs_attention` · `deferred` · `locked` · `device_unrecognized`. */
    val connectivity: String,
    val isSyncing: Boolean,
    val pendingBlockCount: Int,
    val lastSyncedAtMillis: Double,
)

/**
 * Whether this device can sync (`drive-web-parity.md` W3).
 *
 * ⚠⚠ The signal is not new — `initSyncService` has always called `initSyncHealth`, so the
 * single-row `SyncHealthStatus` has been maintained in the browser's store since Phase A with
 * nothing reading it. On 2026-08-28 a browser sat in `device_unrecognized` for hours behind a
 * normal-looking file list; the row said so the whole time.
 */
@JsExport
object SyncHealth {

    /**
     * The current row, or `null` before the first tick has produced one.
     *
     * ⚠ One shot. [observe] is the reactive form; this stays for callers that genuinely want a
     * point-in-time answer (and for the bridge's `req`/`res` handler).
     */
    fun status(): Promise<SyncHealthJs?> = GlobalScope.promise {
        JsRuntime.awaitReady()
        JsRuntime.tenshuClient.syncService.syncHealth().first()?.toJs()
    }

    /**
     * Pushes every change to the sync-health row until the returned lambda is called.
     *
     * ⭐ The reactive source was never missing: `SyncService.syncHealth()` has always returned a
     * `Flow<SyncHealthStatus?>`, and [status] simply took its `first()`. The web read snapshots
     * because the **bridge** could not carry a stream (§1.2g), not because the runtime could not
     * produce one — the third signal on this track that existed with nothing able to read it.
     * W12's `sub`/`evt`/`unsub` envelopes removed that constraint.
     *
     * ⚠ Unlike `Devices.observe`, this is genuine push rather than a callback-shaped poll:
     * cancelling the returned lambda cancels the collector, which walks the Flow's `finally` and
     * ends the underlying stream.
     */
    fun observe(callback: (SyncHealthJs?) -> Unit): () -> Unit {
        val job = GlobalScope.launch {
            JsRuntime.awaitReady()
            JsRuntime.tenshuClient.syncService.syncHealth().collect { row ->
                callback(row?.toJs())
            }
        }
        return { job.cancel() }
    }

    private fun SyncHealthStatus.toJs(): SyncHealthJs = SyncHealthJs(
        connectivity = connectivity.get(),
        isSyncing = isSyncing.get(),
        pendingBlockCount = pendingBlockCount.get().toInt(),
        lastSyncedAtMillis = lastSyncedAtMillis.get().toDouble(),
    )

    /** Constants for [SyncHealthJs.connectivity], so callers do not spell them. */
    val ONLINE: String get() = SyncHealthStatus.CONNECTIVITY_ONLINE
    val OFFLINE: String get() = SyncHealthStatus.CONNECTIVITY_OFFLINE
    val NEEDS_ATTENTION: String get() = SyncHealthStatus.CONNECTIVITY_NEEDS_ATTENTION
    val DEFERRED: String get() = SyncHealthStatus.CONNECTIVITY_DEFERRED
    val LOCKED: String get() = SyncHealthStatus.CONNECTIVITY_LOCKED
    val DEVICE_UNRECOGNIZED: String get() = SyncHealthStatus.CONNECTIVITY_DEVICE_UNRECOGNIZED
}
