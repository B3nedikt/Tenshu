// Wall-clock helpers the overview card loaders share (docs/proposals/web-apex-home.md §3.6).
// Everything is in the BROWSER's zone — the overview shows the reader's day.
// ⚠ No `@tenshu/sdk` imports: registry files must stay importable without the Kotlin bundle.

const pad = (n: number) => String(n).padStart(2, '0')

/** `YYYY-MM-DD` of [date] in the browser's zone. */
export function civilDate(date: Date): string {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

/** `YYYY-MM-DDTHH:mm` of [ms] in the browser's zone. */
export function localDateTime(ms: number): string {
  const d = new Date(ms)
  return `${civilDate(d)}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}

/** [date] moved by [days] calendar days (DST-safe: works on the date, not on 24 h steps). */
export function addDays(date: Date, days: number): Date {
  const d = new Date(date)
  d.setDate(d.getDate() + days)
  return d
}

/** The browser's IANA zone ('' when the platform cannot say — every SDK read treats '' as "mine"). */
export function browserZone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone ?? ''
  } catch {
    return ''
  }
}
