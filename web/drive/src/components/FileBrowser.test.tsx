import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
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
  // L6: this browser's stored display — none yet (the local copy stands).
  driveSettings: async () => ({ ok: false, error: { kind: 'no_device' } }),
  setDriveListing: async () => ({ ok: true }),
  setDriveDetailsPane: async () => ({ ok: true }),
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

  // Grid tiles: the name was squeezed to nothing beside the size and date — now they sit under it.
  it('in grid view the size and date sit under the name', async () => {
    listFiles.mockResolvedValue([file('/holiday-photos-2026.jpg', 3_400_000)])
    renderBrowser()
    await userEvent.click(await screen.findByRole('button', { name: 'Switch to grid' }))
    const name = screen.getByText('holiday-photos-2026.jpg')
    expect(name.parentElement).toHaveClass('flex-col')
    expect(name.parentElement).toHaveTextContent(/MB/)
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

// The marquee (rubber band), Finder / iCloud (web-apps-layout.md §4.3 "Marquee"). jsdom has no
// layout, so the geometry is pinned: the listing area is 800 × 1000 at the origin and row i is the
// full width, 48 px tall at y = 48·i — both offset by the scroller's `scrollTop` when there is one
// (an AppLayout-like <main> around the browser), as the content moves under a fixed viewport.
describe('marquee selection', () => {
  const rect = (left: number, top: number, width: number, height: number) =>
    ({ left, top, width, height, right: left + width, bottom: top + height, x: left, y: top, toJSON: () => ({}) }) as DOMRect

  async function listing(count = 5, { inMain = false } = {}) {
    const names = Array.from({ length: count }, (_, i) => `f${String(i).padStart(2, '0')}.txt`)
    listFiles.mockResolvedValue(names.map((name) => file(`/${name}`)))
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const browser = (
      <QueryClientProvider client={queryClient}>
        <FileBrowser />
      </QueryClientProvider>
    )
    render(inMain ? <main>{browser}</main> : browser)
    await screen.findByText(names[0]!)
    const main = document.querySelector('main')
    const scrolled = () => main?.scrollTop ?? 0
    const area = screen.getByTestId('file-listing')
    vi.spyOn(area, 'getBoundingClientRect').mockImplementation(() => rect(0, -scrolled(), 800, 1000))
    document.querySelectorAll<HTMLElement>('[data-row]').forEach((row, i) => {
      vi.spyOn(row, 'getBoundingClientRect').mockImplementation(() => rect(0, 48 * i - scrolled(), 800, 48))
    })
    return { area, names, main }
  }
  const row = (name: string) => screen.getByRole('button', { name: new RegExp(`^${name.replace('.', '\\.')}`) })
  const pressed = (name: string) => row(name).getAttribute('aria-pressed') === 'true'
  const at = (x: number, y: number, extra: Record<string, unknown> = {}) =>
    ({ clientX: x, clientY: y, pointerId: 1, button: 0, isPrimary: true, pointerType: 'mouse', ...extra })
  function drag(el: HTMLElement, from: [number, number], to: [number, number], extra: Record<string, unknown> = {}) {
    fireEvent.pointerDown(el, at(...from, extra))
    fireEvent.pointerMove(el, at((from[0] + to[0]) / 2, (from[1] + to[1]) / 2, extra))
    fireEvent.pointerMove(el, at(...to, extra))
    fireEvent.pointerUp(el, at(...to, extra))
  }

  it('a drag from empty space selects every row the band intersects, live', async () => {
    const { area, names } = await listing()
    // From below the last row (y 400) up to y 60: rows 1–4 (row 0 ends at 48).
    fireEvent.pointerDown(area, at(400, 400))
    fireEvent.pointerMove(area, at(400, 60))
    expect(screen.getByTestId('marquee')).toBeInTheDocument()
    expect(names.map(pressed)).toEqual([false, true, true, true, true])
    expect(screen.getByText('4 selected')).toBeInTheDocument()
    // Shrinking the band drops rows again.
    fireEvent.pointerMove(area, at(400, 200))
    expect(names.map(pressed)).toEqual([false, false, false, false, true])
    fireEvent.pointerUp(area, at(400, 200))
    expect(screen.queryByTestId('marquee')).toBeNull()
    // A band of one is a set gesture: the selection bar shows for it.
    expect(screen.getByText('1 selected')).toBeInTheDocument()
  })

  it('a plain drag replaces the selection; ⌘ or Shift at the press adds to it', async () => {
    const { area, names } = await listing()
    await userEvent.click(screen.getByText('f00.txt'))
    drag(area, [400, 400], [400, 200], { metaKey: true }) // f04 only
    expect(names.map(pressed)).toEqual([true, false, false, false, true])
    drag(area, [400, 400], [400, 150], { shiftKey: true }) // f03, f04 — added, f00 kept
    expect(names.map(pressed)).toEqual([true, false, false, true, true])
    drag(area, [400, 400], [400, 200])
    expect(names.map(pressed)).toEqual([false, false, false, false, true])
  })

  it('a click on empty space (no drag) clears the selection', async () => {
    const { area, names } = await listing()
    await userEvent.click(screen.getByText('f00.txt'))
    fireEvent.click(screen.getByText('f01.txt'), { metaKey: true })
    expect(screen.getByText('2 selected')).toBeInTheDocument()
    // Under the 4 px threshold: a click, not a band.
    fireEvent.pointerDown(area, at(400, 400))
    fireEvent.pointerMove(area, at(402, 401))
    expect(screen.queryByTestId('marquee')).toBeNull()
    fireEvent.pointerUp(area, at(402, 401))
    expect(names.map(pressed)).toEqual([false, false, false, false, false])
  })

  it("a press on a row's icon or name never bands, and the row still selects on click", async () => {
    const { area, names } = await listing()
    fireEvent.pointerDown(screen.getByText('f01.txt'), at(60, 70))
    fireEvent.pointerMove(area, at(400, 400))
    expect(screen.queryByTestId('marquee')).toBeNull()
    fireEvent.pointerUp(area, at(400, 400))
    expect(names.map(pressed)).toEqual([false, false, false, false, false])
    await userEvent.click(screen.getByText('f01.txt'))
    expect(names.map(pressed)).toEqual([false, true, false, false, false])
  })

  it("a row's blank part: a click selects (and a double-click opens); a drag bands", async () => {
    const { area, names } = await listing()
    const meta = (name: string) => within(row(name)).getByText(/100 B/)
    // No drag: the row's own click, untouched by the band (nothing is cleared on release).
    await userEvent.click(meta('f01.txt'))
    expect(names.map(pressed)).toEqual([false, true, false, false, false])
    expect(document.activeElement).toBe(row('f01.txt'))
    // A drag from the same spot bands: f02 … f04.
    fireEvent.pointerDown(meta('f02.txt'), at(700, 110))
    fireEvent.pointerMove(area, at(700, 230))
    expect(screen.getByTestId('marquee')).toBeInTheDocument()
    fireEvent.pointerUp(area, at(700, 230))
    expect(names.map(pressed)).toEqual([false, false, true, true, true])
    await userEvent.dblClick(meta('f03.txt'))
    expect(await screen.findByRole('dialog')).toBeInTheDocument()
  })

  it('the checkbox and ⋯ are the row’s own: a press there never bands', async () => {
    const { area } = await listing()
    fireEvent.pointerDown(screen.getByRole('checkbox', { name: 'Select f01.txt' }), at(12, 70))
    fireEvent.pointerMove(area, at(400, 400))
    expect(screen.queryByTestId('marquee')).toBeNull()
    fireEvent.pointerUp(area, at(400, 400))
    fireEvent.pointerDown(screen.getByRole('button', { name: 'Actions for f01.txt' }), at(790, 70))
    fireEvent.pointerMove(area, at(400, 400))
    expect(screen.queryByTestId('marquee')).toBeNull()
    fireEvent.pointerUp(area, at(400, 400))
  })

  it('the band stays inside the listing area, however far the pointer goes', async () => {
    const { area } = await listing()
    fireEvent.pointerDown(area, at(400, 400))
    fireEvent.pointerMove(area, at(5000, -400)) // onto the pane, above the toolbar
    const band = screen.getByTestId('marquee')
    expect(parseFloat(band.style.left) + parseFloat(band.style.width)).toBeLessThanOrEqual(800)
    expect(parseFloat(band.style.top)).toBeGreaterThanOrEqual(0)
    expect(parseFloat(band.style.left)).toBeGreaterThanOrEqual(0)
    fireEvent.pointerUp(area, at(5000, -400))
  })

  it('a press on empty space gives the list focus, so Escape clears right after a band', async () => {
    const { area } = await listing()
    drag(area, [400, 400], [400, 60])
    expect(document.activeElement).toBe(screen.getByRole('list', { name: 'Files' }))
    expect(screen.getByText('4 selected')).toBeInTheDocument()
    await userEvent.keyboard('{Escape}')
    expect(screen.queryByText(/selected/)).toBeNull()
  })

  it('a band started on a row takes focus off that row (⌫ must not act on it); a plain press focuses the list', async () => {
    const { area, names } = await listing()
    const list = screen.getByRole('list', { name: 'Files' })
    fireEvent.pointerDown(within(row('f01.txt')).getByText(/100 B/), at(700, 70))
    expect(document.activeElement).toBe(row('f01.txt'))
    fireEvent.pointerMove(area, at(700, 230))
    expect(document.activeElement).toBe(list)
    fireEvent.pointerUp(area, at(700, 230))
    // A press on empty space without a drag: the list has focus, so ⌘A works next.
    row('f00.txt').focus()
    fireEvent.pointerDown(area, at(400, 400))
    fireEvent.pointerUp(area, at(400, 400))
    expect(document.activeElement).toBe(list)
    await userEvent.keyboard('{Meta>}a{/Meta}')
    expect(names.map(pressed)).toEqual([true, true, true, true, true])
  })

  it('a press in a row’s padding (not its button) is not empty space: it never clears', async () => {
    const { area, names } = await listing()
    await userEvent.click(screen.getByText('f00.txt'))
    const li = document.querySelector<HTMLElement>('[data-row="/f02.txt"]')!
    fireEvent.pointerDown(li, at(796, 110))
    fireEvent.pointerUp(area, at(796, 110))
    expect(names.map(pressed)).toEqual([true, false, false, false, false])
  })

  it('a Shift-click after a band extends from the band’s first hit', async () => {
    const { area, names } = await listing()
    drag(area, [400, 400], [400, 100]) // f02 … f04; f02 is the first hit
    await new Promise((resolve) => setTimeout(resolve, 0)) // a separate click, not the band's own
    fireEvent.click(screen.getByText('f00.txt'), { shiftKey: true })
    expect(names.map(pressed)).toEqual([true, true, true, false, false])
  })

  // Review findings (Drive marquee).
  it('a press released outside the area ends there: a later hover never bands', async () => {
    const { area, names } = await listing()
    fireEvent.pointerDown(area, at(400, 400))
    fireEvent.pointerUp(window, at(400, 900)) // released off the area, before capture was taken
    fireEvent.pointerMove(area, at(400, 60))
    expect(screen.queryByTestId('marquee')).toBeNull()
    expect(names.some(pressed)).toBe(false)
  })

  it('the click that follows a band’s release does not collapse the band to one row', async () => {
    const { area, names } = await listing()
    drag(area, [400, 400], [400, 100])
    fireEvent.click(screen.getByText('f03.txt')) // the browser's click after the band's pointerup
    expect(names.map(pressed)).toEqual([false, false, true, true, true])
  })

  it('Escape during a band cancels it; the next move does not bring the selection back', async () => {
    const { area, names } = await listing()
    fireEvent.pointerDown(area, at(400, 400))
    fireEvent.pointerMove(area, at(400, 100))
    expect(screen.getByTestId('marquee')).toBeInTheDocument()
    fireEvent.keyDown(document.activeElement ?? area, { key: 'Escape' })
    fireEvent.pointerMove(area, at(400, 60))
    expect(screen.queryByTestId('marquee')).toBeNull()
    expect(names.some(pressed)).toBe(false)
  })

  it('ignores a secondary button and a touch', async () => {
    const { area, names } = await listing()
    await userEvent.click(screen.getByText('f00.txt'))
    drag(area, [400, 400], [400, 60], { button: 2 })
    drag(area, [400, 400], [400, 60], { pointerType: 'touch' })
    expect(screen.queryByTestId('marquee')).toBeNull()
    expect(names.map(pressed)).toEqual([true, false, false, false, false])
  })

  it('never bands while the viewer is open', async () => {
    const { area } = await listing()
    await userEvent.dblClick(screen.getByText('f00.txt'))
    await screen.findByRole('dialog')
    fireEvent.pointerDown(area, at(400, 400))
    fireEvent.pointerMove(area, at(400, 60))
    expect(screen.queryByTestId('marquee')).toBeNull()
  })

  it('near the scroller’s edge the content scrolls and the band follows it (and a wheel scroll too)', async () => {
    const frames: FrameRequestCallback[] = []
    const raf = vi.spyOn(window, 'requestAnimationFrame').mockImplementation((cb) => frames.push(cb))
    const caf = vi.spyOn(window, 'cancelAnimationFrame').mockImplementation(() => {})
    try {
      const { area, names, main } = await listing(20, { inMain: true })
      // The scroller: a 600 px viewport over 1000 px of content.
      vi.spyOn(main!, 'getBoundingClientRect').mockReturnValue(rect(0, 0, 800, 600))
      Object.defineProperty(main!, 'clientHeight', { configurable: true, value: 600 })
      Object.defineProperty(main!, 'scrollHeight', { configurable: true, value: 1000 })
      // Press in row 2 (content y 100), drag to 10 px above the viewport's bottom: rows 2–12.
      fireEvent.pointerDown(area, at(400, 100))
      fireEvent.pointerMove(area, at(400, 590))
      expect(names.map(pressed).lastIndexOf(true)).toBe(12)
      // Three frames at 18 px each: the content under the (still) pointer is y 644, row 13.
      for (let i = 0; i < 3; i++) act(() => frames.shift()!(0))
      expect(main!.scrollTop).toBe(54)
      expect(names.map(pressed).lastIndexOf(true)).toBe(13)
      expect(names.map(pressed).indexOf(true)).toBe(2) // the press stayed anchored to the content
      // A wheel scroll mid-drag: the band follows the content too.
      main!.scrollTop = 200
      fireEvent.scroll(main!)
      expect(names.map(pressed).lastIndexOf(true)).toBe(16) // content y 790
      fireEvent.pointerUp(area, at(400, 590))
      expect(caf).toHaveBeenCalled()
      expect(screen.queryByTestId('marquee')).toBeNull()
    } finally {
      raf.mockRestore()
      caf.mockRestore()
    }
  })
})
