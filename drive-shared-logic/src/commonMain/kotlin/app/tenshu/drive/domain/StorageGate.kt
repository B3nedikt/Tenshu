package app.tenshu.drive.domain

import app.tenshu.ingest.backup.StorageStatusModel

/**
 * Tier-1 gate decision for storage-consuming actions
 * (`docs/proposals/storage-capacity-gating.md` §2). Pure function of the cached
 * [StorageStatusModel] — the share / upload entry points consult this to decide
 * whether to run the action or raise a dialog, with no per-action round trip.
 *
 * Tier 2 (the op-embedded check) remains the authoritative, un-bypassable gate;
 * this is the proactive UX layer, so an as-yet-unknown status (`null`, not yet
 * derived) is treated optimistically as [Allow] — Tier 2 backstops it — rather
 * than flashing a spurious "needs storage" dialog before the signal arrives.
 */
sealed interface StorageGateDecision {
    /** Proceed with the action. */
    data object Allow : StorageGateDecision

    /** No storage account bound — prompt the user to set one up. */
    data object NeedsStorage : StorageGateDecision

    /** An account is bound but out of room — prompt to free up / upgrade. */
    data object Full : StorageGateDecision
}

object StorageGate {

    fun evaluate(status: StorageStatusModel?): StorageGateDecision = when {
        status == null -> StorageGateDecision.Allow      // unknown ⇒ optimistic; Tier 2 backstops
        !status.hasStorage -> StorageGateDecision.NeedsStorage
        status.isFull -> StorageGateDecision.Full
        else -> StorageGateDecision.Allow
    }
}
