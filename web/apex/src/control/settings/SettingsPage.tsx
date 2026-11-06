// Control → Settings — the index (web-app-suite.md §2 Control row, Wave 4). Mirrors native
// Control's `SettingsScreen` entry for entry, but lists ONLY what the web apex already has a page
// for: a dead row is worse than no row, so an entry with no route here is omitted, never shown
// disabled. What is left out, and why, is `OMITTED_ON_WEB` in `settingsEntries.ts` — keep that
// list honest when a page lands.
//
// Order follows native: sync and devices first, then background ingest, then recovery, and the
// legal entries last. ⚠ Native puts "Forget this device" directly AFTER recovery so the entry
// that saves the phrase is reached first; the web has no such row (sign-out lives in the chrome's
// device menu), so there is nothing to order against — but a future one goes after recovery too.
import { Link } from 'react-router'
import { useTranslation } from 'react-i18next'
import { privacyPolicyUrl, termsOfUseUrl } from '@tenshu/web-tokens/legal'
import { SETTINGS_ENTRIES } from './settingsEntries'

export function SettingsPage() {
  const { t, i18n } = useTranslation('settings')
  return (
    <div>
      <h1 className="text-2xl font-medium">{t('title')}</h1>
      <p className="mt-1 text-sm text-text-2">{t('subtitle')}</p>

      <ul className="mt-6 divide-y divide-line rounded-md border border-line">
        {SETTINGS_ENTRIES.map(entry => (
          <li key={entry.id}>
            <Link
              to={entry.to}
              className="flex items-center gap-4 px-4 py-4 transition-colors hover:bg-surface-3"
            >
              <span aria-hidden className="w-6 text-center text-xl text-text-3">
                {entry.glyph}
              </span>
              <span className="min-w-0 flex-1">
                <span className="block text-sm font-medium text-text">
                  {t(`entries.${entry.id}.headline`)}
                </span>
                <span className="mt-0.5 block text-xs text-text-2">
                  {t(`entries.${entry.id}.support`)}
                </span>
              </span>
              <span aria-hidden className="text-text-3">
                ›
              </span>
            </Link>
          </li>
        ))}
      </ul>

      {/* The legal entries, last, as on native: the in-app privacy policy (App Store 5.1.1(i),
          Play's User Data policy want it reachable from every Tenshu app's settings), then the
          terms of use. Plain <a>s: both are static pages outside the SPA's routes. */}
      <h2 className="mt-8 text-sm font-medium uppercase tracking-wide text-text-2">{t('legal')}</h2>
      <p className="mt-3 text-sm">
        <a
          href={privacyPolicyUrl(i18n.language)}
          target="_blank"
          rel="noreferrer"
          className="font-medium text-text underline-offset-4 hover:underline"
        >
          {t('privacyPolicy')}
        </a>
      </p>
      <p className="mt-2 text-sm">
        <a
          href={termsOfUseUrl(i18n.language)}
          target="_blank"
          rel="noreferrer"
          className="font-medium text-text underline-offset-4 hover:underline"
        >
          {t('termsOfUse')}
        </a>
      </p>
    </div>
  )
}
