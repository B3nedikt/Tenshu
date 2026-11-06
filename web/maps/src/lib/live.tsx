// The push half of Maps. `place.list.observe` is a genuine stream (run A); it is subscribed ONCE, at
// the app root, and written into the query cache, so the list and the map read one copy and no view
// refetches after a rename — the rename's own emission updates it. The mechanism is the app-kit's
// (`useLive`); this file is which stream.
//
// ⚠⚠ A terminal stream error is surfaced as `streamError` (render it with `StreamNotice`), and a
// typed failure arrives as `data` with `ok: false` — neither is ever an empty list.
import type { ReactNode } from 'react'
import type { MapsPlacesResult } from '@tenshu/sdk/maps'
import { createLiveContext, useLive, type Live } from '@tenshu/app-kit/live'
import { places, subscribePlaces } from './proxy'
import { queryKeys } from './queryKeys'

const [LiveContext, useLiveState] = createLiveContext<Live<MapsPlacesResult>>('LiveProvider')

export function LiveProvider({ children }: { children: ReactNode }) {
  const live = useLive(queryKeys.places(), places, subscribePlaces)
  return <LiveContext.Provider value={live}>{children}</LiveContext.Provider>
}

// eslint-disable-next-line react-refresh/only-export-components
export function usePlaces(): Live<MapsPlacesResult> {
  return useLiveState()
}
