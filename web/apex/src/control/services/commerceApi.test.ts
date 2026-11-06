// What actually goes out on the wire, and how each status is read back.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { accountDeletionApi, commerceApi, TOS_VERSION, PRIVACY_VERSION } from './commerceApi'
import { PRIVACY_POLICY_DATE } from '../../../support-pages/privacy.ts'
import { TERMS_DATE } from '../../../support-pages/terms.ts'

interface Sent {
  url: string
  body: Record<string, unknown>
}

let sent: Sent[] = []

function respond(status: number, json: unknown = null) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init: RequestInit) => {
      sent.push({ url, body: JSON.parse(String(init.body)) as Record<string, unknown> })
      return {
        status,
        json: async () => {
          if (json === null) throw new Error('no body')
          return json
        },
      } as Response
    }),
  )
}

beforeEach(() => {
  sent = []
})
afterEach(() => {
  vi.unstubAllGlobals()
})

// ⚠ The privacy version a sign-up records names the policy the user can actually read: the published
// /legal/privacy page's date. The Kotlin twin (CommercePublisherClient.PRIVACY_VERSION) moves with it.
describe('acceptance versions', () => {
  it('the privacy version is the published policy\'s date', () => {
    expect(PRIVACY_VERSION).toBe(PRIVACY_POLICY_DATE)
  })

  // The same for the terms: the version a sign-up (and a checkout's withdrawal acknowledgement)
  // records names the /legal/terms page the screen links. The Kotlin twin is
  // CommercePublisherClient.TOS_VERSION, pinned to the same date by TermsVersionParityTest.
  it('the terms version is the published terms\' date', () => {
    expect(TOS_VERSION).toBe(TERMS_DATE)
  })
})

describe('register', () => {
  it('sends the acceptance versions and reads 202 as sent', async () => {
    respond(202)
    expect((await commerceApi.register('a@example.com')).kind).toBe('otpSent')
    expect(sent[0]?.body).toEqual({
      email: 'a@example.com',
      tosVersion: TOS_VERSION,
      privacyVersion: PRIVACY_VERSION,
    })
  })
})

describe('verify', () => {
  it('returns the session token', async () => {
    respond(200, { sessionToken: 'tok-9' })
    const result = await commerceApi.verify('a@example.com', '123456', 'owner-1')
    expect(result).toEqual({ kind: 'verified', sessionToken: 'tok-9' })
  })

  // ⚠ 401 covers unknown email, wrong code, expired code and exhausted attempts —
  // the service answers uniformly so this cannot become an enumeration oracle, and
  // the client must not try to distinguish them either.
  it('reads every 401 as simply "invalid code"', async () => {
    respond(401, { error: 'unauthorized' })
    expect((await commerceApi.verify('a@example.com', '000000', 'owner-1')).kind).toBe(
      'invalidCode',
    )
  })

  it('treats a 200 with no token as an error, not a success', async () => {
    respond(200, {})
    expect((await commerceApi.verify('a@example.com', '123456', 'owner-1')).kind).toBe('error')
  })
})

describe('checkout', () => {
  // ⚠⚠ Without `client: 'Web'` the service uses its native `tenshu://` redirects,
  // and a paying customer's browser lands nowhere.
  it('identifies itself as the web client and carries the acknowledgement', async () => {
    respond(200, { checkoutUrl: 'https://pay.example/x' })
    await commerceApi.checkout({
      ownerId: 'owner-1',
      productId: 'tenshu.drive.basic',
      withdrawalAcknowledged: true,
      sessionToken: 'tok-1',
    })
    expect(sent[0]?.body).toEqual({
      ownerId: 'owner-1',
      productId: 'tenshu.drive.basic',
      withdrawalAcknowledged: true,
      // The session is what pins the email Stripe charges.
      sessionToken: 'tok-1',
      tosVersion: TOS_VERSION,
      client: 'Web',
    })
  })

  it('reads 503 as unavailable rather than a generic error', async () => {
    respond(503, { error: 'stripe_unavailable' })
    expect(
      (
        await commerceApi.checkout({
          ownerId: 'owner-1',
          productId: 'tenshu.drive.basic',
          withdrawalAcknowledged: true,
          sessionToken: 'tok-1',
        })
      ).kind,
    ).toBe('unavailable')
  })

  it('tells a plan already billing from a plan change', async () => {
    const args = {
      ownerId: 'owner-1',
      productId: 'tenshu.drive.basic',
      withdrawalAcknowledged: true,
      sessionToken: 'tok-1',
    }
    respond(409, { error: 'already_subscribed' })
    expect((await commerceApi.checkout(args)).kind).toBe('alreadySubscribed')
    respond(409, { error: 'plan_change_required' })
    expect((await commerceApi.checkout(args)).kind).toBe('planChange')
    // The realm pays under another email: this session cannot change that plan.
    respond(409, { error: 'subscribed_under_another_account' })
    expect((await commerceApi.checkout(args)).kind).toBe('subscribedElsewhere')
  })

  it('reads 401 as signed out', async () => {
    respond(401, { error: 'unauthorized' })
    expect(
      (
        await commerceApi.checkout({
          ownerId: 'owner-1',
          productId: 'tenshu.drive.basic',
          withdrawalAcknowledged: true,
          sessionToken: 'stale',
        })
      ).kind,
    ).toBe('signedOut')
  })
})

