// The push half of Fit. `fit.goals.observe`, `fit.profile.observe` and `fit.dataSources.observe` are
// genuine streams (run A); each is subscribed ONCE, at the app root, and written into the query
// cache, so every screen reads one copy and no view refetches after a write — the write's own
// emission updates it. The mechanism is the app-kit's (`useLive`); this file is which streams.
//
// ⚠⚠ A terminal stream error is surfaced as `streamError` (render it with `StreamNotice`), and a
// typed failure arrives as `data` with `ok: false` — neither is ever a default goal or a blank profile.
import type { ReactNode } from 'react'
import type { DataSourcesResult, GoalsResult, ProfileResult } from '@tenshu/sdk/fit'
import { createLiveContext, useLive, type Live } from '@tenshu/app-kit/live'
import { dataSources, goals, profile, subscribeDataSources, subscribeGoals, subscribeProfile } from './proxy'
import { queryKeys } from './queryKeys'
import { DEFAULT_UNITS, UnitsContext } from './units'

interface LiveState {
  readonly goals: Live<GoalsResult>
  readonly profile: Live<ProfileResult>
  readonly dataSources: Live<DataSourcesResult>
}

const [LiveContext, useLiveState] = createLiveContext<LiveState>('LiveProvider')

export function LiveProvider({ children }: { children: ReactNode }) {
  const goalsLive = useLive(queryKeys.goals(), goals, subscribeGoals)
  const profileLive = useLive(queryKeys.profile(), profile, subscribeProfile)
  const sourcesLive = useLive(queryKeys.dataSources(), dataSources, subscribeDataSources)
  // Every value on screen formats in the profile's units; metric until the read answers (`units.ts`).
  const units = profileLive.data?.ok ? profileLive.data.profile.units : DEFAULT_UNITS
  return (
    <LiveContext.Provider value={{ goals: goalsLive, profile: profileLive, dataSources: sourcesLive }}>
      <UnitsContext.Provider value={units}>{children}</UnitsContext.Provider>
    </LiveContext.Provider>
  )
}

// eslint-disable-next-line react-refresh/only-export-components
export function useGoals(): Live<GoalsResult> {
  return useLiveState().goals
}

// eslint-disable-next-line react-refresh/only-export-components
export function useProfile(): Live<ProfileResult> {
  return useLiveState().profile
}

// eslint-disable-next-line react-refresh/only-export-components
export function useDataSources(): Live<DataSourcesResult> {
  return useLiveState().dataSources
}
