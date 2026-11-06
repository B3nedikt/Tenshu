package org.tenshu.sync.proxy

/**
 * Optional action run by [TenshuOperationWorker] **after** an app-closed
 * `DrainSync` background drain completes (bgproc Phase 1 — it moved here when the
 * `DrainSync` op replaced the bespoke `SyncWorker`). The drain is the only thing
 * that wakes a closed Control process
 * on a schedule, so it's also the natural place to react to whatever the pull just
 * surfaced — e.g. Control's central notifications post an OS alert for a freshly
 * pulled membership grant / redeemed invite (`control-central-notifications.md`
 * §4.3.4) *before* the process is reaped, rather than racing it from an
 * Application-scope observer.
 *
 * Generic by design (no Control concepts): bound optionally via Koin, so a build
 * without one just skips it. A throwing hook must not fail the sync result — the
 * worker isolates it.
 */
fun interface PostDrainHook {
    suspend fun onDrained()
}
