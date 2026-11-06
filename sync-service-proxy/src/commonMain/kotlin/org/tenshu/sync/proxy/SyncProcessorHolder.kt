package org.tenshu.sync.proxy

/**
 * Mutable holder for the session's [SyncProcessor]. Populated by
 * [initSyncService] once the per-`ExecutionContext` `TombstoneRegistry`
 * + `SemanticMergerRegistry` are built; read by [impl.OnDemandResolutionImpl]
 * at resolve-time so OnDemand fetches reuse the canonical processor
 * instead of constructing a fresh `SyncProcessor` (whose default
 * `TombstoneRegistry` re-registers itself as a `DataObserver` on every
 * call — accumulating observers across calls and racing with in-flight
 * `observerRegistry` iterations in `ExecutionContext.saveData`).
 *
 * Same shape as [org.tenshu.sync.common.TombstoneEmitterHolder] — the
 * Koin singleton for `OnDemandResolutionImpl` is constructed before
 * `initSyncService` runs, so the consumer reads the field lazily at
 * dispatch time rather than depending on it at construction time.
 */
class SyncProcessorHolder {
    var processor: SyncProcessor? = null
}
