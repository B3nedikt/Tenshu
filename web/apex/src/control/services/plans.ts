// The storage plans the apex shows, in ONE place: the plan picker (tiers.ts), the storage card
// (servicesCardState.ts), the commerce client (commerceApi.ts) and the public pricing page
// (support-pages/pricing.ts) all read them from here.
//
// ⚠ Display only. The canonical quotas are `EntitlementProducts` in `:storage-service-proxy`, the
// product ids `ProductCatalog` in `:commerce-service`, and what a buyer is actually charged is the
// Stripe price the SERVICE maps the product to (commerce-accounts-and-sessions.md §2.1). Change a
// price there first, then here, then the Terms (support-pages/terms.ts §"Plans, prices and payment",
// with a TERMS_DATE bump); generate.test.ts fails while the Terms and this file disagree.
//
// ⚠ Pure: no imports. Plain Node runs it for the support-page build.

export const PRODUCT_FREE = 'tenshu.drive.free'
export const PRODUCT_BASIC = 'tenshu.drive.basic'
export const PRODUCT_PREMIUM = 'tenshu.drive.premium'

/**
 * Tier quotas, mirroring `EntitlementProducts` (the canonical map).
 *
 * ⚠ Free is 5 GiB. The apex previously carried 1 GiB here — the stale web-genesis figure
 * `retention-quota-model.md` D9 corrected everywhere else.
 */
export const FREE_LIMIT_BYTES = 5 * 1024 * 1024 * 1024
export const BASIC_LIMIT_BYTES = 50 * 1024 * 1024 * 1024
export const PREMIUM_LIMIT_BYTES = 2 * 1024 * 1024 * 1024 * 1024

export interface Plan {
  readonly id: 'free' | 'basic' | 'premium'
  readonly productId: string
  readonly limitBytes: number
  /** The monthly price in euro cents, VAT included; 0 for Free. */
  readonly monthlyEuroCents: number
}

/** Smallest first: the order every surface lists them in. */
export const PLANS: readonly Plan[] = [
  { id: 'free', productId: PRODUCT_FREE, limitBytes: FREE_LIMIT_BYTES, monthlyEuroCents: 0 },
  { id: 'basic', productId: PRODUCT_BASIC, limitBytes: BASIC_LIMIT_BYTES, monthlyEuroCents: 299 },
  { id: 'premium', productId: PRODUCT_PREMIUM, limitBytes: PREMIUM_LIMIT_BYTES, monthlyEuroCents: 999 },
]

/**
 * A price as the site writes it: "€2.99" in English, "2,99 €" in German and French — the Terms'
 * own spelling, so the pricing page and the Terms read the same.
 */
export function formatEuro(cents: number, lang: string): string {
  const amount = (cents / 100).toFixed(2)
  return lang === 'en' ? `€${amount}` : `${amount.replace('.', ',')} €`
}
