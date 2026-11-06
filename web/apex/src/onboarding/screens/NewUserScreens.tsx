// §4b — new-user flow screens. The screens chain via the orchestrator;
// this file collocates them so the per-screen state machine stays
// readable and the copy can be cross-referenced against UX §4b.
//
// State flow (single mount, no URL changes), post web-roadmap 6.6:
//   §4b.1 Intro          → IntroScreen
//   §4b.2 Generate       → GenerateRealmScreen (1.2s spinner; auto-advance)
//   §4b.x Commit         → CommittingRealmScreen (Realm.create)
// On commit success the orchestrator transitions straight into the shared
// tail (passkey → browser sync → explainer → storage tier). The phrase
// show + confirm screens moved to Settings → Set up account recovery
// (`control/recovery/RecoveryCaptureScreens.tsx`, §7.4) — per the
// low-friction redesign the phrase is generated here but no longer shown.
//
// Per UX §7.1 the mnemonic only persists once `Realm.create` resolves.
// Closing the tab before commit leaves no trace.
import { useEffect, useRef, useState } from 'react'
import { Trans, useTranslation } from 'react-i18next'
import { Bip39 } from '@tenshu/sdk/core'
import { useOnboardingSdk } from '../../lib/useOnboardingSdk'
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

export function IntroScreen({
  onContinue,
}: {
  readonly onContinue: () => void
}) {
  const { t } = useTranslation('onboarding')
  return (
    <OnboardingCard>
      <OnboardingTitle>{t('newUser.intro.title')}</OnboardingTitle>
      <OnboardingProse>
        <p>
          <Trans ns="onboarding" i18nKey="newUser.intro.body1" components={{ em: <em /> }} />
        </p>
        <p>{t('newUser.intro.body2')}</p>
      </OnboardingProse>
      <OnboardingActions>
        <PrimaryButton onClick={onContinue}>{t('newUser.intro.continue')}</PrimaryButton>
        <a
          href="https://tenshu.app/about"
          target="_blank"
          rel="noopener noreferrer"
          className="text-sm font-medium text-text-2 underline-offset-4 hover:text-text hover:underline"
        >
          {t('newUser.intro.tellMeMore')}
        </a>
      </OnboardingActions>
    </OnboardingCard>
  )
}

export function GenerateRealmScreen({
  onReady,
}: {
  readonly onReady: (mnemonic: string) => void
}) {
  const { t } = useTranslation('onboarding')
  // §4b.2: 1–2s feedback while we generate the mnemonic. The mnemonic
  // generator itself is synchronous; the spinner exists to make the
  // transition observable to the user.
  useEffect(() => {
    const mnemonic = Bip39.generateMnemonic()
    const id = setTimeout(() => onReady(mnemonic), 1200)
    return () => clearTimeout(id)
  }, [onReady])
  return (
    <OnboardingCard>
      <OnboardingTitle>{t('newUser.generate.title')}</OnboardingTitle>
      <OnboardingProse>
        <p>{t('newUser.generate.body')}</p>
      </OnboardingProse>
      <div className="mt-8 flex items-center gap-3">
        <Spinner />
        <p className="text-sm text-text-2">{t('newUser.generate.spinnerLabel')}</p>
      </div>
    </OnboardingCard>
  )
}

