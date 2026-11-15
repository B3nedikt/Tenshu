import { describe, expect, it } from 'vitest'
import {
  GAP,
  HEADER_HEIGHT,
  cellRects,
  indexAtTop,
  columnsAt,
  columnsFor,
  layoutGrid,
  marqueeHits,
  monthSections,
  topOfIndex,
  visibleRows,
  yearMarks,
  type GridRow,
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

  it('the photograph at the top is read back exactly where a seek put it (a row top, a header top)', () => {
    for (const mosaic of [false, true]) {
      const l = layoutGrid(monthSections(times), 960, 6, true, mosaic)
      for (const index of [0, 6, 30, 96, 300, 4_321, 9_999]) {
        const top = topOfIndex(l, index)
        const row = l.rows.find((r) => r.top === top)!
        const first = row.kind === 'cells' ? row.start : row.section.startIndex
        expect(indexAtTop(l, top)).toBe(first)
        expect(indexAtTop(l, top + 0.5)).toBe(first)
      }
    }
  })

  it('lays a list out with no headers', () => {
    const list = layoutGrid([{ key: 0, firstMs: 0, startIndex: 0, count: 7 }], 960, 6, false)
    expect(list.rows.every((r) => r.kind === 'cells')).toBe(true)
    expect(list.height).toBe(2 * (list.cell + GAP))
    expect(HEADER_HEIGHT).toBeGreaterThan(0)
  })
})

describe('yearMarks (the Years view)', () => {
  it('one mark per run of a year, with its first index and count', () => {
    const times = [...Array(8).fill(at(2026, 5)), at(2020, 5), at(2019, 5)]
    expect(yearMarks(times).map((m) => [m.year, m.startIndex, m.count])).toEqual([
      [2026, 0, 8],
      [2020, 8, 1],
      [2019, 9, 1],
    ])
  })
})

describe('marqueeHits', () => {
  // 960 px, 6 columns: 158 px cells on a 160 px pitch; with headers, a 44 px header then rows at
  // 44 and 204 (a cell spans its row's top to top + 158 — the trailing gap is not tile).
  const sections = [
    { key: 1, firstMs: 0, startIndex: 0, count: 8 },
    { key: 2, firstMs: 0, startIndex: 8, count: 2 },
  ]
  const layout = layoutGrid(sections, 960, 6)

  it('reads the cells from the geometry, columns from the rows’ left edge', () => {
    expect(layout.cell).toBe(158)
    expect(marqueeHits(layout, { left: 170, top: 100, right: 330, bottom: 210 })).toEqual([1, 2, 7])
  })

  it('a band in the gaps or a header hits nothing', () => {
    expect(marqueeHits(layout, { left: 158.5, top: 50, right: 159.5, bottom: 60 })).toEqual([])
    expect(marqueeHits(layout, { left: 0, top: 0, right: 960, bottom: 40 })).toEqual([])
    // Row 1 holds 6 and 7 only: the band right of them is empty space.
    expect(marqueeHits(layout, { left: 330, top: 210, right: 900, bottom: 300 })).toEqual([])
  })

  it('spans rows far past any viewport (no DOM involved)', () => {
    // The second section: header at 364, its row at 408.
    expect(marqueeHits(layout, { left: 0, top: 0, right: 10, bottom: 1000 })).toEqual([0, 6, 8])
  })
})

