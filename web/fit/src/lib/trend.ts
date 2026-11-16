// The web charts' arithmetic (fit-web-parity.md §2.1): axis ticks, the year's month buckets and the
// pointer → bar lookup. Pure, so the SVG components stay thin renderers and this is tested without a
// DOM. The bar geometry itself is native `ChartMath`'s (`lib/chart.ts`).
import type { FitDay } from '@tenshu/sdk/fit'

/**
 * A "nice" axis for values up to [max]: a step of 1, 2 or 5 × 10ⁿ giving about [target] gridlines,
 * and the ceiling rounded up to a whole step. ⚠ Never a 0 ceiling — an all-zero week still draws an
 * axis (0 … 1 step), so the plot has a scale rather than dividing by zero.
 */
export function niceAxis(max: number, target = 3): { step: number; top: number; ticks: number[] } {
  const raw = Math.max(max, 0) / Math.max(target, 1)
  const magnitude = raw > 0 ? 10 ** Math.floor(Math.log10(raw)) : 1
  const step = [1, 2, 5, 10].map((f) => f * magnitude).find((s) => s >= raw) ?? magnitude * 10
  const top = Math.max(step, Math.ceil(Math.max(max, 0) / step) * step)
  const ticks: number[] = []
  for (let v = 0; v <= top + step / 2; v += step) ticks.push(Math.round(v * 1e6) / 1e6)
  return { step, top, ticks }
}

/** One bar of the year view: a calendar month, and the metric's DAILY AVERAGE over its days. */
export interface MonthBucket {
  /** `YYYY-MM` */
  readonly month: string
  readonly average: number
  /** How many of the window's days fell in it — a partial first or current month is still averaged. */
  readonly days: number
}

/**
 * The year as 12 monthly bars (fit-web-parity.md §2.1): each month's daily average over the days of
 * the window it holds, oldest first. ⚠ An average, never a sum — the current month is still under
 * way and the first one is cut by the window, so sums would read as two short months.
 */
export function monthBuckets(days: readonly FitDay[], metric: keyof Omit<FitDay, 'date'>): MonthBucket[] {
  const byMonth = new Map<string, { total: number; days: number }>()
  for (const d of days) {
    const month = d.date.slice(0, 7)
    const bucket = byMonth.get(month) ?? { total: 0, days: 0 }
    bucket.total += d[metric]
    bucket.days += 1
    byMonth.set(month, bucket)
  }
  return [...byMonth.entries()]
    .sort(([a], [b]) => (a < b ? -1 : 1))
    .map(([month, b]) => ({ month, average: Math.round(b.total / b.days), days: b.days }))
}

/** The bar under a pointer at [x] (px from the plot's left edge) in [count] equal slots; null outside. */
export function slotAt(x: number, plotWidth: number, count: number): number | null {
  if (count <= 0 || plotWidth <= 0 || x < 0 || x >= plotWidth) return null
  return Math.min(count - 1, Math.floor((x / plotWidth) * count))
}

/** Which bars carry an x label: every one up to [maxLabels], else every k-th, always the last. */
export function labelledSlots(count: number, maxLabels: number): Set<number> {
  const every = Math.max(1, Math.ceil(count / Math.max(maxLabels, 1)))
  const out = new Set<number>()
  for (let i = count - 1; i >= 0; i -= every) out.add(i)
  return out
}

/** The point of a time series nearest [t] (seconds) — the shared hover cursor of the workout charts. */
export function nearestIndex(times: readonly number[], t: number): number | null {
  if (times.length === 0) return null
  let lo = 0
  let hi = times.length - 1
  while (lo < hi) {
    const mid = (lo + hi) >> 1
    if (times[mid]! < t) lo = mid + 1
    else hi = mid
  }
  if (lo > 0 && Math.abs(times[lo - 1]! - t) <= Math.abs(times[lo]! - t)) return lo - 1
  return lo
}

/**
 * An axis over [min, max] that does NOT start at zero (heart rate, elevation, pace): about [target]
 * nice steps, the ends rounded outward to whole steps, with [pad] of the span as headroom so a line
 * never kisses the plot edge (native `hrPolyline`'s ±8 bpm, generalised).
 */
export function rangeAxis(min: number, max: number, target = 3, pad = 0.08): { lo: number; hi: number; ticks: number[] } {
  // ⚠ At least one unit of span: a flat series (0 m all along) gave a 1e-6 step, rounded to [-0, 0, 0].
  const span0 = Math.max(max - min, Math.abs(max) * 0.05, 1)
  const lo0 = min - span0 * pad
  const hi0 = max + span0 * pad
  const { step } = niceAxis(hi0 - lo0, target)
  const lo = Math.floor(lo0 / step) * step
  const hi = Math.ceil(hi0 / step) * step
  const ticks: number[] = []
  for (let v = lo; v <= hi + step / 2; v += step) ticks.push(Math.round(v * 1e6) / 1e6 || 0)
  return { lo, hi, ticks: [...new Set(ticks)] }
}
