// The signed-in parent of both shells (docs/proposals/web-apex-home.md §3.2): Home + the hosted
// apps render under `AppShell`, `/control/*` under `ControlShell`, and both need the same session
// guard. It lives here once rather than in each shell.
//
// - `Session.detect()`: a first-time browser goes to the landing, a locked one to `/lock?next=…`,
//   an active one renders the child shell with its device name (read with `useSessionDevice`).
// - W10a: ask the browser to keep this origin's data, once per signed-in mount.
// - The shown-apps subscription the overview, its dialog and both launchers share.
import { useEffect, useState } from 'react'
import { Outlet, useLocation, useNavigate } from 'react-router'
import { useTranslation } from 'react-i18next'
import { Session } from '@tenshu/sdk/onboarding'
import { ensurePersistence } from '../lib/persistentStorage'
import type { SessionOutletContext } from './sessionContext'
import { ShownAppsProvider } from '../overview/ShownAppsProvider'
import type { ShownAppsSource } from '../overview/shownAppsContext'

// [shownApps]: the overview's shown-apps source (web-apex-home.md §3.5). ⚠ Required and injected by
// the runtime root, never defaulted to the SDK here (`overview/shownAppsContext.ts`). Its provider
// mounts only once the session is ACTIVE, so a locked or first-time browser never subscribes.
export function SessionLayout({ shownApps }: { readonly shownApps: ShownAppsSource }) {
  const { t } = useTranslation('control')
  const navigate = useNavigate()
  const [deviceName, setDeviceName] = useState<string | null>(null)
  // Where the user was going when the guard ran — the router's location, not `window.location`
  // (they differ under a MemoryRouter). Captured once: the probe runs once per mount, at entry.
  const { pathname, search } = useLocation()
  const [entryPath] = useState(pathname + search)
  useEffect(() => {
    let cancelled = false
    void Session.detect().then(s => {
      if (cancelled) return
      if (s.kind === 'firstTime') {
        navigate('/', { replace: true })
        return
      }
      if (s.kind === 'locked') {
        const next = encodeURIComponent(entryPath)
        navigate(`/lock?next=${next}`, { replace: true })
        return
      }
      setDeviceName(s.deviceName)
    })
    return () => {
      cancelled = true
    }
  }, [navigate, entryPath])
  // W10a. This is the signed-in layout, so by here the device has (or is acquiring) a realm —
  // asking earlier would request durability for nothing.
  //
  // ⚠⚠ ABOVE the `deviceName === null` early return, deliberately. Below it this is a CONDITIONAL
  // hook — not called on the loading render, called on the next — which throws "rendered more hooks
  // than during the previous render" and takes the whole shell down.
  //
  // ⚠ Not awaited and never surfaced as an error: a refusal is a normal outcome (Chrome grants on
  // its own heuristics, Firefox prompts), and the honest report is the Services row that reads the
  // state, not a toast at an arbitrary moment.
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
  const context: SessionOutletContext = { deviceName }
  return (
    <ShownAppsProvider source={shownApps}>
      <Outlet context={context} />
    </ShownAppsProvider>
  )
}
