// What a workout's route stream holds beyond the line on the map (fit-web-parity.md §2.2): elevation,
// pace and per-km splits, derived in the tab. Native reads `ascentM` / `laps` from stored fields its
// projection does not fill yet (`FitProjection.kt`: "deferred"), and the web SDK carries neither — but
// every route point has `tSec`, `lat/lng` and `altM`, which is all these need. Pure, tested.
import type { FitHrPoint, FitRoutePoint } from '@tenshu/sdk/fit'
import type { SeriesPoint } from '../components/SeriesChart'

const EARTH_M = 6_371_008.8
const METRES_PER_MILE = 1609.344

/** Great-circle metres between two points (haversine). */
export function metresBetween(a: { lat: number; lng: number }, b: { lat: number; lng: number }): number {
  const rad = Math.PI / 180
  const dLat = (b.lat - a.lat) * rad
  const dLng = (b.lng - a.lng) * rad
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(a.lat * rad) * Math.cos(b.lat * rad) * Math.sin(dLng / 2) ** 2
  return 2 * EARTH_M * Math.asin(Math.min(1, Math.sqrt(h)))
}

/** Distance from the start at every route point, metres. */
export function cumulativeMetres(route: readonly FitRoutePoint[]): number[] {
  const out: number[] = []
  let total = 0
  route.forEach((p, i) => {
    if (i > 0) total += metresBetween(route[i - 1]!, p)
    out.push(total)
  })
  return out
}

/** The elevation profile: the points that carry an altitude. Empty when none does. */
export function elevationSeries(route: readonly FitRoutePoint[]): SeriesPoint[] {
  return route.filter((p) => p.altM !== null).map((p) => ({ t: p.tSec, v: p.altM! }))
}

/**
 * Metres climbed. ⚠ With hysteresis: a climb counts only once the altitude has risen [thresholdM]
 * above the last low point, so GPS altitude noise (±several m on a flat run) never adds up to a hill.
 */
export function ascentMetres(route: readonly FitRoutePoint[], thresholdM = 3): number | null {
  const alts = route.map((p) => p.altM).filter((a): a is number => a !== null)
  if (alts.length < 2) return null
  let ascent = 0
  let low = alts[0]!
  let high = alts[0]!
  let climbing = false
  for (const a of alts.slice(1)) {
    if (climbing) {
      if (a > high) {
        ascent += a - high
        high = a
      } else if (high - a >= thresholdM) {
        climbing = false
        low = a
      }
    } else if (a < low) {
      low = a
    } else if (a - low >= thresholdM) {
      climbing = true
      ascent += a - low
      high = a
    }
  }
  return Math.round(ascent)
}

/**
 * Pace (seconds per km) over a trailing [windowSec] window, at each route point from the window's
 * end on. ⚠ A stretch slower than [stopPaceSecPerKm] — or than twice the workout's own median pace —
 * (standing at a light, the slow metres around it) is a GAP, not a spike that flattens the rest of the
 * chart: a fixed threshold alone let the walk-up to a stop stretch a 5 min/km run's axis to 20.
 */
export function paceSeries(route: readonly FitRoutePoint[], windowSec = 30, stopPaceSecPerKm = 1_200): SeriesPoint[] {
  const dist = cumulativeMetres(route)
  const out: SeriesPoint[] = []
  let from = 0
  for (let i = 1; i < route.length; i++) {
    while (from < i && route[i]!.tSec - route[from + 1]!.tSec >= windowSec) from++
    const dt = route[i]!.tSec - route[from]!.tSec
    const dm = dist[i]! - dist[from]!
    if (dt < windowSec / 2 || dm <= 0) continue
    const pace = (dt / dm) * 1000
    if (pace <= stopPaceSecPerKm) out.push({ t: route[i]!.tSec, v: pace })
  }
  if (out.length < 3) return out
  const sorted = out.map((p) => p.v).sort((a, b) => a - b)
  const median = sorted[sorted.length >> 1]!
  return out.filter((p) => p.v <= median * 2)
}

/** One split: the [index]th km (or mile), how long it took, and its average heart rate. */
export interface Split {
  readonly index: number
  readonly distanceM: number
  readonly durationSec: number
  /** Seconds per km, whatever the split's unit — the pace formatter converts (`paceText`). */
  readonly paceSecPerKm: number
  readonly avgHr: number | null
  /** A trailing part-split shorter than the unit. */
  readonly partial: boolean
}

/**
 * Splits per km (per mile for [imperial]), as native's lap table: each boundary's time interpolated
 * between the two route points that straddle it; a trailing part-split marked [partial] when it is
 * at least a tenth of the unit (a few metres past the last mark are not a lap).
 */
export function splits(route: readonly FitRoutePoint[], hr: readonly FitHrPoint[], imperial = false): Split[] {
  if (route.length < 2) return []
  const unit = imperial ? METRES_PER_MILE : 1000
  const dist = cumulativeMetres(route)
  const total = dist[dist.length - 1]!
  const marks: number[] = [route[0]!.tSec]
  let next = unit
  for (let i = 1; i < route.length && next <= total; i++) {
    while (next <= dist[i]! && next <= total) {
      const span = dist[i]! - dist[i - 1]!
      const f = span > 0 ? (next - dist[i - 1]!) / span : 0
      marks.push(route[i - 1]!.tSec + f * (route[i]!.tSec - route[i - 1]!.tSec))
      next += unit
    }
  }
  const out: Split[] = []
  for (let k = 1; k < marks.length; k++) {
    const durationSec = marks[k]! - marks[k - 1]!
    out.push({ index: k, distanceM: unit, durationSec, paceSecPerKm: (durationSec / unit) * 1000, avgHr: avgHrBetween(hr, marks[k - 1]!, marks[k]!), partial: false })
  }
  const rest = total - (marks.length - 1) * unit
  if (rest >= unit / 10) {
    const start = marks[marks.length - 1]!
    const end = route[route.length - 1]!.tSec
    const durationSec = end - start
    out.push({
      index: marks.length,
      distanceM: rest,
      durationSec,
      paceSecPerKm: (durationSec / rest) * 1000,
      avgHr: avgHrBetween(hr, start, end, true),
      partial: true,
    })
  }
  return out
}

// Half-open [from, to): a sample on a boundary counts in one split, not both. The last split closes.
function avgHrBetween(hr: readonly FitHrPoint[], from: number, to: number, last = false): number | null {
  const inside = hr.filter((p) => p.tSec >= from && (last ? p.tSec <= to : p.tSec < to))
  if (inside.length === 0) return null
  return Math.round(inside.reduce((s, p) => s + p.bpm, 0) / inside.length)
}

/** The fastest FULL split — native highlights it; a partial one never counts. */
export function bestSplitIndex(all: readonly Split[]): number | null {
  const full = all.filter((s) => !s.partial)
  if (full.length < 2) return null
  return full.reduce((best, s) => (s.paceSecPerKm < best.paceSecPerKm ? s : best)).index
}
