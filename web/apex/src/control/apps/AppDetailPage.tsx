// /control/apps/:appId — per-app detail per `web-control-plane.md` §4.2
// + `control-ia.md` §4.2. Reads the live `AppRegistry`: declared permissions
// with a tri-state grant control (display state on the web — `appRegistry.ts`),
// service cross-links to §6 Services, version, and — from the realm's
// `AppRegistration` rows — the install date and the devices it runs on
// (web-apex-home.md §4.7).
//
// ⚠⚠ Removal is ALL devices or nothing: the browser holds no registration of
// its own, so "this device" would be a silent no-op. The app stays openable on
// the web afterwards, so the page stays where it is.
import { useState } from 'react'
import { Link, Navigate, useParams } from 'react-router'
import { useTranslation } from 'react-i18next'
import type { TFunction } from 'i18next'
import { useAppDetail, useAppRegistrations, useAppRegistry } from './appRegistryContext'
import { type AppRegistrationInfo, type GrantState, type RegistrationsState } from './appRegistry'
import { type AppPermission, type AppServiceLink, type PermissionState } from './manifest'

const GRANT_OPTION_STATES: readonly GrantState[] = ['granted', 'denied', 'askEachTime']

export function AppDetailPage() {
  const { t } = useTranslation('control')
  const { appId } = useParams<{ readonly appId: string }>()
  const registry = useAppRegistry()
  const app = useAppDetail(appId ?? '')
  const registrations = useAppRegistrations()
  const [confirmingRemoval, setConfirmingRemoval] = useState(false)
  const [removing, setRemoving] = useState(false)
  const [removalFailed, setRemovalFailed] = useState(false)

  if (appId === undefined) return <Navigate to="/control/apps" replace />
  if (app === null) return <NotFound id={appId} />
  const registration = registrations.kind === 'ready' ? (registrations.byApp.get(app.id) ?? null) : undefined

  async function remove(id: string) {
    setRemoving(true)
    setRemovalFailed(false)
    const ok = await registry.removeFromAllDevices(id)
    setRemoving(false)
    setConfirmingRemoval(false)
    if (!ok) setRemovalFailed(true)
  }

  return (
    <div>
      <Link
        to="/control/apps"
        className="text-xs font-medium text-text-2 hover:text-text"
      >
        {t('apps.detail.backLink')}
      </Link>
      <header className="mt-4 flex items-start gap-4">
        <span
          aria-hidden
          className="grid h-12 w-12 shrink-0 place-items-center rounded-md bg-primary text-base font-semibold text-on-primary"
        >
          {app.label[0]}
        </span>
        <div className="min-w-0">
          <h1 className="text-2xl font-medium">{app.label}</h1>
          <p className="mt-1 text-sm text-text-2">{app.description}</p>
        </div>
      </header>

      <dl className="mt-6 grid gap-4 sm:grid-cols-3">
        <MetaCell term={t('apps.detail.meta.version')} value={`v${app.version}`} />
        <MetaCell term={t('apps.detail.meta.installed')} value={installedValue(registration, t)} />
        <MetaCell term={t('apps.detail.meta.launchPath')} value={app.launchPath} mono />
      </dl>

      <section className="mt-8">
        <SectionHeader>{t('apps.detail.permissions.title')}</SectionHeader>
        <p className="mt-1 text-xs text-text-2">{t('apps.detail.permissions.subtitle')}</p>
        <ul className="mt-3 divide-y divide-line rounded-md border border-line">
          {app.permissions.map(p => (
            <li key={p.id} className="px-4 py-3">
              <PermissionRow
                permission={p}
                onSetGrant={state => registry.setGrant(app.id, p.id, state)}
              />
            </li>
          ))}
        </ul>
      </section>

      <section className="mt-8">
        <SectionHeader>{t('apps.detail.services.title')}</SectionHeader>
        <p className="mt-1 text-xs text-text-2">
          {t('apps.detail.services.subtitlePrefix')}
          <Link to="/control/services" className="underline-offset-4 hover:underline">
            {t('apps.detail.services.subtitleLink')}
          </Link>
          {t('apps.detail.services.subtitleSuffix')}
        </p>
        <ul className="mt-3 divide-y divide-line rounded-md border border-line">
          {app.services.map(s => (
            <li key={s.id} className="px-4 py-3">
              <ServiceRow link={s} />
            </li>
          ))}
        </ul>
      </section>

      <section className="mt-8">
        <SectionHeader>{t('apps.detail.devices.title')}</SectionHeader>
        <DevicesBody registrations={registrations} registration={registration} />
      </section>

      <section className="mt-10">
        <SectionHeader>{t('apps.detail.perAppActions')}</SectionHeader>
        <div className="mt-3 flex flex-wrap gap-3">
          <button
            type="button"
            onClick={() => registry.revokeAll(app.id)}
            className="rounded-md border border-line bg-surface px-3 py-1.5 text-sm font-medium text-text hover:bg-surface-3"
          >
            {t('apps.detail.revokeAll')}
          </button>
          {/* Only with something to remove: no device holds a row while loading, failed or unused. */}
          {registration != null && (
            <button
              type="button"
              onClick={() => setConfirmingRemoval(true)}
              className="rounded-md bg-danger px-3 py-1.5 text-sm font-medium text-white hover:bg-danger/90"
            >
              {t('apps.detail.uninstall')}
            </button>
          )}
        </div>
        {removalFailed && (
          <p role="alert" className="mt-3 text-sm text-danger">
            {t('apps.detail.uninstallConfirm.failed', { appName: app.label })}
          </p>
        )}
      </section>

      {confirmingRemoval && (
        <UninstallConfirm
          appName={app.label}
          busy={removing}
          onCancel={() => setConfirmingRemoval(false)}
          onConfirm={() => void remove(app.id)}
        />
      )}
    </div>
  )
}

