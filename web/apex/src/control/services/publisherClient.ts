// The apex's commerce account client — the web twin of `:control-shared-logic`'s
// `PublisherClient` / `CommercePublisherClient`.
//
// Per House Rule #5 the Kotlin client is not compiled into the bundle; the wire
// contract lives in `commerceApi.ts` and this file owns the state around it
// (pending registration, the persisted session, the observable account).
//
// ⚠⚠ Carries NO storage figures. It used to hold `storageUsedBytes` /
// `storageLimitBytes`, which nothing populated correctly, and the card rendered
// them over the real synced ones. Storage comes from `Storage.status()` — see
// `servicesCardState.ts`.
//
// ⚠ `Lifetime` and `Rei Key` are gone. The one-time Lifetime tier was dropped at
// commerce Ph1 (tiers are Free / Basic / Premium), and `rei` was retired in CE-4
// as "an identity, never a plan". What replaced the Rei Key box is `redeem`,
// which binds a real tier to this realm.
import {
  commerceApi,
  type CheckoutResult,
  type CommerceApi,
  type PortalResult,
  type RegisterResult,
  type SignedEntitlement,
  type VerifyResult,
  GENERIC,
  NO_IDENTITY,
  PRODUCT_FREE,
} from './commerceApi'

/** How this device authenticated to the commerce plane. Email OTP is the only way. */
export type IdentityMethod = { readonly kind: 'email'; readonly address: string }

export interface PublisherAccount {
  readonly identityMethod: IdentityMethod
  readonly displayName: string | null
}

/**
 * What [PublisherClient.syncPlan] found. `synced` names the plan the realm now
 * holds — read off a record commerce signed, so it may be shown as fact.
 */
export type PlanSyncResult =
  | { readonly kind: 'synced'; readonly productId: string }
  /** No session on this device, or commerce no longer recognises it. */
  | { readonly kind: 'signedOut' }
  /** The account has no live plan, or the realm would not take the record. */
  | { readonly kind: 'unavailable' }
  | { readonly kind: 'error'; readonly message: string }

/**
 * What [PublisherClient.redeem] did. `redeemed` means the plan is saved in the
 * realm, never merely that commerce took the code.
 */
export type PlanRedeemResult =
  | { readonly kind: 'redeemed'; readonly productId: string }
  /**
   * ⚠⚠ Commerce accepted — and spent — the code, but the realm did not save the
   * plan. Redeeming again cannot help. `signedIn` says whether the redeem rode a
   * session: only then is the tier on an account that a later [syncPlan] can mint.
   */
  | { readonly kind: 'notSaved'; readonly signedIn: boolean }
  | { readonly kind: 'invalid' }
  | { readonly kind: 'error'; readonly message: string }

/** Saves a signed record into the realm — `Storage.applyEntitlement` in production. */
export type EntitlementSink = (entitlement: SignedEntitlement) => Promise<boolean>

export interface PublisherClient {
  getAccount(): PublisherAccount | null
  /** Non-null exactly when a session is held; the portal and redeem calls need it. */
  currentSessionToken(): string | null
  subscribe(listener: () => void): () => void
  /** Sends an OTP. Serves sign-up and sign-in alike — the service decides which. */
  register(email: string, displayName: string | null): Promise<RegisterResult>
  /** Completes the OTP started by [register]. */
  verify(code: string): Promise<VerifyResult>
  startCheckout(productId: string, withdrawalAcknowledged: boolean): Promise<CheckoutResult>
  openBillingPortal(): Promise<PortalResult>
  redeem(code: string): Promise<PlanRedeemResult>
  /**
   * Fetch the account's current plan from commerce and save it into the realm.
   *
   * ⚠⚠ Nothing else brings a purchase to a browser. The webhook writes commerce's
   * ledger only, and the signed record is client-pull: without this call a plan
   * bought here shows up only if some other device of the realm happens to hold a
   * session and mints it.
   */
  syncPlan(): Promise<PlanSyncResult>
  disconnect(): Promise<void>
}

