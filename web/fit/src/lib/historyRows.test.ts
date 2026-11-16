import { describe, expect, it } from 'vitest'
import { historyLayout, topOfWorkout, visibleRows, weekStartOf } from './historyRows'
import type { FitWorkout } from '@tenshu/sdk/fit'

const w = (id: string, startLocal: string, distanceM: number | null = 5_000, durationSec = 1_800) =>
  ({ workoutId: id, startLocal, distanceM, durationSec }) as unknown as FitWorkout

describe('historyLayout', () => {
  it('a week summary, then a header per day, then its workouts — newest first, as the pages give them', () => {
    const layout = historyLayout([
      w('a', '2026-11-15T09:00:00'), // Sunday
      w('b', '2026-11-15T07:00:00'),
      w('c', '2026-11-12T18:00:00', null), // Thursday, no distance
      w('d', '2026-11-08T10:00:00'), // the Sunday before: the previous week
    ])
    expect(layout.rows.map((r) => (r.kind === 'workout' ? r.key : `${r.kind}:${r.kind === 'week' ? r.weekStart : r.date}`))).toEqual([
      'week:2026-11-09',
      'day:2026-11-15',
      'a',
      'b',
      'day:2026-11-12',
      'c',
      'week:2026-11-02',
      'day:2026-11-08',
      'd',
    ])
    const week = layout.rows[0]!
    expect(week).toMatchObject({ count: 3, distanceM: 10_000, durationSec: 5_400 })
    expect(layout.height).toBe(2 * 52 + 3 * 32 + 4 * 64)
  })

  it('groups by the workout\'s own wall clock (startLocal), never the browser\'s', () => {
    // 23:30 local on the 14th — whatever the instant is in the browser's zone.
    const layout = historyLayout([w('late', '2026-11-14T23:30:00')])
    expect(layout.rows[1]).toMatchObject({ kind: 'day', date: '2026-11-14' })
  })

  it('weeks start on Monday', () => {
    expect(weekStartOf('2026-11-16')).toBe('2026-11-16') // Monday
    expect(weekStartOf('2026-11-15')).toBe('2026-11-09') // Sunday
    expect(weekStartOf('2026-11-11')).toBe('2026-11-09')
  })
})

describe('visibleRows / topOfWorkout', () => {
  const many = Array.from({ length: 200 }, (_, i) => w(`x${i}`, `2026-11-${String(15 - Math.floor(i / 24)).padStart(2, '0')}T${String(23 - (i % 24)).padStart(2, '0')}:00:00`))
  const layout = historyLayout(many)

  it('the window covers the viewport plus overscan, found by the rows\' tops', () => {
    const top = topOfWorkout(layout, 120)
    const { first, last } = visibleRows(layout, top, 640)
    expect(layout.tops[first]!).toBeLessThanOrEqual(top - 400)
    expect(layout.tops[last]!).toBeGreaterThanOrEqual(top + 640)
    const drawn = layout.rows.slice(first, last + 1).filter((r) => r.kind === 'workout').map((r) => (r.kind === 'workout' ? r.index : -1))
    expect(drawn).toContain(120)
    expect(last - first).toBeLessThan(40)
  })

  it('the top of a workout is after its headers', () => {
    expect(topOfWorkout(layout, 0)).toBe(52 + 32)
    expect(topOfWorkout(layout, 1)).toBe(52 + 32 + 64)
  })
})
