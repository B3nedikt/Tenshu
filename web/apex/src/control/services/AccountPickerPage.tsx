// The tier picker. Free / Basic / Premium — the tiers the commerce plane actually
// sells (`commerce-accounts-and-sessions.md` §2.1). The Lifetime card is gone with
// the tier (dropped at Ph1) and the Rei Key card with the planKey (retired at
// CE-4); redeeming a code now has its own entry below the tiers.
import { Link, useNavigate } from 'react-router'
import { useTranslation } from 'react-i18next'
import { TIERS, tierStorageLabel, type Tier, purchasePath } from './tiers'
import { usePublisherAccount } from './publisherContext'

export function AccountPickerPage() {
  const { t } = useTranslation('control')
  const navigate = useNavigate()
  const account = usePublisherAccount()
  return (
    <div className="mx-auto max-w-2xl">
      <h1 className="text-center text-2xl font-semibold tracking-tight">
        {t('services.picker.title')}
      </h1>
      <p className="mt-3 text-center text-sm text-text-2">{t('services.picker.body')}</p>
      <div className="mt-6 space-y-3">
        {TIERS.map(tier => (
          <TierCard key={tier.id} tier={tier} signedIn={account !== null} />
        ))}
      </div>
      <div className="mt-6 text-center text-sm">
        <Link to="/control/services/redeem" className="text-text-2 underline-offset-4 hover:underline">
          {t('services.picker.redeemLink')}
        </Link>
      </div>
      <div className="mt-4 text-center">
        <button
          type="button"
          onClick={() => navigate(-1)}
          className="text-sm text-text-2 underline-offset-4 hover:text-text hover:underline"
        >
          {t('services.picker.cancel')}
        </button>
      </div>
    </div>
  )
}

function TierCard({ tier, signedIn }: { readonly tier: Tier; readonly signedIn: boolean }) {
  const { t } = useTranslation('control')
  const free = tier.productId === null
  // ⚠ A paid tier goes to the acknowledgement step, never straight to Stripe: the
  // service refuses a checkout without it, and the customer has to have seen it.
  const to =
    tier.productId === null
      ? '/control/services/signup'
      : purchasePath(tier.productId, signedIn)
  return (
    <section
      className={
        free
          ? 'rounded-lg border border-success/30 bg-success/10 p-4'
          : 'rounded-lg border border-line bg-surface p-4'
      }
    >
      <div className="flex items-baseline justify-between gap-3">
        <h2 className="font-semibold text-text">{t(`services.picker.tiers.${tier.id}.title`)}</h2>
        <span className="text-sm text-text-2">
          {tier.price === null
            ? t('services.picker.priceFree')
            : t('services.picker.pricePerMonth', { price: tier.price })}
        </span>
      </div>
      <p className="mt-2 text-sm text-text-2">
        {t(`services.picker.tiers.${tier.id}.body`, { storage: tierStorageLabel(tier) })}
      </p>
      <div className="mt-3">
        <Link
          to={to}
          className={
            free
              ? 'inline-flex rounded-md bg-primary px-3 py-1.5 text-sm font-medium text-on-primary hover:bg-primary/90'
              : 'inline-flex rounded-md border border-line px-3 py-1.5 text-sm font-medium text-text hover:bg-surface-3'
          }
        >
          {free
            ? signedIn
              ? t('services.picker.tiers.free.ctaSignedIn')
              : t('services.picker.tiers.free.cta')
            : t('services.picker.choose')}
        </Link>
      </div>
    </section>
  )
}