/** Reads this realm's ownerId — `Realm.ownerId()` in production. */
export type OwnerIdProvider = () => Promise<string | null>

/** How far a record's window must advance before an unchanged plan is saved again. */
const RENEWAL_STEP_MS = 12 * 60 * 60 * 1000

export const STORAGE_KEY = 'tenshu.commerce.session'

interface StorageLike {
  getItem(key: string): string | null
  setItem(key: string, value: string): void
  removeItem(key: string): void
}

interface StoredSession {
  readonly email: string
  readonly sessionToken: string
}

function defaultStorage(): StorageLike | null {
  if (typeof window === 'undefined') return null
  try {
    const storage = window.localStorage
    if (typeof storage?.getItem !== 'function') return null
    return storage
  } catch {
    return null
  }
}

/**
 * ⚠ The session record (email + token) is **device-local PII** and never crosses
 * to the relay — House Rule #1. It lives here only so a reload does not drop the
 * user's sign-in.
 */
export class CommercePublisherClient implements PublisherClient {
  private account: PublisherAccount | null
  private sessionToken: string | null
  private pendingEmail: string | null = null
  private pendingDisplayName: string | null = null
  private readonly listeners = new Set<() => void>()

  // Plain fields, not constructor parameter properties: the apex compiles with
  // `erasableSyntaxOnly`, which forbids syntax that emits runtime code.
  private readonly ownerIdProvider: OwnerIdProvider
  private readonly api: CommerceApi
  private readonly storage: StorageLike | null
  private readonly entitlementSink: EntitlementSink | null
  private lastApplied: { productId: string; validUntil: number } | null = null

  constructor(
    ownerIdProvider: OwnerIdProvider,
    api: CommerceApi = commerceApi,
    storage: StorageLike | null = defaultStorage(),
    entitlementSink: EntitlementSink | null = null,
  ) {
    this.ownerIdProvider = ownerIdProvider
    this.api = api
    this.storage = storage
    this.entitlementSink = entitlementSink
    const stored = this.loadSession()
    this.sessionToken = stored?.sessionToken ?? null
    this.account =
      stored === null
        ? null
        : { identityMethod: { kind: 'email', address: stored.email }, displayName: null }
  }

  getAccount(): PublisherAccount | null {
    return this.account
  }

  currentSessionToken(): string | null {
    return this.sessionToken === '' ? null : this.sessionToken
  }

  subscribe(listener: () => void): () => void {
    this.listeners.add(listener)
    return () => {
      this.listeners.delete(listener)
    }
  }

  async register(email: string, displayName: string | null): Promise<RegisterResult> {
    const normalised = email.trim()
    if (normalised === '' || !normalised.includes('@')) {
      return { kind: 'error', message: 'Enter a valid email address.' }
    }
    const result = await this.api.register(normalised)
    if (result.kind === 'otpSent') {
      this.pendingEmail = normalised
      this.pendingDisplayName = displayName?.trim() || null
    }
    return result
  }

  async verify(code: string): Promise<VerifyResult> {
    const email = this.pendingEmail
    if (email === null) return { kind: 'error', message: 'Start by entering your email.' }
    const ownerId = await this.ownerId()
    if (ownerId === null) return { kind: 'error', message: NO_IDENTITY }

    const result = await this.api.verify(email, code, ownerId)
    if (result.kind !== 'verified') return result

    this.sessionToken = result.sessionToken
    this.persistSession({ email, sessionToken: result.sessionToken })
    // ⚠ Identity only. Plan and usage come from the synced entitlement; asserting
    // them here as literals is the CE-4 defect this whole surface was rebuilt to
    // avoid.
    this.setAccount({
      identityMethod: { kind: 'email', address: email },
      displayName: this.pendingDisplayName,
    })
    this.pendingEmail = null
    this.pendingDisplayName = null
    // Signing in is the moment a plan bought before registering becomes
    // reachable. Best-effort: a failed sync must not fail the sign-in.
    await this.syncPlan()
    return result
  }

