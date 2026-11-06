// The wire contract of `:commerce-service`'s account + purchase endpoints, as the
// browser sees them. Mirrors the native `CommercePublisherClient`
// (`:control-shared-logic`) endpoint for endpoint — House Rule #5: the protocol is
// shared, the client is not, and a small TS client beats compiling the Kotlin one
// into the bundle for six POSTs.
//
// ⚠ Every endpoint is content-free on error. The service answers uniformly (a 401
// on `/verify` means unknown email OR wrong OR expired OR exhausted code) so that
// nothing here becomes an account-enumeration oracle. Do not "improve" these
// messages by echoing status detail back to the user.

/** Product ids, mirroring `ProductCatalog` on the service (plans.ts). */
export { PRODUCT_BASIC, PRODUCT_FREE, PRODUCT_PREMIUM } from './plans'

/**
 * ⚠⚠ Acceptance versions, mirroring `CommercePublisherClient.TOS_VERSION` /
 * `PRIVACY_VERSION`, each the date of the published page (support-pages/terms.ts
 * TERMS_DATE, privacy.ts PRIVACY_POLICY_DATE; commerceApi.test.ts pins both).
 * These are not decoration: `/commerce/checkout` refuses with
 * `400 acknowledgement_required` unless a non-blank `tosVersion` rides along with
 * `withdrawalAcknowledged`, and the webhook writes the value into a durable
 * acceptance record, so it has to name the terms the customer was shown. Kept by
 * hand because the web does not compile the Kotlin client.
 */
export const TOS_VERSION = '2026-10-28'
export const PRIVACY_VERSION = '2026-11-05'

export type RegisterResult =
  | { readonly kind: 'otpSent' }
  | { readonly kind: 'error'; readonly message: string }

export type VerifyResult =
  // The session token authenticates the portal + redeem calls. It is returned
  // rather than stashed here so the client owns persistence in one place.
  | { readonly kind: 'verified'; readonly sessionToken: string }
  | { readonly kind: 'invalidCode' }
  | { readonly kind: 'error'; readonly message: string }

export type CheckoutResult =
  | { readonly kind: 'ready'; readonly checkoutUrl: string }
  // 401, or no session to send: a browser buys signed in, so the email Stripe
  // charges is the one this device can later fetch the plan with.
  | { readonly kind: 'signedOut' }
  // 409: this plan is already billing. A checkout would start a second
  // subscription beside it and charge twice.
  | { readonly kind: 'alreadySubscribed' }
  // 409: another plan is billing. Changing it happens on that subscription, in
  // the billing portal — never by buying a second one.
  | { readonly kind: 'planChange' }
  // 409: this realm already pays for a plan under a DIFFERENT account (another
  // email). This session cannot manage that subscription, so it is not a plan
  // change — the way forward is to sign in with that email.
  | { readonly kind: 'subscribedElsewhere' }
  | { readonly kind: 'unavailable' }
  | { readonly kind: 'error'; readonly message: string }

export type PortalResult =
  | { readonly kind: 'ready'; readonly portalUrl: string }
  | { readonly kind: 'unauthorized' }
  | { readonly kind: 'noSubscription' }
  | { readonly kind: 'unavailable' }
  | { readonly kind: 'error'; readonly message: string }

export type RedeemResult =
  // The same signed record `/mint` returns, for the redeemed tier. Nothing is
  // redeemed for this realm until it is saved — see `PublisherClient.redeem`.
  | { readonly kind: 'redeemed'; readonly entitlement: SignedEntitlement }
  | { readonly kind: 'invalid' }
  | { readonly kind: 'error'; readonly message: string }

/** The `/commerce/entitlement/mint` body: a record commerce signed for this realm. */
export interface SignedEntitlement {
  readonly ownerId: string
  readonly publisherId: string
  readonly productId: string
  readonly validFrom: number
  readonly validUntil: number
  readonly publisherSignature: string
}

export type MintResult =
  | { readonly kind: 'minted'; readonly entitlement: SignedEntitlement }
  // 401: the session is unknown or expired.
  | { readonly kind: 'unauthorized' }
  // 403: the account holds no live entitlement (or is closed).
  | { readonly kind: 'noEntitlement' }
  | { readonly kind: 'error'; readonly message: string }

export const UNREACHABLE =
  "Couldn't reach the account service. Check your connection and try again."
export const GENERIC = 'Something went wrong. Please try again.'
export const NO_IDENTITY = 'Set up this device before binding an account.'

/**
 * Commerce origin. `VITE_TENSHU_COMMERCE_URL` at build time; the local dry-run
 * stack's default in dev (`docs/how-to/commerce-dry-run.md`), production
 * otherwise.
 *
 * ⚠ A browser can only reach this if the service was started with
 * `WEB_APP_ORIGIN` naming this apex — otherwise the request dies in CORS
 * preflight with no response to inspect. That is deliberate (the service is
 * native-only by default); it is also the first thing to check when every call
 * here fails at once.
 */
