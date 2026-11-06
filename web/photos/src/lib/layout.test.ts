import { describe, expect, it } from 'vitest'
import {
  HEADER_HEIGHT,
  indexAtFraction,
  layoutGrid,
  monthSections,
  thinToFit,
  topOfIndex,
  trackFractions,
  visibleRows,
  yearMarks,
} from './layout'

const at = (y: number, m: number, d = 15) => new Date(y, m, d, 12).getTime()

describe('monthSections', () => {
  it('groups a newest-first index into runs of a month', () => {
    const times = [at(2026, 9, 20), at(2026, 9, 2), at(2026, 8, 30), at(2025, 0, 1)]
    expect(monthSections(times).map((s) => [s.startIndex, s.count])).toEqual([
      [0, 2],
      [2, 1],
      [3, 1],
    ])
    expect(monthSections([])).toEqual([])
  })
})

describe('layoutGrid / visibleRows', () => {
  const times = Array.from({ length: 10_000 }, (_, i) => at(2026, 9) - i * 3_600_000)
  const layout = layoutGrid(monthSections(times), 960)

  it('lays every photograph out once, under its month header', () => {
    const cells = layout.rows.filter((r) => r.kind === 'cells')
    const covered = cells.reduce((n, r) => n + (r.kind === 'cells' ? r.end - r.start : 0), 0)
    expect(covered).toBe(10_000)
    expect(layout.rows[0]!.kind).toBe('header')
  })

  it('mounts a screenful plus overscan, wherever the scroll is', () => {
    for (const top of [0, layout.height / 2, layout.height - 800]) {
      const rows = visibleRows(layout, top, 800)
      const cells = rows.reduce((n, r) => n + (r.kind === 'cells' ? r.end - r.start : 0), 0)
      expect(cells).toBeGreaterThan(0)
      expect(cells).toBeLessThan(200)
    }
  })

  it('finds the row of an index, its month header first', () => {
    const sections = monthSections(times)
    const second = sections[1]!
    expect(topOfIndex(layout, second.startIndex)).toBe(
      layout.rows.find((r) => r.kind === 'header' && r.section.startIndex === second.startIndex)!.top,
    )
    expect(topOfIndex(layout, 0)).toBe(0)
  })

  it('lays a list out with no headers', () => {
    const list = layoutGrid([{ key: 0, firstMs: 0, startIndex: 0, count: 7 }], 960, 6, false)
    expect(list.rows.every((r) => r.kind === 'cells')).toBe(true)
    expect(list.height).toBe(2 * (list.cell + 4))
    expect(HEADER_HEIGHT).toBeGreaterThan(0)
  })
})

describe('the scrubber marks (ported from FeedYearIndex.kt)', () => {
  it('spaces years by how many photographs they hold', () => {
    const times = [...Array(8).fill(at(2026, 5)), ...Array(1).fill(at(2020, 5)), at(2019, 5)]
    const fractions = trackFractions(yearMarks(times))
    expect(fractions.map((f) => [f.mark.year, f.fraction])).toEqual([
      [2026, 0],
      [2020, 8 / 9],
      [2019, 1],
    ])
    // The crowded tail thins to the newest of the run.
    expect(thinToFit(fractions, 0.2).map((f) => f.mark.year)).toEqual([2026, 2020])
  })

  it('seeks a fraction to an index', () => {
    expect(indexAtFraction(0, 100)).toBe(0)
    expect(indexAtFraction(1, 100)).toBe(99)
    expect(indexAtFraction(2, 100)).toBe(99)
    expect(indexAtFraction(0.5, 0)).toBe(0)
  })
})
