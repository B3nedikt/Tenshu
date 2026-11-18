// §3 Locked unlock flow. Shown when Session.detect returns
// `{ kind: 'locked' }` — i.e. the apex has IDB state but no valid
// session token. The user is prompted for a passkey assertion; on
// success, Session.unlock derives a fresh token and persists it before
// routing to the control plane.
//
// Phase 5.3: "Lost your passkey?" no longer routes to /recovery (full
// Recovery.claim with relay round-trip — that's the firstTime-flow path
// for "lost all devices"). Instead it switches to an inline phrase-entry
// step that calls Session.unlockWithPhrase to derive the master keypair
// locally without contacting the relay. Users who genuinely lost all
// devices can still reach /recovery via direct URL or the firstTime flow.
import { useEffect, useMemo, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { postUnlockPath } from '../lib/overviewPath'
import { Trans, useTranslation } from 'react-i18next'
import type { TFunction } from 'i18next'
import type {
  PhraseUnlockError,
  SessionState,
  UnlockError,
} from '@tenshu/sdk/onboarding'
import { useOnboardingSdk } from '../lib/useOnboardingSdk'
import { DisconnectBrowserPanel } from '../components/DisconnectBrowserPanel'
import { BIP39_WORDSET } from '../lib/bip39'
import {
  getPasskeyAssertion,
  isWebAuthnSupported,
  type WebAuthnFailure,
} from '../lib/webauthn'
import { PasskeySetupScreen } from './screens/TailScreens'
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

type Step =
  | 'welcome'
  | 'unlocking'
  | { failure: LockedFailure }
  | 'confirm-disconnect'
  | 'phrase-entry'
  | { phraseProcessing: { mnemonic: string } }
  | { phraseFailed: { mnemonic: string; error: PhraseUnlockError } }
  // After a phrase unlock: the passkey path just failed or never existed, so offer a new one here
  // rather than leaving the phrase as the only way back in every 24 hours.
  | 'passkey-setup'

type LockedFailure =
  | 'cancelled'
  | 'wrongCredential'
  | 'hardware'
  | 'unsupported'
  | 'unknown'
  // Phase 5 PRF-unwrap path: WebAuthn verified but the wrapped master
  // keypair couldn't be unwrapped (auth-tag mismatch / corrupted blob /
  // missing blob). Routes the user to the recovery-phrase fallback
  // since the passkey alone can't restore asset A1.
  | 'masterKeyUnwrap'
  // This browser has no passkey stored at all (skipped at setup, or its save never landed). No
  // passkey the user owns can unlock it, so the ceremony is not offered — only the phrase.
  | 'noPasskey'

export function LockedFlow() {
  const { t } = useTranslation('onboarding')
  const sdk = useOnboardingSdk()
  const navigate = useNavigate()
  // Back to the page the session guard sent us away from (validated: lib/overviewPath.ts).
  const [searchParams] = useSearchParams()
  const destination = postUnlockPath(searchParams.get('next'))
  const [deviceName, setDeviceName] = useState<string>(() => t('deviceName.default'))
  const [step, setStep] = useState<Step>('welcome')
  // false only on a definite "none stored"; a failed lookup stays unknown and keeps the passkey path.
  const [hasPasskey, setHasPasskey] = useState<boolean | 'unknown'>('unknown')

  useEffect(() => {
    let cancelled = false
    void sdk.Session.detect().then((s: SessionState) => {
      if (cancelled) return
      if (s.kind === 'active') {
        navigate(destination, { replace: true })
        return
      }
      if (s.kind === 'firstTime') {
        navigate('/onboarding', { replace: true })
        return
      }
      setDeviceName(s.deviceName)
      void sdk.Session.passkeyCredentialId()
        .then(id => {
          if (!cancelled) setHasPasskey(id !== null)
        })
        .catch(() => undefined)
    })
    return () => {
      cancelled = true
    }
  }, [sdk, navigate, destination])

  async function onUnlock() {
    setStep('unlocking')
    // Narrow the browser's picker to the passkey this browser registered. A failed lookup
    // must not block unlock — fall back to the discoverable picker. A definite "none stored" must
    // not open the picker either: it lists every passkey the user owns, and each one fails.
    const lookup = await sdk.Session.passkeyCredentialId().then(
      id => ({ ok: true as const, id }),
      () => ({ ok: false as const }),
    )
    if (lookup.ok && lookup.id === null) {
      setHasPasskey(false)
      setStep({ failure: 'noPasskey' })
      return
    }
    const credentialId = lookup.ok ? lookup.id : null
    const assertionResult = await getPasskeyAssertion(credentialId)
    if (!assertionResult.ok) {
      setStep({ failure: mapFailure(assertionResult.error) })
      return
    }
    const sessionResult = await sdk.Session.unlock(assertionResult.value)
    if (!sessionResult.ok) {
      setStep({ failure: mapUnlockError(sessionResult.error) })
      return
    }
    await sdk.Session.persist(sessionResult.value)
    navigate(destination, { replace: true })
  }

  return (
    <OnboardingShell>
      {step === 'welcome' && (
        <WelcomeCard
          deviceName={deviceName}
          hasPasskey={hasPasskey !== false}
          onUnlock={() => void onUnlock()}
          onUsePhrase={() => setStep('phrase-entry')}
          onDisconnect={() => setStep('confirm-disconnect')}
        />
      )}
      {/* Escape hatch for a browser that can't unlock, or a user done with it. Removes this
          browser from its realm and wipes it (`DisconnectBrowserPanel`, `lib/disconnectBrowser.ts`):
          the device's own A4 key signs the removal, so no master key is needed here. */}
      {step === 'confirm-disconnect' && (
        <OnboardingCard>
          <DisconnectBrowserPanel
            deviceName={deviceName}
            session={sdk.Session}
            onCancel={() => setStep('welcome')}
            renderTitle={text => <OnboardingTitle>{text}</OnboardingTitle>}
          />
        </OnboardingCard>
      )}
      {step === 'unlocking' && (
        <OnboardingCard>
          <OnboardingTitle>{t('locked.unlocking.title')}</OnboardingTitle>
          <OnboardingProse>
            <p>{t('locked.unlocking.body')}</p>
          </OnboardingProse>
        </OnboardingCard>
      )}
      {typeof step === 'object' && 'failure' in step && (
        <UnlockFailedCard
          failure={step.failure}
          onRetry={() => void onUnlock()}
          onUsePhrase={() => setStep('phrase-entry')}
          onWelcome={() => setStep('welcome')}
          onDisconnect={() => setStep('confirm-disconnect')}
        />
      )}
      {step === 'phrase-entry' && (
        <PhraseEntryCard
          onSubmit={mnemonic => setStep({ phraseProcessing: { mnemonic } })}
          onBack={() => setStep('welcome')}
        />
      )}
      {typeof step === 'object' && 'phraseProcessing' in step && (
        <PhraseProcessingCard
          mnemonic={step.phraseProcessing.mnemonic}
          onSucceeded={() =>
            isWebAuthnSupported() ? setStep('passkey-setup') : navigate(destination, { replace: true })
          }
          onFailed={error =>
            setStep({
              phraseFailed: { mnemonic: step.phraseProcessing.mnemonic, error },
            })
          }
        />
      )}
      {typeof step === 'object' && 'phraseFailed' in step && (
        <PhraseFailedCard
          error={step.phraseFailed.error}
          onEdit={() => setStep('phrase-entry')}
          onRetry={() =>
            setStep({
              phraseProcessing: { mnemonic: step.phraseFailed.mnemonic },
            })
          }
          onWelcome={() => setStep('welcome')}
        />
      )}
      {step === 'passkey-setup' && (
        <PasskeySetupScreen
          deviceName={deviceName}
          onContinue={() => navigate(destination, { replace: true })}
          onSkip={() => navigate(destination, { replace: true })}
        />
      )}
    </OnboardingShell>
  )
}

function mapFailure(error: WebAuthnFailure): LockedFailure {
  return error.kind
}

function mapUnlockError(error: UnlockError): LockedFailure {
  switch (error.kind) {
    case 'noPasskey':
      return 'noPasskey'
    case 'wrongCredential':
      return 'wrongCredential'
    case 'masterKeyUnwrap':
      return 'masterKeyUnwrap'
    case 'unsupportedAlgorithm':
      return 'unsupported'
    case 'other':
      return 'unknown'
  }
}

function WelcomeCard({
  deviceName,
  hasPasskey,
  onUnlock,
  onUsePhrase,
  onDisconnect,
}: {
  readonly deviceName: string
  readonly hasPasskey: boolean
  readonly onUnlock: () => void
  readonly onUsePhrase: () => void
  readonly onDisconnect: () => void
}) {
  const { t } = useTranslation('onboarding')
  return (
    <OnboardingCard>
      <OnboardingTitle>{t('locked.welcome.title', { deviceName })}</OnboardingTitle>
      <OnboardingProse>
        <p>{hasPasskey ? t('locked.welcome.body') : t('locked.welcome.noPasskeyStoredBody')}</p>
      </OnboardingProse>
      <OnboardingActions>
        {hasPasskey ? (
          <>
            <PrimaryButton onClick={onUnlock}>{t('locked.welcome.unlock')}</PrimaryButton>
            <LinkButton onClick={onUsePhrase}>{t('locked.welcome.lostPasskey')}</LinkButton>
          </>
        ) : (
          <PrimaryButton onClick={onUsePhrase}>{t('locked.failed.usePhrase')}</PrimaryButton>
        )}
        <LinkButton onClick={onDisconnect}>{t('locked.welcome.disconnect')}</LinkButton>
      </OnboardingActions>
      <Hint>
        <a
          href="https://tenshu.app/help/passkeys"
          target="_blank"
          rel="noopener noreferrer"
          className="underline-offset-4 hover:underline"
        >
          {t('locked.welcome.whatIsPasskey')}
        </a>
      </Hint>
      {!isWebAuthnSupported() && (
        <p className="mt-4 rounded-md border border-warning/30 bg-warning/10 p-3 text-sm text-warning">
          {t('locked.welcome.noPasskeyHint')}
        </p>
      )}
    </OnboardingCard>
  )
}

function UnlockFailedCard({
  failure,
  onRetry,
  onUsePhrase,
  onWelcome,
  onDisconnect,
}: {
  readonly failure: LockedFailure
  readonly onRetry: () => void
  readonly onUsePhrase: () => void
  readonly onWelcome: () => void
  readonly onDisconnect: () => void
}) {
  const { t } = useTranslation('onboarding')
  const message = t(`locked.failed.message.${failure}`)
  return (
    <OnboardingCard>
      <OnboardingTitle>{t('locked.failed.title')}</OnboardingTitle>
      <OnboardingProse>
        <p>{message}</p>
      </OnboardingProse>
      <OnboardingActions>
        {failure === 'noPasskey' ? (
          <PrimaryButton onClick={onUsePhrase}>{t('locked.failed.usePhrase')}</PrimaryButton>
        ) : (
          <>
            <PrimaryButton onClick={onRetry}>{t('locked.failed.retry')}</PrimaryButton>
            <LinkButton onClick={onUsePhrase}>{t('locked.failed.usePhrase')}</LinkButton>
          </>
        )}
        <LinkButton onClick={onWelcome}>{t('locked.failed.back')}</LinkButton>
        <LinkButton onClick={onDisconnect}>{t('locked.failed.disconnect')}</LinkButton>
      </OnboardingActions>
    </OnboardingCard>
  )
}

function PhraseEntryCard({
  onSubmit,
  onBack,
}: {
  readonly onSubmit: (mnemonic: string) => void
  readonly onBack: () => void
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
      <OnboardingTitle>{t('locked.phraseEntry.title')}</OnboardingTitle>
      <OnboardingProse>
        <p>{t('locked.phraseEntry.body1')}</p>
        <p>{t('locked.phraseEntry.body2')}</p>
      </OnboardingProse>
      <label className="mt-6 block">
        <span className="block text-xs font-medium text-text-2">{t('locked.phraseEntry.fieldLabel')}</span>
        <textarea
          rows={6}
          autoFocus
          value={text}
          autoComplete="off"
          autoCorrect="off"
          autoCapitalize="none"
          spellCheck={false}
          onChange={e => setText(e.target.value)}
          placeholder={t('locked.phraseEntry.placeholder')}
          className="mt-1 w-full rounded-md border border-line bg-surface px-3 py-2 font-mono text-sm shadow-sm"
        />
      </label>
      <div className="mt-3 flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-text-2">
        <span>
          {t('locked.phraseEntry.wordsLabel')} <span className="font-mono">{wordCount}/24</span>
        </span>
        <span>
          {t('locked.phraseEntry.recognisedLabel')} <span className="font-mono">{validWordCount}</span>
        </span>
        {invalidWords.length > 0 && (
          <span className="text-danger">
            {t('locked.phraseEntry.unknownLabel')} <span className="font-mono">{invalidWords.slice(0, 3).join(', ')}</span>
            {invalidWords.length > 3 ? '…' : ''}
          </span>
        )}
      </div>
      <OnboardingActions>
        <PrimaryButton onClick={() => onSubmit(words.join(' '))} disabled={!ready}>
          {t('locked.phraseEntry.submit')}
        </PrimaryButton>
        <LinkButton onClick={onBack}>{t('locked.phraseEntry.back')}</LinkButton>
      </OnboardingActions>
      <Hint>
        <Trans
          ns="onboarding"
          i18nKey="locked.phraseEntry.hintLostAll"
          components={{
            em: <em />,
            restore: <Link to="/recovery" className="underline-offset-4 hover:underline" />,
          }}
        />
      </Hint>
    </OnboardingCard>
  )
}

function PhraseProcessingCard({
  mnemonic,
  onSucceeded,
  onFailed,
}: {
  readonly mnemonic: string
  readonly onSucceeded: () => void
  readonly onFailed: (error: PhraseUnlockError) => void
}) {
  const { t } = useTranslation('onboarding')
  const sdk = useOnboardingSdk()
  useEffect(() => {
    let cancelled = false
    void sdk.Session.unlockWithPhrase(mnemonic).then(async result => {
      if (cancelled) return
      if (!result.ok) {
        onFailed(result.error)
        return
      }
      await sdk.Session.persist(result.value)
      onSucceeded()
    })
    return () => {
      cancelled = true
    }
  }, [sdk, mnemonic, onSucceeded, onFailed])
  return (
    <OnboardingCard>
      <OnboardingTitle>{t('locked.phraseProcessing.title')}</OnboardingTitle>
      <OnboardingProse>
        <p>{t('locked.phraseProcessing.body')}</p>
      </OnboardingProse>
      <div className="mt-8 flex items-center gap-3">
        <span
          aria-hidden
          className="inline-block h-5 w-5 animate-spin rounded-full border-2 border-line border-t-text"
        />
        <p className="text-sm text-text-2">{t('locked.phraseProcessing.spinnerLabel')}</p>
      </div>
    </OnboardingCard>
  )
}

function PhraseFailedCard({
  error,
  onEdit,
  onRetry,
  onWelcome,
}: {
  readonly error: PhraseUnlockError
  readonly onEdit: () => void
  readonly onRetry: () => void
  readonly onWelcome: () => void
}) {
  const { t } = useTranslation('onboarding')
  const { title, body, primary } = describePhraseError(t, error)
  return (
    <OnboardingCard>
      <OnboardingTitle>{title}</OnboardingTitle>
      <OnboardingProse>
        <p>{body}</p>
      </OnboardingProse>
      <OnboardingActions>
        {primary === 'retry' ? (
          <PrimaryButton onClick={onRetry}>{t('locked.phraseFailed.tryAgain')}</PrimaryButton>
        ) : (
          <PrimaryButton onClick={onEdit}>{t('locked.phraseFailed.editPhrase')}</PrimaryButton>
        )}
        <LinkButton onClick={onWelcome}>{t('locked.phraseFailed.backToPasskey')}</LinkButton>
      </OnboardingActions>
    </OnboardingCard>
  )
}

function describePhraseError(
  t: TFunction,
  error: PhraseUnlockError,
): {
  readonly title: string
  readonly body: string
  readonly primary: 'edit' | 'retry'
} {
  if (error.kind === 'invalidMnemonic') {
    if (error.reason.kind === 'invalidLength') {
      return {
        title: t('locked.phraseFailed.invalidLength.title'),
        body: t('locked.phraseFailed.invalidLength.body', { wordCount: error.reason.wordCount }),
        primary: 'edit',
      }
    }
    if (error.reason.kind === 'unknownWord') {
      return {
        title: t('locked.phraseFailed.unknownWord.title'),
        body: t('locked.phraseFailed.unknownWord.body', { word: error.reason.word }),
        primary: 'edit',
      }
    }
    return {
      title: t('locked.phraseFailed.noMatch.title'),
      body: t('locked.phraseFailed.noMatch.body'),
      primary: 'edit',
    }
  }
  if (error.kind === 'wrongRealm') {
    return {
      title: t('locked.phraseFailed.wrongRealm.title'),
      body: t('locked.phraseFailed.wrongRealm.body'),
      primary: 'edit',
    }
  }
  return {
    title: t('locked.phraseFailed.other.title'),
    body: error.message,
    primary: 'retry',
  }
}

function splitWords(text: string): readonly string[] {
  return text
    .toLowerCase()
    .split(/\s+/)
    .map(w => w.trim())
    .filter(w => w !== '')
}
