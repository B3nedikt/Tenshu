import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
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

  it('navigates into a folder on click and lists its contents', async () => {
    listFiles.mockResolvedValueOnce([folder('/Photos')]) // root
    listFiles.mockResolvedValueOnce([file('/Photos/a.jpg')]) // inside Photos
    renderBrowser()

    await userEvent.click(await screen.findByText('Photos'))

    await screen.findByText('a.jpg')
    expect(listFiles).toHaveBeenCalledWith('/Photos')
    // Breadcrumb trail now shows the folder.
    expect(screen.getByRole('navigation', { name: 'Breadcrumb' })).toHaveTextContent('Photos')
  })

  it('navigates back up via a breadcrumb', async () => {
    listFiles.mockResolvedValueOnce([folder('/Photos')]) // root
    listFiles.mockResolvedValueOnce([file('/Photos/a.jpg')]) // Photos
    listFiles.mockResolvedValueOnce([folder('/Photos')]) // back at root
    renderBrowser()

    await userEvent.click(await screen.findByText('Photos'))
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

    await userEvent.click(await screen.findByText('a.png'))

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

    await userEvent.click(await screen.findByText('a.png'))

    const img = await screen.findByRole('img', { name: 'a.png' })
    expect(img).toHaveAttribute('src', 'blob:rung')
    expect(getLargePreview).toHaveBeenCalledWith('/a.png', 2048)
    expect(getData).not.toHaveBeenCalled()
    vi.unstubAllGlobals()
  })

  // ⚠⚠ The data-loss guard. What is on screen is a 2048 px JPEG, not the file — so Download must
  // fetch the real bytes. Deleting the `standIn` branch makes Download an `<a href>` over the rung,
  // which saves a thumbnail under the original's filename and passes every other test silently.
  it('Download fetches the original when the lightbox is showing a rung', async () => {
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

    await userEvent.click(await screen.findByText('a.png'))
    await screen.findByRole('img', { name: 'a.png' })
    expect(getData).not.toHaveBeenCalled()

    await userEvent.click(screen.getByRole('button', { name: 'Download' }))

    await waitFor(() => expect(getData).toHaveBeenCalledWith('/a.png'))
    vi.unstubAllGlobals()
  })

  it('opens a preview (getData + lightbox) when a file is clicked', async () => {
    listFiles.mockResolvedValueOnce([file('/a.png')])
    getData.mockResolvedValueOnce({ ok: true, bytes: new Int8Array([1, 2, 3]) })
    vi.stubGlobal('URL', {
      ...URL,
      createObjectURL: vi.fn(() => 'blob:p'),
      revokeObjectURL: vi.fn(),
    })
    renderBrowser()

    await userEvent.click(await screen.findByText('a.png'))

    await waitFor(() => expect(getData).toHaveBeenCalledWith('/a.png'))
    const img = await screen.findByRole('img', { name: 'a.png' })
    expect(img).toHaveAttribute('src', 'blob:p')
    vi.unstubAllGlobals()
  })

  it('opens the details drawer from the row menu', async () => {
    listFiles.mockResolvedValueOnce([file('/a.jpg')])
    details.mockResolvedValue({ ok: true, row: { path: '/a.jpg', size: 10, createdAt: 0, lastModified: 0 } })
    activity.mockResolvedValue([])
    renderBrowser()

    await userEvent.click(await screen.findByRole('button', { name: 'Actions for a.jpg' }))
    await userEvent.click(screen.getByRole('menuitem', { name: 'Details' }))

    expect(await screen.findByRole('dialog', { name: 'File details' })).toBeInTheDocument()
    expect(details).toHaveBeenCalledWith('/a.jpg')
  })

  it('does not offer Details for a folder', async () => {
    listFiles.mockResolvedValue([folder('/Docs')])
    renderBrowser()

    await userEvent.click(await screen.findByRole('button', { name: 'Actions for Docs' }))
    expect(screen.queryByRole('menuitem', { name: 'Details' })).toBeNull()
  })

  it('shows an empty state and an Upload button', async () => {
    listFiles.mockResolvedValueOnce([])
    renderBrowser()

    await screen.findByText('This folder is empty.')
    expect(screen.getByRole('button', { name: 'Upload' })).toBeInTheDocument()
  })

  it('creates a folder via the New folder dialog', async () => {
    listFiles.mockResolvedValue([])
    createFolder.mockResolvedValueOnce(true)
    renderBrowser()
    await screen.findByText('This folder is empty.')

    await userEvent.click(screen.getByRole('button', { name: 'New folder' }))
    await userEvent.type(screen.getByLabelText('Folder name'), 'Photos')
    await userEvent.click(screen.getByRole('button', { name: 'Create' }))

    await waitFor(() => expect(createFolder).toHaveBeenCalledWith('/Photos'))
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
  it('recreates a picked folder’s tree, parents before children', async () => {
    listFiles.mockResolvedValue([])
    createFolder.mockResolvedValue(true)
    uploadFile.mockResolvedValue(true)
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <FileBrowser />
      </QueryClientProvider>,
    )
    await screen.findByText(/upload folder/i)

    const input = document.querySelector('input[webkitdirectory]') as HTMLInputElement
    expect(input).not.toBeNull()

    const pick = (relative: string) => {
      const f = new File(['x'], relative.split('/').pop()!, { type: 'text/plain' })
      Object.defineProperty(f, 'webkitRelativePath', { value: relative })
      return f
    }
    const files = [pick('trip/photos/a.jpg'), pick('trip/notes.txt'), pick('trip/photos/raw/b.dng')]
    Object.defineProperty(input, 'files', { value: files, configurable: true })
    input.dispatchEvent(new Event('change', { bubbles: true }))

    await waitFor(() => expect(uploadFile).toHaveBeenCalledTimes(3))

    // ⚠⚠ Parents before children — a child folder cannot be created into a parent that does
    // not exist yet. Every ancestor appears exactly once, however many files share it.
    const created = createFolder.mock.calls.map((c) => c[0] as string)
    expect(created).toEqual(['/trip', '/trip/photos', '/trip/photos/raw'])

    // Each file into the folder its relative path named — not the root.
    const targets = uploadFile.mock.calls.map((c) => [c[0], c[1]])
    expect(targets).toContainEqual(['/trip/photos', 'a.jpg'])
    expect(targets).toContainEqual(['/trip', 'notes.txt'])
    expect(targets).toContainEqual(['/trip/photos/raw', 'b.dng'])
  })

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
