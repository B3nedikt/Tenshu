import type { StorageStatus } from '@tenshu/sdk/storage'

// Tier-1 gate decision for storage-consuming actions, mirroring native's
// `StorageGate.evaluate` (docs/proposals/storage-capacity-gating.md §2). A pure
// function of the cached status — the share entry point consults it to decide
// whether to mint a link or show a block message, with no per-action round trip.
//
// Tier 2 (the op-embedded `ensureCapacity` in `ShareLinkImplementation`) remains
// the authoritative, un-bypassable gate; this is the proactive UX layer, so an
// as-yet-unknown status (`null`, not yet derived) is treated optimistically as
// 'allow' rather than flashing a spurious block before the signal arrives.
export type StorageGateDecision = 'allow' | 'needs-storage' | 'full'

export function evaluateStorageGate(status: StorageStatus | null): StorageGateDecision {
  if (!status) return 'allow' // unknown ⇒ optimistic; Tier 2 backstops
  if (!status.hasStorage) return 'needs-storage'
  if (status.usedBytes >= status.limitBytes) return 'full'
  return 'allow'
}
