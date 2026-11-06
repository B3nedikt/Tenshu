import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

const getPreview = vi.fn()
vi.mock('../lib/proxy', () => ({
  getPreview: (path: string, w: number, h: number) => getPreview(path, w, h),
}))

const { FileThumbnail } = await import('./FileThumbnail')

// jsdom has no IntersectionObserver, so useInView treats the row as in-view and
// the image query runs immediately — no observer stub needed. The thumbnail
// <img> is decorative (alt=""), so it has no `img` role — query the element.
function renderThumb(path: string, name: string) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const { container } = render(
    <QueryClientProvider client={queryClient}>
      <FileThumbnail path={path} name={name} />
    </QueryClientProvider>,
  )
  return container
}

afterEach(() => {
  getPreview.mockReset()
  vi.unstubAllGlobals()
})

describe('FileThumbnail', () => {
  it('fetches and shows a thumbnail for an image file', async () => {
    getPreview.mockResolvedValueOnce({ ok: true, bytes: new Int8Array([1, 2, 3]) })
    vi.stubGlobal('URL', {
      ...URL,
      createObjectURL: vi.fn(() => 'blob:t'),
      revokeObjectURL: vi.fn(),
    })

    const container = renderThumb('/Photos/a.png', 'a.png')

    await waitFor(() => expect(container.querySelector('img')).not.toBeNull())
    expect(container.querySelector('img')).toHaveAttribute('src', 'blob:t')
    expect(getPreview).toHaveBeenCalledWith('/Photos/a.png', 40, 40)
  })

  it('shows the glyph and skips the fetch for a non-image file', async () => {
    const container = renderThumb('/docs/notes.txt', 'notes.txt')
    // The query is disabled for non-images — nothing should fetch, no <img>.
    await waitFor(() => expect(getPreview).not.toHaveBeenCalled())
    expect(container.querySelector('img')).toBeNull()
    expect(container.querySelector('svg')).not.toBeNull()
  })

  it('falls back to the glyph when no thumbnail is available', async () => {
    getPreview.mockResolvedValueOnce({ ok: true, bytes: null })
    const container = renderThumb('/Photos/broken.png', 'broken.png')
    await waitFor(() => expect(getPreview).toHaveBeenCalled())
    expect(container.querySelector('img')).toBeNull()
  })
})
