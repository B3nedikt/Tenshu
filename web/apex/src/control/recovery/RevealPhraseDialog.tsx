// [Re-display recovery phrase] flow per `web-control-plane.md` §4.6.1.
// Auth gate: the §4.6.1 dedicated WebAuthn re-challenge from
// `lib/webauthn.ts` — fresh ceremony, action-bound, ~60s freshness check.
// The user must approve a passkey/Touch ID prompt at the moment of click
// even when the session is already unlocked.
//
// Phase B v1 ships the auth gate as a working primitive; the reveal panel is
// still staged as "Coming soon".
//
// ⚠ The SDK half that this used to wait on has LANDED: the v2
// `tenshu-sdk-master-keys` blob carries the BIP-39 *entropy* alongside the
// master keypair, and `Recovery.revealPhrase()` reads it and `Bip39.encode`s
// it (`:tenshu-sdk-js` Recovery.kt; typed on `RecoveryApi` in
// `web/sdk/src/onboarding.ts`). What remains is purely wiring this dialog to
// that call and rendering the phrase — not a protocol or migration change.
//
// Until that wiring exists the threat-model T-RC-07 entry still reflects what
// ships: the unattended-tab vector for phrase capture is not reachable from
// this UI, and the auth gate that mitigates it is in place and tested for when
// reveal is wired.
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { TFunction } from 'i18next'
import {
  getDedicatedAssertion,
  isDedicatedAssertionFresh,
  isWebAuthnSupported,
  type DedicatedAssertion,
  type WebAuthnFailure,
} from '../../lib/webauthn'
import { RecoveryDialog } from './RecoveryDialog'

type Step =
  | { readonly kind: 'gate' }
  | { readonly kind: 'authenticating' }
  | { readonly kind: 'authFailed'; readonly error: WebAuthnFailure }
  | { readonly kind: 'staged'; readonly assertion: DedicatedAssertion }

const REVEAL_ACTION = 'recovery.reveal-phrase'

export function RevealPhraseDialog({ onClose }: { readonly onClose: () => void }) {
  const { t } = useTranslation('control')
  const [step, setStep] = useState<Step>({ kind: 'gate' })

  async function authenticate() {
    setStep({ kind: 'authenticating' })
    const result = await getDedicatedAssertion(REVEAL_ACTION)
    if (!result.ok) {
      setStep({ kind: 'authFailed', error: result.error })
      return
    }
    if (!isDedicatedAssertionFresh(result.value)) {
      // Defence in depth — the assertion just resolved so it should
      // always be fresh. Treat a stale result as a hardware/clock
      // anomaly and bounce the user back to retry.
      setStep({
        kind: 'authFailed',
        error: { kind: 'unknown', message: t('recovery.reveal.errors.assertionExpired') },
      })
      return
    }
    setStep({ kind: 'staged', assertion: result.value })
  }

  return (
    <RecoveryDialog title={t('recovery.reveal.title')} onClose={onClose}>
      {step.kind === 'gate' && (
        <GateStep
          supported={isWebAuthnSupported()}
          onContinue={() => void authenticate()}
          onCancel={onClose}
        />
      )}
      {step.kind === 'authenticating' && <AuthenticatingStep />}
      {step.kind === 'authFailed' && (
        <AuthFailedStep
          error={step.error}
          onRetry={() => setStep({ kind: 'gate' })}
          onCancel={onClose}
        />
      )}
      {step.kind === 'staged' && <StagedStep onClose={onClose} />}
    </RecoveryDialog>
  )
}