function NotFound({ id }: { readonly id: string }) {
  const { t } = useTranslation('control')
  return (
    <div>
      <Link
        to="/control/apps"
        className="text-xs font-medium text-text-2 hover:text-text"
      >
        {t('apps.detail.backLink')}
      </Link>
      <h1 className="mt-4 text-2xl font-medium">{t('apps.detail.notFound.title')}</h1>
      <p className="mt-1 text-sm text-text-2">
        {t('apps.detail.notFound.bodyPrefix')}
        <span className="font-mono text-xs">{id}</span>
        {t('apps.detail.notFound.bodySuffix')}
      </p>
    </div>
  )
}

function SectionHeader({ children }: { readonly children: React.ReactNode }) {
  return (
    <h2 className="text-sm font-semibold uppercase tracking-wide text-text-2">{children}</h2>
  )
}

function MetaCell({
  term,
  value,
  mono,
}: {
  readonly term: string
  readonly value: string
  readonly mono?: boolean
}) {
  return (
    <div className="rounded-md border border-line px-3 py-2">
      <dt className="text-[11px] uppercase tracking-wide text-text-2">{term}</dt>
      <dd
        className={
          'mt-0.5 text-sm text-text' +
          (mono === true ? ' font-mono text-xs' : '')
        }
      >
        {value}
      </dd>
    </div>
  )
}

function PermissionRow({
  permission,
  onSetGrant,
}: {
  readonly permission: AppPermission
  readonly onSetGrant: (state: GrantState) => void
}) {
  const { t } = useTranslation('control')
  return (
    <div className="flex flex-col gap-2">
      <div className="min-w-0">
        <p className="text-sm font-medium text-text">{permission.label}</p>
        <p className="mt-0.5 text-xs text-text-2">{permission.description}</p>
        <p className="mt-1 text-[11px] text-text-2">
          {t('apps.detail.permissions.lastUsedLabel')}
          {permission.lastUsedAtMs === null
            ? t('apps.detail.permissions.lastUsedNever')
            : new Date(permission.lastUsedAtMs).toLocaleString()}
        </p>
      </div>
      <GrantControl label={permission.label} state={permission.state} onSetGrant={onSetGrant} />
    </div>
  )
}

