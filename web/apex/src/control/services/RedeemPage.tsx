// Redeem a code for a tier. Replaces the Rei Key box: `rei` was retired as a plan
// key (CE-4 — "an identity, never a plan"), but the endpoint behind that box is
// real and binds an actual tier to this realm.
//
// ⚠ Identity-light by design: `/commerce/entitlement/redeem` needs an ownerId and
// takes the session token only if one exists. You do not have to sign in to redeem.
import { useState } from 'react'
import { useNavigate } from 'react-router'
import { useTranslation } from 'react-i18next'
import { usePublisherClient } from './publisherContext'

export function RedeemPage() {
  const { t } = useTranslation('control')
  const client = usePublisherClient()
  const navigate = useNavigate()
  const [code, setCode] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    if (busy) return
    setBusy(true)
    setError(null)
    const result = await client.redeem(code)
    setBusy(false)
    if (result.kind === 'redeemed') {
      navigate('/control/services', { replace: true })
    } else if (result.kind === 'notSaved') {
      setError(
        t(result.signedIn ? 'services.redeem.notSavedSignedIn' : 'services.redeem.notSavedSignedOut'),
      )
    } else if (result.kind === 'invalid') {
      setError(t('services.redeem.invalid'))
    } else {
      setError(result.message)
    }
  }

  return (
    <div className="mx-auto max-w-md">
      <h1 className="text-2xl font-semibold tracking-tight">{t('services.redeem.title')}</h1>
      <p className="mt-2 text-sm text-text-2">{t('services.redeem.body')}</p>
      <form onSubmit={submit} className="mt-4 space-y-4">
        <label className="block text-sm">
          <span className="text-text-2">{t('services.redeem.label')}</span>
          <input
            type="text"
            required
            value={code}
            onChange={e => setCode(e.target.value)}
            className="mt-1 w-full rounded-md border border-line bg-surface px-3 py-2 font-mono text-text"
          />
        </label>
        {error !== null && <p className="text-sm text-error">{error}</p>}
        <div className="flex flex-wrap gap-3">
          <button
            type="submit"
            disabled={busy}
            className="rounded-md bg-primary px-4 py-2 text-sm font-medium text-on-primary disabled:opacity-60"
          >
            {busy ? t('services.redeem.checking') : t('services.redeem.continue')}
          </button>
          <button
            type="button"
            onClick={() => navigate('/control/services/picker')}
            className="rounded-md border border-line px-4 py-2 text-sm text-text-2"
          >
            {t('services.redeem.cancel')}
          </button>
        </div>
      </form>
    </div>
  )
}
