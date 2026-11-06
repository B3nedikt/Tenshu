import { describe, expect, it } from 'vitest'
import {
  cleanDetails,
  displayFingerprint,
  findMergeCandidates,
  formatSas,
  matchesQuery,
  normalisePhone,
  preferredTarget,
  vcardFileName,
} from './contacts'
import { contact, identity } from '../test/proxyMock'

const details = (over: Partial<ReturnType<typeof cleanDetails>> = {}) => ({
  phones: [],
  emails: [],
  addresses: [],
  organisation: '',
  note: '',
  ...over,
})

describe('contacts helpers', () => {
  it('formats fingerprints and codes the native way', () => {
    expect(displayFingerprint('abcdef0123456789ffff')).toBe('ABCDE-F0123-45678')
    expect(formatSas('123456')).toBe('123 456')
    expect(formatSas('')).toBe('')
  })

  it('compares phones on their last nine digits', () => {
    expect(normalisePhone('+44 7700 900123')).toBe(normalisePhone('07700 900123'))
    expect(normalisePhone('00447700900123')).toBe('700900123')
    expect(normalisePhone('112')).toBe('112')
  })

  it('searches accent- and case-insensitively, across details and claimed names', () => {
    const c = contact({
      displayName: 'Zoë',
      identities: [identity('k', 'UNVERIFIED', 'Zed')],
      details: details({ emails: [{ label: '', value: 'z@example.org' }] }),
    })
    expect(matchesQuery(c, 'zoe')).toBe(true)
    expect(matchesQuery(c, 'EXAMPLE')).toBe(true)
    expect(matchesQuery(c, 'zed')).toBe(true)
    expect(matchesQuery(c, 'nope')).toBe(false)
    expect(matchesQuery(c, '  ')).toBe(true)
  })

  it('ranks shared-phone pairs above name-only ones, and never pairs two connected people by name', () => {
    const a = contact({ displayName: 'Sam', details: details({ phones: [{ label: '', value: '+1 555 123 4567' }] }) })
    const b = contact({ displayName: 'Other', details: details({ phones: [{ label: '', value: '5551234567' }] }) })
    const c = contact({ displayName: 'sam' })
    const k1 = contact({ displayName: 'Kim', identities: [identity('1', 'VERIFIED')] })
    const k2 = contact({ displayName: 'kim', identities: [identity('2', 'VERIFIED')] })
    const pairs = findMergeCandidates([a, b, c, k1, k2])
    expect(pairs.map((p) => [p.first.displayName, p.second.displayName, p.reasons])).toEqual([
      ['Sam', 'Other', ['phone']],
      ['Sam', 'sam', ['name']],
    ])
  })

  it('keeps the connected contact by default', () => {
    const plain = contact({ displayName: 'A' })
    const connected = contact({ displayName: 'A', identities: [identity('k', 'UNVERIFIED')] })
    expect(preferredTarget({ first: plain, second: connected, reasons: ['name'] })).toBe(connected.personId)
  })

  it('cleans the editor draft but keeps every key (a missing key would delete on save)', () => {
    expect(
      cleanDetails(details({ phones: [{ label: ' m ', value: ' 1 ' }, { label: 'x', value: '  ' }], note: ' n ' })),
    ).toEqual({ phones: [{ label: 'm', value: '1' }], emails: [], addresses: [], organisation: '', note: 'n' })
  })

  it('makes a safe .vcf file name', () => {
    expect(vcardFileName('Ada / Lovelace?')).toBe('Ada _ Lovelace_.vcf')
    expect(vcardFileName('   ')).toBe('contact.vcf')
  })
})
