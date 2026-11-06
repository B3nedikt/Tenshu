// Calendar's single connection to the apex proxy (docs/web/architecture.md §5.2): the app has no
// backend of its own, and every read and write goes through the apex, which holds the keys and
// runs the runtime. The connection is the app-kit's (`createApexBridge`); this file is the wire
// names and the typed calls. The handlers are run A's (`web/apex/src/lib/gateway/calendar.ts`).
//
// ⚠⚠ No value import from `@tenshu/sdk` here — `import type` only. A value import drags the Kotlin
// runtime into this bundle (web-app-suite.md §7; `sdkBoundary.test.ts` fails on it).
//
// ⚠⚠ What this origin may call is decided apex-side (F5): `methods` in
// `web/apex/src/apps/registry/apps/calendar.ts`. A name added to `ProxyMethod` below must be added
// there too — the apex's `gatewayPermissions.test.ts` reads this table and fails until it is.
//
// ⚠⚠ Not here, on purpose (calendar-app.md §Web scope, Decided): `calendar.reminder.dismiss` /
// `.snooze` (nothing in a tab fires a reminder, so there is nothing to dismiss — W1), and no
// subscription refresh exists on the bridge at all (W2).
import { createApexBridge } from '@tenshu/app-kit/bridge'
import type { RuntimeLeaseState } from '@tenshu/sdk/core'
import type { SyncHealthStatus } from '@tenshu/sdk/syncHealth'
import type {
  CalendarOk,
  CalendarsResult,
  CalendarFailure,
  DefaultCalendarResult,
  EventDraft,
  HiddenCalendarsResult,
  IcsImportResult,
  OccurrencesResult,
  SubscriptionsResult,
} from '@tenshu/sdk/calendar'

// The wire names, shared with the apex gateway (`web/apex/src/lib/gateway/calendar.ts`).
export const ProxyMethod = {
  Ping: 'bridge.ping',
  RuntimeLease: 'runtime.lease',
  RuntimeUseHere: 'runtime.useHere',
  SyncHealthObserve: 'sync.health.observe',
  List: 'calendar.list',
  ListObserve: 'calendar.list.observe',
  Occurrences: 'calendar.occurrences',
  OccurrencesObserve: 'calendar.occurrences.observe',
  HiddenObserve: 'calendar.hidden.observe',
  SetVisible: 'calendar.setVisible',
  Default: 'calendar.default',
  DefaultObserve: 'calendar.default.observe',
  SetDefault: 'calendar.setDefault',
  Save: 'calendar.save',
  Delete: 'calendar.delete',
  EventSave: 'calendar.event.save',
  OccurrenceSave: 'calendar.occurrence.save',
  EventDelete: 'calendar.event.delete',
  OccurrenceDelete: 'calendar.occurrence.delete',
  Subscriptions: 'calendar.subscriptions',
  SubscriptionsObserve: 'calendar.subscriptions.observe',
  SubscriptionAdd: 'calendar.subscription.add',
  SubscriptionSetEnabled: 'calendar.subscription.setEnabled',
  SubscriptionRemove: 'calendar.subscription.remove',
  ImportIcs: 'calendar.importIcs',
  ExportIcs: 'calendar.exportIcs',
  Reminders: 'calendar.reminders',
  SetReminders: 'calendar.setReminders',
  DefaultReminderMinutes: 'calendar.defaultReminderMinutes',
} as const

// The one shared connection to `VITE_APEX_ORIGIN` (else the apex dev server), mounted on first use.
const bridge = createApexBridge(ProxyMethod)
const call = bridge.call
const subscribe = bridge.subscribe

type Pushed<T> = (onValue: (value: T) => void, onError: (error: Error) => void) => () => void

// ── session ─────────────────────────────────────────────────────────────────

export function runtimeLease(): Promise<RuntimeLeaseState> {
  return call<RuntimeLeaseState>(ProxyMethod.RuntimeLease)
}

export async function runtimeUseHere(): Promise<void> {
  await call<void>(ProxyMethod.RuntimeUseHere)
}

export function subscribeSyncHealth(
  onValue: (status: SyncHealthStatus | null) => void,
  onError: (error: Error) => void,
): () => void {
  return subscribe(ProxyMethod.SyncHealthObserve, undefined, onValue, onError)
}

// ── calendars ───────────────────────────────────────────────────────────────

export function listCalendars(): Promise<CalendarsResult> {
  return call<CalendarsResult>(ProxyMethod.List)
}

export const subscribeCalendars: Pushed<CalendarsResult> = (onValue, onError) =>
  subscribe(ProxyMethod.ListObserve, undefined, onValue, onError)

// ⚠⚠ The seed read: hidden calendars INCLUDED, like the stream below, so the two agree and the view
// filters both with the hidden set (CA-2g).
export function occurrences(from: string, to: string): Promise<OccurrencesResult> {
  return call<OccurrencesResult>(ProxyMethod.Occurrences, { from, to, includeHidden: true })
}

// ⚠⚠ Hidden calendars INCLUDED (the native contract): filter with `subscribeHidden`.
export function subscribeOccurrences(
  from: string,
  to: string,
  onValue: (value: OccurrencesResult) => void,
  onError: (error: Error) => void,
): () => void {
  return subscribe(ProxyMethod.OccurrencesObserve, { from, to }, onValue, onError)
}

