// Calendar entry point (web-app-suite.md Wave 2, run A). Backed by `Calendar` on the Kotlin side
// (`:tenshu-sdk-js`), which mirrors the native `CalendarRepository` op for op: the same
// `:calendar-service` ops the native apps run, dispatched through the apex in-process runtime.
//
// ⚠ Civil time throughout (calendar-app.md §3 D3): windows are `YYYY-MM-DD`, times are
// `2026-10-26T09:00` in the event's own zone. Each occurrence carries its instants too
// (`startEpochMs`), resolved with the bundled IANA database (tzdb 2026a, `@js-joda/timezone` 2.26.0).
// ⚠⚠ A window never fails over a zone it cannot name: see `readerZoneFallback` and `zoneUnresolved`.
//
// ⚠⚠ Not on web (calendar-app.md §Web scope): feed fetching, reminder firing, the first-run seed.
// The subscription rows and the reminder offsets are data, and are here.

import * as bundle from './_bundle'

export interface CalendarFailure {
  readonly ok: false
  readonly error: { readonly kind: string; readonly message?: string }
}

// The catalogue keys this surface mints itself. Any other failure carries the key derived from
// the service's typed error (e.g. `error_generic`). Branch on `kind`, never on `message`.
export const CalendarErrorKind = {
  invalidArgument: 'error_calendar_invalid_argument',
  invalidRule: 'error_calendar_invalid_rule',
  unknownZone: 'error_calendar_unknown_zone',
  notFound: 'error_calendar_not_found',
  noneInFile: 'error_calendar_none_in_file',
  subscriptionUrl: 'error_calendar_subscription_url',
  noDevice: 'error_calendar_no_device',
} as const

export interface CalendarSummary {
  readonly calendarId: string
  readonly name: string
  // ARGB, alpha byte included.
  readonly colorArgb: number
  // The lock-screen control (Q10). Synced, unlike `visible`.
  readonly sensitive: boolean
  // This browser's view state only.
  readonly visible: boolean
}

export interface Occurrence {
  readonly eventId: string
  readonly calendarId: string
  // The ORIGINAL start: pass it back to edit or skip this occurrence, even after it moved.
  readonly recurrenceId: string
  readonly title: string
  readonly location: string
  readonly notes: string
  readonly startLocal: string
  readonly endLocal: string
  // IANA id, '' = floating.
  readonly zoneId: string
  readonly allDay: boolean
  readonly isOverridden: boolean
  readonly recurrenceRule: string
  // Ask "this occurrence or all?" only when true.
  readonly isRecurring: boolean
  // Resolved in `zoneId`, or the reader's zone for floating / all-day / `zoneUnresolved`.
  readonly startEpochMs: number
  readonly endEpochMs: number
  // ⚠ `zoneId` is not in the bundled zone database (a device on a newer tzdb wrote it): the
  // instants are placed in the reader's zone instead. Say so; editing it is `unknownZone`.
  readonly zoneUnresolved: boolean
}

export interface CalendarSubscription {
  readonly subscriptionId: string
  readonly calendarId: string
  readonly url: string
  readonly enabled: boolean
  // ⚠ 0 = never fetched (by any device), not 1970.
  readonly lastFetchedEpochMs: number
}

// Every key required; see `NativeEventDraft` for what '' means per field.
export interface EventDraft {
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

export type CalendarOk = { readonly ok: true } | CalendarFailure
export type CalendarsResult = { readonly ok: true; readonly calendars: CalendarSummary[] } | CalendarFailure
export type OccurrencesResult =
  | {
      readonly ok: true
      readonly occurrences: Occurrence[]
      // The zone the window was read in: the `readerZone` argument, or the browser's own.
      readonly readerZone: string
      // ⚠ The browser's zone is not in the bundled database: times are placed with the browser's
      // own clock (`Date`), which is right for this browser but cannot be named as an IANA zone.
      readonly readerZoneFallback: boolean
    }
  | CalendarFailure
export type SubscriptionsResult =
  | { readonly ok: true; readonly subscriptions: CalendarSubscription[] }
  | CalendarFailure
export type HiddenCalendarsResult = { readonly ok: true; readonly hidden: string[] } | CalendarFailure
export type DefaultCalendarResult = { readonly ok: true; readonly calendarId: string } | CalendarFailure
// A series whose event saved while its source or reminders did not: imported, kept, and listed.
export interface IcsImportWarning {
  readonly eventId: string
  readonly part: 'source' | 'reminders'
  readonly kind: string
  readonly message: string
}
export type IcsImportResult =
  | {
      readonly ok: true
      readonly imported: number
      readonly overrides: number
      readonly skipped: number
      // Series the service refused, plus each of their overrides (never written on a failed series).
      readonly failed: number
      readonly warnings: IcsImportWarning[]
    }
  | CalendarFailure

// Operation surface: every method is Promise-returning (suspend lives on the Kotlin side); the
// `observe…` methods return their unsubscribe and take a TERMINAL `onError`.
export const Calendar = bundle.Calendar.getInstance()
