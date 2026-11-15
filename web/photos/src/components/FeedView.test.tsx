import { act, fireEvent, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { proxy, keysetFeed, library, resetProxy } from '../test/proxyMock'
import { renderWithProviders } from '../test/render'
import { FEED_PAGE_SIZE } from '../lib/feedPager'
import { useState } from 'react'
import userEvent from '@testing-library/user-event'
import { TILE_TARGET, columnsFor, indexAtTop, layoutGrid, sectionsFor, topOfIndex } from '../lib/layout'

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

  it('the browser scrollbar is the one position indicator: no slider, nothing hidden, rows at full width', async () => {
    const { photos, times } = library(30)
    proxy.captureTimes.mockResolvedValue({ ok: true, times })
    proxy.feedPage.mockImplementation(keysetFeed(photos))
    renderWithProviders(<FeedView />)
    await waitFor(() => expect(screen.queryAllByTestId('tile').length).toBeGreaterThan(0))
    expect(screen.queryByRole('slider')).toBeNull()
    expect(screen.getByTestId('grid-scroller').className).not.toContain('scrollbar-width:none')
    // jsdom's fallback box is 960 wide: 6 columns of 158 px on a 160 px pitch fill it.
    const cells = [...screen.getAllByRole('row')[0]!.children] as HTMLElement[]
    const last = cells[cells.length - 1]!
    expect(Number.parseFloat(last.style.left) + Number.parseFloat((last.firstElementChild as HTMLElement).style.width)).toBe(958)
  })

  it('shows month headers from the capture-time index', async () => {
    const { photos, times } = library(30)
    proxy.captureTimes.mockResolvedValue({ ok: true, times })
    proxy.feedPage.mockImplementation(keysetFeed(photos))
    renderWithProviders(<FeedView />)
    const month = new Intl.DateTimeFormat('en', { month: 'long', year: 'numeric' }).format(times[0])
    expect(await screen.findByRole('heading', { name: month })).toBeInTheDocument()
  })

  it('the date bubble appears only while scrolling, names the date at the top, and fades after', async () => {
    const { photos, times } = library(FEED_PAGE_SIZE * 3)
    proxy.captureTimes.mockResolvedValue({ ok: true, times })
    proxy.feedPage.mockImplementation(keysetFeed(photos))
    renderWithProviders(<FeedView />)
    await screen.findByRole('button', { name: /p0\.jpg/ })
    const bubble = () => screen.getByTestId('date-bubble')
    // Opening the Library is not scrolling it.
    expect(bubble()).not.toHaveAttribute('data-visible')
    expect(bubble()).toHaveAttribute('aria-hidden', 'true')
    vi.useFakeTimers({ shouldAdvanceTime: true })
    await scrollTo(3_000)
    expect(bubble()).toHaveAttribute('data-visible')
    const month = new Intl.DateTimeFormat('en', { month: 'long', year: 'numeric' })
    const top = indexAtTop(layoutGrid(sectionsFor(times, 'months'), 960, columnsFor(960, TILE_TARGET.months)), 3_000)
    expect(top).toBeGreaterThan(0)
    expect(bubble().textContent).toBe(month.format(times[top]))
    await act(async () => {
      vi.advanceTimersByTime(1_100)
    })
    expect(bubble()).not.toHaveAttribute('data-visible')
  })
})

