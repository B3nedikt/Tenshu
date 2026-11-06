// react-i18next bootstrap for apex. Per ADR 0016:
//   - per-package i18next instance (no shared JS provider across web/ packages)
//   - ICU MessageFormat from day one (i18next-icu)
//   - OS / browser locale by default (LanguageDetector); in-app override is a
//     future :preference-service DeviceSetting
//   - keys are dot-separated, scoped by namespace then function (agent.md §19)
//
// Languages: English, German (every namespace), and French for the public marketing surface
// (`landing` + `common`) plus the two Control screens built with all three from the start
// (`settings`, `dataFiles` — web-app-suite.md Wave 4, key parity pinned by `i18nParity.test.ts`);
// a French visitor elsewhere sees English, per key, through fallbackLng. The landing page's footer has a language picker (src/landing/LanguagePicker.tsx):
// its pick is stored under LANGUAGE_STORAGE_KEY and read here first, ahead of the browser's list
// — the same per-viewer convenience as the theme — and `/en`, `/de`, `/fr` pin a language by URL
// (App.tsx). ⚠ The French and German marketing copy is machine-translated pending native review
// (src/landing/languages.ts `reviewed`).
//
// Catalogues are bundled JSON imports — small enough that HTTP loading + a
// Suspense fallback would add ceremony for no benefit. Synchronous init means
// tests can `import './i18n'` once and `screen.getByText('Cancel')` keeps
// working without awaiting i18next-ready.
import i18n from 'i18next'
import { initReactI18next } from 'react-i18next'
import LanguageDetector from 'i18next-browser-languagedetector'
import ICU from 'i18next-icu'

import enCommon from '../locales/en/common.json'
import enLanding from '../locales/en/landing.json'
import enOnboarding from '../locales/en/onboarding.json'
import enControl from '../locales/en/control.json'
import enFiles from '../locales/en/files.json'
import enSettings from '../locales/en/settings.json'
import enDataFiles from '../locales/en/dataFiles.json'
import deCommon from '../locales/de/common.json'
import deLanding from '../locales/de/landing.json'
import deOnboarding from '../locales/de/onboarding.json'
import deControl from '../locales/de/control.json'
import deFiles from '../locales/de/files.json'
import deSettings from '../locales/de/settings.json'
import deDataFiles from '../locales/de/dataFiles.json'
import frCommon from '../locales/fr/common.json'
import frLanding from '../locales/fr/landing.json'
import frSettings from '../locales/fr/settings.json'
import frDataFiles from '../locales/fr/dataFiles.json'
import { readLanguageChoice } from './landing/languages'

export const defaultNamespace = 'common'

export const supportedLanguages = ['en', 'de', 'fr'] as const
export type SupportedLanguage = (typeof supportedLanguages)[number]

// The footer's stored pick (src/landing/languages.ts), read afresh on every detection and inside
// a try/catch. Not the detector's built-in `localStorage` lookup: that probes storage once and
// caches the answer for the life of the page.
const detector = new LanguageDetector()
detector.addDetector({ name: 'siteLanguageChoice', lookup: () => readLanguageChoice() })

void i18n
  .use(ICU)
  .use(detector)
  .use(initReactI18next)
  .init({
    resources: {
      en: {
        common: enCommon,
        landing: enLanding,
        onboarding: enOnboarding,
        control: enControl,
        files: enFiles,
        settings: enSettings,
        dataFiles: enDataFiles,
      },
      de: {
        common: deCommon,
        landing: deLanding,
        onboarding: deOnboarding,
        control: deControl,
        files: deFiles,
        settings: deSettings,
        dataFiles: deDataFiles,
      },
      fr: {
        common: frCommon,
        landing: frLanding,
        settings: frSettings,
        dataFiles: frDataFiles,
      },
    },
    fallbackLng: 'en',
    supportedLngs: supportedLanguages as unknown as string[],
    ns: ['common', 'landing', 'onboarding', 'control', 'files', 'settings', 'dataFiles'],
    defaultNS: defaultNamespace,
    interpolation: {
      // React already escapes
      escapeValue: false,
    },
    detection: {
      // The stored footer pick first; blocked or empty storage falls through to the browser's
      // list. caches: [] — only an explicit pick is ever written (storeLanguageChoice), never a
      // detected language.
      order: ['siteLanguageChoice', 'navigator', 'htmlTag'],
      caches: [],
    },
    returnNull: false,
  })

// <html lang> follows the language actually shown, for screen readers and hyphenation.
const syncHtmlLang = (lng: string | undefined) => {
  if (typeof document !== 'undefined' && lng) document.documentElement.lang = lng
}
i18n.on('languageChanged', syncHtmlLang)
syncHtmlLang(i18n.resolvedLanguage)

export default i18n
