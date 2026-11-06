// The remaining sync signals: pending content transfer, schema fence, and a manual drain kick.
// Backed by rows `:sync-service-proxy` already maintains — this is the first web reader.

import * as bundle from './_bundle'

export interface ContentTransfer {
  // Held back by the user's Wi-Fi-only policy rather than by a failure.
  readonly deferred: boolean
  readonly pendingFileCount: number
  readonly pendingBytes: number
}

export interface SchemaFence {
  // This build is too old to read what the realm now writes, so sync is held rather than
  // corrupting data.
  readonly fenced: boolean
  // The services holding the fence; empty when `fenced` is false.
  readonly serviceIds: readonly string[]
}

export const SyncStatus = bundle.SyncStatus.getInstance()
