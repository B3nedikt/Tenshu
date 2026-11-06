import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

const browseSharedFolder = vi.fn()
const readSharedFile = vi.fn()
vi.mock('../lib/proxy', () => ({
  browseSharedFolder: (realmId: string, parentFileId: string) =>
    browseSharedFolder(realmId, parentFileId),
  readSharedFile: (realmId: string, fileId: string) => readSharedFile(realmId, fileId),
}))

const { SharedFolderBrowser } = await import('./SharedFolderBrowser')

function renderBrowser() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <SharedFolderBrowser realmId="realm-1" realmName="Holiday Photos" onBack={() => {}} />
    </QueryClientProvider>,
  )
}

afterEach(() => {
  browseSharedFolder.mockReset()
  readSharedFile.mockReset()
})

describe('SharedFolderBrowser', () => {
  it('lists the realm root entries', async () => {
    browseSharedFolder.mockResolvedValueOnce([
      { fileId: 'f1', name: 'sub', isFolder: true },
      { fileId: 'f2', name: 'note.txt', isFolder: false },
    ])
    renderBrowser()

    expect(await screen.findByText('sub')).toBeInTheDocument()
    expect(screen.getByText('note.txt')).toBeInTheDocument()
    // Root browse uses '' (SharedFile.ROOT_PARENT).
    expect(browseSharedFolder).toHaveBeenCalledWith('realm-1', '')
  })

  it('drills into a folder', async () => {
    browseSharedFolder
      .mockResolvedValueOnce([{ fileId: 'f1', name: 'sub', isFolder: true }])
      .mockResolvedValueOnce([{ fileId: 'f3', name: 'inner.txt', isFolder: false }])
    renderBrowser()

    await userEvent.click(await screen.findByText('sub'))
    expect(await screen.findByText('inner.txt')).toBeInTheDocument()
    await waitFor(() => expect(browseSharedFolder).toHaveBeenCalledWith('realm-1', 'f1'))
  })

  it('previews a text file inline → readSharedFile + a lightbox, no download', async () => {
    browseSharedFolder.mockResolvedValue([{ fileId: 'f2', name: 'note.txt', isFolder: false }])
    readSharedFile.mockResolvedValueOnce({ ok: true, bytes: new Int8Array([104, 105]) }) // "hi"
    // jsdom lacks URL.createObjectURL — stub it.
    vi.stubGlobal('URL', {
      ...URL,
      createObjectURL: vi.fn(() => 'blob:x'),
      revokeObjectURL: vi.fn(),
    })
    const clickSpy = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})

    renderBrowser()
    await userEvent.click(await screen.findByText('note.txt'))
    await waitFor(() => expect(readSharedFile).toHaveBeenCalledWith('realm-1', 'f2'))

    const dialog = await screen.findByRole('dialog')
    expect(dialog).toBeInTheDocument()
    expect(within(dialog).getByText('hi')).toBeInTheDocument()
    // A Download link is offered, but nothing is auto-downloaded for a preview.
    expect(within(dialog).getByRole('link', { name: /download/i })).toBeInTheDocument()
    expect(clickSpy).not.toHaveBeenCalled()

    clickSpy.mockRestore()
    vi.unstubAllGlobals()
  })

  it('previews an image inline', async () => {
    browseSharedFolder.mockResolvedValue([{ fileId: 'f4', name: 'pic.png', isFolder: false }])
    readSharedFile.mockResolvedValueOnce({ ok: true, bytes: new Int8Array([1, 2, 3]) })
    vi.stubGlobal('URL', {
      ...URL,
      createObjectURL: vi.fn(() => 'blob:img'),
      revokeObjectURL: vi.fn(),
    })

    renderBrowser()
    await userEvent.click(await screen.findByText('pic.png'))

    const img = await screen.findByRole('img', { name: 'pic.png' })
    expect(img).toHaveAttribute('src', 'blob:img')
    vi.unstubAllGlobals()
  })

  it('downloads a non-previewable file instead of previewing', async () => {
    browseSharedFolder.mockResolvedValue([{ fileId: 'f5', name: 'archive.zip', isFolder: false }])
    readSharedFile.mockResolvedValueOnce({ ok: true, bytes: new Int8Array([1, 2, 3]) })
    vi.stubGlobal('URL', {
      ...URL,
      createObjectURL: vi.fn(() => 'blob:z'),
      revokeObjectURL: vi.fn(),
    })
    const clickSpy = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})

    renderBrowser()
    await userEvent.click(await screen.findByText('archive.zip'))
    await waitFor(() => expect(clickSpy).toHaveBeenCalled())
    // No inline preview for a binary download.
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()

    clickSpy.mockRestore()
    vi.unstubAllGlobals()
  })

  it('says a shared file has not synced when there are simply no bytes', async () => {
    browseSharedFolder.mockResolvedValue([{ fileId: 'f2', name: 'note.txt', isFolder: false }])
    readSharedFile.mockResolvedValueOnce({ ok: true, bytes: null })
    renderBrowser()

    await userEvent.click(await screen.findByText('note.txt'))
    expect(await screen.findByText(/hasn't synced to this device yet/i)).toBeInTheDocument()
  })

  // ⚠⚠ On a shared realm these are genuinely different problems: a failed read is usually the
  // GRANTOR's permissions, and the old shared message ("may not have synced yet") pointed the
  // member at their own connection instead — the one place the misdirection is most expensive,
  // because the remedy is on someone else's device.
  it('distinguishes a refused shared read from an unsynced one', async () => {
    browseSharedFolder.mockResolvedValue([{ fileId: 'f3', name: 'secret.txt', isFolder: false }])
    readSharedFile.mockResolvedValueOnce({ ok: false, error: { kind: 'read', message: 'denied' } })
    renderBrowser()

    await userEvent.click(await screen.findByText('secret.txt'))
    expect(await screen.findByText(/couldn't open "secret.txt" \(read\)/i)).toBeInTheDocument()
    expect(screen.queryByText(/hasn't synced/i)).not.toBeInTheDocument()
  })
})