  async syncPlan(): Promise<PlanSyncResult> {
    const token = this.currentSessionToken()
    if (token === null) return { kind: 'signedOut' }
    const minted = await this.api.mint(token)
    if (minted.kind === 'unauthorized') {
      // ⚠ A definitive answer: commerce no longer knows this token. Keeping it
      // would leave every screen showing a signed-in account that can do nothing
      // — the result page waiting on a plan it cannot fetch, Services offering
      // billing controls that all answer 401.
      await this.disconnect()
      return { kind: 'signedOut' }
    }
    if (minted.kind === 'noEntitlement') return { kind: 'unavailable' }
    if (minted.kind === 'error') return minted
    if (this.entitlementSink === null) return { kind: 'unavailable' }
    try {
      return (await this.apply(this.entitlementSink, minted.entitlement))
        ? { kind: 'synced', productId: minted.entitlement.productId }
        : { kind: 'unavailable' }
    } catch {
      return { kind: 'error', message: GENERIC }
    }
  }

  async startCheckout(
    productId: string,
    withdrawalAcknowledged: boolean,
  ): Promise<CheckoutResult> {
    const ownerId = await this.ownerId()
    if (ownerId === null) return { kind: 'error', message: NO_IDENTITY }
    const sessionToken = this.currentSessionToken()
    if (sessionToken === null) return { kind: 'signedOut' }
    return this.api.checkout({ ownerId, productId, withdrawalAcknowledged, sessionToken })
  }

  async openBillingPortal(): Promise<PortalResult> {
    const token = this.currentSessionToken()
    if (token === null) return { kind: 'unauthorized' }
    return this.api.portal(token)
  }

  async redeem(code: string): Promise<PlanRedeemResult> {
    if (code.trim() === '') return { kind: 'invalid' }
    const ownerId = await this.ownerId()
    if (ownerId === null) return { kind: 'error', message: NO_IDENTITY }
    const sessionToken = this.currentSessionToken()
    const result = await this.api.redeem(code, ownerId, sessionToken)
    if (result.kind !== 'redeemed') return result
    // ⚠⚠ The code is spent from here on; every failure below is `notSaved`, never
    // an error that invites another try.
    const notSaved = { kind: 'notSaved', signedIn: sessionToken !== null } as const
    if (this.entitlementSink === null) return notSaved
    try {
      return (await this.apply(this.entitlementSink, result.entitlement))
        ? { kind: 'redeemed', productId: result.entitlement.productId }
        : notSaved
    } catch {
      return notSaved
    }
  }

  async disconnect(): Promise<void> {
    this.sessionToken = null
    this.lastApplied = null
    this.pendingEmail = null
    this.pendingDisplayName = null
    this.storage?.removeItem(STORAGE_KEY)
    this.setAccount(null)
  }

  /** Saves [next] unless it only re-stamps the plan last saved. Throws what the sink throws. */
  private async apply(sink: EntitlementSink, next: SignedEntitlement): Promise<boolean> {
    // ⚠⚠ Every `/mint` answer is a new record — same plan, a `validUntil` a few
    // seconds later — and the realm saves a later `validUntil` as a renewal. Saving
    // each one made the result page's poll a stored row and a sync block per tick.
    const last = this.lastApplied
    if (
      last !== null &&
      last.productId === next.productId &&
      next.validUntil - last.validUntil < RENEWAL_STEP_MS
    ) {
      return true
    }
    const saved = await sink(next)
    if (saved) this.lastApplied = { productId: next.productId, validUntil: next.validUntil }
    return saved
  }

  private async ownerId(): Promise<string | null> {
    const id = await this.ownerIdProvider()
    return id === null || id.trim() === '' ? null : id
  }

  private setAccount(next: PublisherAccount | null) {
    this.account = next
    this.listeners.forEach(l => l())
  }

  private persistSession(session: StoredSession) {
    try {
      this.storage?.setItem(STORAGE_KEY, JSON.stringify(session))
    } catch {
      // Private mode / storage disabled: the session holds for this tab only.
    }
  }

