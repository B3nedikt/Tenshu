import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { ErrorBoundary } from './components/ErrorBoundary'
import { RuntimeBanner } from './components/RuntimeBanner'
import { SyncBanner } from './components/SyncBanner'
import { FeedView } from './components/FeedView'
import { AlbumsView } from './components/AlbumsView'
import { FavouritesView } from './components/FavouritesView'
import { TrashView } from './components/TrashView'
import { LiveProvider } from './lib/live'
import { FeedProvider } from './lib/feed'

type Tab = 'photos' | 'albums' | 'favourites' | 'trash'

const TABS: readonly Tab[] = ['photos', 'albums', 'favourites', 'trash']

// Photos on the web (web-app-suite.md Wave 2, Photos run B). No backend of its own: every view
// reads and writes through the apex bridge (`lib/proxy.ts`). The three pushed streams are
// subscribed once, here (`LiveProvider`), and the feed's pager lives here too (`FeedProvider`), so
// a tab switch keeps the library's place.
//
// ⚠ Not on web v1, by decision (photos-app.md §42.6): faces, labels, semantic search, upload,
// places, mint-on-view, duplicates and a hidden-photos surface.
export default function App() {
  const { t } = useTranslation()
  const [tab, setTab] = useState<Tab>('photos')

  return (
    <LiveProvider>
      <FeedProvider>
        <div className="flex h-screen flex-col bg-surface">
          <main className="mx-auto flex min-h-0 w-full max-w-6xl flex-1 flex-col px-6 py-6">
            <header className="mb-4">
              <h1 className="text-2xl font-semibold tracking-tight text-text">{t('app.title')}</h1>
              <p className="mt-1 text-sm text-text-2">{t('app.tagline')}</p>
            </header>

            <nav className="mb-4 flex gap-1 border-b border-line">
              {TABS.map((id) => (
                <button
                  key={id}
                  type="button"
                  onClick={() => setTab(id)}
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
            <SyncBanner />

            {/* Keyed by tab, so a crashed view recovers on navigation and the shell stays usable. */}
            <ErrorBoundary key={tab}>
              <div className="flex min-h-0 flex-1 flex-col">
                {tab === 'photos' && <FeedView />}
                {tab === 'albums' && <AlbumsView />}
                {tab === 'favourites' && <FavouritesView />}
                {tab === 'trash' && <TrashView />}
              </div>
            </ErrorBoundary>
          </main>
        </div>
      </FeedProvider>
    </LiveProvider>
  )
}
