// Storage entry point: the storage-provision snapshot the upload/share entry
// points gate on (`docs/proposals/storage-capacity-gating.md` /
// `docs/proposals/web-storage-status.md`). Backed by `StorageService` on the
// Kotlin side (`:tenshu-sdk-js`), produced in the apex `JsRuntime`.

import * as bundle from './_bundle'

// One storage snapshot. `usedBytes` / `limitBytes` are raw byte counts — the UI
// formats them. `hasStorage` is the Tier-1 gate (false ⇒ no storage plan bound).
export interface StorageStatus {
  readonly hasStorage: boolean
  readonly usedBytes: number
  readonly limitBytes: number
}

// Operation: Promise-returning. Suspend lives on the Kotlin side; JS sees a
// vanilla `Promise<StorageStatus | null>` safe to use as a TanStack Query
// `queryFn`. `null` until the apex runtime has derived a status.
export const Storage = bundle.Storage.getInstance()
