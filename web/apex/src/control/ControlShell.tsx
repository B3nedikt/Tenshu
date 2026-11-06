// Apex shell for /control/* and /drive/* routes (per
// `web-control-plane.md` §3 + §7). Top bar (sticky), side rail (≥md),
// bottom nav (<md), and a session-expired guard that bounces to /lock.
//
// Sync status indicator: per AGENTS.md's in-flight refactor table the
// SyncManager.divergenceWarning StateFlow is currently a perpetually-null
// placeholder until proxy init wires it. Phase A renders the green-default
// dot; the status pipe lights up once the SDK exposes the stream.
import { useEffect, useState } from 'react'
import { Link, NavLink, Outlet, useNavigate } from 'react-router'
import { useTranslation } from 'react-i18next'
import type { TFunction } from 'i18next'
import { Session } from '@tenshu/sdk/onboarding'
import { BrandLockup } from '../components/BrandLockup'
import { AppLauncher } from './AppLauncher'
import { DeviceMenu } from './DeviceMenu'
import { TryModeBanner } from './TryModeBanner'
import { ensurePersistence } from '../lib/persistentStorage'

interface SectionEntry {
  readonly to: string
  readonly labelKey: string
  readonly icon: () => React.ReactNode
  // Match nested routes (e.g. /control/data covers /control/data/files).
  readonly basePath: string
}

const SECTIONS: readonly SectionEntry[] = [
  { to: '/control/home', basePath: '/control/home', labelKey: 'shell.sections.home', icon: HomeIcon },
  { to: '/control/apps', basePath: '/control/apps', labelKey: 'shell.sections.apps', icon: AppsIcon },
  { to: '/control/data', basePath: '/control/data', labelKey: 'shell.sections.data', icon: DataIcon },
  { to: '/control/services', basePath: '/control/services', labelKey: 'shell.sections.services', icon: ServicesIcon },
  { to: '/control/settings', basePath: '/control/settings', labelKey: 'shell.sections.settings', icon: SettingsIcon },
]

export function ControlShell() {
  const { t } = useTranslation('control')
  const navigate = useNavigate()
  const [deviceName, setDeviceName] = useState<string | null>(null)
  useEffect(() => {
    let cancelled = false
    void Session.detect().then(s => {
      if (cancelled) return
      if (s.kind === 'firstTime') {
        navigate('/', { replace: true })
        return
      }
      if (s.kind === 'locked') {
        const next = encodeURIComponent(window.location.pathname + window.location.search)
        navigate(`/lock?next=${next}`, { replace: true })
        return
      }
      setDeviceName(s.deviceName)
    })
    return () => {
      cancelled = true
    }
  }, [navigate])
  // W10a. Ask the browser to keep this origin's data, once. This is the signed-in shell, so by
  // here the device has (or is acquiring) a realm — asking earlier would request durability for
  // nothing.
  //
  // ⚠⚠ ABOVE the `deviceName === null` early return, deliberately. Placed after it this is a
  // CONDITIONAL hook — not called on the loading render, called on the next — which throws
  // "rendered more hooks than during the previous render" and takes the whole shell down. Three
  // ControlShell tests caught exactly that.
  //
  // ⚠ Not awaited and never surfaced as an error: a refusal is a normal outcome (Chrome grants on
  // its own heuristics, Firefox prompts), and the honest report is the Services row that reads
  // the state, not a toast at an arbitrary moment.
  useEffect(() => {
    void ensurePersistence()
  }, [])

  if (deviceName === null) {
    return (
      <div className="grid min-h-screen place-items-center bg-surface text-text">
        <div className="text-sm text-text-2">{t('shell.loading')}</div>
      </div>
    )
  }
  return (
    <div className="min-h-screen bg-surface text-text">
      <TopBar deviceName={deviceName} />
      <TryModeBanner />
      <div className="mx-auto flex w-full max-w-7xl">
        <SideRail deviceName={deviceName} />
        <main className="flex-1 px-6 py-8 pb-24 md:pb-8">
          <Outlet />
        </main>
      </div>
      <BottomNav />
    </div>
  )
}

function TopBar({ deviceName }: { readonly deviceName: string }) {
  return (
    <header className="sticky top-0 z-20 h-14 border-b border-line bg-surface/80 backdrop-blur">
      <div className="mx-auto flex h-full max-w-7xl items-center justify-between gap-3 px-4">
        <Link to="/control/home">
          <BrandLockup />
        </Link>
        <div className="flex justify-center">
          <AppLauncher />
        </div>
        <DeviceMenu deviceName={deviceName} />
      </div>
    </header>
  )
}

