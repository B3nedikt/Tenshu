// Ports of native `ChartMathTest` (`fit-shared-logic`): the same inputs give the same geometry.
import { describe, expect, it } from 'vitest'
import { barHeights, dailyAverage, deltaPercent, goalLineFraction, hrAverageY, hrPolyline, ringSweep, windowDays } from './chart'

describe('ChartMath (ported)', () => {
  it('ringSweep clamps to 0..1 and a non-positive goal is empty', () => {
    expect(ringSweep(15, 30)).toBe(0.5)
    expect(ringSweep(45, 30)).toBe(1)
    expect(ringSweep(10, 0)).toBe(0)
  })

  it('barHeights normalise against max(values, goal), so the goal line fits', () => {
    expect(barHeights([], 30)).toEqual([])
    expect(barHeights([0, 0], 0)).toEqual([0, 0])
    expect(barHeights([15, 30, 60], 30)).toEqual([0.25, 0.5, 1])
    expect(barHeights([10, 20], 40)).toEqual([0.25, 0.5])
    expect(goalLineFraction([10, 20], 40)).toBe(1)
    expect(goalLineFraction([15, 60], 30)).toBe(0.5)
    expect(goalLineFraction([1], 0)).toBe(0)
  })

  it('hrPolyline spans the series’ own range with headroom, y = 0 at the top', () => {
    const points = hrPolyline([
      { tSec: 0, bpm: 100 },
      { tSec: 50, bpm: 140 },
      { tSec: 100, bpm: 120 },
    ])
    expect(points.map((p) => p.x)).toEqual([0, 0.5, 1])
    // axis 92..148: 100 → 1 - 8/56
    expect(points[0]!.y).toBeCloseTo(1 - 8 / 56)
    expect(points[1]!.y).toBeCloseTo(1 - 48 / 56)
    expect(hrAverageY([{ tSec: 0, bpm: 100 }, { tSec: 1, bpm: 140 }], 120)).toBeCloseTo(0.5)
    expect(hrPolyline([])).toEqual([])
    expect(hrAverageY([], 120)).toBe(0.5)
  })

  it('dailyAverage rounds half up; deltaPercent is null without a previous window', () => {
    expect(dailyAverage([])).toBe(0)
    expect(dailyAverage([1, 2])).toBe(2)
    expect(dailyAverage([10, 20, 31])).toBe(20)
    expect(deltaPercent(150, 100)).toBe(50)
    expect(deltaPercent(50, 100)).toBe(-50)
    expect(deltaPercent(10, 0)).toBeNull()
  })

  it('windowDays fills every date of the window, keeping the read’s rows', () => {
    const days = windowDays([{ date: '2026-03-02', steps: 5, distanceM: 1, activeEnergyKj: 2, activeMinutes: 3 }], '2026-02-28', '2026-03-02')
    expect(days.map((d) => d.date)).toEqual(['2026-02-28', '2026-03-01', '2026-03-02'])
    expect(days[0]).toEqual({ date: '2026-02-28', steps: 0, distanceM: 0, activeEnergyKj: 0, activeMinutes: 0 })
    expect(days[2]!.steps).toBe(5)
  })
})
