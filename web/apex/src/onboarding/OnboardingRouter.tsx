// Top-level landing-state router (UX §2). Calls `Session.detect()` on
// mount and routes the user to the right flow:
//   active    → the overview (`/overview`)
//   locked    → §3 Locked unlock (`/lock`)
//   firstTime → §4.0 chooser (`/onboarding`)
//
// The detect call has no network round-trip per the SDK contract, so a
// brief local spinner is the entire pre-route delay.
import { useEffect, useState } from 'react'
import { Navigate } from 'react-router'
import { OVERVIEW_PATH } from '../lib/overviewPath'
import { useTranslation } from 'react-i18next'
import type { SessionState } from '@tenshu/sdk/onboarding'
import { useOnboardingSdk } from '../lib/useOnboardingSdk'

export function OnboardingRouter() {
  const sdk = useOnboardingSdk()
  const [state, setState] = useState<SessionState | null>(null)
  useEffect(() => {
    let cancelled = false
    void sdk.Session.detect().then(s => {
      if (!cancelled) setState(s)
    })
    return () => {
      cancelled = true
    }
  }, [sdk])
  if (state === null) return <BootSplash />
  switch (state.kind) {
    case 'active':
      return <Navigate to={OVERVIEW_PATH} replace />
    case 'locked':
      return <Navigate to="/lock" replace />
    case 'firstTime':
      return <Navigate to="/onboarding" replace />
  }
}

function BootSplash() {
  const { t } = useTranslation('onboarding')
  return (
    <div className="grid min-h-screen place-items-center bg-surface text-text">
      <div className="text-sm text-text-2">{t('router.loading')}</div>
    </div>
  )
}
