// `Calendar` — the `:calendar-service` surface (web-app-suite.md Wave 2, run A), mirroring the
// native `CalendarRepository`. A fragment of `../native.d.ts` (see there for the bundle's shape).
//
// Every call resolves `{ ok: true, … } | NativeFailure`; `error.kind` is a catalogue key (`error_…`).
// ⚠ Civil time throughout: `YYYY-MM-DD` windows, `2026-10-26T09:00` times in the event's own zone.

interface NativeCalendar {
  readonly calendarId: string
  readonly name: string
  // ARGB, alpha byte included.
  readonly colorArgb: number
  // The lock-screen control (Q10). Synced, unlike `visible`.
  readonly sensitive: boolean
  // This browser's view state (DEVICE-scoped `HiddenCalendars`).
  readonly visible: boolean
}

interface NativeOccurrence {
  readonly eventId: string
  readonly calendarId: string
  // The occurrence's ORIGINAL start — the handle to edit or skip it, even once moved.
  readonly recurrenceId: string
  readonly title: string
  readonly location: string
  readonly notes: string
  readonly startLocal: string
  readonly endLocal: string
  // IANA id, or '' for floating.
  readonly zoneId: string
  readonly allDay: boolean
  readonly isOverridden: boolean
  // The series' raw RRULE, '' for a one-off.
  readonly recurrenceRule: string
  readonly isRecurring: boolean
  // Resolved in `zoneId`, or the reader's zone for floating / all-day / `zoneUnresolved`.
  readonly startEpochMs: number
  readonly endEpochMs: number
  // ⚠ `zoneId` is not in the bundled zone database; the instants are the reader's-zone placement.
  readonly zoneUnresolved: boolean
}

interface NativeCalendarSubscription {
  readonly subscriptionId: string
  readonly calendarId: string
  readonly url: string
  readonly enabled: boolean
  // ⚠ 0 = never fetched, not the epoch.
  readonly lastFetchedEpochMs: number
}

// The editor's draft. ⚠ Every key is required: a missing one is `error_calendar_invalid_argument`.
// '' means: eventId → create; calendarId → the default calendar; endLocal → the default duration;
// timeZoneId → floating (a real value); recurrenceRule → a one-off.
interface NativeEventDraft {
  readonly eventId: string
  readonly calendarId: string
  readonly title: string
  readonly startLocal: string
  readonly endLocal: string
  readonly timeZoneId: string
  readonly allDay: boolean
  readonly location: string
  readonly notes: string
  readonly recurrenceRule: string
}

type NativeCalendarOk = { readonly ok: true } | NativeFailure
type NativeCalendarsResult = { readonly ok: true; readonly calendars: NativeCalendar[] } | NativeFailure
type NativeOccurrencesResult =
  | {
      readonly ok: true
      readonly occurrences: NativeOccurrence[]
      readonly readerZone: string
      // ⚠ The browser's zone is not in the bundled database: placed with the browser's own clock.
      readonly readerZoneFallback: boolean
    }
  | NativeFailure
type NativeSubscriptionsResult =
  | { readonly ok: true; readonly subscriptions: NativeCalendarSubscription[] }
  | NativeFailure
type NativeHiddenCalendarsResult = { readonly ok: true; readonly hidden: string[] } | NativeFailure
type NativeDefaultCalendarResult = { readonly ok: true; readonly calendarId: string } | NativeFailure

