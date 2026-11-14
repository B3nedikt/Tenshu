// §6 Recovery flow — entered when the user has lost access to all
// realm devices and passkeys. The 24-word recovery phrase rebuilds the
// master identity; per UX §6.2, the Argon2id derive takes 5–10s.
//
// On success the user enters the shared tail (passkey → browser sync →
// explainer), modeled in UX §6.3 as "treated as a returning user with
// a brand-new device". We re-use the tail components from
// `./screens/TailScreens` rather than inlining a different copy.
import { useEffect, useMemo, useState } from 'react'
import { Link, useNavigate } from 'react-router'
import { OVERVIEW_PATH } from '../lib/overviewPath'
import { Trans, useTranslation } from 'react-i18next'
import type { TFunction } from 'i18next'
import type { RecoveryError } from '@tenshu/sdk/onboarding'
import { useOnboardingSdk } from '../lib/useOnboardingSdk'
import { BIP39_WORDSET } from '../lib/bip39'
// useOnboardingSdk is consumed inside ProcessingScreen; the outer
// RecoveryFlow only orchestrates step transitions.
import {
  Hint,
  LinkButton,
  OnboardingActions,
  OnboardingCard,
  OnboardingProse,
  OnboardingShell,
  OnboardingTitle,
  PrimaryButton,
} from './OnboardingShell'
import {
  BrowserSyncWarningScreen,
  CompletingSessionScreen,
  LayeredExplainerScreen,
  PasskeySetupScreen,
  SyncOptOutScreen,
} from './screens/TailScreens'
import { DeviceNameScreen } from './screens/NewUserScreens'

type Step =
  | { readonly kind: 'entry' }
  | {
      readonly kind: 'deviceName'
      readonly mnemonic: string
    }
  | {
      readonly kind: 'processing'
      readonly mnemonic: string
      readonly deviceName: string
    }
  | {
      readonly kind: 'failed'
      readonly mnemonic: string
      readonly deviceName: string
      readonly error: RecoveryError
    }
  | { readonly kind: 'tail.passkey'; readonly deviceName: string }
  | { readonly kind: 'tail.syncWarning'; readonly deviceName: string }
  | { readonly kind: 'tail.syncOptOut'; readonly deviceName: string }
  | { readonly kind: 'tail.explainer'; readonly deviceName: string }
  | { readonly kind: 'tail.completing' }

export function RecoveryFlow() {
  const { t } = useTranslation('onboarding')
  const navigate = useNavigate()
  const [step, setStep] = useState<Step>({ kind: 'entry' })

  return (
    <OnboardingShell>
      {step.kind === 'entry' && (
        <RecoveryEntryScreen
          onSubmit={mnemonic => setStep({ kind: 'deviceName', mnemonic })}
        />
      )}
      {step.kind === 'deviceName' && (
        <DeviceNameScreen
          defaultName={t('deviceName.default')}
          onContinue={name =>
            setStep({ kind: 'processing', mnemonic: step.mnemonic, deviceName: name })
          }
          onBack={() => setStep({ kind: 'entry' })}
        />
      )}
      {step.kind === 'processing' && (
        <ProcessingScreen
          mnemonic={step.mnemonic}
          deviceName={step.deviceName}
          onSucceeded={() => setStep({ kind: 'tail.passkey', deviceName: step.deviceName })}
          onFailed={error =>
            setStep({
              kind: 'failed',
              mnemonic: step.mnemonic,
              deviceName: step.deviceName,
              error,
            })
          }
        />
      )}
      {step.kind === 'failed' && (
        <FailedScreen
          error={step.error}
          onRetry={() =>
            setStep({
              kind: 'processing',
              mnemonic: step.mnemonic,
              deviceName: step.deviceName,
            })
          }
          onBackToEntry={() => setStep({ kind: 'entry' })}
        />
      )}

      {/* Tail */}
      {step.kind === 'tail.passkey' && (
        <PasskeySetupScreen
          deviceName={step.deviceName}
          onContinue={() => setStep({ kind: 'tail.syncWarning', deviceName: step.deviceName })}
          onSkip={() => setStep({ kind: 'tail.syncWarning', deviceName: step.deviceName })}
        />
      )}
      {step.kind === 'tail.syncWarning' && (
        <BrowserSyncWarningScreen
          onSeparate={() => setStep({ kind: 'tail.syncOptOut', deviceName: step.deviceName })}
          onSkip={() => setStep({ kind: 'tail.explainer', deviceName: step.deviceName })}
        />
      )}
      {step.kind === 'tail.syncOptOut' && (
        <SyncOptOutScreen
          onContinue={() => setStep({ kind: 'tail.explainer', deviceName: step.deviceName })}
        />
      )}
      {step.kind === 'tail.explainer' && (
        <LayeredExplainerScreen onContinue={() => setStep({ kind: 'tail.completing' })} />
      )}
      {step.kind === 'tail.completing' && (
        <CompletingSessionScreen onCompleted={() => navigate(OVERVIEW_PATH, { replace: true })} />
      )}

      {/* Footer fallback for first screen — unused on tail steps */}
      {step.kind === 'entry' && (
        <p className="mt-6 text-center text-xs text-text-2">
          <Trans
            ns="onboarding"
            i18nKey="recovery.entry.footer"
            components={{
              back: <Link to="/" className="underline-offset-4 hover:underline" />,
            }}
          />
        </p>
      )}
    </OnboardingShell>
  )
}

