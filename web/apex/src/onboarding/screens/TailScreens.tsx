// Shared tail screens reused by §4a (existing device), §4b (new user),
// and §6 (recovery). The flow is: passkey setup → browser-sync warning
// (+ per-browser opt-out) → layered "How you can use Tenshu" explainer.
// §4b additionally shows the storage-tier informational screen.
//
// WebAuthn ceremonies live on the web side: `registerPasskey` runs the
// `navigator.credentials.create()` ceremony, then we hand the result
// to `sdk.Session.registerPasskey(...)` so the SDK has a stored
// credential to verify against on subsequent `unlock(assertion)` calls.
// `getPasskeyAssertion` + `Session.unlock` close the loop on the §3
// locked path (see `LockedFlow`).
import { useEffect, useState } from 'react'
import { Trans, useTranslation } from 'react-i18next'
import type { TFunction } from 'i18next'
import { useOnboardingSdk } from '../../lib/useOnboardingSdk'
import {
  browserFamilyLabel,
  detectBrowserFamily,
  type BrowserFamily,
} from '../../lib/browserDetect'
import {
  isWebAuthnSupported,
  registerPasskey,
  type WebAuthnFailure,
} from '../../lib/webauthn'
import {
  Hint,
  LinkButton,
  OnboardingActions,
  OnboardingCard,
  OnboardingProse,
  OnboardingTitle,
  PrimaryButton,
  SecondaryButton,
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

export function BrowserSyncWarningScreen({
  onSeparate,
  onSkip,
}: {
  readonly onSeparate: () => void
  readonly onSkip: () => void
}) {
  const { t } = useTranslation('onboarding')
  return (
    <OnboardingCard>
      <OnboardingTitle>{t('tail.syncWarning.title')}</OnboardingTitle>
      <OnboardingProse>
        <p>{t('tail.syncWarning.body1')}</p>
        <p>
          <Trans
            ns="onboarding"
            i18nKey="tail.syncWarning.body2"
            components={{ strong: <strong /> }}
          />
        </p>
        <p className="font-medium text-text">{t('tail.syncWarning.question')}</p>
      </OnboardingProse>
      <OnboardingActions>
        <PrimaryButton onClick={onSeparate}>{t('tail.syncWarning.separate')}</PrimaryButton>
        <LinkButton onClick={onSkip}>{t('tail.syncWarning.skip')}</LinkButton>
      </OnboardingActions>
    </OnboardingCard>
  )
}

export function SyncOptOutScreen({
  initialFamily,
  onContinue,
}: {
  readonly initialFamily?: BrowserFamily
  readonly onContinue: () => void
}) {
  const { t } = useTranslation('onboarding')
  const detected = initialFamily ?? detectBrowserFamily()
  const [family, setFamily] = useState<BrowserFamily>(detected)
  return (
    <OnboardingCard>
      <OnboardingTitle>{t('tail.syncOptOut.title', { family: browserFamilyLabel(family) })}</OnboardingTitle>
      <BrowserChooser current={family} onChange={setFamily} />
      <OnboardingProse>
        <SyncOptOutSteps t={t} family={family} />
      </OnboardingProse>
      <OnboardingActions>
        <PrimaryButton onClick={onContinue}>{t('tail.syncOptOut.doneAction')}</PrimaryButton>
        <LinkButton onClick={onContinue}>{t('tail.syncOptOut.skipAction')}</LinkButton>
      </OnboardingActions>
      <Hint>{t('tail.syncOptOut.hint')}</Hint>
    </OnboardingCard>
  )
}

function BrowserChooser({
  current,
  onChange,
}: {
  readonly current: BrowserFamily
  readonly onChange: (next: BrowserFamily) => void
}) {
  const { t } = useTranslation('onboarding')
  const options: readonly BrowserFamily[] = ['chrome', 'edge', 'firefox', 'safari', 'other']
  return (
    <div className="mt-4 flex flex-wrap items-center gap-2">
      <span className="text-xs font-medium text-text-2">{t('tail.syncOptOut.browserLabel')}</span>
      {options.map(o => (
        <button
          key={o}
          type="button"
          onClick={() => onChange(o)}
          className={[
            'rounded-md border px-2.5 py-1 text-xs',
            o === current
              ? 'border-primary bg-primary text-on-primary'
              : 'border-line bg-surface text-text-2',
          ].join(' ')}
        >
          {browserFamilyLabel(o)}
        </button>
      ))}
    </div>
  )
}

function SyncOptOutSteps({ t, family }: { readonly t: TFunction; readonly family: BrowserFamily }) {
  if (family === 'chrome' || family === 'edge') {
    return (
      <>
        <ol className="ml-5 list-decimal space-y-1 text-sm">
          <li>{t('tail.syncOptOut.chrome.step1')}</li>
          <li>
            <Trans ns="onboarding" i18nKey="tail.syncOptOut.chrome.step2" components={{ em: <em /> }} />
          </li>
          <li>
            <Trans ns="onboarding" i18nKey="tail.syncOptOut.chrome.step3" components={{ em: <em /> }} />
          </li>
          <li>
            <Trans ns="onboarding" i18nKey="tail.syncOptOut.chrome.step4" components={{ strong: <strong /> }} />
          </li>
        </ol>
        <p className="text-sm">{t('tail.syncOptOut.chrome.outcome')}</p>
      </>
    )
  }
  if (family === 'firefox') {
    return (
      <ol className="ml-5 list-decimal space-y-1 text-sm">
        <li>{t('tail.syncOptOut.firefox.step1')}</li>
        <li>
          <Trans ns="onboarding" i18nKey="tail.syncOptOut.firefox.step2" components={{ em: <em /> }} />
        </li>
        <li>
          <Trans ns="onboarding" i18nKey="tail.syncOptOut.firefox.step3" components={{ em: <em /> }} />
        </li>
      </ol>
    )
  }
  if (family === 'safari') {
    return (
      <>
        <p className="text-sm">{t('tail.syncOptOut.safari.intro')}</p>
        <ol className="ml-5 list-decimal space-y-1 text-sm">
          <li>{t('tail.syncOptOut.safari.step1')}</li>
          <li>
            <Trans ns="onboarding" i18nKey="tail.syncOptOut.safari.step2" components={{ em: <em /> }} />
          </li>
          <li>
            <Trans ns="onboarding" i18nKey="tail.syncOptOut.safari.step3" components={{ em: <em /> }} />
          </li>
        </ol>
        <p className="text-xs text-text-2">{t('tail.syncOptOut.safari.caveat')}</p>
      </>
    )
  }
  return <p className="text-sm">{t('tail.syncOptOut.other')}</p>
}

export function LayeredExplainerScreen({
  onContinue,
}: {
  readonly onContinue: () => void
}) {
  const { t } = useTranslation('onboarding')
  return (
    <OnboardingCard>
      <OnboardingTitle>{t('tail.explainer.title')}</OnboardingTitle>
      <OnboardingProse>
        <p>{t('tail.explainer.body')}</p>
      </OnboardingProse>
      <div className="mt-6 grid gap-4 sm:grid-cols-3">
        <LayerBlock
          title={t('tail.explainer.layer.web.title')}
          chip={t('tail.explainer.layer.web.chip')}
          body={t('tail.explainer.layer.web.body')}
        />
        <LayerBlock
          title={t('tail.explainer.layer.extension.title')}
          chip={t('tail.explainer.layer.extension.chip')}
          body={t('tail.explainer.layer.extension.body')}
          actionLabel={t('tail.explainer.layer.extension.action')}
          onAction={() => alert(t('tail.explainer.layer.extension.actionAlert'))}
        />
        <LayerBlock
          title={t('tail.explainer.layer.pwa.title')}
          chip={t('tail.explainer.layer.pwa.chip')}
          body={t('tail.explainer.layer.pwa.body')}
          actionLabel={t('tail.explainer.layer.pwa.action')}
          onAction={() => alert(t('tail.explainer.layer.pwa.actionAlert'))}
        />
      </div>
      <OnboardingActions>
        <PrimaryButton onClick={onContinue}>{t('tail.explainer.continue')}</PrimaryButton>
      </OnboardingActions>
    </OnboardingCard>
  )
}

function LayerBlock({
  title,
  chip,
  body,
  actionLabel,
  onAction,
}: {
  readonly title: string
  readonly chip: string
  readonly body: string
  readonly actionLabel?: string
  readonly onAction?: () => void
}) {
  return (
    <div className="flex flex-col rounded-lg border border-line p-4 text-sm">
      <p className="font-medium">{title}</p>
      <p className="mt-1 text-xs text-text-2">{chip}</p>
      <p className="mt-3 flex-1 text-sm text-text-2">{body}</p>
      {actionLabel !== undefined && onAction !== undefined && (
        <SecondaryButton onClick={onAction}>{actionLabel}</SecondaryButton>
      )}
    </div>
  )
}

const FREE_TIER_GB = 5 // Placeholder per UX §4b.8 ({free_tier_size}). The real
// value comes from App Console / Tenshu Store config; this hard-coded
// constant ships with the demo until that wiring lands.

export function StorageTierScreen({
  onContinue,
}: {
  readonly onContinue: () => void
}) {
  const { t } = useTranslation('onboarding')
  return (
    <OnboardingCard>
      <OnboardingTitle>{t('tail.storage.title')}</OnboardingTitle>
      <OnboardingProse>
        <p>{t('tail.storage.body1', { sizeGb: FREE_TIER_GB })}</p>
        <p>{t('tail.storage.body2')}</p>
      </OnboardingProse>
      <OnboardingActions>
        <PrimaryButton onClick={onContinue}>{t('tail.storage.continue')}</PrimaryButton>
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