describe('FeedView — the size slider (web-apps-layout.md §4.10)', () => {
  function Sized({ density = 'days' as 'days' | 'months' | 'years', start = 5, mosaic = false }) {
    const [columns, setColumns] = useState(start)
    return <FeedView density={density} onDensity={() => undefined} columns={columns} onColumns={setColumns} mosaic={mosaic} onLayout={() => undefined} />
  }
  const perRow = () => (screen.getAllByRole('row')[0]!.children.length)

  it('draws its photos per row; + makes them larger (fewer), − smaller, and the top photograph stays', async () => {
    const { photos, times } = library(FEED_PAGE_SIZE * 3)
    proxy.captureTimes.mockResolvedValue({ ok: true, times })
    proxy.feedPage.mockImplementation(keysetFeed(photos))
    renderWithProviders(<Sized density="months" />)
    await screen.findByRole('button', { name: /p0\.jpg/ })
    expect(perRow()).toBe(5)
    expect(screen.getByRole('slider', { name: 'Photo size' })).toHaveAttribute('aria-valuetext', '5 photos per row')
    await scrollTo(1_500)
    const sections = sectionsFor(times, 'months')
    const topIndex = indexAtTop(layoutGrid(sections, 960, 5), 1_500)

    await userEvent.click(screen.getByRole('button', { name: 'Larger photos' }))
    expect(screen.getByRole('slider', { name: 'Photo size' })).toHaveAttribute('aria-valuetext', '4 photos per row')
    expect(screen.getByTestId('grid-scroller').scrollTop).toBe(topOfIndex(layoutGrid(sections, 960, 4), topIndex))
    await userEvent.click(screen.getByRole('button', { name: 'Smaller photos' }))
    await userEvent.click(screen.getByRole('button', { name: 'Smaller photos' }))
    expect(screen.getByRole('slider', { name: 'Photo size' })).toHaveAttribute('aria-valuetext', '6 photos per row')
  })

  it('a step that draws the same grid (Mosaic rounds 5 and 6 to six columns) leaves the scroll alone', async () => {
    const { photos, times } = library(FEED_PAGE_SIZE * 3)
    proxy.captureTimes.mockResolvedValue({ ok: true, times })
    proxy.feedPage.mockImplementation(keysetFeed(photos))
    renderWithProviders(<Sized density="months" start={6} mosaic />)
    await screen.findByRole('button', { name: /p0\.jpg/ })
    await scrollTo(1_537)
    await userEvent.click(screen.getByRole('button', { name: 'Larger photos' }))
    expect(screen.getByRole('slider', { name: 'Photo size' })).toHaveAttribute('aria-valuetext', '6 photos per row')
    expect(screen.getByTestId('grid-scroller').scrollTop).toBe(1_537)
  })

  it('the slider runs small → large left to right, and its ends disable − and +', async () => {
    const { photos, times } = library(30)
    proxy.captureTimes.mockResolvedValue({ ok: true, times })
    proxy.feedPage.mockImplementation(keysetFeed(photos))
    renderWithProviders(<Sized />)
    await screen.findByRole('button', { name: /p0\.jpg/ })
    const slider = screen.getByRole('slider', { name: 'Photo size' })
    fireEvent.change(slider, { target: { value: '0' } })
    expect(slider).toHaveAttribute('aria-valuetext', '8 photos per row')
    expect(screen.getByRole('button', { name: 'Smaller photos' })).toBeDisabled()
    await waitFor(() => expect(perRow()).toBe(8))
    fireEvent.change(slider, { target: { value: '5' } })
    expect(slider).toHaveAttribute('aria-valuetext', '3 photos per row')
    expect(screen.getByRole('button', { name: 'Larger photos' })).toBeDisabled()
    await waitFor(() => expect(perRow()).toBe(3))
  })

  it('Years keeps its contact sheet: the slider is off and does not size it', async () => {
    const { photos, times } = library(FEED_PAGE_SIZE)
    proxy.captureTimes.mockResolvedValue({ ok: true, times })
    proxy.feedPage.mockImplementation(keysetFeed(photos))
    renderWithProviders(<Sized density="years" start={3} />)
    await screen.findByRole('button', { name: /p0\.jpg/ })
    expect(screen.getByRole('slider', { name: 'Photo size' })).toBeDisabled()
    expect(perRow()).toBe(columnsFor(960, TILE_TARGET.years))
  })
})

