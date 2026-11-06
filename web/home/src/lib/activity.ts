// The activity feed over the SDK's keyset pages (`a1.` cursors, web-app-suite.md §4.H). ⚠⚠ Never the
// whole history: `RoutineRun` retention is declared but not enforced, so the feed is unbounded; pages
// load as the list scrolls and only the visible rows render (`ActivityView`). Fit's History rules
// (`web/fit/src/lib/history.ts`), each pinned by `ActivityView.test.tsx`:
//  - the next page is requested with exactly the cursor the last page returned (opaque to TS);
//  - a row is rendered once, by `firingId`, even if a re-read served it again;
//  - `home.activity.changes` moving re-reads the HEAD: the loaded pages drop back to the first and
//    that page is fetched again. A run that arrived above the old cursor is only reachable that way;
//  - ⚠⚠ the version counts per SUBSCRIPTION, not per runtime (`Home.observeActivityChanges` starts each
//    one at 1), so versions are never compared across subscriptions. Instead a new subscription over a
//    cached feed (the tab came back, or the stream was reconnected) re-reads the head at once: what
//    changed while nothing was listening is otherwise lost to `staleTime: Infinity`;
//  - a failed head re-read is retried as a head re-read, a failed next page as that page — except
//    `error_home_bad_cursor`: that cursor will never read, so its retry starts from the top (the head);
//  - ⚠⚠ a retry holds off while ANY fetch runs (`isRetrying`), so it cannot cancel one either;
//  - ⚠⚠ `fetchMore` holds off while ANY fetch runs, so it cannot cancel that head re-read;
//  - `events: []` + `nextCursor: null` is the NORMAL end (an exactly-full last page carries a cursor).
import { useEffect, useMemo, useState } from 'react'
import { useInfiniteQuery, useQueryClient, type InfiniteData, type QueryClient } from '@tanstack/react-query'
import type { HomeActivity, HomeActivityPageResult, HomeError } from '@tenshu/sdk/home'
import { activityPage, subscribeActivityChanges } from './proxy'
import { queryKeys } from './queryKeys'

export const ACTIVITY_PAGE_SIZE = 50

type Page = Extract<HomeActivityPageResult, { ok: true }>

// A typed failure from a page read, thrown into the query so it renders as a failure by `kind`.
export class PageFailure extends Error {
  readonly failure: HomeError
  constructor(failure: HomeError) {
    super(failure.kind)
    this.failure = failure
  }
}

async function readPage(cursor: string | null): Promise<Page> {
  const result = await activityPage(cursor, ACTIVITY_PAGE_SIZE)
  if (!result.ok) throw new PageFailure(result.error)
  return result
}

export interface Activity {
  readonly rows: readonly HomeActivity[]
  readonly isPending: boolean
  readonly error: unknown
  readonly hasMore: boolean
  readonly isFetchingMore: boolean
  // Drops back to 1 when the head is re-read: the view's cue to scroll back to the top.
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
  // The change stream ended: the feed no longer follows new runs (`Stopped`).
  readonly changes: { readonly streamError: Error | null; readonly retry: () => void }
}

export function useActivity(): Activity {
  const queryClient = useQueryClient()
  const query = useInfiniteQuery({
    queryKey: queryKeys.activity(),
    queryFn: ({ pageParam }) => readPage(pageParam),
    initialPageParam: null as string | null,
    // Exactly the returned cursor; `null` — or an empty page — is the end.
    getNextPageParam: (last) => (last.events.length === 0 ? undefined : (last.nextCursor ?? undefined)),
    staleTime: Infinity,
  })

  // Each subscription's first value is its starting version, not a change. ⚠ The previous version lives
  // in THIS subscription only: a new one starts counting again (see the header).
  const [attempt, setAttempt] = useState(0)
  const [changeError, setChangeError] = useState<{ attempt: number; error: Error } | null>(null)
  useEffect(() => {
    // A cached feed was not followed while unsubscribed: re-read its head now.
    if (queryClient.getQueryData(queryKeys.activity()) !== undefined) refetchHead(queryClient)
    let previous: number | null = null
    return subscribeActivityChanges(
      (version) => {
        const seen = previous
        previous = version
        if (seen === null || seen === version) return
        refetchHead(queryClient)
      },
      (error) => setChangeError({ attempt, error }),
    )
  }, [queryClient, attempt])

  const failure = query.error instanceof PageFailure ? query.error.failure : null
  // ⚠ Only a failed NEXT page is retried as one. A failed head re-read is retried as the head:
  // `fetchNextPage` would read on from the STALE head's cursor (or nothing), clearing the error while
  // the runs that triggered the re-read stay unread. And a bad cursor is retried as the head too: the
  // same cursor would fail again, forever.
  const retryNextPage = query.isFetchNextPageError && failure?.kind !== 'error_home_bad_cursor'

  const rows = useMemo(() => {
    const seen = new Set<string>()
    const out: HomeActivity[] = []
    for (const page of query.data?.pages ?? []) {
      for (const e of page.events) {
        if (seen.has(e.firingId)) continue
        seen.add(e.firingId)
        out.push(e)
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
    // ⚠⚠ `!isFetching`, not `!isFetchingNextPage`: during a head re-read `hasNextPage` still reads the
    // OLD head's cursor, and `fetchNextPage`'s default `cancelRefetch: true` would cancel the re-read.
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
      // Resubscribing re-reads the head (the effect above, over the cached feed).
      retry: () => setAttempt((a) => a + 1),
    },
  }
}

// Drop every page but the head, then fetch the head again.
export function refetchHead(queryClient: QueryClient) {
  queryClient.setQueryData<InfiniteData<Page, string | null>>(queryKeys.activity(), (data) =>
    data ? { pages: data.pages.slice(0, 1), pageParams: data.pageParams.slice(0, 1) } : data,
  )
  void queryClient.invalidateQueries({ queryKey: queryKeys.activity() })
}