  private loadSession(): StoredSession | null {
    if (this.storage === null) return null
    let raw: string | null
    try {
      raw = this.storage.getItem(STORAGE_KEY)
    } catch {
      return null
    }
    if (raw === null) return null
    try {
      const parsed = JSON.parse(raw) as Partial<StoredSession>
      if (typeof parsed.email !== 'string' || typeof parsed.sessionToken !== 'string') {
        throw new Error('shape')
      }
      return { email: parsed.email, sessionToken: parsed.sessionToken }
    } catch {
      try {
        this.storage.removeItem(STORAGE_KEY)
      } catch {
        // Best-effort eviction.
      }
      return null
    }
  }
}

/**
 * In-memory stand-in for `?mock=1` and for tests, implementing the same contract.
 * Accepts any 6-digit code and never talks to a network.
 *
 * ⚠ Keeps no plan or storage state — there is none to keep. A mock that invented
 * figures is what put wrong numbers on screen for months.
 */
export class MockPublisherClient implements PublisherClient {
  private account: PublisherAccount | null = null
  private sessionToken: string | null = null
  private pendingEmail: string | null = null
  private pendingDisplayName: string | null = null
  private readonly listeners = new Set<() => void>()

  constructor(account: PublisherAccount | null = null) {
    this.account = account
    if (account !== null) this.sessionToken = 'mock-session-token'
  }

  getAccount(): PublisherAccount | null {
    return this.account
  }

  currentSessionToken(): string | null {
    return this.sessionToken
  }

  subscribe(listener: () => void): () => void {
    this.listeners.add(listener)
    return () => {
      this.listeners.delete(listener)
    }
  }

  async register(email: string, displayName: string | null): Promise<RegisterResult> {
    const normalised = email.trim()
    if (normalised === '' || !normalised.includes('@')) {
      return { kind: 'error', message: 'Enter a valid email address.' }
    }
    this.pendingEmail = normalised
    this.pendingDisplayName = displayName?.trim() || null
    return { kind: 'otpSent' }
  }

  async verify(code: string): Promise<VerifyResult> {
    const email = this.pendingEmail
    if (email === null) return { kind: 'error', message: 'Start by entering your email.' }
    if (!/^\d{6}$/.test(code.trim())) return { kind: 'invalidCode' }
    this.sessionToken = 'mock-session-token'
    this.setAccount({
      identityMethod: { kind: 'email', address: email },
      displayName: this.pendingDisplayName,
    })
    this.pendingEmail = null
    this.pendingDisplayName = null
    return { kind: 'verified', sessionToken: this.sessionToken }
  }

  async startCheckout(_productId: string, ack: boolean): Promise<CheckoutResult> {
    // Mirrors the service's refusal so the mock cannot be used to "prove" a
    // checkout works without the acknowledgement.
    if (!ack) return { kind: 'error', message: 'acknowledgement required' }
    if (this.sessionToken === null) return { kind: 'signedOut' }
    return { kind: 'ready', checkoutUrl: 'https://checkout.example/mock-session' }
  }

  async openBillingPortal(): Promise<PortalResult> {
    if (this.sessionToken === null) return { kind: 'unauthorized' }
    return { kind: 'ready', portalUrl: 'https://billing.example/mock-portal' }
  }

  async redeem(code: string): Promise<PlanRedeemResult> {
    return code.trim() === '' ? { kind: 'invalid' } : { kind: 'redeemed', productId: PRODUCT_FREE }
  }

  async syncPlan(): Promise<PlanSyncResult> {
    return this.sessionToken === null
      ? { kind: 'signedOut' }
      : { kind: 'synced', productId: PRODUCT_FREE }
  }

  async disconnect(): Promise<void> {
    this.sessionToken = null
    this.pendingEmail = null
    this.pendingDisplayName = null
    this.setAccount(null)
  }

  private setAccount(next: PublisherAccount | null) {
    this.account = next
    this.listeners.forEach(l => l())
  }
}
