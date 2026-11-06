// §6.4 disconnect per `docs/proposals/onboarding-low-friction.md`. Drops
// the local Publisher binding after confirmation. Native uses passphrase
// re-entry as the confirm factor (biometric deferred); web has the
// WebAuthn-PRF session as its equivalent — but the apex SDK doesn't
// surface a "re-verify session" call yet, so v1 here uses a typed
// "Disconnect" confirmation string instead. Same UX intent: a
// destructive action gated on a non-trivial keystroke.
import { useState } from 'react'
import { useNavigate } from 'react-router'
import { useTranslation } from 'react-i18next'
import { usePublisherClient } from './publisherContext'

const CONFIRMATION_PHRASE = 'Disconnect'

export function DisconnectConfirmPage() {
  const { t } = useTranslation('control')
  const client = usePublisherClient()
  const navigate = useNavigate()
  const [confirmation, setConfirmation] = useState('')
  const [busy, setBusy] = useState(false)

  const canConfirm = confirmation === CONFIRMATION_PHRASE && !busy

  async function onConfirm() {
    if (!canConfirm) return
    setBusy(true)
    await client.disconnect()
    setBusy(false)
    navigate('/control/services', { replace: true })
  }

  return (
    <div className="mx-auto max-w-md">
      <h1 className="text-center text-2xl font-semibold tracking-tight">
        {t('services.disconnect.title')}
      </h1>
      <div className="mt-4 rounded-md border border-danger/30 bg-danger/10 p-3 text-sm text-danger">
        {t('services.disconnect.warning')}
      </div>
      <div className="mt-4 space-y-3">
        <label className="block text-sm font-medium">
          {t('services.disconnect.labelPrefix')}
          <span className="font-mono">{CONFIRMATION_PHRASE}</span>
          {t('services.disconnect.labelSuffix')}
          <input
            type="text"
            value={confirmation}
            onChange={e => setConfirmation(e.target.value)}
            disabled={busy}
            className="mt-1 block w-full rounded-md border border-line px-3 py-2 text-sm shadow-sm focus:border-line-strong focus:outline-none focus:ring-1 focus:ring-line-strong disabled:bg-surface-2"
          />
        </label>
        <button
          type="button"
          onClick={() => navigate(-1)}
          disabled={busy}
          className="block w-full rounded-md bg-primary px-4 py-2 text-sm font-medium text-on-primary shadow-sm transition-colors hover:bg-primary/90"
        >
          {t('services.disconnect.cancel')}
        </button>
        <button
          type="button"
          onClick={onConfirm}
          disabled={!canConfirm}
          className="block w-full rounded-md bg-danger px-4 py-2 text-sm font-medium text-white shadow-sm transition-colors hover:bg-danger/90 disabled:cursor-not-allowed disabled:bg-danger/40"
        >
          {busy ? t('services.disconnect.inFlight') : t('services.disconnect.confirm')}
        </button>
      </div>
    </div>
  )
}
