// What the dock and the launcher list right now (docs/proposals/web-apex-home.md §3.5).
import { HOSTED_APPS, type HostedApp } from '../apps/registry'
import { shownApps } from './shownApps'
import { useShownAppsState, type ShownAppsState } from './shownAppsContext'

export interface VisibleApps {
  readonly apps: readonly HostedApp[]
  readonly state: ShownAppsState
}

export function useVisibleApps(): VisibleApps {
  const { state } = useShownAppsState()
  switch (state.kind) {
    // Nothing yet: a default could be wrong for a customised realm and would flash.
    case 'loading':
      return { apps: [], state }
    case 'ready':
      return { apps: shownApps(state.stored, HOSTED_APPS), state }
    // ⚠⚠ Unknown list → show everything, never a guess that hides apps.
    case 'unavailable':
      return { apps: HOSTED_APPS, state }
  }
}
