// One mock of `lib/proxy` for every component test. Each test file does
//   vi.mock('../lib/proxy', async () => (await import('../test/proxyMock')).proxyModule)
// and drives the pushed streams through `streams`.
import { vi } from 'vitest'
import type {
  CalendarSummary,
  CalendarsResult,
  DefaultCalendarResult,
  HiddenCalendarsResult,
  Occurrence,
  OccurrencesResult,
  SubscriptionsResult,
} from '@tenshu/sdk/calendar'

type Sub<T> = { onValue: (v: T) => void; onError: (e: Error) => void; closed: boolean; params?: unknown }

function stream<T>() {
  const subs: Sub<T>[] = []
  return {
    subs,
    subscribe: (onValue: (v: T) => void, onError: (e: Error) => void, params?: unknown) => {
      const sub: Sub<T> = { onValue, onError, closed: false, params }
      subs.push(sub)
      return () => {
        sub.closed = true
      }
    },
    push(value: T) {
      for (const s of subs) if (!s.closed) s.onValue(value)
    },
    fail(error: Error) {
      for (const s of subs) if (!s.closed) s.onError(error)
    },
    open: () => subs.filter((s) => !s.closed).length,
    reset: () => subs.splice(0),
  }
}

export const streams = {
  calendars: stream<CalendarsResult>(),
  hidden: stream<HiddenCalendarsResult>(),
  defaultCalendar: stream<DefaultCalendarResult>(),
  subscriptions: stream<SubscriptionsResult>(),
  occurrences: stream<OccurrencesResult>(),
}

const ok = { ok: true as const }

export const WORK: CalendarSummary = { calendarId: 'cal-work', name: 'Work', colorArgb: 0xff4285f4 | 0, sensitive: false, visible: true }
export const HOME: CalendarSummary = { calendarId: 'cal-home', name: 'Home', colorArgb: 0xff0f9d58 | 0, sensitive: true, visible: true }

// The book every test starts from, unless it says otherwise: two calendars, Work the default.
let book: { calendars: CalendarSummary[]; occurrences: Occurrence[] } = { calendars: [WORK, HOME], occurrences: [] }

export function setBook(next: { calendars?: CalendarSummary[]; occurrences?: Occurrence[] }) {
  book = { ...book, ...next }
}

function defaults() {
  return {
    runtimeLease: async () => 'held' as unknown,
    runtimeUseHere: async () => {},
    subscribeSyncHealth: (_v: unknown, _e: unknown) => () => {},
    listCalendars: async (): Promise<CalendarsResult> => ({ ok: true, calendars: book.calendars }),
    subscribeCalendars: streams.calendars.subscribe,
    occurrences: async (_from: string, _to: string): Promise<OccurrencesResult> => ({
      ok: true,
      occurrences: book.occurrences,
      readerZone: 'Europe/Berlin',
      readerZoneFallback: false,
    }),
    subscribeOccurrences: (from: string, to: string, onValue: (v: OccurrencesResult) => void, onError: (e: Error) => void) =>
      streams.occurrences.subscribe(onValue, onError, { from, to }),
    subscribeHidden: (onValue: (v: HiddenCalendarsResult) => void, onError: (e: Error) => void) => {
      const stop = streams.hidden.subscribe(onValue, onError)
      // The real stream seeds itself with the current set.
      queueMicrotask(() => onValue({ ok: true, hidden: [] }))
      return stop
    },
    setCalendarVisible: async (_id: string, _v: boolean) => ({ ok: true as const, changed: true }),
    defaultCalendar: async (): Promise<DefaultCalendarResult> => ({ ok: true, calendarId: 'cal-work' }),
    subscribeDefaultCalendar: streams.defaultCalendar.subscribe,
    setDefaultCalendar: async (_id: string) => ok,
    saveCalendar: async (id: string, _n: string, _c: number, _s: boolean) => ({ ok: true as const, calendarId: id || 'cal-new' }),
    deleteCalendar: async (_id: string) => ok,
    saveEvent: async (d: { eventId: string }) => ({ ok: true as const, eventId: d.eventId || 'evt-new' }),
    saveOccurrence: async (_d: unknown, _r: string) => ok,
    deleteEvent: async (_id: string) => ok,
    deleteOccurrence: async (_id: string, _r: string) => ok,
    remindersFor: async (_id: string) => ({ ok: true as const, minutes: [10] }),
    setReminders: async (_id: string, _m: number[]) => ok,
    defaultReminderMinutes: async (allDay: boolean) => ({ ok: true as const, minutes: allDay ? -540 : 10 }),
    listSubscriptions: async (): Promise<SubscriptionsResult> => ({ ok: true, subscriptions: [] }),
    subscribeSubscriptions: streams.subscriptions.subscribe,
    addSubscription: async (_u: string, _n: string) => ({ ok: true as const, subscriptionId: 'sub-new' }),
    setSubscriptionEnabled: async (_id: string, _e: boolean) => ok,
    removeSubscription: async (_id: string) => ok,
    importIcs: async (_t: string, _c: string, _o: string) => ({
      ok: true as const,
      imported: 2,
      overrides: 0,
      skipped: 0,
      failed: 0,
      warnings: [] as never[],
    }),
    exportIcs: async (_id: string) => ({ ok: true as const, ics: 'BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n' }),
  }
}

type Impl = ReturnType<typeof defaults>
const impl = defaults()

export const proxy = Object.fromEntries(
  Object.entries(impl).map(([name, fn]) => [name, vi.fn(fn as (...args: never[]) => unknown)]),
) as unknown as { [K in keyof Impl]: ReturnType<typeof vi.fn<Impl[K]>> }

export const proxyModule = { ...proxy, ProxyMethod: {}, APEX: 'http://localhost:5173' }

export function resetProxy() {
  // ⚠ `mockReset` puts each back to its `impl`, so a `mockResolvedValue` cannot leak between tests.
  for (const fn of Object.values(proxy)) (fn as ReturnType<typeof vi.fn>).mockReset()
  for (const s of Object.values(streams)) s.reset()
  book = { calendars: [WORK, HOME], occurrences: [] }
}

let n = 0
/** A timed occurrence at LOCAL `date` `HH:MM`–`HH:MM` (instants from this machine's clock). */
export function occurrence(overrides: Partial<Occurrence> & { title: string; date: string; from?: string; to?: string }): Occurrence {
  n += 1
  const { date, from = '09:00', to = '10:00', ...rest } = overrides
  const [y, m, d] = date.split('-').map(Number) as [number, number, number]
  const at = (hm: string) => {
    const [h, mi] = hm.split(':').map(Number) as [number, number]
    return new Date(y, m - 1, d, h, mi).getTime()
  }
  return {
    eventId: `evt-${n}`,
    calendarId: WORK.calendarId,
    recurrenceId: `${date}T${from}`,
    location: '',
    notes: '',
    startLocal: `${date}T${from}`,
    endLocal: `${date}T${to}`,
    zoneId: '',
    allDay: false,
    isOverridden: false,
    recurrenceRule: '',
    isRecurring: false,
    startEpochMs: at(from),
    endEpochMs: at(to),
    zoneUnresolved: false,
    ...rest,
  }
}
