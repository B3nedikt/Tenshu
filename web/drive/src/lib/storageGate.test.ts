import { describe, expect, it } from 'vitest'
import { evaluateStorageGate } from './storageGate'

describe('evaluateStorageGate', () => {
  it('allows optimistically when the status is unknown (Tier-2 backstops)', () => {
    expect(evaluateStorageGate(null)).toBe('allow')
  })

  it('needs storage when no plan is bound', () => {
    expect(evaluateStorageGate({ hasStorage: false, usedBytes: 0, limitBytes: 0 })).toBe(
      'needs-storage',
    )
  })

  it('is full when usage has reached the limit', () => {
    expect(evaluateStorageGate({ hasStorage: true, usedBytes: 100, limitBytes: 100 })).toBe('full')
  })

  it('allows when storage is bound with room to spare', () => {
    expect(evaluateStorageGate({ hasStorage: true, usedBytes: 10, limitBytes: 100 })).toBe('allow')
  })
})