function RecoveryEntryScreen({
  onSubmit,
}: {
  readonly onSubmit: (mnemonic: string) => void
}) {
  const { t } = useTranslation('onboarding')
  const [text, setText] = useState('')
  const words = useMemo(() => splitWords(text), [text])
  const validWordCount = useMemo(
    () => words.filter(w => BIP39_WORDSET.has(w)).length,
    [words],
  )
  const invalidWords = useMemo(
    () => words.filter(w => !BIP39_WORDSET.has(w)),
    [words],
  )
  const wordCount = words.length
  const ready = wordCount === 24 && invalidWords.length === 0
  return (
    <OnboardingCard>
      <OnboardingTitle>{t('recovery.entry.title')}</OnboardingTitle>
      <OnboardingProse>
        <p>{t('recovery.entry.body1')}</p>
        <p>{t('recovery.entry.body2')}</p>
      </OnboardingProse>
      <label className="mt-6 block">
        <span className="block text-xs font-medium text-text-2">{t('recovery.entry.fieldLabel')}</span>
        <textarea
          rows={6}
          autoFocus
          value={text}
          autoComplete="off"
          autoCorrect="off"
          autoCapitalize="none"
          spellCheck={false}
          onChange={e => setText(e.target.value)}
          placeholder={t('recovery.entry.placeholder')}
          className="mt-1 w-full rounded-md border border-line bg-surface px-3 py-2 font-mono text-sm shadow-sm"
        />
      </label>
      <div className="mt-3 flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-text-2">
        <span>
          {t('recovery.entry.wordsLabel')} <span className="font-mono">{wordCount}/24</span>
        </span>
        <span>
          {t('recovery.entry.recognisedLabel')} <span className="font-mono">{validWordCount}</span>
        </span>
        {invalidWords.length > 0 && (
          <span className="text-danger">
            {t('recovery.entry.unknownLabel')} <span className="font-mono">{invalidWords.slice(0, 3).join(', ')}</span>
            {invalidWords.length > 3 ? '…' : ''}
          </span>
        )}
      </div>
      <OnboardingActions>
        <PrimaryButton onClick={() => onSubmit(words.join(' '))} disabled={!ready}>
          {t('recovery.entry.submit')}
        </PrimaryButton>
        <Link
          to="/"
          className="text-sm font-medium text-text-2 underline-offset-4 hover:text-text hover:underline"
        >
          {t('recovery.entry.cancel')}
        </Link>
      </OnboardingActions>
      <Hint>{t('recovery.entry.hint')}</Hint>
    </OnboardingCard>
  )
}

