// `calendar.*` — the `:calendar-service` bridge (web-app-suite.md Wave 2, run A), mirroring the
// native `CalendarRepository` op for op. Every handler is a validated pass-through: the ops, the
// occurrence fold and the typed `{ ok, error: { kind } }` envelopes live in the Kotlin SDK
// (`Calendar.kt`). The reads that are Flows natively have a push form under `subscriptions`.
//
// ⚠⚠ Not here, on purpose (calendar-app.md §Web scope): feed fetching, reminder firing and the
// first-run seed. No app's allowlist names these methods yet, so they are unreachable until the
// Calendar app (run B) registers its origin.
import { Calendar } from '@tenshu/sdk/calendar'
import type { EventDraft } from '@tenshu/sdk/calendar'
import type { GatewayModule } from './module'
import { asRecord, readId, readString } from './params'

export const calendarGateway: GatewayModule = {
  handlers: {
    'calendar.list': async () => Calendar.calendars(),
    'calendar.occurrences': async (params) => {
      const w = readWindow(params, 'calendar.occurrences')
      return Calendar.occurrences(w.from, w.to, readOptionalBoolean(params, 'includeHidden', 'calendar.occurrences'), w.readerZone)
    },
    // This browser only (DEVICE scope).
    'calendar.setVisible': async (params) =>
      Calendar.setCalendarVisible(
        readId(params, 'calendarId', 'calendar.setVisible'),
        readBoolean(params, 'visible', 'calendar.setVisible'),
      ),
    'calendar.default': async () => Calendar.defaultCalendar(),
    'calendar.setDefault': async (params) =>
      Calendar.setDefaultCalendar(readId(params, 'calendarId', 'calendar.setDefault')),
    // '' calendarId creates. `sensitive` is the lock-screen control (Q10): required, never defaulted.
    'calendar.save': async (params) =>
      Calendar.saveCalendar(
        readString(params, 'calendarId', 'calendar.save'),
        readString(params, 'name', 'calendar.save'),
        readInteger(params, 'colorArgb', 'calendar.save'),
        readBoolean(params, 'sensitive', 'calendar.save'),
      ),
    // ⚠⚠ Deletes every event in the calendar: the calling UI must say so first.
    'calendar.delete': async (params) => Calendar.deleteCalendar(readId(params, 'calendarId', 'calendar.delete')),
    'calendar.event.save': async (params) => Calendar.saveEvent(readDraft(params, 'calendar.event.save')),
    // ⚠⚠ One occurrence, keyed by its ORIGINAL start; the series is untouched.
    'calendar.occurrence.save': async (params) =>
      Calendar.saveOccurrence(
        readDraft(params, 'calendar.occurrence.save'),
        readId(params, 'recurrenceId', 'calendar.occurrence.save'),
      ),
    'calendar.event.delete': async (params) => Calendar.deleteEvent(readId(params, 'eventId', 'calendar.event.delete')),
    'calendar.occurrence.delete': async (params) =>
      Calendar.deleteOccurrence(
        readId(params, 'eventId', 'calendar.occurrence.delete'),
        readId(params, 'recurrenceId', 'calendar.occurrence.delete'),
      ),

    // Subscription ROWS only (W2): the browser never fetches; the user's native devices do.
    'calendar.subscriptions': async () => Calendar.subscriptions(),
    'calendar.subscription.add': async (params) =>
      Calendar.addSubscription(
        readId(params, 'url', 'calendar.subscription.add'),
        readString(params, 'name', 'calendar.subscription.add'),
      ),
    'calendar.subscription.setEnabled': async (params) =>
      Calendar.setSubscriptionEnabled(
        readId(params, 'subscriptionId', 'calendar.subscription.setEnabled'),
        readBoolean(params, 'enabled', 'calendar.subscription.setEnabled'),
      ),
    // ⚠⚠ Also deletes the calendar the feed filled.
    'calendar.subscription.remove': async (params) =>
      Calendar.removeSubscription(readId(params, 'subscriptionId', 'calendar.subscription.remove')),

    'calendar.importIcs': async (params) =>
      Calendar.importIcs(
        readString(params, 'text', 'calendar.importIcs'),
        readId(params, 'calendarId', 'calendar.importIcs'),
        readOptionalString(params, 'origin', 'calendar.importIcs'),
      ),
    // '' (or absent) calendarId = every calendar.
    'calendar.exportIcs': async (params) =>
      Calendar.exportIcs(readOptionalString(params, 'calendarId', 'calendar.exportIcs')),

    // Reminder DATA only (W1): nothing in a tab fires a reminder.
    'calendar.reminders': async (params) => Calendar.remindersFor(readId(params, 'eventId', 'calendar.reminders')),
    'calendar.setReminders': async (params) =>
      Calendar.setReminders(readId(params, 'eventId', 'calendar.setReminders'), readMinutes(params)),
    'calendar.defaultReminderMinutes': async (params) =>
      Calendar.defaultReminderMinutes(readBoolean(params, 'allDay', 'calendar.defaultReminderMinutes')),
    'calendar.reminder.dismiss': async (params) =>
      Calendar.dismissReminder(
        readId(params, 'reminderId', 'calendar.reminder.dismiss'),
        readId(params, 'recurrenceId', 'calendar.reminder.dismiss'),
      ),
    'calendar.reminder.snooze': async (params) => {
      const until = asRecord(params).untilEpochMs
      if (typeof until !== 'number' || !Number.isFinite(until)) {
        throw new Error('calendar.reminder.snooze requires { untilEpochMs: number }')
      }
      return Calendar.snoozeReminder(
        readId(params, 'reminderId', 'calendar.reminder.snooze'),
        readId(params, 'recurrenceId', 'calendar.reminder.snooze'),
        until,
      )
    },
  },
  subscriptions: {
    // Genuine push: Kotlin collectors over the relations' Flows (and the two preferences), cancelled
    // by the returned lambda. ⚠ `fail` is wired through, so a dead collector ends the subscriber's
    // stream with a terminal error instead of freezing its screen.
    'calendar.list.observe': (_params, emit, fail) => Calendar.observeCalendars((r) => emit(r), fail),
    // ⚠⚠ Hidden calendars INCLUDED (the native contract): filter with `calendar.hidden.observe`.
    'calendar.occurrences.observe': (params, emit, fail) => {
      const w = readWindow(params, 'calendar.occurrences.observe')
      return Calendar.observeOccurrences(w.from, w.to, w.readerZone, (r) => emit(r), fail)
    },
    'calendar.hidden.observe': (_params, emit, fail) => Calendar.observeHiddenCalendars((r) => emit(r), fail),
    'calendar.default.observe': (_params, emit, fail) => Calendar.observeDefaultCalendar((r) => emit(r), fail),
    'calendar.subscriptions.observe': (_params, emit, fail) =>
      Calendar.observeSubscriptions((r) => emit(r), fail),
  },
}

