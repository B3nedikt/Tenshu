import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

const details = vi.fn()
const activity = vi.fn()
const getData = vi.fn()
vi.mock('../lib/proxy', () => ({
  details: (path: string) => details(path),
  activity: (path: string) => activity(path),
  getData: (path: string) => getData(path),
}))

const { FileDetails } = await import('./FileDetails')

function renderPanel(path: string, onClose = vi.fn()) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <FileDetails path={path} onClose={onClose} />
    </QueryClientProvider>,
  )
  return onClose
}

afterEach(() => {
  details.mockReset()
  activity.mockReset()
  getData.mockReset()
})

describe('FileDetails', () => {
  it('renders metadata and the activity history', async () => {
    details.mockResolvedValueOnce({ ok: true, row: {
      path: '/Photos/a.jpg',
      size: 2048,
      createdAt: Date.UTC(2024, 0, 1),
      lastModified: Date.UTC(2024, 0, 2),
    } })
    activity.mockResolvedValueOnce([
      {
        kind: 'Renamed',
        detail: 'old.jpg → a.jpg',
        timestampMillis: Date.UTC(2024, 0, 2, 9, 30),
        deviceId: 'dev-1',
        deviceName: 'Laptop',
      },
    ])

    renderPanel('/Photos/a.jpg')

    // Header is the basename; size is formatted; the rename row + device show.
    expect(await screen.findByRole('heading', { name: 'a.jpg' })).toBeInTheDocument()
    expect(await screen.findByText('2 KB')).toBeInTheDocument()
    expect(await screen.findByText('Renamed')).toBeInTheDocument()
    expect(screen.getByText('old.jpg → a.jpg')).toBeInTheDocument()
    expect(screen.getByText('Laptop')).toBeInTheDocument()
    expect(details).toHaveBeenCalledWith('/Photos/a.jpg')
    expect(activity).toHaveBeenCalledWith('/Photos/a.jpg')
  })

  it('falls back to the device id when no name is resolved', async () => {
    details.mockResolvedValueOnce({ ok: true, row: { path: '/x', size: 0, createdAt: 0, lastModified: 0 } })
    activity.mockResolvedValueOnce([
      { kind: 'Created', detail: null, timestampMillis: Date.UTC(2024, 0, 1), deviceId: 'dev-xyz', deviceName: null },
    ])
    renderPanel('/x')
    expect(await screen.findByText('dev-xyz')).toBeInTheDocument()
  })

  it('shows an empty activity state', async () => {
    details.mockResolvedValueOnce({ ok: true, row: { path: '/x', size: 10, createdAt: 0, lastModified: 0 } })
    activity.mockResolvedValueOnce([])
    renderPanel('/x')
    expect(await screen.findByText('No activity yet.')).toBeInTheDocument()
  })

  it('closes via the close button', async () => {
    details.mockResolvedValueOnce({ ok: true, row: null })
    activity.mockResolvedValueOnce([])
    const onClose = renderPanel('/x')
    await userEvent.click(screen.getByRole('button', { name: 'Close details' }))
    expect(onClose).toHaveBeenCalledOnce()
  })

  // The pane is sticky (D2): Escape belongs to the list (clear the selection), not to the pane.
  it('does not close on Escape', async () => {
    details.mockResolvedValueOnce({ ok: true, row: null })
    activity.mockResolvedValueOnce([])
    const onClose = renderPanel('/x')
    await userEvent.keyboard('{Escape}')
    expect(onClose).not.toHaveBeenCalled()
  })

  // ⚠⚠ The two failures a download can have are different problems with different remedies, and
  // until `getData` became result-shaped they arrived identically as `null` — so the UI said
  // "Couldn't download this file" for both, including for a file that was simply not on this
  // device yet. `T?` collapsing failure into absence is the bug class this guards.
  it('tells a failed read apart from a file whose bytes are not here', async () => {
    details.mockResolvedValue({ ok: true, row: { path: '/docs/a.zip', size: 3, createdAt: 0, lastModified: 0 } })
    activity.mockResolvedValue([])

    getData.mockResolvedValueOnce({ ok: false, error: { kind: 'read', message: 'boom' } })
    renderPanel('/docs/a.zip')
    await userEvent.click(await screen.findByRole('button', { name: 'Download' }))
    expect(await screen.findByText(/couldn't download this file \(read\)/i)).toBeInTheDocument()
  })

  it('says so when the file simply has no bytes on this device', async () => {
    details.mockResolvedValue({ ok: true, row: { path: '/docs/b.zip', size: 3, createdAt: 0, lastModified: 0 } })
    activity.mockResolvedValue([])

    getData.mockResolvedValueOnce({ ok: true, bytes: null })
    renderPanel('/docs/b.zip')
    await userEvent.click(await screen.findByRole('button', { name: 'Download' }))
    expect(await screen.findByText(/aren't on this device yet/i)).toBeInTheDocument()
  })

  it('downloads via the Download button (getData → anchor click, no lightbox)', async () => {
    details.mockResolvedValue({ ok: true, row: { path: '/docs/archive.zip', size: 3, createdAt: 0, lastModified: 0 } })
    activity.mockResolvedValue([])
    getData.mockResolvedValueOnce({ ok: true, bytes: new Int8Array([1, 2, 3]) })
    vi.stubGlobal('URL', {
      ...URL,
      createObjectURL: vi.fn(() => 'blob:z'),
      revokeObjectURL: vi.fn(),
    })
    const clickSpy = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})

    renderPanel('/docs/archive.zip')
    await userEvent.click(await screen.findByRole('button', { name: 'Download' }))
    await waitFor(() => expect(getData).toHaveBeenCalledWith('/docs/archive.zip'))
    expect(clickSpy).toHaveBeenCalled()
    // The drawer is details-only now — no preview lightbox is ever opened here.
    expect(screen.queryByRole('dialog', { name: 'archive.zip' })).not.toBeInTheDocument()

    clickSpy.mockRestore()
    vi.unstubAllGlobals()
  })
})
