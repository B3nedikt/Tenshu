import { describe, expect, it } from 'vitest'
import { hostedAppAt } from './appLabel'
import { HOSTED_APPS } from './registry'

describe('hostedAppAt', () => {
  it('finds the app whose route contains the path', () => {
    expect(hostedAppAt(HOSTED_APPS, '/drive/files')?.id).toBe('drive')
    expect(hostedAppAt(HOSTED_APPS, '/drive/files/a/b')?.id).toBe('drive')
    expect(hostedAppAt(HOSTED_APPS, '/photos')?.id).toBe('photos')
  })

  it('is null on the overview and in Control', () => {
    expect(hostedAppAt(HOSTED_APPS, '/overview')).toBeNull()
    expect(hostedAppAt(HOSTED_APPS, '/control/home')).toBeNull()
  })

  it('does not match on a bare prefix (/homework is not the Home app)', () => {
    expect(hostedAppAt(HOSTED_APPS, '/home')?.id).toBe('home')
    expect(hostedAppAt(HOSTED_APPS, '/homework')).toBeNull()
  })
})
