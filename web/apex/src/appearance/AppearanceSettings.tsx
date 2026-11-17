// Settings → Appearance (docs/proposals/web-apex-home.md §4.13): the overview edit bar's controls,
// reachable without entering edit mode.
import { useTranslation } from 'react-i18next'
import { AppearanceControls } from './AppearanceControls'

export function AppearanceSettings() {
  const { t } = useTranslation('control')
  return (
    <section aria-labelledby="settings-appearance" className="mt-8">
      <h2 id="settings-appearance" className="text-sm font-medium uppercase tracking-wide text-text-2">
        {t('appearance.heading')}
      </h2>
      <div className="mt-3 rounded-md border border-line p-4">
        <AppearanceControls />
        <p className="mt-3 text-xs text-text-2">{t('appearance.thisBrowser')}</p>
      </div>
    </section>
  )
}
