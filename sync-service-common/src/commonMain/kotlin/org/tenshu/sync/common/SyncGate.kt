package org.tenshu.sync.common

/**
 * Policy gate for the in-process sync scheduler — Path A of
 * `docs/proposals/bgproc-scheduling-roles.md`. Each tick the scheduler skips the
 * whole drain/pull cycle when [shouldSync] returns `false`, so a realm-wide
 * background-sync preference (e.g. `ControlPreferences.WifiOnly` on a metered
 * network) suspends sync while the proxy keeps running.
 *
 * Distinct from `SyncManager.isEnabled()` (the realm/unlock gate): this is the
 * *user-policy* gate. The default [AlwaysAllow] preserves prior behaviour, so
 * test and legacy wirings are unaffected; production wiring supplies a
 * constraint-backed implementation (`ConstraintSyncGate` in `:control-shared`).
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
