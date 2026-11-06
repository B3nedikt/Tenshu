// `/en`, `/de`, `/fr`: the landing page pinned to one language by its URL — what the footer's
// language picker links to, so a shared link shows what the sender saw. `/` stays the detected
// language (stored pick, then the browser's list — src/i18n.ts).
//
// The language is switched for the whole SPA, not just this page: a visitor who picked German
// here and goes on to "Get started" continues in German.
import { useLayoutEffect } from 'react'
import { useTranslation } from 'react-i18next'
import { LandingPage } from './LandingPage'

export function LocalizedLanding({ lang }: { readonly lang: string }) {
  const { i18n } = useTranslation()
  // Layout effect, so the page never paints in the previous language: the catalogues are bundled,
  // so the change completes synchronously and react-i18next re-renders before the browser draws.
  useLayoutEffect(() => {
    if (i18n.resolvedLanguage !== lang) void i18n.changeLanguage(lang)
  }, [i18n, lang])
  return <LandingPage />
}
