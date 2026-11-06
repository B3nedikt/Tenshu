import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { displayFingerprint, formatSas, readOwnDisplayName, writeOwnDisplayName } from './contacts'

// jsdom's localStorage is partial in this env, so back it with a plain Map.
function stubLocalStorage() {
  const store = new Map<string, string>()
  vi.stubGlobal('localStorage', {
    getItem: (k: string) => store.get(k) ?? null,
    setItem: (k: string, v: string) => void store.set(k, v),
    removeItem: (k: string) => void store.delete(k),
    clear: () => store.clear(),
  })
}

describe('displayFingerprint', () => {
  it('formats the leading 15 hex chars as XXXXX-YYYYY-ZZZZZ, uppercased', () => {
    // Mirrors the native `displayFingerprint` (drive-shared-logic) so a
    // web↔native pair reads the same string.
    expect(displayFingerprint('0123456789abcdef0123456789')).toBe('01234-56789-ABCDE')
  })

  it('handles a short fingerprint without padding', () => {
    expect(displayFingerprint('abc')).toBe('ABC')
  })
})

describe('formatSas', () => {
  it('splits a 6-digit code into two groups of three', () => {
    expect(formatSas('744595')).toBe('744 595')
  })

  it('passes a non-6-digit value through unchanged', () => {
    expect(formatSas('')).toBe('')
    expect(formatSas('12345')).toBe('12345')
  })
})

describe('own display name', () => {
  beforeEach(() => stubLocalStorage())
  afterEach(() => vi.unstubAllGlobals())

  it('round-trips through localStorage, trimming whitespace', () => {
    writeOwnDisplayName('  Alice  ')
    expect(readOwnDisplayName()).toBe('Alice')
  })

  it('defaults to empty when unset', () => {
    expect(readOwnDisplayName()).toBe('')
  })
})
