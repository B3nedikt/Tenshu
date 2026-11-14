import { useCallback, useState, type DragEvent } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import type { File as FsEntry } from '@tenshu/sdk/files'
import { AppLayout, type AppNavItem } from '@tenshu/app-kit/ui'
import { useAppRoute } from '@tenshu/app-kit/route'
import { PromptDialog } from './components/Dialog'
import { FileDetails } from './components/FileDetails'
import { NewMenu } from './components/NewMenu'
import { createFolder } from './lib/proxy'
import { basename, isFolder, joinPath, parentDir } from './lib/paths'
import { loadPaneOpen, savePaneOpen } from './lib/paneState'
import { queryKeys } from './lib/queryKeys'
import { useUploader } from './lib/useUploader'
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

const SECTIONS: ReadonlyArray<AppNavItem<Tab>> = [
  { id: 'home', label: 'Home' },
  { id: 'files', label: 'My files' },
  { id: 'search', label: 'Search' },
  { id: 'shared', label: 'Shared links' },
  { id: 'incoming', label: 'Shared with me' },
  { id: 'people', label: 'People' },
]
const FOOTER: ReadonlyArray<AppNavItem<Tab>> = [{ id: 'settings', label: 'Settings' }]
// The section ids — the apex registry's `sections` for Drive, with 'home' as `/`. ⚠ Module-level:
// its identity is an effect dependency of useAppRoute.
const TAB_IDS: readonly Tab[] = [...SECTIONS, ...FOOTER].map((s) => s.id)

// The files section's fragment: the open folder (`/Documents`), or a file open in the viewer
// (`view:/Documents/a.jpg` — a path always starts with `/`, so the prefix cannot be a folder).
const VIEW = 'view:'