function GateStep({
  supported,
  onContinue,
  onCancel,
}: {
  readonly supported: boolean
  readonly onContinue: () => void
  readonly onCancel: () => void
}) {
  const { t } = useTranslation('control')
  return (
    <div>
      <p className="text-sm text-text-2">{t('recovery.reveal.gateBody')}</p>
      <p className="mt-2 text-xs text-text-2">{t('recovery.reveal.gateNote')}</p>
      {!supported && (
        <div className="mt-4 rounded-md border border-warning/30 bg-warning/10 p-3 text-sm text-warning">
          {t('recovery.reveal.noWebAuthn')}
        </div>
      )}
      <div className="mt-6 flex flex-wrap items-center gap-3">
        <button
          type="button"
          disabled={!supported}
          onClick={onContinue}
          className="rounded-md bg-primary px-4 py-2 text-sm font-medium text-on-primary shadow-sm transition-colors hover:bg-primary/90 disabled:cursor-not-allowed disabled:bg-surface-3"
        >
          {t('recovery.reveal.approve')}
        </button>
        <button
          type="button"
          onClick={onCancel}
          className="text-sm font-medium text-text-2 hover:text-text"
        >
          {t('recovery.reveal.cancel')}
        </button>
      </div>
    </div>
  )
}

function AuthenticatingStep() {
  const { t } = useTranslation('control')
  return (
    <div className="py-6 text-center">
      <div className="mx-auto h-8 w-8 animate-spin rounded-full border-2 border-line border-t-text" />
      <p className="mt-4 text-sm text-text-2">
        {t('recovery.reveal.authenticating')}
      </p>
    </div>
  )
}

function AuthFailedStep({
  error,
  onRetry,
  onCancel,
}: {
  readonly error: WebAuthnFailure
  readonly onRetry: () => void
  readonly onCancel: () => void
}) {
  const { t } = useTranslation('control')
  return (
    <div>
      <div className="rounded-md border border-danger/30 bg-danger/10 p-3 text-sm text-danger">
        {describeAuthFailure(t, error)}
      </div>
      <div className="mt-6 flex flex-wrap items-center gap-3">
        <button
          type="button"
          onClick={onRetry}
          className="rounded-md bg-primary px-4 py-2 text-sm font-medium text-on-primary shadow-sm transition-colors hover:bg-primary/90"
        >
          {t('recovery.reveal.retry')}
        </button>
        <button
          type="button"
          onClick={onCancel}
          className="text-sm font-medium text-text-2 hover:text-text"
        >
          {t('recovery.reveal.cancel')}
        </button>
      </div>
    </div>
  )
}

function StagedStep({ onClose }: { readonly onClose: () => void }) {
  const { t } = useTranslation('control')
  // Auth gate succeeded. `Recovery.revealPhrase()` already returns the phrase
  // (see the file header); this step is where that call belongs once the panel
  // is wired. Until then we acknowledge the gate worked and explain what's
  // missing.
  return (
    <div>
      <div className="rounded-md border border-success/30 bg-success/10 p-3 text-sm text-success">
        {t('recovery.reveal.approved')}
      </div>
      <div className="mt-4 rounded-md border border-line bg-surface p-4 text-sm text-text-2">
        <p className="font-medium text-text">{t('recovery.reveal.stagedTitle')}</p>
        <p className="mt-2">{t('recovery.reveal.stagedBody')}</p>
        <p className="mt-2 text-xs text-text-2">{t('recovery.reveal.stagedFallback')}</p>
      </div>
      <div className="mt-6">
        <button
          type="button"
          onClick={onClose}
          className="rounded-md bg-primary px-4 py-2 text-sm font-medium text-on-primary shadow-sm transition-colors hover:bg-primary/90"
        >
          {t('recovery.reveal.done')}
        </button>
      </div>
    </div>
  )
}

function describeAuthFailure(t: TFunction, error: WebAuthnFailure): string {
  switch (error.kind) {
    case 'cancelled':
      return t('recovery.reveal.errors.cancelled')
    case 'wrongCredential':
      return t('recovery.reveal.errors.wrongCredential')
    case 'hardware':
      return t('recovery.reveal.errors.hardware')
    case 'unsupported':
      return t('recovery.reveal.errors.unsupported')
    case 'unknown':
      return error.message === '' ? t('recovery.reveal.errors.unknownFallback') : error.message
  }
}
