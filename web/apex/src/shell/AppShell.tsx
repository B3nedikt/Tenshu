// The chrome for the overview and every hosted app (docs/proposals/web-apex-home.md §3.2): one
// slim top bar and nothing else, so an app gets the full width below it. Control keeps its own
// shell with the side rail (`control/ControlShell.tsx`); both sit under `SessionLayout`.
//
// Left: the brand on the overview; inside an app, "← Tenshu" then the app's icon and name.
// ⚠ Never "← Home": Home is an app of its own (lib/overviewPath.ts).
// Right: app launcher, the gear into Control (§3.4), the device menu. In Customize mode the whole bar
// is the Customize bar instead.
//
// The overview (and Customize) sits on the wallpaper under a glass bar (ADR 0027); inside an app the
// page stays the plain surface, so an app's own UI is never drawn over a gradient.
import { Link, Outlet, useLocation } from 'react-router'
import { useTranslation } from 'react-i18next'
import { BrandLockup } from '../components/BrandLockup'
import { AppIcon } from '../apps/AppIcon'
import { appLabel, hostedAppAt } from '../apps/appLabel'
import { HOSTED_APPS } from '../apps/registry'
import { AppLauncher } from '../control/AppLauncher'
import { DeviceMenu } from '../control/DeviceMenu'
import { TryModeBanner } from '../control/TryModeBanner'
import { CONTROL_PATH, CUSTOMIZE_PATH, OVERVIEW_PATH } from '../lib/overviewPath'
import { Bell } from './Bell'
import { BackIcon, GearIcon } from './icons'
import { SyncPill } from './SyncPill'
import { useSessionDevice } from './sessionContext'

export function AppShell() {
  const { t } = useTranslation('control')
  const deviceName = useSessionDevice()
  const { pathname } = useLocation()
  const app = hostedAppAt(HOSTED_APPS, pathname)
  const onWall = app === null
  return (
    <div className={`isolate min-h-screen text-text ${onWall ? '' : 'bg-surface'}`}>
      {onWall && <div aria-hidden data-testid="wallpaper" className="fixed inset-0 -z-10 [background:var(--wall)]" />}
      {pathname.replace(/\/+$/, '') === CUSTOMIZE_PATH ? <CustomizeBar /> : (
      <header
        className={`sticky top-0 z-20 h-14 border-b backdrop-blur-xl ${
          onWall ? 'border-glass-line bg-glass backdrop-saturate-150' : 'border-line bg-surface/80'
        }`}
      >
        <div className="flex h-full items-center justify-between gap-3 px-4">
          {app === null ? (
            <Link to={OVERVIEW_PATH}>
              <BrandLockup />
            </Link>
          ) : (
            <div className="flex min-w-0 items-center gap-2">
              <Link
                to={OVERVIEW_PATH}
                title={t('shell.backToOverviewTitle')}
                className="flex items-center gap-1 rounded-m px-2 py-1.5 text-sm text-text-2 hover:bg-surface-2 hover:text-text focus:outline-none focus-visible:ring-2 focus-visible:ring-line-strong"
              >
                <BackIcon />
                <span>{t('shell.backToOverview')}</span>
              </Link>
              <span aria-hidden className="h-5 w-px bg-line" />
              <span className="flex min-w-0 items-center gap-2 pl-1">
                <AppIcon appId={app.id} label={appLabel(t, app)} size="sm" />
                <span className="truncate text-base font-semibold">{appLabel(t, app)}</span>
              </span>
            </div>
          )}
          <div className="flex items-center gap-1">
            {/* §3.4: the tray's sync line and its notifications, on the web. */}
            <SyncPill compact={app !== null} />
            <Bell />
            <AppLauncher />
            <Link
              to={CONTROL_PATH}
              aria-label={t('appLauncher.control')}
              title={t('appLauncher.control')}
              className="rounded-m p-2 text-text-2 hover:bg-well hover:text-text focus:outline-none focus-visible:ring-2 focus-visible:ring-line-strong"
            >
              <GearIcon size={20} />
            </Link>
            <DeviceMenu deviceName={deviceName} />
          </div>
        </div>
      </header>
      )}
      <TryModeBanner />
      <main>
        <Outlet />
      </main>
    </div>
  )
}

// Customize mode replaces the top bar (web-apex-home.md §3.5.1): one way out, so nothing else in the
// chrome competes while the layout is being edited. Escape also leaves (OverviewPage).
function CustomizeBar() {
  const { t } = useTranslation('control')
  return (
    <header className="sticky top-0 z-20 h-14 border-b border-glass-line bg-glass backdrop-blur-xl backdrop-saturate-150">
      <div className="flex h-full items-center justify-between gap-3 px-4">
        <Link
          to={OVERVIEW_PATH}
          replace
          className="rounded-pill bg-primary px-5 py-1.5 text-sm font-semibold text-on-primary hover:opacity-90 focus:outline-none focus-visible:ring-2 focus-visible:ring-line-strong"
        >
          {t('customize.done')}
        </Link>
        {/* Drag does nothing on touch, so the hint is for a fine, hovering pointer only. */}
        <span className="hidden truncate text-sm text-text-2 [@media(hover:hover)_and_(pointer:fine)]:block">
          {t('customize.hint')}
        </span>
        <span aria-hidden className="w-16" />
      </div>
    </header>
  )
}
