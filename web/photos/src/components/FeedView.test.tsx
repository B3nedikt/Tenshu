import { act, fireEvent, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { proxy, keysetFeed, library, resetProxy } from '../test/proxyMock'
import { renderWithProviders } from '../test/render'
import { FEED_PAGE_SIZE } from '../lib/feedPager'

vi.mock('../lib/proxy', async () => (await import('../test/proxyMock')).proxyModule)
const { FeedView } = await import('./FeedView')

let minted = 0
let revoked = 0
beforeEach(() => {
  resetProxy()
  minted = 0
  revoked = 0
  URL.createObjectURL = vi.fn(() => `blob:${++minted}`)
  URL.revokeObjectURL = vi.fn(() => {
    revoked += 1
  })
})
afterEach(() => vi.useRealTimers())

function tileLabels() {
  return screen.queryAllByTestId('tile').map((el) => el.getAttribute('aria-label'))
}

async function scrollTo(top: number) {
  const scroller = screen.getByTestId('grid-scroller')
  await act(async () => {
    scroller.scrollTop = top
    fireEvent.scroll(scroller)
  })
}

describe('FeedView — paging', () => {
  it('draws the first page, asks for the next cursor as the grid scrolls, and never repeats a row', async () => {
    const { photos, times } = library(FEED_PAGE_SIZE * 2 + 10)
    proxy.captureTimes.mockResolvedValue({ ok: true, times })
    proxy.feedPage.mockImplementation(keysetFeed(photos))
    renderWithProviders(<FeedView />)

    expect(await screen.findByRole('button', { name: /p0\.jpg/ })).toBeInTheDocument()
    expect(proxy.feedPage).toHaveBeenCalledTimes(1)
    expect(proxy.feedPage.mock.calls[0]).toEqual([null, FEED_PAGE_SIZE])

    // Deep into the second page's rows.
    await scrollTo(10_000)
    await waitFor(() => expect(proxy.feedPage).toHaveBeenCalledTimes(2))
    const last = photos[FEED_PAGE_SIZE - 1]!
    expect(proxy.feedPage.mock.calls[1]).toEqual([`v2.${last.capturedAtMs}.${last.path}`, FEED_PAGE_SIZE])

    await waitFor(() => expect(tileLabels().length).toBeGreaterThan(0))
    const labels = tileLabels()
    expect(new Set(labels).size).toBe(labels.length)
  })

  it('shows month headers from the capture-time index', async () => {
    const { photos, times } = library(30)
    proxy.captureTimes.mockResolvedValue({ ok: true, times })
    proxy.feedPage.mockImplementation(keysetFeed(photos))
    renderWithProviders(<FeedView />)
    const month = new Intl.DateTimeFormat('en', { month: 'long', year: 'numeric' }).format(times[0])
    expect(await screen.findByRole('heading', { name: month })).toBeInTheDocument()
    expect(screen.getByRole('slider', { name: 'Jump to a date' })).toBeInTheDocument()
  })

  it('the scrubber seeks: a keyboard End jumps to the last photograph and pages it in', async () => {
    const { photos, times } = library(FEED_PAGE_SIZE * 3)
    proxy.captureTimes.mockResolvedValue({ ok: true, times })
    proxy.feedPage.mockImplementation(keysetFeed(photos))
    renderWithProviders(<FeedView />)
    const slider = await screen.findByRole('slider')
    fireEvent.keyDown(slider, { key: 'End' })
    await waitFor(() => expect(screen.getByRole('button', { name: new RegExp(`p${photos.length - 1}\\.jpg`) })).toBeInTheDocument())
    expect(proxy.feedPage).toHaveBeenCalledTimes(3)
  })
})

describe('FeedView — the memory bound', () => {
  it('a 10k-photo library scrolled end to end mounts a few screens of tiles and holds a bounded set of blobs', async () => {
    const { photos, times } = library(10_000)
    proxy.captureTimes.mockResolvedValue({ ok: true, times })
    proxy.feedPage.mockImplementation(keysetFeed(photos))
    proxy.getPreview.mockImplementation(async () => ({ ok: true, bytes: new Int8Array([0xff, 0xd8, 0xff, 0]) }))
    const { cache } = renderWithProviders(<FeedView />)
    await screen.findByRole('button', { name: /p0\.jpg/ })

    const scroller = screen.getByTestId('grid-scroller')
    const height = Number.parseFloat((scroller.firstElementChild as HTMLElement).style.height)
    let peakTiles = 0
    let peakLive = 0
    for (let top = 0; top < height; top += 4_000) {
      await scrollTo(top)
      await act(async () => {
        await new Promise((r) => setTimeout(r, 0))
      })
      peakTiles = Math.max(peakTiles, screen.queryAllByTestId('tile').length)
      peakLive = Math.max(peakLive, minted - revoked)
    }
    expect(peakTiles).toBeLessThan(150)
    // The visible tiles plus the retained LRU (150), never the library.
    expect(peakLive).toBeLessThanOrEqual(peakTiles + 150)
    expect(cache.stats().live).toBeLessThanOrEqual(peakTiles + 150)
    expect(minted).toBeGreaterThan(0)
  }, 60_000)
})

describe('FeedView — errors render by kind, never as an empty library', () => {
  it('a typed failure of the index', async () => {
    proxy.captureTimes.mockResolvedValue({ ok: false, error: { kind: 'error_photos_bad_page_size' } })
    renderWithProviders(<FeedView />)
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveAttribute('data-kind', 'error_photos_bad_page_size')
    expect(screen.queryByText(/No photos yet/)).toBeNull()
  })

  it('an unknown kind falls back to the generic copy, never the raw key', async () => {
    proxy.captureTimes.mockResolvedValue({ ok: false, error: { kind: 'error_from_the_future' } })
    renderWithProviders(<FeedView />)
    expect(await screen.findByText('Something went wrong. Please try again.')).toBeInTheDocument()
    expect(screen.queryByText(/error_from_the_future/)).toBeNull()
  })

  it('a rejected bridge call', async () => {
    proxy.captureTimes.mockRejectedValue(new Error('not permitted: photo.captureTimes'))
    renderWithProviders(<FeedView />)
    expect(await screen.findByText('Photos is not allowed to do that.')).toBeInTheDocument()
  })

  it('a failed page keeps the rows it has and offers a retry from the same cursor', async () => {
    const { photos, times } = library(10)
    proxy.captureTimes.mockResolvedValue({ ok: true, times })
    proxy.feedPage.mockResolvedValueOnce({ ok: false, error: { kind: 'error_photos_bad_cursor' } }).mockImplementation(keysetFeed(photos))
    renderWithProviders(<FeedView />)
    expect(await screen.findByText('The library changed while loading. Refresh to start again.')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByRole('button', { name: /p0\.jpg/ })).toBeInTheDocument()
    expect(proxy.feedPage.mock.calls.map(([c]) => c)).toEqual([null, null])
  })

  it('an empty library says so — only after the read succeeded', async () => {
    proxy.captureTimes.mockResolvedValue({ ok: true, times: [] })
    renderWithProviders(<FeedView />)
    expect(await screen.findByText(/No photos yet/)).toBeInTheDocument()
  })
})
