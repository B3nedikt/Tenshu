import { useState } from 'react'
import { ErrorBoundary } from './components/ErrorBoundary'
import { FileBrowser } from './components/FileBrowser'
import { SearchView } from './components/SearchView'
import { SyncBanner } from './components/SyncBanner'
import { RuntimeBanner } from './components/RuntimeBanner'
import { HomeView } from './components/HomeView'
import { SharedFoldersView } from './components/SharedFoldersView'
import { PeopleView } from './components/PeopleView'
import { SharedWithMeView } from './components/SharedWithMeView'
import { SettingsView } from './components/SettingsView'

type Tab = 'home' | 'files' | 'search' | 'shared' | 'people' | 'incoming' | 'settings'

const TABS: ReadonlyArray<{ id: Tab; label: string }> = [
  { id: 'home', label: 'Home' },
  { id: 'files', label: 'Files' },
  { id: 'search', label: 'Search' },
  { id: 'shared', label: 'Shared' },
  { id: 'people', label: 'People' },
  { id: 'incoming', label: 'Shared with me' },
  { id: 'settings', label: 'Settings' },
]

// Drive on the web (Phases C1–C5). The subdomain has no backend of its own —
// every view reads/writes through the apex postMessage bridge
// (docs/proposals/drive-web-app.md §6, Phase C). Files are empty until the apex
// tab is signed in; the UI works over the bridge regardless.
export default function App() {
  const [tab, setTab] = useState<Tab>('home')
  // The folder a search result asked us to open; also the FileBrowser's remount key.
  const [landing, setLanding] = useState('/')

  return (
    <div className="min-h-screen bg-surface">
      <main className="mx-auto max-w-3xl px-6 py-10">
        <header className="mb-6">
          <h1 className="text-2xl font-semibold tracking-tight text-text">Drive</h1>
          <p className="mt-1 text-sm text-text-2">Your end-to-end encrypted files.</p>
        </header>

        <nav className="mb-5 flex gap-1 border-b border-line">
          {TABS.map(({ id, label }) => (
            <TabButton key={id} active={tab === id} onClick={() => setTab(id)}>
              {label}
            </TabButton>
          ))}
        </nav>

        {/* Above the boundary and outside the tab key: a device that cannot sync is a
            property of the app, not of whichever tab you happen to be on. */}
        {/* First: in a tab without the runtime, every other banner's read fails too. */}
        <RuntimeBanner />
        <SyncBanner />

        {/* Keyed by tab so switching tabs remounts the boundary — a crashed
            view auto-recovers on navigation, and the shell/nav stay usable. */}
        <ErrorBoundary key={tab}>
          {tab === 'home' && <HomeView />}
          {/* Keyed by the folder a search hit asked for, so landing somewhere new
              remounts the browser at that path instead of keeping its old one. */}
          {tab === 'files' && <FileBrowser key={landing} initialPath={landing} />}
          {tab === 'search' && (
            <SearchView
              onOpenFolder={(folder) => {
                setLanding(folder)
                setTab('files')
              }}
            />
          )}
          {tab === 'shared' && <SharedFoldersView />}
          {tab === 'people' && <PeopleView />}
          {tab === 'incoming' && <SharedWithMeView />}
          {tab === 'settings' && <SettingsView />}
        </ErrorBoundary>
      </main>
    </div>
  )
}

function TabButton({
  active,
  onClick,
  children,
}: {
  active: boolean
  onClick: () => void
  children: React.ReactNode
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-current={active ? 'page' : undefined}
      className={`-mb-px border-b-2 px-3 py-2 text-sm font-medium ${
        active
          ? 'border-primary text-primary'
          : 'border-transparent text-text-2 hover:text-text'
      }`}
    >
      {children}
    </button>
  )
}
