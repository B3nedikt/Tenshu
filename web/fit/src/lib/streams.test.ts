import { describe, expect, it } from 'vitest'
import { ascentMetres, bestSplitIndex, cumulativeMetres, elevationSeries, metresBetween, paceSeries, splits } from './streams'

// Due north along a meridian: 0.001° of latitude ≈ 111.2 m.
const M_PER_MILLIDEG = metresBetween({ lat: 0, lng: 0 }, { lat: 0.001, lng: 0 })
const north = (n: number, secEach: number, alt: (i: number) => number | null = () => null) =>
  Array.from({ length: n }, (_, i) => ({ tSec: i * secEach, lat: i * 0.001, lng: 0, altM: alt(i) }))

describe('distance', () => {
  it('haversine metres, summed along the route', () => {
    expect(M_PER_MILLIDEG).toBeGreaterThan(111)
    expect(M_PER_MILLIDEG).toBeLessThan(111.4)
    const d = cumulativeMetres(north(4, 10))
    expect(d[0]).toBe(0)
    expect(d[3]!).toBeCloseTo(3 * M_PER_MILLIDEG, 3)
  })
})

describe('splits', () => {
  it('one per km, the boundary time interpolated, then a partial when it is a tenth of a km or more', () => {
    // 25 points × 111.2 m ≈ 2.67 km at 30 s per point (≈ 4:30 /km).
    const route = north(25, 30)
    const hr = route.map((p, i) => ({ tSec: p.tSec, bpm: 130 + i }))
    const s = splits(route, hr)
    expect(s.map((x) => x.partial)).toEqual([false, false, true])
    const perKm = (30 / M_PER_MILLIDEG) * 1000
    expect(s[0]!.durationSec).toBeCloseTo(perKm, 3)
    expect(s[0]!.paceSecPerKm).toBeCloseTo(perKm, 3)
    expect(s[2]!.distanceM).toBeCloseTo(24 * M_PER_MILLIDEG - 2000, 3)
    expect(s[2]!.paceSecPerKm).toBeCloseTo(perKm, 3)
    expect(s[0]!.avgHr).toBeGreaterThan(130)
  })

  it('no partial for a few metres past the last mark', () => {
    const route = north(10, 30) // 9 × 111.2 ≈ 1.0008 km
    expect(splits(route, []).map((x) => x.partial)).toEqual([false])
  })

  it('per mile in imperial, the pace still per km (the formatter converts)', () => {
    const route = north(30, 30) // ≈ 3.2 km ≈ 2 mi
    const s = splits(route, [], true)
    expect(s.filter((x) => !x.partial)).toHaveLength(2)
    expect(s[0]!.paceSecPerKm).toBeCloseTo((30 / M_PER_MILLIDEG) * 1000, 3)
  })

  it('the best FULL split is the fastest; a fast partial never counts', () => {
    expect(
      bestSplitIndex([
        { index: 1, distanceM: 1000, durationSec: 300, paceSecPerKm: 300, avgHr: null, partial: false },
        { index: 2, distanceM: 1000, durationSec: 280, paceSecPerKm: 280, avgHr: null, partial: false },
        { index: 3, distanceM: 200, durationSec: 40, paceSecPerKm: 200, avgHr: null, partial: true },
      ]),
    ).toBe(2)
  })
})

describe('elevation and ascent', () => {
  it('only the points that carry an altitude; none, no profile and no ascent', () => {
    expect(elevationSeries(north(3, 10, (i) => (i === 1 ? null : 100 + i)))).toEqual([
      { t: 0, v: 100 },
      { t: 20, v: 102 },
    ])
    expect(elevationSeries(north(3, 10))).toEqual([])
    expect(ascentMetres(north(3, 10))).toBeNull()
  })

  it('a climb counts; GPS noise under the threshold does not add up', () => {
    // Up 50 m, down, up 20 m.
    const hill = [100, 110, 130, 150, 140, 120, 125, 140]
    expect(ascentMetres(north(hill.length, 10, (i) => hill[i]!))).toBe(70)
    // ±2 m jitter around flat ground: no ascent at all.
    const flat = [100, 102, 100, 102, 100, 102, 100]
    expect(ascentMetres(north(flat.length, 10, (i) => flat[i]!))).toBe(0)
    // A 2 m dip near the top is noise, not a descent: the climb resumes from its high, 51 m — not 53.
    const dip = [100, 150, 148, 151]
    expect(ascentMetres(north(dip.length, 10, (i) => dip[i]!))).toBe(51)
  })
})

describe('pace', () => {
  it('seconds per km over a trailing window; a standstill is a gap, not a spike', () => {
    const route = [
      ...north(10, 30), // moving: ≈ 270 s/km
      // standing for 3 minutes at the last point
      ...[1, 2, 3, 4, 5, 6].map((k) => ({ tSec: 270 + k * 30, lat: 0.009, lng: 0, altM: null })),
    ]
    const pace = paceSeries(route)
    expect(pace.length).toBeGreaterThan(5)
    const moving = pace.filter((p) => p.t <= 270)
    expect(Math.round(moving[moving.length - 1]!.v)).toBe(Math.round((30 / M_PER_MILLIDEG) * 1000))
    expect(pace.some((p) => p.t > 300)).toBe(false)
  })

  it('the slow metres around a stop (over twice the median pace) are a gap too, though under 20 min/km', () => {
    const route = [
      ...north(12, 30), // ≈ 4:30 /km
      // ≈ 11:15 /km for a minute: walking up to a light — under the fixed threshold, over 2× median
      ...[1, 2].map((k) => ({ tSec: 330 + k * 30, lat: 0.011 + k * 0.0004, lng: 0, altM: null })),
      ...north(10, 30).map((p, k) => ({ ...p, tSec: 390 + (k + 1) * 30, lat: 0.0118 + (k + 1) * 0.001 })),
    ]
    const pace = paceSeries(route)
    expect(Math.max(...pace.map((p) => p.v))).toBeLessThan(600)
  })

  it('a shuffle slower than 20 min/km (queueing, a light) is a gap too, not a spike that flattens the chart', () => {
    const route = [
      ...north(10, 30),
      // 5 m every 30 s ≈ 100 min/km
      ...[1, 2, 3, 4].map((k) => ({ tSec: 270 + k * 30, lat: 0.009 + k * 0.000045, lng: 0, altM: null })),
    ]
    expect(paceSeries(route).some((p) => p.t > 300)).toBe(false)
  })
})

describe('split heart rate', () => {
  it('a sample on a boundary counts in one split, not both', () => {
    const route = north(19, 30) // two full km
    const boundary = (30 / M_PER_MILLIDEG) * 1000 // the first km's end, s
    const hr = [{ tSec: 0, bpm: 100 }, { tSec: boundary, bpm: 200 }, { tSec: boundary + 10, bpm: 200 }]
    const s = splits(route, hr)
    expect(s[0]!.avgHr).toBe(100)
    expect(s[1]!.avgHr).toBe(200)
  })
})