// Drive on the web (Phases C1–C5). The subdomain has no backend of its own —
// every view reads/writes through the apex postMessage bridge
// (docs/proposals/drive-web-app.md §6, Phase C). Files are empty until the apex
// tab is signed in; the UI works over the bridge regardless.
export default function App() {
  // The section is the URL path, the open folder its fragment (`/files#/Documents/Taxes`) — so a
  // reload, Back and a shared link keep the place (web-apps-layout.md §3.4). ⚠⚠ A folder name goes
  // in the fragment only: the path reaches the app and apex hosts' logs, the fragment never does.
  const route = useAppRoute(TAB_IDS, 'home')
  const tab = route.section
  const inFiles = route.section === 'files'
  const viewing = inFiles && route.fragment.startsWith(VIEW) ? route.fragment.slice(VIEW.length) : null
  const folder = viewing ? parentDir(viewing) : inFiles && route.fragment.startsWith('/') ? route.fragment : '/'
  const folderFragment = (path: string) => (path === '/' ? '' : path)
  // Opening a folder is a new history entry, so Back returns to the folder you came from.
  const openFolder = (path: string) => route.navigate('files', folderFragment(path))
  // D6: opening the viewer takes over the content, so it is a history entry; stepping through the
  // folder inside it is not; closing steps back (or, for a reloaded viewer, replaces).
  const onView = (path: string | null, how: 'open' | 'move' | 'close') => {
    if (how === 'open' && path) route.open(VIEW + path)
    else if (how === 'move' && path) route.setFragment(VIEW + path)
    else route.back(folderFragment(folder))
  }

  // The details pane (D2): stays as you left it; describes the one selected entry.
  const [paneOpen, setPaneOpen] = useState(loadPaneOpen)
  const togglePane = useCallback(() => {
    setPaneOpen((open) => {
      savePaneOpen(!open)
      return !open
    })
  }, [])
  const [focused, setFocused] = useState<FsEntry | null>(null)
  const details = (entry: FsEntry) =>
    // Keyed by path: a Download in flight (or its error) belongs to that file, not the next one.
    isFolder(entry) ? <FolderSummary key={entry.path} entry={entry} /> : <FileDetails key={entry.path} path={entry.path} onClose={togglePane} />

  // "+ New" (the add-file sheet) and dropping files anywhere: into the open folder in My files,
  // else into My files' root — and show it there.
  const queryClient = useQueryClient()
  const target = inFiles ? folder : '/'
  const uploader = useUploader((parent) => queryClient.invalidateQueries({ queryKey: queryKeys.files(parent) }))
  const upload = (files: readonly File[]) => {
    uploader.upload(target, files)
    if (!inFiles) route.navigate('files')
  }
  const [naming, setNaming] = useState(false)
  async function onCreateFolder(name: string): Promise<string | null> {
    const ok = await createFolder(joinPath(target, name))
    if (!ok) return `Couldn't create “${name}” — it may already exist.`
    queryClient.invalidateQueries({ queryKey: queryKeys.files(target) })
    if (!inFiles) route.navigate('files')
    return null
  }
  const [dropping, setDropping] = useState(false)
  const [dropNotice, setDropNotice] = useState<string | null>(null)
  const carriesFiles = (event: DragEvent) => event.dataTransfer.types.includes('Files')

  // No headline: the apex top bar already names the app (web-apps-layout.md §3.1).
  return (
    <div
      onDragOver={(event) => {
        if (!carriesFiles(event)) return
        event.preventDefault()
        setDropping(true)
      }}
      onDragLeave={(event) => {
        // Only when the drag leaves the app (or is cancelled): moving between children also fires
        // dragleave, with the child as target — and the wrapper is entirely covered by them.
        if (!event.currentTarget.contains(event.relatedTarget as Node | null)) setDropping(false)
      }}
      onDrop={(event) => {
        if (!carriesFiles(event)) return
        event.preventDefault()
        setDropping(false)
        // Files only: a dropped FOLDER arrives as a File with no readable bytes. Classified by its
        // entry, not by size or type (an empty `.gitkeep` is a real file; a folder can report 4096).
        const items = Array.from(event.dataTransfer.items ?? [])
        const isDirectory = (index: number) => items[index]?.webkitGetAsEntry?.()?.isDirectory === true
        const all = Array.from(event.dataTransfer.files)
        const files = all.filter((_, index) => !isDirectory(index))
        setDropNotice(
          files.length < all.length ? 'Folders can’t be dropped here — use + New → Upload folder.' : null,
        )
        upload(files)
      }}
    >
      <AppLayout
        navLabel="Drive sections"
        nav={SECTIONS}
        navFooter={FOOTER}
        active={tab}
        onNavigate={(next) => route.navigate(next)}
        primaryAction={
          <NewMenu
            onNewFolder={() => setNaming(true)}
            onUpload={upload}
            progress={uploader.progress}
            busy={uploader.pending}
          />
        }
        pane={
          inFiles && paneOpen ? (
            focused ? (
              details(focused)
            ) : (
              <p className="text-sm text-text-3">Select a file to see its details.</p>
            )
          ) : null
        }
        paneLabel="Details"
        onPaneClose={togglePane}
        // Above the boundary and outside the tab key: a device that cannot sync is a property of
        // the app, not of whichever section you happen to be on. RuntimeBanner first — in a tab
        // without the runtime, every other banner's read fails too.
        banners={
          <>
            <RuntimeBanner />
            <SyncBanner />
            {uploader.error && (
              <p role="alert" className="mb-3 rounded-lg bg-danger/10 px-4 py-2 text-sm text-danger">
                {uploader.error}
              </p>
            )}
            {dropNotice && (
            <p role="status" className="mb-3 rounded-lg bg-surface-2 px-4 py-2 text-sm text-text-2">
              {dropNotice}
            </p>
          )}
          {dropping && (
              <p className="mb-3 rounded-lg border-2 border-dashed border-primary px-4 py-3 text-center text-sm text-text">
                Drop to upload to {target === '/' ? 'My files' : basename(target)}
              </p>
            )}
          </>
        }
      >
        {/* Keyed by tab so switching sections remounts the boundary — a crashed view
            auto-recovers on navigation, and the shell/nav stay usable. */}
        <ErrorBoundary key={tab}>
          {tab === 'home' && <HomeView />}
          {tab === 'files' && (
            <FileBrowser
              path={folder}
              onPathChange={openFolder}
              viewing={viewing}
              onView={onView}
              paneOpen={paneOpen}
              onTogglePane={togglePane}
              onFocusChange={setFocused}
              details={details}
            />
          )}
          {tab === 'search' && <SearchView onOpenFolder={openFolder} />}
          {tab === 'shared' && <SharedFoldersView />}
          {tab === 'people' && <PeopleView />}
          {tab === 'incoming' && <SharedWithMeView />}
          {tab === 'settings' && <SettingsView />}
        </ErrorBoundary>
      </AppLayout>
      {naming && (
        <PromptDialog
          title="New folder"
          label="Folder name"
          submitLabel="Create"
          onSubmit={onCreateFolder}
          onClose={() => setNaming(false)}
        />
      )}
    </div>
  )
}

function FolderSummary({ entry }: { entry: FsEntry }) {
  return (
    <section aria-label="Folder details" className="space-y-2 text-sm">
      <h2 className="text-base font-semibold text-text">{basename(entry.path)}</h2>
      <p className="text-text-2">
        Folder · {entry.itemCount} item{entry.itemCount === 1 ? '' : 's'}
      </p>
    </section>
  )
}