describe('the mosaic (ported from native FeedLayout.of)', () => {
  const pitch = (layout: { cell: number }) => layout.cell + GAP
  const cellRows = (rows: readonly GridRow[]) => rows.filter((r): r is Extract<GridRow, { kind: 'cells' }> => r.kind === 'cells')

  it('rounds the columns to whole three-column blocks', () => {
    expect(columnsFor(960, 160, true)).toBe(6)
    expect(columnsFor(1300, 160, true)).toBe(9)
    expect(columnsFor(300, 160, true)).toBe(3)
    expect(columnsFor(1300, 160)).toBe(8)
    // A desktop Days grid (885 px at 220): four plain columns, so six — never back down to three.
    expect(columnsFor(885, 220)).toBe(4)
    expect(columnsFor(885, 220, true)).toBe(6)
  })

  it('draws the 1st and 4th of every 7 full lines as features, heroes alternating, by library position', () => {
    const layout = layoutGrid([{ key: 0, firstMs: 0, startIndex: 0, count: 60 }], 960, 6, false, true)
    const rows = cellRows(layout.rows)
    expect(rows.map((r) => (r.feature ? (r.feature.heroFirst ? 'L' : 'R') : '.')).join('')).toBe('L..R...L..')
    // A feature is two rows tall, a plain row one.
    expect(rows[0]!.height).toBe(2 * pitch(layout))
    expect(rows[1]!.height).toBe(pitch(layout))
    expect(layout.height).toBe(13 * pitch(layout))
  })

  it('the rhythm is each line’s library position, never a count of lines drawn (headers change nothing)', () => {
    // A short day of 4, then a long one: the long day's lines start at 4, 10, 16, 22 … — slots
    // 0, 1, 2, 3 — so it opens on a feature even though a line and a header were drawn above it.
    const days = [
      { key: 1, firstMs: 0, startIndex: 0, count: 4 },
      { key: 2, firstMs: 0, startIndex: 4, count: 60 },
    ]
    const pattern = (headers: boolean) =>
      cellRows(layoutGrid(days, 960, 6, headers, true).rows)
        .map((r) => (r.feature ? (r.feature.heroFirst ? 'L' : 'R') : '.'))
        .join('')
    expect(pattern(false)).toBe('.L..R...L..')
    expect(pattern(true)).toBe(pattern(false))
  })

  it('never makes a feature of a short line, nor at a column count that is not whole blocks', () => {
    // A section ending three into its first line: that line is short, so it is a plain row.
    const short = layoutGrid([{ key: 0, firstMs: 0, startIndex: 0, count: 3 }], 960, 6, false, true)
    expect(cellRows(short.rows)[0]!.feature).toBeUndefined()
    const eight = layoutGrid([{ key: 0, firstMs: 0, startIndex: 0, count: 64 }], 1300, 8, false, true)
    expect(cellRows(eight.rows).some((r) => r.feature)).toBe(false)
  })

  it('without mosaic every line is plain', () => {
    const layout = layoutGrid([{ key: 0, firstMs: 0, startIndex: 0, count: 60 }], 960, 6, false, false)
    expect(cellRows(layout.rows).some((r) => r.feature)).toBe(false)
  })

  it('a feature draws every photograph once, in reading order, with no two squares overlapping', () => {
    const layout = layoutGrid([{ key: 0, firstMs: 0, startIndex: 0, count: 60 }], 960, 6, false, true)
    for (const row of cellRows(layout.rows)) {
      const rects = cellRects(layout, row)
      expect(rects.map((c) => c.index).sort((a, b) => a - b)).toEqual(
        Array.from({ length: row.end - row.start }, (_, k) => row.start + k),
      )
      for (const a of rects)
        for (const b of rects) {
          if (a === b) continue
          const apart = a.x + a.size <= b.x || b.x + b.size <= a.x || a.y + a.size <= b.y || b.y + b.size <= a.y
          expect(apart).toBe(true)
        }
      for (const c of rects) {
        expect(c.x + c.size).toBeLessThanOrEqual(960)
        expect(c.y + c.size).toBeLessThanOrEqual(row.height)
      }
    }
    // Line 0, hero on the left: 0 spans 2×2, 1 and 2 stack beside it; the next block mirrors it.
    const p = pitch(layout)
    const first = cellRects(layout, cellRows(layout.rows)[0]!)
    expect(first.slice(0, 3)).toEqual([
      { index: 0, x: 0, y: 0, size: 2 * layout.cell + GAP },
      { index: 1, x: 2 * p, y: 0, size: layout.cell },
      { index: 2, x: 2 * p, y: p, size: layout.cell },
    ])
    expect(first.slice(3)).toEqual([
      { index: 3, x: 3 * p, y: 0, size: layout.cell },
      { index: 4, x: 4 * p, y: 0, size: 2 * layout.cell + GAP },
      { index: 5, x: 3 * p, y: p, size: layout.cell },
    ])
  })

  it('a band hits a hero anywhere on it, and the small squares where they are drawn', () => {
    const layout = layoutGrid([{ key: 0, firstMs: 0, startIndex: 0, count: 60 }], 960, 6, false, true)
    const p = pitch(layout)
    // The hero's lower half, where a plain grid would have had photograph 6.
    expect(marqueeHits(layout, { left: 10, top: p + 10, right: 20, bottom: p + 20 })).toEqual([0])
    // Across the lower row: block 0's lower small square, block 1's (on its left) and block 1's hero.
    expect(marqueeHits(layout, { left: 2 * p + 10, top: p + 10, right: 4 * p + 10, bottom: p + 20 })).toEqual([2, 4, 5])
    // The upper row's small squares there are 1 and 3, not 2 and 5.
    expect(marqueeHits(layout, { left: 2 * p + 10, top: 10, right: 3 * p + 10, bottom: 20 })).toEqual([1, 3])
    // The next line starts below the feature's two rows.
    expect(marqueeHits(layout, { left: 0, top: 2 * p + 1, right: 10, bottom: 2 * p + 2 })).toEqual([6])
  })
})

describe('columnsAt — the size slider (§4.10)', () => {
  it('draws the count asked for, within 3 … 8', () => {
    expect([3, 4, 5, 6, 7, 8].map((c) => columnsAt(1400, c))).toEqual([3, 4, 5, 6, 7, 8])
    expect(columnsAt(1400, 12)).toBe(8)
    expect(columnsAt(1400, 1)).toBe(3)
  })

  it('a narrow window draws fewer than asked, never tiles under 96 px, never under 3 columns', () => {
    expect(columnsAt(600, 8)).toBe(6)
    expect(columnsAt(200, 8)).toBe(3)
  })

  it('with mosaic, the nearest whole number of three-column blocks that fits', () => {
    expect([3, 4, 5, 6, 7, 8].map((c) => columnsAt(1400, c, true))).toEqual([3, 3, 6, 6, 6, 9])
    // 8 rounds to 9, but a 700 px window fits 7: one block fewer.
    expect(columnsAt(700, 8, true)).toBe(6)
    // 500 px fits 5, which rounds UP to 6 blocks' worth: back down to 3.
    expect(columnsAt(500, 8, true)).toBe(3)
    expect(columnsAt(200, 8, true)).toBe(3)
  })
})
