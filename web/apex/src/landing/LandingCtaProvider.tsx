// Mounts at the top of the LandingPage and probes `Session.detect()`.
// Children read the result via `useLandingCta()`.
//
// Per web-apex-landing.md §4 + §8 the probe happens once per route
// mount — not per block — so an N-block landing isn't N-times-probed.
// It additionally re-probes when the tab becomes visible again, so a
// session change made elsewhere (locking/unlocking/disconnecting in
// another tab, or returning from the unlock flow) is reflected without
// a hard reload. Re-probes overwrite in place — no flash back to
// `loading` — so the CTA only changes once the fresh state resolves.
import { useEffect, useState, type ReactNode } from 'react'
import { useOnboardingSdk } from '../lib/useOnboardingSdk'
import { LandingCtaContext, type LandingCtaState } from './landingCtaContext'

export function LandingCtaProvider({ children }: { readonly children: ReactNode }) {
  const sdk = useOnboardingSdk()
  const [state, setState] = useState<LandingCtaState>({ kind: 'loading' })
  useEffect(() => {
    let cancelled = false
    function probe() {
      // ⚠ `peek`, not `detect`, when the SDK has it: detect() starts the runtime, which takes the
      // one-per-origin lock — a landing page must never do that (docs/web/architecture.md).
      const probeSession = sdk.Session.peek ?? (() => sdk.Session.detect())
      void probeSession().then(session => {
        if (!cancelled) setState({ kind: 'resolved', session })
      })
    }
    probe()
    function onVisibility() {
      if (document.visibilityState === 'visible') probe()
    }
    document.addEventListener('visibilitychange', onVisibility)
    return () => {
      cancelled = true
      document.removeEventListener('visibilitychange', onVisibility)
    }
  }, [sdk])
  return <LandingCtaContext.Provider value={state}>{children}</LandingCtaContext.Provider>
}
