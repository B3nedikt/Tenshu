import 'fake-indexeddb/auto'
import { afterEach, describe, expect, it } from 'vitest'
import { getRecoveryStatus, setRecoveryVerified } from './recoveryStatus'

afterEach(async () => {
  await new Promise<void>((resolve, reject) => {
    const req = indexedDB.deleteDatabase('tenshu-apex-control')
    req.onsuccess = () => resolve()
    req.onerror = () => reject(req.error)
    req.onblocked = () => resolve()
  })
})

const SIX_MONTHS_MS = 6 * 30 * 24 * 60 * 60 * 1000

describe('recoveryStatus', () => {
  it("reports 'never' when no mark is stored", async () => {
    const status = await getRecoveryStatus()
    expect(status.kind).toBe('never')
  })

  it("reports 'verified' for a recent mark", async () => {
    const now = 2_000_000_000_000
    await setRecoveryVerified(now - 30 * 24 * 60 * 60 * 1000)
    const status = await getRecoveryStatus(now)
    expect(status.kind).toBe('verified')
  })

  it("reports 'stale' for a mark older than 6 months", async () => {
    const now = 2_000_000_000_000
    await setRecoveryVerified(now - SIX_MONTHS_MS - 1)
    const status = await getRecoveryStatus(now)
    expect(status.kind).toBe('stale')
  })
})
