// Purchase a paid tier. Two steps: the withdrawal acknowledgement, then a hand-off
// to Stripe's hosted checkout.
//
// ⚠⚠ The checkbox here is a UI affordance; the RULE lives in the service.
// `/commerce/checkout` answers `400 acknowledgement_required` unless
// `withdrawalAcknowledged` is true AND `tosVersion` is non-blank — because the
// route is an ordinary POST anyone can make, so a UI-only tick would leave the
// terms claiming a waiver we could not evidence. Absent and explicitly-false are
// treated identically, since a DEFAULTED field is exactly what a waiver may never
// be.
//
// ⚠⚠ Reached signed in, or not at all. Stripe is handed the session's email, so
// the plan lands on the account this device can fetch it from; with a free-typed
// email it landed wherever the buyer's typing put it. Closing the tab after
// paying is then harmless — the Services page fetches the plan when it opens.
import { useState } from 'react'
import { Navigate, useNavigate, useParams } from 'react-router'
import { useTranslation } from 'react-i18next'
import { usePublisherAccount, usePublisherClient } from './publisherContext'
import { purchasePath, tierForProductId, tierStorageLabel } from './tiers'
import { TOS_VERSION } from './commerceApi'
import { termsWithdrawalUrl } from '@tenshu/web-tokens/legal'

type Step =
  | 'acknowledge'
  | 'launching'
  | 'unavailable'
  | 'alreadySubscribed'
  | 'planChange'
  | 'subscribedElsewhere'
  | 'error'

export function CheckoutPage() {
  const { t, i18n } = useTranslation('control')
  const client = usePublisherClient()
  const account = usePublisherAccount()
  const navigate = useNavigate()
  const { productId } = useParams()
  const tier = tierForProductId(productId)
  // ⚠ Acknowledge is the INITIAL step, not one the flow can skip past.
  const [step, setStep] = useState<Step>('acknowledge')
  const [acknowledged, setAcknowledged] = useState(false)
  const [message, setMessage] = useState<string | null>(null)

  if (tier === null || tier.productId === null) {
    return (
      <div className="mx-auto max-w-md">
        <h1 className="text-2xl font-semibold tracking-tight">{t('services.checkout.title')}</h1>
        <p className="mt-4 text-sm text-text-2">{t('services.checkout.unknownTier')}</p>
      </div>
    )
  }

  if (account === null) {
    return <Navigate to={purchasePath(tier.productId, false)} replace />
  }
  const signInPath = purchasePath(tier.productId, false)

  async function openPortal() {
    setMessage(null)
    const result = await client.openBillingPortal()
    if (result.kind === 'ready') {
      window.location.assign(result.portalUrl)
      return
    }
    setMessage(result.kind === 'error' ? result.message : t('services.checkout.genericError'))
  }

  async function start() {
    if (!acknowledged || tier === null || tier.productId === null) return
    setStep('launching')
    setMessage(null)
    const result = await client.startCheckout(tier.productId, true)
    if (result.kind === 'ready') {
      // Same tab: Stripe redirects back to the result route when it is done.
      window.location.assign(result.checkoutUrl)
      return
    }
    if (result.kind === 'signedOut') {
      // The session expired between the picker and here.
      navigate(signInPath, { replace: true })
      return
    }
    if (
      result.kind === 'unavailable' ||
      result.kind === 'alreadySubscribed' ||
      result.kind === 'subscribedElsewhere' ||
      result.kind === 'planChange'
    ) {
      setStep(result.kind)
      return
    }
    setStep('error')
    setMessage(result.message)
  }

  return (
    <div className="mx-auto max-w-md">
      <h1 className="text-2xl font-semibold tracking-tight">{t('services.checkout.title')}</h1>
      <p className="mt-2 text-sm text-text-2">
        {t('services.checkout.tier', {
          tier: t(`services.picker.tiers.${tier.id}.title`),
          storage: tierStorageLabel(tier),
        })}
      </p>

      {step === 'acknowledge' && (
        <div className="mt-6 space-y-4">
          <p className="text-sm text-text">{t('services.checkout.ackBody')}</p>
          <label className="flex items-start gap-3 text-sm">
            <input
              type="checkbox"
              checked={acknowledged}
              onChange={e => setAcknowledged(e.target.checked)}
              className="mt-0.5"
            />
            <span className="text-text">{t('services.checkout.ackCheckbox')}</span>
          </label>
          {/* The withdrawal notice and model form: without it the period never starts (§ 356
              Abs. 3 BGB) and the pro-rata compensation the tick asks for is not owed (§ 357a
              Abs. 2). A plain <a>: the terms are a static page. */}
          <p className="text-sm">
            <a
              href={termsWithdrawalUrl(i18n.language)}
              target="_blank"
              rel="noreferrer"
              className="font-medium text-text underline underline-offset-4"
            >
              {t('services.checkout.ackWithdrawalLink')}
            </a>
          </p>
          <p className="text-xs text-text-3">
            {t('services.checkout.ackTerms', { version: TOS_VERSION })}
          </p>
          <div className="flex flex-wrap gap-3">
            <button
              type="button"
              disabled={!acknowledged}
              onClick={() => void start()}
              className="rounded-md bg-primary px-4 py-2 text-sm font-medium text-on-primary disabled:opacity-60"
            >
              {t('services.checkout.continue')}
            </button>
            <button
              type="button"
              onClick={() => navigate('/control/services/picker')}
              className="rounded-md border border-line px-4 py-2 text-sm text-text-2"
            >
              {t('services.checkout.cancel')}
            </button>
          </div>
        </div>
      )}

      {step === 'launching' && (
        <p className="mt-6 text-sm text-text-2">{t('services.checkout.launching')}</p>
      )}

      {step === 'unavailable' && (
        <p className="mt-6 text-sm text-text-2">{t('services.checkout.unavailable')}</p>
      )}

      {step === 'alreadySubscribed' && (
        <div className="mt-6 space-y-3">
          <p className="text-sm text-text">{t('services.checkout.alreadySubscribed')}</p>
          <button
            type="button"
            onClick={() => navigate('/control/services')}
            className="rounded-md border border-line px-4 py-2 text-sm text-text-2"
          >
            {t('services.checkoutResult.backToServices')}
          </button>
        </div>
      )}

      {step === 'subscribedElsewhere' && (
        <div className="mt-6 space-y-3">
          <p className="text-sm text-text">{t('services.checkout.subscribedElsewhere')}</p>
          <button
            type="button"
            onClick={() => navigate('/control/services')}
            className="rounded-md border border-line px-4 py-2 text-sm text-text-2"
          >
            {t('services.checkoutResult.backToServices')}
          </button>
        </div>
      )}

      {step === 'planChange' && (
        <div className="mt-6 space-y-3">
          <p className="text-sm text-text">{t('services.checkout.planChange')}</p>
          {message !== null && <p className="text-sm text-error">{message}</p>}
          <button
            type="button"
            onClick={() => void openPortal()}
            className="rounded-md bg-primary px-4 py-2 text-sm font-medium text-on-primary"
          >
            {t('services.checkout.changeInBilling')}
          </button>
        </div>
      )}

      {step === 'error' && (
        <div className="mt-6 space-y-3">
          <p className="text-sm text-error">{message ?? t('services.checkout.genericError')}</p>
          <button
            type="button"
            onClick={() => setStep('acknowledge')}
            className="rounded-md border border-line px-4 py-2 text-sm text-text-2"
          >
            {t('services.checkout.retry')}
          </button>
        </div>
      )}
    </div>
  )
}
