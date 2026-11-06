// The dashboard's ranges and metrics (native's `ChartRange` switcher, without Day: today is its own card).
import { addDays } from './dates'

export type Range = 'week' | 'month' | 'year'
export const RANGES: readonly Range[] = ['week', 'month', 'year']
export const RANGE_DAYS: Record<Range, number> = { week: 7, month: 30, year: 365 }

export type Metric = 'steps' | 'activeMinutes' | 'distanceM' | 'activeEnergyKj'
export const METRICS: readonly Metric[] = ['steps', 'activeMinutes', 'distanceM', 'activeEnergyKj']

// The window read for a range: the range itself plus, where it fits in the SDK's 400-day bound
// (`MAX_AGGREGATE_WINDOW_DAYS`), the range before it, for the change figure. A year reads one year.
export function windowFor(range: Range, today: string, maxDays = 400): { from: string; to: string; days: number; withPrevious: boolean } {
  const days = RANGE_DAYS[range]
  const withPrevious = days * 2 <= maxDays
  return { from: addDays(today, -(days * (withPrevious ? 2 : 1) - 1)), to: today, days, withPrevious }
}