// There is no one-shot read of the hidden set on the bridge; the stream seeds itself.
export const subscribeHidden: Pushed<HiddenCalendarsResult> = (onValue, onError) =>
  subscribe(ProxyMethod.HiddenObserve, undefined, onValue, onError)

// This browser only (DEVICE scope).
export function setCalendarVisible(
  calendarId: string,
  visible: boolean,
): Promise<{ readonly ok: true; readonly changed: boolean } | CalendarFailure> {
  return call(ProxyMethod.SetVisible, { calendarId, visible })
}

export function defaultCalendar(): Promise<DefaultCalendarResult> {
  return call<DefaultCalendarResult>(ProxyMethod.Default)
}

export const subscribeDefaultCalendar: Pushed<DefaultCalendarResult> = (onValue, onError) =>
  subscribe(ProxyMethod.DefaultObserve, undefined, onValue, onError)

export function setDefaultCalendar(calendarId: string): Promise<CalendarOk> {
  return call<CalendarOk>(ProxyMethod.SetDefault, { calendarId })
}

// '' calendarId creates. `sensitive` is the lock-screen control (Q10): passed through, never defaulted.
export function saveCalendar(
  calendarId: string,
  name: string,
  colorArgb: number,
  sensitive: boolean,
): Promise<{ readonly ok: true; readonly calendarId: string } | CalendarFailure> {
  return call(ProxyMethod.Save, { calendarId, name, colorArgb, sensitive })
}

// ⚠⚠ Deletes every event in it.
export function deleteCalendar(calendarId: string): Promise<CalendarOk> {
  return call<CalendarOk>(ProxyMethod.Delete, { calendarId })
}

// ── events ──────────────────────────────────────────────────────────────────

export function saveEvent(draft: EventDraft): Promise<{ readonly ok: true; readonly eventId: string } | CalendarFailure> {
  return call(ProxyMethod.EventSave, { draft })
}

// ⚠⚠ ONE occurrence, keyed by its ORIGINAL start; the series is untouched.
export function saveOccurrence(draft: EventDraft, recurrenceId: string): Promise<CalendarOk> {
  return call<CalendarOk>(ProxyMethod.OccurrenceSave, { draft, recurrenceId })
}

export function deleteEvent(eventId: string): Promise<CalendarOk> {
  return call<CalendarOk>(ProxyMethod.EventDelete, { eventId })
}

export function deleteOccurrence(eventId: string, recurrenceId: string): Promise<CalendarOk> {
  return call<CalendarOk>(ProxyMethod.OccurrenceDelete, { eventId, recurrenceId })
}

// ── reminders: the offsets only (W1) ────────────────────────────────────────

export function remindersFor(
  eventId: string,
): Promise<{ readonly ok: true; readonly minutes: number[] } | CalendarFailure> {
  return call(ProxyMethod.Reminders, { eventId })
}

// A set, not add/remove.
export function setReminders(eventId: string, minutesBefore: number[]): Promise<CalendarOk> {
  return call<CalendarOk>(ProxyMethod.SetReminders, { eventId, minutesBefore })
}

export function defaultReminderMinutes(
  allDay: boolean,
): Promise<{ readonly ok: true; readonly minutes: number | null } | CalendarFailure> {
  return call(ProxyMethod.DefaultReminderMinutes, { allDay })
}

// ── subscriptions: the rows only (W2) ───────────────────────────────────────

export function listSubscriptions(): Promise<SubscriptionsResult> {
  return call<SubscriptionsResult>(ProxyMethod.Subscriptions)
}

export const subscribeSubscriptions: Pushed<SubscriptionsResult> = (onValue, onError) =>
  subscribe(ProxyMethod.SubscriptionsObserve, undefined, onValue, onError)

// ⚠⚠ Consent to the user's OTHER devices fetching the feed: the copy before this call says so.
export function addSubscription(
  url: string,
  name: string,
): Promise<{ readonly ok: true; readonly subscriptionId: string } | CalendarFailure> {
  return call(ProxyMethod.SubscriptionAdd, { url, name })
}

export function setSubscriptionEnabled(subscriptionId: string, enabled: boolean): Promise<CalendarOk> {
  return call<CalendarOk>(ProxyMethod.SubscriptionSetEnabled, { subscriptionId, enabled })
}

// ⚠⚠ Also deletes the calendar the feed filled.
export function removeSubscription(subscriptionId: string): Promise<CalendarOk> {
  return call<CalendarOk>(ProxyMethod.SubscriptionRemove, { subscriptionId })
}

// ── .ics text ───────────────────────────────────────────────────────────────

// `origin` is free text shown to the user (the file name); never parsed.
export function importIcs(text: string, calendarId: string, origin: string): Promise<IcsImportResult> {
  return call<IcsImportResult>(ProxyMethod.ImportIcs, { text, calendarId, origin })
}

// '' = every calendar.
export function exportIcs(calendarId: string): Promise<{ readonly ok: true; readonly ics: string } | CalendarFailure> {
  return call(ProxyMethod.ExportIcs, { calendarId })
}

export const APEX = bridge.apexOrigin
