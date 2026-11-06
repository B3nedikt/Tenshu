// Where Stripe sends a browser customer back to.
//
// ⚠⚠ Claims nothing from the query parameter. This page is reached by a redirect
// the customer's browser followed, the least trustworthy source in the flow; what
// it reports as "active" is read off a record commerce signed for this realm.
//
// ⚠⚠ It is also where a purchase is fetched. The webhook only writes commerce's
// ledger — nothing pushes the plan to a browser — so this page asks for it, and a
// buyer with no session here is first asked to confirm the checkout email. The
// earlier version only said "your plan updates as soon as it syncs", which was
// true solely when another device of the realm happened to mint it.
import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import { useTranslation } from 'react-i18next'
import { PRODUCT_BASIC, PRODUCT_FREE, PRODUCT_PREMIUM } from './commerceApi'
import { usePublisherAccount, usePublisherClient } from './publisherContext'

type Activation =
  | { readonly kind: 'activating' }
  | { readonly kind: 'active'; readonly productId: string }
  | { readonly kind: 'pending' }

const PLAN_NAMES: Readonly<Record<string, string>> = {
  [PRODUCT_BASIC]: 'Basic',
  [PRODUCT_PREMIUM]: 'Premium',
}

export function CheckoutResultPage({
  attempts = 8,
  intervalMs = 2500,
}: {
  /** How often commerce is asked before the page stops waiting for the webhook. */
  readonly attempts?: number
  readonly intervalMs?: number
}) {
  const { t } = useTranslation('control')
  const [params] = useSearchParams()
  const status = params.get('status')
  const paid = status !== 'cancel' && status !== 'portal'
  const account = usePublisherAccount()
  const client = usePublisherClient()
  const [activation, setActivation] = useState<Activation>({ kind: 'activating' })

  useEffect(() => {
    if (!paid || account === null) return
    let cancelled = false
    // The webhook and this redirect race, so a first answer of Free means "not
    // yet" rather than "no": ask again for a while before saying so.
    async function poll() {
      for (let attempt = 0; attempt < attempts && !cancelled; attempt++) {
        const result = await client.syncPlan()
        if (cancelled) return
        if (result.kind === 'synced' && result.productId !== PRODUCT_FREE) {
          setActivation({ kind: 'active', productId: result.productId })
          return
        }
        if (result.kind === 'signedOut') break
        await new Promise(resolve => setTimeout(resolve, intervalMs))
      }
      if (!cancelled) setActivation({ kind: 'pending' })
    }
    void poll()
    return () => {
      cancelled = true
    }
  }, [paid, account, client, attempts, intervalMs])

  if (!paid) {
    const key = status === 'cancel' ? 'cancelled' : 'portal'
    return (
      <Shell title={t(`services.checkoutResult.${key}.title`)}>
        <p className="mt-3 text-sm text-text-2">{t(`services.checkoutResult.${key}.body`)}</p>
      </Shell>
    )
  }

  if (account === null) {
    return (
      <Shell title={t('services.checkoutResult.confirm.title')} primary={false}>
        <p className="mt-3 text-sm text-text-2">{t('services.checkoutResult.confirm.body')}</p>
        <div className="mt-6">
          <Link
            to="/control/services/signup?return=checkout"
            className="inline-flex rounded-md bg-primary px-4 py-2 text-sm font-medium text-on-primary"
          >
            {t('services.checkoutResult.confirm.cta')}
          </Link>
        </div>
      </Shell>
    )
  }

  if (activation.kind === 'active') {
    return (
      <Shell title={t('services.checkoutResult.active.title')}>
        <p className="mt-3 text-sm text-text-2">
          {t('services.checkoutResult.active.body', {
            plan: PLAN_NAMES[activation.productId] ?? activation.productId,
          })}
        </p>
      </Shell>
    )
  }

  return (
    <Shell title={t(`services.checkoutResult.${activation.kind}.title`)}>
      <p className="mt-3 text-sm text-text-2">
        {t(`services.checkoutResult.${activation.kind}.body`, {
          email: account.identityMethod.address,
        })}
      </p>
    </Shell>
  )
}

function Shell({
  title,
  primary = true,
  children,
}: {
  readonly title: string
  /** False when the page has its own primary action and Services is the way out. */
  readonly primary?: boolean
  readonly children: React.ReactNode
}) {
  const { t } = useTranslation('control')
  return (
    <div className="mx-auto max-w-md">
      <h1 className="text-2xl font-semibold tracking-tight">{title}</h1>
      {children}
      <div className="mt-6">
        <Link
          to="/control/services"
          className={
            primary
              ? 'inline-flex rounded-md bg-primary px-4 py-2 text-sm font-medium text-on-primary'
              : 'text-sm text-text-2 underline-offset-4 hover:underline'
          }
        >
          {t('services.checkoutResult.backToServices')}
        </Link>
      </div>
    </div>
  )
}