export const COMMERCE_URL: string =
  (import.meta.env.VITE_TENSHU_COMMERCE_URL as string | undefined) ??
  (import.meta.env.DEV ? 'http://localhost:8081' : 'https://commerce.service.tenshu.app')

async function postJson(
  path: string,
  body: unknown,
): Promise<{ status: number; json: unknown } | null> {
  try {
    const response = await fetch(`${COMMERCE_URL.replace(/\/$/, '')}${path}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    })
    let parsed: unknown = null
    try {
      parsed = await response.json()
    } catch {
      // 202 and some errors carry no body; the status is the whole answer.
    }
    return { status: response.status, json: parsed }
  } catch {
    // Network failure, DNS, or a CORS rejection — indistinguishable from here by
    // design (the browser withholds the detail), and all mean the same to a user.
    return null
  }
}

function readSignedEntitlement(json: unknown): SignedEntitlement | null {
  if (typeof json !== 'object' || json === null) return null
  const o = json as Record<string, unknown>
  const strings = ['ownerId', 'publisherId', 'productId', 'publisherSignature'] as const
  if (strings.some(k => typeof o[k] !== 'string' || o[k] === '')) return null
  if (typeof o.validFrom !== 'number' || typeof o.validUntil !== 'number') return null
  return {
    ownerId: o.ownerId as string,
    publisherId: o.publisherId as string,
    productId: o.productId as string,
    validFrom: o.validFrom,
    validUntil: o.validUntil,
    publisherSignature: o.publisherSignature as string,
  }
}

function readString(json: unknown, key: string): string | null {
  if (typeof json !== 'object' || json === null) return null
  const value = (json as Record<string, unknown>)[key]
  return typeof value === 'string' && value !== '' ? value : null
}

export interface CommerceApi {
  register(email: string): Promise<RegisterResult>
  verify(email: string, code: string, ownerId: string): Promise<VerifyResult>
  checkout(args: {
    ownerId: string
    productId: string
    withdrawalAcknowledged: boolean
    sessionToken: string
  }): Promise<CheckoutResult>
  portal(sessionToken: string): Promise<PortalResult>
  redeem(code: string, ownerId: string, sessionToken: string | null): Promise<RedeemResult>
  /** The account's current plan, signed for the realm it is bound to. */
  mint(sessionToken: string): Promise<MintResult>
}

export const commerceApi: CommerceApi = {
  async register(email) {
    const response = await postJson('/commerce/account/register', {
      email,
      tosVersion: TOS_VERSION,
      privacyVersion: PRIVACY_VERSION,
    })
    if (response === null) return { kind: 'error', message: UNREACHABLE }
    // ⚠ 202 whether or not the address is known — that is what stops this being
    // an enumeration oracle, and the UI must not try to tell the two apart.
    return response.status === 202
      ? { kind: 'otpSent' }
      : { kind: 'error', message: GENERIC }
  },

  async verify(email, code, ownerId) {
    const response = await postJson('/commerce/account/verify', {
      email,
      code: code.trim(),
      ownerId,
    })
    if (response === null) return { kind: 'error', message: UNREACHABLE }
    if (response.status === 401) return { kind: 'invalidCode' }
    if (response.status !== 200) return { kind: 'error', message: GENERIC }
    const token = readString(response.json, 'sessionToken')
    return token === null
      ? { kind: 'error', message: GENERIC }
      : { kind: 'verified', sessionToken: token }
  },

  async checkout({ ownerId, productId, withdrawalAcknowledged, sessionToken }) {
    const response = await postJson('/commerce/checkout', {
      ownerId,
      productId,
      withdrawalAcknowledged,
      sessionToken,
      tosVersion: TOS_VERSION,
      // Selects the browser return URLs the service derives from its configured
      // web origin. Without it we would be redirected to a `tenshu://` deep link
      // after paying.
      client: 'Web',
    })
    if (response === null) return { kind: 'error', message: UNREACHABLE }
    if (response.status === 401) return { kind: 'signedOut' }
    if (response.status === 409) {
      const error = readString(response.json, 'error')
      if (error === 'already_subscribed') return { kind: 'alreadySubscribed' }
      if (error === 'subscribed_under_another_account') return { kind: 'subscribedElsewhere' }
      return { kind: 'planChange' }
    }
    // 503 = no Stripe rail, or no web origin configured on this deployment.
    if (response.status === 503) return { kind: 'unavailable' }
    if (response.status !== 200) return { kind: 'error', message: GENERIC }
    const url = readString(response.json, 'checkoutUrl')
    return url === null ? { kind: 'error', message: GENERIC } : { kind: 'ready', checkoutUrl: url }
  },

  async portal(sessionToken) {
    const response = await postJson('/commerce/portal', { sessionToken, client: 'Web' })
    if (response === null) return { kind: 'error', message: UNREACHABLE }
    if (response.status === 401) return { kind: 'unauthorized' }
    if (response.status === 409) return { kind: 'noSubscription' }
    if (response.status === 503) return { kind: 'unavailable' }
    if (response.status !== 200) return { kind: 'error', message: GENERIC }
    const url = readString(response.json, 'portalUrl')
    return url === null ? { kind: 'error', message: GENERIC } : { kind: 'ready', portalUrl: url }
  },

  async redeem(code, ownerId, sessionToken) {
    const response = await postJson('/commerce/entitlement/redeem', {
      code: code.trim(),
      ownerId,
      sessionToken,
    })
    if (response === null) return { kind: 'error', message: UNREACHABLE }
    if (response.status === 403) return { kind: 'invalid' }
    if (response.status !== 200) return { kind: 'error', message: GENERIC }
    const entitlement = readSignedEntitlement(response.json)
    return entitlement === null
      ? { kind: 'error', message: GENERIC }
      : { kind: 'redeemed', entitlement }
  },

  async mint(sessionToken) {
    const response = await postJson('/commerce/entitlement/mint', { sessionToken })
    if (response === null) return { kind: 'error', message: UNREACHABLE }
    if (response.status === 401) return { kind: 'unauthorized' }
    if (response.status === 403) return { kind: 'noEntitlement' }
    if (response.status !== 200) return { kind: 'error', message: GENERIC }
    const entitlement = readSignedEntitlement(response.json)
    return entitlement === null
      ? { kind: 'error', message: GENERIC }
      : { kind: 'minted', entitlement }
  },
}

