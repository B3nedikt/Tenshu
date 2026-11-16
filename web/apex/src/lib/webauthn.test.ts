// `getPasskeyAssertion`'s `allowCredentials` narrowing: with the registered credential ID the
// browser may offer only that passkey; without one it falls back to the discoverable picker.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { getPasskeyAssertion } from './webauthn'

type GetOptions = { publicKey: PublicKeyCredentialRequestOptions }

let get: ReturnType<typeof vi.fn<(options: GetOptions) => Promise<unknown>>>

beforeEach(() => {
  get = vi.fn(async () => ({
    rawId: new Uint8Array([9, 9]).buffer,
    response: {
      clientDataJSON: new ArrayBuffer(1),
      authenticatorData: new ArrayBuffer(1),
      signature: new ArrayBuffer(1),
      userHandle: null,
    },
    getClientExtensionResults: () => ({}),
  }))
  vi.stubGlobal('PublicKeyCredential', function PublicKeyCredential() {})
  Object.defineProperty(navigator, 'credentials', { value: { get }, configurable: true })
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('getPasskeyAssertion', () => {
  it('restricts the ceremony to the registered credential when given one', async () => {
    const result = await getPasskeyAssertion(new Int8Array([1, -2, 3]))
    expect(result.ok).toBe(true)
    const allow = get.mock.calls[0]![0].publicKey.allowCredentials
    expect(allow).toHaveLength(1)
    expect(allow![0]!.type).toBe('public-key')
    // Signed bytes go over the wire unsigned: -2 → 0xfe.
    expect(Array.from(allow![0]!.id as Uint8Array)).toEqual([1, 0xfe, 3])
  })

  it('leaves the picker discoverable without a registered credential', async () => {
    await getPasskeyAssertion(null)
    await getPasskeyAssertion(new Int8Array(0))
    await getPasskeyAssertion()
    for (const call of get.mock.calls) {
      expect(call[0].publicKey.allowCredentials).toBeUndefined()
    }
  })
})
