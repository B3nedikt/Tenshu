import { useTranslation } from 'react-i18next'
import { AppLayout } from '@tenshu/app-kit/ui'
import { useAppRoute } from '@tenshu/app-kit/route'
import { ErrorBoundary } from './components/ErrorBoundary'
import { RuntimeBanner } from './components/RuntimeBanner'
import { SyncBanner } from './components/SyncBanner'
import { FeedView } from './components/FeedView'
import { YearsView } from './components/YearsView'
import { DENSITIES, type Density } from './lib/layout'
import { AlbumsView } from './components/AlbumsView'
import { FavouritesView } from './components/FavouritesView'
import { TrashView } from './components/TrashView'
import { LiveProvider } from './lib/live'
import { FeedProvider } from './lib/feed'
import { urlViewer } from './lib/viewerRoute'

type Tab = 'photos' | 'years' | 'videos' | 'albums' | 'favourites' | 'trash'

// Also the URL's sections (web-apps-layout.md §3.4): `photos` is `/`, the rest `/<id>` — they must
// equal the apex registry's `sections`. Module-level: its identity is an effect dependency.
const TABS: readonly Tab[] = ['photos', 'years', 'videos', 'albums', 'favourites', 'trash']
// The Library (sections `photos`) carries its density and where to open in the fragment:
// `view=days`, `view=years&at=120` (§3.4 — a fragment, never the path). Months is the default.
// The viewer adds `open=<feed index>` to whatever is there (`view=days&open=42`, `lib/viewerRoute`).
function libraryState(fragment: string): { density: Density; at: number | null } {
  const params = new URLSearchParams(fragment)
  const view = params.get('view')
  const density = (DENSITIES as readonly string[]).includes(view ?? '') ? (view as Density) : 'months'
  const at = Number.parseInt(params.get('at') ?? '', 10)
  return { density, at: Number.isFinite(at) && at >= 0 ? at : null }
}
function libraryFragment(density: Density, at: number | null = null): string {
  const params = new URLSearchParams()
  if (density !== 'months') params.set('view', density)
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
  const library = libraryState(tab === 'photos' ? fragment : '')
  // D6: the viewer takes over the content — opening it pushes, stepping within it replaces, its
  // close is `back`. The open photograph is `open=<index>` in the section's fragment.
  const viewer = urlViewer(fragment, { open, setFragment, back })
  // Albums: `a=<albumId>` is the open album (opening one pushes; its Back is `back`), and its
  // viewer adds `open=<index into the album>`. An opaque id, never the title.
  const openAlbum = tab === 'albums' ? new URLSearchParams(fragment).get('a') : null
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
          navLabel={t('nav.label')}
          nav={nav}
          navFooter={[{ id: 'trash', label: t('nav.trash') }]}
          active={tab}
          onNavigate={navigate}
          // First: in a tab without the runtime, every other read fails too.
          banners={
            <>
              <RuntimeBanner />
              <SyncBanner />
            </>
          }
        >
          {/* Keyed by tab, so a crashed view recovers on navigation and the shell stays usable. */}
          <ErrorBoundary key={tab}>
            <div className="flex min-h-0 flex-1 flex-col">
              {tab === 'photos' && (
                <FeedView
                  density={library.density}
                  // A density change is a view setting, not a place: no history entry.
                  onDensity={(density, topIndex) => setFragment(libraryFragment(density, topIndex))}
                  startAt={library.at}
                  viewer={viewer}
                />
              )}
              {tab === 'years' && (
                // A year opens the Library at its first photograph, by month — a history entry.
                <YearsView onOpenYear={(index) => navigate('photos', libraryFragment('months', index))} />
              )}
              {tab === 'videos' && (
                // The Videos collection (L5): the library's walk filtered to videos, its own pager
                // and times; by month, its viewer `open=<index into the videos>`.
                <FeedProvider kind="videos">
                  <FeedView viewer={viewer} emptyText={t('videos.empty')} />
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
        </AppLayout>
      </FeedProvider>
    </LiveProvider>
  )
}
