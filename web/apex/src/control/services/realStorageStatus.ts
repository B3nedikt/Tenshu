// The production storage reader: the SDK's `Storage.status()` (web-storage-status.md
// Bucket 1), which the apex `JsRuntime`'s `StoragePlugin` derives from the synced
// `Entitlement` plus the relay's reported usage.
//
// ⚠ Isolated in its own module because importing `@tenshu/sdk/storage` drags the
// Kotlin bundle into whatever imports it. Only the app root does — see
// `storageStatusContext.ts`.
//
// ⚠ One-shot, not reactive. `Storage.status()` snapshots a Kotlin `Flow` with
// `first()`; the React layer re-reads on mount. Good enough for a figure that
// changes on the timescale of an upload, and it matches how `web/drive`'s
// Settings section consumes the same op.
import { Storage } from '@tenshu/sdk/storage'
import type { StorageStatusSnapshot } from './servicesCardState'

export const readStorageStatus = async (): Promise<StorageStatusSnapshot | null> => {
  const status = await Storage.status()
  if (status === null || status === undefined) return null
  return {
    hasStorage: status.hasStorage,
    usedBytes: status.usedBytes,
    limitBytes: status.limitBytes,
  }
}
