// The gate's polarity is the load-bearing part: unset/empty MUST mean open, so
// that local dev, vitest and a test deployment all get the working site with no
// configuration and only the public build carries a knob. A future edit that
// flips the default would silently close signup on every deploy that never set
// the variable — including the test server the flag exists to keep working.
import { afterEach, describe, expect, it, vi } from 'vitest'
import { signupOpen } from './signupGate'

afterEach(() => {
  vi.unstubAllEnvs()
})

describe('signupOpen', () => {
  it('is open when the variable is unset', () => {
    expect(signupOpen()).toBe(true)
  })

  it('is open when the variable is set but empty', () => {
    // The deploy templates always WRITE the key (`VITE_SIGNUP_DISABLED=`), so
    // empty is the shape an un-configured deployment actually produces — not a
    // hypothetical.
    vi.stubEnv('VITE_SIGNUP_DISABLED', '')
    expect(signupOpen()).toBe(true)
  })

  it('is open when the variable holds only whitespace', () => {
    vi.stubEnv('VITE_SIGNUP_DISABLED', '  ')
    expect(signupOpen()).toBe(true)
  })

  it('is closed for any non-empty value', () => {
    for (const value of ['1', 'true', 'yes', 'closed']) {
      vi.stubEnv('VITE_SIGNUP_DISABLED', value)
      expect(signupOpen(), `value: ${value}`).toBe(false)
    }
  })
})
