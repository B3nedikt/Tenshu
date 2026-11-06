package org.tenshu.sync.common

/**
 * Mutable holder for a lazily-initialized [SyncBlockEmitter]. Mirrors
 * [TombstoneEmitterHolder] — the emitter (`SyncObserver`) is constructed
 * inside the proxy-initialization lambda, but consumed by upload-side
 * operation implementations bound earlier in the Koin graph.
 *
 * Consumers read `holder.emitter` at operation-execution time, by which
 * point proxy initialization has run and the field is set. Pre-init
 * reads return null and the affected operations treat the emit as a
 * no-op (test harnesses without sync don't need byte propagation).
 */
class SyncBlockEmitterHolder {
    var emitter: SyncBlockEmitter? = null

    /**
     * The two-plane file-content emitter (`SyncObserver` again). Set
     * alongside [emitter] by `initSyncService`. Separate field rather
     * than folded into [SyncBlockEmitter] because file content takes
     * the bounded-memory chunk path, not the single-block path —
     * `UploadFileImplementation` calls this instead of
     * `emitter.emitPropertyBlock` for `File.Relation.Data`
     * (`docs/adr/0017-file-content-two-plane-channel.md` P1).
     */
    var fileContentEmitter: FileContentSyncEmitter? = null
}