function ProcessingScreen({
  mnemonic,
  deviceName,
  onSucceeded,
  onFailed,
}: {
  readonly mnemonic: string
  readonly deviceName: string
  readonly onSucceeded: () => void
  readonly onFailed: (error: RecoveryError) => void
}) {
  const { t } = useTranslation('onboarding')
  const sdk = useOnboardingSdk()
  useEffect(() => {
    let cancelled = false
    void sdk.Recovery.claim({ mnemonic, deviceName }).then(result => {
      if (cancelled) return
      if (result.ok) onSucceeded()
      else onFailed(result.error)
    })
    return () => {
      cancelled = true
    }
  }, [sdk, mnemonic, deviceName, onSucceeded, onFailed])
  return (
    <OnboardingCard>
      <OnboardingTitle>{t('recovery.processing.title')}</OnboardingTitle>
      <OnboardingProse>
        <p>{t('recovery.processing.body')}</p>
      </OnboardingProse>
      <div className="mt-8 flex items-center gap-3">
        <span
          aria-hidden
          className="inline-block h-5 w-5 animate-spin rounded-full border-2 border-line border-t-text"
        />
        <p className="text-sm text-text-2">{t('recovery.processing.spinnerLabel')}</p>
      </div>
    </OnboardingCard>
  )
}

function FailedScreen({
  error,
  onRetry,
  onBackToEntry,
}: {
  readonly error: RecoveryError
  readonly onRetry: () => void
  readonly onBackToEntry: () => void
}) {
  const { t } = useTranslation('onboarding')
  const { title, body } = describeRecoveryError(t, error)
  return (
    <OnboardingCard>
      <OnboardingTitle>{title}</OnboardingTitle>
      <OnboardingProse>
        <p>{body}</p>
      </OnboardingProse>
      <OnboardingActions>
        <PrimaryButton onClick={onRetry}>{t('recovery.failed.tryAgain')}</PrimaryButton>
        <LinkButton onClick={onBackToEntry}>{t('recovery.failed.editPhrase')}</LinkButton>
      </OnboardingActions>
    </OnboardingCard>
  )
}

function describeRecoveryError(t: TFunction, error: RecoveryError): { title: string; body: string } {
  if (error.kind === 'invalidMnemonic') {
    if (error.reason.kind === 'invalidLength') {
      return {
        title: t('recovery.failed.invalidLength.title'),
        body: t('recovery.failed.invalidLength.body', { wordCount: error.reason.wordCount }),
      }
    }
    if (error.reason.kind === 'unknownWord') {
      return {
        title: t('recovery.failed.unknownWord.title'),
        body: t('recovery.failed.unknownWord.body', { word: error.reason.word }),
      }
    }
    return {
      title: t('recovery.failed.noMatch.title'),
      body: t('recovery.failed.noMatch.body'),
    }
  }
  if (error.kind === 'noBackupOnRelay') {
    return {
      title: t('recovery.failed.noBackupOnRelay.title'),
      body: t('recovery.failed.noBackupOnRelay.body'),
    }
  }
  if (error.kind === 'kdfParametersOutOfDate') {
    return {
      title: t('recovery.failed.kdfParametersOutOfDate.title'),
      body: t('recovery.failed.kdfParametersOutOfDate.body'),
    }
  }
  if (error.kind === 'deviceAlreadySetUp') {
    return {
      title: t('recovery.failed.deviceAlreadySetUp.title'),
      body: t('recovery.failed.deviceAlreadySetUp.body'),
    }
  }
  if (error.kind === 'network') {
    return {
      title: t('recovery.failed.network.title'),
      body: t('recovery.failed.network.body'),
    }
  }
  return {
    title: t('recovery.failed.other.title'),
    body: error.message,
  }
}

function splitWords(text: string): readonly string[] {
  return text
    .toLowerCase()
    .split(/\s+/)
    .map(w => w.trim())
    .filter(w => w !== '')
}
