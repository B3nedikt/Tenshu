// Editing the shown set — the "Your apps" dialog and Customize mode
// (docs/proposals/web-apex-home.md §3.5, §3.5.1).
//
// ⚠⚠ Build every change from `stored` here, and refuse new ones while `pending`. `stored` already
// carries the provider's single write overlay (`ShownAppsProvider`): between a write resolving and
// the store re-emitting it, it is the list just written, for every editor at once.
import { useShownAppsState, type ShownAppsState } from './shownAppsContext'

export interface ShownAppsEditor {
  readonly state: ShownAppsState
  /** The list to build the next change from; `null` unless the state is `ready`. */
  readonly stored: readonly string[] | null
  /** A write is in flight anywhere — editors refuse new changes until it settles. */
  readonly pending: boolean
  /** Writes [next] as the whole list. False when it failed (nothing moved) or the list is not ready. */
  apply(next: readonly string[]): Promise<boolean>
}

export function useShownAppsEditor(): ShownAppsEditor {
  const { state, pending, apply } = useShownAppsState()
  return { state, stored: state.kind === 'ready' ? state.stored : null, pending, apply }
}
