// §7.4 "Write down your recovery phrase" — the phrase-capture screens,
// lifted out of the new-user onboarding path (web-roadmap 6.6) and made
// available on-demand from Settings → Recovery → Set up account recovery.
// Per `docs/proposals/onboarding-low-friction.md` §7.4 the screens are
// otherwise unchanged from their original §4b.3/§4b.4 form: phrase display
// gated behind a "saved it" checkbox, then a confirmation step that re-enters
// three random word positions with a force-re-display after three misses.
//
// They still consume the onboarding shell + mnemonic widgets (shared UI
// primitives), and keep their original `onboarding` i18n keys (`newUser.show`
// / `newUser.confirm`) so the copy reads identically whether captured during
// onboarding (historically) or post-data from Settings. The orchestration
// that feeds them a phrase + records completion lives in
// `AccountRecoverySetupPage`.
import { useState } from 'react'
import { Trans, useTranslation } from 'react-i18next'
import {
  OnboardingActions,
  OnboardingCard,
  OnboardingProse,
  OnboardingTitle,
  PrimaryButton,
} from '../../onboarding/OnboardingShell'
import {
  ClipboardCountdownButton,
  MnemonicGrid,
} from '../../onboarding/widgets/MnemonicGrid'
import { MnemonicConfirm } from '../../onboarding/widgets/MnemonicConfirm'
import { pickConfirmPositions } from '../../onboarding/widgets/mnemonicUtil'

export function ShowMnemonicScreen({
  mnemonic,
  onContinue,
}: {
  readonly mnemonic: readonly string[]
  readonly onContinue: () => void
}) {
  const { t } = useTranslation('onboarding')
  const [confirmed, setConfirmed] = useState(false)
  return (
    <OnboardingCard>
      <OnboardingTitle>{t('newUser.show.title')}</OnboardingTitle>
      <OnboardingProse>
        <p>{t('newUser.show.body')}</p>
      </OnboardingProse>
      <div className="mt-6">
        <MnemonicGrid words={mnemonic} />
      </div>
      <ClipboardCountdownButton text={mnemonic.join(' ')} className="mt-4" />
      <div
        role="alert"
        className="mt-6 rounded-md border border-danger/30 bg-danger/10 px-3 py-3 text-sm text-danger"
      >
        {t('newUser.show.supportWarning')}
      </div>
      <p className="mt-3 rounded-md border border-warning/30 bg-warning/10 px-3 py-3 text-xs text-warning">
        <Trans
          ns="onboarding"
          i18nKey="newUser.show.walletWarning"
          components={{ strong: <strong /> }}
        />
      </p>
      <label className="mt-6 flex items-start gap-3 text-sm text-text-2">
        <input
          type="checkbox"
          checked={confirmed}
          onChange={e => setConfirmed(e.target.checked)}
          className="mt-0.5 h-4 w-4 rounded border-line"
        />
        <span>{t('newUser.show.savedCheckbox')}</span>
      </label>
      <OnboardingActions>
        <PrimaryButton onClick={onContinue} disabled={!confirmed}>
          {t('newUser.show.continue')}
        </PrimaryButton>
      </OnboardingActions>
    </OnboardingCard>
  )
}

export function ConfirmMnemonicScreen({
  mnemonic,
  onConfirmed,
  onRedisplay,
}: {
  readonly mnemonic: readonly string[]
  readonly onConfirmed: () => void
  readonly onRedisplay: () => void
}) {
  const { t } = useTranslation('onboarding')
  // Positions are picked once per mount; remounting (e.g. via the
  // orchestrator after re-display) re-randomises them.
  const [positions] = useState(() => pickConfirmPositions(3, mnemonic.length))
  return (
    <OnboardingCard>
      <OnboardingTitle>{t('newUser.confirm.title')}</OnboardingTitle>
      <OnboardingProse>
        <p>{t('newUser.confirm.body')}</p>
      </OnboardingProse>
      <div className="mt-6">
        <MnemonicConfirm
          mnemonic={mnemonic}
          positions={positions}
          onConfirmed={onConfirmed}
          onRedisplay={onRedisplay}
        />
      </div>
    </OnboardingCard>
  )
}
