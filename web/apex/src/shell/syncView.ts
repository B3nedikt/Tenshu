// The sync pill's wording of a `SyncHealth` row (web-apex-home.md §3.4). Apart from the component so
// fast refresh keeps working.
import type { SyncRow } from './statusContext'

export type Tone = 'ok' | 'busy' | 'warn' | 'muted'

interface SyncView {
  readonly key: string
  readonly tone: Tone
}

/**
 * The sync row, worded: a catalogue key under `sync.state.` and a dot colour.
 *
 * ⚠⚠ [paused] outranks everything: a paused browser sends and fetches nothing, so the row's last
 * "online" is not a promise that anything is in sync — it must never read "Synced" (§4.6).
 */
export function syncView(row: SyncRow | null, lost = false, paused = false): SyncView {
  if (paused) return { key: 'paused', tone: 'muted' }
  // The stream ended: whatever the last row said is no longer known to be true.
  if (lost) return { key: 'unknown', tone: 'muted' }
  if (row === null) return { key: 'checking', tone: 'muted' }
  switch (row.connectivity) {
    case 'online':
      return row.isSyncing || row.pendingBlockCount > 0 ? { key: 'syncing', tone: 'busy' } : { key: 'synced', tone: 'ok' }
    case 'offline':
      return { key: 'offline', tone: 'muted' }
    case 'deferred':
      return { key: 'deferred', tone: 'muted' }
    case 'locked':
      return { key: 'locked', tone: 'warn' }
    case 'needs_attention':
    case 'device_unrecognized':
      return { key: 'attention', tone: 'warn' }
    default:
      return { key: 'unknown', tone: 'muted' }
  }
}
