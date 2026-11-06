package org.tenshu.sync.common

/**
 * Mutable holder for a lazily-initialized [TombstoneEmitter]. Used by Koin DI graphs where
 * the emitter (a `SyncObserver` instance) is constructed inside the proxy-initialization
 * lambda, but consumed by the operation-implementation factory bound earlier in the graph.
 *
 * The factory reads `holder.emitter` at operation-execution time, by which point the
 * proxy initialization has run and the field is set. Pre-init reads return null and the
 * affected operations (e.g. `DeleteFileImplementation`) treat the emit as a no-op.
 */
class TombstoneEmitterHolder {
    var emitter: TombstoneEmitter? = null
}
