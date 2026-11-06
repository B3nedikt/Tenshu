// Where an occurrence lands in the READER's zone (calendar-app.md §Web scope W5): which days it
// touches and, for a timed one, which minutes of each. Timed occurrences are placed by their
// instants (`startEpochMs`/`endEpochMs`, resolved by run A in the event's own zone) on this
// browser's clock; an all-day one is a civil date range and belongs to no zone at all.
//
// ⚠ The bridge's window is civil dates intersected in each event's OWN zone, so a window read for
// exactly the visible days can miss an event that, in the reader's zone, falls on its edge.
// `queryWindow` pads it by a day each side and `placeAll` keeps only what lands on a visible day.
import type { Occurrence } from '@tenshu/sdk/calendar'
import { addDays, daysBetween, localCivil, localMinuteOfDay, type CivilDate } from './dates'

export interface Placed {
  readonly occurrence: Occurrence
  readonly date: CivilDate
  // Minutes from the day's midnight; all-day = 0..1440.
  readonly start: number
  readonly end: number
  readonly allDay: boolean
  // A slice of something longer than this day.
  readonly continuesBefore: boolean
  readonly continuesAfter: boolean
}

export function queryWindow(from: CivilDate, to: CivilDate): { from: CivilDate; to: CivilDate } {
  return { from: addDays(from, -1), to: addDays(to, 1) }
}

/** The day slices of one occurrence, in the reader's zone. */
export function slices(o: Occurrence): Placed[] {
  if (o.allDay) {
    const first = o.startLocal.slice(0, 10)
    // An exclusive end date; a same-day (or missing) end is one day.
    const endExclusive = o.endLocal.slice(0, 10)
    const span = Math.max(1, daysBetween(first, endExclusive))
    return Array.from({ length: span }, (_, i) => ({
      occurrence: o,
      date: addDays(first, i),
      start: 0,
      end: 1440,
      allDay: true,
      continuesBefore: i > 0,
      continuesAfter: i < span - 1,
    }))
  }
  const startDate = localCivil(o.startEpochMs)
  const startMinute = localMinuteOfDay(o.startEpochMs)
  const endMs = Math.max(o.endEpochMs, o.startEpochMs)
  let endDate = localCivil(endMs)
  let endMinute = localMinuteOfDay(endMs)
  // Ending exactly at midnight ends the previous day, not a sliver of the next.
  if (endMinute === 0 && endDate !== startDate) {
    endDate = addDays(endDate, -1)
    endMinute = 1440
  }
  const span = daysBetween(startDate, endDate) + 1
  return Array.from({ length: span }, (_, i) => ({
    occurrence: o,
    date: addDays(startDate, i),
    start: i === 0 ? startMinute : 0,
    end: i === span - 1 ? endMinute : 1440,
    allDay: false,
    continuesBefore: i > 0,
    continuesAfter: i < span - 1,
  }))
}

/**
 * Every slice landing on one of `days`, grouped by day: all-day first, then by start, then title —
 * a stable order, so a re-emission never shuffles a cell.
 */
export function placeAll(occurrences: readonly Occurrence[], days: readonly CivilDate[]): Map<CivilDate, Placed[]> {
  const byDay = new Map<CivilDate, Placed[]>(days.map((d) => [d, []]))
  for (const o of occurrences) {
    for (const s of slices(o)) byDay.get(s.date)?.push(s)
  }
  for (const list of byDay.values()) {
    list.sort(
      (a, b) =>
        Number(b.allDay) - Number(a.allDay) ||
        a.start - b.start ||
        a.occurrence.title.localeCompare(b.occurrence.title) ||
        a.occurrence.eventId.localeCompare(b.occurrence.eventId),
    )
  }
  return byDay
}

/** The occurrence's identity in a list: a series repeats its `eventId`. */
export function occurrenceKey(o: Occurrence): string {
  return `${o.eventId}@${o.recurrenceId}`
}