export function CommittingRealmScreen({
  mnemonic,
  deviceName,
  onCommitted,
  onFailed,
}: {
  readonly mnemonic: string
  readonly deviceName: string
  readonly onCommitted: () => void
  readonly onFailed: (message: string) => void
}) {
  const { t } = useTranslation('onboarding')
  const sdk = useOnboardingSdk()
  // Run-once guard. `Realm.create`'s relay genesis (`POST /sync/realm`) is not
  // safe to invoke concurrently: two in-flight calls both pass the proxy's
  // `realmRelayRegistered` latch before either sets it, so the first POST wins
  // (201) and the second races to a 409 ("Realm already exists for this
  // ownerId") that surfaces as a spurious "Couldn't set up your realm". React
  // StrictMode double-invokes effects in dev, which triggers exactly this; the
  // ref persists across the StrictMode remount so the second pass is a no-op.
  // We deliberately do NOT use a `cancelled` flag keyed to the effect run — the
  // StrictMode cleanup would set it on the only run that started the promise,
  // stranding the resolved result and hanging on the spinner forever.
  const startedRef = useRef(false)
  useEffect(() => {
    if (startedRef.current) return
    startedRef.current = true
    void sdk.Realm.create({ mnemonic, deviceName }).then(result => {
      if (result.ok) onCommitted()
      else onFailed(result.error)
    })
  }, [sdk, mnemonic, deviceName, onCommitted, onFailed])
  return (
    <OnboardingCard>
      <OnboardingTitle>{t('newUser.commit.title')}</OnboardingTitle>
      <OnboardingProse>
        <p>{t('newUser.commit.body')}</p>
      </OnboardingProse>
      <div className="mt-8 flex items-center gap-3">
        <Spinner />
        <p className="text-sm text-text-2">{t('newUser.commit.spinnerLabel')}</p>
      </div>
    </OnboardingCard>
  )
}

export function RealmCreateFailedScreen({
  message,
  onRetry,
  onCancel,
}: {
  readonly message: string
  readonly onRetry: () => void
  readonly onCancel: () => void
}) {
  const { t } = useTranslation('onboarding')
  return (
    <OnboardingCard>
      <OnboardingTitle>{t('newUser.commitFailed.title')}</OnboardingTitle>
      <OnboardingProse>
        {/* `message` is the raw SDK error string — already English in
            this codebase; once errors graduate to typed codes (ADR
            0016 rollout) this branch becomes a key lookup instead. */}
        <p>{message}</p>
        <p>{t('newUser.commitFailed.retainPhrase')}</p>
      </OnboardingProse>
      <OnboardingActions>
        <PrimaryButton onClick={onRetry}>{t('newUser.commitFailed.retry')}</PrimaryButton>
        <LinkButton onClick={onCancel}>{t('newUser.commitFailed.cancel')}</LinkButton>
      </OnboardingActions>
      <Hint>{t('newUser.commitFailed.networkHint')}</Hint>
    </OnboardingCard>
  )
}

export function DeviceNameScreen({
  defaultName,
  onContinue,
  onBack,
}: {
  readonly defaultName: string
  readonly onContinue: (name: string) => void
  readonly onBack?: () => void
}) {
  const { t } = useTranslation('onboarding')
  // The UX spec defaults the device name to "This Browser" when the user
  // declines to set one (§3.1's `{device_name}` substitution). Asking
  // here is a small kindness so the device list reads better.
  const [name, setName] = useState(defaultName)
  const trimmed = name.trim()
  return (
    <OnboardingCard>
      <OnboardingTitle>{t('newUser.deviceName.title')}</OnboardingTitle>
      <OnboardingProse>
        <p>{t('newUser.deviceName.body')}</p>
      </OnboardingProse>
      <label className="mt-6 block">
        <span className="block text-xs font-medium text-text-2">
          {t('newUser.deviceName.fieldLabel')}
        </span>
        <input
          type="text"
          autoFocus
          value={name}
          maxLength={64}
          onChange={e => setName(e.target.value)}
          className="mt-1 w-full rounded-md border border-line bg-surface-2 px-3 py-2 text-sm shadow-sm"
        />
      </label>
      <OnboardingActions>
        <PrimaryButton onClick={() => onContinue(trimmed === '' ? defaultName : trimmed)}>
          {t('newUser.deviceName.continue')}
        </PrimaryButton>
        {onBack !== undefined && (
          <SecondaryButton onClick={onBack}>{t('newUser.deviceName.back')}</SecondaryButton>
        )}
      </OnboardingActions>
    </OnboardingCard>
  )
}

function Spinner() {
  return (
    <span
      aria-hidden
      className="inline-block h-5 w-5 animate-spin rounded-full border-2 border-line border-t-text"
    />
  )
}
