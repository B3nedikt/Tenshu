// The push half of an app's data: a genuine stream (`*.observe` over the bridge), subscribed ONCE
// at the app root and written into the TanStack cache, so every screen reads one copy and no view
// refetches after a write — the write's own emission updates it. Contacts' `LiveProvider` pattern
// (web-app-suite.md §4.2): the app keeps a small provider that calls `useLive` once per stream and
// puts the results in a context (`createLiveContext`).
//
// ⚠⚠ A terminal stream error (the producer's collector died, or the gateway refused) is surfaced
// as `streamError`, never swallowed: before Contacts run B1 a dead collector froze the screen on its
// last value with nothing to say so. Render it with `StreamNotice`.
import { createContext, useContext, useEffect, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'

export interface Live<T> {
  readonly data: T | undefined
  // The seed read failed (a rejected bridge call — a typed failure arrives as `data`).
  readonly error: Error | null
  readonly isPending: boolean
  // The stream ended; `data` is the last thing it said, and may be stale.
  readonly streamError: Error | null
  readonly retry: () => void
}

/**
 * One live stream: `seed` fills the cache under `queryKey`, `subscribe` keeps writing into it.
 * ⚠ `subscribe` must be a stable reference (a module-level function): a new one resubscribes.
 */
export function useLive<T>(
  queryKey: readonly unknown[],
  seed: () => Promise<T>,
  subscribe: (onValue: (v: T) => void, onError: (e: Error) => void) => () => void,
): Live<T> {
  const queryClient = useQueryClient()
  const query = useQuery({ queryKey, queryFn: seed, staleTime: Infinity })
  // The attempt counter is the resubscribe trigger; the error belongs to the attempt that hit it,
  // so a retry clears it by moving on rather than by a setState inside the effect.
  const [attempt, setAttempt] = useState(0)
  const [failure, setFailure] = useState<{ attempt: number; error: Error } | null>(null)

  useEffect(() => {
    return subscribe(
      (value) => queryClient.setQueryData(queryKey, value),
      (error) => setFailure({ attempt, error }),
    )
    // `queryKey` is a fresh array each render; its content is fixed per call site.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [queryClient, attempt, subscribe])

  return {
    data: query.data,
    error: query.error,
    isPending: query.isPending,
    streamError: failure?.attempt === attempt ? failure.error : null,
    retry: () => {
      setAttempt((a) => a + 1)
      void query.refetch()
    },
  }
}

/**
 * The context an app's live provider fills, and the hook that reads it. The hook throws outside
 * the provider, so a screen rendered without it fails loudly rather than reading `undefined`.
 */
export function createLiveContext<S>(providerName: string) {
  const Context = createContext<S | null>(null)
  function useLiveState(): S {
    const state = useContext(Context)
    if (!state) throw new Error(`${providerName} is missing`)
    return state
  }
  return [Context, useLiveState] as const
}
