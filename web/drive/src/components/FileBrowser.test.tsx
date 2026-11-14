import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { FileBrowser } from './FileBrowser'

// Mock only the bridge proxy — the real `@tenshu/sdk/files` `isFolder` runs so
// folder/file discrimination is exercised for real.
const listFiles = vi.fn()
const uploadFile = vi.fn()
const details = vi.fn()
const activity = vi.fn()
const createFolder = vi.fn()
const rename = vi.fn()
const deleteEntry = vi.fn()
const move = vi.fn()
const createShareLink = vi.fn()
const getData = vi.fn()
const getLargePreview = vi.fn()
vi.mock('../lib/proxy', () => ({
  listFiles: (path: string) => listFiles(path),
  uploadFile: (...args: unknown[]) => uploadFile(...args),
  details: (path: string) => details(path),
  activity: (path: string) => activity(path),
  createFolder: (path: string) => createFolder(path),
  rename: (...args: unknown[]) => rename(...args),
  deleteEntry: (path: string) => deleteEntry(path),
  move: (...args: unknown[]) => move(...args),
  createShareLink: (...args: unknown[]) => createShareLink(...args),
  // Row click opens a file → the opener fetches its bytes via getData.
  getData: (path: string) => getData(path),
  // FileThumbnail (row icon) fetches this for image entries — no thumbnail here.
  getPreview: () => ({ ok: true, bytes: null }),
  // X-5: an image open asks for the large classes first; nothing stored here, so it falls
  // through to `getData` exactly as before.
  getLargePreview: (path: string, px: number) => getLargePreview(path, px),
}))

function folder(path: string, childCount = 0) {
  // W11: a listing row is flat — `isFolder` + `itemCount`, not a nested `files` array.
  return { path, size: 0, lastModified: 0, createdAt: 0, isFolder: true, itemCount: childCount }
}

function file(path: string, size = 100) {
  return { path, size, lastModified: Date.UTC(2024, 0, 1), createdAt: 0, isFolder: false, itemCount: 0 }
}

function renderBrowser() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <FileBrowser />
    </QueryClientProvider>,
  )
}

afterEach(() => {
  listFiles.mockReset()
  uploadFile.mockReset()
  details.mockReset()
  activity.mockReset()
  createFolder.mockReset()
  rename.mockReset()
  deleteEntry.mockReset()
  move.mockReset()
  createShareLink.mockReset()
  getData.mockReset()
  // X-5 default: nothing stored large, so an image open falls through to `getData`.
  getLargePreview.mockReset()
  getLargePreview.mockResolvedValue({ ok: true, bytes: null, thumbnailClass: null, coveringRung: 'absent' })
})

