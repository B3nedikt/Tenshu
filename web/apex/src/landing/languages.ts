// The languages the marketing site — the landing page and the static support pages — is offered
// in, and where each one's landing page lives. The language picker (LanguagePicker.tsx)
// lists these; `/` keeps detecting (src/i18n.ts), `/<lang>` pins one (App.tsx).
import { LANGUAGE_CHOICE_ATTRIBUTE, LANGUAGE_STORAGE_KEY } from '../../support-pages/languageSwitch.ts'
import { imprintUrl } from '../../support-pages/imprint.ts'
import { privacyUrl } from '../../support-pages/privacy.ts'
import { termsUrl } from '../../support-pages/terms.ts'

export { LANGUAGE_CHOICE_ATTRIBUTE, LANGUAGE_STORAGE_KEY }

export interface SiteLanguage {
  readonly lang: string
  /** The language's own name for itself, so a reader finds theirs whatever the page is in. */
  readonly name: string
  /**
   * ⚠ false = machine-translated, not yet read by a native speaker. German and French (the
   * landing page's locales/fr/landing.json) both wait for that review; flip it when it is done. ⚖️ The support pages' privacy sections are under counsel review in English —
   * a change counsel makes there must reach every translation too. Not shown to visitors.
   */
  readonly reviewed: boolean
}

export const SITE_LANGUAGES: readonly SiteLanguage[] = [
  { lang: 'en', name: 'English', reviewed: true },
  { lang: 'de', name: 'Deutsch', reviewed: false },
  { lang: 'fr', name: 'Français', reviewed: false },
]

/**
 * The imprint in [lang] (`/legal/imprint/de`): a static page the support-page build generates
 * (support-pages/imprint.ts), linked from the footer on every page. A language the site does not
 * offer gets the English one.
 */
export function imprintPath(lang: string): string {
  return imprintUrl(SITE_LANGUAGES.some((l) => l.lang === lang) ? lang : 'en')
}

/**
 * The privacy policy in [lang] (`/legal/privacy/de`): static like the imprint
 * (support-pages/privacy.ts) and linked beside it from every footer. ⚠ The per-language URLs are
 * linked from shipped apps: never move them. The stores carry the language-neutral `/legal/privacy`
 * (store/shared/en-US/privacy_url.txt), which Caddy redirects by Accept-Language.
 */
export function privacyPath(lang: string): string {
  return privacyUrl(SITE_LANGUAGES.some((l) => l.lang === lang) ? lang : 'en')
}

/**
 * The terms of use in [lang] (`/legal/terms/de`): static like the privacy policy
 * (support-pages/terms.ts) and linked beside it from every footer and from the sign-up screens.
 * ⚠ The per-language URLs are linked from shipped apps: never move them.
 */
export function termsPath(lang: string): string {
  return termsUrl(SITE_LANGUAGES.some((l) => l.lang === lang) ? lang : 'en')
}

/** The landing page in [lang]: `/en`, `/de`, `/fr`. `/` itself stays the detected language. */
export function landingPath(lang: string): string {
  return `/${lang}`
}

/** The visitor's stored pick, or undefined when there is none or storage is unreadable. */
export function readLanguageChoice(): string | undefined {
  try {
    return window.localStorage.getItem(LANGUAGE_STORAGE_KEY) ?? undefined
  } catch {
    return undefined
  }
}

/** Records the visitor's pick; blocked storage only means it is not remembered. */
export function storeLanguageChoice(lang: string): void {
  try {
    window.localStorage.setItem(LANGUAGE_STORAGE_KEY, lang)
  } catch {
    // Private mode, a strict cookie policy, a sandboxed frame: the link still works.
  }
}
