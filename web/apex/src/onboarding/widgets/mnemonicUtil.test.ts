import { describe, expect, it } from 'vitest'
import { pickConfirmPositions } from './mnemonicUtil'

describe('pickConfirmPositions', () => {
  it('returns the requested number of positions', () => {
    const positions = pickConfirmPositions(3, 24)
    expect(positions).toHaveLength(3)
  })

  it('keeps positions in [0, total)', () => {
    const positions = pickConfirmPositions(5, 24)
    for (const p of positions) {
      expect(p).toBeGreaterThanOrEqual(0)
      expect(p).toBeLessThan(24)
    }
  })

  it('returns positions sorted ascending', () => {
    for (let i = 0; i < 50; i++) {
      const positions = pickConfirmPositions(5, 24)
      const sorted = [...positions].sort((a, b) => a - b)
      expect(positions).toEqual(sorted)
    }
  })

  it('never picks duplicates', () => {
    for (let i = 0; i < 50; i++) {
      const positions = pickConfirmPositions(5, 24)
      const unique = new Set(positions)
      expect(unique.size).toBe(positions.length)
    }
  })

  it('caps at the total when count exceeds it', () => {
    const positions = pickConfirmPositions(10, 5)
    expect(positions).toHaveLength(5)
    expect([...positions].sort((a, b) => a - b)).toEqual([0, 1, 2, 3, 4])
  })

  it('returns an empty array for count=0', () => {
    expect(pickConfirmPositions(0, 24)).toEqual([])
  })
})
