// What the top bar's bell and sync pill read (docs/proposals/web-apex-home.md §3.4, H5b) — the web
// twin of the desktop tray's sync line, "Recent" list and actions.
//
// ⚠ No `@tenshu/sdk` value imports here: the runtime root injects `realStatus.ts`, tests a fake
// (the `shownAppsContext.ts` pattern). Nothing subscribes until a bell or pill mounts.
import { createContext, useContext } from 'react'

export interface RecentEvent {
  readonly id: string
  readonly timestamp: number
  readonly kind: string
  /** A path for file kinds, an app name for app kinds. */
  readonly name: string
  readonly otherCount: number
}

export interface SyncRow {
  /** 'online' | 'offline' | 'needs_attention' | 'deferred' | 'locked' | 'device_unrecognized' */
  readonly connectivity: string
  readonly isSyncing: boolean
  readonly pendingBlockCount: number
  readonly lastSyncedAtMillis: number
}

export interface StatusSource {
  /** Newest [limit] events; `null` = the read failed (⚠ never `[]` for that). Returns the unsubscribe. */
  observeRecent(limit: number, onEvents: (events: readonly RecentEvent[] | null) => void): () => void
  /**
   * The sync row; `null` before the runtime has produced one. [onLost] fires when the stream can no
   * longer deliver — ⚠ the last row is then NOT current, and must not keep saying "Synced".
   */
  observeSync(onRow: (row: SyncRow | null) => void, onLost: () => void): () => void
  /** Asks for a sync now. True when the request was signalled — not that it finished. */
  syncNow(): Promise<boolean>
  /**
   * This browser's pause switch (§4.7): the current value first, then every change — another tab's
   * included. Device-local; it pauses no other device. Returns the unsubscribe.
   */
  observePaused(onPaused: (paused: boolean) => void): () => void
  /** Pause or resume sync in this browser. Paused: no push, no pull, no upload until resumed. */
  setPaused(paused: boolean): void
}

export const StatusContext = createContext<StatusSource | null>(null)

export function useStatusSource(): StatusSource | null {
  return useContext(StatusContext)
}
