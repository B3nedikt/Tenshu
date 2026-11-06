package org.tenshu.core.common.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * An auxiliary process-liveness source, combined with [ProxyGateway.inFlightCount] to
 * drive the platform's foreground/daemon liveness (Android FGS, desktop daemon).
 *
 * Distinct from in-flight *RPC* tasks: some background work must keep the proxy process
 * alive while it has permitted work to do, without being a client-initiated Layer-2 task
 * in the [ProxyGateway] registry. The motivating case (Phase L2-DRAIN,
 * `docs/proposals/core-background-processing.md` §4.6) is the sync **bulk drain**: a
 * large folder upload queues hundreds of small `SyncBlock`s whose sequential push can
 * take tens of seconds. The drain runs on the session scope, holds no `TaskHandle`, and
 * therefore didn't keep the Android foreground service up — so when the foreground app
 * unbound and `ProxyService.onDestroy` cancelled the session, the drain died mid-pass and
 * stranded the backlog (confirmed on-device 2026-07-11).
 *
 * The `SyncScheduler` raises this ([setActive]`(true)`) exactly while it has a
 * **permitted, pushable** backlog — backlog non-empty AND the active `SyncGate` /
 * storage gate currently permits push — and lowers it once the backlog drains or the
 * gate closes. The metered subtlety is load-bearing: on a metered network under a
 * Wi-Fi-only policy the correct state is *deferred* (no foreground service, no busy
 * loop), so a closed gate lowers this to 0 rather than pinning the process (§4.6).
 *
 * **Multi-source.** More than one independent background job may need the process kept
 * alive at once — the sync drain and a proxy-side map-region download (`maps-region-delivery.md`
 * §7.1, the download-UX slice) can overlap. Each job holds the liveness under its own
 * [source] key, so one lowering its key never clears another's. [active] is the OR of all
 * held keys — `1` while *any* source is active, else `0` — so it still composes additively
 * with the in-flight count via a simple sum: the merged count is `> 0` whenever *either*
 * source (or an in-flight RPC) wants the process kept alive.
 */
class BackgroundLiveness {
    private val sources = MutableStateFlow<Set<String>>(emptySet())

    // A tiny internal scope so [active] can be a derived StateFlow without a lock: the
    // map runs synchronously (Unconfined) on the thread that mutates `sources`, so a
    // reader sees the OR immediately. SupervisorJob keeps it alive for the process.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    /** `1` while at least one background source needs the process alive, else `0`. */
    val active: StateFlow<Int> =
        sources.map { if (it.isEmpty()) 0 else 1 }
            .stateIn(scope, SharingStarted.Eagerly, 0)

    /** The held source keys — for a consumer that reacts to one source, not the aggregate. */
    val activeSources: StateFlow<Set<String>> = sources.asStateFlow()

    /**
     * `1` while a source the user **started** is held ([USER_STARTED_SOURCES]), else `0`.
     *
     * ⚠⚠ What Android's foreground-service promotion counts — never [active]. Play allows Control's
     * `dataSync` foreground service only for work the user started and would notice stopping; the
     * other sources are automatic and run under Control's own WorkManager jobs instead
     * (`docs/proposals/control-background-work-off-fgs.md` §5.2).
     */
    val userStartedActive: StateFlow<Int> =
        sources.map { held -> if (held.any { it in USER_STARTED_SOURCES }) 1 else 0 }
            .stateIn(scope, SharingStarted.Eagerly, 0)

    /**
     * Runs [block] with [source] held, and lowers it however [block] ends — a throw or a
     * cancellation included, since a source left raised pins a foreground service for good.
     */
    suspend fun <T> holding(source: String, block: suspend () -> T): T {
        setActive(true, source)
        try {
            return block()
        } finally {
            setActive(false, source)
        }
    }

    /**
     * Raise ([active]` = true`) or lower the liveness for [source]. Idempotent — the
     * backing set conflates, so repeated same-value sets for the same source don't
     * re-emit, and [active] only re-emits when it crosses the 0 ⇄ 1 boundary.
     */
    fun setActive(active: Boolean, source: String = SYNC_DRAIN) {
        sources.update { if (active) it + source else it - source }
    }

    companion object {
        /** The default (legacy) source — the sync bulk drain. */
        const val SYNC_DRAIN: String = "sync-drain"

        /** A proxy-side map-region download in flight (`maps-region-delivery.md` §7.1). */
        const val MAP_REGION_DOWNLOAD: String = "map-region-download"

        /**
         * A proxy-side inference-model download in flight (`inference-service.md` IN-2b/1b).
         *
         * ⚠ Its own key rather than reusing [MAP_REGION_DOWNLOAD]: the two can overlap, and a
         * shared key means whichever finishes first lowers the liveness out from under the other.
         * ⚠⚠ A model is the largest artifact this project downloads — the pinned SigLIP pair is
         * ~383 MB — so this is the source most likely to be holding the process alive.
         */
        const val MODEL_DOWNLOAD: String = "model-download"

        /**
         * A media index pass in flight (`inference-service.md` §34).
         *
         * ⚠⚠ The **longest-held** source here: a first index is one neural-network run per photograph
         * — minutes on a small library, hours on a real one. A scheduled pass is already held up by
         * the job that woke it; this is what keeps a **manually started** one alive once the user
         * leaves the screen and the last client unbinds.
         */
        const val MEDIA_INDEX_PASS: String = "media-index-pass"

        /**
         * A **scheduled** media index pass in flight — automatic, so never a foreground-service
         * source (`control-background-work-off-fgs.md` §5.2). Its own key, not [MEDIA_INDEX_PASS],
         * so a manual pass's key is never lowered by a scheduled one ending, or the reverse.
         */
        const val SCHEDULED_MEDIA_INDEX_PASS: String = "scheduled-media-index-pass"

        /**
         * A realm-data reset the user started from Control's own Data screen (`RealmDataClient`).
         *
         * ⚠⚠ Its own user-started key because the reset runs **in-process** — Control's UI calls the
         * proxy without IPC, so it never reaches `ProxyGateway` and no client mark can count it. It
         * used to be protected only by the registered-task count, which no longer promotes. A reset
         * cut in half by an OEM task-killer is a half-deleted data type: hold the service for it.
         */
        const val REALM_DATA_RESET: String = "realm-data-reset"

        // ⚠ No content-maintenance key any more (`control-background-work-off-fgs.md` §5.6). The pass
        // used to raise one, from when the foreground service was what kept a pass networked; on
        // Android it now runs only under its own WorkManager job, which holds the process and the
        // network for the whole pass, and on desktop nothing ever read the key.

        /**
         * The sources a person starts from a screen, and so the only ones that may hold Android's
         * foreground service ([userStartedActive]).
         *
         * ⚠ Not [MEDIA_INDEX_PASS] since `control-background-work-off-fgs.md` §5.6.1: on Android a
         * manual *Index now* pass runs as a WorkManager long-running worker with its own
         * `mediaProcessing` (API 35+) or `dataSync` notification, so `ProxyService` holds nothing for
         * it. [SCHEDULED_MEDIA_INDEX_PASS] and [SYNC_DRAIN] are automatic and absent too.
         */
        val USER_STARTED_SOURCES: Set<String> =
            setOf(MAP_REGION_DOWNLOAD, MODEL_DOWNLOAD, REALM_DATA_RESET)
    }
}
