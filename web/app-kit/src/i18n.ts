// react-i18next bootstrap for an app — the apex's setup (`web/apex/src/i18n.ts`, ADR 0016): ICU
// MessageFormat, the browser's language by default, and bundled JSON catalogues so init is
// synchronous and tests need no ready-wait. One namespace per app, named after it.
//
// ⚠ Import it from the app's `i18n.ts` only, and only in an app that localises: it pulls i18next
// into the bundle (`web/drive` has no i18n yet and does not import this).
import i18n, { type i18n as I18n } from 'i18next'
import { initReactI18next } from 'react-i18next'
import LanguageDetector from 'i18next-browser-languagedetector'
import ICU from 'i18next-icu'

export const appLanguages = ['en', 'de', 'fr'] as const
export type AppLanguage = (typeof appLanguages)[number]

/**
 * Initialises the default i18next instance with one catalogue per language under `namespace`.
 * Every language in `appLanguages` must be given; `catalogueKeys` (`@tenshu/app-kit/testing`) is
 * the parity test that keeps them in step.
 */
export function initAppI18n(
  namespace: string,
  catalogues: Readonly<Record<AppLanguage, object>>,
): I18n {
  void i18n
    .use(ICU)
    .use(LanguageDetector)
    .use(initReactI18next)
    .init({
      resources: Object.fromEntries(
        appLanguages.map((lang) => [lang, { [namespace]: catalogues[lang] }]),
      ),
      fallbackLng: 'en',
      supportedLngs: [...appLanguages],
      ns: [namespace],
      defaultNS: namespace,
      interpolation: { escapeValue: false },
      detection: { order: ['navigator'], caches: [] },
      returnNull: false,
    })
  return i18n
}
