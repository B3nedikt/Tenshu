// Persistent "you're signed in as {device_name}" banner for returning
// visitors with an active session (web-apex-landing.md §4). Sits above
// the marketing header; clicking dismisses for the tab session via
// sessionStorage. Mounted exactly once per LandingPage so screen-reader
// announcement happens at the document level (§9 a11y note).
import { useState } from 'react'
import { Link, useNavigate } from 'react-router'
import { OVERVIEW_PATH } from '../lib/overviewPath'
import { Trans, useTranslation } from 'react-i18next'
import { RUNTIME_ELSEWHERE } from '../lib/runtimeElsewhere'
import { useLandingCta } from './useLandingCta'
import { dismissBanner, readBannerDismissed } from './signedInBannerStorage'

export function SignedInBanner() {
  const { t } = useTranslation('landing')
  const state = useLandingCta()
  const navigate = useNavigate()
  const [dismissed, setDismissed] = useState<boolean>(readBannerDismissed)

  if (state.kind !== 'resolved' || state.session.kind !== 'active' || dismissed) {
    return null
  }

  function dismiss() {
    dismissBanner()
    setDismissed(true)
  }

  // Lock = drop the working session, keep the realm's wrapped keys in IDB
  // so unlock can re-derive. This is the reversible "step away" action; it
  // does NOT remove this device from the realm (no ghost-browser concern —
  // a locked device is still a valid member). Mirrors DeviceMenu.lockNow.
  async function lockNow() {
    // The one runtime-needing action on a public page: start it on the click, not on the visit.
    // ⚠ Loaded on the click as well: a static import would ship the Kotlin runtime with the
    // landing page (web-app-suite.md F6).
    const [{ ensureRuntime }, { Session }] = await Promise.all([
      import('../lib/runtime'),
      import('@tenshu/sdk/onboarding'),
    ])
    ensureRuntime()
    try {
      await Session.clear({})
    } catch (error) {
      // Another tab holds the runtime: RuntimeLeaseNotice (above every route) is now showing
      // "open in another tab" with "Use here", which is the answer — so stay put rather than
      // navigate to a /lock this tab cannot serve. Anything else is a real failure: surface it.
      if (error instanceof Error && error.message.includes(RUNTIME_ELSEWHERE)) return
      throw error
    }
    navigate('/lock', { replace: true })
  }

  const deviceName = state.session.deviceName ?? t('banner.thisDevice')

  return (
    <div
      role="status"
      aria-live="polite"
      className="border-b border-line bg-surface text-sm"
    >
      <div className="mx-auto flex max-w-5xl items-center justify-between gap-4 px-6 py-2">
        <span className="text-text-2">
          <Trans
            ns="landing"
            i18nKey="banner.signedInAs"
            values={{ deviceName }}
            components={{
              strong: <strong className="font-semibold" />,
              apps: (
                <Link
                  to={OVERVIEW_PATH}
                  onClick={dismiss}
                  className="font-medium text-text underline-offset-4 hover:underline"
                />
              ),
            }}
          />
        </span>
        <div className="flex shrink-0 items-center gap-1">
          <button
            type="button"
            onClick={() => void lockNow()}
            aria-label={t('banner.lockAriaLabel')}
            className="rounded-md px-2 py-1 text-xs font-medium text-text-2 hover:bg-surface-3 hover:text-text"
          >
            {t('banner.lock')}
          </button>
          <button
            type="button"
            onClick={dismiss}
            aria-label={t('banner.dismiss')}
            className="rounded-md p-1 text-xs text-text-2 hover:bg-surface-3 hover:text-text"
          >
            {t('banner.dismiss')}
          </button>
        </div>
      </div>
    </div>
  )
}
