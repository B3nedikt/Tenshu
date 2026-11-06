// `SyncHealth` / `SyncStatus` — whether this device can sync, and the content plane.
// A fragment of `../native.d.ts` (see there for the bundle's shape and the type mapping).

// W3: whether this device can sync, and why not. Mirrors the `@JsExport` classes in
// `SyncHealth.kt`. The row itself is not new — `initSyncService` has always maintained it;
// this is the first reader on web.
interface NativeSyncHealth {
  // 'online' | 'offline' | 'needs_attention' | 'deferred' | 'locked' | 'device_unrecognized'
  readonly connectivity: string
  readonly isSyncing: boolean
  readonly pendingBlockCount: number
  readonly lastSyncedAtMillis: number
}

// W3 remainder. Mirrors the `@JsExport` classes in `SyncStatus.kt`.
interface NativeContentTransfer {
  readonly deferred: boolean
  readonly pendingFileCount: number
  readonly pendingBytes: number
}

interface NativeSchemaFence {
  readonly fenced: boolean
  readonly serviceIds: string[]
}

interface SyncStatusInstance {
  contentTransfer(): Promise<NativeContentTransfer | null>
  schemaFence(): Promise<NativeSchemaFence | null>
  // Resolves whether the KICK was signalled — not whether the drain succeeded.
  syncNow(): Promise<boolean>
}

interface SyncHealthInstance {
  // Current row, or `null` before the first tick has produced one. One shot; `observe` is the
  // reactive form.
  status(): Promise<NativeSyncHealth | null>
  // Pushes every change until the returned lambda is called. Genuine push — `syncHealth()` is a
  // Flow in the runtime and always was; `status()` just takes its first emission.
  observe(callback: (row: NativeSyncHealth | null) => void): () => void
}

declare module '*/productionExecutable/tenshu-sdk-js.js' {
  export const SyncHealth: { getInstance(): SyncHealthInstance }
  export const SyncStatus: { getInstance(): SyncStatusInstance }
}
