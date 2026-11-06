// `Storage` — the storage-provision snapshot and entitlement intake.
// A fragment of `../native.d.ts` (see there for the bundle's shape and the type mapping).

// `Storage.status()` snapshot — the storage-provision signal the upload/share
// entry points gate on (storage-capacity-gating.md). `usedBytes` / `limitBytes`
// are raw byte counts (the UI formats). On a web device that hasn't joined a
// funded realm this is `{ hasStorage: false, usedBytes: 0, limitBytes: 0 }`.
interface NativeStorageStatus {
  readonly hasStorage: boolean
  readonly usedBytes: number
  readonly limitBytes: number
}

interface StorageInstance {
  // Current storage snapshot, or `null` until the apex runtime has derived a
  // status. Mirrors the native `StorageService.status()` reactive read; the
  // React layer takes this snapshot and refetches.
  status(): Promise<NativeStorageStatus | null>
  // Saves a commerce-signed entitlement (the `/commerce/entitlement/mint` body) into
  // the realm. False when it names another owner or no trusted key signed it.
  applyEntitlement(fields: NativeSignedEntitlement): Promise<boolean>
}

// The `/commerce/entitlement/mint` response, field for field.
interface NativeSignedEntitlement {
  readonly ownerId: string
  readonly publisherId: string
  readonly productId: string
  readonly validFrom: number
  readonly validUntil: number
  readonly publisherSignature: string
}

declare module '*/productionExecutable/tenshu-sdk-js.js' {
  export const Storage: { getInstance(): StorageInstance }
}
