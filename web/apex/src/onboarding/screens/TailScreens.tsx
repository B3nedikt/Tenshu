// Shared tail screens reused by §4a (existing device), §4b (new user),
// and §6 (recovery). Every path sets up a passkey. A new realm additionally
// gets "Get Tenshu on your devices", and a new account (not try-mode) the
// plan step.
//
// WebAuthn ceremonies live on the web side: `registerPasskey` runs the
// `navigator.credentials.create()` ceremony, then we hand the result
// to `sdk.Session.registerPasskey(...)` so the SDK has a stored
// credential to verify against on subsequent `unlock(assertion)` calls.
// `getPasskeyAssertion` + `Session.unlock` close the loop on the §3
// locked path (see `LockedFlow`).
import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useOnboardingSdk } from '../../lib/useOnboardingSdk'
import { CONTROL_MAC_DOWNLOAD_URL, CONTROL_PLAY_URL } from '../../lib/storeLinks'
import { TierCard } from '../../control/services/AccountPickerPage'
import { TIERS } from '../../control/services/tiers'
import {
  isWebAuthnSupported,
  registerPasskey,
  type WebAuthnFailure,
} from '../../lib/webauthn'
import {
  LinkButton,
  OnboardingActions,
  OnboardingCard,
  OnboardingProse,
  OnboardingTitle,
  PrimaryButton,
} from '../OnboardingShell'

export function PasskeySetupScreen({
  deviceName,
  onContinue,
  onSkip,
  isFirstEncounter = false,
}: {
  readonly deviceName: string
  readonly onContinue: () => void
  readonly onSkip: () => void
  readonly isFirstEncounter?: boolean
}) {
  const { t } = useTranslation('onboarding')
  const sdk = useOnboardingSdk()
  const [error, setError] = useState<WebAuthnFailure | null>(null)
  const [registering, setRegistering] = useState(false)
  const supported = isWebAuthnSupported()

  async function onRegister() {
    setError(null)
    setRegistering(true)
    const userId = new Int8Array(16)
    crypto.getRandomValues(new Uint8Array(userId.buffer, userId.byteOffset, userId.byteLength))
    const result = await registerPasskey({
      userId,
      userName: deviceName,
      displayName: deviceName,
    })
    if (!result.ok) {
      setRegistering(false)
      setError(result.error)
      return
    }
    // Hand the registration to the SDK so subsequent unlocks have a
    // stored credential to verify against. Treat a failed handoff as
    // an "unknown" WebAuthn error so the existing error surface
    // applies — the user can retry without re-running the browser
    // ceremony from scratch (registerPasskey is idempotent on the
    // SDK side; latest registration wins).
    const stored = await sdk.Session.registerPasskey(result.value)
    setRegistering(false)
    if (!stored.ok) {
      setError({
        kind: 'unknown',
        message:
          typeof stored.error === 'string'
            ? stored.error
            : t('tail.passkey.saveFailedFallback'),
      })
      return
    }
    onContinue()
  }

  if (!supported) {
    return (
      <OnboardingCard>
        <OnboardingTitle>{t('tail.passkey.fallback.title')}</OnboardingTitle>
        <OnboardingProse>
          <p>{t('tail.passkey.fallback.body')}</p>
          <p className="text-xs text-text-2">{t('tail.passkey.fallback.placeholder')}</p>
        </OnboardingProse>
        <OnboardingActions>
          <PrimaryButton onClick={onContinue}>{t('tail.passkey.fallback.continue')}</PrimaryButton>
        </OnboardingActions>
      </OnboardingCard>
    )
  }

  return (
    <OnboardingCard>
      <OnboardingTitle>{t('tail.passkey.title')}</OnboardingTitle>
      <OnboardingProse>
        <p>{t('tail.passkey.body')}</p>
        {isFirstEncounter && <p>{t('tail.passkey.firstEncounterNote')}</p>}
      </OnboardingProse>
      {error !== null && <PasskeyErrorBanner error={error} />}
      <OnboardingActions>
        <PrimaryButton onClick={() => void onRegister()} disabled={registering}>
          {registering ? t('tail.passkey.registering') : t('tail.passkey.register')}
        </PrimaryButton>
        <LinkButton onClick={onSkip}>{t('tail.passkey.skip')}</LinkButton>
      </OnboardingActions>
    </OnboardingCard>
  )
}

