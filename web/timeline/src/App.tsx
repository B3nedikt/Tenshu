import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { ErrorBoundary } from './components/ErrorBoundary'
import { RuntimeBanner } from './components/RuntimeBanner'
import { HomeView } from './components/HomeView'
import { PlacesView } from './components/PlacesView'
import { MomentDetail } from './components/MomentDetail'
import { TripDetail } from './components/TripDetail'
import { PlaceDetail } from './components/PlaceDetail'
import { LiveProvider } from './lib/live'
import { DEFAULT_UNITS, UnitsContext } from './lib/units'
import type { Detail } from './lib/nav'

type Tab = 'timeline' | 'places'

const TABS: readonly Tab[] = ['timeline', 'places']

// Timeline on the web (web-app-suite.md Wave 2, run B). No backend of its own: every view reads
// and writes through the apex bridge (`lib/proxy.ts`), and the two live streams (feed, places) are
// subscribed once, here, by `LiveProvider`. Scope is `timeline-app.md` §Web scope → Decided: no
// check-in, no geocoding, no live "now" rows, no photos. Maps (Wave 3): trip, movement and place
// detail only — the day list has none, as natively, and never loads MapLibre.
export default function App() {
  const { t } = useTranslation()
  const [tab, setTab] = useState<Tab>('timeline')
  // The detail screens opened from this tab, innermost last; Back pops one.
  const [stack, setStack] = useState<readonly Detail[]>([])
  const top = stack.at(-1) ?? null

  const open = (detail: Detail) => setStack((s) => [...s, detail])
  const back = () => setStack((s) => s.slice(0, -1))
  const replace = (detail: Detail) => setStack((s) => [...s.slice(0, -1), detail])

  return (
    // ⚠ Units: `DEFAULT_UNITS` until the web SDK exposes `OwnerPreferences.Units` (`lib/units.ts`).
    <UnitsContext.Provider value={DEFAULT_UNITS}>
      <LiveProvider>
        <div className="min-h-screen bg-surface">
          <main className="mx-auto max-w-3xl px-4 py-10 sm:px-6">
            <header className="mb-6">
              <h1 className="text-2xl font-semibold tracking-tight text-text">{t('app.title')}</h1>
              <p className="mt-1 text-sm text-text-2">{t('app.tagline')}</p>
            </header>

            <nav className="mb-5 flex gap-1 border-b border-line">
              {TABS.map((id) => (
                <button
                  key={id}
                  type="button"
                  onClick={() => {
                    setTab(id)
                    setStack([])
                  }}
                  aria-current={tab === id ? 'page' : undefined}
                  className={`-mb-px border-b-2 px-3 py-2 text-sm font-medium ${
                    tab === id ? 'border-primary text-primary' : 'border-transparent text-text-2 hover:text-text'
                  }`}
                >
                  {t(`nav.${id}`)}
                </button>
              ))}
            </nav>

            {/* First: in a tab without the runtime, every other read fails too. */}
            <RuntimeBanner />

            {/* Keyed by tab and screen, so a crashed view recovers on navigation. */}
            <ErrorBoundary key={`${tab}:${top ? `${top.kind}:${top.id}` : ''}:${stack.length}`}>
              {top?.kind === 'moment' ? (
                <MomentDetail momentId={top.id} onBack={back} onOpen={open} />
              ) : top?.kind === 'trip' ? (
                <TripDetail tripId={top.id} onBack={back} onOpen={open} />
              ) : top?.kind === 'place' ? (
                <PlaceDetail placeId={top.id} onBack={back} onOpen={open} onReplace={replace} />
              ) : tab === 'timeline' ? (
                <HomeView onOpen={open} />
              ) : (
                <PlacesView onOpen={open} />
              )}
            </ErrorBoundary>
          </main>
        </div>
      </LiveProvider>
    </UnitsContext.Provider>
  )
}
