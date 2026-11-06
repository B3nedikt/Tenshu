// Calendar dates ('YYYY-MM-DD') in the browser's zone — the zone the SDK groups days in when the app
// passes '' (`proxy.ts` ZONE). Arithmetic is on the calendar, never on milliseconds, so a DST change
// cannot move a day.

export function isoDate(year: number, month: number, day: number): string {
  return `${String(year).padStart(4, '0')}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`
}

// Today in the browser's own zone.
export function localToday(now: Date = new Date()): string {
  return isoDate(now.getFullYear(), now.getMonth() + 1, now.getDate())
}

export function addDays(date: string, days: number): string {
  const [y, m, d] = date.split('-').map(Number)
  const at = new Date(Date.UTC(y!, m! - 1, d! + days))
  return isoDate(at.getUTCFullYear(), at.getUTCMonth() + 1, at.getUTCDate())
}

// Every date from `from` to `to`, inclusive, oldest first.
export function datesBetween(from: string, to: string): string[] {
  const out: string[] = []
  for (let d = from; d <= to; d = addDays(d, 1)) out.push(d)
  return out
}
