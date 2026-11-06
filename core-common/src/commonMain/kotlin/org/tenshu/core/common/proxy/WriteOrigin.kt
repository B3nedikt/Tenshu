package org.tenshu.core.common.proxy

/**
 * Why a save happened — surfaced to [DataObserver] implementations so they can
 * adjust behaviour based on the call site. The motivating case: `SyncObserver`
 * must skip outbound block generation when the save came from
 * `SyncProcessor.processSyncBlock` ingesting a peer's block, otherwise the
 * observer echoes the inbound block back to the relay (infinite ping-pong).
 *
 * Threading [WriteOrigin] through [ExecutionContext.saveData] / [ObserverRegistry.onDataSaved]
 * is the principled alternative to a shared-mutable `SyncIngestMarker` boolean.
 * Different threads carry their own [WriteOrigin] parameter on each call; no
 * cross-thread visibility hazard.
 */
enum class WriteOrigin {
    /** User-initiated operation or local migration. The default. */
    USER,

    /** Save originated from [SyncProcessor] applying a peer's block. */
    SYNC_INGEST,

    /**
     * A **locally derived projection** — an observer recomputing a field from state this device
     * already holds, rather than new information entering the system.
     *
     * ⚠⚠ Like [SYNC_INGEST] this must never be broadcast, and for a subtly different reason. An
     * ingest echo is a *duplicate*; a derived echo is a *disagreement*. Every device recomputes
     * the same projection from its own (necessarily different) local window, so broadcasting the
     * result turns a local derivation into a distributed election with no convergence rule — two
     * devices can flip the field between their respective answers forever, each flip a fresh
     * block with a newer HLC that the other dutifully applies and re-derives.
     *
     * That is not hypothetical: `MomentReconciliationPass` wrote its `supersededBy` verdict with
     * the default [USER] origin, and the two paired devices emitted ~99.8% of all realm traffic
     * to each other — measured 2026-08-05 at ~12-32 blocks/min, sustained, around the clock, of
     * which only 4 blocks in 1,600 carried an entity either side had not already seen.
     *
     * A derived value needs no transport: the peer derives it itself, from the same rule, as soon
     * as it holds the same inputs.
     */
    DERIVED,
}
