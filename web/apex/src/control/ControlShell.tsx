// Apex shell for /control/* (per `web-control-plane.md` §3, as split by `web-apex-home.md` §3.2):
// top bar (sticky), side rail (≥md), bottom nav (<md). The overview and the hosted apps have their
// own slimmer shell (`shell/AppShell.tsx`); the session guard both need is `shell/SessionLayout`.
//
// Sync status indicator: per AGENTS.md's in-flight refactor table the
// SyncManager.divergenceWarning StateFlow is currently a perpetually-null
// placeholder until proxy init wires it. Phase A renders the green-default
// dot; the status pipe lights up once the SDK exposes the stream.
import { Link, NavLink, Outlet } from 'react-router'
import { useTranslation } from 'react-i18next'
import type { TFunction } from 'i18next'
import { BrandLockup } from '../components/BrandLockup'
import { OVERVIEW_PATH } from '../lib/overviewPath'
import { BellIcon, GearIcon } from '../shell/icons'
import { useSessionDevice } from '../shell/sessionContext'
import { AppLauncher } from './AppLauncher'
import { DeviceMenu } from './DeviceMenu'
import { TryModeBanner } from './TryModeBanner'

interface SectionEntry {
  readonly to: string
  readonly labelKey: string
  readonly icon: () => React.ReactNode
  // Match nested routes (e.g. /control/data covers /control/data/files).
  readonly basePath: string
}

// The first section is Control's notification feed at its old route; it is labelled
// "Notifications", never "Home" — the overview and the Home app both own that word (§3.4).
const SECTIONS: readonly SectionEntry[] = [
  { to: '/control/home', basePath: '/control/home', labelKey: 'shell.sections.notifications', icon: NotificationsIcon },
  { to: '/control/apps', basePath: '/control/apps', labelKey: 'shell.sections.apps', icon: AppsIcon },
  { to: '/control/data', basePath: '/control/data', labelKey: 'shell.sections.data', icon: DataIcon },
  { to: '/control/services', basePath: '/control/services', labelKey: 'shell.sections.services', icon: ServicesIcon },
  { to: '/control/settings', basePath: '/control/settings', labelKey: 'shell.sections.settings', icon: SettingsIcon },
]

export function ControlShell() {
  const deviceName = useSessionDevice()
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
        <Link to={OVERVIEW_PATH}>
          <BrandLockup />
        </Link>
        <div className="flex items-center gap-1">
          <AppLauncher />
          <DeviceMenu deviceName={deviceName} />
        </div>
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
  return <GearIcon />
}
function NotificationsIcon() {
  return <BellIcon />
}
