package org.tenshu.sync.proxy

/**
 * Mutable holder for the session's [SyncScheduler], populated by `initSyncService`'s
 * caller from [SyncProxyComponents.scheduler]. Same shape as [RealmRegistryHolder]:
 * the head-less `DrainSync` op's implementation reads the scheduler through this
 * holder lazily, because the op is constructed by the operation factory long before
 * the scheduler exists (the scheduler isn't in the DI graph at build time).
 *
 * `DrainSync` calls [SyncScheduler.drainNow] to run one serialized background drain
 * (bgproc Phase 1); `null` (scheduler not yet wired, e.g. a race at cold boot) ⇒ the
 * drain no-ops and the in-process loop covers the tick.
 */
class SyncSchedulerHolder {
    var scheduler: SyncScheduler? = null
}
