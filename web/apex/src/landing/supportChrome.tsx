// The landing page's header and footer, rendered to static HTML for the support pages
// (support-pages/generate.ts, docs/proposals/store-presence-and-support-pages.md §4).
//
// The same components the SPA mounts — MarketingHeader, MarketingFooter, BrandLockup — so a
// change to either surface's chrome is a change to both. Rendered once per page at build time
// with `renderToStaticMarkup` and never hydrated: every link is a plain <a>, the phone menu is a
// <details>, and the theme switch is the script-wired StaticThemeSwitch.
//
// ⚠ Loaded through Vite's module runner (support-pages/loadChrome.ts), not plain Node: it is TSX
// and reads `import.meta.env` (lib/signupGate.ts). It must not import the SDK at runtime — the
// support pages build where the Gradle-built bundle does not exist (`pnpm -F apex build:support`).
import type { ReactNode } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { StaticRouter } from 'react-router'
import { I18nextProvider } from 'react-i18next'
import i18next, { type i18n } from 'i18next'
import ICU from 'i18next-icu'
import enLanding from '../../locales/en/landing.json'
import deLanding from '../../locales/de/landing.json'
import frLanding from '../../locales/fr/landing.json'
import { AppCarousel } from './carousel/AppCarousel'
import type { CarouselEntry, CarouselLabels } from './carousel/carouselModel'
import { LOGGED_OUT_CTA, LandingCtaContext } from './landingCtaContext'
import { LanguagePicker, type LanguageLink } from './LanguagePicker'
import { MarketingFooter } from './MarketingFooter'
import { MarketingHeader } from './MarketingHeader'
import { MARKETING_PAGE_CLASSES } from './marketingNav'

const LANDING: Record<string, object> = { en: enLanding, de: deLanding, fr: frLanding }

export interface SupportChrome {
  /** The landing page's root classes, for the support page's <body>. */
  readonly bodyClass: string
  readonly header: string
  readonly footer: string
  /**
   * The language picker under the page title — the footer's own component, opening downwards and
   * lined up with the column's trailing edge. Empty when [SupportChromeOptions.languages] is.
   */
  readonly languagePicker: string
  /**
   * The app carousel (carousel/AppCarousel.tsx) as static markup — the landing page's Apps section
   * renders the same component live. The support hub's lists of apps; its script enhances them.
   */
  readonly carousel: (entries: readonly CarouselEntry[], listLabel: string, labels: CarouselLabels, footnote?: string) => string
}

export interface SupportChromeOptions {
  /** The page's language; the chrome's copy comes from `locales/<lang>/landing.json`. */
  readonly lang: string
  /** The page's own URL path, for the router `Link`s resolve against. */
  readonly url: string
  /** The header's "Support" entry — the support hub in the page's language (`/apps/support/<lang>`). */
  readonly supportHref: string
  /**
   * The language pickers' links (the footer's and the one under the title): this page in each
   * language it exists in (its siblings, never the landing page). Empty — the redirect pages, which
   * have no language of their own — and there is no picker.
   */
  readonly languages: readonly LanguageLink[]
  /** The header entry marked current: "Support" on a support page (the default), "Roadmap" on the roadmap, "Pricing" on the pricing page, none elsewhere. */
  readonly current?: 'support' | 'roadmap' | 'pricing' | null
}

export function renderSupportChrome({ lang, url, supportHref, languages, current = 'support' }: SupportChromeOptions): SupportChrome {
  const i18n = instanceFor(lang)
  // Each part is its own render, so each gets its own `useId` prefix: the header, the picker and
  // the footer land in one document and their ids must not collide.
  const wrap = (node: ReactNode, identifierPrefix: string) =>
    renderToStaticMarkup(
      <I18nextProvider i18n={i18n}>
        <StaticRouter location={url}>
          <LandingCtaContext.Provider value={LOGGED_OUT_CTA}>{node}</LandingCtaContext.Provider>
        </StaticRouter>
      </I18nextProvider>,
      { identifierPrefix },
    )
  return {
    bodyClass: MARKETING_PAGE_CLASSES,
    header: wrap(<MarketingHeader supportHref={supportHref} current={current ?? undefined} />, 'h-'),
    footer: wrap(<MarketingFooter staticMarkup languages={languages} />, 'f-'),
    languagePicker: wrap(<LanguagePicker links={languages} opens="down" align="end" />, 'l-'),
    carousel: (entries, listLabel, labels, footnote) =>
      renderToStaticMarkup(<AppCarousel mode="static" entries={entries} listLabel={listLabel} labels={labels} footnote={footnote} />),
  }
}

const instances = new Map<string, i18n>()

function instanceFor(lang: string): i18n {
  const cached = instances.get(lang)
  if (cached) return cached
  const instance = i18next.createInstance()
  // Synchronous: the resources are bundled, so `t` works the moment init returns.
  void instance.use(ICU).init({
    lng: lang,
    fallbackLng: 'en',
    resources: Object.fromEntries(Object.entries(LANDING).map(([l, landing]) => [l, { landing }])),
    ns: ['landing'],
    defaultNS: 'landing',
    initAsync: false,
    interpolation: { escapeValue: false },
    returnNull: false,
  })
  instances.set(lang, instance)
  return instance
}
