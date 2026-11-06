// The commerce client's state handling around the wire contract in `commerceApi.ts`.
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { CommercePublisherClient, MockPublisherClient, STORAGE_KEY } from './publisherClient'
import type { CommerceApi } from './commerceApi'

function memoryStorage(): Storage {
  const map = new Map<string, string>()
  return {
    getItem: (k: string) => map.get(k) ?? null,
    setItem: (k: string, v: string) => void map.set(k, v),
    removeItem: (k: string) => void map.delete(k),
    clear: () => map.clear(),
    key: (i: number) => Array.from(map.keys())[i] ?? null,
    get length() {
      return map.size
    },
  } as Storage
}

function stubApi(over: Partial<CommerceApi> = {}): CommerceApi {
  return {
    register: vi.fn().mockResolvedValue({ kind: 'otpSent' }),
    verify: vi.fn().mockResolvedValue({ kind: 'verified', sessionToken: 'tok-1' }),
    checkout: vi.fn().mockResolvedValue({ kind: 'ready', checkoutUrl: 'https://pay.example/x' }),
    portal: vi.fn().mockResolvedValue({ kind: 'ready', portalUrl: 'https://billing.example/x' }),
    redeem: vi.fn().mockResolvedValue({ kind: 'redeemed', entitlement: SIGNED }),
    mint: vi.fn().mockResolvedValue({ kind: 'minted', entitlement: SIGNED }),
    ...over,
  }
}

const SIGNED = {
  ownerId: 'owner-1',
  publisherId: 'tenshu.commerce',
  productId: 'tenshu.drive.basic',
  validFrom: 1,
  validUntil: 2,
  publisherSignature: 'c2ln',
}

const SIGNED_IN = {
  identityMethod: { kind: 'email', address: 'a@example.com' },
  displayName: null,
} as const

const OWNER = () => Promise.resolve('owner-1')
const NO_OWNER = () => Promise.resolve(null)

describe('CommercePublisherClient', () => {
  let storage: Storage
  beforeEach(() => {
    storage = memoryStorage()
  })

  it('binds the account only after the code is verified', async () => {
    const client = new CommercePublisherClient(OWNER, stubApi(), storage)
    await client.register('a@example.com', null)
    expect(client.getAccount()).toBeNull()

    await client.verify('123456')
    expect(client.getAccount()?.identityMethod).toEqual({ kind: 'email', address: 'a@example.com' })
    expect(client.currentSessionToken()).toBe('tok-1')
  })

  it('survives a reload', async () => {
    const first = new CommercePublisherClient(OWNER, stubApi(), storage)
    await first.register('a@example.com', null)
    await first.verify('123456')

    const second = new CommercePublisherClient(OWNER, stubApi(), storage)
    expect(second.getAccount()?.identityMethod.address).toBe('a@example.com')
    expect(second.currentSessionToken()).toBe('tok-1')
  })

  it('drops the persisted session on disconnect', async () => {
    const client = new CommercePublisherClient(OWNER, stubApi(), storage)
    await client.register('a@example.com', null)
    await client.verify('123456')
    await client.disconnect()

    expect(client.getAccount()).toBeNull()
    expect(client.currentSessionToken()).toBeNull()
    expect(storage.getItem(STORAGE_KEY)).toBeNull()
  })

  it('evicts a corrupt stored session instead of crashing', () => {
    storage.setItem(STORAGE_KEY, '{not json')
    const client = new CommercePublisherClient(OWNER, stubApi(), storage)
    expect(client.getAccount()).toBeNull()
    expect(storage.getItem(STORAGE_KEY)).toBeNull()
  })

  it('rejects an obviously invalid email before calling out', async () => {
    const api = stubApi()
    const client = new CommercePublisherClient(OWNER, api, storage)
    const result = await client.register('nope', null)
    expect(result.kind).toBe('error')
    expect(api.register).not.toHaveBeenCalled()
  })

  // ⚠ Every commerce endpoint is keyed by ownerId. Without a realm there is
  // nothing to bind an entitlement to, and the call must not be attempted.
  it('refuses to verify, check out or redeem without a realm', async () => {
    const api = stubApi()
    const client = new CommercePublisherClient(NO_OWNER, api, storage)
    await client.register('a@example.com', null)

    expect((await client.verify('123456')).kind).toBe('error')
    expect((await client.startCheckout('tenshu.drive.basic', true)).kind).toBe('error')
    expect((await client.redeem('CODE')).kind).toBe('error')
    expect(api.verify).not.toHaveBeenCalled()
    expect(api.checkout).not.toHaveBeenCalled()
    expect(api.redeem).not.toHaveBeenCalled()
  })

  it('does not bind an account when the code is wrong', async () => {
    const api = stubApi({ verify: vi.fn().mockResolvedValue({ kind: 'invalidCode' }) })
    const client = new CommercePublisherClient(OWNER, api, storage)
    await client.register('a@example.com', null)

    expect((await client.verify('000000')).kind).toBe('invalidCode')
    expect(client.getAccount()).toBeNull()
    expect(storage.getItem(STORAGE_KEY)).toBeNull()
  })

  it('forwards the acknowledgement and the session to the checkout call', async () => {
    const api = stubApi()
    const client = new CommercePublisherClient(OWNER, api, storage)
    await client.register('a@example.com', null)
    await client.verify('123456')
    await client.startCheckout('tenshu.drive.premium', true)
    expect(api.checkout).toHaveBeenCalledWith({
      ownerId: 'owner-1',
      productId: 'tenshu.drive.premium',
      withdrawalAcknowledged: true,
      sessionToken: 'tok-1',
    })
  })

  // ⚠⚠ A browser buys signed in: Stripe is handed the session's email, so the
  // plan cannot land on an account this device is not signed in to.
  it('will not start a checkout without a session', async () => {
    const api = stubApi()
    const client = new CommercePublisherClient(OWNER, api, storage)
    expect(await client.startCheckout('tenshu.drive.premium', true)).toEqual({ kind: 'signedOut' })
    expect(api.checkout).not.toHaveBeenCalled()
  })

  it('will not open the billing portal without a session', async () => {
    const api = stubApi()
    const client = new CommercePublisherClient(OWNER, api, storage)
    expect((await client.openBillingPortal()).kind).toBe('unauthorized')
    expect(api.portal).not.toHaveBeenCalled()
  })

  // Redeeming is identity-light: an ownerId is required, a session is not.
  it('redeems without a session', async () => {
    const api = stubApi()
    const client = new CommercePublisherClient(OWNER, api, storage, vi.fn().mockResolvedValue(true))
    expect((await client.redeem('CODE-1')).kind).toBe('redeemed')
    expect(api.redeem).toHaveBeenCalledWith('CODE-1', 'owner-1', null)
  })
})

