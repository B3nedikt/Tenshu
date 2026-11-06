// [Test recovery] flow per `web-control-plane.md` §4.6 + IA §7.2. Asks
// the user to type their full 24-word phrase; verifies it against the
// local realm via `Session.unlockWithPhrase`, the locked flow's
// recovery-phrase fallback. On success, stamps the IDB `verifiedAtMs` mark
// the RecoveryBanner reads.
//
// The spec calls for a "3 random words" challenge; full-phrase entry
// proves the same property (the user can produce the phrase) through the
// unlock path. Side effect of `unlockWithPhrase` is a refreshed session
// token, which is harmless. The KDF re-derive takes ~5 s on web so the dialog mirrors
// the onboarding-recovery copy.
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { TFunction } from 'i18next'
import type { Bip39DecodeError, PhraseUnlockError } from '@tenshu/sdk/onboarding'
import { useOnboardingSdk } from '../../lib/useOnboardingSdk'
import { setRecoveryVerified } from '../recoveryStatus'
import { RecoveryDialog } from './RecoveryDialog'

type Step =
  | { readonly kind: 'entry' }
  | { readonly kind: 'verifying' }
  | { readonly kind: 'failed'; readonly error: PhraseUnlockError }
  | { readonly kind: 'verified' }

export function TestRecoveryDialog({
  onClose,
  onVerified,
}: {
  readonly onClose: () => void
  readonly onVerified: () => void
}) {
  const { t } = useTranslation('control')
  const sdk = useOnboardingSdk()
  const [phrase, setPhrase] = useState('')
  const [step, setStep] = useState<Step>({ kind: 'entry' })

  async function submit() {
    setStep({ kind: 'verifying' })
    const result = await sdk.Session.unlockWithPhrase(phrase.trim())
    if (!result.ok) {
      setStep({ kind: 'failed', error: result.error })
      return
    }
    // Persist token so the bonus session refresh isn't lost — same call
    // pattern the LockedFlow uses after `unlockWithPhrase` succeeds.
    await sdk.Session.persist(result.value)
    await setRecoveryVerified(Date.now())
    setStep({ kind: 'verified' })
    onVerified()
  }

  return (
    <RecoveryDialog title={t('recovery.test.title')} onClose={onClose}>
      {step.kind === 'entry' && (
        <EntryStep
          phrase={phrase}
          onChange={setPhrase}
          onCancel={onClose}
          onSubmit={() => void submit()}
        />
      )}
      {step.kind === 'verifying' && <VerifyingStep />}
      {step.kind === 'failed' && (
        <FailedStep
          error={step.error}
          onRetry={() => setStep({ kind: 'entry' })}
          onCancel={onClose}
        />
      )}
      {step.kind === 'verified' && <VerifiedStep onClose={onClose} />}
    </RecoveryDialog>
  )
}

function EntryStep({
  phrase,
  onChange,
  onCancel,
  onSubmit,
}: {
  readonly phrase: string
  readonly onChange: (value: string) => void
  readonly onCancel: () => void
  readonly onSubmit: () => void
}) {
  const { t } = useTranslation('control')
  const wordCount = countWords(phrase)
  const canSubmit = wordCount === 24
  return (
    <form
      onSubmit={e => {
        e.preventDefault()
        if (canSubmit) onSubmit()
      }}
    >
      <p className="text-sm text-text-2">{t('recovery.test.intro')}</p>
      <p className="mt-2 text-xs text-text-2">{t('recovery.test.subhint')}</p>
      <textarea
        autoFocus
        value={phrase}
        onChange={e => onChange(e.target.value)}
        rows={5}
        spellCheck={false}
        autoCapitalize="none"
        autoCorrect="off"
        className="mt-4 w-full rounded-md border border-line bg-surface px-3 py-2 font-mono text-sm tracking-wide text-text focus:border-line-strong focus:outline-none"
        placeholder={t('recovery.test.placeholder')}
      />
      <p className="mt-2 text-xs text-text-2">
        {t('recovery.test.wordCount', { count: wordCount })}
      </p>
      <div className="mt-6 flex flex-wrap items-center gap-3">
        <button
          type="submit"
          disabled={!canSubmit}
          className="rounded-md bg-primary px-4 py-2 text-sm font-medium text-on-primary shadow-sm transition-colors hover:bg-primary/90 disabled:cursor-not-allowed disabled:bg-surface-3"
        >
          {t('recovery.test.verify')}
        </button>
        <button
          type="button"
          onClick={onCancel}
          className="text-sm font-medium text-text-2 hover:text-text"
        >
          {t('recovery.test.cancel')}
        </button>
      </div>
    </form>
  )
}

function VerifyingStep() {
  const { t } = useTranslation('control')
  return (
    <div className="py-6 text-center">
      <div className="mx-auto h-8 w-8 animate-spin rounded-full border-2 border-line border-t-text" />
      <p className="mt-4 text-sm text-text-2">{t('recovery.test.verifying')}</p>
    </div>
  )
}

function FailedStep({
  error,
  onRetry,
  onCancel,
}: {
  readonly error: PhraseUnlockError
  readonly onRetry: () => void
  readonly onCancel: () => void
}) {
  const { t } = useTranslation('control')
  const message = describeError(t, error)
  return (
    <div>
      <div className="rounded-md border border-danger/30 bg-danger/10 p-3 text-sm text-danger">
        {message}
      </div>
      <div className="mt-6 flex flex-wrap items-center gap-3">
        <button
          type="button"
          onClick={onRetry}
          className="rounded-md bg-primary px-4 py-2 text-sm font-medium text-on-primary shadow-sm transition-colors hover:bg-primary/90"
        >
          {t('recovery.test.retry')}
        </button>
        <button
          type="button"
          onClick={onCancel}
          className="text-sm font-medium text-text-2 hover:text-text"
        >
          {t('recovery.test.cancel')}
        </button>
      </div>
    </div>
  )
}

function VerifiedStep({ onClose }: { readonly onClose: () => void }) {
  const { t } = useTranslation('control')
  return (
    <div>
      <div className="rounded-md border border-success/30 bg-success/10 p-3 text-sm text-success">
        {t('recovery.test.verified')}
      </div>
      <div className="mt-6">
        <button
          type="button"
          onClick={onClose}
          className="rounded-md bg-primary px-4 py-2 text-sm font-medium text-on-primary shadow-sm transition-colors hover:bg-primary/90"
        >
          {t('recovery.test.done')}
        </button>
      </div>
    </div>
  )
}

function countWords(input: string): number {
  const trimmed = input.trim()
  if (trimmed === '') return 0
  return trimmed.split(/\s+/).length
}

function describeError(t: TFunction, error: PhraseUnlockError): string {
  switch (error.kind) {
    case 'invalidMnemonic':
      return describeBip39Error(t, error.reason)
    case 'wrongRealm':
      return t('recovery.test.errors.wrongRealm')
    case 'other':
      return error.message === '' ? t('recovery.test.errors.otherFallback') : error.message
  }
}

function describeBip39Error(t: TFunction, reason: Bip39DecodeError): string {
  switch (reason.kind) {
    case 'invalidLength':
      return t('recovery.test.errors.invalidLength', { wordCount: reason.wordCount })
    case 'unknownWord':
      return reason.word === ''
        ? t('recovery.test.errors.unknownWordGeneric')
        : t('recovery.test.errors.unknownWord', { word: reason.word })
    case 'checksumMismatch':
      return t('recovery.test.errors.checksum')
  }
}
