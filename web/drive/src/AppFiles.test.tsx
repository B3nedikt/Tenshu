// Drive's files section as the App wires it (web-apps-layout.md L3): "+ New" (the add-file sheet),
// the sticky details pane (D2), and the viewer in the URL (D6).
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const listFiles = vi.fn()
const createFolder = vi.fn()
const uploadFile = vi.fn()
const details = vi.fn()
const activity = vi.fn()
const getData = vi.fn()
vi.mock('./lib/proxy', () => ({
  listFiles: (path: string) => listFiles(path),
  createFolder: (path: string) => createFolder(path),
  uploadFile: (...args: unknown[]) => uploadFile(...args),
  details: (path: string) => details(path),
  activity: (path: string) => activity(path),
  getData: (path: string) => getData(path),
  getPreview: async () => ({ ok: true, bytes: null }),
  getLargePreview: async () => ({ ok: true, bytes: null, thumbnailClass: null, coveringRung: 'absent' }),
  recentFiles: async () => [],
  listShares: async () => [],
  syncHealth: async () => ({ connectivity: 'online', isSyncing: false, pendingBlockCount: 0, lastSyncedAtMillis: Date.now() }),
  schemaFence: async () => ({ fenced: false, serviceIds: [] }),
  syncNow: async () => true,
  subscribeSyncHealth: () => () => undefined,
  runtimeLease: async () => 'held',
  runtimeUseHere: async () => undefined,
  APEX: 'http://localhost:5173',
}))

const App = (await import('./App')).default

const file = (path: string) => ({ path, size: 100, lastModified: 0, createdAt: 0, isFolder: false, itemCount: 0 })

function renderApp() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <App />
    </QueryClientProvider>,
  )
}

// This jsdom has no Storage; the pane's open state needs one to be observable.
const store = new Map<string, string>()
vi.stubGlobal('localStorage', {
  getItem: (k: string) => store.get(k) ?? null,
  setItem: (k: string, v: string) => void store.set(k, v),
  removeItem: (k: string) => void store.delete(k),
  clear: () => store.clear(),
})

beforeEach(() => {
  window.history.replaceState(null, '', '/files')
  store.clear()
  details.mockResolvedValue({ ok: true, row: { path: '/a.txt', size: 100, createdAt: 0, lastModified: 0 } })
  activity.mockResolvedValue([])
})
afterEach(() => vi.clearAllMocks())

describe('Drive files — + New', () => {
  it('creates a folder in the open folder', async () => {
    window.history.replaceState(null, '', '/files#/Docs')
    listFiles.mockResolvedValue([])
    createFolder.mockResolvedValue(true)
    renderApp()
    await userEvent.click(screen.getByRole('button', { name: '+ New' }))
    await userEvent.click(await screen.findByRole('menuitem', { name: 'New folder' }))
    await userEvent.type(screen.getByLabelText('Folder name'), 'Taxes')
    await userEvent.click(screen.getByRole('button', { name: 'Create' }))
    await waitFor(() => expect(createFolder).toHaveBeenCalledWith('/Docs/Taxes'))
  })

  it('recreates a picked folder’s tree, parents before children', async () => {
    listFiles.mockResolvedValue([])
    createFolder.mockResolvedValue(true)
    uploadFile.mockResolvedValue(true)
    renderApp()
    const input = screen.getByTestId('upload-folder') as HTMLInputElement
    const pick = (relative: string) => {
      const f = new File(['x'], relative.split('/').pop()!, { type: 'text/plain' })
      Object.defineProperty(f, 'webkitRelativePath', { value: relative })
      return f
    }
    Object.defineProperty(input, 'files', {
      value: [pick('trip/photos/a.jpg'), pick('trip/notes.txt'), pick('trip/photos/raw/b.dng')],
      configurable: true,
    })
    input.dispatchEvent(new Event('change', { bubbles: true }))
    await waitFor(() => expect(uploadFile).toHaveBeenCalledTimes(3))
    // ⚠⚠ Parents before children; every ancestor exactly once.
    expect(createFolder.mock.calls.map((c) => c[0])).toEqual(['/trip', '/trip/photos', '/trip/photos/raw'])
    const targets = uploadFile.mock.calls.map((c) => [c[0], c[1]])
    expect(targets).toContainEqual(['/trip/photos', 'a.jpg'])
    expect(targets).toContainEqual(['/trip', 'notes.txt'])
  })
})

describe('Drive files — the details pane', () => {
  it('is closed until ⓘ, then shows the selection, and stays open across a reload', async () => {
    listFiles.mockResolvedValue([file('/a.txt')])
    renderApp()
    await userEvent.click(await screen.findByText('a.txt'))
    expect(screen.queryByRole('complementary', { name: 'Details' })).toBeNull()

    await userEvent.click(screen.getByRole('button', { name: 'Details' }))
    const pane = screen.getByRole('complementary', { name: 'Details' })
    expect(await within(pane).findByRole('region', { name: 'File details' })).toBeInTheDocument()
    expect(details).toHaveBeenCalledWith('/a.txt')

    // D2: it stays as you left it.
    expect(window.localStorage.getItem('tenshu-drive-pane')).toBe('open')
  })

  it('says what to do when nothing is selected', async () => {
    window.localStorage.setItem('tenshu-drive-pane', 'open')
    listFiles.mockResolvedValue([file('/a.txt')])
    renderApp()
    expect(await screen.findByText('Select a file to see its details.')).toBeInTheDocument()
  })
})

describe('Drive files — the viewer in the URL (D6)', () => {
  it('a double-click opens the viewer and puts the file in the fragment, never the path', async () => {
    window.history.replaceState(null, '', '/files#/Docs')
    listFiles.mockResolvedValue([file('/Docs/a.txt'), file('/Docs/b.txt')])
    getData.mockResolvedValue({ ok: true, bytes: new Int8Array([104, 105]) })
    renderApp()
    await userEvent.dblClick(await screen.findByText('a.txt'))
    expect(await screen.findByRole('dialog', { name: 'a.txt' })).toBeInTheDocument()
    expect(window.location.pathname).toBe('/files')
    expect(decodeURI(window.location.hash)).toBe('#view:/Docs/a.txt')

    // Moving inside the viewer changes the file in place; closing returns to the folder.
    await userEvent.keyboard('{ArrowRight}')
    await waitFor(() => expect(decodeURI(window.location.hash)).toBe('#view:/Docs/b.txt'))
    await userEvent.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('dialog', { name: /\.txt$/ })).toBeNull())
    expect(decodeURI(window.location.hash)).toBe('#/Docs')
  })

  it('a URL naming a file opens it in the viewer', async () => {
    window.history.replaceState(null, '', '/files#view:/Docs/a.txt')
    listFiles.mockResolvedValue([file('/Docs/a.txt')])
    getData.mockResolvedValue({ ok: true, bytes: new Int8Array([104, 105]) })
    renderApp()
    expect(await screen.findByRole('dialog', { name: 'a.txt' })).toBeInTheDocument()
    expect(listFiles).toHaveBeenCalledWith('/Docs')
  })
})
