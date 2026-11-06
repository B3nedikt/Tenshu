// /control/settings/recovery/setup — §7.4 "Set up account recovery".
// The deferred phrase-capture flow, reachable from Settings → Recovery
// (web-roadmap 6.6, `docs/proposals/onboarding-low-friction.md` §7.4).
//
// New-user onboarding has no 24-word ceremony: the phrase is generated
// silently and only *captured* here,
// once the user has decided Tenshu is worth keeping. This page re-reads the
// phrase via `Recovery.revealPhrase()` (the gated SDK reveal — see the
// `RevealPhraseError` doc) and walks the user through the
// show + confirm screens. On a confirmed capture it marks recovery verified
// (`setRecoveryVerified`), which clears the Home RecoveryBanner.
//
// The SDK reveal (Kotlin `Recovery.revealPhrase`) reads the BIP-39 entropy the
// `MasterKeyWrapper` blob carries, held in `JsRuntime.currentEntropy` from
// `Realm.create`/unlock, so a fresh signup gets the real 24-word phrase here.
// `unavailable` ("coming soon") is an edge case — a wrap blob with no
// entropy, or a joiner device. The apex boundary mock also returns the
// phrase, so the flow stays fully exercisable under `?mock=1` and in tests.
import { useCallback, useState } from 'react'
import { Link } from 'react-router'
import { useTranslation } from 'react-i18next'
import type { RevealPhraseError } from '@tenshu/sdk/onboarding'
import { useOnboardingSdk } from '../../lib/useOnboardingSdk'
import { setRecoveryVerified } from '../recoveryStatus'
import { ConfirmMnemonicScreen, ShowMnemonicScreen } from './RecoveryCaptureScreens'

type Step =
  | { readonly kind: 'gate' }
  | { readonly kind: 'revealing' }
  | { readonly kind: 'error'; readonly error: RevealPhraseError }
  | { readonly kind: 'show'; readonly mnemonic: readonly string[] }
  | { readonly kind: 'confirm'; readonly mnemonic: readonly string[] }
  | { readonly kind: 'done' }

const RECOVERY_HOME = '/control/settings/recovery'

export function AccountRecoverySetupPage() {
  const { t } = useTranslation('control')
  const sdk = useOnboardingSdk()
  const [step, setStep] = useState<Step>({ kind: 'gate' })

  const reveal = useCallback(async () => {
    setStep({ kind: 'revealing' })
    const result = await sdk.Recovery.revealPhrase()
    if (result.ok) {
      setStep({ kind: 'show', mnemonic: result.value.trim().split(/\s+/) })
    } else {
      setStep({ kind: 'error', error: result.error })
    }
  }, [sdk])

  const onConfirmed = useCallback(() => {
    // Record the capture so the Home banner + Settings status panel flip to
    // verified. A write failure (private-mode IDB, etc.) shouldn't block the
    // user from finishing — the worst case is the banner staying up.
    void setRecoveryVerified(Date.now()).catch(() => {})
    setStep({ kind: 'done' })
  }, [])

  return (
    <div className="mx-auto max-w-2xl">
      <h1 className="text-2xl font-medium">{t('recoverySetup.title')}</h1>
      <p className="mt-1 text-sm text-text-2">{t('recoverySetup.subtitle')}</p>

      <div className="mt-8">
        {step.kind === 'gate' && <GateStep onStart={() => void reveal()} />}
        {step.kind === 'revealing' && <RevealingStep />}
        {step.kind === 'error' && <ErrorStep error={step.error} onRetry={() => void reveal()} />}
        {step.kind === 'show' && (
          <ShowMnemonicScreen
            mnemonic={step.mnemonic}
            onContinue={() => setStep({ kind: 'confirm', mnemonic: step.mnemonic })}
          />
        )}
        {step.kind === 'confirm' && (
          <ConfirmMnemonicScreen
            mnemonic={step.mnemonic}
            onConfirmed={onConfirmed}
            onRedisplay={() => setStep({ kind: 'show', mnemonic: step.mnemonic })}
          />
        )}
        {step.kind === 'done' && <DoneStep />}
      </div>
    </div>
  )
}

