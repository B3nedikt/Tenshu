// The signed-in landing page at `/overview` (docs/proposals/web-apex-home.md §3.3).
//
// H1: the Needs-action band (today only the recovery reminder — the web has no device-join
// approval surface yet), then the widgets (`WidgetBoard`, §4.12): "Your apps" — the dock of the
// user's SHOWN apps, whose "Your apps" dialog (at `/overview/apps`, `editingApps`) adds, hides and
// orders them — and the preview cards (`glance/`). Edit mode (at `/overview/customize`,
// `customizing`) is the only place the layout changes: widgets wiggle, − removes one, Add widget
// brings one back, Done leaves.
import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router'
import { useTranslation } from 'react-i18next'
import { AppearanceControls } from '../appearance/AppearanceControls'
import { RecoveryBanner } from '../control/RecoveryBanner'
import { CUSTOMIZE_PATH, OVERVIEW_PATH } from '../lib/overviewPath'
import { AddWidgetDialog } from './AddWidgetDialog'
import { useWidgets } from './useWidgets'
import { WidgetBoard } from './WidgetBoard'
import { ADD_WIDGET_ID } from './widgets'
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
  const { stored: widgetsStored, pending } = useWidgets()
  const [dialog, setDialog] = useState<'add' | 'apps' | null>(null)

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
      {customizing && (
        <EditBar
          editable={widgetsStored !== null}
          pending={pending}
          onAdd={() => setDialog('add')}
          onApps={() => setDialog('apps')}
        />
      )}
      {/* Keyed: entering or leaving edit mode starts the board afresh (no stale Undo toast). */}
      <WidgetBoard key={customizing ? 'edit' : 'view'} editing={customizing} />
      {/* Always offered: mode and language need no stored list, and edit mode says itself when the
          widgets cannot be changed. */}
      {!customizing && (
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
      {customizing && dialog === 'apps' && <YourAppsDialog onClose={() => setDialog(null)} />}
      {customizing && dialog === 'add' && <AddWidgetDialog onClose={() => setDialog(null)} />}
    </div>
  )
}

const BAR_BUTTON =
  'flex h-10 items-center gap-2 rounded-pill border border-glass-line bg-glass px-4 text-sm font-medium text-text shadow-glass backdrop-blur-xl hover:bg-well focus:outline-none focus-visible:ring-2 focus-visible:ring-line-strong aria-disabled:opacity-50'

/** Edit mode's toolbar and the appearance controls (§4.13); Done is the shell's top bar. */
function EditBar({
  editable,
  pending,
  onAdd,
  onApps,
}: {
  readonly editable: boolean
  readonly pending: boolean
  readonly onAdd: () => void
  readonly onApps: () => void
}) {
  const { t } = useTranslation('control')
  return (
    <>
      <div role="toolbar" aria-label={t('customize.toolbarAriaLabel')} className="flex flex-wrap items-center gap-2">
        {editable && (
          <button
            type="button"
            id={ADD_WIDGET_ID}
            aria-disabled={pending || undefined}
            onClick={() => {
              if (!pending) onAdd()
            }}
            className={BAR_BUTTON}
          >
            <svg aria-hidden viewBox="0 0 20 20" width={16} height={16} fill="none" stroke="currentColor" strokeWidth={1.8}>
              <path d="M10 4v12M4 10h12" />
            </svg>
            {t('customize.addWidget')}
          </button>
        )}
        <button type="button" onClick={onApps} className={BAR_BUTTON}>
          {t('overview.appsHeading')}
        </button>
      </div>
      <section aria-label={t('appearance.heading')} className="-mt-4 flex flex-col gap-3">
        <AppearanceControls />
        {/* The hint and Done are in the shell's top bar (AppShell's CustomizeBar). */}
        {!editable && <p className="text-sm text-text-2">{t('customize.unavailable')}</p>}
      </section>
    </>
  )
}