// ⚠⚠ The code is spent the moment commerce answers 200, so `redeemed` has to mean
// the plan is in the realm — never just that the code was accepted.
describe('CommercePublisherClient.redeem', () => {
  let storage: Storage
  beforeEach(() => {
    storage = memoryStorage()
  })

  function signedInStorage(): Storage {
    storage.setItem(STORAGE_KEY, JSON.stringify({ email: 'a@example.com', sessionToken: 'tok-1' }))
    return storage
  }

  it('saves the redeemed record into the realm, once', async () => {
    const sink = vi.fn().mockResolvedValue(true)
    const client = new CommercePublisherClient(OWNER, stubApi(), storage, sink)
    expect(await client.redeem('CODE-1')).toEqual({
      kind: 'redeemed',
      productId: 'tenshu.drive.basic',
    })
    expect(sink).toHaveBeenCalledTimes(1)
    expect(sink).toHaveBeenCalledWith(SIGNED)
  })

  it('does not claim a plan the realm refused', async () => {
    const client = new CommercePublisherClient(
      OWNER,
      stubApi(),
      storage,
      vi.fn().mockResolvedValue(false),
    )
    expect(await client.redeem('CODE-1')).toEqual({ kind: 'notSaved', signedIn: false })
  })

  it('reads a sink that throws as not saved, not as an error to retry', async () => {
    const client = new CommercePublisherClient(
      OWNER,
      stubApi(),
      signedInStorage(),
      vi.fn().mockRejectedValue(new Error('verify failed')),
    )
    expect(await client.redeem('CODE-1')).toEqual({ kind: 'notSaved', signedIn: true })
  })

  it('does not claim a plan with nowhere to save it', async () => {
    const client = new CommercePublisherClient(OWNER, stubApi(), storage)
    expect(await client.redeem('CODE-1')).toEqual({ kind: 'notSaved', signedIn: false })
  })

  it('leaves the refusals as they were', async () => {
    const sink = vi.fn()
    const api = stubApi({ redeem: vi.fn().mockResolvedValue({ kind: 'invalid' }) })
    const client = new CommercePublisherClient(OWNER, api, storage, sink)
    expect(await client.redeem('CODE-1')).toEqual({ kind: 'invalid' })
    expect(sink).not.toHaveBeenCalled()
  })

  // The redeemed record and the next `/mint` name the same plan; saving it twice
  // would be a stored row and a sync block for nothing.
  it('is not saved again by a later sync of the same plan', async () => {
    const sink = vi.fn().mockResolvedValue(true)
    const minted = { ...SIGNED, validUntil: SIGNED.validUntil + 2_500 }
    const api = stubApi({ mint: vi.fn().mockResolvedValue({ kind: 'minted', entitlement: minted }) })
    const client = new CommercePublisherClient(OWNER, api, signedInStorage(), sink)
    await client.redeem('CODE-1')

    expect(await client.syncPlan()).toEqual({ kind: 'synced', productId: 'tenshu.drive.basic' })
    expect(api.mint).toHaveBeenCalledWith('tok-1')
    expect(sink).toHaveBeenCalledTimes(1)
  })
})

