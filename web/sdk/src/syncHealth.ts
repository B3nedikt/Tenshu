// Sync-health entry point: whether this device can sync, and why not.
// Backed by `:sync-service`'s single-row `SyncHealthStatus` on the Kotlin side.

import * as bundle from './_bundle'

/** The states, ordered by severity on the producing side. Mutually exclusive. */
export type Connectivity =
  | 'online'
  | 'offline'
  // The relay is reachable but refusing this device's writes (402, entitlement enforcement).
  | 'needs_attention'
  // Push deferred by the user's Wi-Fi-only policy while a backlog waits — benign.
  | 'deferred'
  // Control's realm is locked on this device, so nothing can sync.
  | 'locked'
  // The relay does not recognise this device — it was removed, or its registration was lost.
  | 'device_unrecognized'

export interface SyncHealthStatus {
  readonly connectivity: Connectivity
  readonly isSyncing: boolean
  readonly pendingBlockCount: number
  readonly lastSyncedAtMillis: number
}

export const SyncHealth = bundle.SyncHealth.getInstance()
