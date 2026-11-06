// History over the SDK's keyset pages (`w1.` cursors). ⚠⚠ Never the whole history: a user can
// hold ~4.5k workouts, so pages load as the list scrolls and only the visible rows render.
//
// Paging rules, each pinned by `HistoryView.test.tsx`:
//  - the next page is requested with exactly the cursor the last page returned (opaque to TS);
//  - a row is rendered once, by id, even if a re-read served it again;
//  - `fit.workouts.changes` moving re-reads the HEAD: the loaded pages are dropped back to the first
//    and that page is fetched again. A row that arrived above the old cursor is only reachable that
//    way (the SDK's contract), and re-walking every cursor would re-serve rows the user scrolled past;
//  - a failed head re-read is retried as a head re-read, a failed next page as that page — except
//    `error_fit_bad_cursor`: that cursor will never read, so its retry starts from the top (the head);
//  - ⚠⚠ a retry holds off while ANY fetch runs (`isRetrying`), so it cannot cancel one.
import { useEffect, useMemo, useState } from 'react'
import { useInfiniteQuery, useQueryClient, type InfiniteData } from '@tanstack/react-query'
import type { FitFailure, FitWorkout, WorkoutsPageResult } from '@tenshu/sdk/fit'
import { subscribeWorkoutChanges, workoutsPage } from './proxy'
import { queryKeys } from './queryKeys'

export const HISTORY_PAGE_SIZE = 50

type Page = Extract<WorkoutsPageResult, { ok: true }>

// A typed failure from a page read, thrown into the query so it renders as a failure by `kind`.
export class PageFailure extends Error {
  readonly failure: FitFailure['error']
  constructor(failure: FitFailure['error']) {
    super(failure.kind)
    this.failure = failure
  }
}

async function readPage(cursor: string | null): Promise<Page> {
  const result = await workoutsPage(cursor, HISTORY_PAGE_SIZE)
  if (!result.ok) throw new PageFailure(result.error)
  return result
}

export interface History {
  readonly rows: readonly FitWorkout[]
  readonly isPending: boolean
  readonly error: unknown
  readonly hasMore: boolean
  readonly isFetchingMore: boolean
  // How many pages are loaded. It drops back to 1 when the head is re-read (`refetchHead`), which is
  // the view's cue to scroll back to the top, where the re-read head is.
  readonly pageCount: number
  readonly fetchMore: () => void
  // A read failed with rows on screen: ask for the failed read again — the next page if that was it,
  // else the head (`fetchMore` holds off while the query is in error). A bad cursor starts over.
  readonly retryMore: () => void
  // `retryMore` re-reads the head rather than the failed page: the view says "start from the top".
  readonly retryStartsOver: boolean
  // A fetch is running: `retryMore` is a no-op, and the view disables it.
  readonly isRetrying: boolean
  readonly retry: () => void
  // The change stream ended: the list no longer follows new or deleted workouts (`StreamNotice`).
  readonly changes: { readonly streamError: Error | null; readonly retry: () => void }
}

// The last `fit.workouts.changes` version History saw, across mounts (one runtime per tab).
const lastSeenVersion: { current: number | null } = { current: null }

// For tests, which share the module across cases.
export function forgetSeenVersion() {
  lastSeenVersion.current = null
}

export function useHistory(): History {
  const queryClient = useQueryClient()
  const query = useInfiniteQuery({
    queryKey: queryKeys.history(),
    queryFn: ({ pageParam }) => readPage(pageParam),
    initialPageParam: null as string | null,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    staleTime: Infinity,
  })

  // The change signal. The first value a stream ever sends is the current version, not a change.
  // ⚠ Kept OUTSIDE the component (`lastSeenVersion`): History unmounts on every other tab, and a
  // version that moved while it was closed must still re-read the head when it comes back — the
  // cached pages are `staleTime: Infinity` and would otherwise show the old list for good.
  const [attempt, setAttempt] = useState(0)
  const [changeError, setChangeError] = useState<{ attempt: number; error: Error } | null>(null)
  useEffect(() => {
    return subscribeWorkoutChanges(
      (version) => {
        const previous = lastSeenVersion.current
        lastSeenVersion.current = version
        if (previous === null || previous === version) return
        refetchHead(queryClient)
      },
      (error) => setChangeError({ attempt, error }),
    )
  }, [queryClient, attempt])

  const failure = query.error instanceof PageFailure ? query.error.failure : null
  // ⚠ Only a failed NEXT page is retried as one. A failed head re-read (rows still on screen) is
  // retried as the head: `fetchNextPage` would read on from the STALE head's cursor, clearing the error
  // while the rows that triggered the re-read stay unread. And a bad cursor is retried as the head too:
  // the same cursor would fail again, forever.
  const retryNextPage = query.isFetchNextPageError && failure?.kind !== 'error_fit_bad_cursor'

  const rows = useMemo(() => {
    const seenIds = new Set<string>()
    const out: FitWorkout[] = []
    for (const page of query.data?.pages ?? []) {
      for (const w of page.workouts) {
        if (seenIds.has(w.workoutId)) continue
        seenIds.add(w.workoutId)
        out.push(w)
      }
    }
    return out
  }, [query.data])

  return {
    rows,
    isPending: query.isPending,
    error: query.error,
    hasMore: query.hasNextPage,
    isFetchingMore: query.isFetchingNextPage,
    pageCount: query.data?.pages.length ?? 0,
    // ⚠⚠ Holds off while ANY fetch runs, not only a next-page one. During a head re-read `hasNextPage`
    // still reads the OLD head's cursor, and `fetchNextPage`'s default `cancelRefetch: true` cancels
    // the re-read: the new row never shows and page 2 is read with the stale cursor.
    fetchMore: () => {
      if (query.hasNextPage && !query.isFetching && !query.isError) void query.fetchNextPage()
    },
    // ⚠⚠ Gated on `!isFetching` like `fetchMore`: during a head re-read the query can still be in error,
    // and `fetchNextPage` (`cancelRefetch: true`) would cancel that re-read.
    retryMore: () => {
      if (query.isFetching) return
      if (retryNextPage) void query.fetchNextPage()
      else refetchHead(queryClient)
    },
    retryStartsOver: !retryNextPage,
    isRetrying: query.isFetching,
    retry: () => void query.refetch(),
    changes: {
      streamError: changeError?.attempt === attempt ? changeError.error : null,
      retry: () => {
        lastSeenVersion.current = null
        setAttempt((a) => a + 1)
        refetchHead(queryClient)
      },
    },
  }
}

// Drop every page but the head, then fetch the head again. Also called after a delete or an import,
// whose own change signal would do the same a moment later. The view scrolls back to the top when the
// page count drops (`HistoryView`), and `fetchMore` waits for this read, so the next page is asked for
// with the NEW head's cursor.
export function refetchHead(queryClient: ReturnType<typeof useQueryClient>) {
  queryClient.setQueryData<InfiniteData<Page, string | null>>(queryKeys.history(), (data) =>
    data ? { pages: data.pages.slice(0, 1), pageParams: data.pageParams.slice(0, 1) } : data,
  )
  void queryClient.invalidateQueries({ queryKey: queryKeys.history() })
  void queryClient.invalidateQueries({ queryKey: queryKeys.workouts() })
}
