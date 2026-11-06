package app.tenshu.control.di

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import org.koin.core.Koin
import org.koin.core.qualifier.named
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.CoroutineContext

/** The qualifier of the graph's [ProxyGraphJob] single in [buildProxyModule]. */
const val PROXY_GRAPH_JOB = "proxyGraphJob"

/**
 * ⚠⚠ **One root [Job] per proxy graph** (`ios-shared-store-sqlite.md` B.16.8 Q45, decided
 * 2026-10-28). Every coroutine scope a definition of [buildProxyModule] makes for itself is a
 * child of [job], made through [scope], so cancelling it stops all of them: the model downloader's,
 * `MediaIndexRunner`'s, the map-region components', `ClientRegistry`'s `serviceScope` (and through
 * it every session scope and detached request job, which `ClientRegistry` already parents to it),
 * the client stream listener's, the schema announcer's, `HostPairingMonitor`'s and the pending
 * requests feed's.
 *
 * **Who cancels it.** The graph's Koin close, through the single's `onClose`: `Koin.close()` runs
 * no other teardown of this module, so without it those scopes outlived the graph (B.16.8 (229)).
 * ⚠ On iOS route 1 that close is the S0b-3 rebuild's ([closeProxyGraph], which cancels first, from
 * `FirstPartyLocalRuntime.closeGraph` and `ControlSurface.rebuildRealRuntime`), which runs **after** the host's stop (the loops unbound,
 * `SyncManager.lock()`, the app's registered components stopped) and step 2b's release of the fenced
 * handle: nothing of the old graph is still writing when its scopes are cancelled, and the
 * cancellation is never at the stop itself, because a stopped graph keeps serving its stand-in
 * (B.16 (230)) until the rebuild and a cancelled `serviceScope` would hang its ops instead.
 * ⚠ Android, desktop and the web never close their process-lifetime graph, so for them this is a
 * parent that is never cancelled: each scope behaves as the parentless one it replaced. The hub's
 * clean exit closes its graph after it has joined its loops, so there it cancels nothing still
 * running.
 *
 * ⚠ Supervisor all the way down: the root and every child are [SupervisorJob]s, so one owner's
 * failure cancels neither its siblings nor the root, exactly as when each scope had its own.
 */
@OptIn(ExperimentalAtomicApi::class)
class ProxyGraphJob {
    private val root: CompletableJob = SupervisorJob()
    private val made = AtomicReference<Map<String, CoroutineScope>>(emptyMap())

    /** The root: cancelled when the graph is closed. */
    val job: Job get() = root

    /**
     * A new scope for [owner], a supervisor child of [job], on [context] (the dispatcher and any
     * handler; a [Job] in it is replaced).
     */
    fun scope(owner: String, context: CoroutineContext = Dispatchers.Default): CoroutineScope {
        val scope = CoroutineScope(context + SupervisorJob(root))
        while (true) {
            val before = made.load()
            if (made.compareAndSet(before, before + (owner to scope))) return scope
        }
    }

    /** What [scope] made, by owner: for the teardown's tests, never for control flow. */
    val scopes: Map<String, CoroutineScope> get() = made.load()

    /** The graph's teardown. Idempotent. */
    fun cancel() {
        root.cancel(CancellationException("the proxy graph was closed (Q45)"))
    }

    /** The owners [buildProxyModule] makes scopes for, by name. */
    object Owners {
        const val MODEL_DELIVERY = "modelDelivery"
        const val MEDIA_INDEX_RUNNER = "mediaIndexRunner"
        const val MAP_REGION = "mapRegion"
        const val SERVICE_SCOPE = "serviceScope"
        const val CLIENT_STREAM = "clientStream"
        const val SCHEMA_ANNOUNCER = "schemaAnnouncer"
        const val HOST_PAIRING_MONITOR = "hostPairingMonitor"
        const val PENDING_REQUESTS = "pendingRequests"

        /**
         * iOS Control's own pending-requests feed (`ControlSurface.installRealRuntime`): not a
         * definition of [buildProxyModule], which iOS never resolves for the feed, but a scope of
         * the same graph's root.
         */
        const val CONTROL_SURFACE_PENDING_REQUESTS = "controlSurface.pendingRequests"
    }
}

/** The graph's root job, from a Koin graph built over [buildProxyModule]. */
fun proxyGraphJob(koin: Koin): ProxyGraphJob = koin.get(named(PROXY_GRAPH_JOB))

/**
 * ⚠ **A rebuilt graph's teardown** (Q45, B.16.15 (295)): its root job cancelled, **then** its Koin
 * closed. The close's `onClose` would cancel the root too, but only somewhere in the middle of
 * dropping the singletons; cancelling first means a coroutine still at work meets its cancellation
 * at its next suspension rather than a lookup in a graph already emptied, which throws a
 * non-cancellation exception that aborts a K/N process from a scope with no handler. Never joins:
 * the iOS rebuild runs on the main thread. Never throws.
 */
fun closeProxyGraph(koin: Koin) {
    try {
        koin.getOrNull<ProxyGraphJob>(named(PROXY_GRAPH_JOB))?.cancel()
    } catch (t: Throwable) {
        println("ProxyGraphJob: cancelling the graph's root threw: ${t.message}")
    }
    koin.close()
}
