// Civil-date arithmetic and the view grids, ported from `calendar-shared-logic` (`MonthGrid`,
// `WeekGrid`). A civil date is `YYYY-MM-DD` (calendar-app.md §3 D3), the same string the bridge
// takes for a window.
//
// ⚠ Day arithmetic runs on UTC midnights, never on local `Date`s: a local midnight plus 24 h lands
// at 23:00 or 01:00 across a DST change, and a grid built that way repeats or skips a day.

export type CivilDate = string

const CIVIL = /^(\d{4})-(\d{2})-(\d{2})$/

export function parseCivil(date: CivilDate): { year: number; month: number; day: number } {
  const m = CIVIL.exec(date)
  if (!m) throw new Error(`not a civil date: ${date}`)
  return { year: Number(m[1]), month: Number(m[2]), day: Number(m[3]) }
}

export function civil(year: number, month: number, day: number): CivilDate {
  const d = new Date(Date.UTC(year, month - 1, day))
  return utcCivil(d)
}

function utcCivil(d: Date): CivilDate {
  const y = String(d.getUTCFullYear()).padStart(4, '0')
  const mo = String(d.getUTCMonth() + 1).padStart(2, '0')
  const da = String(d.getUTCDate()).padStart(2, '0')
  return `${y}-${mo}-${da}`
}

function utcOf(date: CivilDate): Date {
  const { year, month, day } = parseCivil(date)
  return new Date(Date.UTC(year, month - 1, day))
}

export function addDays(date: CivilDate, days: number): CivilDate {
  const d = utcOf(date)
  d.setUTCDate(d.getUTCDate() + days)
  return utcCivil(d)
}

/** Whole days from `a` to `b` (negative when `b` is earlier). */
export function daysBetween(a: CivilDate, b: CivilDate): number {
  return Math.round((utcOf(b).getTime() - utcOf(a).getTime()) / 86_400_000)
}

/** 1 = Monday … 7 = Sunday (ISO), like kotlinx-datetime's `isoDayNumber`. */
export function isoWeekday(date: CivilDate): number {
  const js = utcOf(date).getUTCDay()
  return js === 0 ? 7 : js
}

/** The month `delta` months from `year`/`month` (`shiftMonth`). */
export function shiftMonth(year: number, month: number, delta: number): { year: number; month: number } {
  const total = year * 12 + (month - 1) + delta
  return { year: Math.floor(total / 12), month: (((total % 12) + 12) % 12) + 1 }
}

/** The civil date of an instant on THIS browser's clock — the reader's zone. */
export function localCivil(epochMs: number): CivilDate {
  const d = new Date(epochMs)
  return civil(d.getFullYear(), d.getMonth() + 1, d.getDate())
}

/** Minutes since local midnight of an instant, on this browser's clock. */
export function localMinuteOfDay(epochMs: number): number {
  const d = new Date(epochMs)
  return d.getHours() * 60 + d.getMinutes()
}

export function today(now: number = Date.now()): CivilDate {
  return localCivil(now)
}

export interface MonthCell {
  readonly date: CivilDate
  // False for the leading/trailing days borrowed from the adjacent months.
  readonly inMonth: boolean
}

export const MONTH_ROWS = 6

/**
 * The 6×7 month grid (`MonthGrid.of`). ⚠ Always six rows, so paging through the year never makes
 * the screen jump. `weekStart` is ISO (1 = Monday) and a locale fact, never a constant.
 */
export function monthGrid(year: number, month: number, weekStart: number): MonthCell[] {
  const first = civil(year, month, 1)
  const lead = (isoWeekday(first) - weekStart + 7) % 7
  const start = addDays(first, -lead)
  return Array.from({ length: MONTH_ROWS * 7 }, (_, i) => {
    const date = addDays(start, i)
    const p = parseCivil(date)
    return { date, inMonth: p.month === month && p.year === year }
  })
}

/** The seven days of the week `date` falls in (`WeekGrid.containing`). */
export function weekContaining(date: CivilDate, weekStart: number): CivilDate[] {
  const back = (isoWeekday(date) - weekStart + 7) % 7
  const first = addDays(date, -back)
  return Array.from({ length: 7 }, (_, i) => addDays(first, i))
}

/**
 * The locale's first day of the week, ISO-numbered. `Intl.Locale#getWeekInfo` (or the older
 * `weekInfo` getter) where the engine has it; Monday otherwise, the ISO default.
 */
export function weekStartFor(language: string): number {
  try {
    const locale = new Intl.Locale(language) as Intl.Locale & {
      getWeekInfo?: () => { firstDay: number }
      weekInfo?: { firstDay: number }
    }
    const info = locale.getWeekInfo?.() ?? locale.weekInfo
    if (info && info.firstDay >= 1 && info.firstDay <= 7) return info.firstDay
  } catch {
    // An unparseable language tag: fall through.
  }
  return 1
}

/** `YYYY-MM-DDTHH:MM` from its parts — the civil date-time the bridge takes. */
export function civilDateTime(date: CivilDate, minuteOfDay: number): string {
  const h = String(Math.floor(minuteOfDay / 60)).padStart(2, '0')
  const m = String(minuteOfDay % 60).padStart(2, '0')
  return `${date}T${h}:${m}`
}

/** `HH:MM` → minutes, or null. */
export function parseTime(value: string): number | null {
  const m = /^(\d{2}):(\d{2})$/.exec(value)
  if (!m) return null
  const h = Number(m[1])
  const mi = Number(m[2])
  if (h > 23 || mi > 59) return null
  return h * 60 + mi
}

export function formatTime(minuteOfDay: number): string {
  return `${String(Math.floor(minuteOfDay / 60)).padStart(2, '0')}:${String(minuteOfDay % 60).padStart(2, '0')}`
}

/** The date and minute-of-day of a civil date-time (`2026-10-26T09:00`; a bare date is 00:00). */
export function splitLocal(local: string): { date: CivilDate; minute: number } {
  const date = local.slice(0, 10)
  const time = local.length >= 16 ? parseTime(local.slice(11, 16)) : 0
  return { date, minute: time ?? 0 }
}

// A civil date at noon UTC, for `Intl` formatting with `timeZone: 'UTC'`: the label of a date is
// never a question about any zone.
export function civilAsUtcDate(date: CivilDate): Date {
  const { year, month, day } = parseCivil(date)
  return new Date(Date.UTC(year, month - 1, day, 12))
}

/** A civil date as the reader's language writes it ("26 Oct 2026"), zone-free. */
export function formatCivil(language: string, date: CivilDate, options: Intl.DateTimeFormatOptions = { dateStyle: 'medium' }): string {
  return new Intl.DateTimeFormat(language, { ...options, timeZone: 'UTC' }).format(civilAsUtcDate(date))
}