describe('FileBrowser', () => {
  it('renders folders before files, each group alphabetical', async () => {
    listFiles.mockResolvedValueOnce([file('/zeta.txt'), folder('/Photos'), folder('/Albums')])
    renderBrowser()

    await screen.findByText('Albums')
    const names = screen
      .getAllByText(/^(Albums|Photos|zeta\.txt)$/)
      .map((el) => el.textContent)
    expect(names).toEqual(['Albums', 'Photos', 'zeta.txt'])
  })

  // ⚠⚠ Folders stay first under EVERY key and direction — the chosen key orders only within a
  // group. This is the invariant that makes Drive's listing unpageable (a page is a slice, and
  // `foldersFirst` reorders the whole list), so it is worth pinning rather than assuming.
  it('keeps folders first even when sorting by size, descending', async () => {
    const user = userEvent.setup()
    listFiles.mockResolvedValue([
      { ...file('/big.bin'), size: 9_000_000 },
      folder('/Albums'),
      { ...file('/small.txt'), size: 1 },
    ])
    renderBrowser()
    await screen.findByText('Albums')

    await user.selectOptions(screen.getByLabelText('Sort by'), 'size')

    const names = screen
      .getAllByText(/^(Albums|big\.bin|small\.txt)$/)
      .map((el) => el.textContent)
    // Folder first despite having size 0; files then descend by size.
    expect(names).toEqual(['Albums', 'big.bin', 'small.txt'])
  })

  it('navigates into a folder on double-click and lists its contents', async () => {
    listFiles.mockResolvedValueOnce([folder('/Photos')]) // root
    listFiles.mockResolvedValueOnce([file('/Photos/a.jpg')]) // inside Photos
    renderBrowser()

    await userEvent.dblClick(await screen.findByText('Photos'))

    await screen.findByText('a.jpg')
    expect(listFiles).toHaveBeenCalledWith('/Photos')
    // Breadcrumb trail now shows the folder.
    expect(screen.getByRole('navigation', { name: 'Breadcrumb' })).toHaveTextContent('Photos')
  })

  it('controlled: opening a folder asks the owner to move, and follows the path it is given', async () => {
    listFiles.mockImplementation(async (path: string) => (path === '/' ? [folder('/Photos')] : [file('/Photos/a.jpg')]))
    const onPathChange = vi.fn()
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const view = (path: string) => (
      <QueryClientProvider client={queryClient}>
        <FileBrowser path={path} onPathChange={onPathChange} />
      </QueryClientProvider>
    )
    const { rerender } = render(view('/'))
    await userEvent.dblClick(await screen.findByText('Photos'))
    expect(onPathChange).toHaveBeenCalledWith('/Photos')
    // The owner (App, via the URL) decides; until it does, the browser stays put.
    expect(screen.queryByText('a.jpg')).toBeNull()
    rerender(view('/Photos'))
    expect(await screen.findByText('a.jpg')).toBeInTheDocument()
  })

  it('navigates back up via a breadcrumb', async () => {
    listFiles.mockResolvedValueOnce([folder('/Photos')]) // root
    listFiles.mockResolvedValueOnce([file('/Photos/a.jpg')]) // Photos
    listFiles.mockResolvedValueOnce([folder('/Photos')]) // back at root
    renderBrowser()

    await userEvent.dblClick(await screen.findByText('Photos'))
    await screen.findByText('a.jpg')
    await userEvent.click(screen.getByRole('button', { name: 'Drive' }))

    await screen.findByText('Photos')
    expect(listFiles).toHaveBeenLastCalledWith('/')
  })

  // ⚠⚠ X-5's skew case. `fs.getLargePreview` is a NEW bridge method, and the apex a browser has
  // cached may predate it — an unknown method rejects, and can do so synchronously, before any
  // promise exists. Opening a file is the app's primary action and worked before X-5, so a
  // version-behind apex must cost the sharper preview, never the open itself.
  it('still opens the original when the apex does not know getLargePreview', async () => {
    listFiles.mockResolvedValueOnce([file('/a.png')])
    getLargePreview.mockImplementation(() => {
      throw new TypeError('getLargePreview is not a function')
    })
    getData.mockResolvedValueOnce({ ok: true, bytes: new Int8Array([1, 2, 3]) })
    vi.stubGlobal('URL', {
      ...URL,
      createObjectURL: vi.fn(() => 'blob:p'),
      revokeObjectURL: vi.fn(),
    })
    renderBrowser()

    await userEvent.dblClick(await screen.findByText('a.png'))

    await waitFor(() => expect(getData).toHaveBeenCalledWith('/a.png'))
    expect(await screen.findByRole('img', { name: 'a.png' })).toHaveAttribute('src', 'blob:p')
    vi.unstubAllGlobals()
  })

  // ⚠⚠ X-5's happy path, which had NO test at all: every mock answered `bytes: null`, so nothing
  // asserted that a present rung is drawn, nor — the actual point of the phase — that the whole
  // original is NOT fetched when one is.
  it('draws the large rung and does not fetch the original when one is present', async () => {
    listFiles.mockResolvedValueOnce([file('/a.png')])
    getLargePreview.mockResolvedValue({
      ok: true,
      bytes: new Int8Array([0xff, 0xd8, 0xff, 0xe0, 9]),
      thumbnailClass: 'xxlarge',
      coveringRung: 'present',
    })
    vi.stubGlobal('URL', {
      ...URL,
      createObjectURL: vi.fn(() => 'blob:rung'),
      revokeObjectURL: vi.fn(),
    })
    renderBrowser()

    await userEvent.dblClick(await screen.findByText('a.png'))

    const img = await screen.findByRole('img', { name: 'a.png' })
    expect(img).toHaveAttribute('src', 'blob:rung')
    expect(getLargePreview).toHaveBeenCalledWith('/a.png', 2048)
    expect(getData).not.toHaveBeenCalled()
    vi.unstubAllGlobals()
  })

  // ⚠⚠ The data-loss guard. What is on screen is a 2048 px JPEG, not the file — so Download must
  // fetch the real bytes. Deleting the `standIn` branch makes Download an `<a href>` over the rung,
  // which saves a thumbnail under the original's filename and passes every other test silently.
  it('Download fetches the original when the viewer is showing a rung', async () => {
    listFiles.mockResolvedValueOnce([file('/a.png')])
    getLargePreview.mockResolvedValue({
      ok: true,
      bytes: new Int8Array([0xff, 0xd8, 0xff, 0xe0, 9]),
      thumbnailClass: 'xxlarge',
      coveringRung: 'present',
    })
    getData.mockResolvedValueOnce({ ok: true, bytes: new Int8Array([1, 2, 3, 4]) })
    vi.stubGlobal('URL', {
      ...URL,
      createObjectURL: vi.fn(() => 'blob:rung'),
      revokeObjectURL: vi.fn(),
    })
    renderBrowser()

    await userEvent.dblClick(await screen.findByText('a.png'))
    await screen.findByRole('img', { name: 'a.png' })
    expect(getData).not.toHaveBeenCalled()

    await userEvent.click(screen.getByRole('button', { name: 'Download' }))

    await waitFor(() => expect(getData).toHaveBeenCalledWith('/a.png'))
    vi.unstubAllGlobals()
  })

  it('opens a file in the viewer (getData) on double-click', async () => {
    listFiles.mockResolvedValueOnce([file('/a.png')])
    getData.mockResolvedValueOnce({ ok: true, bytes: new Int8Array([1, 2, 3]) })
    vi.stubGlobal('URL', {
      ...URL,
      createObjectURL: vi.fn(() => 'blob:p'),
      revokeObjectURL: vi.fn(),
    })
    renderBrowser()

    await userEvent.dblClick(await screen.findByText('a.png'))

    await waitFor(() => expect(getData).toHaveBeenCalledWith('/a.png'))
    const img = await screen.findByRole('img', { name: 'a.png' })
    expect(img).toHaveAttribute('src', 'blob:p')
    vi.unstubAllGlobals()
  })

  it('Get info selects the entry and opens the details pane', async () => {
    listFiles.mockResolvedValue([file('/a.jpg'), file('/b.jpg')])
    const onTogglePane = vi.fn()
    const onFocusChange = vi.fn()
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <FileBrowser onTogglePane={onTogglePane} onFocusChange={onFocusChange} />
      </QueryClientProvider>,
    )

    await userEvent.click(await screen.findByRole('button', { name: 'Actions for b.jpg' }))
    await userEvent.click(screen.getByRole('menuitem', { name: 'Get info' }))

    expect(onTogglePane).toHaveBeenCalledOnce()
    await waitFor(() => expect(onFocusChange).toHaveBeenLastCalledWith(expect.objectContaining({ path: '/b.jpg' })))
  })

  it('does not offer Download for a folder', async () => {
    listFiles.mockResolvedValue([folder('/Docs')])
    renderBrowser()

    await userEvent.click(await screen.findByRole('button', { name: 'Actions for Docs' }))
    // Folders get Get info (a summary) but no Download.
    expect(screen.queryByRole('menuitem', { name: 'Download' })).toBeNull()
  })

  it('shows an empty state', async () => {
    listFiles.mockResolvedValueOnce([])
    renderBrowser()
    expect(await screen.findByText('This folder is empty.')).toBeInTheDocument()
  })

  // §3.2: a click selects — it never opens.
  it('a click selects without opening; Escape clears', async () => {
    listFiles.mockResolvedValue([folder('/Photos'), file('/a.txt')])
    renderBrowser()
    await userEvent.click(await screen.findByText('Photos'))
    expect(screen.getByText('Photos').closest('button')).toHaveAttribute('aria-pressed', 'true')
    expect(listFiles).not.toHaveBeenCalledWith('/Photos')
    await userEvent.keyboard('{Escape}')
    expect(screen.getByText('Photos').closest('button')).toHaveAttribute('aria-pressed', 'false')
  })

  it('Enter opens the selected folder', async () => {
    listFiles.mockResolvedValueOnce([folder('/Photos')])
    listFiles.mockResolvedValueOnce([file('/Photos/a.jpg')])
    renderBrowser()
    await userEvent.click(await screen.findByText('Photos'))
    await userEvent.keyboard('{Enter}')
    expect(await screen.findByText('a.jpg')).toBeInTheDocument()
  })

  it('Shift-click selects the range, ⌘-click adds one; the selection bar replaces the toolbar', async () => {
    listFiles.mockResolvedValue([file('/a.txt'), file('/b.txt'), file('/c.txt'), file('/d.txt')])
    const user = userEvent.setup() // one instance, so a held modifier carries into the click
    renderBrowser()
    await user.click(await screen.findByText('a.txt'))
    expect(screen.queryByRole('toolbar', { name: 'Selection' })).toBeNull() // one plain click: no bar
    await user.keyboard('{Shift>}')
    await user.click(screen.getByText('c.txt'))
    await user.keyboard('{/Shift}')
    expect(screen.getByText('3 selected')).toBeInTheDocument()
    await user.keyboard('{Meta>}')
    await user.click(screen.getByText('b.txt'))
    await user.keyboard('{/Meta}')
    expect(screen.getByText('2 selected')).toBeInTheDocument()
    expect(screen.getByRole('toolbar', { name: 'Selection' })).toHaveTextContent('200 B')
    expect(screen.queryByRole('combobox', { name: 'Sort by' })).toBeNull()
  })

  // The native file-actions sheet, on right-click — the same entries as the ⋯ menu.
  it('right-click opens the item menu', async () => {
    listFiles.mockResolvedValue([file('/a.txt')])
    renderBrowser()
    fireEvent.contextMenu(await screen.findByText('a.txt'))
    // It also selects the row it points at, so the pane and the menu agree.
    expect(screen.getByText('a.txt').closest('button')).toHaveAttribute('aria-pressed', 'true')
    for (const name of ['Open', 'Download', 'Share', 'Move', 'Rename', 'Delete']) {
      expect(await screen.findByRole('menuitem', { name: new RegExp(`^${name}`) })).toBeInTheDocument()
    }
  })

  // Review finding (L3): Enter on a menu item used to bubble to the list and ALSO open the selected
  // folder — so "Delete" landed you inside the folder you were deleting.
  it('Enter on a menu item does only that item, not "open" for the selected row', async () => {
    listFiles.mockResolvedValue([folder('/Photos')])
    renderBrowser()
    fireEvent.contextMenu(await screen.findByText('Photos'))
    const del = await screen.findByRole('menuitem', { name: /^Delete/ })
    del.focus()
    await userEvent.keyboard('{Enter}')
    expect(await screen.findByRole('dialog', { name: 'Delete folder' })).toBeInTheDocument()
    expect(listFiles).not.toHaveBeenCalledWith('/Photos')
  })

  it('keys on the toolbar are the toolbar’s: Backspace there deletes nothing', async () => {
    listFiles.mockResolvedValue([file('/a.txt')])
    renderBrowser()
    await userEvent.click(await screen.findByText('a.txt'))
    screen.getByRole('button', { name: /Sort (ascending|descending)/ }).focus()
    await userEvent.keyboard('{Backspace}')
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('a selected file that disappears from the listing drops out of the selection', async () => {
    listFiles.mockResolvedValueOnce([file('/a.txt'), file('/b.txt')])
    listFiles.mockResolvedValue([file('/a.txt')])
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <QueryClientProvider client={queryClient}>
        <FileBrowser />
      </QueryClientProvider>,
    )
    await userEvent.click(await screen.findByRole('checkbox', { name: 'Select a.txt' }))
    await userEvent.click(screen.getByRole('checkbox', { name: 'Select b.txt' }))
    expect(screen.getByText('2 selected')).toBeInTheDocument()
    await queryClient.invalidateQueries()
    await waitFor(() => expect(screen.queryByText('b.txt')).toBeNull())
    expect(screen.getByText('1 selected')).toBeInTheDocument()
  })

  it('renames an entry via its row menu', async () => {
    listFiles.mockResolvedValue([file('/a.txt')])
    rename.mockResolvedValueOnce(true)
    renderBrowser()

    await userEvent.click(await screen.findByRole('button', { name: 'Actions for a.txt' }))
    await userEvent.click(screen.getByRole('menuitem', { name: 'Rename' }))
    const input = screen.getByLabelText('New name')
    await userEvent.clear(input)
    await userEvent.type(input, 'b.txt')
    await userEvent.click(screen.getByRole('button', { name: 'Rename' }))

    await waitFor(() => expect(rename).toHaveBeenCalledWith('/a.txt', 'b.txt'))
  })

  it('deletes an entry after confirmation', async () => {
    listFiles.mockResolvedValue([file('/a.txt')])
    deleteEntry.mockResolvedValueOnce(true)
    renderBrowser()

    await userEvent.click(await screen.findByRole('button', { name: 'Actions for a.txt' }))
    await userEvent.click(screen.getByRole('menuitem', { name: 'Delete' }))
    await userEvent.click(screen.getByRole('button', { name: 'Delete' }))

    await waitFor(() => expect(deleteEntry).toHaveBeenCalledWith('/a.txt'))
  })

  it('moves an entry into a folder picked in the Move dialog', async () => {
    listFiles.mockResolvedValue([folder('/Docs'), file('/a.txt')])
    move.mockResolvedValueOnce(true)
    renderBrowser()

    await userEvent.click(await screen.findByRole('button', { name: 'Actions for a.txt' }))
    await userEvent.click(screen.getByRole('menuitem', { name: 'Move' }))

    const dialog = screen.getByRole('dialog', { name: /Move/ })
    await userEvent.click(within(dialog).getByText('Docs')) // descend into the destination
    await userEvent.click(within(dialog).getByRole('button', { name: 'Move here' }))

    await waitFor(() => expect(move).toHaveBeenCalledWith('/a.txt', '/Docs'))
  })

  it('shares a file via its row menu and shows the link', async () => {
    listFiles.mockResolvedValue([file('/a.txt')])
    createShareLink.mockResolvedValueOnce({ shareId: 'abc', urlFragment: 'XYZ' })
    renderBrowser()

    await userEvent.click(await screen.findByRole('button', { name: 'Actions for a.txt' }))
    await userEvent.click(screen.getByRole('menuitem', { name: 'Share' }))

    expect(await screen.findByLabelText('Share link')).toHaveValue(
      'https://tenshu.app/s/abc#k=XYZ',
    )
  })

  it('does not offer Share for a folder', async () => {
    listFiles.mockResolvedValue([folder('/Docs')])
    renderBrowser()

    await userEvent.click(await screen.findByRole('button', { name: 'Actions for Docs' }))
    expect(screen.getByRole('menuitem', { name: 'Rename' })).toBeInTheDocument()
    expect(screen.queryByRole('menuitem', { name: 'Share' })).toBeNull()
  })

  // W7. A folder pick arrives as a FLAT FileList; the directory tree exists only inside each
  // file's `webkitRelativePath`. If that is not reconstructed, every file lands in the root and
  // the user's folder structure is silently flattened on upload.
  it('bulk-deletes multiple selected items', async () => {
    listFiles.mockResolvedValue([file('/a.txt'), file('/b.txt')])
    deleteEntry.mockResolvedValue(true)
    renderBrowser()

    await screen.findByText('a.txt')
    await userEvent.click(screen.getByRole('checkbox', { name: 'Select a.txt' }))
    await userEvent.click(screen.getByRole('checkbox', { name: 'Select b.txt' }))
    expect(screen.getByText('2 selected')).toBeInTheDocument()

    // Selection-bar Delete (the only Delete button until the dialog opens).
    await userEvent.click(screen.getByRole('button', { name: 'Delete' }))
    const dialog = screen.getByRole('dialog', { name: 'Delete items' })
    await userEvent.click(within(dialog).getByRole('button', { name: 'Delete' }))

    await waitFor(() => {
      expect(deleteEntry).toHaveBeenCalledWith('/a.txt')
      expect(deleteEntry).toHaveBeenCalledWith('/b.txt')
    })
  })
})
