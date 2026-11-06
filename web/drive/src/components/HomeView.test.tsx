import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

const recentFiles = vi.fn()
const getData = vi.fn()
vi.mock('../lib/proxy', () => ({
  recentFiles: (limit: number) => recentFiles(limit),
  // Row click opens a file → the opener fetches its bytes via getData.
  getData: (path: string) => getData(path),
  // FileThumbnail (row icon) reads this for image entries.
  getPreview: () => ({ ok: true, bytes: null }),
  // X-5: an image open asks for the large classes first; nothing stored here, so it falls
  // through to `getData` exactly as before.
  getLargePreview: () => ({ ok: true, bytes: null, thumbnailClass: null, coveringRung: 'absent' }),
}))

const { HomeView } = await import('./HomeView')

function file(path: string, size = 100) {
  return { path, size, lastModified: Date.UTC(2024, 0, 1), createdAt: 0, isFolder: false, itemCount: 0 }
}
function folder(path: string) {
  return { path, size: 0, lastModified: 0, isFolder: true, itemCount: 0 }
}

function renderHome() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <HomeView />
    </QueryClientProvider>,
  )
}

afterEach(() => {
  recentFiles.mockReset()
  getData.mockReset()
})

describe('HomeView', () => {
  it('lists recent files (folders filtered out) and opens a preview on click', async () => {
    recentFiles.mockResolvedValueOnce([file('/a.png'), folder('/Docs'), file('/b.txt')])
    getData.mockResolvedValueOnce({ ok: true, bytes: new Int8Array([1, 2, 3]) })
    vi.stubGlobal('URL', {
      ...URL,
      createObjectURL: vi.fn(() => 'blob:p'),
      revokeObjectURL: vi.fn(),
    })
    renderHome()

    await screen.findByText('a.png')
    expect(screen.getByText('b.txt')).toBeInTheDocument()
    expect(screen.queryByText('Docs')).toBeNull()
    expect(recentFiles).toHaveBeenCalledWith(20)

    await userEvent.click(screen.getByText('a.png'))
    await waitFor(() => expect(getData).toHaveBeenCalledWith('/a.png'))
    const img = await screen.findByRole('img', { name: 'a.png' })
    expect(img).toHaveAttribute('src', 'blob:p')
    vi.unstubAllGlobals()
  })

  it('shows an empty state', async () => {
    recentFiles.mockResolvedValueOnce([])
    renderHome()
    expect(await screen.findByText(/Nothing here yet/i)).toBeInTheDocument()
  })
})
