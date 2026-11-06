import { describe, expect, it } from 'vitest'
import { validatePairingUri } from './pairingUri'

// Validation is reconciled on the canonical role-host form Control emits:
//   tenshu://join?o=<ownerId>&d=<base32(deviceId)>&k=<base32(pubkey)>&s=<base32(secret)>
// The retired legacy `tenshu://pair?…` host and unknown role hosts get their
// own reasons (sync-pairing-inverted-direction.md §3.2) — never a partial parse.
describe('validatePairingUri', () => {
  it('accepts a fully-formed canonical join URI', () => {
    const uri = 'tenshu://join?o=owner123&d=MQYQ&k=AAAAAAAAAAAA&s=OM'
    expect(validatePairingUri(uri)).toEqual({ ok: true })
  })

  it('trims surrounding whitespace before parsing', () => {
    const uri = '   tenshu://join?o=o&d=MQYQ&k=AAAA&s=OM  \n'
    expect(validatePairingUri(uri)).toEqual({ ok: true })
  })

  it.each([
    ['empty string', ''],
    ['plain text', 'hello world'],
    ['retired opaque-URI draft scheme', 'tenshu-pair:v1?o=o&d=d&k=k&s=s'],
    ['https link', 'https://tenshu.app/join?o=o&d=d&k=k&s=s'],
  ])('rejects %s with wrongScheme', (_label, input) => {
    expect(validatePairingUri(input)).toEqual({ ok: false, reason: 'wrongScheme' })
  })

  it.each([
    // Pinned to the exact pre-flag-day emitter shape so the removal stays
    // deliberate: anything on the retired host is legacy by definition.
    ['fully-formed legacy URI', 'tenshu://pair?v=1&d=tenshu1abcd&ownerId=owner123&deviceId=deviceA'],
    ['legacy URI without version', 'tenshu://pair?d=tenshu1abcd&ownerId=o&deviceId=d'],
    ['bare legacy host', 'tenshu://pair'],
    ['garbage on the legacy host', 'tenshu://pair?v=1&d=abc'],
  ])('rejects %s as an unknown role', (_label, input) => {
    expect(validatePairingUri(input)).toEqual({ ok: false, reason: 'unknownRole' })
  })

  it('rejects an offer URI with offerRole (a new device cannot admit this one)', () => {
    const uri = 'tenshu://offer?k=AAAA&s=OM&n=Laptop'
    expect(validatePairingUri(uri)).toEqual({ ok: false, reason: 'offerRole' })
  })

  it.each([
    ['a future role host', 'tenshu://handover?k=AAAA&s=OM'],
    ['the iOS EPC pairing form', 'tenshu://epc/pair#fragment'],
  ])('rejects %s with unknownRole', (_label, input) => {
    expect(validatePairingUri(input)).toEqual({ ok: false, reason: 'unknownRole' })
  })

  it.each([
    ['missing o', 'tenshu://join?d=MQYQ&k=AAAA&s=OM'],
    ['missing d', 'tenshu://join?o=o&k=AAAA&s=OM'],
    ['missing k', 'tenshu://join?o=o&d=MQYQ&s=OM'],
    ['missing s', 'tenshu://join?o=o&d=MQYQ&k=AAAA'],
    ['empty value', 'tenshu://join?o=&d=MQYQ&k=AAAA&s=OM'],
    ['no query at all', 'tenshu://join'],
  ])('rejects %s with missingParams', (_label, input) => {
    expect(validatePairingUri(input)).toEqual({ ok: false, reason: 'missingParams' })
  })
})
