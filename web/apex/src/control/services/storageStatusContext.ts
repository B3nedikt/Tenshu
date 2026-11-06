// The Services card's storage source: a one-shot reader over the SDK's
// `Storage.status()`, injectable so pages that consume it stay testable.
//
// ⚠ This module deliberately does NOT import `@tenshu/sdk/storage`. That entry
// point pulls the Gradle-built Kotlin bundle into the module graph, and any test
// touching it then needs the bundle built first — the reason eight apex suites
// already fail in a fresh clone. The production reader lives in
// `realStorageStatus.ts` and is wired in at the app root, the same shape the
// onboarding SDK uses (`onboardingSdk.tsx` / `realOnboardingSdk.ts`).
//
// ⚠ A `null` reader means "no source configured" and settles as *no storage*,
// not as a spinner. A card that never settles is indistinguishable from one that
// is still loading, and this one is reachable in tests and in any future surface
// that forgets the provider.
import { createContext, useContext, useEffect, useState } from 'react'
import type { StorageStatusSnapshot } from './servicesCardState'

/** Reads one storage snapshot; `null` when the proxy has derived none yet. */
export type StorageStatusReader = () => Promise<StorageStatusSnapshot | null>

export const StorageStatusContext = createContext<StorageStatusReader | null>(null)

export interface StorageStatusResult {
  readonly status: StorageStatusSnapshot | null
  /**
   * False until the first read resolves. Distinguishes "still asking" from
   * "asked, and the answer is no storage" — collapsing the two is what makes a
   * gauge sit at zero looking authoritative.
   */
  readonly settled: boolean
}

/**
 * [revision] re-reads when it changes. The read is a snapshot, so a caller that
 * has just changed the plan bumps it rather than waiting for a remount.
 */
export function useStorageStatus(revision = 0): StorageStatusResult {
  const read = useContext(StorageStatusContext)
  const [result, setResult] = useState<StorageStatusResult>({ status: null, settled: false })

  useEffect(() => {
    if (read === null) return
    let cancelled = false
    // ⚠ No reset to "unsettled" here. Writing state synchronously from an effect
    // is what `react-hooks/set-state-in-effect` forbids, and the reset is not
    // wanted anyway: `read` is a stable binding in practice, and on the rare
    // change keeping the previous figures until the new ones land beats
    // flickering the card back through "Checking…".
    void read()
      .then(status => {
        if (!cancelled) setResult({ status, settled: true })
      })
      .catch(() => {
        // ⚠ A failed read settles as "no storage", never as a zeroed gauge: the
        // card's other states already say something true, and an error banner
        // for a display-only figure would be louder than the fact deserves.
        if (!cancelled) setResult({ status: null, settled: true })
      })
    return () => {
      cancelled = true
    }
  }, [read, revision])

  // Derived at render, not written from the effect: with no reader configured
  // there is nothing to wait for, and a card that never settles is
  // indistinguishable from one still loading.
  if (read === null) return { status: null, settled: true }
  return result
}
