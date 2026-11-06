// Pure chart geometry, ported from native `ChartMath` (`fit-shared-logic`, fit-app.md D8), and the
// dashboard's day window. The SVG components are thin renderers over these, so the math is tested
// without a DOM. Outputs are fractions (0..1); y follows the SVG convention (0 = top).
import type { FitDay, FitHrPoint } from '@tenshu/sdk/fit'
import { datesBetween } from './dates'

export interface NormalizedPoint {
  readonly x: number
  readonly y: number
}

const clamp01 = (v: number) => Math.min(1, Math.max(0, v))

/** Goal-ring fraction (`ChartMath.ringSweep`). A non-positive goal is an empty ring. */
export function ringSweep(value: number, goal: number): number {
  if (goal <= 0) return 0
  return clamp01(value / goal)
}

/** Bars against `max(values, goal)`, so the goal line always fits (`ChartMath.barHeights`). */
export function barHeights(values: readonly number[], goal: number): number[] {
  if (values.length === 0) return []
  const reference = Math.max(...values, goal, 1)
  return values.map((v) => clamp01(v / reference))
}

/** The goal line on the same axis as [barHeights] (`ChartMath.goalLineFraction`). */
export function goalLineFraction(values: readonly number[], goal: number): number {
  if (goal <= 0) return 0
  const reference = Math.max(values.length ? Math.max(...values) : 0, goal, 1)
  return clamp01(goal / reference)
}

/**
 * The HR curve (`ChartMath.hrPolyline`): x = elapsed fraction, y over the series' own min/max with
 * [headroomBpm] of padding, so the curve never kisses the plot edges.
 */
export function hrPolyline(series: readonly FitHrPoint[], headroomBpm = 8): NormalizedPoint[] {
  if (series.length === 0) return []
  const duration = Math.max(series[series.length - 1]!.tSec, 1)
  const lo = Math.min(...series.map((p) => p.bpm)) - headroomBpm
  const hi = Math.max(...series.map((p) => p.bpm)) + headroomBpm
  const span = Math.max(hi - lo, 1)
  return series.map((p) => ({ x: clamp01(p.tSec / duration), y: 1 - clamp01((p.bpm - lo) / span) }))
}

/** The average-HR guide line on [hrPolyline]'s axis (`ChartMath.hrAverageY`). */
export function hrAverageY(series: readonly FitHrPoint[], avgBpm: number, headroomBpm = 8): number {
  if (series.length === 0) return 0.5
  const lo = Math.min(...series.map((p) => p.bpm)) - headroomBpm
  const hi = Math.max(...series.map((p) => p.bpm)) + headroomBpm
  const span = Math.max(hi - lo, 1)
  return 1 - clamp01((avgBpm - lo) / span)
}

/** Whole-number daily average; zero for an empty window (`ChartMath.dailyAverage`). */
export function dailyAverage(values: readonly number[]): number {
  return values.length === 0 ? 0 : Math.floor(values.reduce((a, b) => a + b, 0) / values.length + 0.5)
}

/** Percent change against the previous window; `null` without one (`FitFormat.weekDeltaPercent`). */
export function deltaPercent(current: number, previous: number): number | null {
  if (previous <= 0) return null
  return Math.round(((current - previous) * 100) / previous)
}

/**
 * Every date of `from`..`to` with its aggregate. ⚠ The SDK answers only the days that hold a stored
 * row or a workout (the shared projection's shape), so a date it did not name is a day with nothing
 * recorded — a real zero, from a read that SUCCEEDED. A failed read never reaches here.
 */
export function windowDays(days: readonly FitDay[], from: string, to: string): FitDay[] {
  const byDate = new Map(days.map((d) => [d.date, d]))
  return datesBetween(from, to).map(
    (date) => byDate.get(date) ?? { date, steps: 0, distanceM: 0, activeEnergyKj: 0, activeMinutes: 0 },
  )
}

export function sum(values: readonly number[]): number {
  return values.reduce((a, b) => a + b, 0)
}
