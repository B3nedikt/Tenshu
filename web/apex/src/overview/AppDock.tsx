// The overview's row of apps (docs/proposals/web-apex-home.md §3.3, item 2) — the most prominent
// element on the page. Each tile opens the app at its registry `launchPath`; the last tile opens the
// "Your apps" dialog.
import { Link } from 'react-router'
import { useTranslation } from 'react-i18next'
import { AppIcon } from '../apps/AppIcon'
import { appDescription, appLabel } from '../apps/appLabel'
import { YOUR_APPS_PATH } from '../lib/overviewPath'
import { useVisibleApps } from './useVisibleApps'

// Icons straight on the wallpaper, home-screen style (ADR 0027): four across on phones, a wrapping
// row from `sm`. Hover lifts a glass tile behind the icon.
const LIST = 'grid grid-cols-4 gap-x-1 gap-y-3 sm:flex sm:flex-wrap sm:gap-x-1.5 sm:gap-y-2'
const TILE =
  'flex flex-col items-center gap-2 rounded-[18px] px-0.5 py-1.5 text-text hover:bg-glass focus:outline-none focus-visible:ring-2 focus-visible:ring-line-strong sm:w-24 sm:px-1 sm:py-2.5'

export function AppDock() {
  const { t } = useTranslation('control')
  const { apps, state } = useVisibleApps()
  if (state.kind === 'loading') {
    // Holds the row's height so the page does not jump when the list arrives.
    return <div aria-busy="true" aria-label={t('shell.loading')} className="h-24" />
  }
  return (
    <div className="flex flex-col gap-3">
      {state.kind === 'unavailable' && (
        <p role="status" className="text-sm text-text-2">
          {t('overview.shownAppsUnavailable')}
        </p>
      )}
      <ul className={LIST}>
        {apps.map(app => {
          const label = appLabel(t, app)
          return (
            <li key={app.id}>
              <Link to={app.manifest.launchPath} title={appDescription(t, app)} className={TILE}>
                <AppIcon appId={app.id} label={label} size="lg" />
                <span className="max-w-full truncate text-xs font-medium sm:text-[13px]">{label}</span>
              </Link>
            </li>
          )
        })}
        {/* Editing needs a list to edit: with the store unreachable there is nothing to save to. */}
        {state.kind === 'ready' && (
          <li>
            <Link to={YOUR_APPS_PATH} className={`${TILE} text-text-2`}>
              <span aria-hidden className="grid h-16 w-16 place-items-center rounded-[16px] border-[1.5px] border-dashed border-text-3">
                <svg viewBox="0 0 24 24" width={28} height={28} fill="none" stroke="currentColor" strokeWidth={1.7}>
                  <path d="M12 5v14M5 12h14" />
                </svg>
              </span>
              <span className="max-w-full truncate text-xs font-medium sm:text-[13px]">{t('overview.addApp')}</span>
            </Link>
          </li>
        )}
      </ul>
    </div>
  )
}
