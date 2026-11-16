// The dashboard's ranges and metrics (native's `ChartRange` switcher, without Day: today is its own card).
import type { FitGoals } from '@tenshu/sdk/fit'
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

/** The metric's daily goal: steps and active minutes have one; distance and energy none (0). */
export function goalFor(metric: Metric, goals: FitGoals | null): number {
  if (!goals) return 0
  return metric === 'steps' ? goals.steps : metric === 'activeMinutes' ? goals.activeMinutes : 0
}

/** `m=steps&r=month` ↔ the metric page's state; anything unknown falls back to steps / week. */
export function metricRoute(fragment: string): { metric: Metric; range: Range } {
  const params = new URLSearchParams(fragment)
  const m = params.get('m') as Metric | null
  const r = params.get('r') as Range | null
  return { metric: m && METRICS.includes(m) ? m : 'steps', range: r && RANGES.includes(r) ? r : 'week' }
}

export function metricFragment(metric: Metric, range: Range): string {
  return new URLSearchParams({ m: metric, r: range }).toString()
}
