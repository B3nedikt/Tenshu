// History's rows as native shows them (fit-web-parity.md §2.4): a summary at the top of each week
// (workouts, distance, time), a header for each day, then that day's workouts. Pure: the virtualised
// list (`HistoryView`) lays out exactly these, at their own heights.
//
// Grouped by `startLocal`'s date — the SDK's wall clock in the READER's zone (the bridge asks with zone
// '', the browser's), so it is ordered as `startMs` is and days follow the browser's calendar, as the
// dashboard's do. Weeks start on Monday (ISO).
// ⚠ A week's summary counts the LOADED workouts: the oldest loaded week can still be cut by the page
// boundary, and grows as the next page lands (it loads before it scrolls into view).
import type { FitWorkout } from '@tenshu/sdk/fit'
import { addDays } from './dates'

export const WEEK_HEIGHT = 52
export const DAY_HEIGHT = 32
export const WORKOUT_HEIGHT = 64
// Rendered above and below the viewport, so a fast scroll never shows blank rows.
const OVERSCAN_PX = 480

export type HistoryRow =
  | { readonly kind: 'week'; readonly key: string; readonly weekStart: string; readonly count: number; readonly distanceM: number; readonly durationSec: number }
  | { readonly kind: 'day'; readonly key: string; readonly date: string }
  | { readonly kind: 'workout'; readonly key: string; readonly workout: FitWorkout; readonly index: number }

export interface HistoryLayout {
  readonly rows: readonly HistoryRow[]
  /** Each row's top, px; one more entry than [rows]: the total height. */
  readonly tops: readonly number[]
  readonly height: number
}

export function heightOf(row: HistoryRow): number {
  return row.kind === 'week' ? WEEK_HEIGHT : row.kind === 'day' ? DAY_HEIGHT : WORKOUT_HEIGHT
}

/** The Monday of [date]'s ISO week. */
export function weekStartOf(date: string): string {
  const [y, m, d] = date.split('-').map(Number) as [number, number, number]
  const weekday = new Date(Date.UTC(y, m - 1, d)).getUTCDay() // 0 = Sunday
  return addDays(date, -((weekday + 6) % 7))
}

function localDate(w: FitWorkout): string {
  return w.startLocal.slice(0, 10)
}

/** The rows for [workouts], newest first as the pages give them. */
export function historyLayout(workouts: readonly FitWorkout[]): HistoryLayout {
  const weeks = new Map<string, { count: number; distanceM: number; durationSec: number }>()
  for (const w of workouts) {
    const week = weekStartOf(localDate(w))
    const s = weeks.get(week) ?? { count: 0, distanceM: 0, durationSec: 0 }
    s.count += 1
    s.distanceM += w.distanceM ?? 0
    s.durationSec += w.durationSec
    weeks.set(week, s)
  }
  const rows: HistoryRow[] = []
  let week: string | null = null
  let day: string | null = null
  workouts.forEach((w, index) => {
    const date = localDate(w)
    const ws = weekStartOf(date)
    if (ws !== week) {
      week = ws
      const s = weeks.get(ws)!
      rows.push({ kind: 'week', key: `week-${ws}`, weekStart: ws, ...s })
      day = null
    }
    if (date !== day) {
      day = date
      rows.push({ kind: 'day', key: `day-${date}`, date })
    }
    rows.push({ kind: 'workout', key: w.workoutId, workout: w, index })
  })
  const tops: number[] = [0]
  for (const r of rows) tops.push(tops[tops.length - 1]! + heightOf(r))
  return { rows, tops, height: tops[tops.length - 1]! }
}

/** The rows within [scrollTop, scrollTop + viewport] plus overscan: `[first, last]` indices. */
export function visibleRows(layout: HistoryLayout, scrollTop: number, viewport: number): { first: number; last: number } {
  const n = layout.rows.length
  if (n === 0) return { first: 0, last: -1 }
  const from = Math.max(0, scrollTop - OVERSCAN_PX)
  const to = scrollTop + viewport + OVERSCAN_PX
  // The first row whose bottom is below `from`.
  let lo = 0
  let hi = n - 1
  while (lo < hi) {
    const mid = (lo + hi) >> 1
    if (layout.tops[mid + 1]! <= from) lo = mid + 1
    else hi = mid
  }
  let last = lo
  while (last < n - 1 && layout.tops[last + 1]! < to) last++
  return { first: lo, last }
}

/** The top of the [index]th workout's row (for a scroll to it). */
export function topOfWorkout(layout: HistoryLayout, index: number): number {
  const at = layout.rows.findIndex((r) => r.kind === 'workout' && r.index === index)
  return at < 0 ? 0 : layout.tops[at]!
}