function GateStep({ onStart }: { readonly onStart: () => void }) {
  const { t } = useTranslation('control')
  return (
    <div className="rounded-md border border-line bg-surface p-6">
      <p className="text-sm text-text-2">{t('recoverySetup.gateBody')}</p>
      <p className="mt-2 text-xs text-text-2">{t('recoverySetup.gateNote')}</p>
      <div className="mt-6 flex flex-wrap items-center gap-3">
        <button
          type="button"
          onClick={onStart}
          className="rounded-md bg-primary px-4 py-2 text-sm font-medium text-on-primary shadow-sm transition-colors hover:bg-primary/90"
        >
          {t('recoverySetup.start')}
        </button>
        <Link
          to={RECOVERY_HOME}
          className="text-sm font-medium text-text-2 hover:text-text"
        >
          {t('recoverySetup.cancel')}
        </Link>
      </div>
    </div>
  )
}

function RevealingStep() {
  const { t } = useTranslation('control')
  return (
    <div className="rounded-md border border-line p-6 text-center">
      <div className="mx-auto h-8 w-8 animate-spin rounded-full border-2 border-line border-t-text" />
      <p className="mt-4 text-sm text-text-2">{t('recoverySetup.revealing')}</p>
    </div>
  )
}

function ErrorStep({
  error,
  onRetry,
}: {
  readonly error: RevealPhraseError
  readonly onRetry: () => void
}) {
  const { t } = useTranslation('control')
  // `unavailable` is an edge case (a wrap blob carrying no entropy, or a
  // joiner device). It reads as an informational "coming soon" rather than a
  // failure; the other branches are genuine errors.
  const isComingSoon = error.kind === 'unavailable'
  const message =
    error.kind === 'unavailable'
      ? t('recoverySetup.errors.unavailable')
      : error.kind === 'locked'
        ? t('recoverySetup.errors.locked')
        : error.kind === 'joinerDevice'
          ? t('recoverySetup.errors.joinerDevice')
          : t('recoverySetup.errors.other', { message: error.message })
  return (
    <div
      className={[
        'rounded-md border p-4',
        isComingSoon
          ? 'border-line bg-surface'
          : 'border-danger/30 bg-danger/10',
      ].join(' ')}
    >
      <p
        className={
          isComingSoon
            ? 'text-sm text-text-2'
            : 'text-sm text-danger'
        }
      >
        {message}
      </p>
      <div className="mt-4 flex flex-wrap items-center gap-3">
        {!isComingSoon && error.kind !== 'joinerDevice' && (
          <button
            type="button"
            onClick={onRetry}
            className="rounded-md bg-primary px-4 py-2 text-sm font-medium text-on-primary shadow-sm transition-colors hover:bg-primary/90"
          >
            {t('recoverySetup.retry')}
          </button>
        )}
        <Link
          to={RECOVERY_HOME}
          className="text-sm font-medium text-text-2 hover:text-text"
        >
          {t('recoverySetup.backToRecovery')}
        </Link>
      </div>
    </div>
  )
}

function DoneStep() {
  const { t } = useTranslation('control')
  return (
    <div className="rounded-md border border-success/30 bg-success/10 p-6">
      <p className="text-sm font-medium text-success">
        {t('recoverySetup.done.title')}
      </p>
      <p className="mt-1 text-sm text-success">
        {t('recoverySetup.done.body')}
      </p>
      <div className="mt-4">
        <Link
          to={RECOVERY_HOME}
          className="text-sm font-medium text-success underline-offset-4 hover:underline"
        >
          {t('recoverySetup.done.back')}
        </Link>
      </div>
    </div>
  )
}