interface CalendarInstance {
  calendars(): Promise<NativeCalendarsResult>
  // ⚠ Every `onError` below is TERMINAL: it fires once when the stream can no longer deliver, and
  // nothing is pushed after it.
  observeCalendars(callback: (r: NativeCalendarsResult) => void, onError: (message: string) => void): () => void
  // Hidden calendars excluded unless `includeHidden`. `readerZone` '' = the browser's own zone.
  occurrences(from: string, to: string, includeHidden: boolean, readerZone: string): Promise<NativeOccurrencesResult>
  // ⚠⚠ Hidden calendars INCLUDED — filter with `observeHiddenCalendars` (CA-2g).
  observeOccurrences(
    from: string,
    to: string,
    readerZone: string,
    callback: (r: NativeOccurrencesResult) => void,
    onError: (message: string) => void,
  ): () => void
  // ⚠⚠ The only correct way to observe visibility: a read right after a write may predate it.
  observeHiddenCalendars(callback: (r: NativeHiddenCalendarsResult) => void, onError: (message: string) => void): () => void
  // This browser only. `changed: false` = it already was. No device id yet = `error_calendar_no_device`.
  setCalendarVisible(calendarId: string, visible: boolean): Promise<{ readonly ok: true; readonly changed: boolean } | NativeFailure>
  // '' = no default set.
  defaultCalendar(): Promise<NativeDefaultCalendarResult>
  observeDefaultCalendar(callback: (r: NativeDefaultCalendarResult) => void, onError: (message: string) => void): () => void
  setDefaultCalendar(calendarId: string): Promise<NativeCalendarOk>
  // '' calendarId creates (an id derived from the name).
  saveCalendar(calendarId: string, name: string, colorArgb: number, sensitive: boolean): Promise<{ readonly ok: true; readonly calendarId: string } | NativeFailure>
  // ⚠⚠ Deletes every event in it; re-points the default if it was this one.
  deleteCalendar(calendarId: string): Promise<NativeCalendarOk>
  saveEvent(draft: NativeEventDraft): Promise<{ readonly ok: true; readonly eventId: string } | NativeFailure>
  // ⚠⚠ ONE occurrence (its original start), not the series.
  saveOccurrence(draft: NativeEventDraft, recurrenceId: string): Promise<NativeCalendarOk>
  deleteEvent(eventId: string): Promise<NativeCalendarOk>
  deleteOccurrence(eventId: string, recurrenceId: string): Promise<NativeCalendarOk>
  // ⚠⚠ Rows only: a browser never fetches a feed (calendar-app.md §Web scope W2).
  subscriptions(): Promise<NativeSubscriptionsResult>
  observeSubscriptions(callback: (r: NativeSubscriptionsResult) => void, onError: (message: string) => void): () => void
  // https:// and webcal:// only. ⚠⚠ Consent to the user's OTHER devices fetching it.
  addSubscription(url: string, name: string): Promise<{ readonly ok: true; readonly subscriptionId: string } | NativeFailure>
  setSubscriptionEnabled(subscriptionId: string, enabled: boolean): Promise<NativeCalendarOk>
  removeSubscription(subscriptionId: string): Promise<NativeCalendarOk>
  // `skipped` = blocks the parser refused, `failed` = series the service refused (and their
  // overrides, never written); `warnings` = series imported whose source or reminders failed.
  importIcs(text: string, calendarId: string, origin: string): Promise<
    | {
        readonly ok: true
        readonly imported: number
        readonly overrides: number
        readonly skipped: number
        readonly failed: number
        readonly warnings: { readonly eventId: string; readonly part: 'source' | 'reminders'; readonly kind: string; readonly message: string }[]
      }
    | NativeFailure
  >
  // '' = every calendar.
  exportIcs(calendarId: string): Promise<{ readonly ok: true; readonly ics: string } | NativeFailure>
  // Reminder DATA only (W1): minutes before the start, negative = after. Nothing in a tab fires.
  remindersFor(eventId: string): Promise<{ readonly ok: true; readonly minutes: number[] } | NativeFailure>
  // A set, not add/remove.
  setReminders(eventId: string, minutesBefore: number[]): Promise<NativeCalendarOk>
  // `minutes: null` = no reminder by default.
  defaultReminderMinutes(allDay: boolean): Promise<{ readonly ok: true; readonly minutes: number | null } | NativeFailure>
  dismissReminder(reminderId: string, recurrenceId: string): Promise<NativeCalendarOk>
  snoozeReminder(reminderId: string, recurrenceId: string, untilEpochMs: number): Promise<NativeCalendarOk>
}

declare module '*/productionExecutable/tenshu-sdk-js.js' {
  export const Calendar: { getInstance(): CalendarInstance }
}