function GrantControl({
  label,
  state,
  onSetGrant,
}: {
  readonly label: string
  readonly state: PermissionState
  readonly onSetGrant: (state: GrantState) => void
}) {
  const { t } = useTranslation('control')
  return (
    <div className="inline-flex rounded-md border border-line p-0.5">
      {GRANT_OPTION_STATES.map(optionState => {
        const selected = optionState === state
        const optionLabel = grantLabel(t, optionState)
        return (
          <button
            key={optionState}
            type="button"
            aria-pressed={selected}
            aria-label={t('apps.detail.grant.setAriaLabel', { permission: label, state: optionLabel })}
            onClick={() => onSetGrant(optionState)}
            className={
              'rounded px-2.5 py-1 text-xs font-medium transition-colors ' +
              (selected
                ? 'bg-primary text-on-primary'
                : 'text-text-2 hover:bg-surface-3')
            }
          >
            {optionLabel}
          </button>
        )
      })}
    </div>
  )
}

function grantLabel(t: TFunction, state: GrantState): string {
  return t(`apps.detail.grant.${state}`)
}

function ServiceRow({ link }: { readonly link: AppServiceLink }) {
  return (
    <div className="flex items-start justify-between gap-4">
      <div className="min-w-0">
        <Link
          to={`/control/services/${link.id}`}
          className="text-sm font-medium text-text underline-offset-4 hover:underline"
        >
          {link.label}
        </Link>
        <p className="mt-0.5 text-xs text-text-2">{link.reason}</p>
      </div>
      <span aria-hidden className="text-text-3">
        →
      </span>
    </div>
  )
}

function UninstallConfirm({
  appName,
  busy,
  onCancel,
  onConfirm,
}: {
  readonly appName: string
  readonly busy: boolean
  readonly onCancel: () => void
  readonly onConfirm: () => void
}) {
  const { t } = useTranslation('control')
  return (
    <div
      role="dialog"
      aria-label={t('apps.detail.uninstallConfirm.ariaLabel', { appName })}
      className="fixed inset-0 z-50 grid place-items-center bg-black/40 p-4"
    >
      <div className="w-full max-w-md rounded-lg bg-surface p-6 shadow-xl">
        <h2 className="text-lg font-medium">{t('apps.detail.uninstallConfirm.title', { appName })}</h2>
        <p className="mt-3 text-sm text-text-2">{t('apps.detail.uninstallConfirm.note')}</p>
        <div className="mt-5 flex justify-end gap-2">
          <button
            type="button"
            onClick={onCancel}
            className="rounded-md border border-line bg-surface px-3 py-1.5 text-sm font-medium text-text hover:bg-surface-3"
          >
            {t('apps.detail.uninstallConfirm.cancel')}
          </button>
          <button
            type="button"
            aria-disabled={busy || undefined}
            onClick={() => {
              if (!busy) onConfirm()
            }}
            className="rounded-md bg-danger px-3 py-1.5 text-sm font-medium text-white hover:bg-danger/90 aria-disabled:opacity-50"
          >
            {t('apps.detail.uninstallConfirm.remove')}
          </button>
        </div>
      </div>
    </div>
  )
}

/** `undefined` = no answer (loading / failed) → a dash, never a guessed date. */
function installedValue(registration: AppRegistrationInfo | null | undefined, t: TFunction): string {
  if (registration === undefined) return '—'
  if (registration === null) return t('apps.detail.meta.notInstalled')
  return new Date(registration.installedAtMs).toLocaleDateString()
}

function DevicesBody({
  registrations,
  registration,
}: {
  readonly registrations: RegistrationsState
  readonly registration: AppRegistrationInfo | null | undefined
}) {
  const { t } = useTranslation('control')
  if (registrations.kind === 'loading') {
    return <p className="mt-1 text-xs text-text-2">{t('apps.detail.devices.loading')}</p>
  }
  if (registration === undefined) {
    return <p className="mt-1 text-xs text-text-2">{t('apps.detail.devices.unavailable')}</p>
  }
  if (registration === null) {
    return <p className="mt-1 text-xs text-text-2">{t('apps.detail.devices.none')}</p>
  }
  return (
    <ul className="mt-3 divide-y divide-line rounded-md border border-line">
      {registration.devices.map(device => (
        <li key={device.deviceId} className="px-4 py-3 text-sm text-text">
          {device.name ?? t('apps.detail.devices.unknownDevice')}
        </li>
      ))}
    </ul>
  )
}
