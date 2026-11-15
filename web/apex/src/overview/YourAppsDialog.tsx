// "Your apps" — add and hide apps on the overview (docs/proposals/web-apex-home.md §3.5, H2), and
// under *More apps* where the user already uses each one (H6: "You use it on Pixel 9").
//
// ⚠⚠ Hiding only changes what the overview and the launcher list. The dialog says so, and nothing
// here touches an app's data, registration or permissions.
// ⚠⚠ The "You use it on" line comes only from a ready registrations read naming a device
// (`usedOn.ts`) — loading or a failed read shows no line, never a guessed one. It never reorders the
// list: rows jumping when the read lands would move a row out from under the pointer.
// ⚠ Each change writes at once; a failed write is reported and nothing on screen moves (the live
// list only changes when the store emits).
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import type { TFunction } from 'i18next'
import { Dialog } from '@tenshu/web-tokens/ui'
import { AppIcon } from '../apps/AppIcon'
import { appDescription, appLabel } from '../apps/appLabel'
import { HOSTED_APPS, type HostedApp } from '../apps/registry'
import { useAppRegistrations } from '../control/apps/appRegistryContext'
import { ALWAYS_SHOWN, hiddenApps, shownApps, usesDrive, withAdded, withRemoved } from './shownApps'
import { useShownAppsEditor } from './useShownAppsEditor'
import { usedOnDevices } from './usedOn'

export function YourAppsDialog({ onClose }: { readonly onClose: () => void }) {
  const { t } = useTranslation('control')
  const { state, stored: editable, pending, apply } = useShownAppsEditor()
  const registrations = useAppRegistrations()
  const [error, setError] = useState<string | null>(null)

  async function change(next: string[]) {
    // Refused while any write is in flight (aria-disabled below, not `disabled`, so focus stays).
    if (pending) return
    setError(null)
    if (!(await apply(next))) setError(t('yourApps.writeFailed'))
  }

  if (state.kind !== 'ready' || editable === null) {
    return (
      <Dialog title={t('yourApps.title')} onClose={onClose}>
        <p className="text-sm text-text-2">
          {state.kind === 'loading' ? t('shell.loading') : t('yourApps.unavailable')}
        </p>
        <DoneButton onClose={onClose} />
      </Dialog>
    )
  }

  const stored = editable
  const shown = shownApps(stored, HOSTED_APPS)
  const more = hiddenApps(stored, HOSTED_APPS)
  return (
    <Dialog title={t('yourApps.title')} onClose={onClose}>
      <p className="text-sm text-text-2">{t('yourApps.intro')}</p>
      {/* The lists scroll; title, intro and Done stay put. Nine apps outgrow a laptop screen, and
          the shared Dialog does not cap its own height. */}
      <div className="-mx-1 mt-4 max-h-[calc(100dvh-14rem)] overflow-y-auto px-1">
      <section aria-labelledby="your-apps-shown">
        <h3 id="your-apps-shown" className="text-xs font-semibold uppercase tracking-wide text-text-3">
          {t('yourApps.shownHeading')}
        </h3>
        <ul className="mt-1 divide-y divide-line">
          {shown.map(app => (
            <AppRow key={app.id} app={app}>
              {app.id === ALWAYS_SHOWN ? (
                <span className="shrink-0 rounded-pill bg-surface-3 px-2.5 py-1 text-xs text-text-2">
                  {t('yourApps.alwaysOn')}
                </span>
              ) : (
                <button
                  type="button"
                  aria-disabled={pending || undefined}
                  onClick={() => void change(withRemoved(stored, app.id))}
                  aria-label={t('yourApps.removeAriaLabel', { app: appLabel(t, app) })}
                  className="shrink-0 rounded-pill border border-line px-3 py-1 text-xs text-text hover:bg-surface-3 aria-disabled:opacity-50"
                >
                  {t('yourApps.remove')}
                </button>
              )}
            </AppRow>
          ))}
        </ul>
      </section>
      {more.length > 0 && (
        <section aria-labelledby="your-apps-more" className="mt-4">
          <h3 id="your-apps-more" className="text-xs font-semibold uppercase tracking-wide text-text-3">
            {t('yourApps.moreHeading')}
          </h3>
          <ul className="mt-1 divide-y divide-line">
            {more.map(app => (
              <AppRow key={app.id} app={app} usedOn={usedOnDevices(registrations, app.id)}>
                <button
                  type="button"
                  aria-disabled={pending || undefined}
                  onClick={() => void change(withAdded(stored, app.id))}
                  aria-label={t('yourApps.addAriaLabel', { app: appLabel(t, app) })}
                  className="shrink-0 rounded-pill bg-primary px-3 py-1 text-xs font-semibold text-on-primary hover:opacity-90 aria-disabled:opacity-50"
                >
                  {t('yourApps.add')}
                </button>
              </AppRow>
            ))}
          </ul>
        </section>
      )}
      {error !== null && (
        <p role="alert" className="mt-3 text-sm text-danger">
          {error}
        </p>
      )}
      </div>
      <p className="mt-3 text-xs text-text-3">{t('yourApps.dataStays')}</p>
      <DoneButton onClose={onClose} />
    </Dialog>
  )
}

function AppRow({
  app,
  usedOn = [],
  children,
}: {
  readonly app: HostedApp
  /** Named devices the app is registered on (H6); empty shows no line. */
  readonly usedOn?: readonly string[]
  readonly children: React.ReactNode
}) {
  const { t } = useTranslation('control')
  const label = appLabel(t, app)
  const detail =
    app.id === ALWAYS_SHOWN
      ? t('yourApps.driveDetail')
      : usesDrive(app)
        ? t('yourApps.usesDrive')
        : appDescription(t, app)
  return (
    <li className="flex items-center gap-3 py-2.5">
      <AppIcon appId={app.id} label={label} size="md" />
      <span className="min-w-0 flex-1">
        <span className="block text-sm font-medium text-text">{label}</span>
        <span className="block truncate text-xs text-text-2">{detail}</span>
        {usedOn.length > 0 && (
          <span className="block truncate text-xs font-medium text-text">{usedOnLine(t, usedOn)}</span>
        )}
      </span>
      {children}
    </li>
  )
}

function usedOnLine(t: TFunction, names: readonly string[]): string {
  const [first, second] = names
  if (names.length === 1) return t('yourApps.usedOnOne', { device: first })
  if (names.length === 2) return t('yourApps.usedOnTwo', { first, second })
  return t('yourApps.usedOnMany', { first, count: names.length - 1 })
}

function DoneButton({ onClose }: { readonly onClose: () => void }) {
  const { t } = useTranslation('control')
  return (
    <div className="mt-4 flex justify-end">
      <button
        type="button"
        onClick={onClose}
        className="rounded-pill bg-primary px-4 py-1.5 text-sm font-semibold text-on-primary hover:opacity-90"
      >
        {t('yourApps.done')}
      </button>
    </div>
  )
}
