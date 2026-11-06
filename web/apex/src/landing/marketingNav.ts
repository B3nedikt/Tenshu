// What the marketing chrome shares between the landing page and the static support pages:
// the header's link targets (MarketingHeader, MarketingMobileMenu) and the page canvas.

/** The landing page's root classes (LandingPage.tsx); a support page's <body> carries the same. */
export const MARKETING_PAGE_CLASSES = 'bg-surface text-text'

export const DOCS_HREF = 'https://github.com/B3nedikt/Tenshu/tree/master/docs'

/**
 * Where the header's "Support" entry goes from the SPA's pages: the support hub's entry point, a
 * static page (support-pages/generate.ts) that picks the visitor's language and redirects to
 * `/apps/support/<lang>`, or lists the languages with JavaScript off. The static pages pass the
 * hub in their own language instead. ⚠ Never `/support`: that path is reserved for a possible
 * support app (store-presence-and-support-pages.md §4.7).
 */
export const SUPPORT_HREF = '/apps/support'

/** The Vision page (VisionPage.tsx), an SPA route. Language comes from detection, like `/`. */
export const VISION_HREF = '/vision'

/**
 * The public roadmap (support-pages/roadmap.ts), a static page outside the SPA: Caddy 302s this
 * language-neutral URL to `/roadmap/<lang>` by Accept-Language, so it is a plain <a>, never a
 * router `Link` (the SPA's catch-all would send it back to `/`).
 */
export const ROADMAP_HREF = '/roadmap'

/**
 * The public pricing page (support-pages/pricing.ts), a static page outside the SPA like the
 * roadmap: Caddy 302s `/pricing` to `/pricing/<lang>`. The one page that names a price, so the
 * landing page and the app pages link here instead of repeating one.
 */
export const PRICING_HREF = '/pricing'

/**
 * The account-deletion page (AccountDeletionPage.tsx), an SPA route linked from the footer and
 * from Control's Services page. ⚠ A stable public URL: Google Play's listing for Control points at
 * it, and it must never sit behind the pre-launch curtain (lib/alwaysPublicPaths.ts).
 */
export const ACCOUNT_DELETION_HREF = '/account/delete'

export interface MarketingNavProps {
  /** The "Support" entry's target; default SUPPORT_HREF. */
  readonly supportHref?: string
  /** The entry marked as the current page (`aria-current="page"` + the active style). */
  readonly current?: 'support' | 'vision' | 'pricing' | 'roadmap'
}