function PasskeyErrorBanner({ error }: { readonly error: WebAuthnFailure }) {
  const { t } = useTranslation('onboarding')
  const message =
    error.kind === 'cancelled'
      ? t('tail.passkey.error.cancelled')
      : error.kind === 'unsupported'
        ? t('tail.passkey.error.unsupported')
        : error.kind === 'hardware'
          ? t('tail.passkey.error.hardware')
          : error.kind === 'wrongCredential'
            ? t('tail.passkey.error.wrongCredential')
            : t('tail.passkey.error.other', { message: error.message })
  return (
    <p
      role="alert"
      className="mt-4 rounded-md border border-danger/30 bg-danger/10 px-3 py-2 text-sm text-danger"
    >
      {message}
    </p>
  )
}

// The native apps, most-installed first. An entry without a URL has no store
// listing yet and renders as "coming soon"; give it one in `lib/storeLinks.ts`.
type AppPlatformId = 'android' | 'iphone' | 'mac'

const APP_PLATFORMS: readonly { readonly id: AppPlatformId; readonly url?: string }[] = [
  { id: 'android', url: CONTROL_PLAY_URL },
  { id: 'mac', url: CONTROL_MAC_DOWNLOAD_URL },
  { id: 'iphone' },
]

export function GetAppsScreen({
  onContinue,
}: {
  readonly onContinue: () => void
}) {
  const { t } = useTranslation('onboarding')
  return (
    <OnboardingCard>
      <OnboardingTitle>{t('tail.getApps.title')}</OnboardingTitle>
      <OnboardingProse>
        <p>{t('tail.getApps.body')}</p>
      </OnboardingProse>
      <ul className="mt-6 space-y-3">
        {APP_PLATFORMS.map(p => (
          <li key={p.id}>
            {p.url === undefined ? (
              <div className="flex items-center justify-between rounded-lg border border-line px-4 py-3 text-sm text-text-2">
                <span>{t(`tail.getApps.platform.${p.id}`)}</span>
                <span className="text-xs">{t('tail.getApps.comingSoon')}</span>
              </div>
            ) : (
              <a
                href={p.url}
                target="_blank"
                rel="noopener noreferrer"
                className="flex items-center justify-between rounded-lg bg-text px-4 py-3 text-sm font-medium text-surface shadow-sm transition-opacity hover:opacity-90"
              >
                <span>{t(`tail.getApps.platform.${p.id}`)}</span>
                <span aria-hidden>→</span>
              </a>
            )}
          </li>
        ))}
      </ul>
      <OnboardingActions>
        <PrimaryButton onClick={onContinue}>{t('tail.getApps.continue')}</PrimaryButton>
      </OnboardingActions>
    </OnboardingCard>
  )
}

// The plan step that ends the new-account path. The cards are the control
// plane's own (`AccountPickerPage`), so the tiers and their routing — paid
// tiers through the acknowledgement step — cannot drift apart. Choosing hands
// back a destination; onboarding finishes its session, then navigates there.
export function PlanScreen({
  onChoose,
  onNotNow,
}: {
  readonly onChoose: (to: string) => void
  readonly onNotNow: () => void
}) {
  const { t } = useTranslation('onboarding')
  return (
    <OnboardingCard>
      <OnboardingTitle>{t('tail.plan.title')}</OnboardingTitle>
      <OnboardingProse>
        <p>{t('tail.plan.body')}</p>
      </OnboardingProse>
      <div className="mt-6 space-y-3">
        {TIERS.map(tier => (
          <TierCard key={tier.id} tier={tier} signedIn={false} onChoose={onChoose} />
        ))}
      </div>
      <OnboardingActions>
        <LinkButton onClick={() => onChoose('/control/services/redeem')}>
          {t('tail.plan.redeem')}
        </LinkButton>
        <LinkButton onClick={onNotNow}>{t('tail.plan.notNow')}</LinkButton>
      </OnboardingActions>
    </OnboardingCard>
  )
}

export function CompletingSessionScreen({
  onCompleted,
}: {
  readonly onCompleted: () => void
}) {
  const { t } = useTranslation('onboarding')
  // Persists a fresh 24h session token via Session.persist, then routes
  // to the control plane. This is where §4a/§4b/§6 ultimately converge
  // before redirecting to /overview.
  const sdk = useOnboardingSdk()
  useEffect(() => {
    let cancelled = false
    const bytes = new Int8Array(32)
    crypto.getRandomValues(new Uint8Array(bytes.buffer, bytes.byteOffset, bytes.byteLength))
    void sdk.Session.persist({
      bytes,
      expiresAtMs: Date.now() + 24 * 60 * 60 * 1000,
    }).then(() => {
      if (!cancelled) onCompleted()
    })
    return () => {
      cancelled = true
    }
  }, [sdk, onCompleted])
  return (
    <OnboardingCard>
      <OnboardingTitle>{t('tail.completing.title')}</OnboardingTitle>
    </OnboardingCard>
  )
}
