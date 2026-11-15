// The feed's state, held once at the app root so switching tabs keeps its place: the capture-time
// index (the grid's skeleton and the scrubber's map) and the keyset pager (the rows).
//
// ⚠ Snapshot + refetch: the feed has no push form (photos-app.md §43). `refresh()` starts a fresh
// pager from the head and refetches the index; the app calls it after its own writes that move a
// photograph in or out of the feed (trash, restore), and the user can call it from the header. A
// file moved or deleted in Drive changes no photo-service row, so nothing pushes it.
import { createContext, useCallback, useContext, useMemo, useState, useSyncExternalStore, type ReactNode } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import type { CaptureTimesResult } from '@tenshu/sdk/photos'
import { captureTimes, feedPage, type FeedKind } from './proxy'
import { queryKeys } from './queryKeys'
import { FeedPager, type PagerState } from './feedPager'

export interface FeedState {
  readonly times: { readonly data: CaptureTimesResult | undefined; readonly error: Error | null; readonly isPending: boolean }
  readonly pager: FeedPager
  readonly page: PagerState
  readonly refresh: () => void
}

const FeedContext = createContext<FeedState | null>(null)

// [kind] 'videos' is the Videos collection (L5): its own pager and times, mounted inside the
// library's provider while that section is open. Its refresh refreshes the library too — a video
// trashed from Videos must leave the library's feed as well.
export function FeedProvider({ children, kind = 'all' }: { children: ReactNode; kind?: FeedKind }) {
  const queryClient = useQueryClient()
  const parent = useContext(FeedContext)
  // The library's call stays exactly what it was; only Videos adds a kind.
  const fetchPage = useCallback(
    (cursor: string | null, size: number) => (kind === 'all' ? feedPage(cursor, size) : feedPage(cursor, size, kind)),
    [kind],
  )
  const [pager, setPager] = useState(() => new FeedPager(fetchPage))
  const page = useSyncExternalStore(pager.subscribe, pager.getState)
  const times = useQuery({ queryKey: queryKeys.captureTimes(kind), queryFn: () => captureTimes(kind), staleTime: Infinity })
  const parentRefresh = parent?.refresh
  const refresh = useCallback(() => {
    setPager(new FeedPager(fetchPage))
    // ⚠ The library's refresh invalidates the `['captureTimes']` PREFIX — every kind's times, the
    // Videos entry included. A trash or restore anywhere else changes the Videos feed too, and a
    // stale cached index would no longer map its rows (index i of the times is index i of the feed).
    void queryClient.invalidateQueries({ queryKey: queryKeys.captureTimes(kind), exact: kind !== 'all' })
    parentRefresh?.()
  }, [queryClient, fetchPage, kind, parentRefresh])
  const value = useMemo(
    () => ({ times: { data: times.data, error: times.error, isPending: times.isPending }, pager, page, refresh }),
    [times.data, times.error, times.isPending, pager, page, refresh],
  )
  return <FeedContext.Provider value={value}>{children}</FeedContext.Provider>
}

// eslint-disable-next-line react-refresh/only-export-components
export function useFeed(): FeedState {
  const state = useContext(FeedContext)
  if (!state) throw new Error('FeedProvider is missing')
  return state
}
