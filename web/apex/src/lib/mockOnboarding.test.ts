// Tests for the apex-boundary mock SDK. Two suites:
//
//   1. `classifyMnemonic` — the bridge between the Kotlin SDK's
//      `Bip39.decode` result and the apex's `RecoveryError` shape. The
//      real BIP-39 logic is covered in `core-common/.../CryptoTest.kt`;
//      these tests only assert each kind lowers to the expected error.
//   2. `mockSession.registerPasskey + unlock` — the dev-mode passkey
//      handoff and unlock matching. Real WebCrypto verification is
//      exercised in `tenshu-sdk-js`'s `SessionTest.kt`; these tests
//      assert the mock's credentialId-matching state machine matches
//      what the real Kotlin Session does (noPasskey, wrongCredential,
//      success).
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { Bip39 } from '@tenshu/sdk/core'
import {
  classifyMnemonic,
  MockOnboardingSdk,
  resetMockState,
} from './mockOnboarding'

describe('classifyMnemonic', () => {
  it('accepts a freshly-generated mnemonic', () => {
    const mnemonic = Bip39.generateMnemonic()
    expect(classifyMnemonic(mnemonic)).toBeNull()
  })

  it('flags too-short phrases with invalidLength', () => {
    const result = classifyMnemonic('abandon abandon abandon')
    expect(result).toEqual({
      kind: 'invalidMnemonic',
      reason: { kind: 'invalidLength', wordCount: 3 },
    })
  })

  it('flags too-long phrases with invalidLength', () => {
    const mnemonic = Array.from({ length: 26 }, () => 'abandon').join(' ')
    expect(classifyMnemonic(mnemonic)).toEqual({
      kind: 'invalidMnemonic',
      reason: { kind: 'invalidLength', wordCount: 26 },
    })
  })

  it('flags an unknown word with the offending word', () => {
    // 24 tokens (right length) but one is not in the BIP-39 wordlist.
    const tokens = Array.from({ length: 24 }, () => 'abandon')
    tokens[5] = 'notaword'
    const result = classifyMnemonic(tokens.join(' '))
    expect(result).toEqual({
      kind: 'invalidMnemonic',
      reason: { kind: 'unknownWord', word: 'notaword' },
    })
  })

  it('flags a checksum mismatch when all words are valid but the phrase is wrong', () => {
    // 24 valid wordlist entries but no checksum was computed — `abandon`
    // ×24 is a deterministic example that fails the checksum.
    const result = classifyMnemonic(Array.from({ length: 24 }, () => 'abandon').join(' '))
    expect(result).toEqual({
      kind: 'invalidMnemonic',
      reason: { kind: 'checksumMismatch' },
    })
  })

  it('tolerates leading/trailing whitespace and tabs between words', () => {
    const mnemonic = Bip39.generateMnemonic()
    const reformatted = '\n  ' + mnemonic.split(' ').join('\t') + '  '
    expect(classifyMnemonic(reformatted)).toBeNull()
  })
})

describe('MockOnboardingSdk.Session passkey handoff', () => {
  beforeEach(() => {
    resetMockState()
  })
  afterEach(() => {
    resetMockState()
  })

  function int8(...values: number[]): Int8Array {
    const out = new Int8Array(values.length)
    for (let i = 0; i < values.length; i++) out[i] = values[i]!
    return out
  }

  function registration(credentialId: Int8Array) {
    return {
      credentialId,
      publicKey: int8(0xa, 0xb, 0xc),
      algorithm: -7,
      clientDataJson: int8(),
      attestationObject: int8(),
    }
  }

  function assertion(credentialId: Int8Array) {
    return {
      credentialId,
      clientDataJson: int8(),
      authenticatorData: int8(),
      signature: int8(),
      userHandle: null,
    }
  }

  it('unlock returns noPasskey when nothing registered', async () => {
    const result = await MockOnboardingSdk.Session.unlock(assertion(int8(1, 2, 3)))
    expect(result.ok).toBe(false)
    if (!result.ok) {
      expect(result.error).toEqual({ kind: 'noPasskey' })
    }
  })

  it('registerPasskey stores the credential, unlock matches by credentialId', async () => {
    const credentialId = int8(0x10, 0x20, 0x30, 0x40)
    const reg = await MockOnboardingSdk.Session.registerPasskey(registration(credentialId))
    expect(reg.ok).toBe(true)

    const result = await MockOnboardingSdk.Session.unlock(assertion(credentialId))
    expect(result.ok).toBe(true)
    if (result.ok) {
      expect(result.value.bytes.length).toBe(32)
      expect(result.value.expiresAtMs).toBeGreaterThan(Date.now())
    }
  })

  it('unlock rejects assertion whose credentialId does not match the registered passkey', async () => {
    await MockOnboardingSdk.Session.registerPasskey(registration(int8(1, 2, 3)))
    const result = await MockOnboardingSdk.Session.unlock(assertion(int8(9, 9, 9)))
    expect(result.ok).toBe(false)
    if (!result.ok) {
      expect(result.error).toEqual({ kind: 'wrongCredential' })
    }
  })

  it('clear({purgeRealm: true}) wipes the passkey so unlock falls back to noPasskey', async () => {
    const credentialId = int8(7, 7, 7)
    await MockOnboardingSdk.Session.registerPasskey(registration(credentialId))
    await MockOnboardingSdk.Session.clear({ purgeRealm: true })

    const result = await MockOnboardingSdk.Session.unlock(assertion(credentialId))
    expect(result.ok).toBe(false)
    if (!result.ok) {
      expect(result.error).toEqual({ kind: 'noPasskey' })
    }
  })
})

describe('MockOnboardingSdk.Recovery.revealPhrase (§7.4 deferred capture)', () => {
  beforeEach(() => {
    resetMockState()
  })
  afterEach(() => {
    resetMockState()
  })

  it('returns the phrase stored at Realm.create', async () => {
    const mnemonic = 'abandon ability able about above absent'
    await MockOnboardingSdk.Realm.create({ mnemonic, deviceName: 'Work laptop' })
    const result = await MockOnboardingSdk.Recovery.revealPhrase()
    expect(result.ok).toBe(true)
    if (result.ok) expect(result.value).toBe(mnemonic)
  })

  it('returns locked when no realm exists', async () => {
    const result = await MockOnboardingSdk.Recovery.revealPhrase()
    expect(result.ok).toBe(false)
    if (!result.ok) expect(result.error).toEqual({ kind: 'locked' })
  })

  it('returns joinerDevice via the scenario override (paired realm, no phrase)', async () => {
    // A joiner holds a realm row but no phrase; the `joiner` scenario stands
    // in for that state without the multi-second pairing round-trip.
    window.sessionStorage.setItem('tenshu-mock:revealPhrase', 'joiner')
    const result = await MockOnboardingSdk.Recovery.revealPhrase()
    expect(result.ok).toBe(false)
    if (!result.ok) expect(result.error).toEqual({ kind: 'joinerDevice' })
  })
})
