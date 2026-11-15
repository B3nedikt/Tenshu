import { describe, expect, it } from 'vitest'
import { COLUMNS, packSpans } from './packSpans'

const rows = (spans: number[]) => {
  const out: number[][] = [[]]
  let used = 0
  for (const s of spans) {
    if (used + s > COLUMNS) {
      out.push([])
      used = 0
    }
    out[out.length - 1]!.push(s)
    used += s
  }
  return out
}

describe('packSpans', () => {
  it('two wides share a row; three narrows share a row', () => {
    expect(packSpans(['wide', 'wide'])).toEqual([3, 3])
    expect(packSpans(['narrow', 'narrow', 'narrow'])).toEqual([2, 2, 2])
  })

  // The case that left a hole: a third next to a half.
  it('stretches a short row\'s last card instead of leaving a hole, keeping order', () => {
    expect(packSpans(['narrow', 'wide', 'wide', 'narrow', 'narrow'])).toEqual([2, 4, 3, 3, 6])
  })

  it('every row adds up to the full width', () => {
    for (const sizes of [
      ['wide'],
      ['narrow'],
      ['wide', 'narrow', 'narrow', 'wide'],
      ['narrow', 'wide', 'narrow', 'narrow', 'wide', 'narrow'],
    ] as const) {
      for (const row of rows(packSpans(sizes))) expect(row.reduce((a, b) => a + b, 0)).toBe(COLUMNS)
    }
  })
})
