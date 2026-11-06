import { describe, expect, it } from 'vitest'
import { bridgeErrorKind, knownErrorKind } from './errors'

describe('error copy keys', () => {
  it('keeps a known kind and falls back for anything else — never the raw key', () => {
    expect(knownErrorKind('error_not_found', ['error_generic', 'error_not_found'])).toBe('error_not_found')
    expect(knownErrorKind('error_brand_new', ['error_generic'])).toBe('error_generic')
    expect(knownErrorKind('error_brand_new', [], 'error_unexpected')).toBe('error_unexpected')
  })

  it('tells a refused, a timed-out and any other rejected bridge call apart', () => {
    expect(bridgeErrorKind(new Error('not permitted: contact.merge'))).toBe('bridge_not_permitted')
    expect(bridgeErrorKind(new Error('call contact.list timed out after 15000ms'))).toBe('bridge_timeout')
    expect(bridgeErrorKind('boom')).toBe('bridge')
  })
})
