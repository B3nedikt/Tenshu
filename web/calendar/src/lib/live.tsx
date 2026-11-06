// The push half of the calendar. The calendars, the hidden set, the default calendar and the
// subscriptions are genuine streams (run A); each is subscribed ONCE, here, and written into the
// query cache, so every screen reads one copy and no view refetches after a write. The occurrence
// window is per view (it moves as the user pages), so it has its own hook, `useOccurrences`.
//
// ⚠⚠ A terminal stream error is surfaced as `streamError`, never swallowed (`StreamNotice`).
import { useEffect, useState, type ReactNode } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import type {
  CalendarsResult,
  DefaultCalendarResult,
  HiddenCalendarsResult,
  OccurrencesResult,
  SubscriptionsResult,
} from '@tenshu/sdk/calendar'
import { createLiveContext, useLive, type Live } from '@tenshu/app-kit/live'
import {
  defaultCalendar,
  listCalendars,
  listSubscriptions,
  occurrences,
  subscribeCalendars,
  subscribeDefaultCalendar,
  subscribeHidden,
  subscribeOccurrences,
  subscribeSubscriptions,
} from './proxy'
import { queryKeys } from './queryKeys'

interface LiveState {
  readonly calendars: Live<CalendarsResult>
  readonly hidden: Live<HiddenCalendarsResult>
  readonly defaultCalendar: Live<DefaultCalendarResult>
  readonly subscriptions: Live<SubscriptionsResult>
}

const [LiveContext, useLiveState] = createLiveContext<LiveState>('LiveProvider')

// The hidden set has no one-shot read on the bridge: its seed is the stream's first value.
function hiddenSeed(): Promise<HiddenCalendarsResult> {
  return new Promise((resolve, reject) => {
    let done = false
    let stop: (() => void) | null = null
    const finish = () => {
      done = true
      stop?.()
    }
    stop = subscribeHidden(
      (value) => {
        if (done) return
        finish()
        resolve(value)
      },
      (error) => {
        if (done) return
        finish()
        reject(error)
      },
    )
    if (done) stop()
  })
}

export function LiveProvider({ children }: { children: ReactNode }) {
  const calendars = useLive(queryKeys.calendars(), listCalendars, subscribeCalendars)
  const hidden = useLive(queryKeys.hidden(), hiddenSeed, subscribeHidden)
  const defaultCal = useLive(queryKeys.defaultCalendar(), defaultCalendar, subscribeDefaultCalendar)
  const subscriptions = useLive(queryKeys.subscriptions(), listSubscriptions, subscribeSubscriptions)
  return (
    <LiveContext.Provider value={{ calendars, hidden, defaultCalendar: defaultCal, subscriptions }}>
      {children}
    </LiveContext.Provider>
  )
}

// eslint-disable-next-line react-refresh/only-export-components
export function useCalendars(): Live<CalendarsResult> {
  return useLiveState().calendars
}

// eslint-disable-next-line react-refresh/only-export-components
export function useHidden(): Live<HiddenCalendarsResult> {
  return useLiveState().hidden
}

// eslint-disable-next-line react-refresh/only-export-components
export function useDefaultCalendar(): Live<DefaultCalendarResult> {
  return useLiveState().defaultCalendar
}

// eslint-disable-next-line react-refresh/only-export-components
export function useSubscriptions(): Live<SubscriptionsResult> {
  return useLiveState().subscriptions
}

/**
 * One occurrence window, live: seeded by `calendar.occurrences`, kept current by
 * `calendar.occurrences.observe` for exactly `[from, to]`; paging moves to a new key and a new
 * subscription. ⚠ Hidden calendars are INCLUDED; filter with `useHidden`.
 */
// eslint-disable-next-line react-refresh/only-export-components
export function useOccurrences(from: string, to: string): Live<OccurrencesResult> {
  const queryClient = useQueryClient()
  const key = queryKeys.occurrences(from, to)
  const query = useQuery({ queryKey: key, queryFn: () => occurrences(from, to), staleTime: Infinity })
  const [attempt, setAttempt] = useState(0)
  const [failure, setFailure] = useState<{ token: string; error: Error } | null>(null)
  const token = `${from}/${to}/${attempt}`

  useEffect(() => {
    return subscribeOccurrences(
      from,
      to,
      (value) => queryClient.setQueryData(queryKeys.occurrences(from, to), value),
      (error) => setFailure({ token, error }),
    )
  }, [queryClient, from, to, token])

  return {
    data: query.data,
    error: query.error,
    isPending: query.isPending,
    streamError: failure?.token === token ? failure.error : null,
    retry: () => {
      setAttempt((a) => a + 1)
      void query.refetch()
    },
  }
}
