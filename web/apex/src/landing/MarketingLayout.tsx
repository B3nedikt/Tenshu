// The site's one top and bottom bar around a public page that is not the landing page itself:
// the signup-closed notice at /onboarding, the share viewer at /s/:id, the invite page at /i,
// the Vision page at /vision.
// The same MarketingHeader and MarketingFooter as the landing page and the static support pages
// (theme switch, language picker), so every public page wears the same chrome. In-progress flows
// that hold key material (onboarding, unlock, recovery) and the signed-in app (ControlShell) keep
// their own — see store-presence-and-support-pages.md §4.2.
//
// ⚠ No session probe: these pages must not touch the realm on their own account (the share viewer
// is anonymous by design), so the header's CTA is the logged-out one, like a support page's.
// The page renders its own <main>; this wraps it in a plain block.
//
// These pages have no URL per language, so the language picker switches in place (`inPlace`)
// rather than navigating — never away from a share being decrypted or an invite being redeemed.
import type { ReactNode } from 'react'
import { useLocation } from 'react-router'
import { useTranslation } from 'react-i18next'
import { LOGGED_OUT_CTA, LandingCtaContext } from './landingCtaContext'
import { MarketingFooter } from './MarketingFooter'
import { MarketingHeader } from './MarketingHeader'
import { SITE_LANGUAGES } from './languages'
import { MARKETING_PAGE_CLASSES, type MarketingNavProps } from './marketingNav'

export function MarketingLayout({
  children,
  current,
}: {
  readonly children: ReactNode
  /** The header entry marked as the current page, if this page is one of them. */
  readonly current?: MarketingNavProps['current']
}) {
  const { i18n } = useTranslation()
  const { pathname, search } = useLocation()
  const currentLang = i18n.resolvedLanguage ?? i18n.language
  // The page's own address without its fragment: a share's key lives there and has no business
  // in a link's href.
  const languages = SITE_LANGUAGES.map(({ lang, name }) => ({
    lang,
    name,
    href: pathname + search,
    current: lang === currentLang,
    inPlace: true,
  }))
  return (
    <LandingCtaContext.Provider value={LOGGED_OUT_CTA}>
      <div className={`${MARKETING_PAGE_CLASSES} flex min-h-svh flex-col`}>
        <MarketingHeader current={current} />
        <div className="flex-1">{children}</div>
        <MarketingFooter languages={languages} />
      </div>
    </LandingCtaContext.Provider>
  )
}
