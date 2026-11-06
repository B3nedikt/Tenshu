// Gateway coverage for Calendar (web-app-suite.md Wave 2, run A).
//
// Same harness as `proxyGateway.contacts.test.ts`: every `@tenshu/sdk/*` module is mocked (the
// real ones value-import the Kotlin/JS bundle) and `serveProxyGateway` is captured so the handler
// and subscription maps can be driven directly. What this guards is the gateway's wiring and its
// param validation — the ops themselves are pinned by `CalendarWebTest` in jsTest.
import { describe, expect, it, vi, beforeEach } from 'vitest'

type Handler = (params: unknown) => unknown
type Subscription = (
  params: unknown,
  emit: (value: unknown) => void,
  fail: (message: string) => void,
) => () => void
let captured: Record<string, Handler> = {}
let subscriptions: Record<string, Subscription> = {}

vi.mock('@tenshu/sdk/bridge', () => ({
  serveProxyGateway: (options: {
    handlers: Record<string, Handler>
    subscriptions?: Record<string, Subscription>
  }) => {
    captured = options.handlers
    subscriptions = options.subscriptions ?? {}
    return { dispose: () => {} }
  },
}))

const { ensureRuntime, calendar, unsubscribe } = vi.hoisted(() => {
  const ok = { ok: true }
  const unsubscribe = () => {}
  const observe = () => vi.fn((..._args: unknown[]) => unsubscribe)
  return {
    ensureRuntime: vi.fn(),
    unsubscribe,
    calendar: {
      calendars: vi.fn(async () => ({ ok: true, calendars: [] })),
      observeCalendars: observe(),
      occurrences: vi.fn(async (_f: string, _t: string, _h: boolean, _z: string) => ({ ok: true, occurrences: [] })),
      observeOccurrences: observe(),
      observeHiddenCalendars: observe(),
      setCalendarVisible: vi.fn(async (_id: string, _v: boolean) => ({ ok: true, changed: true })),
      defaultCalendar: vi.fn(async () => ({ ok: true, calendarId: 'personal' })),
      observeDefaultCalendar: observe(),
      setDefaultCalendar: vi.fn(async (_id: string) => ok),
      saveCalendar: vi.fn(async (_id: string, _n: string, _c: number, _s: boolean) => ({ ok: true, calendarId: 'cal-1' })),
      deleteCalendar: vi.fn(async (_id: string) => ok),
      saveEvent: vi.fn(async (_d: unknown) => ({ ok: true, eventId: 'evt-1' })),
      saveOccurrence: vi.fn(async (_d: unknown, _r: string) => ok),
      deleteEvent: vi.fn(async (_id: string) => ok),
      deleteOccurrence: vi.fn(async (_id: string, _r: string) => ok),
      subscriptions: vi.fn(async () => ({ ok: true, subscriptions: [] })),
      observeSubscriptions: observe(),
      addSubscription: vi.fn(async (_u: string, _n: string) => ({ ok: true, subscriptionId: 'sub-1' })),
      setSubscriptionEnabled: vi.fn(async (_id: string, _e: boolean) => ok),
      removeSubscription: vi.fn(async (_id: string) => ok),
      importIcs: vi.fn(async (_t: string, _c: string, _o: string) => ({ ok: true, imported: 2, overrides: 0, skipped: 0, failed: 0, warnings: [] })),
      exportIcs: vi.fn(async (_c: string) => ({ ok: true, ics: 'BEGIN:VCALENDAR' })),
      remindersFor: vi.fn(async (_id: string) => ({ ok: true, minutes: [10] })),
      setReminders: vi.fn(async (_id: string, _m: number[]) => ok),
      defaultReminderMinutes: vi.fn(async (_a: boolean) => ({ ok: true, minutes: null })),
      dismissReminder: vi.fn(async (_r: string, _o: string) => ok),
      snoozeReminder: vi.fn(async (_r: string, _o: string, _u: number) => ok),
    },
  }
})

vi.mock('./runtime', () => ({ ensureRuntime: () => ensureRuntime() }))
vi.mock('@tenshu/sdk/calendar', () => ({ Calendar: calendar }))

