// Marketing-page footer per web-apex-landing.md §5. Copyright +
// links to whitepaper / threat-model / repo / contact / imprint / privacy policy. Footer copy
// content beyond the basics is an open item (§9) — kept tight here. The
// Light / Dark / Auto switch sits here, and beside it the language picker.
//
// ⚠ Also the static support pages' footer: support-pages/generate.ts renders this component to
// HTML at build time (src/landing/supportChrome.tsx), so a change here changes both surfaces.
// `staticMarkup` swaps in the switch those never-hydrated pages wire with an inline script.
// `languages` is what the language picker links to: absent, the landing page in each language
// (`/de`, …); a support page passes its own siblings (`/apps/<app>/support/<platform>/de`), so
// the picker never sends a reader from a help page back to the marketing page.
import { useTranslation } from 'react-i18next'
import { StaticThemeSwitch, ThemeSwitch } from '../theme/ThemeSwitch'
import { licensesUrl } from '../../support-pages/licenses.ts'
import { LanguagePicker, type LanguageLink } from './LanguagePicker'
import { SITE_LANGUAGES, imprintPath, landingPath, privacyPath, termsPath } from './languages'
import { ACCOUNT_DELETION_HREF } from './marketingNav'

export function MarketingFooter({
  staticMarkup = false,
  languages,
}: {
  readonly staticMarkup?: boolean
  readonly languages?: readonly LanguageLink[]
}) {
  const { t, i18n } = useTranslation('landing')
  const year = new Date().getFullYear()
  const current = i18n.resolvedLanguage ?? i18n.language
  const links =
    languages ??
    SITE_LANGUAGES.map(({ lang, name }) => ({ lang, name, href: landingPath(lang), current: lang === current }))
  return (
    <footer className="border-t border-line bg-surface-2">
      <div className="mx-auto flex max-w-5xl flex-col gap-3 px-6 py-8 text-sm text-text-2 sm:flex-row sm:flex-wrap sm:items-center sm:justify-between">
        <div>{t('footer.copyright', { year })}</div>
        <div className="flex flex-wrap items-center gap-x-6 gap-y-3">
          {staticMarkup ? <StaticThemeSwitch /> : <ThemeSwitch />}
          {/* At the foot of the page: the list opens upwards. */}
          <LanguagePicker links={links} opens="up" />
        </div>
        <nav className="flex flex-wrap items-center gap-x-4 gap-y-1">
          <FooterLink href="https://github.com/B3nedikt/Tenshu/blob/master/docs/whitepaper.adoc">
            {t('footer.link.whitepaper')}
          </FooterLink>
          <FooterLink href="https://github.com/B3nedikt/Tenshu/blob/master/docs/sync/threat-model.md">
            {t('footer.link.threatModel')}
          </FooterLink>
          <FooterLink href="https://github.com/B3nedikt/Tenshu">
            {t('footer.link.github')}
          </FooterLink>
          {/* A plain <a>: the third-party licences are a static page (support-pages/licenses.ts). */}
          <FooterLink href={licensesUrl(current)}>{t('footer.link.licenses')}</FooterLink>
          <FooterLink href="mailto:support@tenshu.app">{t('footer.link.contact')}</FooterLink>
          {/* Plain <a>s: the imprint, the privacy policy and the terms are static pages, outside the SPA's routes. */}
          <FooterLink href={imprintPath(current)}>{t('footer.link.imprint')}</FooterLink>
          <FooterLink href={privacyPath(current)}>{t('footer.link.privacy')}</FooterLink>
          <FooterLink href={termsPath(current)}>{t('footer.link.terms')}</FooterLink>
          {/* Google Play requires this page to be findable without the app. */}
          <FooterLink href={ACCOUNT_DELETION_HREF}>{t('footer.link.deleteAccount')}</FooterLink>
        </nav>
      </div>
    </footer>
  )
}

function FooterLink({ href, children }: { readonly href: string; readonly children: string }) {
  return (
    <a
      href={href}
      className="hover:text-text hover:underline underline-offset-4"
    >
      {children}
    </a>
  )
}