function SideRail({ deviceName }: { readonly deviceName: string }) {
  const { t } = useTranslation('control')
  return (
    <aside className="hidden w-56 shrink-0 border-r border-line px-3 py-6 md:block">
      <nav className="flex flex-col gap-1">
        {SECTIONS.map(s => (
          <SectionLink key={s.to} entry={s} variant="rail" />
        ))}
      </nav>
      <div className="mt-8 rounded-md border border-line p-3 text-xs">
        <p className="text-text-2">{t('shell.thisDeviceLabel')}</p>
        <p className="mt-1 truncate font-mono text-text">{deviceName}</p>
        <div className="mt-2 flex items-center gap-2 text-text-2">
          <span aria-hidden className="h-2 w-2 rounded-full bg-success" />
          <span>{t('shell.synced')}</span>
        </div>
      </div>
    </aside>
  )
}

function BottomNav() {
  return (
    <nav className="fixed inset-x-0 bottom-0 z-20 grid grid-cols-5 border-t border-line bg-surface/95 backdrop-blur md:hidden">
      {SECTIONS.map(s => (
        <SectionLink key={s.to} entry={s} variant="bottom" />
      ))}
    </nav>
  )
}

function SectionLink({
  entry,
  variant,
}: {
  readonly entry: SectionEntry
  readonly variant: 'rail' | 'bottom'
}) {
  const { t } = useTranslation('control')
  const Icon = entry.icon
  const label = labelFor(t, entry)
  if (variant === 'rail') {
    return (
      <NavLink
        to={entry.to}
        className={({ isActive }) =>
          [
            'flex items-center gap-3 rounded-md px-3 py-2 text-sm font-medium transition-colors',
            matchesBase(isActive, entry)
              ? 'bg-primary text-on-primary'
              : 'text-text-2 hover:bg-surface-3',
          ].join(' ')
        }
      >
        <Icon />
        <span>{label}</span>
      </NavLink>
    )
  }
  return (
    <NavLink
      to={entry.to}
      className={({ isActive }) =>
        [
          'flex flex-col items-center gap-0.5 py-2 text-[11px] font-medium',
          matchesBase(isActive, entry)
            ? 'text-primary'
            : 'text-text-2 hover:text-text',
        ].join(' ')
      }
    >
      <Icon />
      <span>{label}</span>
    </NavLink>
  )
}

function labelFor(t: TFunction, entry: SectionEntry): string {
  return t(entry.labelKey)
}

// React Router's `isActive` is exact-match for our top-level routes; we
// also want a section to highlight while the user is on a nested route
// (e.g. /control/data/files highlights Data). NavLink's `end` flag would
// make `/control/data` the active segment, but doesn't help when we
// don't render the parent route directly. So we additionally inspect
// the current pathname.
function matchesBase(isActive: boolean, entry: SectionEntry): boolean {
  if (isActive) return true
  if (typeof window === 'undefined') return false
  return window.location.pathname.startsWith(entry.basePath + '/')
}

function HomeIcon() {
  return (
    <svg aria-hidden viewBox="0 0 20 20" width={18} height={18} fill="none" stroke="currentColor" strokeWidth={1.6}>
      <path d="M3 10l7-6 7 6" />
      <path d="M5 9v8h10V9" />
    </svg>
  )
}
function AppsIcon() {
  return (
    <svg aria-hidden viewBox="0 0 20 20" width={18} height={18} fill="currentColor">
      <rect x={3} y={3} width={6} height={6} rx={1} />
      <rect x={11} y={3} width={6} height={6} rx={1} />
      <rect x={3} y={11} width={6} height={6} rx={1} />
      <rect x={11} y={11} width={6} height={6} rx={1} />
    </svg>
  )
}
function DataIcon() {
  return (
    <svg aria-hidden viewBox="0 0 20 20" width={18} height={18} fill="none" stroke="currentColor" strokeWidth={1.6}>
      <ellipse cx={10} cy={5} rx={6} ry={2.5} />
      <path d="M4 5v10c0 1.4 2.7 2.5 6 2.5s6-1.1 6-2.5V5" />
      <path d="M4 10c0 1.4 2.7 2.5 6 2.5s6-1.1 6-2.5" />
    </svg>
  )
}
function ServicesIcon() {
  return (
    <svg aria-hidden viewBox="0 0 20 20" width={18} height={18} fill="none" stroke="currentColor" strokeWidth={1.6}>
      <circle cx={10} cy={10} r={3} />
      <path d="M10 1.5v2M10 16.5v2M1.5 10h2M16.5 10h2M3.9 3.9l1.4 1.4M14.7 14.7l1.4 1.4M3.9 16.1l1.4-1.4M14.7 5.3l1.4-1.4" />
    </svg>
  )
}
function SettingsIcon() {
  return (
    <svg aria-hidden viewBox="0 0 20 20" width={18} height={18} fill="none" stroke="currentColor" strokeWidth={1.6}>
      <circle cx={10} cy={10} r={2.5} />
      <path d="M10 1.5l1.2 2.1 2.4-.5.5 2.4 2.1 1.2-1.2 2.1.5 2.4-2.4.5-1.2 2.1-2.1-1.2-2.4 1.2-1.2-2.1-2.4-.5.5-2.4-2.1-1.2 1.2-2.1-.5-2.4 2.4.5L10 1.5z" />
    </svg>
  )
}