vi.mock('@tenshu/sdk/core', () => ({ TenshuSdk: {} }))
vi.mock('@tenshu/sdk/files', () => ({ FileSystem: {} }))
vi.mock('@tenshu/sdk/content', () => ({ ContentStream: {} }))
vi.mock('@tenshu/sdk/share', () => ({ Shares: {} }))
vi.mock('@tenshu/sdk/contact', () => ({ Contacts: {} }))
vi.mock('@tenshu/sdk/membership', () => ({ Membership: {} }))
vi.mock('@tenshu/sdk/sharedFiles', () => ({ SharedFiles: {} }))
vi.mock('@tenshu/sdk/storage', () => ({ Storage: {} }))
vi.mock('@tenshu/sdk/syncHealth', () => ({ SyncHealth: {} }))
vi.mock('@tenshu/sdk/syncStatus', () => ({ SyncStatus: {} }))
vi.mock('@tenshu/sdk/timeline', () => ({ Timeline: {} }))

import { mountProxyGateway } from './proxyGateway'

function handler(method: string): Handler {
  const h = captured[method]
  if (!h) throw new Error(`gateway registered no handler for '${method}'`)
  return h
}

function subscription(method: string): Subscription {
  const s = subscriptions[method]
  if (!s) throw new Error(`gateway registered no subscription for '${method}'`)
  return s
}

const draft = {
  eventId: '',
  calendarId: 'cal-1',
  title: 'Standup',
  startLocal: '2026-10-19T09:00',
  endLocal: '2026-10-19T09:15',
  timeZoneId: 'Europe/Berlin',
  allDay: false,
  location: '',
  notes: '',
  recurrenceRule: 'FREQ=WEEKLY',
}

