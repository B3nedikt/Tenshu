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
  // The browser's device-local pause switch (web-apex-home.md §4.7). Paused: no push, no pull, no
  // upload, and `syncNow` does not get past it. Persisted per browser,
  // never synced; readable before the runtime has started.
  isPaused(): boolean
  setPaused(paused: boolean): void
  // The current value first, then every change (including another tab's). Returns the unsubscribe.
  observePaused(callback: (paused: boolean) => void): () => void
}

interface SyncHealthInstance {
  // Current row, or `null` before the first tick has produced one. One shot; `observe` is the
  // reactive form.
  status(): Promise<NativeSyncHealth | null>
  // Pushes every change until the returned lambda is called. Genuine push — `syncHealth()` is a
  // Flow in the runtime and always was; `status()` just takes its first emission.
  observe(callback: (row: NativeSyncHealth | null) => void): () => void
  // `observe` with an end: `onEnd` fires once when the stream can no longer deliver (never for the
  // returned unsubscribe), so a reader can stop showing its last row as current.
  observeUntilEnd(callback: (row: NativeSyncHealth | null) => void, onEnd: (reason: string) => void): () => void
}

declare module '*/productionExecutable/tenshu-sdk-js.js' {
  export const SyncHealth: { getInstance(): SyncHealthInstance }
  export const SyncStatus: { getInstance(): SyncStatusInstance }
}
