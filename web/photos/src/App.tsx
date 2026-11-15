import { useCallback, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { photoSettings, setPhotoColumns, setPhotoDensity, setPhotoLayout } from './lib/proxy'
import { queryKeys } from './lib/queryKeys'
import { AppLayout } from '@tenshu/app-kit/ui'
import { useAppRoute } from '@tenshu/app-kit/route'
import { ErrorBoundary } from './components/ErrorBoundary'
import { RuntimeBanner } from './components/RuntimeBanner'
import { SyncBanner } from './components/SyncBanner'
import { FeedView } from './components/FeedView'
import { YearsView } from './components/YearsView'
import { SearchBox } from './components/SearchBox'
import { SearchView } from './components/SearchView'
import { DEFAULT_COLUMNS, DENSITIES, type Density } from './lib/layout'
import { AlbumsView } from './components/AlbumsView'
import { FavouritesView } from './components/FavouritesView'
import { TrashView } from './components/TrashView'
import { LiveProvider } from './lib/live'
import { FeedProvider } from './lib/feed'
import { urlViewer } from './lib/viewerRoute'
import { PaneContext, type PaneControl, type PhotoFocus } from './lib/pane'
import { loadPaneOpen, savePaneOpen } from './lib/paneState'
import { PhotoPane } from './components/PhotoPane'

type Tab = 'photos' | 'search' | 'years' | 'videos' | 'albums' | 'favourites' | 'trash'

// Also the URL's sections (web-apps-layout.md §3.4): `photos` is `/`, the rest `/<id>` — they must
// equal the apex registry's `sections`. Module-level: its identity is an effect dependency.
// `search` is reached from the sidebar's search field, not a nav item; its query is `q=` in the
// fragment (§3.4: what the user typed never goes in the path).
const TABS: readonly Tab[] = ['photos', 'search', 'years', 'videos', 'albums', 'favourites', 'trash']
// The sections with a photo grid — the ones the Info pane describes a selection in.
const GRID_TABS: readonly Tab[] = ['photos', 'search', 'videos', 'albums', 'favourites']
// The Library (sections `photos`) carries its density and where to open in the fragment:
// `view=days`, `view=years&at=120` (§3.4 — a fragment, never the path). Months is the default.
// The viewer adds `open=<feed index>` to whatever is there (`view=days&open=42`, `lib/viewerRoute`).
function libraryState(fragment: string): { density: Density | null; at: number | null } {
  const params = new URLSearchParams(fragment)
  const view = params.get('view')
  // null = the URL names none: the browser's remembered density applies (L6).
  const density = (DENSITIES as readonly string[]).includes(view ?? '') ? (view as Density) : null
  const at = Number.parseInt(params.get('at') ?? '', 10)
  return { density, at: Number.isFinite(at) && at >= 0 ? at : null }
}
function libraryFragment(density: Density, at: number | null = null): string {
  const params = new URLSearchParams()
  // Always named: with a remembered default (L6), an absent `view=` means "the default", not Months.
  params.set('view', density)
  if (at !== null) params.set('at', String(at))
  return params.toString()
}

// Photos on the web (web-app-suite.md Wave 2, Photos run B). No backend of its own: every view
// reads and writes through the apex bridge (`lib/proxy.ts`). The three pushed streams are
// subscribed once, here (`LiveProvider`), and the feed's pager lives here too (`FeedProvider`), so
// a tab switch keeps the library's place.
//
// ⚠ Not on web v1, by decision (photos-app.md §42.6): faces, labels, semantic search, upload,
// places, mint-on-view, duplicates and a hidden-photos surface.
export default function App() {
  const { t } = useTranslation()
  const { section: tab, fragment, navigate, setFragment, open, back } = useAppRoute(TABS, 'photos')
  const searched = tab === 'search' ? (new URLSearchParams(fragment).get('q') ?? '') : ''
  // From another section a search is a new place (a history entry); refining it replaces in place.
  // ⚠ Emptying the field CLOSES the search (as iCloud's): back to where it was opened from — the
  // entry it pushed — or, after a reload or deep link, to the Library. A search page with nothing
  // typed is a dead end with no results and no way back but the sidebar.
  const onSearch = (query: string) => {
    const next = query.trim() ? new URLSearchParams({ q: query }).toString() : ''
    if (tab === 'search') {
      if (next) setFragment(next)
      else back('', 'photos')
    } else if (next) navigate('search', next)
  }
  const library = libraryState(tab === 'photos' ? fragment : '')
  // L6: this browser's remembered density (DEVICE scope) when the URL names none; Months otherwise.
  const queryClient = useQueryClient()
  const remembered = useQuery({ queryKey: queryKeys.photoSettings(), queryFn: photoSettings, staleTime: Infinity }).data
  const density: Density = library.density ?? (remembered?.ok ? remembered.density : 'months')
  // §4.9: the mosaic and whole-photo switches, this browser's too — off until chosen.
  const mosaic = remembered?.ok ? (remembered.mosaic ?? false) : false
  const fit = remembered?.ok ? (remembered.fit ?? false) : false
  // §4.10: the size slider's photos per row (an apex older than the app answers none: the default).
  const columns = remembered?.ok ? (remembered.columns ?? DEFAULT_COLUMNS) : DEFAULT_COLUMNS
  // A change is written to the cached settings first (it drives the view now), merged into what is
  // cached, so a layout switch never resets the density or the other way round. The read still in
  // flight is cancelled so it cannot land after the change (App.test pins the outcome; this
  // react-query already keeps the manual write, so the cancel is belt and braces).
  const remember = (patch: { density?: Density; mosaic?: boolean; fit?: boolean; columns?: number }) => {
    void queryClient.cancelQueries({ queryKey: queryKeys.photoSettings() })
    queryClient.setQueryData(queryKeys.photoSettings(), (old: Awaited<ReturnType<typeof photoSettings>> | undefined) => ({
      ok: true as const,
      density: old?.ok ? old.density : 'months',
      mosaic: old?.ok ? (old.mosaic ?? false) : false,
      fit: old?.ok ? (old.fit ?? false) : false,
      columns: old?.ok ? (old.columns ?? DEFAULT_COLUMNS) : DEFAULT_COLUMNS,
      ...patch,
    }))
  }
  // D6: the viewer takes over the content — opening it pushes, stepping within it replaces, its
  // close is `back`. The open photograph is `open=<index>` in the section's fragment.
  const viewer = urlViewer(fragment, { open, setFragment, back })
  // Albums: `a=<albumId>` is the open album (opening one pushes; its Back is `back`), and its
  // viewer adds `open=<index into the album>`. An opaque id, never the title.
  const openAlbum = tab === 'albums' ? new URLSearchParams(fragment).get('a') : null
  // The Info pane (D2): stays as you left it, per browser; describes the one selected photograph,
  // which the grid reports (`lib/pane`) — lifted here because the pane is AppLayout's.
  const [paneOpen, setPaneOpen] = useState(loadPaneOpen)
  const [focus, setFocus] = useState<PhotoFocus | null>(null)
  const togglePane = useCallback(() => {
    const next = !paneOpen
    setPaneOpen(next)
    savePaneOpen(next)
  }, [paneOpen])
  const pane = useMemo<PaneControl>(() => ({ open: paneOpen, toggle: togglePane, onFocusChange: setFocus }), [paneOpen, togglePane])
  const nav = [
    { id: 'photos' as const, label: t('nav.photos') },
    { id: 'years' as const, label: t('nav.years'), heading: t('nav.collections') },
    { id: 'videos' as const, label: t('nav.videos') },
    { id: 'albums' as const, label: t('nav.albums') },
    { id: 'favourites' as const, label: t('nav.favourites') },
  ]

  return (
    <LiveProvider>
      <FeedProvider>
        {/* No headline: the apex top bar already names the app (web-apps-layout.md §3.1). */}
        <AppLayout
          // Never keyed: a remount would drop focus mid-typing. It follows the URL itself (Back/forward).
          primaryAction={<SearchBox initial={searched} section={tab} onSearch={onSearch} />}
          navLabel={t('nav.label')}
          nav={nav}
          navFooter={[{ id: 'trash', label: t('nav.trash') }]}
          active={tab}
          onNavigate={navigate}
          pane={paneOpen && GRID_TABS.includes(tab) ? <PhotoPane focus={focus} /> : null}
          paneLabel={t('pane.label')}
          onPaneClose={togglePane}
          // First: in a tab without the runtime, every other read fails too.
          banners={
            <>
              <RuntimeBanner />
              <SyncBanner />
            </>
          }
        >
          {/* Keyed by tab, so a crashed view recovers on navigation and the shell stays usable. */}
          <PaneContext value={pane}>
          <ErrorBoundary key={tab}>
            <div className="flex min-h-0 flex-1 flex-col">
              {tab === 'photos' && (
                <FeedView
                  density={density}
                  // A density change is a view setting, not a place: no history entry. It is also
                  // this browser's new default (best effort: no sync identity yet keeps the old one).
                  onDensity={(next, topIndex) => {
                    setFragment(libraryFragment(next, topIndex))
                    // ⚠ The cached setting first: a later visit with no `view=` (the Library nav)
                    // must open at this choice, not the value read at the session's start.
                    remember({ density: next })
                    void setPhotoDensity(next).catch(() => undefined)
                  }}
                  mosaic={mosaic}
                  fit={fit}
                  // Same rule: the cached setting first (it drives the grid now), then the store.
                  onLayout={(nextMosaic, nextFit) => {
                    remember({ mosaic: nextMosaic, fit: nextFit })
                    void setPhotoLayout(nextMosaic, nextFit).catch(() => undefined)
                  }}
                  columns={columns}
                  onColumns={(next) => {
                    remember({ columns: next })
                    void setPhotoColumns(next).catch(() => undefined)
                  }}
                  startAt={library.at}
                  viewer={viewer}
                />
              )}
              {tab === 'search' && <SearchView query={searched} viewer={viewer} />}
              {tab === 'years' && (
                // A year opens the Library at its first photograph, by month — a history entry.
                <YearsView onOpenYear={(index) => navigate('photos', libraryFragment('months', index))} />
              )}
              {tab === 'videos' && (
                // The Videos collection (L5): the library's walk filtered to videos, its own pager
                // and times; by month, its viewer `open=<index into the videos>`.
                <FeedProvider kind="videos">
                  <FeedView viewer={viewer} emptyText={t('videos.empty')} fit={fit} columns={columns} />
                </FeedProvider>
              )}
              {tab === 'albums' && (
                <AlbumsView
                  albumId={openAlbum}
                  onAlbum={(id) => (id === null ? back('') : open(new URLSearchParams({ a: id }).toString()))}
                  viewer={viewer}
                />
              )}
              {tab === 'favourites' && <FavouritesView viewer={viewer} />}
              {tab === 'trash' && <TrashView />}
            </div>
          </ErrorBoundary>
          </PaneContext>
        </AppLayout>
      </FeedProvider>
    </LiveProvider>
  )
}
