// Marketing-page chrome per web-apex-landing.md §5. A fixed-height
// (`h-14`, matching the hero's full-screen offset) three-zone bar:
// brand lockup on the left, the Vision, Pricing, Roadmap, Docs + Support links centred, and the
// smart CTA on the right. On phones the centre + right collapse — only
// the brand and a "…" menu remain (see MarketingMobileMenu). Distinct
// from `Layout`'s post-auth chrome — this header signals "you're not
// inside Tenshu yet."
//
// ⚠ Also the static support pages' header: support-pages/generate.ts renders this component to
// HTML at build time (src/landing/supportChrome.tsx) and never hydrates it, so it must stay
// usable with JavaScript off — plain links, and a <details> menu on phones. A change here
// changes both surfaces.
import { Link } from 'react-router'
import { useTranslation } from 'react-i18next'
import { BrandLockup } from '../components/BrandLockup'
import { LandingCta } from './LandingCta'
import { MarketingMobileMenu } from './MarketingMobileMenu'
import { DOCS_HREF, PRICING_HREF, ROADMAP_HREF, SUPPORT_HREF, VISION_HREF, type MarketingNavProps } from './marketingNav'

export function MarketingHeader({ supportHref = SUPPORT_HREF, current }: MarketingNavProps = {}) {
  const { t } = useTranslation('landing')
  const linkClass = (entry: MarketingNavProps['current']) =>
    current === entry
      ? 'text-text underline decoration-primary decoration-2 underline-offset-8'
      : 'text-text-2 hover:text-text'
  return (
    <header className="sticky top-0 z-10 border-b border-line bg-surface/80 backdrop-blur">
      {/* grid-cols-[1fr_auto_1fr] keeps the centre links optically centred
          regardless of the brand / CTA widths. On phones the centre cell
          is empty (links hidden) so the brand and the "…" menu sit at the
          two edges. */}
      <div className="mx-auto grid h-14 max-w-5xl grid-cols-[1fr_auto_1fr] items-center px-6">
        <div className="justify-self-start">
          <Link to="/">
            <BrandLockup />
          </Link>
        </div>
        <nav
          aria-label={t('header.navAriaLabel')}
          className="hidden items-center gap-6 justify-self-center text-sm font-medium md:flex"
        >
          {/* A plain <a>, like Support: the static support pages render this header and are not
              inside the SPA, so a router <Link> would gain nothing there. */}
          <a
            href={VISION_HREF}
            aria-current={current === 'vision' ? 'page' : undefined}
            className={linkClass('vision')}
          >
            {t('header.vision')}
          </a>
          <a
            href={PRICING_HREF}
            aria-current={current === 'pricing' ? 'page' : undefined}
            className={linkClass('pricing')}
          >
            {t('header.pricing')}
          </a>
          <a
            href={ROADMAP_HREF}
            aria-current={current === 'roadmap' ? 'page' : undefined}
            className={linkClass('roadmap')}
          >
            {t('header.roadmap')}
          </a>
          <a href={DOCS_HREF} className="text-text-2 hover:text-text">
            {t('header.docs')}
          </a>
          {/* A plain <a>, never a router <Link>: the support pages are static files outside the
              SPA, whose catch-all route would send the path back to `/`. */}
          <a
            href={supportHref}
            aria-current={current === 'support' ? 'page' : undefined}
            className={linkClass('support')}
          >
            {t('header.support')}
          </a>
        </nav>
        {/* col-start-3: with the centre nav `display: none` on phones, auto-placement would
            otherwise put this cell in the centre column. */}
        <div className="col-start-3 flex items-center justify-self-end">
          <div className="hidden md:block">
            <LandingCta slot="header" />
          </div>
          <div className="md:hidden">
            <MarketingMobileMenu supportHref={supportHref} current={current} />
          </div>
        </div>
      </div>
    </header>
  )
}