// ---- Account deletion (the public `/account/delete` page) --------------------------------------
//
// Two calls, email variant: the page is reachable without the app, without a realm and without a
// session (Google Play requires that), so it identifies the account by a deletion code mailed to
// its address. The app uses the `sessionToken` variant of `/request` instead.
//
// ⚠ This section is imported by the PUBLIC half of the apex (App.tsx). It must stay what the rest of
// this file is: plain `fetch`, no `@tenshu/sdk/*` import (BundleSplit.test.ts).
//
// ⚠ The results carry kinds, not messages: the page is translated, and the copy lives in
// `locales/<lang>/landing.json` under `accountDeletion.*`.

export type DeletionRequestResult =
  // 202 — ALWAYS, for a well-formed address, whether or not an account uses it (the service mails
  // either a code or a "no account uses this address" notice). ⚠ Nothing may tell the two apart.
  | { readonly kind: 'codeSent' }
  // 400: not a well-formed address.
  | { readonly kind: 'invalidEmail' }
  | { readonly kind: 'rateLimited' }
  // 503: the mail could not be sent; worth retrying shortly.
  | { readonly kind: 'sendFailed' }
  // Network, DNS or CORS — no response at all.
  | { readonly kind: 'unreachable' }
  | { readonly kind: 'error' }

export type DeletionConfirmResult =
  | { readonly kind: 'deleted' }
  // 401 — wrong, expired or already-used code, uniformly; never say which. ⚠ A sign-in code is
  // not a deletion code (the service binds each to its purpose), so it lands here too.
  | { readonly kind: 'invalidCode' }
  | { readonly kind: 'rateLimited' }
  | { readonly kind: 'unreachable' }
  | { readonly kind: 'error' }

export interface AccountDeletionApi {
  /** Mails a deletion code to [email], if an account uses it. */
  request(email: string): Promise<DeletionRequestResult>
  /** Deletes the account [email] names, given the code mailed to it. Irreversible. */
  confirm(email: string, code: string): Promise<DeletionConfirmResult>
}

export const accountDeletionApi: AccountDeletionApi = {
  async request(email) {
    const response = await postJson('/commerce/account/delete/request', { email: email.trim() })
    if (response === null) return { kind: 'unreachable' }
    switch (response.status) {
      case 202:
        return { kind: 'codeSent' }
      case 400:
        return { kind: 'invalidEmail' }
      case 429:
        return { kind: 'rateLimited' }
      case 503:
        return { kind: 'sendFailed' }
      default:
        return { kind: 'error' }
    }
  },

  async confirm(email, code) {
    const response = await postJson('/commerce/account/delete/confirm', {
      email: email.trim(),
      code: code.trim(),
    })
    if (response === null) return { kind: 'unreachable' }
    switch (response.status) {
      case 200:
        // ⚠ Only an explicit `deleted` counts: telling someone their account is gone when it is
        // not is the one wrong answer this page must never give.
        return readString(response.json, 'status') === 'deleted'
          ? { kind: 'deleted' }
          : { kind: 'error' }
      case 401:
        return { kind: 'invalidCode' }
      case 429:
        return { kind: 'rateLimited' }
      default:
        return { kind: 'error' }
    }
  },
}
