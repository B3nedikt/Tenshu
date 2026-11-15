import { useCallback, useLayoutEffect, useRef } from 'react'
import { useAppRoute } from '@tenshu/app-kit/route'
import { ErrorBoundary } from './components/ErrorBoundary'
import { RuntimeBanner } from './components/RuntimeBanner'
import { PlacesView } from './components/PlacesView'
import { PlaceDetail } from './components/PlaceDetail'
import { LiveProvider } from './lib/live'
import { detailOf, fragmentOf, type Detail } from './lib/nav'

// One section, so always `/` (the registry's `sections` is `['']`). Module-level: `useAppRoute`
// re-subscribes when its section list changes identity.
const SECTIONS = ['map'] as const

// Maps on the web (web-app-suite.md). No backend of its own: every view reads
// and writes through the apex bridge (`lib/proxy.ts`), and the places stream is subscribed once,
// here, by `LiveProvider`. Scope is `maps-app.md` §Web scope → Decided: the place list with local
// search, the place detail (visits via Timeline), rename, the map and *Copy coordinates*. ⚠ No saved
// places / notes / lists (no store), no add-by-pin or "my location", no world search, no offline
// regions, no map-provider links, no Contribute, no photo strip.
export default function App() {
  // The open place is the URL fragment, `place=<id>` (`lib/nav.ts`), so a reload or Back keeps it.
  // The detail loads by id alone — nothing from the list click is needed to show it.
  const { fragment, open: openDetail, back } = useAppRoute(SECTIONS, 'map')
  const open = detailOf(fragment)
  const openId = open?.id ?? null
  // ⚠ The scroller is this <main>, not the window: the shell is a full-height column, so the
  // window never scrolls and `window.scrollY` reads 0 forever.
  const scroller = useRef<HTMLElement>(null)
  // Where the list was scrolled when a place opened; Back puts it back.
  const listScroll = useRef(0)

  const openPlace = useCallback((detail: Detail) => {
    listScroll.current = scroller.current?.scrollTop ?? 0
    // The detail takes over the list, so it is a history entry: browser Back closes it
    // (web-apps-layout.md §4.2).
    openDetail(fragmentOf(detail))
  }, [openDetail])

  // ⚠ After the commit that swaps the screens, so the list is laid out again before it scrolls.
  useLayoutEffect(() => {
    if (scroller.current) scroller.current.scrollTop = openId ? 0 : listScroll.current
  }, [openId])

  return (
    <LiveProvider>
      {/* No sections, so no sidebar (web-apps-layout.md §3.1): a full-window column whose <main>
          scrolls, so the list and its map get the whole width. No headline — the apex top bar
          already names the app. */}
      <div className="flex h-dvh flex-col bg-surface text-text">
        <main ref={scroller} className="min-h-0 flex-1 overflow-y-auto px-4 py-4 min-[900px]:px-7 min-[900px]:py-5">
          {/* First: in a tab without the runtime, every other read fails too. */}
          <RuntimeBanner />

          {/* ⚠ The list stays MOUNTED behind an open place: Back keeps its search,
              its scroll position and its map. Hidden, not unmounted. */}
          <div hidden={open !== null}>
            <ErrorBoundary>
              <PlacesView onOpen={openPlace} active={open === null} />
            </ErrorBoundary>
          </div>
          {/* Keyed by place, so a crashed detail recovers on navigation. */}
          {open && (
            <ErrorBoundary key={`place:${open.id}`}>
              <PlaceDetail placeId={open.id} onBack={() => back()} />
            </ErrorBoundary>
          )}
        </main>
      </div>
    </LiveProvider>
  )
}
