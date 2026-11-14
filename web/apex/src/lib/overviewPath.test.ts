import { describe, expect, it } from 'vitest'
import { OVERVIEW_PATH, postUnlockPath } from './overviewPath'

describe('postUnlockPath', () => {
  it('returns to a same-origin path', () => {
    expect(postUnlockPath('/drive/files/a?x=1')).toBe('/drive/files/a?x=1')
    expect(postUnlockPath('/control/settings')).toBe('/control/settings')
  })

  it('falls back to the overview without a next', () => {
    expect(postUnlockPath(null)).toBe(OVERVIEW_PATH)
    expect(postUnlockPath('')).toBe(OVERVIEW_PATH)
  })

  // ⚠⚠ `next` is attacker-controlled: an open redirect off the apex would be a phishing aid.
  it('refuses anything that could leave the origin', () => {
    for (const next of ['//evil.example', '/\\evil.example', 'https://evil.example', 'javascript:alert(1)', 'evil']) {
      expect(postUnlockPath(next)).toBe(OVERVIEW_PATH)
    }
  })

  it('never loops back to the lock screen', () => {
    for (const next of ['/lock', '/lock?next=%2Foverview', '/lock/x']) {
      expect(postUnlockPath(next)).toBe(OVERVIEW_PATH)
    }
  })
})
