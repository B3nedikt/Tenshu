// The push half of the timeline. `timeline.feed.observe` and `timeline.places.observe` are genuine
// streams (run A); each is subscribed ONCE, at the app root, and written into the query cache, so
// every screen reads one copy and no view refetches after a write — the write's own emission
// updates it. The mechanism is the app-kit's (`useLive`); this file is which streams.
//
// ⚠⚠ A terminal stream error is surfaced as `streamError` (render it with `StreamNotice`), and a
// typed failure arrives as `data` with `ok: false` — neither is ever an empty feed.
import type { ReactNode } from 'react'
import type { TimelineFeedResult, TimelinePlacesResult } from '@tenshu/sdk/timeline'
import { createLiveContext, useLive, type Live } from '@tenshu/app-kit/live'
import { feed, places, subscribeFeed, subscribePlaces } from './proxy'
import { queryKeys } from './queryKeys'

interface LiveState {
  readonly feed: Live<TimelineFeedResult>
  readonly places: Live<TimelinePlacesResult>
}

const [LiveContext, useLiveState] = createLiveContext<LiveState>('LiveProvider')

export function LiveProvider({ children }: { children: ReactNode }) {
  const feedLive = useLive(queryKeys.feed(), feed, subscribeFeed)
  const placesLive = useLive(queryKeys.places(), places, subscribePlaces)
  return <LiveContext.Provider value={{ feed: feedLive, places: placesLive }}>{children}</LiveContext.Provider>
}

// eslint-disable-next-line react-refresh/only-export-components
export function useFeed(): Live<TimelineFeedResult> {
  return useLiveState().feed
}

// eslint-disable-next-line react-refresh/only-export-components
export function usePlaces(): Live<TimelinePlacesResult> {
  return useLiveState().places
}