describe('portal', () => {
  it('maps 409 to "no subscription" and 401 to "unauthorized"', async () => {
    respond(409, { error: 'no_subscription' })
    expect((await commerceApi.portal('tok')).kind).toBe('noSubscription')
    respond(401, { error: 'unauthorized' })
    expect((await commerceApi.portal('tok')).kind).toBe('unauthorized')
  })
})

describe('redeem', () => {
  const SIGNED = {
    ownerId: 'owner-1',
    publisherId: 'tenshu.commerce',
    productId: 'tenshu.drive.premium',
    validFrom: 1,
    validUntil: 2,
    publisherSignature: 'c2ln',
  }

  it('returns the signed record the redeemed tier was minted as', async () => {
    respond(200, SIGNED)
    expect(await commerceApi.redeem('CODE', 'owner-1', 'tok')).toEqual({
      kind: 'redeemed',
      entitlement: SIGNED,
    })
    expect(sent[0]?.body).toEqual({ code: 'CODE', ownerId: 'owner-1', sessionToken: 'tok' })
  })

  // A 200 with nothing to save is not a redemption this realm holds.
  it('reads a 200 without a signed record as an error', async () => {
    respond(200, { ...SIGNED, publisherSignature: '' })
    expect((await commerceApi.redeem('CODE', 'owner-1', null)).kind).toBe('error')
    respond(200)
    expect((await commerceApi.redeem('CODE', 'owner-1', null)).kind).toBe('error')
  })

  it('maps 403 to invalid', async () => {
    respond(403, { error: 'invalid' })
    expect((await commerceApi.redeem('CODE', 'owner-1', null)).kind).toBe('invalid')
  })
})

describe('transport failure', () => {
  // A CORS rejection and a dead network are indistinguishable from here by design
  // — the browser withholds the detail — and both mean the same to a user.
  it('reports one unreachable message', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('Failed to fetch') }))
    const result = await commerceApi.register('a@example.com')
    expect(result).toEqual({ kind: 'error', message: expect.stringContaining("Couldn't reach") })
  })
})

describe('account deletion: request', () => {
  it('posts the trimmed email to the deletion endpoint and reads 202 as sent', async () => {
    respond(202, { status: 'code_sent' })
    expect(await accountDeletionApi.request(' a@example.com ')).toEqual({ kind: 'codeSent' })
    expect(sent).toHaveLength(1)
    expect(sent[0]?.url).toMatch(/\/commerce\/account\/delete\/request$/)
    expect(sent[0]?.body).toEqual({ email: 'a@example.com' })
  })

  it.each([
    [400, 'invalidEmail'],
    [429, 'rateLimited'],
    [503, 'sendFailed'],
    [500, 'error'],
    [200, 'error'],
  ] as const)('reads %i as %s', async (status, kind) => {
    respond(status, { error: 'x' })
    expect((await accountDeletionApi.request('a@example.com')).kind).toBe(kind)
  })

  it('reads a network or CORS failure as unreachable', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => Promise.reject(new TypeError('Failed to fetch'))))
    expect((await accountDeletionApi.request('a@example.com')).kind).toBe('unreachable')
  })
})

describe('account deletion: confirm', () => {
  it('posts email + code and reads 200 {status: deleted} as deleted', async () => {
    respond(200, { status: 'deleted' })
    expect(await accountDeletionApi.confirm('a@example.com', ' 123456 ')).toEqual({ kind: 'deleted' })
    expect(sent[0]?.url).toMatch(/\/commerce\/account\/delete\/confirm$/)
    expect(sent[0]?.body).toEqual({ email: 'a@example.com', code: '123456' })
  })

  // ⚠ Telling someone their account is gone when it is not is the one answer that must never
  // happen: a 200 without the explicit status is an error, not a deletion.
  it('does not read a 200 without status "deleted" as deleted', async () => {
    respond(200, {})
    expect((await accountDeletionApi.confirm('a@example.com', '123456')).kind).toBe('error')
  })

  it.each([
    [401, 'invalidCode'],
    [429, 'rateLimited'],
    [400, 'error'],
    [500, 'error'],
  ] as const)('reads %i as %s', async (status, kind) => {
    respond(status, { error: 'x' })
    expect((await accountDeletionApi.confirm('a@example.com', '123456')).kind).toBe(kind)
  })

  it('reads a network or CORS failure as unreachable', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => Promise.reject(new TypeError('Failed to fetch'))))
    expect((await accountDeletionApi.confirm('a@example.com', '123456')).kind).toBe('unreachable')
  })
})