const CIVIL_DATE = /^\d{4}-\d{2}-\d{2}$/

// A window is two civil dates plus an optional reader zone ('' = the browser's own). ⚠ The dates
// are required: a missing bound must not read as "the whole calendar", which is not a query a
// recurring series can answer.
function readWindow(params: unknown, op: string): { from: string; to: string; readerZone: string } {
  const r = asRecord(params)
  if (typeof r.from !== 'string' || !CIVIL_DATE.test(r.from) || typeof r.to !== 'string' || !CIVIL_DATE.test(r.to)) {
    throw new Error(`${op} requires { from, to } as YYYY-MM-DD`)
  }
  return { from: r.from, to: r.to, readerZone: readOptionalString(params, 'readerZone', op) }
}

function readBoolean(params: unknown, key: string, op: string): boolean {
  const v = asRecord(params)[key]
  if (typeof v !== 'boolean') throw new Error(`${op} requires { ${key}: boolean }`)
  return v
}

function readOptionalBoolean(params: unknown, key: string, op: string): boolean {
  const v = asRecord(params)[key]
  if (v === undefined) return false
  if (typeof v !== 'boolean') throw new Error(`${op}: ${key} must be a boolean`)
  return v
}

function readOptionalString(params: unknown, key: string, op: string): string {
  const v = asRecord(params)[key]
  if (v === undefined) return ''
  if (typeof v !== 'string') throw new Error(`${op}: ${key} must be a string`)
  return v
}

function readInteger(params: unknown, key: string, op: string): number {
  const v = asRecord(params)[key]
  if (typeof v !== 'number' || !Number.isInteger(v)) throw new Error(`${op} requires { ${key}: integer }`)
  return v
}

function readMinutes(params: unknown): number[] {
  const v = asRecord(params).minutesBefore
  if (!Array.isArray(v) || !v.every((m: unknown) => typeof m === 'number' && Number.isInteger(m))) {
    throw new Error('calendar.setReminders requires { minutesBefore: integer[] }')
  }
  return [...(v as number[])]
}

// ⚠ Every key required, rebuilt field by field so nothing extra crosses: a missing `endLocal` or
// `recurrenceRule` read as '' would silently change what is saved (a default duration, a one-off).
function readDraft(params: unknown, op: string): EventDraft {
  const d = asRecord(asRecord(params).draft)
  const str = (key: string): string => {
    const v = d[key]
    if (typeof v !== 'string') throw new Error(`${op} requires draft.${key}: string`)
    return v
  }
  if (typeof d.allDay !== 'boolean') throw new Error(`${op} requires draft.allDay: boolean`)
  return {
    eventId: str('eventId'),
    calendarId: str('calendarId'),
    title: str('title'),
    startLocal: str('startLocal'),
    endLocal: str('endLocal'),
    timeZoneId: str('timeZoneId'),
    allDay: d.allDay,
    location: str('location'),
    notes: str('notes'),
    recurrenceRule: str('recurrenceRule'),
  }
}
