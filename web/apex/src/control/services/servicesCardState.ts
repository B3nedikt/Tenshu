// What the Services card shows, derived from two independent signals.
//
// The web mirror of native's `ServicesCardState` (`:control-shared-logic`,
// commerce CE-4 / D5). Kept as a pure module so the four reachable combinations
// can be pinned without rendering anything.
//
// ⚠⚠ The split is the whole point. `storage` is the ONLY source of plan and
// usage, and it comes from the synced entitlement — true on every device,
// including one that has never signed in here. `identity` is non-null exactly
// when this device holds an account session, and decides only whether
// identity/billing *actions* are offered.
//
// Before this split the apex card read its figures off the local mock account,
// which writes them once as literals at signup and never revises them. The card
// was also gated on `account === null`, so the honest state was reachable only
// while signed *out* — signing in replaced live figures with invented ones.
// That gate was the defect, not the mock behind it.
import type { PublisherAccount, IdentityMethod } from './publisherClient'
import { BASIC_LIMIT_BYTES, FREE_LIMIT_BYTES, PREMIUM_LIMIT_BYTES } from './plans'

/** A storage snapshot as the SDK reports it (`@tenshu/sdk/storage`). */
export interface StorageStatusSnapshot {
  readonly hasStorage: boolean
  readonly usedBytes: number
  readonly limitBytes: number
}

/** The plan a synced entitlement grants, as far as the card needs to name it. */
export type SyncedPlanTier = 'Free' | 'Basic' | 'Premium'

/**
 * Tier quotas (plans.ts, mirroring `EntitlementProducts`). The apex reads the derived limit off
 * `StorageStatus` and never sees a product id, so the card matches on the number.
 */
export { BASIC_LIMIT_BYTES, FREE_LIMIT_BYTES, PREMIUM_LIMIT_BYTES }

/** Storage figures + the tier they imply, read straight off the entitlement. */
export interface SyncedStorage {
  readonly usedBytes: number
  readonly limitBytes: number
  readonly plan: SyncedPlanTier
}

export interface ServicesCardState {
  /** Funded storage, or `null` when this device has no storage plan bound. */
  readonly storage: SyncedStorage | null
  /** Non-null exactly when this device holds an account session. */
  readonly identity: IdentityMethod | null
}

export const EMPTY_CARD_STATE: ServicesCardState = { storage: null, identity: null }

/** True when this device holds a session, so identity actions may be offered. */
export function signedInHere(state: ServicesCardState): boolean {
  return state.identity !== null
}

/**
 * Reverse-map a quota to its tier. The limit on the derived `StorageStatus` is
 * set from the entitlement's product id, so it is exactly one of the constants
 * above; anything else falls back to Free — the safe floor, which can only
 * under-claim, never invent a paid tier. If usage reporting ever makes the limit
 * stop equalling a tier constant, carry the product id on the status instead.
 */
export function planTierForLimit(limitBytes: number): SyncedPlanTier {
  if (limitBytes === PREMIUM_LIMIT_BYTES) return 'Premium'
  if (limitBytes === BASIC_LIMIT_BYTES) return 'Basic'
  return 'Free'
}

/**
 * The funded-storage projection of a status — ⚠ **not** gated on the local
 * account. That gate is exactly what made signing in regress the figures.
 */
export function fundedStorage(status: StorageStatusSnapshot | null): SyncedStorage | null {
  if (status === null || !status.hasStorage) return null
  return {
    usedBytes: status.usedBytes,
    limitBytes: status.limitBytes,
    plan: planTierForLimit(status.limitBytes),
  }
}

/** Pure derivation of the card state from the two signals the page binds. */
export function servicesCardState(
  account: PublisherAccount | null,
  status: StorageStatusSnapshot | null,
): ServicesCardState {
  return {
    storage: fundedStorage(status),
    identity: account?.identityMethod ?? null,
  }
}

/**
 * What the storage half of the card should render.
 *
 * ⚠ `provisioning` is a real state, not a loading artefact: a device that has
 * just signed up or purchased legitimately reports `hasStorage` with a limit of
 * 0 for a few seconds while the entitlement syncs back. Rendering that as
 * "0 B of 0 B" tells a paying user they have no storage.
 */
export type StorageSectionState =
  | { readonly kind: 'checking' }
  | { readonly kind: 'none' }
  | { readonly kind: 'provisioning' }
  | { readonly kind: 'figures'; readonly storage: SyncedStorage }

export function storageSectionState(
  storage: SyncedStorage | null,
  settled: boolean,
): StorageSectionState {
  if (!settled) return { kind: 'checking' }
  if (storage === null) return { kind: 'none' }
  if (storage.limitBytes <= 0) return { kind: 'provisioning' }
  return { kind: 'figures', storage }
}

/** Gauge fill, clamped. A zero limit yields 0 rather than NaN/Infinity. */
export function storageProgressPct(storage: SyncedStorage): number {
  if (storage.limitBytes <= 0) return 0
  const pct = (storage.usedBytes / storage.limitBytes) * 100
  return Math.max(0, Math.min(100, pct))
}
