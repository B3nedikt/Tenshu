// /control/apps — the apps grid per `web-control-plane.md` §4.2 + `control-ia.md` §4.1. Every app
// this apex hosts, each with where the realm's `AppRegistration` rows say it runs
// (web-apex-home.md §4.7). Apex / Tenshu itself is intentionally absent (IA §4.1).
// ⚠ Loading or a failed read says nothing about devices — never "on no devices".
import { Link } from 'react-router'
import { useTranslation } from 'react-i18next'
import type { TFunction } from 'i18next'
import { useAppRegistrations, useInstalledApps } from './appRegistryContext'
import type { RegistrationsState } from './appRegistry'
import { type AppManifest } from './manifest'

export function AppsListPage() {
  const { t } = useTranslation('control')
  const apps = useInstalledApps()
  const registrations = useAppRegistrations()
  return (
    <div>
      <h1 className="text-2xl font-medium">{t('apps.list.title')}</h1>
      <p className="mt-1 text-sm text-text-2">{t('apps.list.subtitle')}</p>
      {apps.length === 0 ? (
        <div className="mt-6 rounded-md border border-dashed border-line p-8 text-center text-sm text-text-2">
          {t('apps.list.empty')}
        </div>
      ) : (
        <ul className="mt-6 grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
          {apps.map(app => (
            <li key={app.id}>
              <AppCard app={app} registrations={registrations} />
            </li>
          ))}
        </ul>
      )}
      <p className="mt-6 text-xs text-text-2">{t('apps.list.footer')}</p>
    </div>
  )
}

function AppCard({
  app,
  registrations,
}: {
  readonly app: AppManifest
  readonly registrations: RegistrationsState
}) {
  const { t } = useTranslation('control')
  return (
    <Link
      to={`/control/apps/${app.id}`}
      className="flex h-full flex-col rounded-md border border-line bg-surface p-4 transition-colors hover:border-line hover:bg-surface-3 focus:outline-none focus-visible:ring-2 focus-visible:ring-line-strong"
    >
      <div className="flex items-start gap-3">
        <span
          aria-hidden
          className="grid h-10 w-10 shrink-0 place-items-center rounded-md bg-primary text-sm font-semibold text-on-primary"
        >
          {app.label[0]}
        </span>
        <div className="min-w-0">
          <p className="text-sm font-medium text-text">{app.label}</p>
          <p className="mt-0.5 text-xs text-text-2">{t('apps.list.version', { version: app.version })}</p>
        </div>
      </div>
      <p className="mt-3 line-clamp-2 text-xs text-text-2">
        {app.description}
      </p>
      <div className="mt-3 flex items-center justify-between text-[11px] text-text-2">
        <span>{deviceLine(registrations, app.id, t)}</span>
        <span>{t('apps.list.permissionCount', { count: app.permissions.length })}</span>
      </div>
    </Link>
  )
}

function deviceLine(registrations: RegistrationsState, appId: string, t: TFunction): string | null {
  if (registrations.kind !== 'ready') return null
  const info = registrations.byApp.get(appId)
  return info === undefined
    ? t('apps.list.onNoDevice')
    : t('apps.list.onDevices', { count: info.devices.length })
}
