// Pure derivation of the Services card state (commerce CE-4 / D5).
import { describe, expect, it } from 'vitest'
import {
  BASIC_LIMIT_BYTES,
  FREE_LIMIT_BYTES,
  PREMIUM_LIMIT_BYTES,
  fundedStorage,
  planTierForLimit,
  servicesCardState,
  signedInHere,
  storageProgressPct,
  storageSectionState,
  type StorageStatusSnapshot,
} from './servicesCardState'
import type { PublisherAccount } from './publisherClient'

const ACCOUNT: PublisherAccount = {
  identityMethod: { kind: 'email', address: 'test@example.com' },
  displayName: null,
}

function status(over: Partial<StorageStatusSnapshot> = {}): StorageStatusSnapshot {
  return { hasStorage: true, usedBytes: 0, limitBytes: FREE_LIMIT_BYTES, ...over }
}

describe('planTierForLimit', () => {
  it('maps each tier quota to its name', () => {
    expect(planTierForLimit(FREE_LIMIT_BYTES)).toBe('Free')
    expect(planTierForLimit(BASIC_LIMIT_BYTES)).toBe('Basic')
    expect(planTierForLimit(PREMIUM_LIMIT_BYTES)).toBe('Premium')
  })

  it('falls back to Free for an unrecognised quota, never to a paid tier', () => {
    // The safe floor: under-claiming is recoverable, inventing a paid tier is not.
    expect(planTierForLimit(123)).toBe('Free')
    expect(planTierForLimit(0)).toBe('Free')
  })

  it('pins Free at 5 GiB, not the stale web-genesis 1 GiB', () => {
    expect(FREE_LIMIT_BYTES).toBe(5 * 1024 * 1024 * 1024)
  })
})

describe('fundedStorage', () => {
  it('is null when there is no status yet', () => {
    expect(fundedStorage(null)).toBeNull()
  })

  it('is null when the device holds no storage plan', () => {
    expect(fundedStorage(status({ hasStorage: false }))).toBeNull()
  })

  it('carries the figures and the derived tier through', () => {
    const funded = fundedStorage(status({ usedBytes: 42, limitBytes: BASIC_LIMIT_BYTES }))
    expect(funded).toEqual({ usedBytes: 42, limitBytes: BASIC_LIMIT_BYTES, plan: 'Basic' })
  })
})

describe('servicesCardState', () => {
  // ⚠⚠ The regression this whole change exists to prevent: the figures must not
  // depend on whether this device holds a session. Before the split, signing in
  // is what replaced live figures with invented ones.
  it('reports the same storage signed in and signed out', () => {
    const signedOut = servicesCardState(null, status({ usedBytes: 7 }))
    const signedIn = servicesCardState(ACCOUNT, status({ usedBytes: 7 }))
    expect(signedIn.storage).toEqual(signedOut.storage)
  })

  it('storage + identity — subscribed on this device', () => {
    const state = servicesCardState(ACCOUNT, status())
    expect(state.storage?.plan).toBe('Free')
    expect(signedInHere(state)).toBe(true)
  })

  it('storage, no identity — a paired device with a real plan', () => {
    const state = servicesCardState(null, status({ limitBytes: PREMIUM_LIMIT_BYTES }))
    expect(state.storage?.plan).toBe('Premium')
    expect(signedInHere(state)).toBe(false)
  })

  it('identity, no storage — signed in, entitlement not synced back yet', () => {
    const state = servicesCardState(ACCOUNT, null)
    expect(state.storage).toBeNull()
    expect(signedInHere(state)).toBe(true)
  })

  it('neither — no account and no funded storage', () => {
    const state = servicesCardState(null, null)
    expect(state.storage).toBeNull()
    expect(signedInHere(state)).toBe(false)
  })
})

describe('storageSectionState', () => {
  it('is checking until the first read settles', () => {
    expect(storageSectionState(null, false)).toEqual({ kind: 'checking' })
  })

  it('distinguishes "asked, no storage" from "still asking"', () => {
    expect(storageSectionState(null, true)).toEqual({ kind: 'none' })
  })

  // ⚠ A limit of 0 with storage bound is the seconds after signup/purchase.
  // "0 B of 0 B" would tell a paying user they have nothing.
  it('shows provisioning rather than a zeroed gauge', () => {
    const zero = { usedBytes: 0, limitBytes: 0, plan: 'Free' as const }
    expect(storageSectionState(zero, true)).toEqual({ kind: 'provisioning' })
  })

  it('shows figures once there is a real limit', () => {
    const storage = { usedBytes: 1, limitBytes: FREE_LIMIT_BYTES, plan: 'Free' as const }
    expect(storageSectionState(storage, true)).toEqual({ kind: 'figures', storage })
  })
})

describe('storageProgressPct', () => {
  it('is a clamped percentage', () => {
    expect(storageProgressPct({ usedBytes: 50, limitBytes: 100, plan: 'Free' })).toBe(50)
    expect(storageProgressPct({ usedBytes: 500, limitBytes: 100, plan: 'Free' })).toBe(100)
  })

  it('is 0 rather than NaN when the limit is 0', () => {
    expect(storageProgressPct({ usedBytes: 0, limitBytes: 0, plan: 'Free' })).toBe(0)
  })
})
