// The purchasable tiers, as the picker presents them.
//
// ⚠ Built from plans.ts, the apex's one copy of the quotas and prices (the public pricing page
// reads the same list). Prices are display-only: the amount actually charged comes from the
// Stripe price id the SERVICE maps the product to, never from anything sent by this page.
//
// ⚠⚠ The price is deliberately NOT restated on the acknowledgement step. The
// payment page states it authoritatively, and repeating a client-computed figure
// beside a legal acknowledgement is a way to be wrong twice.
import { PLANS, formatEuro } from './plans'
import { formatStorageBytes } from '../../lib/format'

export interface Tier {
  readonly id: 'free' | 'basic' | 'premium'
  /** Commerce product id; null for Free, which is not purchased. */
  readonly productId: string | null
  readonly limitBytes: number
  /** Display price, or null for Free. */
  readonly price: string | null
}

export const TIERS: readonly Tier[] = PLANS.map(plan =>
  plan.monthlyEuroCents === 0
    ? { id: plan.id, productId: null, limitBytes: plan.limitBytes, price: null }
    : { id: plan.id, productId: plan.productId, limitBytes: plan.limitBytes, price: formatEuro(plan.monthlyEuroCents, 'en') },
)

export function tierForProductId(productId: string | undefined): Tier | null {
  if (productId === undefined) return null
  return TIERS.find(t => t.productId === productId) ?? null
}

/** "5 GB" / "50 GB" / "2 TB" — the same formatter the storage gauge uses. */
export function tierStorageLabel(tier: Tier): string {
  return formatStorageBytes(tier.limitBytes)
}

/**
 * Where a paid tier is bought from. A browser buys signed in, so without a
 * session the way in is the email step, which returns to the purchase.
 */
export function purchasePath(productId: string, signedIn: boolean): string {
  return signedIn
    ? `/control/services/checkout/${productId}`
    : `/control/services/signup?return=checkout&product=${encodeURIComponent(productId)}`
}
