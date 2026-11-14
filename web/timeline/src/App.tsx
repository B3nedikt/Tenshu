import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { AppLayout } from '@tenshu/app-kit/ui'
import { ErrorBoundary } from './components/ErrorBoundary'
import { RuntimeBanner } from './components/RuntimeBanner'
import { HomeView } from './components/HomeView'
import { PlacesView } from './components/PlacesView'
import { MomentDetail } from './components/MomentDetail'
import { TripDetail } from './components/TripDetail'
import { PlaceDetail } from './components/PlaceDetail'
import { LiveProvider } from './lib/live'
import { useAppRoute } from '@tenshu/app-kit/route'
import { DEFAULT_UNITS, UnitsContext } from './lib/units'
import { detailOf, fragmentOf, type Detail } from './lib/nav'

type Tab = 'timeline' | 'places'

// Module-level: `useAppRoute` re-subscribes when its section list changes identity. `timeline` is
// `/`, `places` is `/places` — the registry's `sections` (`['', 'places']`) must match.
const TABS: readonly Tab[] = ['timeline', 'places']

// Timeline on the web (web-app-suite.md Wave 2, run B). No backend of its own: every view reads
// and writes through the apex bridge (`lib/proxy.ts`), and the two live streams (feed, places) are
// subscribed once, here, by `LiveProvider`. Scope is `timeline-app.md` §Web scope → Decided: no
// check-in, no geocoding, no live "now" rows, no photos. Maps (Wave 3): trip, movement and place
// detail only — the day list has none, as natively, and never loads MapLibre.
export default function App() {
  const { t } = useTranslation()
  // The section is the path; the innermost open detail is the fragment (`lib/nav.ts`).
  const { section: tab, fragment, navigate, setFragment, open: openDetail, back: routeBack } = useAppRoute(TABS, 'timeline')
  // The detail screens opened from this tab, innermost last; Back pops one. The URL holds only the
  // top, so this is trusted only while its top is what the fragment names — a reload, a deep link
  // or an apex back/forward that lands elsewhere rebuilds it as the fragment's one detail.
  const [kept, setKept] = useState<readonly Detail[]>([])
  const stack = fragmentOf(kept.at(-1) ?? null) === fragment ? kept : fromFragment(fragment)
  const top = stack.at(-1) ?? null

  const show = (next: readonly Detail[]) => {
    setKept(next)
    setFragment(fragmentOf(next.at(-1) ?? null))
  }
  // A detail takes over the content, so opening one is a history entry and Back steps that history
  // (web-apps-layout.md §4.2); a reloaded or deep-linked detail's Back replaces instead.
  const open = (detail: Detail) => {
    setKept([...stack, detail])
    openDetail(fragmentOf(detail))
  }
  const back = () => {
    const next = stack.slice(0, -1)
    setKept(next)
    routeBack(fragmentOf(next.at(-1) ?? null))
  }
  const replace = (detail: Detail) => show([...stack.slice(0, -1), detail])

  return (
    // ⚠ Units: `DEFAULT_UNITS` until the web SDK exposes `OwnerPreferences.Units` (`lib/units.ts`).
    <UnitsContext.Provider value={DEFAULT_UNITS}>
      <LiveProvider>
        {/* No settings section to pin to the foot: both sections are content. */}
        <AppLayout
          navLabel={t('nav.label')}
          nav={TABS.map((id) => ({ id, label: t(`nav.${id}`) }))}
          active={tab}
          onNavigate={(id) => {
            setKept([])
            navigate(id)
          }}
          // First: in a tab without the runtime, every other read fails too.
          banners={<RuntimeBanner />}
        >
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
        </AppLayout>
      </LiveProvider>
    </UnitsContext.Provider>
  )
}

function fromFragment(fragment: string): readonly Detail[] {
  const detail = detailOf(fragment)
  return detail ? [detail] : []
}
