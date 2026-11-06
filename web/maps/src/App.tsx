import { useCallback, useLayoutEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { ErrorBoundary } from './components/ErrorBoundary'
import { RuntimeBanner } from './components/RuntimeBanner'
import { PlacesView } from './components/PlacesView'
import { PlaceDetail } from './components/PlaceDetail'
import { LiveProvider } from './lib/live'
import type { Detail } from './lib/nav'

// Maps on the web (web-app-suite.md). No backend of its own: every view reads
// and writes through the apex bridge (`lib/proxy.ts`), and the places stream is subscribed once,
// here, by `LiveProvider`. Scope is `maps-app.md` §Web scope → Decided: the place list with local
// search, the place detail (visits via Timeline), rename, the map and *Copy coordinates*. ⚠ No saved
// places / notes / lists (no store), no add-by-pin or "my location", no world search, no offline
// regions, no map-provider links, no Contribute, no photo strip.
export default function App() {
  const { t } = useTranslation()
  const [open, setOpen] = useState<Detail | null>(null)
  // Where the list was scrolled when a place opened; Back puts it back.
  const listScroll = useRef(0)

  const openPlace = useCallback((detail: Detail) => {
    listScroll.current = window.scrollY
    setOpen(detail)
  }, [])

  // ⚠ After the commit that swaps the screens, so the list is laid out again before it scrolls.
  useLayoutEffect(() => {
    window.scrollTo(0, open ? 0 : listScroll.current)
  }, [open])

  return (
    <LiveProvider>
      <div className="min-h-screen bg-surface">
        <main className="mx-auto max-w-3xl px-4 py-10 sm:px-6">
          <header className="mb-6">
            <h1 className="text-2xl font-semibold tracking-tight text-text">{t('app.title')}</h1>
            <p className="mt-1 text-sm text-text-2">{t('app.tagline')}</p>
          </header>

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
              <PlaceDetail placeId={open.id} onBack={() => setOpen(null)} />
            </ErrorBoundary>
          )}
        </main>
      </div>
    </LiveProvider>
  )
}