describe('MockPublisherClient', () => {
  it('mirrors the service by refusing a checkout without the acknowledgement', async () => {
    // A mock that let this through could be used to "prove" the flow works
    // without the one step the service actually enforces.
    const client = new MockPublisherClient(SIGNED_IN)
    expect((await client.startCheckout('tenshu.drive.basic', false)).kind).toBe('error')
    expect((await client.startCheckout('tenshu.drive.basic', true)).kind).toBe('ready')
  })

  it('takes any six-digit code', async () => {
    const client = new MockPublisherClient()
    await client.register('a@example.com', null)
    expect((await client.verify('12345')).kind).toBe('invalidCode')
    expect((await client.verify('123456')).kind).toBe('verified')
  })
})

// ⚠⚠ The only path by which a plan bought in a browser reaches its realm.
describe('CommercePublisherClient.syncPlan', () => {
  let storage: Storage
  beforeEach(() => {
    storage = memoryStorage()
  })

  it('saves the record commerce signed and names its plan', async () => {
    const sink = vi.fn().mockResolvedValue(true)
    const api = stubApi()
    const client = new CommercePublisherClient(OWNER, api, storage, sink)
    await client.register('a@example.com', null)
    await client.verify('123456')

    expect(await client.syncPlan()).toEqual({ kind: 'synced', productId: 'tenshu.drive.basic' })
    expect(api.mint).toHaveBeenCalledWith('tok-1')
    expect(sink).toHaveBeenCalledWith(SIGNED)
  })

  // A buyer who paid before registering gets the plan at the moment they sign in.
  it('runs on sign-in', async () => {
    const sink = vi.fn().mockResolvedValue(true)
    const client = new CommercePublisherClient(OWNER, stubApi(), storage, sink)
    await client.register('a@example.com', null)
    expect(sink).not.toHaveBeenCalled()

    await client.verify('123456')
    expect(sink).toHaveBeenCalledTimes(1)
  })

  // ⚠⚠ Each `/mint` answer carries a slightly later `validUntil`, which the realm
  // would save as a renewal — one stored row and one sync block per poll.
  it('saves a changed plan, not every freshly stamped copy of the same one', async () => {
    const sink = vi.fn().mockResolvedValue(true)
    let entitlement = { ...SIGNED, productId: 'tenshu.drive.free', validUntil: 1_000_000 }
    const api = stubApi({ mint: vi.fn(async () => ({ kind: 'minted' as const, entitlement })) })
    const client = new CommercePublisherClient(OWNER, api, storage, sink)
    await client.register('a@example.com', null)
    await client.verify('123456') // signs in, and saves once

    entitlement = { ...entitlement, validUntil: entitlement.validUntil + 2_500 }
    expect(await client.syncPlan()).toEqual({ kind: 'synced', productId: 'tenshu.drive.free' })
    expect(sink).toHaveBeenCalledTimes(1)

    entitlement = { ...entitlement, productId: 'tenshu.drive.basic' } // the webhook landed
    await client.syncPlan()
    expect(sink).toHaveBeenCalledTimes(2)

    entitlement = { ...entitlement, validUntil: entitlement.validUntil + 13 * 60 * 60 * 1000 }
    await client.syncPlan()
    expect(sink).toHaveBeenCalledTimes(3)
  })

  it('asks commerce nothing without a session', async () => {
    const api = stubApi()
    const client = new CommercePublisherClient(OWNER, api, storage, vi.fn())
    expect(await client.syncPlan()).toEqual({ kind: 'signedOut' })
    expect(api.mint).not.toHaveBeenCalled()
  })

  it('does not claim a plan the realm refused', async () => {
    const client = new CommercePublisherClient(OWNER, stubApi(), storage, vi.fn().mockResolvedValue(false))
    await client.register('a@example.com', null)
    await client.verify('123456')
    expect(await client.syncPlan()).toEqual({ kind: 'unavailable' })
  })

  it('reads an expired session as signed out', async () => {
    const api = stubApi({ mint: vi.fn().mockResolvedValue({ kind: 'unauthorized' }) })
    const client = new CommercePublisherClient(OWNER, api, storage, vi.fn())
    await client.register('a@example.com', null)
    await client.verify('123456')
    expect(await client.syncPlan()).toEqual({ kind: 'signedOut' })
    // The dead session is dropped, so the UI stops presenting a signed-in account.
    expect(client.getAccount()).toBeNull()
    expect(client.currentSessionToken()).toBeNull()
    expect(storage.getItem(STORAGE_KEY)).toBeNull()
  })
})