describe('FeedView — the layout switches (web-apps-layout.md §4.9)', () => {
  function Switchable({ density = 'months' as const, fit: fitAt = false }: { density?: 'days' | 'months' | 'years'; fit?: boolean }) {
    const [layout, setLayout] = useState({ mosaic: false, fit: fitAt })
    return <FeedView density={density} onDensity={() => undefined} mosaic={layout.mosaic} fit={layout.fit} onLayout={(mosaic, fit) => setLayout({ mosaic, fit })} />
  }
  const heroes = () =>
    screen.getAllByTestId('tile').filter((el) => {
      const box = el.closest('[data-tile]') as HTMLElement
      return Number.parseFloat(box.style.width) > 300
    })

  it('Mosaic draws some photographs over 2×2 cells, and keeps the same photograph at the top', async () => {
    const { photos, times } = library(FEED_PAGE_SIZE * 3)
    proxy.captureTimes.mockResolvedValue({ ok: true, times })
    proxy.feedPage.mockImplementation(keysetFeed(photos))
    renderWithProviders(<Switchable />)
    await screen.findByRole('button', { name: /p0\.jpg/ })
    expect(heroes()).toHaveLength(0)
    await scrollTo(1_500)
    const sections = sectionsFor(times, 'months')
    const plain = layoutGrid(sections, 960, columnsFor(960, TILE_TARGET.months))
    const topIndex = indexAtTop(plain, 1_500)

    await userEvent.click(screen.getByRole('button', { name: 'Mosaic', pressed: false }))
    expect(screen.getByRole('button', { name: 'Mosaic' })).toHaveAttribute('aria-pressed', 'true')
    await waitFor(() => expect(heroes().length).toBeGreaterThan(0))
    const mosaic = layoutGrid(sections, 960, columnsFor(960, TILE_TARGET.months, true), true, true)
    expect(screen.getByTestId('grid-scroller').scrollTop).toBe(topOfIndex(mosaic, topIndex))
    // And back: still the same photograph's row, not the top of the library.
    await userEvent.click(screen.getByRole('button', { name: 'Mosaic', pressed: true }))
    await waitFor(() => expect(heroes()).toHaveLength(0))
    expect(screen.getByTestId('grid-scroller').scrollTop).toBe(topOfIndex(plain, indexAtTop(mosaic, topOfIndex(mosaic, topIndex))))
    expect(screen.getByTestId('grid-scroller').scrollTop).toBeGreaterThan(0)
  })

  it('Mosaic does not apply by year: the switch is off and no photograph is drawn large', async () => {
    const { photos, times } = library(FEED_PAGE_SIZE)
    proxy.captureTimes.mockResolvedValue({ ok: true, times })
    proxy.feedPage.mockImplementation(keysetFeed(photos))
    renderWithProviders(<FeedView density="years" onDensity={() => undefined} mosaic fit={false} onLayout={() => undefined} />)
    await screen.findByRole('button', { name: /p0\.jpg/ })
    const button = screen.getByRole('button', { name: 'Mosaic' })
    expect(button).toBeDisabled()
    expect(button).toHaveAttribute('aria-pressed', 'false')
    const sizes = new Set(screen.getAllByTestId('tile').map((el) => (el.closest('[data-tile]') as HTMLElement).style.width))
    expect(sizes.size).toBe(1)
  })

  it('Whole photos draws each picture uncropped in its square, and switches back', async () => {
    const { photos, times } = library(6)
    proxy.captureTimes.mockResolvedValue({ ok: true, times })
    proxy.feedPage.mockImplementation(keysetFeed(photos))
    proxy.getPreview.mockImplementation(async () => ({ ok: true, bytes: new Int8Array([0xff, 0xd8, 0xff, 0]) }))
    const { container } = renderWithProviders(<Switchable />)
    await waitFor(() => expect(container.querySelector('[data-tile] img')).not.toBeNull())
    expect(container.querySelector('[data-tile] img')!.className).toContain('object-cover')
    await userEvent.click(screen.getByRole('button', { name: 'Whole photos', pressed: false }))
    const img = container.querySelector('[data-tile] img') as HTMLElement
    expect(img.className).toContain('object-contain')
    // Inset, so two whole photographs side by side never touch.
    expect(Number.parseFloat(img.style.padding)).toBeGreaterThan(0)
    await userEvent.click(screen.getByRole('button', { name: 'Whole photos', pressed: true }))
    expect(container.querySelector('[data-tile] img')!.className).toContain('object-cover')
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