describe('proxyGateway — calendar', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    captured = {}
    subscriptions = {}
    mountProxyGateway()
  })

  it('registers exactly the calendar surface — and no fetch, no reminder firing, no seed', () => {
    expect(Object.keys(captured).filter((m) => m.startsWith('calendar.')).sort()).toEqual([
      'calendar.default',
      'calendar.defaultReminderMinutes',
      'calendar.delete',
      'calendar.event.delete',
      'calendar.event.save',
      'calendar.exportIcs',
      'calendar.importIcs',
      'calendar.list',
      'calendar.occurrence.delete',
      'calendar.occurrence.save',
      'calendar.occurrences',
      'calendar.reminder.dismiss',
      'calendar.reminder.snooze',
      'calendar.reminders',
      'calendar.save',
      'calendar.setDefault',
      'calendar.setReminders',
      'calendar.setVisible',
      'calendar.subscription.add',
      'calendar.subscription.remove',
      'calendar.subscription.setEnabled',
      'calendar.subscriptions',
    ])
    expect(Object.keys(subscriptions).filter((m) => m.startsWith('calendar.')).sort()).toEqual([
      'calendar.default.observe',
      'calendar.hidden.observe',
      'calendar.list.observe',
      'calendar.occurrences.observe',
      'calendar.subscriptions.observe',
    ])
  })

  it.each<[string, unknown, keyof typeof calendar, unknown[]]>([
    ['calendar.list', undefined, 'calendars', []],
    ['calendar.occurrences', { from: '2026-10-01', to: '2026-10-31' }, 'occurrences', ['2026-10-01', '2026-10-31', false, '']],
    [
      'calendar.occurrences',
      { from: '2026-10-01', to: '2026-10-31', includeHidden: true, readerZone: 'Europe/Berlin' },
      'occurrences',
      ['2026-10-01', '2026-10-31', true, 'Europe/Berlin'],
    ],
    ['calendar.setVisible', { calendarId: 'c', visible: false }, 'setCalendarVisible', ['c', false]],
    ['calendar.default', undefined, 'defaultCalendar', []],
    ['calendar.setDefault', { calendarId: 'c' }, 'setDefaultCalendar', ['c']],
    ['calendar.save', { calendarId: '', name: 'Work', colorArgb: -16776961, sensitive: true }, 'saveCalendar', ['', 'Work', -16776961, true]],
    ['calendar.delete', { calendarId: 'c' }, 'deleteCalendar', ['c']],
    ['calendar.event.save', { draft }, 'saveEvent', [draft]],
    ['calendar.occurrence.save', { draft, recurrenceId: '2026-10-26T09:00' }, 'saveOccurrence', [draft, '2026-10-26T09:00']],
    ['calendar.event.delete', { eventId: 'e' }, 'deleteEvent', ['e']],
    ['calendar.occurrence.delete', { eventId: 'e', recurrenceId: 'r' }, 'deleteOccurrence', ['e', 'r']],
    ['calendar.subscriptions', undefined, 'subscriptions', []],
    ['calendar.subscription.add', { url: 'webcal://x/y.ics', name: '' }, 'addSubscription', ['webcal://x/y.ics', '']],
    ['calendar.subscription.setEnabled', { subscriptionId: 's', enabled: false }, 'setSubscriptionEnabled', ['s', false]],
    ['calendar.subscription.remove', { subscriptionId: 's' }, 'removeSubscription', ['s']],
    ['calendar.importIcs', { text: 'BEGIN:VCALENDAR', calendarId: 'c' }, 'importIcs', ['BEGIN:VCALENDAR', 'c', '']],
    ['calendar.importIcs', { text: 'x', calendarId: 'c', origin: 'a.ics' }, 'importIcs', ['x', 'c', 'a.ics']],
    ['calendar.exportIcs', undefined, 'exportIcs', ['']],
    ['calendar.exportIcs', { calendarId: 'c' }, 'exportIcs', ['c']],
    ['calendar.reminders', { eventId: 'e' }, 'remindersFor', ['e']],
    ['calendar.setReminders', { eventId: 'e', minutesBefore: [10, -540] }, 'setReminders', ['e', [10, -540]]],
    ['calendar.setReminders', { eventId: 'e', minutesBefore: [] }, 'setReminders', ['e', []]],
    ['calendar.defaultReminderMinutes', { allDay: true }, 'defaultReminderMinutes', [true]],
    ['calendar.reminder.dismiss', { reminderId: 'r', recurrenceId: 'o' }, 'dismissReminder', ['r', 'o']],
    ['calendar.reminder.snooze', { reminderId: 'r', recurrenceId: 'o', untilEpochMs: 1_800_000_000_000 }, 'snoozeReminder', ['r', 'o', 1_800_000_000_000]],
  ])('%s forwards %j and passes the result through', async (method, params, op, args) => {
    const result = await handler(method)(params)
    expect(calendar[op]).toHaveBeenCalledWith(...args)
    expect(result).toEqual(await (calendar[op] as unknown as (...a: unknown[]) => unknown)(...args))
    expect(ensureRuntime).toHaveBeenCalled()
  })

  it('rebuilds the draft field by field, so nothing extra crosses', async () => {
    await handler('calendar.event.save')({ draft: { ...draft, injected: 'x' } })
    expect(calendar.saveEvent).toHaveBeenCalledWith(draft)
  })

  it('passes a failure envelope through untouched — never as an empty success', async () => {
    const failure = { ok: false, error: { kind: 'error_calendar_not_found', message: 'e' } }
    calendar.deleteOccurrence.mockResolvedValueOnce(failure as never)
    await expect(handler('calendar.occurrence.delete')({ eventId: 'e', recurrenceId: 'r' })).resolves.toEqual(failure)
    calendar.occurrences.mockResolvedValueOnce(failure as never)
    await expect(handler('calendar.occurrences')({ from: '2026-01-01', to: '2026-01-31' })).resolves.toEqual(failure)
  })

  it.each<[string, unknown]>([
    ['calendar.occurrences', {}],
    ['calendar.occurrences', { from: '2026-10-01' }],
    ['calendar.occurrences', { from: '1 Oct', to: '2026-10-31' }],
    ['calendar.occurrences', { from: '2026-10-01', to: '2026-10-31', includeHidden: 'yes' }],
    ['calendar.occurrences', { from: '2026-10-01', to: '2026-10-31', readerZone: 1 }],
    ['calendar.setVisible', { calendarId: 'c' }],
    ['calendar.setVisible', { calendarId: '', visible: true }],
    ['calendar.setVisible', { calendarId: 'c', visible: 'false' }],
    ['calendar.setDefault', {}],
    ['calendar.save', { calendarId: '', name: 'x', colorArgb: 1 }],
    ['calendar.save', { calendarId: '', name: 'x', colorArgb: 1.5, sensitive: false }],
    ['calendar.save', { name: 'x', colorArgb: 1, sensitive: false }],
    ['calendar.delete', { calendarId: '' }],
    ['calendar.event.save', {}],
    ['calendar.event.save', { draft: { ...draft, endLocal: undefined } }],
    ['calendar.event.save', { draft: { ...draft, allDay: 'no' } }],
    ['calendar.occurrence.save', { draft }],
    ['calendar.occurrence.save', { draft, recurrenceId: '' }],
    ['calendar.event.delete', {}],
    ['calendar.occurrence.delete', { eventId: 'e' }],
    ['calendar.subscription.add', { url: '' , name: '' }],
    ['calendar.subscription.add', { url: 'https://x' }],
    ['calendar.subscription.setEnabled', { subscriptionId: 's' }],
    ['calendar.subscription.remove', {}],
    ['calendar.importIcs', { calendarId: 'c' }],
    ['calendar.importIcs', { text: 'x' }],
    ['calendar.importIcs', { text: 'x', calendarId: 'c', origin: 3 }],
    ['calendar.exportIcs', { calendarId: 7 }],
    ['calendar.reminders', {}],
    ['calendar.setReminders', { eventId: 'e' }],
    ['calendar.setReminders', { eventId: 'e', minutesBefore: [10.5] }],
    ['calendar.setReminders', { eventId: 'e', minutesBefore: ['10'] }],
    ['calendar.defaultReminderMinutes', {}],
    ['calendar.reminder.dismiss', { reminderId: 'r' }],
    ['calendar.reminder.snooze', { reminderId: 'r', recurrenceId: 'o' }],
    ['calendar.reminder.snooze', { reminderId: 'r', recurrenceId: 'o', untilEpochMs: Number.NaN }],
  ])('%s rejects malformed params %j without dispatching', async (method, params) => {
    await expect(handler(method)(params)).rejects.toThrow(/requires|must be/)
    for (const op of Object.values(calendar)) expect(op).not.toHaveBeenCalled()
  })

  it('wires every subscription to its collector, with the terminal fail passed through', () => {
    const emit = vi.fn()
    const fail = vi.fn()
    expect(subscription('calendar.list.observe')(undefined, emit, fail)).toBe(unsubscribe)
    expect(calendar.observeCalendars).toHaveBeenCalledWith(expect.any(Function), fail)
    subscription('calendar.occurrences.observe')({ from: '2026-10-01', to: '2026-10-31', readerZone: 'UTC' }, emit, fail)
    expect(calendar.observeOccurrences).toHaveBeenCalledWith('2026-10-01', '2026-10-31', 'UTC', expect.any(Function), fail)
    subscription('calendar.hidden.observe')(undefined, emit, fail)
    expect(calendar.observeHiddenCalendars).toHaveBeenCalledWith(expect.any(Function), fail)
    subscription('calendar.default.observe')(undefined, emit, fail)
    expect(calendar.observeDefaultCalendar).toHaveBeenCalledWith(expect.any(Function), fail)
    subscription('calendar.subscriptions.observe')(undefined, emit, fail)
    expect(calendar.observeSubscriptions).toHaveBeenCalledWith(expect.any(Function), fail)
  })

  it('forwards each push through emit', () => {
    const emit = vi.fn()
    subscription('calendar.occurrences.observe')({ from: '2026-10-01', to: '2026-10-31' }, emit, vi.fn())
    const callback = calendar.observeOccurrences.mock.calls[0]?.[3] as (r: unknown) => void
    const push = { ok: true, occurrences: [{ eventId: 'e' }] }
    callback(push)
    expect(emit).toHaveBeenCalledWith(push)
  })

  it('refuses an observe window with no dates before subscribing', () => {
    expect(() => subscription('calendar.occurrences.observe')({}, vi.fn(), vi.fn())).toThrow(/requires/)
    expect(calendar.observeOccurrences).not.toHaveBeenCalled()
  })
})
