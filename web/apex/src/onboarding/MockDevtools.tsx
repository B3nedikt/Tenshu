// Floating dev panel for clicking through onboarding states without
// real persistence. Visible on localhost or when `?devtools=1` is in
// the URL. Lets you reset apex state and steer mock outcomes
// (pairing/recovery/unlock).
//
// Track A swap: when the real SDK lands, this panel becomes a no-op
// and can be removed; gating it on `import.meta.env.DEV` keeps it out
// of production bundles by default.
import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router'
import { readMockState, resetMockState } from '../lib/mockOnboarding'

const STORAGE_PREFIX = 'tenshu-mock:'

function setScenario(key: string, value: string | null): void {
  if (value === null) window.sessionStorage.removeItem(STORAGE_PREFIX + key)
  else window.sessionStorage.setItem(STORAGE_PREFIX + key, value)
}

function isDevtoolsVisible(): boolean {
  if (typeof window === 'undefined') return false
  if (new URLSearchParams(window.location.search).has('devtools')) return true
  if (window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1') return true
  return false
}

export function MockDevtools() {
  const [open, setOpen] = useState(false)
  const [now, setNow] = useState(() => Date.now())
  const navigate = useNavigate()

  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(id)
  }, [])

  if (!isDevtoolsVisible()) return null

  const state = readMockState()

  return (
    <div className="fixed bottom-3 right-3 z-50 max-w-xs">
      {open ? (
        <div className="rounded-lg border border-line bg-surface/95 p-3 text-xs shadow-lg backdrop-blur">
          <div className="mb-2 flex items-center justify-between">
            <span className="font-semibold">Mock devtools</span>
            <button
              type="button"
              onClick={() => setOpen(false)}
              aria-label="Close"
              className="text-text-3 hover:text-text-2"
            >
              ✕
            </button>
          </div>
          <dl className="grid grid-cols-[max-content_1fr] gap-x-2 gap-y-0.5 font-mono">
            <dt className="text-text-2">realm</dt>
            <dd>{state.hasRealm ? state.deviceName ?? 'yes' : 'no'}</dd>
            <dt className="text-text-2">token</dt>
            <dd>
              {state.hasToken
                ? state.tokenExpiresAtMs !== null
                  ? `${Math.max(0, Math.floor((state.tokenExpiresAtMs - now) / 1000))}s`
                  : 'yes'
                : 'no'}
            </dd>
            <dt className="text-text-2">now</dt>
            <dd className="font-mono">{new Date(now).toLocaleTimeString()}</dd>
          </dl>
          <Section title="Landing">
            <Pill onClick={() => goReset(navigate)}>Reset & home</Pill>
            <Pill onClick={() => goScenario(navigate, 'firstTime')}>firstTime</Pill>
            <Pill onClick={() => goScenario(navigate, 'locked')}>locked</Pill>
            <Pill onClick={() => goScenario(navigate, 'active')}>active</Pill>
          </Section>
          <Section title="Pairing">
            <Pill onClick={() => setScenario('pairing', null)}>paired</Pill>
            <Pill onClick={() => setScenario('pairing', 'denied')}>denied</Pill>
            <Pill onClick={() => setScenario('pairing', 'expired')}>expired</Pill>
            <Pill onClick={() => setScenario('pairing', 'error')}>error</Pill>
            <Pill onClick={() => setScenario('pairing', 'invalidUri')}>invalid</Pill>
            <Pill onClick={() => setScenario('pairing', 'network')}>network</Pill>
          </Section>
          <Section title="Recovery">
            <Pill onClick={() => setScenario('recovery', null)}>ok</Pill>
            <Pill onClick={() => setScenario('recovery', 'noBackup')}>noBackup</Pill>
            <Pill onClick={() => setScenario('recovery', 'kdfStale')}>kdfStale</Pill>
            <Pill onClick={() => setScenario('recovery', 'network')}>network</Pill>
            <Pill onClick={() => setScenario('recovery', 'checksum')}>checksum</Pill>
          </Section>
          <Section title="Unlock">
            <Pill onClick={() => setScenario('unlock', null)}>ok</Pill>
            <Pill onClick={() => setScenario('unlock', 'fail')}>fail</Pill>
          </Section>
          <Section title="Realm.create">
            <Pill onClick={() => setScenario('realm', null)}>ok</Pill>
            <Pill onClick={() => setScenario('realm', 'fail')}>fail</Pill>
          </Section>
          <p className="mt-2 text-[10px] text-text-3">
            Scenarios persist per session. Reset clears them and the apex state.
          </p>
        </div>
      ) : (
        <button
          type="button"
          onClick={() => setOpen(true)}
          className="rounded-full border border-line bg-surface/90 px-3 py-1.5 text-xs font-medium text-text-2 shadow backdrop-blur hover:bg-surface"
        >
          mock devtools
        </button>
      )}
    </div>
  )
}

function Section({ title, children }: { readonly title: string; readonly children: React.ReactNode }) {
  return (
    <div className="mt-2">
      <p className="text-[10px] uppercase tracking-wide text-text-2">{title}</p>
      <div className="mt-1 flex flex-wrap gap-1">{children}</div>
    </div>
  )
}

function Pill({
  children,
  onClick,
}: {
  readonly children: React.ReactNode
  readonly onClick: () => void
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="rounded border border-line bg-surface-2 px-2 py-0.5 text-[11px] hover:bg-surface-3"
    >
      {children}
    </button>
  )
}

function goReset(navigate: ReturnType<typeof useNavigate>): void {
  resetMockState()
  navigate('/', { replace: true })
}

// Navigate to `/` (the landing) so the smart CTA + signed-in banner
// render in the chosen scenario. Click the CTA from there to reach the
// post-state destination (`/start` → `/onboarding` chooser, `/lock`,
// `/overview`). Going straight to those destinations skipped the
// landing — useful before web-apex-landing.md landed; now the landing
// IS the surface most onboarding-flow tests want to start from.
function goScenario(navigate: ReturnType<typeof useNavigate>, scenario: 'firstTime' | 'locked' | 'active'): void {
  resetMockState()
  setScenario('scenario', scenario)
  navigate('/', { replace: true })
}
