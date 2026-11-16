import { describe, expect, it } from 'vitest'
import { labelledSlots, monthBuckets, nearestIndex, niceAxis, rangeAxis, slotAt } from './trend'

const day = (date: string, steps: number) => ({ date, steps, distanceM: 0, activeEnergyKj: 0, activeMinutes: 0 })

describe('niceAxis', () => {
  it('steps of 1, 2 or 5 × 10ⁿ, the top a whole step at or above the max', () => {
    expect(niceAxis(9_300)).toEqual({ step: 5_000, top: 10_000, ticks: [0, 5_000, 10_000] })
    expect(niceAxis(5_400)).toEqual({ step: 2_000, top: 6_000, ticks: [0, 2_000, 4_000, 6_000] })
    expect(niceAxis(42)).toEqual({ step: 20, top: 60, ticks: [0, 20, 40, 60] })
    expect(niceAxis(0.9)).toMatchObject({ step: 0.5, top: 1 })
  })

  it('an all-zero window still has a scale', () => {
    expect(niceAxis(0)).toEqual({ step: 1, top: 1, ticks: [0, 1] })
  })
})

describe('monthBuckets', () => {
  it('a daily average per calendar month, oldest first — never a sum', () => {
    const days = [day('2026-10-30', 1_000), day('2026-10-31', 3_000), day('2026-11-01', 6_000)]
    expect(monthBuckets(days, 'steps')).toEqual([
      { month: '2026-10', average: 2_000, days: 2 },
      { month: '2026-11', average: 6_000, days: 1 },
    ])
  })
})

describe('slotAt / labelledSlots', () => {
  it('maps a pointer to its bar, and nothing outside the plot', () => {
    expect(slotAt(0, 700, 7)).toBe(0)
    expect(slotAt(699, 700, 7)).toBe(6)
    expect(slotAt(350, 700, 7)).toBe(3)
    expect(slotAt(-1, 700, 7)).toBeNull()
    expect(slotAt(700, 700, 7)).toBeNull()
  })

  it('labels every bar when they fit, else every k-th counted back from the newest', () => {
    expect([...labelledSlots(7, 7)].sort((a, b) => a - b)).toEqual([0, 1, 2, 3, 4, 5, 6])
    expect([...labelledSlots(30, 6)].sort((a, b) => a - b)).toEqual([4, 9, 14, 19, 24, 29])
  })
})

describe('nearestIndex', () => {
  it('finds the sample nearest a time, ties to the earlier', () => {
    const t = [0, 10, 20, 40]
    expect(nearestIndex(t, 14)).toBe(1)
    expect(nearestIndex(t, 15)).toBe(1)
    expect(nearestIndex(t, 31)).toBe(3)
    expect(nearestIndex(t, 999)).toBe(3)
    expect(nearestIndex([], 5)).toBeNull()
  })
})

describe('rangeAxis', () => {
  it('nice steps over [min, max] with headroom, not from zero', () => {
    const a = rangeAxis(118, 171)
    expect(a.lo).toBeLessThan(118)
    expect(a.hi).toBeGreaterThan(171)
    expect(a.lo).toBeGreaterThan(80)
    expect(a.ticks[0]).toBe(a.lo)
    expect(a.ticks[a.ticks.length - 1]).toBe(a.hi)
  })

  it('a flat series still has a span', () => {
    const a = rangeAxis(140, 140)
    expect(a.hi).toBeGreaterThan(a.lo)
  })
})
