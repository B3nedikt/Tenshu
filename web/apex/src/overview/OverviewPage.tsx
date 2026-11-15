// The signed-in landing page at `/overview` (docs/proposals/web-apex-home.md §3.3).
//
// H1: the Needs-action band (today only the recovery reminder — the web has no device-join
// approval surface yet) and the app dock. H2: the dock lists the user's SHOWN apps and the
// "Your apps" dialog (at `/overview/apps`, `editingApps`) adds and hides them. H3: Customize mode
// (at `/overview/customize`, `customizing`) — the only place the layout changes. H4: the preview
// cards (`glance/`), one per shown app that declares one.
import { useEffect } from 'react'
import { Link, useNavigate } from 'react-router'
import { useTranslation } from 'react-i18next'
import { RecoveryBanner } from '../control/RecoveryBanner'
import { CUSTOMIZE_PATH, OVERVIEW_PATH } from '../lib/overviewPath'
import { AppDock } from './AppDock'
import { GlanceGrid } from './glance/GlanceGrid'
import { CustomizeDock } from './CustomizeDock'
import { useVisibleApps } from './useVisibleApps'
import { YourAppsDialog } from './YourAppsDialog'

export function OverviewPage({
  editingApps = false,
  customizing = false,
}: {
  readonly editingApps?: boolean
  readonly customizing?: boolean
}) {
  const { t } = useTranslation('control')
  const navigate = useNavigate()
  const { state } = useVisibleApps()

  // Escape leaves Customize mode — unless a dialog inside it is open: that Escape is the dialog's.
  useEffect(() => {
    if (!customizing) return
    function onKey(e: KeyboardEvent) {
      if (e.key !== 'Escape' || e.defaultPrevented) return
      if (document.querySelector('[role="dialog"]') !== null) return
      navigate(OVERVIEW_PATH, { replace: true })
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [customizing, navigate])

  return (
    <div className="mx-auto flex w-full max-w-6xl flex-col gap-8 px-6 py-8">
      <h1 className="sr-only">{t('overview.title')}</h1>
      {!customizing && (
        <section aria-label={t('overview.needsActionAriaLabel')} className="empty:hidden [&>*]:mb-0!">
          <RecoveryBanner />
        </section>
      )}
      <section aria-labelledby="overview-apps" className="flex flex-col gap-4">
        <h2 id="overview-apps" className="text-xl font-semibold tracking-tight text-text">
          {t('overview.appsHeading')}
        </h2>
        {customizing ? <CustomizeDock /> : <AppDock />}
      </section>
      {/* H4: cards follow the dock's order, so Customize edits them through the dock; they are not
          drawn while customizing (nothing on the page navigates mid-edit). */}
      {!customizing && <GlanceGrid />}
      {/* Customizing needs a list to change: hidden while it is loading or unreadable. */}
      {!customizing && state.kind === 'ready' && (
        <div className="flex justify-center pt-4">
          <Link
            to={CUSTOMIZE_PATH}
            className="flex h-11 items-center gap-2.5 rounded-pill border border-glass-line bg-glass px-5 text-sm font-medium text-text shadow-glass backdrop-blur-xl hover:bg-well focus:outline-none focus-visible:ring-2 focus-visible:ring-line-strong"
          >
            <svg aria-hidden viewBox="0 0 20 20" width={18} height={18} fill="none" stroke="currentColor" strokeWidth={1.6}>
              <path d="M3 6h14M3 14h14" />
              <circle cx={7} cy={6} r={2} className="fill-surface" />
              <circle cx={13} cy={14} r={2} className="fill-surface" />
            </svg>
            {t('overview.customize')}
          </Link>
        </div>
      )}
      {editingApps && <YourAppsDialog onClose={() => navigate(OVERVIEW_PATH, { replace: true })} />}
    </div>
  )
}
