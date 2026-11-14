package org.tenshu.sync.common

/**
 * A yes/no gate the sync wiring consults — Path A of `docs/proposals/bgproc-scheduling-roles.md`.
 * Two roles use it, and they gate different things:
 *
 * - **`syncGate`** — the realm-wide background-sync *policy* (e.g. `ControlPreferences.WifiOnly`
 *   on a metered network). ⚠ Since `sync-metered-plane-split.md` it gates the **content plane
 *   only**; the scheduler merely *reports* it (`deferred`). The metadata tick and the pull keep
 *   running, which is the policy's whole point.
 * - **`pauseGate`** — the user's device-local *pause* switch. Closed, it holds **everything**: the
 *   scheduler skips its whole drain/pull cycle and the content plane parks its uploads, until it
 *   opens again.
 *
 * Distinct from `SyncManager.isEnabled()` (the realm/unlock gate). The default [AlwaysAllow]
 * preserves prior behaviour, so test and legacy wirings are unaffected.
 *
 * Lives in `:sync-service-common` (not `-proxy`) so the gate implementation can
 * sit in `:control-shared`'s commonMain — which only depends on the `-proxy`
 * module from its platform source sets.
 */
fun interface SyncGate {
    suspend fun shouldSync(): Boolean

    companion object {
        val AlwaysAllow = SyncGate { true }
    }
}

/** Open only while both are. [SyncGate.AlwaysAllow] on either side returns the other unwrapped. */
fun SyncGate.and(other: SyncGate): SyncGate = when {
    other === SyncGate.AlwaysAllow -> this
    this === SyncGate.AlwaysAllow -> other
    else -> SyncGate { this.shouldSync() && other.shouldSync() }
}
