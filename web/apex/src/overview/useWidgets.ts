// What the overview draws and how it is edited (docs/proposals/web-apex-home.md §4.12): the widget
// list (`widgets.ts`) read against the shown apps.
//
// ⚠ An unreadable widget list is not an unreadable page: it draws the DEFAULT (nothing lost — the
// stored list is untouched) and refuses edits. The cards still wait for the shown apps: their order
// and set come from that list, and a default could be wrong for a customised realm.
import type { HostedApp } from '../apps/registry'
import { useWidgetsState } from './shownAppsContext'
import { useVisibleApps } from './useVisibleApps'
import { APPS_WIDGET, effectiveWidgets, type Widget } from './widgets'

export interface WidgetsView {
  /** The widgets to draw, in order; empty while the shown apps load. */
  readonly widgets: readonly Widget[]
  /** The installed (shown) apps, in the dock's order. */
  readonly installed: readonly HostedApp[]
  /** The stored list to build the next change from; null when edits are refused. */
  readonly stored: readonly string[] | null
  readonly pending: boolean
  readonly apply: (next: readonly string[]) => Promise<boolean>
}

export function useWidgets(): WidgetsView {
  const { apps, state: appsState } = useVisibleApps()
  const { state, pending, apply } = useWidgetsState()
  const stored = state.kind === 'ready' ? state.stored : []
  // Shown apps loading or unreadable: the dock alone, which says so itself (no cards — their set
  // and order come from that list). The widget list loading: nothing yet, rather than a flash of a
  // default that may be wrong.
  const widgets =
    appsState.kind !== 'ready'
      ? [{ kind: 'apps' as const, key: APPS_WIDGET }]
      : state.kind === 'loading'
        ? []
        : effectiveWidgets(stored, apps)
  return {
    widgets,
    installed: apps,
    stored: state.kind === 'ready' && appsState.kind === 'ready' ? state.stored : null,
    pending,
    apply,
  }
}
